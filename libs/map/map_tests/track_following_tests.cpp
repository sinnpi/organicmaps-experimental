#include "testing/testing.hpp"

#include "map/track_following.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace track_following_tests
{
namespace
{
geometry::PointWithAltitude Point(double lat, double lon)
{
  return {mercator::FromLatLon(lat, lon), geometry::kDefaultAltitudeMeters};
}

bool IsClose(m2::PointD const & lhs, m2::PointD const & rhs, double toleranceM = 0.1)
{
  return mercator::DistanceOnEarth(lhs, rhs) <= toleranceM;
}

// How far the point of |track| worst served by |centerline| ends up from it.
double MaxDeviationM(std::vector<m2::PointD> const & track, std::vector<m2::PointD> const & centerline)
{
  double worst = 0.0;
  for (auto const & point : track)
  {
    double best = std::numeric_limits<double>::max();
    for (size_t i = 1; i < centerline.size(); ++i)
    {
      auto const projection =
          m2::ParametrizedSegment<m2::PointD>(centerline[i - 1], centerline[i]).ClosestPointTo(point);
      best = std::min(best, mercator::DistanceOnEarth(point, projection));
    }
    worst = std::max(worst, best);
  }
  return worst;
}

double LengthM(std::vector<m2::PointD> const & points)
{
  double lengthM = 0.0;
  for (size_t i = 1; i < points.size(); ++i)
    lengthM += mercator::DistanceOnEarth(points[i - 1], points[i]);
  return lengthM;
}
}  // namespace

UNIT_TEST(TrackFollowing_MakesForwardSuffixFromClosestProjection)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.001, 0.012);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Forward);

  TEST(plan, ());
  TEST_EQUAL(plan->m_lineIndex, 0, ());
  TEST_GREATER(plan->m_centerline.size(), 1, ());
  TEST(IsClose(plan->m_centerline.front(), mercator::FromLatLon(0.0, 0.012)), ());
  TEST(IsClose(plan->m_centerline.back(), mercator::FromLatLon(0.0, 0.02)), ());
}

UNIT_TEST(TrackFollowing_MakesReverseSuffix)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.001, 0.012);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Reverse);

  TEST(plan, ());
  TEST(IsClose(plan->m_centerline.front(), mercator::FromLatLon(0.0, 0.012)), ());
  TEST(IsClose(plan->m_centerline.back(), mercator::FromLatLon(0.0, 0.0)), ());
}

UNIT_TEST(TrackFollowing_ChoosesClosestDisconnectedLine)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01)}, {Point(1.0, 1.0), Point(1.0, 1.01), Point(1.0, 1.02)}};

  auto const current = mercator::FromLatLon(1.001, 1.005);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Forward);

  TEST(plan, ());
  TEST_EQUAL(plan->m_lineIndex, 1, ());
  TEST(IsClose(plan->m_centerline.back(), mercator::FromLatLon(1.0, 1.02)), ());
}

// A metre of jitter either side of a straight road is recording noise, not a shape the route should
// be pulled through, so 10'000 such points collapse to the two the road actually has.
UNIT_TEST(TrackFollowing_DropsRecordingNoise)
{
  kml::MultiGeometry geometry;
  auto & line = geometry.m_lines.emplace_back();
  for (size_t i = 0; i < 10'000; ++i)
    line.push_back(Point(0.00001 * (i % 2), 0.00001 * i));

  auto const plan = track_following::MakePlan(geometry, line.front().GetPoint(), track_following::Direction::Forward);

  TEST(plan, ());
  TEST_EQUAL(plan->m_centerline.size(), 2, ());
  TEST(IsClose(plan->m_centerline.front(), line.front().GetPoint()), ());
  TEST(IsClose(plan->m_centerline.back(), line.back().GetPoint()), ());
}

// The corridor biases the router toward the track only while the centerline is nearer to the track
// than the roads it has to choose between. A centerline that cuts a corner the track turns makes the
// shortcut across that corner look more on-track than the streets the track really follows, so the
// centerline has to hold its accuracy however long the track is -- it used to be simplified harder
// and harder until it fitted a fixed point budget, which put long tracks tens of metres out.
UNIT_TEST(TrackFollowing_KeepsCenterlineOnTheTrackHoweverLongItIs)
{
  // ~30 km of winding road sampled every 10 m, the shape of a recorded ride.
  double const latPerM = 1.0 / 111'320.0;
  double const lonPerM = latPerM / 0.61;  // cos(52 degrees)
  double lat = 52.36;
  double lon = 7.95;

  kml::MultiGeometry geometry;
  auto & line = geometry.m_lines.emplace_back();
  std::vector<m2::PointD> track;
  for (size_t i = 0; i < 3000; ++i)
  {
    lat += 10.0 * latPerM * std::cos(i * 0.02);
    lon += 10.0 * lonPerM * std::sin(i * 0.02);
    line.push_back(Point(lat, lon));
    track.push_back(mercator::FromLatLon(lat, lon));
  }

  auto const plan = track_following::MakePlan(geometry, track.front(), track_following::Direction::Forward);

  TEST(plan, ());
  // The simplification tolerance, comfortably inside the corridor's free radius.
  TEST_LESS_OR_EQUAL(MaxDeviationM(track, plan->m_centerline), 10.0, ());
}

// A 13 m kink is a corner the track turns, not noise: keeping it is what lets the corridor tell the
// street the track follows from a straight one cutting the same corner.
UNIT_TEST(TrackFollowing_KeepsShallowCorner)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.00012, 0.0003), Point(0.0, 0.001)}};

  auto const plan = track_following::MakePlan(geometry, geometry.m_lines.front().front().GetPoint(),
                                              track_following::Direction::Forward);

  TEST(plan, ());
  TEST_EQUAL(plan->m_centerline.size(), 3, ());
  TEST(IsClose(plan->m_centerline[1], geometry.m_lines.front()[1].GetPoint()), ());
}

// A switchback is a reversal too, on legs far shorter than the ones above. Flattening one deletes
// the stretch of track a shortcut across it has to pay for: the corridor measures skipped track
// along the centerline, so a zigzag reduced to a straight line leaves the climb straight up the
// slope both shorter than the track and, being the centerline itself, free of any penalty at all.
UNIT_TEST(TrackFollowing_PreservesSwitchbacks)
{
  // A footpath climbing a slope in eight 25 m legs, gaining 6 m of ground on each.
  double const latPerM = 1.0 / 111'132.0;
  double const lonPerM = 1.0 / 111'320.0;

  kml::MultiGeometry geometry;
  auto & line = geometry.m_lines.emplace_back();
  std::vector<m2::PointD> track;
  for (size_t i = 0; i <= 8; ++i)
  {
    line.push_back(Point(i * 6.0 * latPerM, (i % 2 ? 25.0 : 0.0) * lonPerM));
    track.push_back(line.back().GetPoint());
  }

  auto const plan = track_following::MakePlan(geometry, track.front(), track_following::Direction::Forward);

  TEST(plan, ());
  TEST_LESS_OR_EQUAL(MaxDeviationM(track, plan->m_centerline), 10.0, ());
  // And it has to keep the track's length, not just its shape: every metre dropped is a metre the
  // climb across the zigzag is not charged for.
  TEST_GREATER(LengthM(plan->m_centerline), 0.7 * LengthM(track), ());
}

UNIT_TEST(TrackFollowing_PreservesLongReversal)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.001), Point(0.0, 0.0), Point(0.001, 0.0)}};

  auto const plan = track_following::MakePlan(geometry, geometry.m_lines.front().front().GetPoint(),
                                              track_following::Direction::Forward);

  TEST(plan, ());
  TEST_EQUAL(plan->m_centerline.size(), 4, ());
  TEST(IsClose(plan->m_centerline[1], geometry.m_lines.front()[1].GetPoint()), ());
}

UNIT_TEST(TrackFollowing_PreservesCircularTrack)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01), Point(0.01, 0.0), Point(0.0, 0.0)}};

  auto const plan = track_following::MakePlan(geometry, geometry.m_lines.front().front().GetPoint(),
                                              track_following::Direction::Forward);

  TEST(plan, ());
  TEST_GREATER(plan->m_centerline.size(), 2, ());
  TEST(IsClose(plan->m_centerline.front(), plan->m_centerline.back()), ());
}

UNIT_TEST(TrackFollowing_NearLoopStartPrefersWholeLoopInEitherDirection)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01), Point(0.01, 0.0), Point(0.0, 0.0)}};

  for (auto direction : {track_following::Direction::Forward, track_following::Direction::Reverse})
  {
    // Exactly on the returning leg, 22 m before the finish. Previously only those 22 m were kept.
    auto const current = direction == track_following::Direction::Forward ? mercator::FromLatLon(0.0002, 0.0)
                                                                          : mercator::FromLatLon(0.0, 0.0002);
    auto const plan = track_following::MakePlan(geometry, current, direction);
    TEST(plan, ());
    TEST_GREATER(LengthM(plan->m_centerline), 4000.0, ());
    auto const next = direction == track_following::Direction::Forward ? Point(0.0, 0.01) : Point(0.01, 0.0);
    TEST(IsClose(plan->m_centerline[1], next.GetPoint()), ());
  }
}

UNIT_TEST(TrackFollowing_NearlyClosedLoopKeepsWholeTrack)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01), Point(0.01, 0.0), Point(0.0002, 0.0)}};
  for (auto direction : {track_following::Direction::Forward, track_following::Direction::Reverse})
  {
    auto const plan = track_following::MakePlan(geometry, mercator::FromLatLon(0.0001, 0.0), direction);
    TEST(plan, ());
    TEST_GREATER(LengthM(plan->m_centerline), 4000.0, ());
    auto const & line = geometry.m_lines.front();
    auto const & finish = direction == track_following::Direction::Forward ? line.back() : line.front();
    TEST(IsClose(plan->m_centerline.back(), finish.GetPoint()), ());
  }
}

UNIT_TEST(TrackFollowing_AwayFromLoopStartKeepsOnlyRemainder)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01), Point(0.01, 0.0), Point(0.0, 0.0)}};
  auto const current = mercator::FromLatLon(0.005, 0.0);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Forward);
  TEST(plan, ());
  TEST(IsClose(plan->m_centerline.front(), current), ());
  TEST_LESS(LengthM(plan->m_centerline), 600.0, ());
}

UNIT_TEST(TrackFollowing_ShortOpenTrackNearBothEndsDoesNotRestart)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.0003)}};
  auto const current = mercator::FromLatLon(0.0, 0.0002);
  auto const plan = track_following::MakePlan(geometry, current, track_following::Direction::Forward);
  TEST(plan, ());
  TEST(IsClose(plan->m_centerline.front(), current), ());
}

UNIT_TEST(TrackFollowing_RejectsFinishedTrack)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01)}};

  auto const plan = track_following::MakePlan(geometry, geometry.m_lines.front().back().GetPoint(),
                                              track_following::Direction::Forward);
  TEST(!plan, ());
}

UNIT_TEST(TrackFollowing_MakesPlanToSelectedPointAhead)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.001, 0.004);
  // Short of the track's end: the plan must stop here rather than run on to 0.02.
  auto const destination = mercator::FromLatLon(0.0, 0.015);
  auto const plan = track_following::MakePlanTo(geometry, current, destination);

  TEST(plan, ());
  TEST_EQUAL(plan->m_lineIndex, 0, ());
  TEST(IsClose(plan->m_centerline.front(), mercator::FromLatLon(0.0, 0.004)), ());
  TEST(IsClose(plan->m_centerline.back(), destination), ());
}

// The direction of travel comes from where the selected point sits relative to the user, so a point
// behind them yields a plan running backwards along the track without the caller asking for it.
UNIT_TEST(TrackFollowing_MakesPlanToSelectedPointBehind)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.001, 0.015);
  auto const destination = mercator::FromLatLon(0.0, 0.004);
  auto const plan = track_following::MakePlanTo(geometry, current, destination);

  TEST(plan, ());
  TEST(IsClose(plan->m_centerline.front(), mercator::FromLatLon(0.0, 0.015)), ());
  TEST(IsClose(plan->m_centerline.back(), destination), ());
}

// A point on a second, disconnected line must route along that line, not the one nearest the user.
UNIT_TEST(TrackFollowing_MakesPlanToSelectedPointOnAnotherLine)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.02)}, {Point(1.0, 0.0), Point(1.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.0, 0.001);
  auto const destination = mercator::FromLatLon(1.0, 0.015);
  auto const plan = track_following::MakePlanTo(geometry, current, destination);

  TEST(plan, ());
  TEST_EQUAL(plan->m_lineIndex, 1, ());
  TEST(IsClose(plan->m_centerline.back(), destination), ());
}

UNIT_TEST(TrackFollowing_RejectsPlanToSelectedPointAtCurrentPosition)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.02)}};

  auto const current = mercator::FromLatLon(0.0, 0.01);
  auto const plan = track_following::MakePlanTo(geometry, current, current);
  TEST(!plan, ());
}

UNIT_TEST(TrackFollowing_LoopCheckpointsAreOrderedByDistance)
{
  auto const start = mercator::FromLatLon(0.0, 0.0);
  // Uneven sampling must not bias the checkpoints toward the densely sampled start.
  std::vector<m2::PointD> centerline = {start,
                                        mercator::FromLatLon(0.0, 0.0001),
                                        mercator::FromLatLon(0.0, 0.0002),
                                        mercator::FromLatLon(0.0, 0.012),
                                        mercator::FromLatLon(0.012, 0.012),
                                        mercator::FromLatLon(0.012, 0.0),
                                        start};
  auto const current = mercator::FromLatLon(-0.001, -0.001);
  for (bool reverse : {false, true})
  {
    if (reverse)
      std::reverse(centerline.begin(), centerline.end());
    auto const checkpoints = track_following::MakeCheckpoints(centerline, current);
    TEST_GREATER(checkpoints.size(), 2, ());
    TEST_EQUAL(checkpoints.front(), current, ());
    TEST_EQUAL(checkpoints.back(), start, ());
    // The internal checkpoint is on the far side, not among the extra samples near the start.
    TEST_GREATER(mercator::DistanceOnEarth(start, checkpoints[1]), 1000.0, ());
  }
}

UNIT_TEST(TrackFollowing_NearlyClosedTrackStillHasLoopCheckpoints)
{
  std::vector<m2::PointD> const centerline = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.01),
                                              mercator::FromLatLon(0.01, 0.01), mercator::FromLatLon(0.01, 0.0),
                                              mercator::FromLatLon(0.001, 0.0)};
  auto const checkpoints = track_following::MakeCheckpoints(centerline, mercator::FromLatLon(-0.001, 0.0));
  TEST_GREATER(checkpoints.size(), 2, ());
  TEST_EQUAL(checkpoints.back(), centerline.back(), ());
}

UNIT_TEST(TrackFollowing_OpenTrackNeedsOnlyTerminalCheckpoints)
{
  std::vector<m2::PointD> const centerline = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.001, 0.01),
                                              mercator::FromLatLon(0.0, 0.02)};
  auto const current = mercator::FromLatLon(-0.001, 0.0);
  auto const checkpoints = track_following::MakeCheckpoints(centerline, current);
  TEST_EQUAL(checkpoints.size(), 2, ());
  TEST_EQUAL(checkpoints.front(), current, ());
  TEST_EQUAL(checkpoints.back(), centerline.back(), ());
}

UNIT_TEST(TrackFollowing_ToSelectedPointNearLoopStartDoesNotRestart)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01), Point(0.01, 0.0), Point(0.0, 0.0)}};
  auto const current = mercator::FromLatLon(0.0003, 0.0);
  auto const destination = mercator::FromLatLon(0.0001, 0.0);
  auto const plan = track_following::MakePlanTo(geometry, current, destination);
  TEST(plan, ());
  TEST(IsClose(plan->m_centerline.front(), current), ());
  TEST(IsClose(plan->m_centerline.back(), destination), ());
  TEST_LESS(LengthM(plan->m_centerline), 30.0, ());
}

UNIT_TEST(TrackFollowing_DetourRejoinsAheadInEitherDirection)
{
  std::vector<m2::PointD> track = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.02)};
  for (bool reverse : {false, true})
  {
    if (reverse)
      std::reverse(track.begin(), track.end());
    auto const current = mercator::FromLatLon(0.0, 0.01);
    auto const remaining = track_following::GetRemainingCenterline(track, 0, current);
    TEST_EQUAL(remaining.front(), current, ());
    auto const stop = mercator::FromLatLon(0.001, reverse ? 0.005 : 0.015);
    auto const detour = track_following::MakeDetourCenterline(remaining, stop);
    TEST(IsClose(detour.front(), mercator::FromLatLon(0.0, reverse ? 0.005 : 0.015)), ());
    TEST_EQUAL(detour.back(), track.back(), ());
    // A stop behind us must not move the rejoin point behind the departure point.
    auto const behind = track_following::MakeDetourCenterline(remaining, track.front());
    TEST_EQUAL(behind.front(), current, ());
  }
}

UNIT_TEST(TrackFollowing_DetourDoesNotSkipAnUnvisitedLoop)
{
  std::vector<m2::PointD> const track = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.01),
                                         mercator::FromLatLon(0.01, 0.01), mercator::FromLatLon(0.01, 0.0),
                                         mercator::FromLatLon(0.0, 0.0)};
  auto const current = mercator::FromLatLon(0.0, 0.001);
  auto const remaining = track_following::GetRemainingCenterline(track, 0, current);
  // This shop is closest to the returning side, which we have not ridden yet.
  auto const stop = mercator::FromLatLon(0.001, -0.0001);
  auto const detour = track_following::MakeDetourCenterline(remaining, stop);
  TEST(IsClose(detour.front(), current), ());
  TEST_GREATER(LengthM(detour), 4000.0, ());
}

UNIT_TEST(TrackFollowing_RemainingCenterlineUsesTheCurrentVisit)
{
  std::vector<m2::PointD> const track = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.01),
                                         mercator::FromLatLon(0.0, 0.0)};
  auto const current = mercator::FromLatLon(0.0, 0.004);
  auto const outward = track_following::GetRemainingCenterline(track, 0, current);
  auto const returning = track_following::GetRemainingCenterline(track, 1, current);
  TEST_GREATER(LengthM(outward), 1700.0, ());
  TEST_LESS(LengthM(returning), 450.0, ());
  TEST_EQUAL(returning.front(), current, ());
}

UNIT_TEST(TrackFollowing_DetourCheckpointsIncludeStopAndRejoin)
{
  std::vector<m2::PointD> const track = {mercator::FromLatLon(0.0, 0.01), mercator::FromLatLon(0.0, 0.02)};
  auto const current = mercator::FromLatLon(0.0, 0.0);
  auto const stop = mercator::FromLatLon(0.001, 0.01);
  auto const checkpoints = track_following::MakeDetourCheckpoints(track, current, stop);
  TEST_EQUAL(checkpoints, (std::vector<m2::PointD>{current, stop, track.front(), track.back()}), ());
  auto const returning = track_following::MakeDetourCheckpoints(track, stop, std::nullopt);
  TEST_EQUAL(returning, (std::vector<m2::PointD>{stop, track.front(), track.back()}), ());
  auto const onTrack = track_following::MakeDetourCheckpoints(track, current, track.front());
  TEST_EQUAL(onTrack, (std::vector<m2::PointD>{current, track.front(), track.back()}), ());
}

UNIT_TEST(TrackFollowing_DetourAtTheEndFailsWithoutChangingTheTrack)
{
  std::vector<m2::PointD> const track = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0, 0.01)};
  TEST(track_following::MakeDetourCenterline(track, track.back()).empty(), ());
  TEST(track_following::GetRemainingCenterline(track, 0, track.back()).empty(), ());
}

UNIT_TEST(TrackFollowing_RejectsDegenerateGeometry)
{
  kml::MultiGeometry geometry;
  geometry.m_lines = {{Point(0.0, 0.0)}, {Point(1.0, 1.0), Point(1.0, 1.0)}};

  auto const plan =
      track_following::MakePlan(geometry, mercator::FromLatLon(0.0, 0.0), track_following::Direction::Forward);
  TEST(!plan, ());
}
}  // namespace track_following_tests
