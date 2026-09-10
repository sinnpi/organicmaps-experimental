package app.organicmaps.maplayer;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.search.SearchPageViewModel;
import app.organicmaps.util.Graphics;
import app.organicmaps.util.UiUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SearchWheel implements View.OnClickListener
{
  private final View mFrame;

  private View mSearchLayout;
  private final ImageView mSearchButton;
  @Nullable
  private final View mTouchInterceptor;
  @Nullable
  private final View mSearchButtonFrame;
  @Nullable
  private Drawable mDefaultLayoutBackground;

  private boolean mIsExpanded;
  /// The action in each wheel slot, null for a hidden slot.
  private final SearchOption[] mSlotOptions = new SearchOption[SLOT_IDS.length];
  @NonNull
  private final View.OnClickListener mOnSearchPressedListener;
  @NonNull
  private final View.OnClickListener mOnSearchCanceledListener;
  private final MapButtonsViewModel mMapButtonsViewModel;
  private final SearchPageViewModel mSearchPageViewModel;

  private static final long CLOSE_DELAY_MILLIS = 5000L;
  private final Runnable mCloseRunnable = new Runnable() {
    @Override
    public void run()
    {
      // if the search bar is already closed, i.e. nothing should be done here.
      if (!mIsExpanded)
        return;

      toggleSearchLayout();
    }
  };

  /**
   * The buttons the wheel is laid out with, in the order they are filled. Which action sits in
   * each one is the user's choice, so the slots are numbered rather than named.
   */
  static final int[] SLOT_IDS = {R.id.search_option_1, R.id.search_option_2, R.id.search_option_3, R.id.search_option_4,
                                 R.id.search_option_5};

  /**
   * A wheel action: a category query, free-text search, or a hidden slot.
   */
  public enum SearchOption
  {
    SEARCH("search", R.string.search, R.drawable.ic_search),
    FUEL("fuel", R.string.category_fuel, R.drawable.ic_nav_search_fuel),
    PARKING("parking", R.string.category_parking, R.drawable.ic_nav_search_parking),
    EAT("eat", R.string.category_eat, R.drawable.ic_nav_search_eat),
    FOOD("food", R.string.category_food, R.drawable.ic_nav_search_food),
    ATM("atm", R.string.category_atm, R.drawable.ic_nav_search_atm),
    WATER("water", R.string.category_water, R.drawable.ic_nav_search_water),
    TOILET("toilet", R.string.category_toilet, R.drawable.ic_nav_search_toilet),
    PHARMACY("pharmacy", R.string.category_pharmacy, R.drawable.ic_nav_search_pharmacy),
    HOSPITAL("hospital", R.string.category_hospital, R.drawable.ic_nav_search_hospital),
    HOTEL("hotel", R.string.category_hotel, R.drawable.ic_nav_search_hotel),
    SHOPPING("shopping", R.string.category_shopping, R.drawable.ic_nav_search_shopping),
    TRANSPORT("transport", R.string.category_transport, R.drawable.ic_nav_search_transport),
    BANK("bank", R.string.category_bank, R.drawable.ic_nav_search_bank),
    POLICE("police", R.string.category_police, R.drawable.ic_nav_search_police),
    POST("post", R.string.category_post, R.drawable.ic_nav_search_post),
    RECYCLING("recycling", R.string.category_recycling, R.drawable.ic_nav_search_recycling),
    RV("rv", R.string.category_rv, R.drawable.ic_nav_search_rv),
    TOURISM("tourism", R.string.category_tourism, R.drawable.ic_nav_search_tourism),
    ENTERTAINMENT("entertainment", R.string.category_entertainment, R.drawable.ic_nav_search_entertainment),
    NIGHTLIFE("nightlife", R.string.category_nightlife, R.drawable.ic_nav_search_nightlife),
    CHILDREN("children", R.string.category_children, R.drawable.ic_nav_search_children),
    SECONDHAND("secondhand", R.string.category_secondhand, R.drawable.ic_nav_search_secondhand),
    NONE("none", R.string.nav_search_hidden, R.drawable.ic_search);

    /// The wheel has a fixed number of buttons, so the selection cannot outgrow it.
    public static final int MAX_SELECTED = SLOT_IDS.length;

    /// What the wheel has always shown, and what it falls back to when nothing has been chosen.
    @NonNull
    private static final List<SearchOption> DEFAULT_SELECTION = Arrays.asList(FUEL, PARKING, EAT, FOOD, ATM);

    /// Stored in the settings, so it must stay stable even if the enum is reordered or renamed.
    @NonNull
    private final String mId;
    @StringRes
    private final int mQueryId;
    @DrawableRes
    private final int mIcon;

    SearchOption(@NonNull String id, @StringRes int queryId, @DrawableRes int icon)
    {
      mId = id;
      mQueryId = queryId;
      mIcon = icon;
    }

    @NonNull
    public String getId()
    {
      return mId;
    }

    @StringRes
    public int getQueryId()
    {
      return mQueryId;
    }

    @Nullable
    public static SearchOption fromId(@Nullable String id)
    {
      for (SearchOption option : values())
        if (option.mId.equals(id))
          return option;
      return null;
    }

    /** The actions the wheel shows, at most one per slot. */
    @NonNull
    public static List<SearchOption> getSelected()
    {
      return parse(Config.getNavSearchOptions());
    }

    public static void setSelected(@NonNull List<SearchOption> options)
    {
      Config.setNavSearchOptions(format(options));
    }

    @NonNull
    static List<SearchOption> parse(@Nullable String ids)
    {
      if (ids == null || ids.isEmpty())
        return DEFAULT_SELECTION;

      final List<SearchOption> selected = new ArrayList<>(MAX_SELECTED);
      for (String id : ids.split(","))
      {
        final SearchOption option = fromId(id);
        // Preserve slot positions when reading settings from a build with different actions.
        if (selected.size() < MAX_SELECTED)
          selected.add(option == null ? NONE : option);
      }
      return selected.stream().allMatch(option -> option == NONE) ? DEFAULT_SELECTION : selected;
    }

    @NonNull
    static String format(@NonNull List<SearchOption> options)
    {
      final StringBuilder ids = new StringBuilder();
      for (SearchOption option : options)
      {
        if (ids.length() > 0)
          ids.append(',');
        ids.append(option.mId);
      }
      return ids.toString();
    }
  }

  public SearchWheel(View frame, @NonNull View.OnClickListener onSearchPressedListener,
                     @NonNull View.OnClickListener onSearchCanceledListener, MapButtonsViewModel mapButtonsViewModel,
                     SearchPageViewModel searchPageViewModel)
  {
    mFrame = frame;
    mMapButtonsViewModel = mapButtonsViewModel;
    mSearchPageViewModel = searchPageViewModel;
    mOnSearchPressedListener = onSearchPressedListener;
    mOnSearchCanceledListener = onSearchCanceledListener;
    mTouchInterceptor = mFrame.findViewById(R.id.touch_interceptor);
    if (mTouchInterceptor != null)
      mTouchInterceptor.setOnClickListener(this);
    mSearchButton = mFrame.findViewById(R.id.btn_search);
    mSearchButton.setOnClickListener(this);
    mSearchButtonFrame = mFrame.findViewById(R.id.search_button_frame);
    if (mSearchButtonFrame != null)
    {
      mSearchButton.addOnLayoutChangeListener(
          (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> alignSearchWheel());
      mSearchButtonFrame.addOnLayoutChangeListener(
          (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> alignSearchWheel());
    }
    refreshSearchVisibility();
  }

  private boolean initSearchLayout()
  {
    if (mSearchLayout != null)
      return true;

    mSearchLayout = mFrame.findViewById(R.id.search_frame);
    if (mSearchLayout == null)
      return false;

    mDefaultLayoutBackground = mSearchLayout.getBackground();

    DisplayMetrics displayMetrics = new DisplayMetrics();
    WindowManager windowmanager = (WindowManager) mFrame.getContext().getSystemService(Context.WINDOW_SERVICE);
    windowmanager.getDefaultDisplay().getMetrics(displayMetrics);
    // Get available screen height in DP
    int height = Math.round(displayMetrics.heightPixels / displayMetrics.density);
    // If height is less than 400dp, the search wheel in a straight line
    // In this case, move the pivot for the animation
    if (height < 400)
    {
      UiUtils.waitLayout(mSearchLayout, () -> {
        mSearchLayout.setPivotX(0);
        mSearchLayout.setPivotY(mSearchLayout.getMeasuredHeight() / 2f);
      });
    }
    for (int slot : SLOT_IDS)
      mSearchLayout.findViewById(slot).setOnClickListener(this);
    bindOptions();
    return true;
  }

  /** Binds the configured actions, leaving hidden and unused slots empty. */
  private void bindOptions()
  {
    final List<SearchOption> options = SearchOption.getSelected();
    for (int i = 0; i < SLOT_IDS.length; ++i)
    {
      final SearchOption option = i < options.size() ? options.get(i) : null;
      mSlotOptions[i] = option == SearchOption.NONE ? null : option;
      if (mSlotOptions[i] == null)
        continue;

      final ImageView button = mSearchLayout.findViewById(SLOT_IDS[i]);
      button.setImageResource(option.mIcon);
      button.setContentDescription(mFrame.getContext().getString(option.mQueryId));
    }
  }

  @Nullable
  private SearchOption optionForSlot(@IdRes int id)
  {
    for (int i = 0; i < SLOT_IDS.length; ++i)
      if (SLOT_IDS[i] == id)
        return mSlotOptions[i];
    return null;
  }

  /**
   * Resizes the wheel with the map buttons. It is laid out around the search button in two rather
   * different ways depending on the screen height, so it is scaled as a whole instead of resizing
   * its parts: growing from the start edge keeps it centred on the search button, which the map
   * buttons controller grows with the same scale.
   */
  public void setScale(float scale)
  {
    if (mSearchButtonFrame == null)
      return;

    mSearchButtonFrame.setScaleX(scale);
    mSearchButtonFrame.setScaleY(scale);
    // Grow from the start edge: the left one, or the right one when the layout is mirrored.
    UiUtils.waitLayout(mSearchButtonFrame, this::alignSearchWheel);
  }

  private void alignSearchWheel()
  {
    if (mSearchButtonFrame == null)
      return;

    // Both views share a parent. Keep the wheel centred even after resizing the search button.
    mSearchButtonFrame.setPivotX(
        mSearchButtonFrame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? mSearchButtonFrame.getWidth() : 0);
    mSearchButtonFrame.setPivotY(mSearchButtonFrame.getHeight() / 2f);
    mSearchButtonFrame.setTranslationY(mSearchButton.getY() + mSearchButton.getHeight() / 2f
                                       - mSearchButtonFrame.getTop() - mSearchButtonFrame.getHeight() / 2f);
  }

  /** Blacks out the panel behind the category buttons while the OLED power save mode is on. */
  public void setLowPowerMode(boolean enabled)
  {
    if (!initSearchLayout())
      return;

    if (enabled)
      mSearchLayout.setBackgroundColor(Color.BLACK);
    else
      mSearchLayout.setBackground(mDefaultLayoutBackground);
  }

  public void show(boolean show)
  {
    UiUtils.showIf(show, mSearchButton);
    if (initSearchLayout())
      UiUtils.showIf(show && mIsExpanded, mSearchLayout);
  }

  public void reset()
  {
    mIsExpanded = false;
    resetSearchButtonImage();
  }

  public void onResume()
  {
    // The selection is editable in the settings, which are a separate activity.
    if (initSearchLayout())
    {
      bindOptions();
      refreshSearchVisibility();
    }

    if (mMapButtonsViewModel.getSearchOption().getValue() != null)
    {
      refreshSearchButtonImage();
      return;
    }

    final String query = SearchEngine.INSTANCE.getQuery();
    if (TextUtils.isEmpty(query))
    {
      resetSearchButtonImage();
      return;
    }

    if (RoutingController.get().isNavigating())
      refreshSearchButtonImage();
    else
      resetSearchButtonImage();
  }

  private void toggleSearchLayout()
  {
    if (initSearchLayout())
    {
      final int animRes;
      if (mIsExpanded)
      {
        animRes = R.animator.show_zoom_out_alpha;
      }
      else
      {
        animRes = R.animator.show_zoom_in_alpha;
        UiUtils.show(mSearchLayout);
      }
      mIsExpanded = !mIsExpanded;
      final Animator animator = AnimatorInflater.loadAnimator(mSearchLayout.getContext(), animRes);
      animator.setTarget(mSearchLayout);
      animator.start();
      if (mTouchInterceptor != null)
        UiUtils.visibleIf(mIsExpanded, mTouchInterceptor);
      animator.addListener(new UiUtils.SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation)
        {
          refreshSearchVisibility();
        }
      });
    }
  }

  private void refreshSearchVisibility()
  {
    if (initSearchLayout())
    {
      for (int i = 0; i < SLOT_IDS.length; ++i)
      {
        final View slot = mSearchLayout.findViewById(SLOT_IDS[i]);
        // Keep the circular layout's anchors in place when some slots are hidden.
        UiUtils.visibleIf(mIsExpanded && mSlotOptions[i] != null, slot);
      }

      if (mTouchInterceptor != null)
        UiUtils.visibleIf(mIsExpanded, mSearchLayout, mTouchInterceptor);

      if (mIsExpanded)
      {
        UiThread.cancelDelayedTasks(mCloseRunnable);
        UiThread.runLater(mCloseRunnable, CLOSE_DELAY_MILLIS);
      }
    }
  }

  private void resetSearchButtonImage()
  {
    mSearchButton.setImageDrawable(Graphics.tint(mSearchButton.getContext(), R.drawable.ic_search));
  }

  private void refreshSearchButtonImage()
  {
    final SearchOption searchOption = mMapButtonsViewModel.getSearchOption().getValue();
    mSearchButton.setImageDrawable(Graphics.tint(
        mSearchButton.getContext(), searchOption == null ? R.drawable.ic_routing_search_off : searchOption.mIcon,
        androidx.appcompat.R.attr.colorAccent));
  }

  @Override
  public void onClick(View v)
  {
    final int id = v.getId();
    if (id == R.id.btn_search)
      onSearchButtonClick(v);
    else if (id == R.id.touch_interceptor)
      toggleSearchLayout();
    else
    {
      final SearchOption option = optionForSlot(id);
      if (option != null)
        startSearch(option);
    }
  }

  private void onSearchButtonClick(View v)
  {
    Boolean searchEnabled = mSearchPageViewModel.getSearchEnabled().getValue();
    boolean enabled = searchEnabled != null && searchEnabled;
    if (!RoutingController.get().isNavigating())
    {
      if (!enabled)
        showSearchInParent();
      else
        mOnSearchCanceledListener.onClick(v);
      return;
    }

    if (mMapButtonsViewModel.getSearchOption().getValue() != null
        || !TextUtils.isEmpty(SearchEngine.INSTANCE.getQuery()))
    {
      mOnSearchCanceledListener.onClick(v);
      refreshSearchVisibility();
      return;
    }

    if (mIsExpanded)
    {
      showSearchInParent();
      return;
    }

    if (!enabled)
      toggleSearchLayout();
    else
      mOnSearchCanceledListener.onClick(v);
  }

  private void showSearchInParent()
  {
    mOnSearchPressedListener.onClick(mSearchButton);
    mIsExpanded = false;
    refreshSearchVisibility();
    mMapButtonsViewModel.setSearchOption(null);
  }

  private void startSearch(SearchOption searchOption)
  {
    if (searchOption == SearchOption.SEARCH)
    {
      showSearchInParent();
      return;
    }
    mMapButtonsViewModel.setSearchOption(searchOption);
    final String query = mFrame.getContext().getString(searchOption.mQueryId);
    // Category request from navigation search wheel.
    SearchEngine.INSTANCE.searchInteractive(mFrame.getContext(), query, true, System.nanoTime(), false);
    SearchEngine.INSTANCE.setQuery(query);
    refreshSearchButtonImage();
    toggleSearchLayout();
  }
}
