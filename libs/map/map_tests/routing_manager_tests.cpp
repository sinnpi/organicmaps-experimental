#include "testing/testing.hpp"

#include "map/framework.hpp"
#include "map/routing_mark.hpp"
#include "map/track_following.hpp"

#include "geometry/mercator.hpp"

#include <algorithm>
#include <vector>

namespace routing_manager_tests
{
namespace
{
RouteMarkData MakeRoutePoint(RouteMarkType type, size_t intermediateIndex, double coord)
{
  RouteMarkData data;
  data.m_pointType = type;
  data.m_intermediateIndex = intermediateIndex;
  data.m_position = m2::PointD(coord, coord);
  return data;
}

size_t GetIntermediatePointsCount(std::vector<RouteMarkData> const & points)
{
  return std::count_if(points.begin(), points.end(),
                       [](RouteMarkData const & d) { return d.m_pointType == RouteMarkType::Intermediate; });
}
}  // namespace

UNIT_TEST(RoutingManager_ContinueRouteToPointAtLimitKeepsFinish)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & routingManager = framework.GetRoutingManager();

  routingManager.AddRoutePoint(MakeRoutePoint(RouteMarkType::Start, 0, 0.0));
  for (size_t i = 0; i < RoutePointsLayout::kMaxIntermediatePointsCount; ++i)
    routingManager.AddRoutePoint(MakeRoutePoint(RouteMarkType::Intermediate, i, static_cast<double>(i + 1)),
                                 false /* reorderIntermediatePoints */);
  routingManager.AddRoutePoint(MakeRoutePoint(RouteMarkType::Finish, 0, 101.0));

  auto const pointsBefore = routingManager.GetRoutePoints();
  TEST_EQUAL(pointsBefore.size(), RoutePointsLayout::kMaxRoutePointsCount, ());
  TEST_EQUAL(GetIntermediatePointsCount(pointsBefore), RoutePointsLayout::kMaxIntermediatePointsCount, ());
  TEST(pointsBefore.back().m_pointType == RouteMarkType::Finish, ());

  auto newFinish = MakeRoutePoint(RouteMarkType::Finish, 0, 102.0);
  TEST(!routingManager.ContinueRouteToPoint(std::move(newFinish)), ());

  auto const pointsAfter = routingManager.GetRoutePoints();
  TEST_EQUAL(pointsAfter.size(), pointsBefore.size(), ());
  TEST_EQUAL(GetIntermediatePointsCount(pointsAfter), RoutePointsLayout::kMaxIntermediatePointsCount, ());
  TEST(pointsAfter.back().m_pointType == RouteMarkType::Finish, ());
  TEST_EQUAL(pointsAfter.back().m_position.x, pointsBefore.back().m_position.x, ());
  TEST_EQUAL(pointsAfter.back().m_position.y, pointsBefore.back().m_position.y, ());
}

// If route marks are wiped between IsRoutingActive() and ContinueRouteToPoint(),
// the latter must return false without mutating the (empty) layout.
UNIT_TEST(RoutingManager_ContinueRouteToPointWithoutFinishFailsCleanly)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & routingManager = framework.GetRoutingManager();

  TEST_EQUAL(routingManager.GetRoutePointsCount(), 0, ());

  auto newFinish = MakeRoutePoint(RouteMarkType::Finish, 0, 1.0);
  TEST(!routingManager.ContinueRouteToPoint(std::move(newFinish)), ());
  TEST_EQUAL(routingManager.GetRoutePointsCount(), 0, ());
}

UNIT_TEST(RoutingManager_PrepareTrackFollowCreatesOnlyTerminalRouteMarks)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & bookmarkManager = framework.GetBookmarkManager();
  auto & routingManager = framework.GetRoutingManager();

  auto const currentPosition = mercator::FromLatLon(0.001, 0.005);
  bookmarkManager.MyPositionMark().SetUserPosition(currentPosition, true /* hasPosition */);

  kml::TrackData trackData;
  kml::SetDefaultStr(trackData.m_name, "Test track");
  trackData.m_layers.emplace_back();
  trackData.m_geometry.AddLine({{mercator::FromLatLon(0.0, 0.0), 0},
                                {mercator::FromLatLon(0.0, 0.01), 0},
                                {mercator::FromLatLon(0.0, 0.02), 0}});
  // MultiGeometry keeps one timestamp list per line and asserts the two stay in step.
  trackData.m_geometry.AddTimestamps({});

  kml::TrackId trackId;
  {
    auto editSession = bookmarkManager.GetEditSession();
    trackId = editSession.CreateTrack(std::move(trackData))->GetId();
  }
  auto const result = routingManager.PrepareTrackFollow(trackId, track_following::Direction::Forward);

  TEST(result == RoutingManager::PrepareTrackFollowResult::Success, ());
  TEST(routingManager.IsTrackFollowMode(), ());
  auto const routePoints = routingManager.GetRoutePoints();
  TEST_EQUAL(routePoints.size(), 2, ());
  TEST(routePoints.front().m_pointType == RouteMarkType::Start, ());
  TEST(routePoints.front().m_isMyPosition, ());
  TEST(routePoints.back().m_pointType == RouteMarkType::Finish, ());
  TEST_EQUAL(routePoints.back().m_title, "Test track", ());

  auto const trackRouter = routingManager.GetRouter();
  routingManager.SetRouter(routing::RouterType::Vehicle);
  TEST(routingManager.GetRouter() == trackRouter, ());

  routingManager.RemoveRoutePoints();
  TEST(!routingManager.IsTrackFollowMode(), ());
}
}  // namespace routing_manager_tests
