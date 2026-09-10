package app.organicmaps.routing;

final class OledPowerSaveMode
{
  private OledPowerSaveMode() {}

  static boolean shouldEnable(boolean settingEnabled, boolean carDisplayUsed)
  {
    return settingEnabled && !carDisplayUsed;
  }
}
