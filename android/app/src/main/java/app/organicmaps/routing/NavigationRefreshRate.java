package app.organicmaps.routing;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.NonNull;

/**
 * Asks the display for a lower refresh rate while navigation runs on its own, and hands the choice
 * back to the system as soon as the user touches the screen.
 *
 * The renderer already throttles itself while following a route, but the swapchain presents FIFO:
 * the panel keeps scanning at its own rate however slowly frames arrive, so a low rate has to be
 * asked for separately to be worth any power. Interaction has to lift it again, or dragging along
 * the elevation profile would follow the finger in visible steps.
 */
final class NavigationRefreshRate
{
  /// Twice the rate the renderer caps itself at while following, so that every frame is shown whole.
  static final float NAVIGATION_HZ = 60.0f;
  static final float LOW_POWER_HZ = 30.0f;
  /// 0 hands the choice back to the system, which is what everything outside navigation wants.
  static final float SYSTEM_DEFAULT_HZ = 0.0f;
  /// Held past the last touch, to cover the fling and the camera animation that usually follow one.
  private static final long SETTLE_DELAY_MS = 1500;

  @NonNull
  private final Window mWindow;
  @NonNull
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull
  private final Runnable mSettleRunnable = this::settle;

  private boolean mNavigating;
  private boolean mLowPowerMode;
  private boolean mInteracting;
  private boolean mBlackout;
  private float mRequestedRate = SYSTEM_DEFAULT_HZ;

  NavigationRefreshRate(@NonNull Window window)
  {
    mWindow = window;
  }

  /// Split out from the window so that the choice can be checked without a display attached.
  static float rateFor(boolean navigating, boolean lowPowerMode, boolean interacting, boolean blackout, float lowestHz)
  {
    // Nothing is drawn under a blackout, and the touch that ends it wakes the screen before it moves anything.
    if (blackout)
      return lowestHz;
    if (interacting || !navigating)
      return SYSTEM_DEFAULT_HZ;

    return lowPowerMode ? LOW_POWER_HZ : NAVIGATION_HZ;
  }

  /**
   * @param navigating whether navigation is running on this display at all
   * @param lowPowerMode whether it is doing so in the sparse OLED mode, which renders slower still
   */
  void update(boolean navigating, boolean lowPowerMode)
  {
    mNavigating = navigating;
    mLowPowerMode = lowPowerMode;
    apply();
  }

  void setBlackout(boolean blackout)
  {
    mBlackout = blackout;
    apply();
  }

  void onInteractionStarted()
  {
    mHandler.removeCallbacks(mSettleRunnable);
    mInteracting = true;
    apply();
  }

  void onInteractionEnded()
  {
    mHandler.removeCallbacks(mSettleRunnable);
    mHandler.postDelayed(mSettleRunnable, SETTLE_DELAY_MS);
  }

  private void settle()
  {
    mInteracting = false;
    apply();
  }

  private void apply()
  {
    final float rate = rateFor(mNavigating, mLowPowerMode, mInteracting, mBlackout,
                               mBlackout ? lowestRefreshRate() : SYSTEM_DEFAULT_HZ);
    if (Float.compare(mRequestedRate, rate) == 0)
      return;

    mRequestedRate = rate;
    final WindowManager.LayoutParams params = mWindow.getAttributes();
    params.preferredRefreshRate = rate;
    mWindow.setAttributes(params);
  }

  /// The lowest rate the display offers at its current resolution, or the system default if it can't be asked.
  private float lowestRefreshRate()
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
      return SYSTEM_DEFAULT_HZ;

    final Display display = mWindow.getWindowManager().getDefaultDisplay();
    final Display.Mode current = display.getMode();
    float lowest = current.getRefreshRate();
    for (Display.Mode mode : display.getSupportedModes())
    {
      if (mode.getPhysicalWidth() == current.getPhysicalWidth()
          && mode.getPhysicalHeight() == current.getPhysicalHeight())
        lowest = Math.min(lowest, mode.getRefreshRate());
    }
    return lowest;
  }
}
