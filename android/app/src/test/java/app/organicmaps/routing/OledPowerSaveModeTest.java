package app.organicmaps.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OledPowerSaveModeTest
{
  @Test
  public void followsToggleOnThePhoneDisplayWithoutNavigation()
  {
    assertTrue(OledPowerSaveMode.shouldEnable(true, false));
    assertFalse(OledPowerSaveMode.shouldEnable(false, false));
  }

  @Test
  public void neverEnablesOnTheCarDisplay()
  {
    assertFalse(OledPowerSaveMode.shouldEnable(true, true));
    assertFalse(OledPowerSaveMode.shouldEnable(false, true));
  }
}
