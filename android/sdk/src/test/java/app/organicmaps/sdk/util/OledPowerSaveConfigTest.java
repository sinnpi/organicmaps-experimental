package app.organicmaps.sdk.util;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mockStatic;

import org.junit.Test;
import org.mockito.MockedStatic;

public class OledPowerSaveConfigTest
{
  @Test
  public void disabledFeatureDoesNotReadPersistedMode()
  {
    // Native settings are unavailable here: the feature gate must short-circuit that read.
    try (MockedStatic<Config> config = mockStatic(Config.class))
    {
      config.when(Config::isOledPowerSaveFeatureEnabled).thenReturn(false);
      config.when(Config::isOledPowerSaveEnabled).thenCallRealMethod();
      assertFalse(Config.isOledPowerSaveEnabled());
      config.verify(Config::isOledPowerSaveFeatureEnabled);
    }
  }
}
