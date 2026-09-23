package app.organicmaps.routing;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutePlace;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.util.ThemeUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cached, passive places-ahead layer. It never changes routing or the search-wheel session. */
final class RoutePlacesController implements RoutePlace.Listener
{
  private static final long REFRESH_INTERVAL_MS = 10 * 60 * 1000;
  private static final double REFRESH_DISTANCE_METERS = 5000;
  private static final int ALL_CATEGORIES = (1 << RoutePlace.CATEGORY_COUNT) - 1;
  private static final String CATEGORIES_KEY = "categories";
  private static final int[] TITLES = {R.string.route_places_water, R.string.route_places_groceries,
                                       R.string.route_places_cafe};
  private static final int[] ICONS = {R.drawable.ic_nav_search_water, R.drawable.ic_nav_search_food,
                                      R.drawable.ic_nav_route_cafe};

  @NonNull
  private final FrameLayout mLane;
  @NonNull
  private final Context mContext;
  @NonNull
  private final SharedPreferences mPreferences;
  @NonNull
  private final Runnable mBeforeInspect;
  private final List<RoutePlace> mPlaces = new ArrayList<>();
  private List<RoutePlacesLayout.Cluster> mClusters = new ArrayList<>();
  private final List<TextView> mMarkers = new ArrayList<>();
  @Nullable
  private AlertDialog mDialog;

  private int mCategories;
  private int mPendingCategories;
  private long mRequestId;
  private boolean mHasRequested;
  private boolean mOpeningDialog;
  private long mRequestedAt;
  private double mRequestedFrom;
  private double mPositionMeters;
  private double mWindowStart;
  private double mWindowEnd;
  private float mLeft;
  private float mWidth;
  private boolean mActive;
  private boolean mLowPowerMode;

  RoutePlacesController(@NonNull FrameLayout lane, @NonNull Runnable beforeInspect)
  {
    mLane = lane;
    mContext = lane.getContext();
    mBeforeInspect = beforeInspect;
    mPreferences = mContext.getSharedPreferences("RoutePlaces", Context.MODE_PRIVATE);
    mCategories = mPreferences.getInt(CATEGORIES_KEY, ALL_CATEGORIES) & ALL_CATEGORIES;
    mLane.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      @Override
      public void onViewAttachedToWindow(View v)
      {
        maybeSearch();
      }

      @Override
      public void onViewDetachedFromWindow(View v)
      {
        clearRequest();
      }
    });
    applyStyle();
  }

  void reset(boolean active, double positionMeters)
  {
    clearRequest();
    mActive = active;
    mPositionMeters = positionMeters;
    maybeSearch();
  }

  private void clearRequest()
  {
    RoutePlace.cancel(mRequestId);
    mRequestId = 0;
    mHasRequested = false;
    mPendingCategories = 0;
    mPlaces.clear();
    if (mDialog != null)
    {
      mDialog.dismiss();
      mDialog = null;
    }
    render();
  }

  void update(double positionMeters, double windowStart, double windowEnd, float left, float width)
  {
    mPositionMeters = positionMeters;
    mWindowStart = windowStart;
    mWindowEnd = windowEnd;
    mLeft = left;
    mWidth = width;
    maybeSearch();
    render();
  }

  private void maybeSearch()
  {
    if (!mActive || mCategories == 0 || !mLane.isAttachedToWindow() || mLane.getWindowVisibility() != View.VISIBLE
        || mPendingCategories != 0 || mOpeningDialog || (mDialog != null && mDialog.isShowing()))
      return;
    final long now = SystemClock.elapsedRealtime();
    if (!shouldRefresh(mHasRequested, mPositionMeters, mRequestedFrom, now - mRequestedAt))
      return;

    mHasRequested = true;
    mRequestedFrom = mPositionMeters;
    mRequestedAt = now;
    mPlaces.clear();
    mPendingCategories = ALL_CATEGORIES;
    mRequestId = RoutePlace.search(mPositionMeters, mPositionMeters + RoutePlace.MAX_AHEAD_METERS, this);
    if (mRequestId == 0)
      mPendingCategories = 0;
    // Also clear old clickable markers when a refresh is started outside update(), e.g. options.
    render();
  }

  static boolean shouldRefresh(boolean hasRequested, double position, double requestedFrom, long ageMillis)
  {
    return !hasRequested || position - requestedFrom >= REFRESH_DISTANCE_METERS || position < requestedFrom - 100
 || ageMillis >= REFRESH_INTERVAL_MS;
  }

  @Override
  public void onPlaces(long requestId, int category, @NonNull RoutePlace[] places)
  {
    if (requestId != mRequestId || !mActive)
      return;
    mPendingCategories &= ~(1 << category);
    mPlaces.addAll(Arrays.asList(places));
    render();
  }

  private int dp(int value)
  {
    return Math.round(value * mContext.getResources().getDisplayMetrics().density);
  }

  private void render()
  {
    final int size = dp(48);
    final List<RoutePlacesLayout.Cluster> clusters =
        RoutePlacesLayout.cluster(mPlaces, mCategories, mPositionMeters, mWindowStart, mWindowEnd, mLeft, mWidth, size);
    boolean same = clusters.size() == mClusters.size();
    for (int i = 0; same && i < clusters.size(); ++i)
      same = clusters.get(i).places.equals(mClusters.get(i).places);
    mClusters = clusters;
    // GPS updates normally only move the existing markers; don't inflate views every fix.
    if (!same)
    {
      for (TextView marker : mMarkers)
        mLane.removeView(marker);
      mMarkers.clear();
      for (RoutePlacesLayout.Cluster cluster : clusters)
      {
        final TextView marker = new TextView(mContext) {
          private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

          @Override
          protected void onDraw(Canvas canvas)
          {
            // The symbol is centered on the x-axis; the rest is a 48dp touch target.
            final float x = getWidth() / 2f;
            mPaint.setColor(mLowPowerMode ? Color.BLACK : ThemeUtils.getColor(mContext, R.attr.cardBackground));
            canvas.drawCircle(x, dp(34), dp(10), mPaint);
            mPaint.setColor(getCurrentTextColor());
            final Drawable icon = getCompoundDrawables()[1];
            if (icon != null)
            {
              icon.setBounds(Math.round(x) - dp(9), dp(25), Math.round(x) + dp(9), dp(43));
              icon.draw(canvas);
            }
            if (cluster.places.size() > 1)
            {
              mPaint.setAlpha(255);
              mPaint.setTextAlign(Paint.Align.CENTER);
              mPaint.setTextSize(dp(10));
              canvas.drawText(String.valueOf(cluster.places.size()), x, dp(16), mPaint);
            }
          }
        };
        // The drawable and optional count are positioned explicitly in onDraw().
        marker.setOnClickListener(v -> inspect(cluster.places));
        marker.setFocusable(true);
        mLane.addView(marker, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        mMarkers.add(marker);
      }
      applyStyle();
    }
    for (int i = 0; i < clusters.size(); ++i)
    {
      final RoutePlacesLayout.Cluster cluster = clusters.get(i);
      final TextView marker = mMarkers.get(i);
      marker.setTranslationX(Math.max(0, Math.min(mLane.getWidth() - size, cluster.x - size / 2f)));
      marker.setContentDescription(
          cluster.places.size() == 1
              ? describe(cluster.places.get(0))
              : mContext.getString(R.string.route_places_cluster, cluster.places.size(),
                                   distance(cluster.places.get(0).distanceMeters - mPositionMeters)));
    }
    // Empty/loading states must not cover the graph or intercept scrubbing. Category options
    // remain available through the elevation window label.
  }

  void setLowPowerMode(boolean enabled)
  {
    mLowPowerMode = enabled;
    applyStyle();
  }

  private void applyStyle()
  {
    final int color = mLowPowerMode ? Color.LTGRAY : ThemeUtils.getColor(mContext, android.R.attr.textColorPrimary);
    for (int i = 0; i < mMarkers.size(); ++i)
    {
      final List<RoutePlace> places = mClusters.get(i).places;
      final Drawable icon = AppCompatResources.getDrawable(mContext, iconForPlaces(places));
      if (icon != null)
      {
        icon.mutate();
        DrawableCompat.setTint(icon, color);
        icon.setBounds(0, 0, dp(18), dp(18));
      }
      final TextView marker = mMarkers.get(i);
      marker.setTextColor(color);
      marker.setCompoundDrawables(null, icon, null, null);
    }
  }

  static int iconForPlaces(@NonNull List<RoutePlace> places)
  {
    // Keep water visible even when it shares a cluster with shops or cafes. The count and
    // inspection dialog still expose every place in the cluster.
    if (places.stream().anyMatch(place -> place.category == RoutePlace.WATER))
      return ICONS[RoutePlace.WATER];
    final int category = places.get(0).category;
    return places.stream().anyMatch(place -> place.category != category) ? R.drawable.ic_nav_search_shopping
                                                                         : ICONS[category];
  }

  @NonNull
  private String distance(double meters)
  {
    return StringUtils.nativeFormatDistance(Math.max(0, meters)).toString(mContext);
  }

  @NonNull
  private String title(@NonNull RoutePlace place)
  {
    return place.name.isEmpty() ? mContext.getString(TITLES[place.category]) : place.name;
  }

  @NonNull
  private String describe(@NonNull RoutePlace place)
  {
    return mContext.getString(R.string.route_places_item, mContext.getString(TITLES[place.category]), title(place),
                              distance(place.distanceMeters - mPositionMeters));
  }

  private void prepareDialog()
  {
    // Cancelling an elevation scrub also updates its viewport. Do not refresh the native result
    // cache underneath the marker whose click we are about to handle.
    mOpeningDialog = true;
    mBeforeInspect.run();
    mOpeningDialog = false;
  }

  private void inspect(@NonNull List<RoutePlace> places)
  {
    prepareDialog();
    if (places.size() == 1)
    {
      inspectPlace(places.get(0));
      return;
    }
    final String[] labels = new String[places.size()];
    for (int i = 0; i < labels.length; ++i)
      labels[i] = describe(places.get(i));
    mDialog = new AlertDialog.Builder(mContext)
                  .setTitle(R.string.route_places_title)
                  .setItems(labels, (dialog, which) -> inspectPlace(places.get(which)))
                  .setNegativeButton(R.string.cancel, null)
                  .show();
  }

  private void inspectPlace(@NonNull RoutePlace place)
  {
    mDialog = new AlertDialog.Builder(mContext)
                  .setTitle(title(place))
                  .setMessage(mContext.getString(R.string.route_places_details,
                                                 distance(place.distanceMeters - mPositionMeters),
                                                 distance(place.offsetMeters))
                              + "\n\n" + mContext.getString(R.string.route_places_unverified))
                  .setPositiveButton(R.string.route_places_view,
                                     (dialog, which) -> RoutePlace.show(place.requestId, place.category, place.index))
                  .setNegativeButton(R.string.cancel, null)
                  .show();
  }

  void showOptions()
  {
    prepareDialog();
    final String[] titles = new String[TITLES.length];
    final boolean[] checked = new boolean[TITLES.length];
    for (int i = 0; i < TITLES.length; ++i)
    {
      titles[i] = mContext.getString(TITLES[i]);
      checked[i] = (mCategories & (1 << i)) != 0;
    }
    mDialog = new AlertDialog.Builder(mContext)
                  .setTitle(R.string.route_places_title)
                  .setMultiChoiceItems(titles, checked,
                                       (dialog, which, enabled) -> {
                                         if (enabled)
                                           mCategories |= 1 << which;
                                         else
                                           mCategories &= ~(1 << which);
                                         mPreferences.edit().putInt(CATEGORIES_KEY, mCategories).apply();
                                         render();
                                       })
                  .setPositiveButton(R.string.ok, (dialog, which) -> mLane.post(this::maybeSearch))
                  .setNeutralButton(R.string.help,
                                    (dialog, which) -> {
                                      mDialog =
                                          new AlertDialog.Builder(mContext)
                                              .setTitle(R.string.route_places_title)
                                              .setMessage(mContext.getString(R.string.route_places_info,
                                                                             distance(RoutePlace.MAX_AHEAD_METERS)))
                                              .setPositiveButton(R.string.ok, null)
                                              .show();
                                    })
                  .show();
  }
}
