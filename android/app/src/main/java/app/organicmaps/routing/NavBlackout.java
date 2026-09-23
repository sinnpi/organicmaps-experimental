package app.organicmaps.routing;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.routing.NavBlackoutPolicy.State;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.MapController;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;

/**
 * Blacks the screen out during navigation on long stretches without turns, see {@link NavBlackoutPolicy}.
 *
 * The activity covers the screen in black at the lowest brightness and refresh rate, and parks the map
 * renderer. Where the app may switch the screen back on (the "Turn screen on" special access since Android 14),
 * it also stops keeping the screen on, so that the screen timeout switches it off for real: a black screen
 * still keeps the display pipeline running and the phone from sleeping. The blackout then shows over the lock
 * screen, and listens to fixes itself, to switch the screen back on before the turn. Voice guidance comes
 * from NavigationService either way.
 */
final class NavBlackout
{
  private static final String TAG = NavBlackout.class.getSimpleName();
  /// Without fixes there is nothing to tell how close the turn is, so the screen has to show that.
  private static final long NO_FIX_WAKE_MS = 15_000;
  /// Often enough for the countdown to change its number close to the whole second.
  private static final long COUNTDOWN_TICK_MS = 100;
  /// Long enough for the activity to show up and keep the screen on by itself.
  private static final long SCREEN_ON_WAKE_LOCK_MS = 5_000;

  @NonNull
  private final Activity mActivity;
  @NonNull
  private final MapController mMapController;
  @NonNull
  private final NavigationRefreshRate mRefreshRate;
  @NonNull
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull
  private final Runnable mNoFixWake = () -> wake("no fix");
  @NonNull
  private final Runnable mCountdownTick = this::onCountdownTick;
  /// The activity gets no fixes while the screen is off.
  @NonNull
  private final LocationListener mScreenOffListener = location -> onFix(Framework.nativeGetRouteFollowingInfo());
  @NonNull
  private final NavBlackoutPolicy mPolicy = new NavBlackoutPolicy(SystemClock.elapsedRealtime());
  @Nullable
  private View mOverlay;
  @Nullable
  private View mCountdown;
  @Nullable
  private TextView mCountdownText;

  private boolean mEnabled;
  /// Set while the screen is off after a blackout, and is to be switched back on before the turn.
  private boolean mScreenOff;
  /// Set while the screen timeout may switch the screen off.
  private boolean mLettingScreenGoOff;
  /// Set by a touch during a countdown or a blackout, so that the rest of its gesture lands nowhere.
  private boolean mSwallowingGesture;

  NavBlackout(@NonNull Activity activity, @NonNull MapController mapController,
              @NonNull NavigationRefreshRate refreshRate)
  {
    mActivity = activity;
    mMapController = mapController;
    mRefreshRate = refreshRate;
  }

  /// @param enabled whether navigation runs on this screen with the setting on, and the activity is in front,
  ///                or the screen has gone off during a blackout
  void setEnabled(boolean enabled)
  {
    if (mEnabled == enabled)
      return;

    mEnabled = enabled;
    // Also restarts the idle period, so that the countdown does not start the moment navigation shows up.
    wake(enabled ? "enabled" : "disabled");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
      mActivity.setShowWhenLocked(enabled && canSwitchScreenOn());
  }

  boolean isScreenOff()
  {
    return mScreenOff;
  }

  /// Carries on with the activity paused if that is because the blackout has let the screen go off.
  void onPause()
  {
    if (!mEnabled || mPolicy.getState() != State.BLACK || !mLettingScreenGoOff || isInteractive())
      return;

    Logger.i(TAG, "Screen off");
    mScreenOff = true;
    MwmApplication.from(mActivity).getLocationHelper().addListener(mScreenOffListener);
  }

  void onResume()
  {
    stopListeningWithScreenOff();
  }

  private void stopListeningWithScreenOff()
  {
    if (!mScreenOff)
      return;
    mScreenOff = false;
    MwmApplication.from(mActivity).getLocationHelper().removeListener(mScreenOffListener);
  }

  void onFix(@Nullable RoutingInfo info)
  {
    if (!mEnabled)
      return;

    final String wakeReason = getWakeReason(info);
    if (wakeReason != null)
    {
      wake(wakeReason);
      return;
    }

    if (mPolicy.onFix(info.distToTurnMeters, SystemClock.elapsedRealtime()))
    {
      Logger.i(TAG, mPolicy.getState() + " at " + Math.round(info.distToTurnMeters) + " m before the turn");
      apply();
    }
    if (mPolicy.getState() == State.BLACK)
    {
      mHandler.removeCallbacks(mNoFixWake);
      mHandler.postDelayed(mNoFixWake, NO_FIX_WAKE_MS);
    }
  }

  @Nullable
  private String getWakeReason(@Nullable RoutingInfo info)
  {
    if (info == null)
      return "no route info";
    if (info.shouldPlayWarningSignal() || info.isSpeedCamLimitExceeded())
      return "speed camera";
    // Other windows, like the keyboard or a dialog, would stay lit above the overlay, and typing on the
    // keyboard never reaches the activity to count as a touch. The lock screen takes the focus when the
    // screen goes off.
    if (!isInteractive())
      return null;
    if (!mActivity.hasWindowFocus())
      return "window focus lost";
    final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
    if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()))
      return "keyboard";
    return null;
  }

  /// @return whether the event has been used up for keeping the screen on, and must not reach anything else
  boolean onTouchEvent(@NonNull MotionEvent ev)
  {
    final int action = ev.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN)
      mSwallowingGesture = mPolicy.getState() != State.LIT;

    final boolean swallowed = mSwallowingGesture;
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
      mSwallowingGesture = false;

    wake("touch");
    return swallowed;
  }

  void wake(@NonNull String reason)
  {
    final State before = mPolicy.getState();
    if (!mPolicy.wake(SystemClock.elapsedRealtime()))
      return;
    Logger.i(TAG, "Woken from " + before + " by " + reason);
    apply();
    if (mScreenOff)
    {
      stopListeningWithScreenOff();
      switchScreenOn();
    }
  }

  @SuppressWarnings("deprecation") // setTurnScreenOn() only works for an activity being brought to the front.
  private void switchScreenOn()
  {
    final PowerManager pm = (PowerManager) mActivity.getSystemService(Context.POWER_SERVICE);
    pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "OrganicMaps:NavBlackout")
        .acquire(SCREEN_ON_WAKE_LOCK_MS);
  }

  private boolean isInteractive()
  {
    return ((PowerManager) mActivity.getSystemService(Context.POWER_SERVICE)).isInteractive();
  }

  /// Whether a wake lock may switch the screen on: from Android 14, only with the "Turn screen on" special access.
  private boolean canSwitchScreenOn()
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1)
      return false;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      return true;
    final String op = AppOpsManager.permissionToOp(Manifest.permission.TURN_SCREEN_ON);
    return op != null
 && mActivity.getSystemService(AppOpsManager.class)
            .unsafeCheckOpNoThrow(op, Process.myUid(), mActivity.getPackageName())
        == AppOpsManager.MODE_ALLOWED;
  }

  /// Lets the screen timeout switch the screen off during a blackout, as far as it can be switched back on.
  private void letScreenGoOff(boolean black)
  {
    final boolean letScreenGoOff = black && canSwitchScreenOn();
    if (letScreenGoOff == mLettingScreenGoOff)
      return;
    mLettingScreenGoOff = letScreenGoOff;
    if (letScreenGoOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
      mActivity.setShowWhenLocked(true);
    Utils.keepScreenOn(!letScreenGoOff && (Config.isKeepScreenOnEnabled() || RoutingController.get().isNavigating()),
                       mActivity.getWindow());
  }

  private void onCountdownTick()
  {
    if (mPolicy.onTick(SystemClock.elapsedRealtime()))
      apply();
    else
      updateCountdown();
  }

  private void updateCountdown()
  {
    if (mPolicy.getState() != State.COUNTDOWN || mCountdownText == null)
      return;

    final int seconds = mPolicy.getCountdownSeconds(SystemClock.elapsedRealtime());
    mCountdownText.setText(mActivity.getString(R.string.nav_blackout_countdown, seconds));
    mHandler.postDelayed(mCountdownTick, COUNTDOWN_TICK_MS);
  }

  private void apply()
  {
    final State state = mPolicy.getState();
    final boolean black = state == State.BLACK;
    mHandler.removeCallbacks(mCountdownTick);
    if (!black)
      mHandler.removeCallbacks(mNoFixWake);

    final View overlay = getOverlay();
    UiUtils.showIf(state != State.LIT, overlay);
    overlay.setBackgroundColor(black ? Color.BLACK : Color.TRANSPARENT);
    UiUtils.showIf(state == State.COUNTDOWN, mCountdown);
    updateCountdown();

    UiUtils.setFullscreen(mActivity, black);
    final WindowManager.LayoutParams params = mActivity.getWindow().getAttributes();
    params.screenBrightness = black ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                                    : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    mActivity.getWindow().setAttributes(params);

    mRefreshRate.setBlackout(black);
    mMapController.setRenderingSuspended(black);
    letScreenGoOff(black);
  }

  @NonNull
  private View getOverlay()
  {
    if (mOverlay == null)
    {
      // Added last to the decor view, so that it covers everything, system bar areas included.
      final ViewGroup decor = (ViewGroup) mActivity.getWindow().getDecorView();
      mOverlay = LayoutInflater.from(mActivity).inflate(R.layout.nav_blackout, decor, false);
      mCountdown = mOverlay.findViewById(R.id.countdown);
      mCountdownText = mOverlay.findViewById(R.id.countdown_text);
      decor.addView(mOverlay);
    }
    return mOverlay;
  }
}
