package bdv.bigcat.viewer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;

public class ViewerPanelState
{

	public class ViewerPanelListener
	{

		private final ViewerPanel viewer;

		private final MapChangeListener< Source< ? >, Boolean > visibilityListener;

		private final ListChangeListener< SourceAndConverter< ? > > sourcesListener;

		private final ChangeListener< Source< ? > > currentSourceListener;

		private final ChangeListener< Interpolation > interpolationListener;

		public ViewerPanelListener( final ViewerPanel viewer )
		{
			super();
			this.viewer = viewer;

			this.visibilityListener = change -> {
				if ( change.wasAdded() )
					viewer.getVisibilityAndGrouping().setSourceActive( change.getKey(), change.getValueAdded() );
			};

			this.sourcesListener = change -> {
				while ( change.next() )
				{
					viewer.getVisibilityAndGrouping().getSources().forEach( sourceState -> viewer.removeSource( sourceState.getSpimSource() ) );
					change.getList().forEach( viewer::addSource );
				}
			};

			this.currentSourceListener = ( observable, oldSource, newSource ) -> {
				viewer.getVisibilityAndGrouping().setCurrentSource( newSource );
			};

			this.interpolationListener = ( observable, oldInterpolation, newInterpolation ) -> {
				viewer.setInterpolation( newInterpolation );
			};
		}

		public MapChangeListener< Source< ? >, Boolean > getVisibilityListener()
		{
			return this.visibilityListener;
		}

		public ListChangeListener< SourceAndConverter< ? > > getSourcesListener()
		{
			return this.sourcesListener;
		}

		public ChangeListener< Source< ? > > getCurrentSourceListener()
		{
			return this.currentSourceListener;
		}

	}

	private final HashMap< ViewerPanel, ViewerPanelListener > viewerListeners = new HashMap<>();

	private final ObservableMap< Source< ? >, Boolean > isVisible = FXCollections.observableHashMap();

	private final ObservableList< SourceAndConverter< ? > > sacs = FXCollections.observableArrayList();

	private final SimpleObjectProperty< Source< ? > > currentSource = new SimpleObjectProperty<>( null, "current source" );

	private final SimpleObjectProperty< Interpolation > interpolation = new SimpleObjectProperty<>( null, "interpolation" );

	public synchronized void addVisibilityListener( final MapChangeListener< Source< ? >, Boolean > listener )
	{
		this.isVisible.addListener( listener );
	}

	public synchronized void removeVisibilityListener( final MapChangeListener< Source< ? >, Boolean > listener )
	{
		this.isVisible.removeListener( listener );
	}

	public synchronized void addSourcesListener( final ListChangeListener< SourceAndConverter< ? > > listener )
	{
		this.sacs.addListener( listener );
	}

	public synchronized void removeSourcesListener( final ListChangeListener< SourceAndConverter< ? > > listener )
	{
		this.sacs.removeListener( listener );
	}

	public synchronized void addCurrentSourceListener( final ChangeListener< Source< ? > > listener )
	{
		this.currentSource.addListener( listener );
	}

	public synchronized void removeCurrentSourceListener( final ChangeListener< Source< ? > > listener )
	{
		this.currentSource.removeListener( listener );
	}

	public synchronized void addInterpolationListener( final ChangeListener< Interpolation > listener )
	{
		this.interpolation.addListener( listener );
	}

	public synchronized void removeInterpolationListener( final ChangeListener< Interpolation > listener )
	{
		this.interpolation.removeListener( listener );
	}

	public boolean isViewerInstalled( final ViewerPanel viewer )
	{
		synchronized ( viewerListeners )
		{
			return viewerListeners.containsKey( viewer );
		}
	}

	public synchronized void installViewer( final ViewerPanel viewer )
	{
		final ViewerPanelListener listener = new ViewerPanelListener( viewer );
		this.viewerListeners.put( viewer, listener );
		synchronized ( viewer )
		{
			viewer.getVisibilityAndGrouping().getSources().forEach( state -> viewer.removeSource( state.getSpimSource() ) );
			synchronized ( sacs )
			{
				sacs.forEach( viewer::addSource );
			}

			addVisibilityListener( listener.getVisibilityListener() );
			addSourcesListener( listener.getSourcesListener() );
			addCurrentSourceListener( listener.getCurrentSourceListener() );
			addInterpolationListener( listener.interpolationListener );

			synchronized ( interpolation )
			{
				final Interpolation method = interpolation.get();
				if ( method == null )
					interpolation.set( viewer.getState().getInterpolation() );
				else
					viewer.setInterpolation( interpolation.get() );
			}
		}
	}

	public synchronized void removeViewer( final ViewerPanel viewer )
	{
		final ViewerPanelListener listener = this.viewerListeners.remove( viewer );
		if ( listener != null )
			synchronized ( viewer )
			{
				removeVisibilityListener( listener.getVisibilityListener() );
				removeSourcesListener( listener.getSourcesListener() );
				removeCurrentSourceListener( listener.getCurrentSourceListener() );
				removeInterpolationListener( listener.interpolationListener );
			}
	}

	public void setVisibility( final Source< ? > source, final boolean isVisible )
	{
		this.isVisible.put( source, isVisible );
	}

	public synchronized void setCurrentSource( final Source< ? > source )
	{
		this.currentSource.set( source );
	}

	public synchronized void addSource( final SourceAndConverter< ? > sac )
	{
		this.sacs.add( sac );
	}

	public synchronized void removeSource( final Source< ? > source )
	{
		SourceAndConverter< ? > sac = null;
		for ( final SourceAndConverter< ? > s : sacs )
			if ( s.getSpimSource().equals( source ) )
			{
				sac = s;
				break;
			}
		if ( sac != null )
			this.sacs.remove( sac );
	}

	public List< SourceAndConverter< ? > > getSourcesCopy()
	{
		synchronized ( this.sacs )
		{
			return new ArrayList<>( this.sacs );
		}
	}

	public synchronized void toggleInterpolation()
	{
		final Interpolation interpolation = this.interpolation.get();
		if ( interpolation == null )
			this.interpolation.set( Interpolation.NEARESTNEIGHBOR );
		else
			switch ( interpolation )
			{
			case NEARESTNEIGHBOR:
				this.interpolation.set( Interpolation.NLINEAR );
				break;
			case NLINEAR:
				this.interpolation.set( Interpolation.NEARESTNEIGHBOR );
				break;
			default:
				this.interpolation.set( Interpolation.NEARESTNEIGHBOR );
				break;
			}
	}

}
