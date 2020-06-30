/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2016 Tobias Pietzsch, Stephan Saalfeld, Stephan Preibisch,
 * Jean-Yves Tinevez, HongKee Moon, Johannes Schindelin, Curtis Rueden, John Bogovic
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.viewer.render;

import bdv.cache.CacheControl;
import bdv.util.MovingAverage;
import bdv.viewer.ViewerState;
import java.util.concurrent.ExecutorService;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.iotiming.CacheIoTiming;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.RenderTarget;
import net.imglib2.util.Intervals;

/**
 * A renderer that uses a coarse-to-fine rendering scheme. First, a small target
 * image at a fraction of the canvas resolution is rendered. Then, increasingly
 * larger images are rendered, until the full canvas resolution is reached.
 * <p>
 * When drawing the low-resolution target images to the screen, they will be
 * scaled up by Java2D (or JavaFX, etc) to the full canvas size, which is
 * relatively fast. Rendering the small, low-resolution images is usually very
 * fast, such that the display is very interactive while the user changes the
 * viewing transformation for example. When the transformation remains fixed for
 * a longer period, higher-resolution details are filled in successively.
 * <p>
 * The renderer allocates a {@code RenderResult} for each of a predefined set of
 * <em>screen scales</em> (a screen scale of 1 means that 1 pixel in the screen
 * image is displayed as 1 pixel on the canvas, a screen scale of 0.5 means 1
 * pixel in the screen image is displayed as 2 pixel on the canvas, etc.)
 * <p>
 * At any time, one of these screen scales is selected as the <em>highest screen
 * scale</em>. Rendering starts with this highest screen scale and then proceeds
 * to lower screen scales (higher resolution images). Unless the highest screen
 * scale is currently rendering, {@link #requestRepaint() repaint request} will
 * cancel rendering, such that display remains interactive.
 * <p>
 * The renderer tries to maintain a per-frame rendering time close to a desired
 * number of {@code targetRenderNanos} nanoseconds. If the rendering time (in
 * nanoseconds) for the (currently) highest scaled screen image is above this
 * threshold, a coarser screen scale is chosen as the highest screen scale to
 * use. Similarly, if the rendering time for the (currently) second-highest
 * scaled screen image is below this threshold, this finer screen scale chosen
 * as the highest screen scale to use.
 * <p>
 * The renderer uses multiple threads (if desired).
 * <p>
 * The renderer supports rendering of {@link Volatile} sources. In each
 * rendering pass, all currently valid data for the best fitting mipmap level
 * and all coarser levels is rendered to a temporary image for each visible
 * source. Then the temporary images are combined to the final image for
 * display. The number of passes required until all data is valid might differ
 * between visible sources.
 * <p>
 * Rendering timing is tied to a {@link CacheControl} control for IO budgeting,
 * etc.
 *
 * @author Tobias Pietzsch
 */
public class MultiResolutionRenderer
{
	/**
	 * Receiver for the {@code BufferedImage BufferedImages} that we render.
	 */
	private final RenderTarget< ? > display;

	/**
	 * Thread that triggers repainting of the display.
	 * Requests for repainting are send there.
	 */
	private final PainterThread painterThread;

	/**
	 * Currently active projector, used to re-paint the display. It maps the
	 * source data to {@code ©screenImages}.
	 */
	private VolatileProjector projector;

	/**
	 * Scale factors from the {@link #display viewer canvas} to the
	 * {@code screenImages}.
	 *
	 * A scale factor of 1 means 1 pixel in the screen image is displayed as 1
	 * pixel on the canvas, a scale factor of 0.5 means 1 pixel in the screen
	 * image is displayed as 2 pixel on the canvas, etc.
	 */
	private final double[] screenScaleFactors;

	static class ScreenScale
	{
		/**
		 * The width of the target image at this ScreenScale.
		 */
		final int w;

		/**
		 * The height of the target image at this ScreenScale.
		 */
		final int h;

		/**
		 * The transformation from viewer to target image coordinates at this ScreenScale.
		 */
		final AffineTransform3D scale;

		public ScreenScale( final int screenW, final int screenH, final double screenToViewerScale )
		{
			w = ( int ) Math.ceil( screenToViewerScale * screenW );
			h = ( int ) Math.ceil( screenToViewerScale * screenH );
			scale = new AffineTransform3D();
			scale.set( screenToViewerScale, 0, 0 );
			scale.set( screenToViewerScale, 1, 1 );
			scale.set( 0.5 * screenToViewerScale - 0.5, 0, 3 );
			scale.set( 0.5 * screenToViewerScale - 0.5, 1, 3 );
		}
	}

	private final ScreenScale[] screenScales;

	private int screenW;

	private int screenH;

	private final RenderStorage renderStorage;

	/**
	 * Target rendering time in nanoseconds. The rendering time for the coarsest
	 * rendered scale should be below this threshold. After the coarsest scale,
	 * increasingly finer scales are rendered, but these render passes may be
	 * canceled (while the coarsest may not).
	 */
	private final long targetRenderNanos;

	/**
	 * Estimate of the time it takes to render one screen pixel from one source,
	 * in nanoseconds.
	 */
	private final MovingAverage renderNanosPerPixelAndSource;

	/**
	 * The index of the screen scale of the {@link #projector current
	 * projector}.
	 */
	private int currentScreenScaleIndex;

	/**
	 * The index of the screen scale which should be rendered next.
	 */
	private int requestedScreenScaleIndex;

	/**
	 * The index of the coarsest screen scale currently used.
	 * Rendering at finer screen scales may be cancelled.
	 */
	private int maxScreenScaleIndex;

	/**
	 * Whether the current rendering operation may be cancelled (to start a new
	 * one). Rendering may be cancelled unless we are rendering at the
	 * {@link #maxScreenScaleIndex coarsest screen scale}.
	 */
	private volatile boolean renderingMayBeCancelled;

	/**
	 * Snapshot of the ViewerState that is currently being rendered.
	 */
	private ViewerState currentViewerState;

	/**
	 * How many sources are visible in {@link #currentViewerState}.
	 */
	private int currentNumVisibleSources;

	/**
	 * Creates projectors for rendering current {@code ViewerState} to a
	 * {@code screenImage}.
	 */
	private final ProjectorFactory projectorFactory;

	/**
	 * Controls IO budgeting and fetcher queue.
	 */
	private final CacheControl cacheControl;

	/**
	 * Whether a repaint was {@link #requestRepaint() requested}. This will
	 * cause {@link CacheControl#prepareNextFrame()}.
	 */
	private boolean newFrameRequest;

	// TODO: should be settable
	private final long[] iobudget = new long[] { 100l * 1000000l,  10l * 1000000l };

	/**
	 * @param display
	 *            The canvas that will display the images we render.
	 * @param painterThread
	 *            Thread that triggers repainting of the display. Requests for
	 *            repainting are send there.
	 * @param screenScaleFactors
	 *            Scale factors from the viewer canvas to screen images of
	 *            different resolutions. A scale factor of 1 means 1 pixel in
	 *            the screen image is displayed as 1 pixel on the canvas, a
	 *            scale factor of 0.5 means 1 pixel in the screen image is
	 *            displayed as 2 pixel on the canvas, etc.
	 * @param targetRenderNanos
	 *            Target rendering time in nanoseconds. The rendering time for
	 *            the coarsest rendered scale should be below this threshold.
	 * @param numRenderingThreads
	 *            How many threads to use for rendering.
	 * @param renderingExecutorService
	 *            if non-null, this is used for rendering. Note, that it is
	 *            still important to supply the numRenderingThreads parameter,
	 *            because that is used to determine into how many sub-tasks
	 *            rendering is split.
	 * @param useVolatileIfAvailable
	 *            whether volatile versions of sources should be used if
	 *            available.
	 * @param accumulateProjectorFactory
	 *            can be used to customize how sources are combined.
	 * @param cacheControl
	 *            the cache controls IO budgeting and fetcher queue.
	 */
	public MultiResolutionRenderer(
			final RenderTarget< ? > display,
			final PainterThread painterThread,
			final double[] screenScaleFactors,
			final long targetRenderNanos,
			final int numRenderingThreads,
			final ExecutorService renderingExecutorService,
			final boolean useVolatileIfAvailable,
			final AccumulateProjectorFactory< ARGBType > accumulateProjectorFactory,
			final CacheControl cacheControl )
	{
		this.display = display;
		this.painterThread = painterThread;
		projector = null;
		currentScreenScaleIndex = -1;
		this.screenScaleFactors = screenScaleFactors.clone();
		this.screenScales = new ScreenScale[ screenScaleFactors.length ];
		renderStorage = new RenderStorage();

		this.targetRenderNanos = targetRenderNanos;
		renderNanosPerPixelAndSource = new MovingAverage( 3 );
		renderNanosPerPixelAndSource.init( 500 );

		maxScreenScaleIndex = screenScales.length - 1;
		requestedScreenScaleIndex = maxScreenScaleIndex;
		renderingMayBeCancelled = false;
		this.cacheControl = cacheControl;
		newFrameRequest = false;

		projectorFactory = new ProjectorFactory(
				numRenderingThreads,
				renderingExecutorService,
				useVolatileIfAvailable,
				accumulateProjectorFactory );
	}

	/**
	 * Check whether the size of the display component was changed and
	 * recreate {@link #screenScales} accordingly.
	 *
	 * @return whether the size was changed.
	 */
	private boolean checkResize()
	{
		final int newScreenW = display.getWidth();
		final int newScreenH = display.getHeight();
		if ( newScreenW != screenW || newScreenH != screenH )
		{
			screenW = newScreenW;
			screenH = newScreenH;
			for ( int i = 0; i < screenScales.length; ++i )
				screenScales[ i ] = new ScreenScale( screenW, screenH, screenScaleFactors[ i ] );
			return true;
		}
		return false;
	}

	/**
	 * Render image at the {@link #requestedScreenScaleIndex requested screen
	 * scale}.
	 */
	public boolean paint( final ViewerState viewerState )
	{
		if ( display.getWidth() <= 0 || display.getHeight() <= 0 )
			return false;

		final boolean resized = checkResize();
		final boolean newFrame;
		synchronized ( this )
		{
			if ( intervalMode )
			{
				paintInterval();
//				return true;
			}

			newFrame = newFrameRequest || resized;
			newFrameRequest = false;
		}

		if ( newFrame )
		{
			cacheControl.prepareNextFrame();
			currentViewerState = viewerState.snapshot();
			currentNumVisibleSources = currentViewerState.getVisibleAndPresentSources().size();
			maxScreenScaleIndex = suggestScreenScale( new FinalDimensions( screenW, screenH ), currentNumVisibleSources );
			requestedScreenScaleIndex = maxScreenScaleIndex;
		}

		final boolean createProjector = newFrame || ( requestedScreenScaleIndex != currentScreenScaleIndex );

		// the projector that paints to the screenImage.
		final VolatileProjector p;

		// TODO make field (for notifying of updates) ???
		RenderResult renderResult = null;

		// if creating a projector
		boolean requestNewFrameIfIncomplete = false;

		synchronized ( this )
		{
			if ( createProjector )
			{
				final ScreenScale screenScale = screenScales[ requestedScreenScaleIndex ];

				renderResult = display.getReusableRenderResult();
				renderResult.init( screenScale.w, screenScale.h );

				renderStorage.checkRenewData( screenScales[ 0 ].w, screenScales[ 0 ].h, currentNumVisibleSources );
				projector = createProjector( currentViewerState, requestedScreenScaleIndex, renderResult.getScreenImage() );
				requestNewFrameIfIncomplete = projectorFactory.requestNewFrameIfIncomplete();
				renderingMayBeCancelled = ( requestedScreenScaleIndex < maxScreenScaleIndex );
			}
			p = projector;
		}

		// try rendering
		final boolean success = p.map( createProjector );
		final long rendertime = p.getLastFrameRenderNanoTime();

		synchronized ( this )
		{
			// if rendering was not cancelled...
			if ( success )
			{
				if ( createProjector )
				{
					currentScreenScaleIndex = requestedScreenScaleIndex;
					currentViewerState.getViewerTransform( renderResult.getViewerTransform() );
					renderResult.setScaleFactor( screenScaleFactors[ currentScreenScaleIndex ] );
					// TODO renderResult.setUpdated();
					( ( RenderTarget ) display ).setRenderResult( renderResult );
					currentRenderResult = renderResult;

					if ( currentNumVisibleSources > 0 )
					{
						final int numRenderPixels = ( int ) Intervals.numElements( renderResult.getScreenImage() ) * currentNumVisibleSources;
						renderNanosPerPixelAndSource.add( rendertime / ( double ) numRenderPixels );
					}
				}
				// TODO else renderResult.setUpdated();

				if ( currentScreenScaleIndex > 0 )
					requestRepaint( currentScreenScaleIndex - 1 );
				else if ( !p.isValid() )
				{
					try
					{
						Thread.sleep( 1 );
					}
					catch ( final InterruptedException e )
					{
						// restore interrupted state
						Thread.currentThread().interrupt();
					}
					if( requestNewFrameIfIncomplete )
						requestRepaint();
					else
						requestRepaint( currentScreenScaleIndex );
				}
			}
		}

		return success;
	}

	/**
	 * Request a repaint of the display from the painter thread. The painter
	 * thread will trigger a {@link #paint} as soon as possible (that is,
	 * immediately or after the currently running {@link #paint} has completed).
	 */
	public synchronized void requestRepaint()
	{
		if ( renderingMayBeCancelled && projector != null )
			projector.cancel();
		newFrameRequest = true;
				requestedScreenInterval = null;
				intervalMode = false;
		painterThread.requestRepaint();
	}

	/**
	 * Request a repaint of the display from the painter thread, setting
	 * {@code requestedScreenScaleIndex} to the specified
	 * {@code screenScaleIndex}. This is used to repaint the
	 * {@code currentViewerState} in a loop, until everything is painted at
	 * highest resolution from valid data (or painting is interrupted by a new
	 * "real" {@link #requestRepaint()}.
	 */
	private void requestRepaint( final int screenScaleIndex )
	{
		requestedScreenScaleIndex = screenScaleIndex;
		painterThread.requestRepaint();
	}

	private int suggestScreenScale( final Dimensions screenSize, final int numSources )
	{
		final double intervalRenderNanos = renderNanosPerPixelAndSource.getAverage() * Intervals.numElements( screenSize ) * numSources;
		for ( int i = 0; i < screenScaleFactors.length - 1; i++ )
		{
			final double s = screenScaleFactors[ i ];
			final double renderTime = intervalRenderNanos * s * s;
			if ( renderTime <= targetRenderNanos )
				return i;
		}
		return screenScaleFactors.length - 1;
	}


	/**
	 * DON'T USE THIS.
	 * <p>
	 * This is a work around for JDK bug
	 * https://bugs.openjdk.java.net/browse/JDK-8029147 which leads to
	 * ViewerPanel not being garbage-collected when ViewerFrame is closed. So
	 * instead we need to manually let go of resources...
	 */
	public void kill()
	{
		projector = null;
		renderStorage.clear();
	}

	private VolatileProjector createProjector(
			final ViewerState viewerState,
			final int screenScaleIndex,
			final RandomAccessibleInterval< ARGBType > screenImage )
	{
		/*
		 * This shouldn't be necessary, with
		 * CacheHints.LoadingStrategy==VOLATILE
		 */
//		CacheIoTiming.getIoTimeBudget().clear(); // clear time budget such that prefetching doesn't wait for loading blocks.

		final AffineTransform3D screenScaleTransform = screenScales[ screenScaleIndex ].scale;
		final AffineTransform3D screenTransform = viewerState.getViewerTransform();
		screenTransform.preConcatenate( screenScaleTransform );

		final VolatileProjector projector = projectorFactory.createProjector(
				viewerState,
				screenImage,
				screenTransform,
				renderStorage );
		CacheIoTiming.getIoTimeBudget().reset( iobudget );
		return projector;
	}





	// =========== intervals =============

	private Interval requestedScreenInterval;
	private boolean intervalMode;
	private RenderResult currentRenderResult;

	private void paintInterval()
	{
		final double targetScale = screenScaleFactors[ currentScreenScaleIndex ];

		final int intervalScaleIndex = screenScales.length - 1;
		final double intervalScale = screenScaleFactors[ intervalScaleIndex ];
//		Intervals.intersect( new FinalInterval( clipW, clipH ), Intervals.smallestContainingInterval( Intervals.scale( requestedScreenInterval, scale ) ) );

		final Interval interval = scaleScreenInterval( requestedScreenInterval, intervalScaleIndex );
		final Interval targetInterval = scaleScreenInterval( requestedScreenInterval, currentScreenScaleIndex );

		final BufferedImageRenderResult intervalResult = ( BufferedImageRenderResult ) display.getReusableRenderResult();
		intervalResult.init(
				( int ) interval.dimension( 0 ),
				( int ) interval.dimension( 1 ) );
		final double offsetX = interval.min( 0 );
		final double offsetY = interval.min( 1 );
		final VolatileProjector p = createProjector( currentViewerState, intervalScaleIndex, intervalResult.getScreenImage(), offsetX, offsetY );
		final boolean success = p.map();

		// NEXT: blend screenImage into currentRenderResult
		final double relativeScale = targetScale / intervalScale;
		final double tx = offsetX * relativeScale - targetInterval.min( 0 );
		final double ty = offsetY * relativeScale - targetInterval.min( 1 );
		( ( BufferedImageRenderResult ) currentRenderResult ).compose( intervalResult, targetInterval, tx, ty, relativeScale );


//		final RandomAccessibleInterval< ARGBType > screenImage = Views.translate( intervalResult.getScreenImage(), 0, 0 ); // TODO
//		projector = createProjector( currentViewerState, screenImage );

//		currentRenderResult
//		currentScreenScaleIndex
//		currentViewerState
//		currentNumVisibleSources
//		maxScreenScaleIndex


	}

	private Interval scaleScreenInterval( final Interval requestedScreenInterval, final int intervalScaleIndex )
	{
		final int clipW = screenScales[ intervalScaleIndex ].w;
		final int clipH = screenScales[ intervalScaleIndex ].h;
		final double scale = screenScaleFactors[ intervalScaleIndex ];
		return Intervals.createMinMax(
				Math.max( 0, ( int ) Math.floor( requestedScreenInterval.min( 0 ) * scale ) ),
				Math.max( 0, ( int ) Math.floor( requestedScreenInterval.min( 1 ) * scale ) ),
				Math.min( clipW - 1, ( int ) Math.ceil( requestedScreenInterval.max( 0 ) * scale ) ),
				Math.min( clipH - 1, ( int ) Math.ceil( requestedScreenInterval.max( 1 ) * scale ) )
		);
	}



	private VolatileProjector createProjector(
			final ViewerState viewerState,
			final int screenScaleIndex,
			final RandomAccessibleInterval< ARGBType > screenImage,
			final double offsetX,
			final double offsetY )
	{
		/*
		 * This shouldn't be necessary, with
		 * CacheHints.LoadingStrategy==VOLATILE
		 */
//		CacheIoTiming.getIoTimeBudget().clear(); // clear time budget such that prefetching doesn't wait for loading blocks.

		final AffineTransform3D screenScaleTransform = screenScales[ screenScaleIndex ].scale;
		final AffineTransform3D screenTransform = viewerState.getViewerTransform();
		screenTransform.preConcatenate( screenScaleTransform );
		screenTransform.translate( -offsetX, -offsetY, 0 );

		final VolatileProjector projector = projectorFactory.createProjector(
				viewerState,
				screenImage,
				screenTransform,
				renderStorage );
		CacheIoTiming.getIoTimeBudget().reset( iobudget );
		return projector;
	}

	public synchronized void requestRepaint( final Interval screenInterval )
	{
		if ( renderingMayBeCancelled )
		{
			if ( projector != null )
				projector.cancel();
//			requestedScreenInterval = screenInterval;
//			intervalMode = true;
//			painterThread.requestRepaint();
//		}
//		else
//		{
//			requestRepaint();
		}

		requestedScreenInterval = screenInterval;
		intervalMode = true;
		painterThread.requestRepaint();

	}
}
