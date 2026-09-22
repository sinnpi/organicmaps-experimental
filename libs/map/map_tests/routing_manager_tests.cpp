#include "testing/testing.hpp"

#include "map/elevation_info.hpp"
#include "map/framework.hpp"
#include "map/routing_mark.hpp"
#include "map/track_following.hpp"

#include "routing/route.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "geometry/mercator.hpp"

#include <algorithm>
#include <memory>
#include <utility>
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
routing::Route MakeElevationRoute(geometry::Altitude peakAltitude)
{
  std::vector<m2::PointD> const points = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.01),
                                          mercator::FromLatLon(0.0, 0.02)};
  geometry::PointWithAltitude const start(points.front(), 100);
  geometry::PointWithAltitude const peak(points[1], peakAltitude);
  geometry::PointWithAltitude const finish(points.back(), 50);
  std::vector<routing::RouteSegment> segments = {{routing::Segment(), {}, peak, {}},
                                                 {routing::Segment(), {}, finish, {}}};
  double const segmentLength = mercator::DistanceOnEarth(points[0], points[1]);
  segments[0].SetDistancesAndTime(segmentLength, 0.01, 100.0);
  segments[1].SetDistancesAndTime(2 * segmentLength, 0.02, 200.0);

  routing::Route route;
  route.SetGeometry(points.begin(), points.end());
  route.SetRouteSegments(std::move(segments));
  route.SetSubroutes(std::vector<routing::Route::SubrouteAttrs>{{start, finish, 0, 2}});
  return route;
}
}  // namespace

UNIT_TEST(RoutingManager_ElevationBeforeAndDuringNavigationForAllRouters)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & manager = framework.GetRoutingManager();
  auto numMwmIds = std::make_shared<routing::NumMwmIds>();
  numMwmIds->RegisterFile(platform::CountryFile("Gibraltar"));
  manager.Init(std::move(numMwmIds));
  auto const originalRouter = manager.GetRouter();

  for (auto const type : {routing::RouterType::Vehicle, routing::RouterType::Pedestrian, routing::RouterType::Bicycle,
                          routing::RouterType::Transit})
  {
    manager.SetRouter(type);
    auto & session = manager.RoutingSession();
    session.AssignRouteForTesting(MakeElevationRoute(300), routing::RouterResultCode::NoError);
    TEST(!manager.IsTrackFollowMode(), (type));
    TEST(manager.HasRouteAltitude(), (type));

    ElevationInfo preview;
    TEST(manager.GetRouteElevationInfo(preview), (type));
    TEST_EQUAL(preview.GetSize(), 3, (type));
    TEST_EQUAL(preview.GetLength(), session.GetRoute()->GetSegDistanceMeters().back(), (type));
    TEST(session.EnableFollowMode(), (type));

    ElevationInfo navigation;
    TEST(manager.GetRouteElevationInfo(navigation), (type));
    TEST_EQUAL(navigation.GetLines().size(), 1, (type));
    TEST_EQUAL(navigation.GetSize(), preview.GetSize(), (type));
    for (size_t i = 0; i < preview.GetSize(); ++i)
    {
      TEST_EQUAL(navigation.GetLines()[0][i].m_distance, preview.GetLines()[0][i].m_distance, (type, i));
      TEST_EQUAL(navigation.GetLines()[0][i].m_altitude, preview.GetLines()[0][i].m_altitude, (type, i));
    }

    // Rebuilding replaces the profile and progress rather than retaining the old geometry.
    session.AssignRouteForTesting(MakeElevationRoute(500), routing::RouterResultCode::NoError);
    TEST(manager.GetRouteElevationInfo(navigation), (type));
    TEST_EQUAL(navigation.CalculateAltitudesInfo(ElevationInfo::kDefThresholdMWM).m_maxAltitude, 500, (type));
    TEST_EQUAL(manager.GetRouteDistanceFromBeginMeters().value(), 0.0, (type));

    session.AssignRouteForTesting(MakeElevationRoute(geometry::kInvalidAltitude), routing::RouterResultCode::NoError);
    TEST(!manager.HasRouteAltitude(), (type));
    TEST(!manager.GetRouteElevationInfo(navigation), (type));
    session.Reset();
    TEST(!manager.GetRouteElevationInfo(navigation), (type));
  }
  manager.SetRouter(originalRouter);
}

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

// The navigation elevation profile polls these every location update, so they must stay quiet
// when there is nothing to report instead of touching an absent route.
UNIT_TEST(RoutingManager_RouteProgressIsEmptyWithoutRoute)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto const & routingManager = framework.GetRoutingManager();

  TEST(!routingManager.GetRouteDistanceFromBeginMeters().has_value(), ());
  TEST(!routingManager.GetRoutePointAtDistance(0.0).has_value(), ());
  TEST(!routingManager.GetRouteRectBetween(0.0, 1000.0, ang::AngleD(0.0)).has_value(), ());
  TEST(!routingManager.GetRouteAheadRect(1000.0).has_value(), ());
  TEST(!routingManager.HasRouteAltitude(), ());

  // Search asks this for every result it is about to put on the map.
  TEST(!routingManager.GetRoutePosition(mercator::FromLatLon(0.0, 0.0)).has_value(), ());

  ElevationInfo ei;
  TEST(!routingManager.GetRouteElevationInfo(ei), ());
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

  TEST(!routingManager.GetTrackIgnoreAccessRestrictions(), ());
  routingManager.SetTrackIgnoreAccessRestrictions(true);
  TEST(routingManager.GetTrackIgnoreAccessRestrictions(), ());
  routingManager.CloseRouting(false /* removeRoutePoints */);
  TEST(routingManager.GetTrackIgnoreAccessRestrictions(), ("Rebuild keeps the override"));
  TEST(routingManager.PrepareTrackFollow(trackId, track_following::Direction::Reverse) ==
           RoutingManager::PrepareTrackFollowResult::Success,
       ());
  TEST(!routingManager.GetTrackIgnoreAccessRestrictions(), ("A new track starts with restrictions enabled"));
  routingManager.SetTrackIgnoreAccessRestrictions(true);

  auto const trackRouter = routingManager.GetRouter();
  routingManager.SetRouter(routing::RouterType::Vehicle);
  TEST(routingManager.GetRouter() == trackRouter, ());

  routingManager.RemoveRoutePoints();
  TEST(!routingManager.IsTrackFollowMode(), ());
  TEST(!routingManager.GetTrackIgnoreAccessRestrictions(), ());
  routingManager.SetTrackIgnoreAccessRestrictions(true);
  TEST(!routingManager.GetTrackIgnoreAccessRestrictions(), ("Normal routes cannot opt in"));
}
UNIT_TEST(RoutingManager_TrackDetourKeepsDestinationAndCanBeRemoved)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & bookmarks = framework.GetBookmarkManager();
  auto & manager = framework.GetRoutingManager();
  bookmarks.MyPositionMark().SetUserPosition(mercator::FromLatLon(0.0, 0.0), true);
  kml::TrackData track;
  kml::SetDefaultStr(track.m_name, "Original destination");
  track.m_layers.emplace_back();
  track.m_geometry.AddLine({{mercator::FromLatLon(0.0, 0.0), 0}, {mercator::FromLatLon(0.0, 0.02), 0}});
  track.m_geometry.AddTimestamps({});
  auto const id = bookmarks.GetEditSession().CreateTrack(std::move(track))->GetId();
  TEST(manager.PrepareTrackFollow(id, track_following::Direction::Forward) ==
           RoutingManager::PrepareTrackFollowResult::Success,
       ());
  auto const original = manager.GetRoutePoints();

  manager.SetTrackIgnoreAccessRestrictions(true);
  // A preview is not an active ride and must not accept a detour.
  TEST(!manager.CanAddTrackDetour(), ());
  manager.RoutingSession().AssignRouteForTesting(MakeElevationRoute(300), routing::RouterResultCode::NoError);
  TEST(manager.RoutingSession().EnableFollowMode(), ());
  TEST(manager.CanAddTrackDetour(), ());

  RouteMarkData stop;
  stop.m_title = "Water";
  stop.m_position = mercator::FromLatLon(0.001, 0.005);
  TEST(manager.AddTrackDetour(std::move(stop)), ());
  TEST(manager.IsTrackFollowMode(), ());
  TEST(manager.GetTrackIgnoreAccessRestrictions(), ("Detours keep the current track's option"));
  TEST(!manager.CanAddTrackDetour(), ());
  auto const points = manager.GetRoutePoints();
  TEST_EQUAL(points.size(), 3, ());
  TEST(points[1].m_pointType == RouteMarkType::Intermediate, ());
  TEST_EQUAL(points[1].m_title, "Water", ());
  TEST_EQUAL(points.back().m_position, original.back().m_position, ());
  TEST_EQUAL(points.back().m_title, original.back().m_title, ());
  TEST(points.front().m_isMyPosition, ());

  // A failed detour build must leave the stop removable and the track destination intact.
  manager.SetRouteBuildingListener([](auto, auto const &) {});
  manager.OnRemoveRoute(routing::RouterResultCode::RouteNotFound);
  TEST(manager.IsTrackFollowMode(), ());
  TEST_EQUAL(manager.GetRoutePointsCount(), 3, ());
  manager.RemoveRoutePoint(RouteMarkType::Intermediate, 0);
  TEST(manager.IsTrackFollowMode(), ());
  TEST_EQUAL(manager.GetRoutePointsCount(), 2, ());
  TEST_EQUAL(manager.GetRoutePoints().back().m_position, original.back().m_position, ());
  manager.CloseRouting(true);
  TEST(!manager.IsTrackFollowMode(), ());
  TEST(!manager.CanAddTrackDetour(), ());
}

UNIT_TEST(RoutingManager_RejectedTrackDetourDoesNotChangeTheItinerary)
{
  Framework framework(FrameworkParams(false /* m_enableDiffs */));
  auto & bookmarks = framework.GetBookmarkManager();
  auto & manager = framework.GetRoutingManager();
  bookmarks.MyPositionMark().SetUserPosition(mercator::FromLatLon(0.0, 0.0), true);
  kml::TrackData track;
  track.m_layers.emplace_back();
  track.m_geometry.AddLine({{mercator::FromLatLon(0.0, 0.0), 0}, {mercator::FromLatLon(0.0, 0.02), 0}});
  track.m_geometry.AddTimestamps({});
  auto const id = bookmarks.GetEditSession().CreateTrack(std::move(track))->GetId();
  TEST(manager.PrepareTrackFollow(id, track_following::Direction::Forward) ==
           RoutingManager::PrepareTrackFollowResult::Success,
       ());
  manager.RoutingSession().AssignRouteForTesting(MakeElevationRoute(300), routing::RouterResultCode::NoError);
  TEST(manager.RoutingSession().EnableFollowMode(), ());
  auto const before = manager.GetRoutePoints();
  RouteMarkData stop;
  stop.m_position = mercator::FromLatLon(0.0, 0.03);  // Beyond the track's finish.
  TEST(!manager.AddTrackDetour(std::move(stop)), ());
  TEST(manager.IsTrackFollowMode(), ());
  TEST(manager.IsRoutingFollowing(), ());
  TEST_EQUAL(manager.GetRoutePointsCount(), before.size(), ());
  TEST_EQUAL(manager.GetRoutePoints().back().m_position, before.back().m_position, ());
}
}  // namespace routing_manager_tests
