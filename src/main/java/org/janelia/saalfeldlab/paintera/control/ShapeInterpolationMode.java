package org.janelia.saalfeldlab.paintera.control;

import java.lang.invoke.MethodHandles;
import java.util.function.Predicate;

import org.janelia.saalfeldlab.fx.event.DelegateEventHandlers;
import org.janelia.saalfeldlab.fx.event.EventFX;
import org.janelia.saalfeldlab.fx.event.KeyTracker;
import org.janelia.saalfeldlab.fx.event.MouseClickFX;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions;
import org.janelia.saalfeldlab.paintera.control.actions.LabelAction;
import org.janelia.saalfeldlab.paintera.control.actions.NavigationAction;
import org.janelia.saalfeldlab.paintera.control.actions.PaintAction;
import org.janelia.saalfeldlab.paintera.control.paint.FloodFill2D;
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds;
import org.janelia.saalfeldlab.paintera.data.mask.Mask;
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo;
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource;
import org.janelia.saalfeldlab.paintera.data.mask.exception.MaskInUse;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.stream.HighlightingStreamConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.fx.viewer.ViewerPanelFX;
import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.label.Label;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Util;

public class ShapeInterpolationMode<D extends IntegerType<D>>
{
	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final AllowedActions allowedActions;
	private static final AllowedActions allowedActionsWhenSelected;
	static
	{
		allowedActions = new AllowedActions(
			NavigationAction.of(NavigationAction.Drag, NavigationAction.Zoom, NavigationAction.Scroll),
			LabelAction.none(),
			PaintAction.none()
		);
		allowedActionsWhenSelected = new AllowedActions(
				NavigationAction.of(NavigationAction.Drag, NavigationAction.Zoom),
				LabelAction.none(),
				PaintAction.none()
			);
	}

	private static final double FILL_DEPTH = 2.0;

	private static final Color MASK_COLOR = Color.web("00CCFF");

	private static final Predicate<UnsignedLongType> FOREGROUND_CHECK = t -> t.get() > 0;

	private final ObjectProperty<ViewerPanelFX> activeViewer = new SimpleObjectProperty<>();

	private final MaskedSource<D, ?> source;
	private final SelectedIds selectedIds;
	private final IdService idService;
	private final HighlightingStreamConverter<?> converter;

	private AllowedActions lastAllowedActions;
	private long lastSelectedId;
	private long[] lastActiveIds;

	private Mask<UnsignedLongType> mask;
	private int currentFillValue;

	private final TLongObjectMap<RealPoint> selectedObjects = new TLongObjectHashMap<>();

	public ShapeInterpolationMode(
			final MaskedSource<D, ?> source,
			final SelectedIds selectedIds,
			final IdService idService,
			final HighlightingStreamConverter<?> converter)
	{
		this.source = source;
		this.selectedIds = selectedIds;
		this.idService = idService;
		this.converter = converter;
	}

	public ObjectProperty<ViewerPanelFX> activeViewerProperty()
	{
		return activeViewer;
	}

	public EventHandler<Event> modeHandler(final PainteraBaseView paintera, final KeyTracker keyTracker)
	{
		final DelegateEventHandlers.AnyHandler filter = DelegateEventHandlers.handleAny();
		filter.addEventHandler(
				KeyEvent.KEY_PRESSED,
				EventFX.KEY_PRESSED(
						"enter shape interpolation mode",
						e -> enterMode(paintera, (ViewerPanelFX) e.getTarget()),
						e -> e.getTarget() instanceof ViewerPanelFX &&
							!isModeOn() &&
							!source.isApplyingMaskProperty().get() &&
							keyTracker.areOnlyTheseKeysDown(KeyCode.S)
					)
			);
		filter.addEventHandler(
				KeyEvent.KEY_PRESSED,
				EventFX.KEY_PRESSED(
						"fix selection",
						e -> fixSelection(paintera),
						e -> isModeOn() &&
							!selectedObjects.isEmpty() &&
							keyTracker.areOnlyTheseKeysDown(KeyCode.S)
					)
			);
		filter.addEventHandler(
				KeyEvent.KEY_PRESSED,
				EventFX.KEY_PRESSED(
						"exit shape interpolation mode",
						e -> exitMode(paintera),
						e -> isModeOn() && keyTracker.areOnlyTheseKeysDown(KeyCode.ESCAPE)
					)
			);
		filter.addEventHandler(MouseEvent.ANY, new MouseClickFX(
				"select object in current section",
				e -> {e.consume(); selectObject(paintera, e.getX(), e.getY(), true);},
				e -> isModeOn() && e.isPrimaryButtonDown() && keyTracker.noKeysActive())
			.handler());
		filter.addEventHandler(MouseEvent.ANY, new MouseClickFX(
				"toggle object in current section",
				e -> {e.consume(); selectObject(paintera, e.getX(), e.getY(), false);},
				e -> isModeOn() &&
					((e.isSecondaryButtonDown() && keyTracker.noKeysActive()) ||
					(e.isPrimaryButtonDown() && keyTracker.areOnlyTheseKeysDown(KeyCode.CONTROL))))
			.handler());
		return filter;
	}

	public void enterMode(final PainteraBaseView paintera, final ViewerPanelFX viewer)
	{
		if (isModeOn())
		{
			LOG.info("Already in shape interpolation mode");
			return;
		}
		LOG.info("Entering shape interpolation mode");
		activeViewer.set(viewer);
		setDisableOtherViewers(true);

		lastAllowedActions = paintera.allowedActionsProperty().get();
		paintera.allowedActionsProperty().set(allowedActions);

		try
		{
			createMask();
			lastSelectedId = selectedIds.getLastSelection();
			lastActiveIds = selectedIds.getActiveIds();
			final long newLabelId = mask.info.value.get();
			converter.setColor(newLabelId, MASK_COLOR);
			selectedIds.activate(newLabelId);
			currentFillValue = 0;
			selectedObjects.clear();
		}
		catch (final MaskInUse e)
		{
			e.printStackTrace();
		}
	}

	public void exitMode(final PainteraBaseView paintera)
	{
		if (!isModeOn())
		{
			LOG.info("Not in shape interpolation mode");
			return;
		}
		LOG.info("Exiting shape interpolation mode");
		setDisableOtherViewers(false);

		paintera.allowedActionsProperty().set(lastAllowedActions);
		lastAllowedActions = null;

		final long newLabelId = mask.info.value.get();
		converter.removeColor(newLabelId);
		selectedIds.activate(lastActiveIds);
		selectedIds.activateAlso(lastSelectedId);
		lastSelectedId = Label.INVALID;
		lastActiveIds = null;
		currentFillValue = 0;
		selectedObjects.clear();
		forgetMask();
		activeViewer.get().requestRepaint();

		activeViewer.set(null);
	}

	public boolean isModeOn()
	{
		return activeViewer.get() != null;
	}

	private void createMask() throws MaskInUse
	{
		final int time = activeViewer.get().getState().timepointProperty().get();
		final int level = 0;
		final long newLabelId = idService.next();
		final MaskInfo<UnsignedLongType> maskInfo = new MaskInfo<>(time, level, new UnsignedLongType(newLabelId));
		mask = source.generateMask(maskInfo, FOREGROUND_CHECK);
		LOG.info("Generated mask for shape interpolation using new label ID {}", newLabelId);
	}

	private void forgetMask()
	{
		mask = null;
		source.resetMasks();
	}

	private void setDisableOtherViewers(final boolean disable)
	{
		final Parent parent = activeViewer.get().getParent();
		for (final Node child : parent.getChildrenUnmodifiable())
		{
			if (child instanceof ViewerPanelFX && child != activeViewer.get())
			{
				final ViewerPanelFX viewer = (ViewerPanelFX) child;
				viewer.setDisable(disable);
				if (disable)
				{
					final ColorAdjust grayedOutEffect = new ColorAdjust();
					grayedOutEffect.setContrast(-0.2);
					grayedOutEffect.setBrightness(-0.5);
					viewer.setEffect(grayedOutEffect);
				}
				else
				{
					viewer.setEffect(null);
				}
			}
		}
	}

	private void fixSelection(final PainteraBaseView paintera)
	{
		selectedObjects.clear();
		paintera.allowedActionsProperty().set(allowedActions);
	}

	private void selectObject(final PainteraBaseView paintera, final double x, final double y, final boolean deactivateOthers)
	{
		final boolean wasSelected = isSelected(x, y);
		final int numSelectedObjects = selectedObjects.size();

		LOG.debug("Object was clicked: deactivateOthers={}, wasSelected={}, numSelectedObjects", deactivateOthers, wasSelected, numSelectedObjects);

		if (deactivateOthers)
		{
			for (final TLongObjectIterator<RealPoint> it = selectedObjects.iterator(); it.hasNext();)
			{
				it.advance();
				final double[] deselectDisplayPos = getDisplayCoordinates(it.value());
				runFloodFillToDeselect(deselectDisplayPos[0], deselectDisplayPos[1]);
			}
			selectedObjects.clear();
		}

		if (!wasSelected || (deactivateOthers && numSelectedObjects > 1))
		{
			final long newFillValue = runFloodFillToSelect(x, y);
			selectedObjects.put(newFillValue, getSourceCoordinates(x, y));
		}
		else
		{
			final long oldFillValue = runFloodFillToDeselect(x, y);
			selectedObjects.remove(oldFillValue);
		}

		activeViewer.get().requestRepaint();
		paintera.allowedActionsProperty().set(selectedObjects.isEmpty() ? allowedActions : allowedActionsWhenSelected);
	}

	/**
	 * Flood-fills the mask using a new fill value to mark the object as selected.
	 *
	 * @param x
	 * @param y
	 * @return the fill value of the selected object
	 */
	private long runFloodFillToSelect(final double x, final double y)
	{
		FloodFill2D.fillMaskAt(x, y, activeViewer.get(), mask, source, ++currentFillValue, FILL_DEPTH);
		return currentFillValue;
	}

	/**
	 * Flood-fills the mask using a background value to remove the object from the selection.
	 *
	 * @param x
	 * @param y
	 * @return the fill value of the deselected object
	 */
	private long runFloodFillToDeselect(final double x, final double y)
	{
		// set the predicate to accept only the fill value at the clicked location to avoid deselecting adjacent objects.
		final long maskValue = getMaskValue(x, y).get();
		final RandomAccessibleInterval<BoolType> predicate = Converters.convert(
				mask.mask,
				(in, out) -> out.set(in.getIntegerLong() == maskValue),
				new BoolType()
			);
		FloodFill2D.fillMaskAt(x, y, activeViewer.get(), mask, predicate, getMaskTransform(), Label.BACKGROUND, FILL_DEPTH);
		return maskValue;
	}

	private boolean isSelected(final double x, final double y)
	{
		return FOREGROUND_CHECK.test(getMaskValue(x, y));
	}

	private UnsignedLongType getMaskValue(final double x, final double y)
	{
		final RealPoint sourcePos = getSourceCoordinates(x, y);
		final RandomAccess<UnsignedLongType> maskAccess = mask.mask.randomAccess();
		for (int d = 0; d < sourcePos.numDimensions(); ++d)
			maskAccess.setPosition(Math.round(sourcePos.getDoublePosition(d)), d);
		return maskAccess.get();
	}

	private AffineTransform3D getMaskTransform()
	{
		final AffineTransform3D maskTransform = new AffineTransform3D();
		source.getSourceTransform(mask.info.t, mask.info.level, maskTransform);
		return maskTransform;
	}

	private RealPoint getSourceCoordinates(final double x, final double y)
	{
		final AffineTransform3D maskTransform = getMaskTransform();
		final RealPoint sourcePos = new RealPoint(maskTransform.numDimensions());
		activeViewer.get().displayToSourceCoordinates(x, y, maskTransform, sourcePos);
		return sourcePos;
	}

	private double[] getDisplayCoordinates(final RealPoint sourcePos)
	{
		final AffineTransform3D maskTransform = getMaskTransform();
		final RealPoint displayPos = new RealPoint(maskTransform.numDimensions());
		final AffineTransform3D viewerTransform = new AffineTransform3D();
		activeViewer.get().getState().getViewerTransform(viewerTransform);
		final AffineTransform3D maskToViewerTransform = viewerTransform.copy().concatenate(maskTransform);
		maskToViewerTransform.apply(sourcePos, displayPos);
		assert Util.isApproxEqual(displayPos.getDoublePosition(2), 0.0, 1e-10);
		return new double[] {displayPos.getDoublePosition(0), displayPos.getDoublePosition(1)};
	}
}
