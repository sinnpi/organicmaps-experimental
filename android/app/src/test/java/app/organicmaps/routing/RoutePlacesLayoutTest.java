package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutePlace;
import java.util.List;
import org.junit.Test;

public class RoutePlacesLayoutTest
{
  private static RoutePlace place(int category, double distance)
  {
    return new RoutePlace(1, category, 0, "Candidate", distance, 100);
  }

  @Test
  public void filtersPassedPlacesWindowAndCategories()
  {
    RoutePlace water = place(RoutePlace.WATER, 2000);
    List<RoutePlace> places =
        List.of(place(RoutePlace.WATER, 999), water, place(RoutePlace.CAFE, 2500), place(RoutePlace.WATER, 4001));
    List<RoutePlacesLayout.Cluster> clusters =
        RoutePlacesLayout.cluster(places, 1 << RoutePlace.WATER, 1000, 0, 4000, 20, 400, 48);
    assertEquals(1, clusters.size());
    assertSame(water, clusters.get(0).places.get(0));
    assertEquals(220, clusters.get(0).x, 0.01);
    assertTrue(RoutePlacesLayout.cluster(places, 0, 1000, 0, 4000, 20, 400, 48).isEmpty());
  }

  @Test
  public void sortsAndClustersMixedCategoriesWithoutOverlap()
  {
    RoutePlace water = place(RoutePlace.WATER, 1000);
    RoutePlace cafe = place(RoutePlace.CAFE, 1100);
    RoutePlace grocery = place(RoutePlace.GROCERIES, 2000);
    List<RoutePlacesLayout.Cluster> clusters =
        RoutePlacesLayout.cluster(List.of(grocery, cafe, water), 7, 0, 0, 4000, 0, 400, 48);
    assertEquals(2, clusters.size());
    assertEquals(List.of(water, cafe), clusters.get(0).places);
    assertEquals(105, clusters.get(0).x, 0.01);
    assertEquals(List.of(grocery), clusters.get(1).places);
    assertTrue(clusters.get(1).x - clusters.get(0).x >= 48);
  }

  @Test
  public void keepsEndpointAlignmentWhenViewportIsPinnedNearFinish()
  {
    RoutePlace current = place(RoutePlace.WATER, 9000);
    RoutePlace finish = place(RoutePlace.CAFE, 10000);
    List<RoutePlacesLayout.Cluster> clusters =
        RoutePlacesLayout.cluster(List.of(current, finish), 7, 9000, 6000, 10000, 20, 400, 48);
    assertEquals(2, clusters.size());
    assertEquals(320, clusters.get(0).x, 0.01);
    assertEquals(420, clusters.get(1).x, 0.01);
  }

  @Test
  public void waterSymbolRemainsVisibleInMixedClusters()
  {
    RoutePlace water = place(RoutePlace.WATER, 1100);
    RoutePlace cafe = place(RoutePlace.CAFE, 1000);
    RoutePlace grocery = place(RoutePlace.GROCERIES, 1200);
    assertEquals(R.drawable.ic_nav_search_water, RoutePlacesController.iconForPlaces(List.of(water)));
    assertEquals(R.drawable.ic_nav_search_water, RoutePlacesController.iconForPlaces(List.of(cafe, water, grocery)));
    assertEquals(R.drawable.ic_nav_search_shopping, RoutePlacesController.iconForPlaces(List.of(cafe, grocery)));
    assertEquals(R.drawable.ic_nav_route_cafe, RoutePlacesController.iconForPlaces(List.of(cafe)));
  }

  @Test
  public void ignoresInvalidViewport()
  {
    List<RoutePlace> places = List.of(place(RoutePlace.WATER, 1000));
    assertTrue(RoutePlacesLayout.cluster(places, 7, 0, 0, 1000, 0, 0, 48).isEmpty());
    assertTrue(RoutePlacesLayout.cluster(places, 7, 0, 1000, 1000, 0, 400, 48).isEmpty());
    assertTrue(RoutePlacesLayout.cluster(places, 7, 0, 2000, 1000, 0, 400, 48).isEmpty());
  }

  @Test
  public void cachesEvenEmptyNativeRequestsAndRefreshesOnlyAtBounds()
  {
    assertTrue(RoutePlacesController.shouldRefresh(false, 1000, 1000, 0));
    // Attempted requests include native requests returning zero for an empty/finished route.
    assertFalse(RoutePlacesController.shouldRefresh(true, 1000, 1000, 1));
    assertFalse(RoutePlacesController.shouldRefresh(true, 5999, 1000, 599999));
    assertFalse(RoutePlacesController.shouldRefresh(true, 900, 1000, 1));
    assertTrue(RoutePlacesController.shouldRefresh(true, 6000, 1000, 1));
    assertTrue(RoutePlacesController.shouldRefresh(true, 899, 1000, 1));
    assertTrue(RoutePlacesController.shouldRefresh(true, 1000, 1000, 600000));
  }
}
