package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavElevationChartControllerTest
{
  private static final float EPS = 0.001f;

  @Test
  public void keepsWindowThatFitsTheRoute()
  {
    assertEquals(10000f, NavElevationChartController.clampWindow(10000f, 42000f), EPS);
  }

  @Test
  public void clampsWindowToRouteLength()
  {
    assertEquals(42000f, NavElevationChartController.clampWindow(50000f, 42000f), EPS);
  }

  @Test
  public void refusesWindowShorterThanAKilometre()
  {
    assertEquals(1000f, NavElevationChartController.clampWindow(200f, 42000f), EPS);
  }

  @Test
  public void keepsMinimumWindowOnRoutesShorterThanIt()
  {
    // A 300 m route still gets a 1 km window: there is nothing to zoom into.
    assertEquals(1000f, NavElevationChartController.clampWindow(10000f, 300f), EPS);
    assertEquals(1000f, NavElevationChartController.clampWindow(200f, 300f), EPS);
  }

  @Test
  public void hidesPositionLineWhileTheViewportStillFollows()
  {
    assertFalse(NavElevationChartController.shouldShowPositionLine(5000f, 5000f, 10000f));
    // Rounding jitter of a few metres must not flash the line on.
    assertFalse(NavElevationChartController.shouldShowPositionLine(5000f, 4990f, 10000f));
  }

  @Test
  public void showsPositionLineOnceTheViewportStopsScrolling()
  {
    // Last kilometre of a 42 km route seen through a 10 km window: the viewport is pinned at 32 km.
    assertTrue(NavElevationChartController.shouldShowPositionLine(41000f, 32000f, 10000f));
  }
}
