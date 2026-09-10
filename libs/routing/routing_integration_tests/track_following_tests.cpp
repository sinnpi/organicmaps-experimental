#include "testing/testing.hpp"

#include "routing/routing_integration_tests/generated_map_test.hpp"
#include "routing/routing_integration_tests/routing_test_tools.hpp"

#include "routing/checkpoints.hpp"
#include "routing/route.hpp"

#include "map/track_following.hpp"

#include "geometry/mercator.hpp"

#include <limits>
#include <vector>

namespace track_following_integration_tests
{
using namespace routing;

namespace
{
kml::MultiGeometry Loop(bool nearlyClosed = false)
{
  kml::MultiGeometry geometry;
  kml::TrackGeometry line;
  for (auto const & point :
       {mercator::FromLatLon(55.07, 82.93), mercator::FromLatLon(55.07, 82.94), mercator::FromLatLon(55.08, 82.94),
        mercator::FromLatLon(55.08, 82.93), mercator::FromLatLon(nearlyClosed ? 55.0702 : 55.07, 82.93)})
    line.emplace_back(point, geometry::kDefaultAltitudeMeters);
  geometry.m_lines.push_back(std::move(line));
  geometry.AddTimestamps({});
  return geometry;
}

void TestVisitsInOrder(Route const & route, std::vector<m2::PointD> const & centerline)
{
  auto const & points = route.GetPoly().GetPoints();
  size_t previous = 0;
  // Ignore the overlapping start/finish; check every corner between them, in order.
  for (size_t i = 1; i + 1 < centerline.size(); ++i)
  {
    double distanceM = std::numeric_limits<double>::max();
    size_t closest = 0;
    for (size_t j = 0; j < points.size(); ++j)
    {
      auto const candidateM = mercator::DistanceOnEarth(points[j], centerline[i]);
      if (candidateM < distanceM)
      {
        distanceM = candidateM;
        closest = j;
      }
    }
    TEST_LESS(distanceM, 2.0, (i));
    TEST_GREATER(closest, previous, (i));
    previous = closest;
  }
}
}  // namespace

UNIT_TEST(TrackFollowing_RoutesWholeLoopInBothDirections)
{
  for (auto vehicle : {VehicleType::Pedestrian, VehicleType::Bicycle})
  {
    integration::GeneratedMapTest map("./data/test_data/osm/track_loop.osm", "Russia_Novosibirsk Oblast", vehicle);
    for (auto direction : {track_following::Direction::Forward, track_following::Direction::Reverse})
    {
      // Exact closure, GPS on the returning leg, distinct recorded endpoints, and an off-track approach.
      for (int scenario = 0; scenario < 4; ++scenario)
      {
        auto const geometry = Loop(scenario == 2);
        auto const current = scenario == 0                                    ? mercator::FromLatLon(55.07, 82.93)
                           : scenario == 3                                    ? mercator::FromLatLon(55.069, 82.929)
                           : direction == track_following::Direction::Forward ? mercator::FromLatLon(55.0702, 82.93)
                                                                              : mercator::FromLatLon(55.07, 82.9302);
        auto const plan = track_following::MakePlan(geometry, current, direction);
        TEST(plan, (scenario));
        Checkpoints const checkpoints(track_following::MakeCheckpoints(plan->m_centerline, current));
        map.GetComponents().GetRouter().SetTrackCorridor(std::vector<m2::PointD>(plan->m_centerline));
        auto const result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
        TEST_EQUAL(result.second, RouterResultCode::NoError, (scenario));
        TEST(result.first, ());
        auto const & route = *result.first;
        TEST_EQUAL(route.GetSubrouteCount(), checkpoints.GetNumSubroutes(), ());
        TEST_GREATER(route.GetSubrouteCount(), 1, ());
        TEST_GREATER(route.GetTotalDistanceMeters(), 3400.0, (scenario));
        TEST_LESS(route.GetTotalDistanceMeters(), scenario == 3 ? 3700.0 : 3600.0, (scenario));
        TestVisitsInOrder(route, plan->m_centerline);
      }
    }
  }
}

UNIT_TEST(TrackFollowing_JoinsLoopPartwayWithoutRestarting)
{
  integration::GeneratedMapTest map("./data/test_data/osm/track_loop.osm", "Russia_Novosibirsk Oblast",
                                    VehicleType::Pedestrian);
  auto const geometry = Loop();
  auto const current = mercator::FromLatLon(55.075, 82.94);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Forward);
  TEST(plan, ());
  Checkpoints const checkpoints(track_following::MakeCheckpoints(plan->m_centerline, current));
  TEST_EQUAL(checkpoints.GetNumSubroutes(), 1, ());
  map.GetComponents().GetRouter().SetTrackCorridor(std::vector<m2::PointD>(plan->m_centerline));
  auto const result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
  TEST_EQUAL(result.second, RouterResultCode::NoError, ());
  TEST(result.first, ());
  TEST_EQUAL(result.first->GetSubrouteCount(), 1, ());
  TEST_GREATER(result.first->GetTotalDistanceMeters(), 2200.0, ());
  TEST_LESS(result.first->GetTotalDistanceMeters(), 2400.0, ());
  TestVisitsInOrder(*result.first, plan->m_centerline);
}

UNIT_TEST(TrackFollowing_RebuildSkipsPassedLoopCheckpoints)
{
  integration::GeneratedMapTest map("./data/test_data/osm/track_loop.osm", "Russia_Novosibirsk Oblast",
                                    VehicleType::Pedestrian);
  auto const geometry = Loop();
  auto const start = geometry.m_lines.front().front().GetPoint();
  auto const plan = track_following::MakePlan(geometry, start, track_following::Direction::Forward);
  TEST(plan, ());
  Checkpoints checkpoints(track_following::MakeCheckpoints(plan->m_centerline, start));
  map.GetComponents().GetRouter().SetTrackCorridor(std::vector<m2::PointD>(plan->m_centerline));

  checkpoints.PassNextPoint();
  auto result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
  TEST_EQUAL(result.second, RouterResultCode::NoError, ());
  TEST(result.first, ());
  TEST_EQUAL(result.first->GetCurrentSubrouteIdx(), 1, ());
  TEST_EQUAL(result.first->GetSubrouteCount(), checkpoints.GetNumSubroutes(), ());
  TEST_GREATER(result.first->GetTotalDistanceMeters(), 1000.0, ());
  TEST_LESS(result.first->GetTotalDistanceMeters(), 3000.0, ());

  // The same position that means a full loop on initial selection now means only 22 m to finish.
  while (checkpoints.GetPassedIdx() + 1 < checkpoints.GetNumSubroutes())
    checkpoints.PassNextPoint();
  checkpoints.SetPointFrom(mercator::FromLatLon(55.0702, 82.93));
  result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
  TEST_EQUAL(result.second, RouterResultCode::NoError, ());
  TEST(result.first, ());
  TEST_EQUAL(result.first->GetCurrentSubrouteIdx(), checkpoints.GetPassedIdx(), ());
  TEST_EQUAL(result.first->GetSubrouteCount(), checkpoints.GetNumSubroutes(), ());
  TEST_GREATER(result.first->GetTotalDistanceMeters(), 10.0, ());
  TEST_LESS(result.first->GetTotalDistanceMeters(), 30.0, ());
}
UNIT_TEST(TrackFollowing_DetourVisitsStopThenResumesTheLoop)
{
  for (auto vehicle : {VehicleType::Pedestrian, VehicleType::Bicycle})
  {
    integration::GeneratedMapTest map("./data/test_data/osm/track_detour.osm", "Russia_Novosibirsk Oblast", vehicle);
    auto const start = mercator::FromLatLon(55.07, 82.93);
    auto const plan = track_following::MakePlan(Loop(), start, track_following::Direction::Forward);
    TEST(plan, ());
    auto const stop = mercator::FromLatLon(55.01, 82.935);
    auto const rejoin = track_following::MakeDetourCenterline(plan->m_centerline, stop);
    TEST(!rejoin.empty(), ());
    TEST_LESS(mercator::DistanceOnEarth(rejoin.front(), mercator::FromLatLon(55.07, 82.935)), 1.0, ());
    auto & router = map.GetComponents().GetRouter();
    router.SetTrackCorridor(std::vector<m2::PointD>(rejoin));
    Checkpoints checkpoints(track_following::MakeDetourCheckpoints(rejoin, start, stop));
    auto result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
    TEST_EQUAL(result.second, RouterResultCode::NoError, (vehicle));
    TEST(result.first, ());
    TEST_EQUAL(result.first->GetSubrouteCount(), checkpoints.GetNumSubroutes(), ());
    TEST_LESS(mercator::DistanceOnEarth(result.first->GetSubrouteAttrs(0).GetFinish().GetPoint(), stop), 1.0, ());
    TEST_LESS(mercator::DistanceOnEarth(result.first->GetSubrouteAttrs(1).GetFinish().GetPoint(), rejoin.front()), 1.0,
              ());
    TEST_GREATER(result.first->GetTotalDistanceMeters(), 16000.0, ());
    TestVisitsInOrder(*result.first, rejoin);

    // An off-route rebuild after visiting the shop must not visit it again or finish the ride there.
    checkpoints.PassNextPoint();
    checkpoints.SetPointFrom(mercator::FromLatLon(55.04, 82.935));
    result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
    TEST_EQUAL(result.second, RouterResultCode::NoError, ());
    TEST(result.first, ());
    TEST_EQUAL(result.first->GetCurrentSubrouteIdx(), 1, ());
    TEST_GREATER(result.first->GetTotalDistanceMeters(), 6000.0, ());
    TEST_LESS(result.first->GetTotalDistanceMeters(), 7000.0, ());
    TestVisitsInOrder(*result.first, rejoin);

    // A full rebuild while returning uses only one approach leg.
    checkpoints = Checkpoints(track_following::MakeDetourCheckpoints(rejoin, stop, std::nullopt));
    result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
    TEST_EQUAL(result.second, RouterResultCode::NoError, ());
    TEST(result.first, ());
    TEST_GREATER(result.first->GetTotalDistanceMeters(), 9500.0, ());
    TEST_LESS(result.first->GetTotalDistanceMeters(), 10500.0, ());
  }
}

UNIT_TEST(TrackFollowing_DetourStopOnTheTrack)
{
  integration::GeneratedMapTest map("./data/test_data/osm/track_detour.osm", "Russia_Novosibirsk Oblast",
                                    VehicleType::Bicycle);
  auto const start = mercator::FromLatLon(55.07, 82.93);
  auto const stop = mercator::FromLatLon(55.07, 82.935);
  auto const plan = track_following::MakePlan(Loop(), start, track_following::Direction::Forward);
  TEST(plan, ());
  auto const rejoin = track_following::MakeDetourCenterline(plan->m_centerline, stop);
  map.GetComponents().GetRouter().SetTrackCorridor(std::vector<m2::PointD>(rejoin));
  Checkpoints const checkpoints(track_following::MakeDetourCheckpoints(rejoin, start, stop));
  auto const result = integration::CalculateRoute(map.GetComponents(), checkpoints, {} /* guides */);
  TEST_EQUAL(result.second, RouterResultCode::NoError, ());
  TEST(result.first, ());
  TEST_GREATER(result.first->GetTotalDistanceMeters(), 3400.0, ());
  TEST_LESS(result.first->GetTotalDistanceMeters(), 3600.0, ());
}
}  // namespace track_following_integration_tests
