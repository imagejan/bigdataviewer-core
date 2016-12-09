package bdv.tools.bookmarks.bookmark;

import net.imglib2.realtransform.AffineTransform3D;

public class KeyFrame {

	private int timepoint;
	private AffineTransform3D transform;

	public KeyFrame(int timepoint, AffineTransform3D transform) {
		this.timepoint = timepoint;
		this.transform = transform;
	}

	public int getTimepoint() {
		return timepoint;
	}

	public void setTimepoint(int timepoint) {
		this.timepoint = timepoint;
	}

	public AffineTransform3D getTransform() {
		return transform;
	}

	public void setTransform(AffineTransform3D transform) {
		this.transform = transform;
	}
}
