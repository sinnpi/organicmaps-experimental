package app.organicmaps.sdk.routing;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/** A mapped candidate near the route, not a guarantee of access or availability. */
public final class RoutePlace
{
  // Keep in sync with route_places::Category.
  public static final int WATER = 0;
  public static final int GROCERIES = 1;
  public static final int CAFE = 2;
  public static final int CATEGORY_COUNT = 3;
  public static final double MAX_AHEAD_METERS = 50000.0;

  public final long requestId;
  public final int category;
  public final int index;
  @NonNull
  public final String name;
  public final double distanceMeters;
  /** Straight-line distance to the route, not a cycling detour distance. */
  public final double offsetMeters;

  @Keep
  public RoutePlace(long requestId, int category, int index, @NonNull String name, double distanceMeters,
                    double offsetMeters)
  {
    this.requestId = requestId;
    this.category = category;
    this.index = index;
    this.name = name;
    this.distanceMeters = distanceMeters;
    this.offsetMeters = offsetMeters;
  }

  @Keep
  public interface Listener {
    /** Called on the UI thread, once per category. */
    void onPlaces(long requestId, int category, @NonNull RoutePlace[] places);
  }

  public static native long search(double fromMeters, double toMeters, @NonNull Listener listener);
  public static native void cancel(long requestId);
  public static native void show(long requestId, int category, int index);
}
