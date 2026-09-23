package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationRefreshRateTest
{
  private static final float EPS = 0.0f;

  @Test
  public void throttlesOnlyWhileNavigatingUntouched()
  {
    assertEquals(NavigationRefreshRate.NAVIGATION_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, false /* lowPowerMode */, false /* interacting */,
                                               false /* blackout */, 0.0f),
                 EPS);
    assertEquals(NavigationRefreshRate.LOW_POWER_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, true /* lowPowerMode */, false /* interacting */,
                                               false /* blackout */, 0.0f),
                 EPS);
    assertEquals(NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
                 NavigationRefreshRate.rateFor(false /* navigating */, false /* lowPowerMode */,
                                               false /* interacting */, false /* blackout */, 0.0f),
                 EPS);
  }

  @Test
  public void interactionAlwaysWinsOverTheNavigationRate()
  {
    assertEquals(NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, false /* lowPowerMode */, true /* interacting */,
                                               false /* blackout */, 0.0f),
                 EPS);
    assertEquals(NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, true /* lowPowerMode */, true /* interacting */,
                                               false /* blackout */, 0.0f),
                 EPS);
  }

  @Test
  public void blackoutAsksForTheLowestRate()
  {
    assertEquals(10.0f,
                 NavigationRefreshRate.rateFor(true /* navigating */, false /* lowPowerMode */, false /* interacting */,
                                               true /* blackout */, 10.0f),
                 EPS);
    assertEquals(10.0f,
                 NavigationRefreshRate.rateFor(true /* navigating */, true /* lowPowerMode */, true /* interacting */,
                                               true /* blackout */, 10.0f),
                 EPS);
  }
}
