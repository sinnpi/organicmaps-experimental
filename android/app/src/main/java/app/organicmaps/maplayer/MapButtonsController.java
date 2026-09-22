package app.organicmaps.maplayer;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.routing.RoutingPlanViewModel;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.downloader.UpdateInfo;
import app.organicmaps.sdk.location.TrackRecorder;
import app.organicmaps.sdk.maplayer.isolines.IsolinesManager;
import app.organicmaps.sdk.maplayer.subway.SubwayManager;
import app.organicmaps.sdk.maplayer.traffic.TrafficManager;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.search.SearchPageViewModel;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.util.WindowInsetUtils;
import app.organicmaps.widget.menu.MyPositionButton;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;
import com.google.android.material.badge.ExperimentalBadgeUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapButtonsController extends Fragment
{
  Map<MapButtons, View> mButtonsMap;
  private View mFrame;
  private View mInnerLeftButtonsFrame;
  private View mInnerRightButtonsFrame;
  @Nullable
  private View mBottomButtonsFrame;
  @Nullable
  private LayersButton mToggleMapLayerButton;
  @Nullable
  FloatingActionButton mTrackRecordingStatusButton;
  @Nullable
  private MyPositionButton mNavMyPosition;
  private SearchWheel mSearchWheel;
  private BadgeDrawable mBadgeDrawable;
  @Nullable
  private ObjectAnimator mBlinkingAnimator;
  private float mContentHeight;
  private float mContentWidth;
  private boolean mIsNavigationLayout;
  private boolean mLowPowerMode;
  private float mButtonsScale;
  @NonNull
  private MapButtonAppearance[] mButtonAppearances = new MapButtonAppearance[0];
  @NonNull
  private MapButtonAppearance[] mSearchWheelAppearances = new MapButtonAppearance[0];
  @NonNull
  private MapButtonAppearance[] mBottomButtonAppearances = new MapButtonAppearance[0];
  @Nullable
  private ConstraintLayout mBottomButtonsRow;
  private int mDefaultBottomRowMaxWidth;
  private int mDefaultLeftButtonsBottomMargin;
  private int mDefaultRightButtonsBottomMargin;
  @Nullable
  private View mNavigationHeader;

  private final View.OnLayoutChangeListener mNavigationHeaderLayoutListener =
      (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateNavigationButtonsBottomMargin();

  private MapButtonClickListener mMapButtonClickListener;
  private PlacePageViewModel mPlacePageViewModel;
  private RoutingPlanViewModel mRoutingPlanViewModel;
  private MapButtonsViewModel mMapButtonsViewModel;
  private SearchPageViewModel mSearchPageViewModel;

  private final Observer<Integer> mPlacePageDistanceToTopObserver = translationY -> move(translationY, true);
  private final Observer<Integer> mRoutingBottomDistanceToTopObserver = translationY -> move(translationY, false);
  private final Observer<Boolean> mBottomButtonHiddenObserver = this::setBottomButtonsHidden;
  private final Observer<Integer> mSearchPageDistanceToTopObserver = this::moveForSearch;
  private final Observer<Boolean> mButtonHiddenObserver = this::setButtonsHidden;
  private final Observer<Integer> mMyPositionModeObserver = this::updateNavMyPositionButton;
  private final Observer<SearchWheel.SearchOption> mSearchOptionObserver = this::onSearchOptionChange;
  private final Observer<Boolean> mTrackRecorderObserver = (enable) ->
  {
    updateMenuBadge(enable);
    showButton(enable, MapButtons.trackRecordingStatus);
  };
  private final Observer<Integer> mTopButtonMarginObserver = this::updateTopButtonsMargin;
  private final Observer<Boolean> mLowPowerModeObserver = this::setLowPowerMode;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    final FragmentActivity activity = requireActivity();
    mMapButtonClickListener = (MwmActivity) activity;
    mRoutingPlanViewModel = new ViewModelProvider(activity).get(RoutingPlanViewModel.class);
    mPlacePageViewModel = new ViewModelProvider(activity).get(PlacePageViewModel.class);
    mMapButtonsViewModel = new ViewModelProvider(activity).get(MapButtonsViewModel.class);
    mSearchPageViewModel = new ViewModelProvider(activity).get(SearchPageViewModel.class);
    mIsNavigationLayout = mMapButtonsViewModel.getLayoutMode().getValue() == LayoutMode.navigation;
    if (mIsNavigationLayout)
      mFrame = inflater.inflate(R.layout.map_buttons_layout_navigation, container, false);
    else
      mFrame = inflater.inflate(R.layout.map_buttons_layout_regular, container, false);

    mInnerLeftButtonsFrame = mFrame.findViewById(R.id.map_buttons_inner_left);
    mInnerRightButtonsFrame = mFrame.findViewById(R.id.map_buttons_inner_right);
    mBottomButtonsFrame = mFrame.findViewById(R.id.map_buttons_bottom);
    if (mIsNavigationLayout)
    {
      if (mInnerLeftButtonsFrame != null)
        mDefaultLeftButtonsBottomMargin = getBottomMargin(mInnerLeftButtonsFrame);
      if (mInnerRightButtonsFrame != null)
        mDefaultRightButtonsBottomMargin = getBottomMargin(mInnerRightButtonsFrame);

      mNavigationHeader = activity.findViewById(R.id.line_frame);
      if (mNavigationHeader != null)
      {
        mNavigationHeader.addOnLayoutChangeListener(mNavigationHeaderLayoutListener);
        mNavigationHeader.post(this::updateNavigationButtonsBottomMargin);
      }
    }

    final FloatingActionButton helpButton = mFrame.findViewById(R.id.help_button);
    final View zoomFrame = mFrame.findViewById(R.id.zoom_buttons_container);
    final FloatingActionButton oledPowerSave = mFrame.findViewById(R.id.oled_power_save);
    UiUtils.showIf(Config.isOledPowerSaveFeatureEnabled(), oledPowerSave);
    oledPowerSave.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.oledPowerSave));
    final FloatingActionButton zoomIn = mFrame.findViewById(R.id.nav_zoom_in);
    zoomIn.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.zoomIn));
    final FloatingActionButton zoomOut = mFrame.findViewById(R.id.nav_zoom_out);
    zoomOut.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.zoomOut));
    final FloatingActionButton bookmarksButton = mFrame.findViewById(R.id.btn_bookmarks);
    bookmarksButton.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.bookmarks));
    final FloatingActionButton myPosition = mFrame.findViewById(R.id.my_position);
    mNavMyPosition =
        new MyPositionButton(myPosition, (v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.myPosition));

    // Some buttons do not exist in navigation mode
    mToggleMapLayerButton = mFrame.findViewById(R.id.layers_button);
    if (mToggleMapLayerButton != null)
    {
      mToggleMapLayerButton.setOnClickListener(
          view -> mMapButtonClickListener.onMapButtonClick(MapButtons.toggleMapLayer));
      mToggleMapLayerButton.setVisibility(View.VISIBLE);
    }
    mMapButtonsViewModel.setTopButtonsMarginTop(-1);
    mTrackRecordingStatusButton = mFrame.findViewById(R.id.track_recording_status);
    if (mTrackRecordingStatusButton != null)
      mTrackRecordingStatusButton.setOnClickListener(
          view -> mMapButtonClickListener.onMapButtonClick(MapButtons.trackRecordingStatus));
    final View menuButton = mFrame.findViewById(R.id.menu_button);
    if (menuButton != null)
    {
      menuButton.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.menu));
      // This hack is needed to show the badge on the initial startup. For some reason, updateMenuBadge does not work
      // from onResume() there.
      menuButton.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout()
        {
          updateMenuBadge();
          menuButton.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        }
      });
    }
    if (helpButton != null)
      helpButton.setOnClickListener((v) -> mMapButtonClickListener.onMapButtonClick(MapButtons.help));

    mSearchWheel =
        new SearchWheel(mFrame,
                        (v)
                            -> mMapButtonClickListener.onMapButtonClick(MapButtons.search),
                        (v) -> mMapButtonClickListener.onSearchCanceled(), mMapButtonsViewModel, mSearchPageViewModel);
    final FloatingActionButton searchButton = mFrame.findViewById(R.id.btn_search);

    // Used to get the maximum height the buttons will evolve in
    mFrame.addOnLayoutChangeListener(new MapButtonsController.ContentViewLayoutChangeListener(mFrame));

    mButtonsMap = new HashMap<>();
    mButtonsMap.put(MapButtons.zoom, zoomFrame);
    mButtonsMap.put(MapButtons.myPosition, myPosition);
    mButtonsMap.put(MapButtons.bookmarks, bookmarksButton);
    mButtonsMap.put(MapButtons.search, searchButton);

    if (mToggleMapLayerButton != null)
      mButtonsMap.put(MapButtons.toggleMapLayer, mToggleMapLayerButton);
    if (menuButton != null)
      mButtonsMap.put(MapButtons.menu, menuButton);
    if (helpButton != null)
      mButtonsMap.put(MapButtons.help, helpButton);
    if (mTrackRecordingStatusButton != null)
      mButtonsMap.put(MapButtons.trackRecordingStatus, mTrackRecordingStatusButton);
    showButton(false, MapButtons.trackRecordingStatus);

    // Buttons that follow the user's size preference and the OLED power save styling.
    final List<MapButtonAppearance> appearances = new ArrayList<>();
    appearances.add(new MapButtonAppearance(oledPowerSave, R.dimen.map_button_icon_size));
    appearances.add(new MapButtonAppearance(zoomIn, R.dimen.map_button_zoom_icon_size));
    appearances.add(new MapButtonAppearance(zoomOut, R.dimen.map_button_zoom_icon_size));
    appearances.add(new MapButtonAppearance(myPosition, R.dimen.map_button_icon_size));
    if (mToggleMapLayerButton != null)
      appearances.add(new MapButtonAppearance(mToggleMapLayerButton, R.dimen.map_button_icon_size));
    if (mTrackRecordingStatusButton != null)
      appearances.add(new MapButtonAppearance(mTrackRecordingStatusButton, R.dimen.map_button_icon_size));
    if (mIsNavigationLayout)
    {
      appearances.add(new MapButtonAppearance(searchButton, R.dimen.map_button_icon_size));
      appearances.add(new MapButtonAppearance(bookmarksButton, R.dimen.map_button_icon_size));

      // The wheel is scaled as a whole by SearchWheel, its buttons only take the power save styling.
      final List<MapButtonAppearance> searchWheel = new ArrayList<>();
      for (int slot : SearchWheel.SLOT_IDS)
        searchWheel.add(new MapButtonAppearance(mFrame.findViewById(slot), R.dimen.map_button_icon_size));
      mSearchWheelAppearances = searchWheel.toArray(new MapButtonAppearance[0]);
    }
    mButtonAppearances = appearances.toArray(new MapButtonAppearance[0]);

    // The bottom bar buttons stand in a row of their own and are scaled with it, see below. In
    // landscape that row is the bottom frame itself, in portrait it is wrapped in one.
    final View bottomRowView = mFrame.findViewById(R.id.map_buttons_bottom_row);
    if (bottomRowView instanceof ConstraintLayout row)
      mBottomButtonsRow = row;
    else if (mBottomButtonsFrame instanceof ConstraintLayout row)
      mBottomButtonsRow = row;
    if (mBottomButtonsRow != null)
    {
      mDefaultBottomRowMaxWidth = getResources().getDimensionPixelSize(R.dimen.map_buttons_bottom_max_width);
      final List<MapButtonAppearance> bottomRow = new ArrayList<>();
      for (View button : new View[] {helpButton, searchButton, bookmarksButton, menuButton})
        if (button != null)
          bottomRow.add(new MapButtonAppearance((FloatingActionButton) button, R.dimen.map_button_icon_size));
      mBottomButtonAppearances = bottomRow.toArray(new MapButtonAppearance[0]);
    }
    updateButtonsScale();
    return mFrame;
  }
  // For disabling bottom buttons which are visible in tablets
  private void setBottomButtonsHidden(boolean hide)
  {
    if (mBottomButtonsFrame != null)
      UiUtils.showIf(!hide, mBottomButtonsFrame);
  }

  public void showButton(boolean show, MapButtonsController.MapButtons button)
  {
    // TODO(AB): Why do we need this check? Isn't it better to crash and fix the wrong logic ASAP?
    final View buttonView = mButtonsMap.get(button);
    if (buttonView == null)
      return;
    switch (button)
    {
    case zoom:
      UiUtils.showIf(show, buttonView);
      UiUtils.showIf(Config.showZoomButtons(), mFrame.findViewById(R.id.nav_zoom_in),
                     mFrame.findViewById(R.id.nav_zoom_out));
      break;
    case toggleMapLayer:
      if (mToggleMapLayerButton != null)
        UiUtils.showIf(show && !isInNavigationMode(), mToggleMapLayerButton);
      break;
    case myPosition:
      if (mNavMyPosition != null)
        mNavMyPosition.showButton(show);
      break;
    case search: mSearchWheel.show(show);
    case bookmarks:
    case menu: UiUtils.showIf(show, buttonView); break;
    case trackRecordingStatus:
      UiUtils.showIf(show, buttonView);
      animateIconBlinking(show, (FloatingActionButton) buttonView);
    }
  }

  void animateIconBlinking(boolean show, @NonNull FloatingActionButton button)
  {
    if (mBlinkingAnimator != null)
    {
      mBlinkingAnimator.cancel();
      mBlinkingAnimator = null;
    }
    if (show)
    {
      Drawable drawable = button.getDrawable();
      mBlinkingAnimator = ObjectAnimator.ofArgb(drawable, "tint", 0xFF757575, 0xFFFF0000);
      mBlinkingAnimator.setDuration(2500);
      mBlinkingAnimator.setEvaluator(new ArgbEvaluator());
      mBlinkingAnimator.setRepeatCount(ObjectAnimator.INFINITE);
      mBlinkingAnimator.setRepeatMode(ObjectAnimator.REVERSE);
      mBlinkingAnimator.start();
    }
  }

  private static int dpToPx(float dp, Context context)
  {
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
  }

  private static int getBottomMargin(@NonNull View view)
  {
    return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
  }

  private void updateNavigationButtonsBottomMargin()
  {
    if (!mIsNavigationLayout || mNavigationHeader == null || mNavigationHeader.getHeight() == 0)
      return;

    final int baseHeaderHeight = getResources().getDimensionPixelSize(R.dimen.nav_menu_height);
    final int extraHeight = Math.max(0, mNavigationHeader.getHeight() - baseHeaderHeight);
    if (mInnerLeftButtonsFrame != null)
      updateBottomMargin(mInnerLeftButtonsFrame, mDefaultLeftButtonsBottomMargin, extraHeight);
    if (mInnerRightButtonsFrame != null)
      updateBottomMargin(mInnerRightButtonsFrame, mDefaultRightButtonsBottomMargin, extraHeight);
  }

  private static void updateBottomMargin(@NonNull View frame, int defaultMargin, int extraHeight)
  {
    // A zero margin in landscape means that side is outside the fixed-width bottom sheet.
    final int margin = defaultMargin == 0 ? 0 : defaultMargin + extraHeight;
    final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
    if (params.bottomMargin == margin)
      return;

    params.bottomMargin = margin;
    frame.setLayoutParams(params);
  }

  private void updateTopButtonsMargin(int margin)
  {
    if (margin == -1 || mTrackRecordingStatusButton == null)
      return;
    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mTrackRecordingStatusButton.getLayoutParams();
    params.topMargin = margin;
    mTrackRecordingStatusButton.setLayoutParams(params);
  }

  @OptIn(markerClass = ExperimentalBadgeUtils.class)
  private void updateMenuBadge(Boolean enable)
  {
    final View menuButton = mButtonsMap.get(MapButtons.menu);
    final Context context = getContext();
    // Sometimes the global layout listener fires when the fragment is not attached to a context
    if (menuButton == null || context == null)
      return;
    final UpdateInfo info = MapManager.nativeGetUpdateInfo(null);
    final int count = (info == null ? 0 : info.filesCount);
    final int verticalOffset = dpToPx(8, context) + dpToPx(Integer.toString(0).length() * 5, context);

    if (count == 0)
    {
      BadgeUtils.detachBadgeDrawable(mBadgeDrawable, menuButton);
      mBadgeDrawable = BadgeDrawable.create(context);
      mBadgeDrawable.setMaxCharacterCount(0);
      mBadgeDrawable.setHorizontalOffset(verticalOffset);
      mBadgeDrawable.setVerticalOffset(dpToPx(9, context));
      mBadgeDrawable.setBackgroundColor(getResources().getColor(R.color.base_accent));
      mBadgeDrawable.setVisible(enable);
      BadgeUtils.attachBadgeDrawable(mBadgeDrawable, menuButton);
    }
  }

  @OptIn(markerClass = com.google.android.material.badge.ExperimentalBadgeUtils.class)
  public void updateMenuBadge()
  {
    final View menuButton = mButtonsMap.get(MapButtons.menu);
    final Context context = getContext();
    // Sometimes the global layout listener fires when the fragment is not attached to a context
    if (menuButton == null || context == null)
      return;
    final UpdateInfo info = MapManager.nativeGetUpdateInfo(null);
    final int count = (info == null ? 0 : info.filesCount);
    final int verticalOffset = dpToPx(8, context) + dpToPx(Integer.toString(0).length() * 5, context);
    BadgeUtils.detachBadgeDrawable(mBadgeDrawable, menuButton);
    mBadgeDrawable = BadgeDrawable.create(context);
    mBadgeDrawable.setMaxCharacterCount(3);
    mBadgeDrawable.setHorizontalOffset(verticalOffset);
    mBadgeDrawable.setVerticalOffset(dpToPx(9, context));
    mBadgeDrawable.setNumber(count);
    mBadgeDrawable.setVisible(count > 0);
    BadgeUtils.attachBadgeDrawable(mBadgeDrawable, menuButton);

    updateMenuBadge(TrackRecorder.nativeIsTrackRecordingEnabled());
  }

  public void updateHelpButtonIcon()
  {
    final View view = mButtonsMap.get(MapButtons.help);
    if (!(view instanceof FloatingActionButton helpButton))
      return;

    if (Framework.nativeCanShowCrowdfundingPromo() && !TextUtils.isEmpty(Utils.getDonateUrl(requireContext())))
    {
      helpButton.setImageResource(R.drawable.ic_crowdfunding);
      helpButton.getDrawable().setTintList(null);
    }
    else if (Config.isNY() && !TextUtils.isEmpty(Utils.getDonateUrl(requireContext())))
    {
      helpButton.setImageResource(R.drawable.ic_christmas_tree);
      helpButton.getDrawable().setTintList(null);
    }
    else
    {
      helpButton.setImageResource(app.organicmaps.branding.R.drawable.logo);
      // Keep this button colorful in normal theme.
      if (!ThemeUtils.isDarkTheme(requireContext()))
        helpButton.getDrawable().setTintList(null);
    }
  }

  public void updateLayerButton()
  {
    if (mToggleMapLayerButton == null)
      return;
    final boolean buttonSelected = TrafficManager.INSTANCE.isEnabled() || IsolinesManager.isEnabled()
                                || SubwayManager.isEnabled() || Framework.nativeIsOutdoorsLayerEnabled()
                                || Framework.nativeIsHikingLayerEnabled() || Framework.nativeIsCyclingLayerEnabled()
                                || Framework.nativeIsBackgroundTilesEnabled();
    mToggleMapLayerButton.setHasActiveLayers(buttonSelected);
  }

  private boolean isBehindPlacePage(View v)
  {
    if (mPlacePageViewModel == null)
      return false;
    final Integer placePageWidth = mPlacePageViewModel.getPlacePageWidth().getValue();
    if (placePageWidth != null)
      return !(mContentWidth / 2 > (placePageWidth.floatValue() / 2.0) + v.getWidth());
    return true;
  }

  private boolean isBehindSearchSheet(View v)
  {
    if (mSearchPageViewModel == null)
      return false;
    final Integer searchPageWidth = mSearchPageViewModel.getSearchPageWidth().getValue();
    if (searchPageWidth != null)
      return !(mContentWidth / 2 > (searchPageWidth.floatValue() / 2.0) + v.getWidth());
    return true;
  }

  private boolean isMoving(View v)
  {
    return v.getTranslationY() < 0;
  }

  public void move(float translationY, boolean shouldActivate)
  {
    if (RoutingController.get().isNavigating() || mContentHeight == 0)
      return;
    final boolean pp = Boolean.TRUE.equals(mRoutingPlanViewModel.getIsPlacePageActive().getValue());
    // don't apply move in landscape
    if (!shouldActivate == pp || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
      return;
    if (mInnerRightButtonsFrame != null)
      applyMove(mInnerRightButtonsFrame, translationY);
  }

  private void moveForSearch(float translationY)
  {
    if (mContentHeight == 0)
      return;

    if (mInnerRightButtonsFrame != null
        && (isBehindSearchSheet(mInnerRightButtonsFrame) || isMoving(mInnerRightButtonsFrame)))
      applyMove(mInnerRightButtonsFrame, translationY);
    if (mInnerLeftButtonsFrame != null
        && (isBehindSearchSheet(mInnerLeftButtonsFrame) || isMoving(mInnerLeftButtonsFrame)))
      applyMove(mInnerLeftButtonsFrame, translationY);
  }

  private void applyMove(View frame, float translationY)
  {
    final float rightTranslation = translationY - frame.getBottom();
    final float appliedTranslation = rightTranslation <= 0 ? rightTranslation : 0;
    frame.setTranslationY(appliedTranslation);
    updateButtonsVisibility(appliedTranslation, frame);
  }

  public void updateButtonsVisibility()
  {
    if (mInnerLeftButtonsFrame != null)
      updateButtonsVisibility(mInnerLeftButtonsFrame.getTranslationY(), mInnerLeftButtonsFrame);
    if (mInnerRightButtonsFrame != null)
      updateButtonsVisibility(mInnerRightButtonsFrame.getTranslationY(), mInnerRightButtonsFrame);
  }

  private void updateButtonsVisibility(final float translation, @Nullable View parent)
  {
    if (parent == null)
      return;
    for (Map.Entry<MapButtons, View> entry : mButtonsMap.entrySet())
    {
      final View button = entry.getValue();
      if (button.getParent() == parent)
      {
        int toleranceOffset = 0;
        // Allow offset tolerance for zoom buttons
        switch (entry.getKey())
        {
        case zoomIn:
        case zoomOut:
        case zoom: toleranceOffset = -140; break;
        }
        showButton(getViewTopOffset(translation, button) >= toleranceOffset, entry.getKey());
      }
    }
  }

  private float getBottomButtonsHeight()
  {
    if (mBottomButtonsFrame != null && mFrame != null && UiUtils.isVisible(mFrame))
      return mBottomButtonsFrame.getMeasuredHeight();
    else
      return 0;
  }

  public void setButtonsHidden(boolean buttonHidden)
  {
    UiUtils.showIf(!buttonHidden, mFrame);
    if (!buttonHidden)
      updateButtonsVisibility();
    mMapButtonsViewModel.setBottomButtonsHeight(getBottomButtonsHeight());
  }

  private boolean isInNavigationMode()
  {
    return RoutingController.get().isPlanning() || RoutingController.get().isNavigating();
  }

  public void updateNavMyPositionButton(int newMode)
  {
    if (mNavMyPosition != null)
      mNavMyPosition.update(newMode);
  }

  /**
   * In the OLED power save mode the map is black, so the buttons drop their filled circle for a
   * dotted white outline and a white icon: only those few pixels are lit.
   */
  private void setLowPowerMode(boolean enabled)
  {
    if (mLowPowerMode == enabled)
      return;

    mLowPowerMode = enabled;
    mFrame.findViewById(R.id.oled_power_save).setSelected(enabled);
    for (MapButtonAppearance appearance : mButtonAppearances)
      appearance.setLowPowerMode(enabled);
    for (MapButtonAppearance appearance : mSearchWheelAppearances)
      appearance.setLowPowerMode(enabled);
    for (MapButtonAppearance appearance : mBottomButtonAppearances)
      appearance.setLowPowerMode(enabled);
    mSearchWheel.setLowPowerMode(enabled);
    if (mNavMyPosition != null)
      mNavMyPosition.setLowPowerMode(enabled);
  }

  /** Resizes the map buttons to the size the user picked in the settings. */
  private void updateButtonsScale()
  {
    final float scale = Config.getMapButtonsScale() / 100f;
    if (mButtonsScale == scale)
      return;

    mButtonsScale = scale;
    for (MapButtonAppearance appearance : mButtonAppearances)
      appearance.setScale(scale);
    mSearchWheel.setScale(scale);
    if (mNavMyPosition != null)
      mNavMyPosition.setScale(scale);
    updateBottomButtonsScale(scale);
  }

  /**
   * The bottom bar buttons stand side by side in a row that is only as wide as it is allowed to be,
   * so they grow together with that limit and only until the row fills the screen.
   */
  private void updateBottomButtonsScale(float scale)
  {
    if (mBottomButtonsRow == null || mBottomButtonAppearances.length == 0)
      return;

    // The row keeps a gap on both sides of every button, whatever the buttons themselves measure.
    final int gaps = getResources().getDimensionPixelSize(R.dimen.margin_half) * (mBottomButtonAppearances.length + 1)
                   + mBottomButtonsRow.getPaddingStart() + mBottomButtonsRow.getPaddingEnd();
    int buttonsWidth = 0;
    for (MapButtonAppearance appearance : mBottomButtonAppearances)
      buttonsWidth += appearance.getDefaultSize();

    final int screenWidth = getResources().getDisplayMetrics().widthPixels;
    final float rowScale = Math.min(scale, (float) (screenWidth - gaps) / buttonsWidth);
    for (MapButtonAppearance appearance : mBottomButtonAppearances)
      appearance.setScale(rowScale);
    mBottomButtonsRow.setMaxWidth(Math.round(mDefaultBottomRowMaxWidth * rowScale));
  }

  private int getViewTopOffset(float translation, View v)
  {
    return (int) (translation + v.getTop());
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    // FragmentStateManager requests insets for the frame before onViewCreated(), but the dispatch
    // itself only happens on the next layout pass — so a listener attached here still receives it.
    // Attaching in onResume() is too late: the dispatch has already run and nothing re-requests
    // insets for an already attached view, leaving the padding at zero.
    ViewCompat.setOnApplyWindowInsetsListener(
        view, WindowInsetUtils.PaddingInsetsListener.allSides(WindowInsetsCompat.Type.systemBars()
                                                              | WindowInsetsCompat.Type.displayCutout()));
  }

  @Override
  public void onStart()
  {
    super.onStart();
    final var viewLifecycleOwner = getViewLifecycleOwner();
    mRoutingPlanViewModel.getRoutingBottomDistanceToTop().observe(viewLifecycleOwner,
                                                                  mRoutingBottomDistanceToTopObserver);
    mPlacePageViewModel.getPlacePageDistanceToTop().observe(viewLifecycleOwner, mPlacePageDistanceToTopObserver);
    mMapButtonsViewModel.getBottomButtonsHidden().observe(viewLifecycleOwner, mBottomButtonHiddenObserver);
    mMapButtonsViewModel.getButtonsHidden().observe(viewLifecycleOwner, mButtonHiddenObserver);
    mSearchPageViewModel.getSearchPageDistanceToTop().observe(viewLifecycleOwner, mSearchPageDistanceToTopObserver);
    mMapButtonsViewModel.getMyPositionMode().observe(viewLifecycleOwner, mMyPositionModeObserver);
    mMapButtonsViewModel.getSearchOption().observe(viewLifecycleOwner, mSearchOptionObserver);
    mMapButtonsViewModel.getTrackRecorderState().observe(viewLifecycleOwner, mTrackRecorderObserver);
    mMapButtonsViewModel.getTopButtonsMarginTop().observe(viewLifecycleOwner, mTopButtonMarginObserver);
    mMapButtonsViewModel.getLowPowerMode().observe(viewLifecycleOwner, mLowPowerModeObserver);
  }

  @Override
  public void onResume()
  {
    super.onResume();
    UiUtils.showIf(Config.isOledPowerSaveFeatureEnabled(), mFrame.findViewById(R.id.oled_power_save));
    updateButtonsScale();
    if (mMapButtonsViewModel.getLayoutMode().getValue() == LayoutMode.navigation)
      mSearchWheel.onResume();
    updateMenuBadge();
    updateLayerButton();
    updateHelpButtonIcon();
  }

  @Override
  public void onDestroyView()
  {
    if (mNavigationHeader != null)
    {
      mNavigationHeader.removeOnLayoutChangeListener(mNavigationHeaderLayoutListener);
      mNavigationHeader = null;
    }
    super.onDestroyView();
  }

  @Override
  public void onStop()
  {
    super.onStop();
    if (mBlinkingAnimator != null)
    {
      mBlinkingAnimator.cancel();
      mBlinkingAnimator = null;
    }
  }

  public void onSearchOptionChange(@Nullable SearchWheel.SearchOption searchOption)
  {
    if (searchOption == null && mMapButtonsViewModel.getLayoutMode().getValue() == LayoutMode.navigation)
      mSearchWheel.reset();
  }

  public enum LayoutMode
  {
    regular,
    planning,
    navigation
  }

  public enum MapButtons
  {
    myPosition,
    toggleMapLayer,
    oledPowerSave,
    zoomIn,
    zoomOut,
    zoom,
    search,
    bookmarks,
    menu,
    help,
    trackRecordingStatus
  }

  public interface MapButtonClickListener
  {
    void onMapButtonClick(MapButtons button);

    void onSearchCanceled();
  }

  /** One map button: the size the user picked and the OLED power save styling are applied here. */
  private static final class MapButtonAppearance
  {
    @NonNull
    private final FloatingActionButton mButton;
    @Nullable
    private final ColorStateList mDefaultIconTint;
    private final int mDefaultSize;
    private final int mDefaultIconSize;
    private boolean mLowPowerMode;

    MapButtonAppearance(@NonNull FloatingActionButton button, @DimenRes int iconSize)
    {
      mButton = button;
      mDefaultIconTint = ImageViewCompat.getImageTintList(button);
      mDefaultSize = button.getCustomSize();
      mDefaultIconSize = button.getResources().getDimensionPixelSize(iconSize);
    }

    int getDefaultSize()
    {
      return mDefaultSize;
    }

    void setScale(float scale)
    {
      mButton.setCustomSize(Math.round(mDefaultSize * scale));
      mButton.setMaxImageSize(Math.round(mDefaultIconSize * scale));
      updateOutline();
    }

    void setLowPowerMode(boolean enabled)
    {
      mLowPowerMode = enabled;
      // The filled circle is the button's content background, faded out here. Tinting it is not an
      // option: the drawable becomes the view background only on the first layout pass, and the
      // style's android:backgroundTint is re-applied over any tint of ours at that point. A custom
      // background is not supported by FloatingActionButton either, hence the outline in the overlay.
      mButton.getContentBackground().setAlpha(enabled ? 0 : 255);
      ImageViewCompat.setImageTintList(mButton, enabled ? ColorStateList.valueOf(Color.WHITE) : mDefaultIconTint);
      updateOutline();
    }

    private void updateOutline()
    {
      mButton.getOverlay().clear();
      if (!mLowPowerMode)
        return;

      final Drawable outline = AppCompatResources.getDrawable(mButton.getContext(), R.drawable.bg_map_button_low_power);
      final int size = mButton.getCustomSize();
      outline.setBounds(0, 0, size, size);
      mButton.getOverlay().add(outline);
    }
  }

  private class ContentViewLayoutChangeListener implements View.OnLayoutChangeListener
  {
    @NonNull
    private final View mContentView;

    public ContentViewLayoutChangeListener(@NonNull View contentView)
    {
      mContentView = contentView;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight,
                               int oldBottom)
    {
      mContentHeight = bottom - top;
      mContentWidth = right - left;
      mMapButtonsViewModel.setBottomButtonsHeight(getBottomButtonsHeight());
      mContentView.removeOnLayoutChangeListener(this);
    }
  }
}
