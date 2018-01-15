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
package net.imglib2.type.numeric.integer;

import net.imglib2.Volatile;
import net.imglib2.img.NativeImg;
import net.imglib2.img.basictypeaccess.volatiles.VolatileShortAccess;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.type.PrimitiveTypeInfo;
import net.imglib2.type.numeric.AbstractVolatileNativeRealType;

/**
 * A {@link Volatile} variant of {@link UnsignedShortType}. It uses an
 * underlying {@link UnsignedShortType} that maps into a
 * {@link VolatileShortAccess}.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class VolatileUnsignedShortType extends AbstractVolatileNativeRealType< UnsignedShortType, VolatileUnsignedShortType >
{
	final protected NativeImg< ?, ? extends VolatileShortAccess > img;

	// this is the constructor if you want it to read from an array
	public VolatileUnsignedShortType( final NativeImg< ?, ? extends VolatileShortAccess > img )
	{
		super( new UnsignedShortType( img ), false );
		this.img = img;
	}

	// this is the constructor if you want to specify the dataAccess
	public VolatileUnsignedShortType( final VolatileShortAccess access )
	{
		super( new UnsignedShortType( access ), access.isValid() );
		this.img = null;
	}

	// this is the constructor if you want it to be a variable
	public VolatileUnsignedShortType( final int value )
	{
		this( new VolatileShortArray( 1, true ) );
		set( value );
	}

	// this is the constructor if you want it to be a variable
	public VolatileUnsignedShortType()
	{
		this( 0 );
	}

	public void set( final int value )
	{
		get().set( value );
	}

	@Override
	public void updateContainer( final Object c )
	{
		final VolatileShortAccess a = img.update( c );
		t.dataAccess = a;
		setValid( a.isValid() );
	}

	@Override
	public VolatileUnsignedShortType duplicateTypeOnSameNativeImg()
	{
		return new VolatileUnsignedShortType( img );
	}

	@Override
	public VolatileUnsignedShortType createVariable()
	{
		return new VolatileUnsignedShortType();
	}

	@Override
	public VolatileUnsignedShortType copy()
	{
		final VolatileUnsignedShortType v = createVariable();
		v.set( this );
		return v;
	}

	private static final PrimitiveTypeInfo< VolatileUnsignedShortType, VolatileShortAccess > info = PrimitiveTypeInfo.SHORT( img -> new VolatileUnsignedShortType( img ) );

	@Override
	public PrimitiveTypeInfo< VolatileUnsignedShortType, ? > getPrimitiveTypeInfo()
	{
		return info;
	}
}