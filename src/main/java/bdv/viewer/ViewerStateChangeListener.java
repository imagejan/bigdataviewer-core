package bdv.viewer;

/**
 * {@link ViewerStateChangeListener}s are notified about BigDataViewer state
 * changes.
 *
 * @author Tobias Pietzsch
 */
public interface ViewerStateChangeListener
{
	void viewerStateChanged( ViewerStateChange change );
}
