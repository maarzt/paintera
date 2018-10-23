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
package bdv.fx.viewer;

import bdv.cache.CacheControl;
import bdv.fx.viewer.render.OverlayRendererGeneric;
import bdv.fx.viewer.render.RenderUnit;
import bdv.viewer.Interpolation;
import bdv.viewer.RequestRepaint;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import gnu.trove.list.array.TIntArrayList;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import net.imglib2.Point;
import net.imglib2.Positionable;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.ui.TransformListener;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.paintera.data.axisorder.AxisOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Philipp Hanslovsky
 */
public class ViewerPanelFX
		extends BorderPane
		implements OverlayRendererGeneric<GraphicsContext>,
		           TransformListener<AffineTransform3D>,
		           RequestRepaint
{

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final RenderUnit renderUnit;

	private final SingleChildStackPane display = new SingleChildStackPane(1, 1);

	private final OverlayPane overlayPane = new OverlayPane();

	private final StackPane center = new StackPane(display, overlayPane);
	/**
	 * Currently rendered state (visible sources, transformation, timepoint, etc.) A copy can be obtained by {@link
	 * #getState()}.
	 */
	protected final ViewerState state;

	/**
	 * Transformation set by the interactive viewer.
	 */
	protected final AffineTransform3D viewerTransform;

	protected ThreadGroup threadGroup;

	/**
	 * The {@link ExecutorService} used for rendereing.
	 */
	protected final PriorityExecutorService renderingExecutorService;

	/**
	 * These listeners will be notified about changes to the {@link #viewerTransform}. This is done <em>before</em>
	 * calling {@link #requestRepaint()} so listeners have the chance to interfere.
	 */
	protected final CopyOnWriteArrayList<TransformListener<AffineTransform3D>> transformListeners;

	/**
	 * These listeners will be notified about changes to the {@link #viewerTransform} that was used to render the
	 * current image. This is intended for example for {@link OverlayRendererGeneric}s that need to exactly match the
	 * transform of their overlaid content to the transform of the image.
	 */
	protected final CopyOnWriteArrayList<TransformListener<AffineTransform3D>> lastRenderTransformListeners;

	protected final ViewerOptions.Values options;

	protected final SimpleDoubleProperty mouseX = new SimpleDoubleProperty();

	protected final SimpleDoubleProperty mouseY = new SimpleDoubleProperty();

	protected final SimpleBooleanProperty isInside = new SimpleBooleanProperty();

	private final Function<Source<?>, Interpolation> interpolation;

	private final ObjectProperty<RenderUnit.ImageDisplayGrid> imageDisplayGrid = new SimpleObjectProperty<>(null);

	public ViewerPanelFX(
			final List<SourceAndConverter<?>> sources,
			final Function<Source<?>, AxisOrder> axisOrder,
			final int numTimePoints,
			final CacheControl cacheControl,
			final Function<Source<?>, Interpolation> interpolation)
	{
		this(sources, axisOrder, numTimePoints, cacheControl, ViewerOptions.options(), interpolation);
	}

	/**
	 * @param cacheControl
	 * 		to control IO budgeting and fetcher queue.
	 * @param optional
	 * 		optional parameters. See {@link ViewerOptions#options()}.
	 */
	public ViewerPanelFX(
			final Function<Source<?>, AxisOrder> axisOrder,
			final CacheControl cacheControl,
			final ViewerOptions optional,
			final Function<Source<?>, Interpolation> interpolation)
	{
		this(axisOrder, 1, cacheControl, optional, interpolation);
	}

	/**
	 * @param numTimepoints
	 * 		number of available timepoints.
	 * @param cacheControl
	 * 		to control IO budgeting and fetcher queue.
	 * @param optional
	 * 		optional parameters. See {@link ViewerOptions#options()}.
	 */
	public ViewerPanelFX(
			final Function<Source<?>, AxisOrder> axisOrder,
			final int numTimepoints,
			final CacheControl cacheControl,
			final ViewerOptions optional,
			final Function<Source<?>, Interpolation> interpolation)
	{
		this(new ArrayList<>(), axisOrder, numTimepoints, cacheControl, optional, interpolation);
	}

	/**
	 * @param sources
	 * 		the {@link SourceAndConverter sources} to display.
	 * @param numTimepoints
	 * 		number of available timepoints.
	 * @param cacheControl
	 * 		to control IO budgeting and fetcher queue.
	 * @param optional
	 * 		optional parameters. See {@link ViewerOptions#options()}.
	 */
	public ViewerPanelFX(
			final List<SourceAndConverter<?>> sources,
			final Function<Source<?>, AxisOrder> axisOrder,
			final int numTimepoints,
			final CacheControl cacheControl,
			final ViewerOptions optional,
			final Function<Source<?>, Interpolation> interpolation)
	{
		super();
		this.setCenter(this.center);
		this.renderingExecutorService = new PriorityExecutorService(Executors.newFixedThreadPool(optional.values.getNumRenderingThreads(), new RenderThreadFactory()));
		options = optional.values;

		this.state = new ViewerState(axisOrder);

		state.numTimepoints.set(numTimepoints);

		threadGroup = new ThreadGroup(this.toString());
		viewerTransform = new AffineTransform3D();

		transformListeners = new CopyOnWriteArrayList<>();
		lastRenderTransformListeners = new CopyOnWriteArrayList<>();

		this.interpolation = interpolation;

		state.sourcesAndConverters.addListener((ListChangeListener<SourceAndConverter<?>>) c -> requestRepaint());

		addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
			synchronized (isInside)
			{
				if (isInside.get())
				{
					mouseX.set(event.getX());
					mouseY.set(event.getY());
				}
			}
		});
		addEventFilter(MouseEvent.MOUSE_ENTERED, event -> {
			synchronized (isInside)
			{
				isInside.set(true);
			}
		});
		addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
			synchronized (isInside)
			{
				isInside.set(false);
			}
		});

		this.renderUnit = new RenderUnit(
				threadGroup,
				this::getState,
				axisOrder,
				interpolation,
				options.getAccumulateProjectorFactory(),
				cacheControl,
				options.getTargetRenderNanos(),
				renderingExecutorService);
		this.renderUnit.addUpdateListener(() ->
		{
			final RenderUnit.ImageDisplayGrid grid = renderUnit.getImageDisplayGrid();
			InvokeOnJavaFXApplicationThread.invoke(() -> this.display.setChild(grid.getGridPane()));
			this.imageDisplayGrid.set(grid);
		});
		this.widthProperty().addListener((obs, oldv, newv) -> this.renderUnit.setDimensions((long)getWidth(), (long)getHeight()));
		this.heightProperty().addListener((obs, oldv, newv) -> this.renderUnit.setDimensions((long)getWidth(), (long)getHeight()));
		setWidth(options.getWidth());
		setHeight(options.getHeight());
	}

	public void addSource(final SourceAndConverter<?> sourceAndConverter)
	{
		synchronized (state)
		{
			state.sourcesAndConverters.add(sourceAndConverter);
		}
	}

	public void addSources(final Collection<? extends SourceAndConverter<?>> sourceAndConverter)
	{
		synchronized (state)
		{
			state.sourcesAndConverters.addAll(sourceAndConverter);
		}
	}

	public void removeSource(final Source<?> source)
	{
		synchronized (state)
		{
			state.sourcesAndConverters.remove(state.sources.get(source));
		}
	}

	public void removeSources(final Collection<Source<?>> sources)
	{
		synchronized (state)
		{
			state.sourcesAndConverters.removeAll(sources.stream().map(state.sources::get).collect(Collectors.toList
					()));
		}
	}

	public void removeAllSources()
	{
		synchronized (state)
		{
			this.state.sourcesAndConverters.clear();
		}
	}

	public void setAllSources(final Collection<? extends SourceAndConverter<?>> sources)
	{
		synchronized (state)
		{
			this.state.sourcesAndConverters.setAll(sources);
		}
	}

	/**
	 * Set {@code gPos} to the display coordinates at gPos transformed into the global coordinate system.
	 *
	 * @param gPos
	 * 		is set to the corresponding global coordinates.
	 */
	public <P extends RealLocalizable & RealPositionable> void displayToGlobalCoordinates(final double[] gPos)
	{
		assert gPos.length >= 3;

		viewerTransform.applyInverse(gPos, gPos);
	}

	/**
	 * Set {@code gPos} to the display coordinates at gPos transformed into the global coordinate system.
	 *
	 * @param gPos
	 * 		is set to the corresponding global coordinates.
	 */
	public <P extends RealLocalizable & RealPositionable> void displayToGlobalCoordinates(final P gPos)
	{
		assert gPos.numDimensions() >= 3;

		viewerTransform.applyInverse(gPos, gPos);
	}

	/**
	 * Set {@code gPos} to the display coordinates (x,y,0)<sup>T</sup> transformed into the global coordinate system.
	 *
	 * @param gPos
	 * 		is set to the global coordinates at display (x,y,0)<sup>T</sup>.
	 */
	public void displayToGlobalCoordinates(final double x, final double y, final RealPositionable gPos)
	{
		assert gPos.numDimensions() >= 3;
		final RealPoint lPos = new RealPoint(3);
		lPos.setPosition(x, 0);
		lPos.setPosition(y, 1);
		viewerTransform.applyInverse(gPos, lPos);
	}

	/**
	 * Set {@code gPos} to the current mouse coordinates transformed into the global coordinate system.
	 *
	 * @param gPos
	 * 		is set to the current global coordinates.
	 */
	public void getGlobalMouseCoordinates(final RealPositionable gPos)
	{
		assert gPos.numDimensions() == 3;
		final RealPoint lPos = new RealPoint(3);
		lPos.setPosition(mouseX.longValue(), 0);
		lPos.setPosition(mouseY.longValue(), 1);
		viewerTransform.applyInverse(gPos, lPos);
	}

	/**
	 * TODO
	 *
	 * @param p
	 */
	public synchronized void getMouseCoordinates(final Positionable p)
	{
		assert p.numDimensions() == 2;
		p.setPosition(mouseX.longValue(), 0);
		p.setPosition(mouseY.longValue(), 1);
	}

	/**
	 * Repaint as soon as possible.
	 */
	@Override
	public void requestRepaint()
	{
		final int[] blocks = iterateOverBlocksInOrder();
		for (int i = 0; i < blocks.length; ++i) {
			renderUnit.requestRepaintSingleTile(blocks[i], PriorityExecutorService.DEFAULT_PRIORITY - i);
		}
//		renderUnit.requestRepaint(iterateOverBlocksInOrder(), PriorityExecutorService.DEFAULT_PRIORITY);
	}


	public void requestRepaint(final long[] min, final long[] max, final double priority)
	{
		renderUnit.requestRepaint(min, max, priority);
	}

	private int[] iterateOverBlocksInOrder()
	{
		final boolean isMouseInside = isMouseInside();
		final long x0 = (long) (isMouseInside ? mouseX.get() : getWidth() / 2);
		final long y0 = (long) (isMouseInside ? mouseY.get() : getHeight() / 2);
		final RenderUnit.ImageDisplayGrid imageDisplayGrid = this.imageDisplayGrid.get();
		if (imageDisplayGrid == null)
			return new int[0];

		final CellGrid grid = imageDisplayGrid.getGrid();
		final long[] pos = {x0, y0};
		final long[] cellPos = new long[2];
		grid.getCellPosition(pos, cellPos);
		final long[] gridDimensions = grid.getGridDimensions();
		LOG.debug("Starting at pos={} cellPos={}", pos, cellPos);
		final ArrayImg<IntType, IntArray> toBeFilled = ArrayImgs.ints(range((int) Intervals.numElements(gridDimensions)), gridDimensions);

		final TIntArrayList indices = new TIntArrayList();
		final IntType targetType = new IntType();
		targetType.set(-1);
		FloodFill.fill(
				Views.extendValue(toBeFilled, targetType.copy()),
				toBeFilled,
				new Point(cellPos),
				new DiamondShape(1),
				(s, t) -> !targetType.valueEquals(s),
				it -> {indices.add(it.getInteger()); it.set(targetType);}
				);

		return indices.toArray();


	}


	@Override
	public synchronized void transformChanged(final AffineTransform3D transform)
	{
		viewerTransform.set(transform);
		state.setViewerTransform(transform);
		for (final TransformListener<AffineTransform3D> l : transformListeners)
			l.transformChanged(viewerTransform);
		requestRepaint();
	}

	/**
	 * Set the viewer transform.
	 */
	public synchronized void setCurrentViewerTransform(final AffineTransform3D viewerTransform)
	{
		transformChanged(viewerTransform);
	}

	/**
	 * Show the specified time-point.
	 *
	 * @param timepoint
	 * 		time-point index.
	 */
	public synchronized void setTimepoint(final int timepoint)
	{
		state.timepoint.set(timepoint);
	}

	public void setNumTimepoints(final int numTimepoints)
	{

		if (numTimepoints < 1 || state.numTimepoints.get() == numTimepoints)
			return;
		state.numTimepoints.set(numTimepoints);
		if (state.numTimepoints.get() >= numTimepoints)
		{
			final int timepoint = numTimepoints - 1;
			state.timepoint.set(timepoint);
		}
		requestRepaint();
	}

	/**
	 * Get a copy of the current {@link ViewerState}.
	 *
	 * @return a copy of the current {@link ViewerState}.
	 */
	public ViewerState getState()
	{
		return state.copy();
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation changes. Listeners will be notified when a
	 * new image has been painted with the viewer transform used to render that image.
	 * <p>
	 * This happens immediately after that image is painted onto the screen, before any overlays are painted.
	 *
	 * @param listener
	 * 		the transform listener to add.
	 */
//	public void addRenderTransformListener(final TransformListener<AffineTransform3D> listener)
//	{
//		renderTarget.addTransformListener(listener);
//	}
//
//	/**
//	 * Add a {@link TransformListener} to notify about viewer transformation changes. Listeners will be notified when a
//	 * new image has been painted with the viewer transform used to render that image.
//	 * <p>
//	 * This happens immediately after that image is painted onto the screen, before any overlays are painted.
//	 *
//	 * @param listener
//	 * 		the transform listener to add.
//	 * @param index
//	 * 		position in the list of listeners at which to insert this one.
//	 */
//	public void addRenderTransformListener(final TransformListener<AffineTransform3D> listener, final int index)
//	{
//		renderTarget.addTransformListener(listener, index);
//	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation changes. Listeners will be notified
	 * <em>before</em> calling {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 * 		the transform listener to add.
	 */
	public void addTransformListener(final TransformListener<AffineTransform3D> listener)
	{
		addTransformListener(listener, Integer.MAX_VALUE);
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation changes. Listeners will be notified
	 * <em>before</em> calling {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 * 		the transform listener to add.
	 * @param index
	 * 		position in the list of listeners at which to insert this one.
	 */
	public void addTransformListener(final TransformListener<AffineTransform3D> listener, final int index)
	{
		synchronized (transformListeners)
		{
			final int s = transformListeners.size();
			transformListeners.add(index < 0 ? 0 : index > s ? s : index, listener);
			listener.transformChanged(viewerTransform);
		}
	}

	/**
	 * Remove a {@link TransformListener}.
	 *
	 * @param listener
	 * 		the transform listener to remove.
	 */
	public void removeTransformListener(final TransformListener<AffineTransform3D> listener)
	{
		synchronized (transformListeners)
		{
			transformListeners.remove(listener);
		}
//		renderTarget.removeTransformListener(listener);
	}

	/**
	 * does nothing.
	 */
	@Override
	public void setCanvasSize(final int width, final int height)
	{
	}

	public ViewerOptions.Values getOptionValues()
	{
		return options;
	}

	//	public SourceInfoOverlayRenderer getSourceInfoOverlayRenderer()
	//	{
	//		return sourceInfoOverlayRenderer;
	//	}

	public void stop()
	{
		renderingExecutorService.shutdown();
	}

	protected static final AtomicInteger panelNumber = new AtomicInteger(1);

	protected class RenderThreadFactory implements ThreadFactory
	{
		private final String threadNameFormat;

		private final AtomicInteger threadNumber = new AtomicInteger(1);

		public RenderThreadFactory()
		{
			this.threadNameFormat = String.format("viewer-panel-fx-%d-thread-%%d", panelNumber.getAndIncrement());
			LOG.debug("Created {} with format {}", getClass().getSimpleName(), threadNameFormat);
		}

		@Override
		public Thread newThread(final Runnable r)
		{
			final Thread t = new Thread(threadGroup, r,
					String.format(threadNameFormat, threadNumber.getAndIncrement()),
					0
			);
			LOG.debug("Creating thread with name {}", t.getName());
			if (!t.isDaemon())
				t.setDaemon(true);
			if (t.getPriority() != Thread.NORM_PRIORITY)
				t.setPriority(Thread.NORM_PRIORITY);
			return t;
		}
	}

//	public TransformAwareBufferedImageOverlayRendererFX renderTarget()
//	{
//		return this.renderTarget;
//	}

	@Override
	public void drawOverlays(final GraphicsContext g)
	{
		display.requestLayout();
	}

	public boolean isMouseInside()
	{
		return this.isInside.get();
	}

	public ReadOnlyBooleanProperty isMouseInsideProperty()
	{
		return ReadOnlyBooleanProperty.readOnlyBooleanProperty(this.isInside);
	}

	public double getMouseX()
	{
		return mouseX.doubleValue();
	}

	public double getMouseY()
	{
		return mouseY.doubleValue();
	}

	public ReadOnlyDoubleProperty mouseXProperty()
	{
		return ReadOnlyDoubleProperty.readOnlyDoubleProperty(mouseX);
	}

	public ReadOnlyDoubleProperty mouseYProperty()
	{
		return ReadOnlyDoubleProperty.readOnlyDoubleProperty(mouseY);
	}

	public void setScreenScales(double[] screenScales)
	{
		LOG.debug("Setting screen scales to {}", screenScales);
		this.renderUnit.setScreenScales(screenScales.clone());
	}

	public OverlayPane getDisplay()
	{
		return this.overlayPane;
	}

	public ObjectProperty<RenderUnit.ImageDisplayGrid> imageDisplayGridProperty()
	{
		return this.imageDisplayGrid;
	}

	private static final int[] range(int size)
	{
		int[] range = new int[size];
		for (int i = 0; i < range.length; ++i)
			range[i] = i;
		return range;
	}

}
