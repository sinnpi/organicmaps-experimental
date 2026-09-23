package app.organicmaps.routing;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RoutePlace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Screen-space clustering, independent of Android views and the chart library. */
final class RoutePlacesLayout
{
  static final class Cluster
  {
    final List<RoutePlace> places = new ArrayList<>();
    float x;
    private float mLastX;

    void add(RoutePlace place, float position)
    {
      x = (x * places.size() + position) / (places.size() + 1);
      places.add(place);
      mLastX = position;
    }
  }

  @NonNull
  static List<Cluster> cluster(@NonNull List<RoutePlace> places, int categories, double positionMeters,
                               double windowStart, double windowEnd, float left, float width, float spacing)
  {
    final List<Cluster> clusters = new ArrayList<>();
    if (width <= 0 || windowEnd <= windowStart)
      return clusters;

    final List<RoutePlace> visible = new ArrayList<>();
    for (RoutePlace place : places)
    {
      if ((categories & (1 << place.category)) != 0 && place.distanceMeters >= positionMeters
          && place.distanceMeters >= windowStart && place.distanceMeters <= windowEnd)
        visible.add(place);
    }
    visible.sort(Comparator.comparingDouble(place -> place.distanceMeters));
    for (RoutePlace place : visible)
    {
      final float x = left + (float) ((place.distanceMeters - windowStart) / (windowEnd - windowStart)) * width;
      if (clusters.isEmpty() || x - clusters.get(clusters.size() - 1).mLastX >= spacing)
        clusters.add(new Cluster());
      clusters.get(clusters.size() - 1).add(place, x);
    }
    return clusters;
  }
}
