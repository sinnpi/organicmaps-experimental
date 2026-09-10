package app.organicmaps.sdk.location;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the polyline arithmetic behind route simulation. A wrong cursor advance would silently
 * produce the wrong speed, which would make power measurement runs incomparable rather than fail
 * outright -- see docs/POWER_MEASUREMENT.md.
 */
public class RouteSimulationProviderTest
{
  private static final double EPS = 1e-9;

  // Cumulative distances of a 3-segment route: 0 -> 100 -> 300 -> 600 m.
  private static final double[] ROUTE = {0.0, 100.0, 300.0, 600.0};

  @Test
  public void segmentIsFoundForDistanceInsideIt()
  {
    assertEquals(0, RouteSimulationProvider.advanceSegment(ROUTE, 0, 50.0));
    assertEquals(1, RouteSimulationProvider.advanceSegment(ROUTE, 0, 150.0));
    assertEquals(2, RouteSimulationProvider.advanceSegment(ROUTE, 0, 450.0));
  }

  @Test
  public void junctionDistanceStaysOnTheSegmentItEnds()
  {
    // Exactly at a junction the previous segment is still current, with fraction 1.0, which puts
    // the position on the junction either way.
    assertEquals(0, RouteSimulationProvider.advanceSegment(ROUTE, 0, 100.0));
    assertEquals(1.0, RouteSimulationProvider.fractionInSegment(ROUTE, 0, 100.0), EPS);
  }

  @Test
  public void cursorNeverWalksPastTheLastSegment()
  {
    // Past the end of the route: clamped to the final segment rather than running off the array.
    assertEquals(2, RouteSimulationProvider.advanceSegment(ROUTE, 0, 10_000.0));
    assertEquals(1.0, RouteSimulationProvider.fractionInSegment(ROUTE, 2, 10_000.0), EPS);
  }

  @Test
  public void advancingFromAPreviousCursorGivesTheSameAnswer()
  {
    // The provider carries the cursor between ticks; that must not change the result.
    for (int from = 0; from <= 2; ++from)
      assertEquals(2, RouteSimulationProvider.advanceSegment(ROUTE, Math.min(from, 2), 450.0));
  }

  @Test
  public void fractionIsProportionalWithinASegment()
  {
    assertEquals(0.0, RouteSimulationProvider.fractionInSegment(ROUTE, 1, 100.0), EPS);
    assertEquals(0.5, RouteSimulationProvider.fractionInSegment(ROUTE, 1, 200.0), EPS);
    assertEquals(0.25, RouteSimulationProvider.fractionInSegment(ROUTE, 2, 375.0), EPS);
  }

  @Test
  public void coincidentJunctionsDoNotDivideByZero()
  {
    final double[] withDuplicate = {0.0, 100.0, 100.0, 250.0};
    assertEquals(0.0, RouteSimulationProvider.fractionInSegment(withDuplicate, 1, 100.0), EPS);
    // The zero-length segment is stepped over rather than becoming a resting place.
    assertEquals(2, RouteSimulationProvider.advanceSegment(withDuplicate, 0, 150.0));
    assertEquals(1.0 / 3.0, RouteSimulationProvider.fractionInSegment(withDuplicate, 2, 150.0), EPS);
  }

  @Test
  public void constantSpeedAdvancesEquallyEachTick()
  {
    // 5 m/s for one second per tick must cover 5 m of route regardless of junction spacing, which
    // is the property that makes runs comparable.
    final double speedMps = 5.0;
    double travelled = 0.0;
    int segment = 0;
    double previousPosition = 0.0;

    for (int tick = 0; tick < 100; ++tick)
    {
      segment = RouteSimulationProvider.advanceSegment(ROUTE, segment, travelled);
      final double fraction = RouteSimulationProvider.fractionInSegment(ROUTE, segment, travelled);
      final double position = ROUTE[segment] + fraction * (ROUTE[segment + 1] - ROUTE[segment]);

      if (tick > 0)
        assertEquals(speedMps, position - previousPosition, EPS);

      previousPosition = position;
      travelled += speedMps;
    }
  }
}
