package app.organicmaps.routing;

import static app.organicmaps.sdk.util.Utils.dimen;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.maplayer.MapButtonsViewModel;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.maplayer.traffic.TrafficManager;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.sdk.widget.roadshield.RoadShieldUtils;
import app.organicmaps.sdk.widgets.lanes.LanesView;
import app.organicmaps.sdk.widgets.speedlimit.SpeedLimitView;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.util.WindowInsetUtils;
import app.organicmaps.util.WindowInsetUtils.BaselinePaddingInsetsListener;
import app.organicmaps.widget.menu.NavMenu;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class NavigationController implements TrafficManager.TrafficCallback, NavMenu.NavMenuListener
{
  private final View mFrame;

  private final ImageView mNextTurnImage;
  private final TextView mNextTurnDistance;

  private final View mNextNextTurnFrame;
  private final ImageView mNextNextTurnImage;

  private final View mStreetFrame;
  private final View mNextTurnFrame;
  private final TextView mNextStreet;

  @NonNull
  private final LanesView mLanesView;
  @NonNull
  private final SpeedLimitView mSpeedLimit;

  private final MapButtonsViewModel mMapButtonsViewModel;
  private final View mTopFrame;
  private final View mNextTurnContainer;

  private final NavMenu mNavMenu;
  private final View mNavBottomSheet;
  private final View mNavBottomSheetLineFrame;
  private final View mNavigationBarBackground;
  @NonNull
  private final NavMenu.OnMenuSizeChangedListener mOnMenuSizeChangedListener;
  @NonNull
  private final NavElevationChartController mElevationChart;
  @NonNull
  private final NavigationRefreshRate mRefreshRate;

  @Nullable
  private final Drawable mDefaultStreetFrameBackground;
  @Nullable
  private final Drawable mDefaultNextTurnFrameBackground;
  @Nullable
  private final Drawable mDefaultNextNextTurnFrameBackground;
  @Nullable
  private final Drawable mDefaultNavBottomSheetBackground;
  @Nullable
  private final ColorStateList mDefaultNavBottomSheetBackgroundTint;
  @Nullable
  private final Drawable mDefaultNavigationBarBackground;
  @NonNull
  private final ColorStateList mDefaultNextStreetTextColors;
  @NonNull
  private final ColorStateList mDefaultNextTurnTextColors;
  @Nullable
  private final ColorStateList mDefaultNextTurnImageTint;
  @Nullable
  private final ColorStateList mDefaultNextNextTurnImageTint;

  private boolean mVisibleViewportNarrowed;
  private int mVisibleViewportBottomInset;
  private boolean mLowPowerMode;
  View.OnClickListener mOnSettingsClickListener;
  View.OnClickListener mOnVoiceSettingsClickListener;

  public NavigationController(AppCompatActivity activity, View.OnClickListener onSettingsClickListener,
                              View.OnClickListener onVoiceSettingsClickListener,
                              NavMenu.OnMenuSizeChangedListener onMenuSizeChangedListener)
  {
    mMapButtonsViewModel = new ViewModelProvider(activity).get(MapButtonsViewModel.class);

    mFrame = activity.findViewById(R.id.navigation_frame);
    mNavMenu = new NavMenu(activity, this, onMenuSizeChangedListener);
    mOnMenuSizeChangedListener = onMenuSizeChangedListener;
    mOnSettingsClickListener = onSettingsClickListener;
    mOnVoiceSettingsClickListener = onVoiceSettingsClickListener;

    // Top frame
    mTopFrame = mFrame.findViewById(R.id.nav_top_frame);
    mTopFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      mMapButtonsViewModel.setTopHeaderHeight(computeNavContentHeight());
      updateVisibleViewport();
    });
    mNextTurnFrame = mTopFrame.findViewById(R.id.nav_next_turn_frame);
    mNextTurnImage = mNextTurnFrame.findViewById(R.id.turn);
    mNextTurnDistance = mNextTurnFrame.findViewById(R.id.distance);

    mNextNextTurnFrame = mTopFrame.findViewById(R.id.nav_next_next_turn_frame);
    mNextNextTurnImage = mNextNextTurnFrame.findViewById(R.id.turn);

    mStreetFrame = mTopFrame.findViewById(R.id.street_frame);
    mNextStreet = mStreetFrame.findViewById(R.id.street);

    mLanesView = mTopFrame.findViewById(R.id.lanes);

    mSpeedLimit = mTopFrame.findViewById(R.id.nav_speed_limit);

    mElevationChart = new NavElevationChartController(mFrame.findViewById(R.id.nav_elevation_frame));
    mRefreshRate = new NavigationRefreshRate(activity.getWindow());

    // Blank rectangle below the navbar that hides menu content behind it.
    mNavigationBarBackground = mFrame.findViewById(R.id.nav_bottom_sheet_nav_bar);
    final View navigationBarBackground = mNavigationBarBackground;
    final View navBottomSheet = mFrame.findViewById(R.id.nav_bottom_sheet);
    mNavBottomSheet = navBottomSheet;
    mNavBottomSheetLineFrame = mNavBottomSheet.findViewById(R.id.line_frame);
    mNextTurnContainer = mFrame.findViewById(R.id.nav_next_turn_container);

    mDefaultStreetFrameBackground = mStreetFrame.getBackground();
    mDefaultNextTurnFrameBackground = mNextTurnFrame.getBackground();
    mDefaultNextNextTurnFrameBackground = mNextNextTurnFrame.getBackground();
    mDefaultNavBottomSheetBackground = mNavBottomSheet.getBackground();
    mDefaultNavBottomSheetBackgroundTint = ViewCompat.getBackgroundTintList(mNavBottomSheet);
    mDefaultNavigationBarBackground = mNavigationBarBackground.getBackground();
    mDefaultNextStreetTextColors = mNextStreet.getTextColors();
    mDefaultNextTurnTextColors = mNextTurnDistance.getTextColors();
    mDefaultNextTurnImageTint = ImageViewCompat.getImageTintList(mNextTurnImage);
    mDefaultNextNextTurnImageTint = ImageViewCompat.getImageTintList(mNextNextTurnImage);

    ViewCompat.setOnApplyWindowInsetsListener(mStreetFrame, BaselinePaddingInsetsListener.excludeBottom());

    ViewCompat.setOnApplyWindowInsetsListener(mTopFrame, (v, windowInsets) -> {
      final Insets safeDrawing = windowInsets.getInsets(WindowInsetUtils.TYPE_SAFE_DRAWING);
      // Pad the start edge (LTR: left, RTL: right) so the next-turn container clears side
      // cutouts and system bars regardless of layout direction.
      final boolean isRtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
      final int startInset = isRtl ? safeDrawing.right : safeDrawing.left;
      mNextTurnContainer.setPaddingRelative(startInset, mNextTurnContainer.getPaddingTop(),
                                            mNextTurnContainer.getPaddingEnd(), mNextTurnContainer.getPaddingBottom());
      return windowInsets;
    });

    ViewCompat.setOnApplyWindowInsetsListener(navigationBarBackground, (v, windowInsets) -> {
      final ViewGroup.LayoutParams lp = v.getLayoutParams();
      lp.height = windowInsets.getInsets(WindowInsetUtils.TYPE_SAFE_DRAWING).bottom;
      v.setLayoutParams(lp);
      return windowInsets;
    });

    // navBottomSheet.getWidth() is 0 on the first inset dispatch (layout hasn't run yet),
    // so mirror the width through a layout listener instead of reading it inline.
    navBottomSheet.addOnLayoutChangeListener((v, l, t, r, b, oL, oT, oR, oB) -> {
      updateVisibleViewport();
      final int width = r - l;
      final ViewGroup.LayoutParams lp = navigationBarBackground.getLayoutParams();
      if (lp.width != width)
      {
        lp.width = width;
        navigationBarBackground.setLayoutParams(lp);
      }
    });
  }

  // Height the search sheet must clear when expanded over the navigation top frame: the always
  // shown street-name frame plus the taller of the turn/speed column or the lanes strip (the two
  // overlap rather than stack, so take the max). The turn/speed column is only laid out below the
  // street frame in portrait.
  private int computeNavContentHeight()
  {
    int turnAndSpeedHeight = 0;
    if (mFrame.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
    {
      if (UiUtils.isVisible(mNextTurnContainer))
        turnAndSpeedHeight += mNextTurnContainer.getHeight();
      if (UiUtils.isVisible(mSpeedLimit))
        turnAndSpeedHeight += mSpeedLimit.getHeight();
    }
    final int lanesHeight = UiUtils.isVisible(mLanesView) ? mLanesView.getHeight() : 0;
    return mStreetFrame.getHeight() + Math.max(turnAndSpeedHeight, lanesHeight);
  }

  private void updateVehicle(@NonNull RoutingInfo info)
  {
    mNextTurnDistance.setText(Utils.formatDistance(mFrame.getContext(), info.distToTurn));
    mNextTurnImage.setImageResource(info.carDirection.getTurnRes(info.exitNum));

    final boolean showNextNextTurn = info.hasNextNextTurn();
    UiUtils.showIf(showNextNextTurn, mNextNextTurnFrame);
    if (showNextNextTurn)
      mNextNextTurnImage.setImageResource(info.nextCarDirection.getTurnRes());

    if (mLowPowerMode)
    {
      mLanesView.setLanes(null);
      UiUtils.hide(mSpeedLimit);
    }
    else
    {
      mLanesView.setLanes(info.lanes);
      UiUtils.show(mSpeedLimit);
      updateSpeedLimit(info);
    }
  }

  private void updatePedestrian(@NonNull RoutingInfo info)
  {
    mNextTurnDistance.setText(Utils.formatDistance(mFrame.getContext(), info.distToTurn));
    mNextTurnImage.setImageResource(info.pedestrianDirection.getTurnRes());
  }

  public void update(@Nullable RoutingInfo info)
  {
    if (info == null)
      return;

    if (Router.get() == Router.Pedestrian)
      updatePedestrian(info);
    else
      updateVehicle(info);

    updateStreetView(info);
    mNavMenu.update(info);
    mElevationChart.onLocationUpdate();
  }

  /**
   * Refreshes the elevation profile from the current route. Must be called whenever the route
   * changes, including reroutes and activity recreation.
   */
  public void refreshElevationData()
  {
    final boolean show = Config.isNavElevationProfileEnabled() && RoutingController.get().isNavigating();
    mElevationChart.setData(show ? Framework.nativeGetRouteAltitudeData() : null);
    updateVisibleViewport();
  }

  public void refreshLowPowerMode()
  {
    final boolean navigating = RoutingController.get().isNavigating();
    final boolean carDisplayUsed = MwmApplication.from(mFrame.getContext()).getDisplayManager().isCarDisplayUsed();
    setLowPowerMode(OledPowerSaveMode.shouldEnable(Config.isOledPowerSaveEnabled(), carDisplayUsed));
    // The phone panel is not the one being looked at when the car display drives navigation.
    mRefreshRate.update(navigating && !carDisplayUsed, mLowPowerMode);
  }

  /**
   * Navigation trades refresh rate for power, which is only tolerable while the display is not
   * following a finger. Both ends of the gesture are reported, so that a long drag along the
   * elevation profile stays at the full rate for as long as it lasts.
   */
  public void onInteractionStarted()
  {
    mRefreshRate.onInteractionStarted();
  }

  public void onInteractionEnded()
  {
    mRefreshRate.onInteractionEnded();
  }

  public boolean isLowPowerMode()
  {
    return mLowPowerMode;
  }

  private void setLowPowerMode(boolean enabled)
  {
    if (mLowPowerMode == enabled)
    {
      // The native Framework outlives activity recreation, so keep both sides synchronized even
      // when this newly-created controller already has the desired Java default.
      Framework.nativeSetLowPowerNavigationMode(enabled);
      return;
    }

    mLowPowerMode = enabled;
    Framework.nativeSetLowPowerNavigationMode(enabled);
    mNavMenu.setLowPowerMode(enabled);
    mElevationChart.setLowPowerMode(enabled);
    mMapButtonsViewModel.setLowPowerMode(enabled);

    if (enabled)
    {
      mStreetFrame.setBackgroundColor(Color.BLACK);
      mNextTurnFrame.setBackgroundColor(Color.BLACK);
      mNextNextTurnFrame.setBackgroundColor(Color.BLACK);
      mNavBottomSheet.setBackgroundColor(Color.BLACK);
      ViewCompat.setBackgroundTintList(mNavBottomSheet, ColorStateList.valueOf(Color.BLACK));
      mNavigationBarBackground.setBackgroundColor(Color.BLACK);
      mNextStreet.setTextColor(Color.WHITE);
      mNextTurnDistance.setTextColor(Color.WHITE);
      ImageViewCompat.setImageTintList(mNextTurnImage, ColorStateList.valueOf(Color.WHITE));
      ImageViewCompat.setImageTintList(mNextNextTurnImage, ColorStateList.valueOf(Color.WHITE));
      mLanesView.setLanes(null);
      UiUtils.hide(mSpeedLimit);
    }
    else
    {
      mStreetFrame.setBackground(mDefaultStreetFrameBackground);
      mNextTurnFrame.setBackground(mDefaultNextTurnFrameBackground);
      mNextNextTurnFrame.setBackground(mDefaultNextNextTurnFrameBackground);
      mNavBottomSheet.setBackground(mDefaultNavBottomSheetBackground);
      ViewCompat.setBackgroundTintList(mNavBottomSheet, mDefaultNavBottomSheetBackgroundTint);
      mNavigationBarBackground.setBackground(mDefaultNavigationBarBackground);
      mNextStreet.setTextColor(mDefaultNextStreetTextColors);
      mNextTurnDistance.setTextColor(mDefaultNextTurnTextColors);
      ImageViewCompat.setImageTintList(mNextTurnImage, mDefaultNextTurnImageTint);
      ImageViewCompat.setImageTintList(mNextNextTurnImage, mDefaultNextNextTurnImageTint);
    }

    final RoutingInfo info = RoutingController.get().getCachedRoutingInfo();
    if (info != null)
      update(info);
    refreshElevationData();
    updateVisibleViewport();
  }

  /**
   * How much of the bottom of the screen the visible viewport reported to the map already leaves
   * out, so that the same UI is not counted a second time as an offset from the bottom.
   */
  public int getViewportBottomInset()
  {
    return mVisibleViewportBottomInset;
  }

  /**
   * Tells the map which part of the screen the navigation UI leaves free, so that my position and
   * a camera move stay where they can actually be seen. Only done while the elevation profile is
   * shown: it is what makes the bottom sheet tall enough for the difference to matter, and the
   * viewport is shared with the place page, which sets it for its own purposes.
   */
  private void updateVisibleViewport()
  {
    final int width = mFrame.getWidth();
    final int height = mFrame.getHeight();
    if (width == 0 || height == 0 || MwmApplication.from(mFrame.getContext()).getDisplayManager().isCarDisplayUsed())
      return;

    final boolean narrow = mElevationChart.isShown() && UiUtils.isVisible(mFrame);
    if (!narrow && !mVisibleViewportNarrowed)
      return;

    // In landscape the bottom sheet is a column down the left instead, so it is width that it takes
    // from the map.
    final boolean portrait = mFrame.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    final int bottomInset =
        narrow && portrait ? mNavBottomSheetLineFrame.getHeight() + mNavigationBarBackground.getHeight() : 0;

    if (narrow)
    {
      // Neither frame's own bounds say where the free part of the screen is: the top frame covers
      // the whole screen with only its widgets drawn on it, and the bottom sheet is moved into
      // place by its behaviour after it has been laid out.
      Framework.nativeSetVisibleRect(portrait ? 0 : mNavBottomSheet.getWidth(), computeNavContentHeight(), width,
                                     height - bottomInset);
    }
    else
      Framework.nativeSetVisibleRect(0, 0, width, height);

    if (mVisibleViewportNarrowed == narrow && mVisibleViewportBottomInset == bottomInset)
      return;

    mVisibleViewportNarrowed = narrow;
    mVisibleViewportBottomInset = bottomInset;
    mOnMenuSizeChangedListener.OnMenuSizeChange();
  }

  private void updateStreetView(@NonNull RoutingInfo info)
  {
    boolean hasStreet = !TextUtils.isEmpty(info.nextStreet);
    // Sic: don't use UiUtils.showIf() here because View.GONE breaks layout
    // https://github.com/organicmaps/organicmaps/issues/3732
    UiUtils.visibleIf(hasStreet, mStreetFrame);
    if (!TextUtils.isEmpty(info.nextStreet))
    {
      if (mLowPowerMode)
        mNextStreet.setText(info.nextStreet);
      else
        mNextStreet.setText(RoadShieldUtils.createStreetTextWithShields(info.nextStreet, info.nextStreetRoadShields,
                                                                        mNextStreet.getTextSize()));
    }
    int margin = dimen(mFrame.getContext(), R.dimen.nav_frame_padding);
    if (hasStreet)
      margin += mStreetFrame.getHeight();
    mMapButtonsViewModel.setTopButtonsMarginTop(margin);
  }

  public void show(boolean show)
  {
    refreshLowPowerMode();
    if (!show)
      mRefreshRate.update(false /* navigating */, mLowPowerMode);

    if (show && !UiUtils.isVisible(mFrame))
    {
      collapseNavMenu();
      // Seed the panel from the already-built route so it isn't empty until the first GPS fix arrives.
      refreshElevationData();
      update(RoutingController.get().getCachedRoutingInfo());
    }
    UiUtils.showIf(show, mFrame);
    if (!show)
    {
      mElevationChart.setData(null);
      mMapButtonsViewModel.setTopHeaderHeight(0);
      updateVisibleViewport();
    }
  }

  public boolean handleBackPress()
  {
    return mElevationChart.handleBackPress();
  }

  public boolean isNavMenuCollapsed()
  {
    return mNavMenu.getBottomSheetState() == BottomSheetBehavior.STATE_COLLAPSED;
  }

  public boolean isNavMenuHidden()
  {
    return mNavMenu.getBottomSheetState() == BottomSheetBehavior.STATE_HIDDEN;
  }

  public void collapseNavMenu()
  {
    mNavMenu.collapseNavBottomSheet();
  }

  public void refresh()
  {
    refreshLowPowerMode();
    refreshElevationData();
    mNavMenu.refreshTts();
  }

  @Override
  public void onEnabled()
  {
    // mNavMenu.refreshTraffic();
  }

  @Override
  public void onDisabled()
  {
    // mNavMenu.refreshTraffic();
  }

  @Override
  public void onWaitingData()
  {
    // no op
  }

  @Override
  public void onOutdated()
  {
    // no op
  }

  @Override
  public void onNoData()
  {
    // no op
  }

  @Override
  public void onNetworkError()
  {
    // no op
  }

  @Override
  public void onExpiredData()
  {
    // no op
  }

  @Override
  public void onExpiredApp()
  {
    // no op
  }

  @Override
  public void onSettingsClicked()
  {
    mOnSettingsClickListener.onClick(null);
  }

  @Override
  public void onTtsVoiceSettingsClicked()
  {
    mOnVoiceSettingsClickListener.onClick(null);
  }

  @Override
  public void onStopClicked()
  {
    RoutingController.get().cancel();
  }

  private void updateSpeedLimit(@NonNull final RoutingInfo info)
  {
    final Location location = MwmApplication.from(mFrame.getContext()).getLocationHelper().getSavedLocation();
    final boolean speedLimitExceeded = location != null && info.speedLimitMps < location.getSpeed();
    mSpeedLimit.setSpeedLimit(StringUtils.nativeFormatSpeed(info.speedLimitMps), speedLimitExceeded);
  }
}
