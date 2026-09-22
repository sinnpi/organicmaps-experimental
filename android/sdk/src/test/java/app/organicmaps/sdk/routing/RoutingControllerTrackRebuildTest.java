package app.organicmaps.sdk.routing;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.Test;

public class RoutingControllerTrackRebuildTest
{
  @Test
  public void changingRouteOptionsRebuildsTrackWithoutCancellingIt()
  {
    RoutingController controller = spy(new RoutingController());
    doReturn(true).when(controller).isTrackFollowMode();
    doNothing().when(controller).rebuildTrackPlan();

    controller.rebuildLastRoute();

    verify(controller).rebuildTrackPlan();
    verify(controller, never()).cancel();
    // The normal branch reads these to prepare a new point-to-point route, losing native track state.
    verify(controller, never()).getStartPoint();
    verify(controller, never()).getEndPoint();
  }
}
