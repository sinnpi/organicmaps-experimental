package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationRefreshRateTest
{
  private static final float EPS = 0.0f;

  @Test
  public void throttlesOnlyWhileNavigatingUntouched()
  {
    assertEquals(
        NavigationRefreshRate.NAVIGATION_HZ,
        NavigationRefreshRate.rateFor(true /* navigating */, false /* lowPowerMode */, false /* interacting */), EPS);
    assertEquals(NavigationRefreshRate.LOW_POWER_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, true /* lowPowerMode */, false /* interacting */),
                 EPS);
    assertEquals(
        NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
        NavigationRefreshRate.rateFor(false /* navigating */, false /* lowPowerMode */, false /* interacting */), EPS);
  }

  @Test
  public void interactionAlwaysWinsOverTheNavigationRate()
  {
    assertEquals(NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, false /* lowPowerMode */, true /* interacting */),
                 EPS);
    assertEquals(NavigationRefreshRate.SYSTEM_DEFAULT_HZ,
                 NavigationRefreshRate.rateFor(true /* navigating */, true /* lowPowerMode */, true /* interacting */),
                 EPS);
  }
}
