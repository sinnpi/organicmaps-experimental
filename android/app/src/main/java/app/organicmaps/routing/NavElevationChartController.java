package app.organicmaps.routing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.location.LocationState;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import app.organicmaps.widget.placepage.AxisValueFormatter;
import app.organicmaps.widget.placepage.ElevationChartUtils;
import app.organicmaps.widget.placepage.ElevationProfileChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.formatter.ValueFormatter;

/**
 * The elevation profile shown at the bottom of the screen while navigating: the terrain ahead of
 * the user, anchored so that the left edge of the chart is the current position. Pinching changes
 * how far ahead it looks, dragging one finger moves a cursor along the profile and shows that point
 * on the map.
 */
public class NavElevationChartController
{
  private static final float MIN_WINDOW_METERS = 1000f;
  private static final float DEFAULT_WINDOW_METERS = 10000f;
  private static final long IDLE_RESTORE_DELAY_MS = 5000;
  /// Coalesces the burst of selections a single drag produces into one camera move.
  private static final long RECENTER_DELAY_MS = 60;
  /// Roughly how long the map takes to ease into the first framing of a gesture.
  private static final long FRAMING_ANIMATION_MS = 800;

  @NonNull
  private final Context mContext;
  @NonNull
  private final View mFrame;
  @NonNull
  private final ElevationProfileChart mChart;
  @NonNull
  private final RouteElevationChartController mProfile;
  @NonNull
  private final TextView mWindowLabel;
  @Nullable
  private final Drawable mDefaultFrameBackground;
  @Nullable
  private final Drawable mDefaultWindowLabelBackground;
  @NonNull
  private final ColorStateList mDefaultWindowLabelTextColors;
  @NonNull
  private final Handler mHandler = new Handler(Looper.getMainLooper());

  private final Runnable mRestoreRunnable = this::restoreFollowing;
  private final Runnable mRecenterRunnable = this::recenterMap;

  private float mRouteLengthMeters;
  private float mWindowMeters = DEFAULT_WINDOW_METERS;
  private float mPositionMeters;

  private float mAppliedYLower = Float.NaN;
  private float mAppliedYUpper = Float.NaN;

  private boolean mScrubbing;
  private boolean mRecenterScheduled;
  private boolean mMapFramed;
  private long mFramingAnimationEndUptime;
  private boolean mLowPowerMode;
  private float mPendingScrubMeters;
  private float mWindowAtPinchStart;

  public NavElevationChartController(@NonNull View frame)
  {
    mContext = frame.getContext();
    mFrame = frame;
    mChart = frame.findViewById(R.id.elevation_profile_chart);
    mWindowLabel = frame.findViewById(R.id.nav_elevation_window);
    mDefaultFrameBackground = mFrame.getBackground();
    mDefaultWindowLabelBackground = mWindowLabel.getBackground();
    mDefaultWindowLabelTextColors = mWindowLabel.getTextColors();

    mProfile = new RouteElevationChartController(frame);
    // Navigation owns the viewport; gestures select a point or change the look-ahead window.
    mChart.setDragEnabled(false);
    mChart.setScaleXEnabled(false);
    mChart.setDoubleTapToZoomEnabled(false);
    mChart.setAlwaysSelectOnDrag(true);
    // Labels are placed at the edges and the middle of the visible window, so that shifting them by
    // the current position yields round "distance ahead" values.
    mChart.getXAxis().setLabelCount(3, true);

    mProfile.setListener(new RouteElevationChartController.ElevationSelectionListener() {
      @Override
      public void onElevationPointSelected(double distanceMeters)
      {
        onPointScrubbed((float) distanceMeters);
      }

      @Override
      public void onElevationPointDeselected()
      {}
    });

    // A pinch is handled here rather than by the chart's own zoom, which would drag the viewport
    // around the focal point and pull the current position away from the left edge.
    mChart.setOnWindowScaleListener(new ElevationProfileChart.OnWindowScaleListener() {
      @Override
      public void onWindowScaleStart()
      {
        mWindowAtPinchStart = mWindowMeters;
        // A gesture that turns out to be a pinch must leave the map alone, so undo the scrub its
        // first finger may already have started.
        cancelPendingWork();
      }

      @Override
      public void onWindowScale(float spanRatio)
      {
        mWindowMeters = clampWindow(mWindowAtPinchStart * spanRatio, mRouteLengthMeters);
        applyWindow();
      }
    });
  }

  /**
   * @param data Altitudes along the whole route, or null to hide the profile.
   */
  public void setData(@Nullable RouteAltitudeData data)
  {
    cancelPendingWork();

    mProfile.setData(data, false /* resetZoom */);
    if (mProfile.getValues().isEmpty())
    {
      mRouteLengthMeters = 0;
      mPositionMeters = 0;
      UiUtils.hide(mFrame);
      return;
    }

    mRouteLengthMeters = (float) data.getDistance(data.getSize() - 1);
    // A rebuilt route has a new distance origin. Never retain progress from the old geometry.
    mPositionMeters = Math.max(0f, (float) Framework.nativeGetRouteDistanceFromBeginMeters());
    mAppliedYLower = Float.NaN;
    mAppliedYUpper = Float.NaN;
    mChart.setVisibleXRangeMinimum(MIN_WINDOW_METERS);

    UiUtils.show(mFrame);
    // The window is applied in terms of the viewport, which needs a laid out chart.
    mChart.post(this::applyWindow);
  }

  public boolean isShown()
  {
    return UiUtils.isVisible(mFrame);
  }

  /** Clears an active chart/map selection and restores route following. */
  public boolean handleBackPress()
  {
    if (!mScrubbing)
      return false;

    cancelPendingWork();
    return true;
  }

  public void setLowPowerMode(boolean enabled)
  {
    if (mLowPowerMode == enabled)
      return;

    mLowPowerMode = enabled;
    applyStyle();
    if (!mProfile.getValues().isEmpty())
      updatePositionLine();
  }

  private void applyStyle()
  {
    mProfile.setLowPowerMode(mLowPowerMode);
    if (mLowPowerMode)
    {
      mFrame.setBackgroundColor(Color.BLACK);
      mWindowLabel.setBackgroundColor(Color.BLACK);
      mWindowLabel.setTextColor(0xFFBDBDBD);
    }
    else
    {
      mFrame.setBackground(mDefaultFrameBackground);
      mWindowLabel.setBackground(mDefaultWindowLabelBackground);
      mWindowLabel.setTextColor(mDefaultWindowLabelTextColors);
    }
  }

  /// Advances the profile to the current position. Cheap to call when the profile is hidden: the
  /// route is only queried once there is something to draw.
  public void onLocationUpdate()
  {
    if (mProfile.getValues().isEmpty())
      return;

    onPositionChanged(Framework.nativeGetRouteDistanceFromBeginMeters());
  }

  /**
   * @param distanceMeters Distance travelled along the route, or a negative value if unknown.
   */
  private void onPositionChanged(double distanceMeters)
  {
    if (distanceMeters < 0)
      return;

    mPositionMeters = (float) distanceMeters;
    // While the user is scrubbing, the chart belongs to them.
    if (!mScrubbing)
      anchorViewport();
  }

  private void applyWindow()
  {
    if (mChart.getData() == null || mRouteLengthMeters <= 0)
      return;

    mWindowMeters = clampWindow(mWindowMeters, mRouteLengthMeters);
    mChart.fitScreen();
    mChart.zoom(mRouteLengthMeters / mWindowMeters, 1f, 0f, 0f);
    anchorViewport();
    updateWindowLabel();
  }

  private void anchorViewport()
  {
    if (mChart.getData() == null)
      return;

    mChart.moveViewToX(mPositionMeters);
    updateAxisOrigin();
    updatePositionLine();
    updateYRange();
  }

  /// Makes the x labels read as distances ahead of the left edge of the chart.
  private void updateAxisOrigin()
  {
    final ValueFormatter formatter = mChart.getXAxis().getValueFormatter();
    if (formatter instanceof AxisValueFormatter)
      ((AxisValueFormatter) formatter).setOrigin(mChart.getLowestVisibleX());
  }

  /// Near the end of the route the viewport can no longer scroll, so the current position drifts
  /// away from the left edge. Mark it explicitly once that happens.
  private void updatePositionLine()
  {
    final XAxis xAxis = mChart.getXAxis();
    xAxis.removeAllLimitLines();

    if (!shouldShowPositionLine(mPositionMeters, mChart.getLowestVisibleX(), mWindowMeters))
      return;

    final LimitLine line = new LimitLine(mPositionMeters);
    line.setLineColor(mLowPowerMode ? Color.WHITE
                                    : ThemeUtils.getColor(mContext, androidx.appcompat.R.attr.colorAccent));
    line.setLineWidth(2f);
    line.setLabel("");
    xAxis.addLimitLine(line);
  }

  /// Rescales the y axis to the visible window, otherwise a short window of a long route looks flat.
  private void updateYRange()
  {
    final float[] range = ElevationChartUtils.computeAltitudeRange(mProfile.getValues(), mChart.getLowestVisibleX(),
                                                                   mChart.getHighestVisibleX());
    if (range == null)
      return;

    mProfile.setAltitudeRange(range[0], range[1]);

    final YAxis y = mChart.getAxisLeft();
    if (y.getAxisMinimum() == mAppliedYLower && y.getAxisMaximum() == mAppliedYUpper)
      return;

    mAppliedYLower = y.getAxisMinimum();
    mAppliedYUpper = y.getAxisMaximum();
    mChart.notifyDataSetChanged();
  }

  private void updateWindowLabel()
  {
    mWindowLabel.setText(StringUtils.nativeFormatDistance(mWindowMeters).toString(mContext));
  }

  private void onPointScrubbed(float distanceMeters)
  {
    mScrubbing = true;
    Framework.nativeRouteSetElevationActivePoint(distanceMeters);
    scheduleRecenter(distanceMeters);
    scheduleRestore();
  }

  private void scheduleRecenter(float distanceMeters)
  {
    mPendingScrubMeters = distanceMeters;
    if (mRecenterScheduled)
      return;

    mRecenterScheduled = true;
    // Re-framing the map while it is still easing into the first framing would cut that animation
    // short and put back the jump it is there to avoid.
    mHandler.postDelayed(mRecenterRunnable,
                         Math.max(RECENTER_DELAY_MS, mFramingAnimationEndUptime - SystemClock.uptimeMillis()));
  }

  /// Frames the stretch of route between the user and the scrubbed point, so the marker is always
  /// seen in relation to where the user actually is.
  private void recenterMap()
  {
    mRecenterScheduled = false;
    // The first framing of a gesture is the long way out of the navigation camera, and is animated
    // so that a tap does not snap the map to another zoom level. The ones that follow track the
    // finger and have to be immediate.
    final boolean animated = !mMapFramed;
    mMapFramed = true;
    mFramingAnimationEndUptime = animated ? SystemClock.uptimeMillis() + FRAMING_ANIMATION_MS : 0;
    // Repeated on every move, not just the first: this also resets the routing not-follow timer,
    // which would otherwise snap the camera back to the user in the middle of a long scrub.
    Framework.nativeStopLocationFollow();
    Framework.nativeShowRouteStretch(mPositionMeters, mPendingScrubMeters, animated);
  }

  private void scheduleRestore()
  {
    mHandler.removeCallbacks(mRestoreRunnable);
    mHandler.postDelayed(mRestoreRunnable, IDLE_RESTORE_DELAY_MS);
  }

  /// Drops the cursor and the map marker and hands the map back to navigation. A gesture that only
  /// zoomed the chart leaves the map alone: there is no selection of ours to clear.
  private void restoreFollowing()
  {
    mMapFramed = false;
    mFramingAnimationEndUptime = 0;

    if (mScrubbing)
    {
      mScrubbing = false;
      mChart.highlightValue(null, false);
      Framework.nativeRouteRemoveElevationActivePoint();
      // In routing this goes straight back to follow-and-rotate. Skip it if the user has already
      // recentred the map themselves.
      if (LocationState.getMode() == LocationState.NOT_FOLLOW)
        LocationState.nativeSwitchToNextMode();
    }

    anchorViewport();
  }

  /// A window shorter than a kilometre is unreadable, and there is nothing beyond the route's end.
  static float clampWindow(float windowMeters, float routeLengthMeters)
  {
    if (routeLengthMeters <= MIN_WINDOW_METERS)
      return Math.max(MIN_WINDOW_METERS, routeLengthMeters);

    return Math.max(MIN_WINDOW_METERS, Math.min(windowMeters, routeLengthMeters));
  }

  /// The current position sits on the left edge until the viewport runs out of route to scroll
  /// through; only then is it worth drawing a line for it.
  static boolean shouldShowPositionLine(float positionMeters, float lowestVisibleMeters, float windowMeters)
  {
    return positionMeters - lowestVisibleMeters > windowMeters * 0.01f;
  }

  private void cancelPendingWork()
  {
    mHandler.removeCallbacks(mRestoreRunnable);
    mHandler.removeCallbacks(mRecenterRunnable);
    mRecenterScheduled = false;
    // A route rebuild can land mid-scrub; don't leave the map off-position with a stale marker.
    restoreFollowing();
  }
}
