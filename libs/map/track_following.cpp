#include "map/track_following.hpp"

#include "routing/track_checkpoints.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"
#include "geometry/simplification.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <limits>

namespace track_following
{
namespace
{
// Drops recording noise while keeping the centerline far closer to the track than the corridor's
// free radius (kFreeRadiusM in track_corridor_world_graph.cpp). That margin is what makes the bias
// mean anything: simplifying harder than the free radius lets the centerline cut a corner the track
// actually turns, and the shortcut across that corner then looks more on-track than the track does.
//
// This is the centerline's only licence to differ from the recording, and deliberately so. The
// corridor charges a shortcut for the track it skips and measures that along the centerline, so
// anything dropped here is track no shortcut across it is ever charged for -- which is why a
// switchback, a reversal on legs of a few tens of metres, has to survive: flatten one and the climb
// straight up the slope comes out both shorter than the track and, being the centerline itself,
// free of any penalty at all.
double constexpr kSimplificationToleranceM = 10.0;
double constexpr kMinTrackRemainderM = 1.0;
// At a loop's meeting point, GPS noise must not select the returning leg instead of the outgoing one.
double constexpr kLoopJoinRadiusM = 50.0;

double LengthM(std::vector<m2::PointD> const & points)
{
  double lengthM = 0.0;
  for (size_t i = 1; i < points.size(); ++i)
    lengthM += mercator::DistanceOnEarth(points[i - 1], points[i]);
  return lengthM;
}

bool IsSamePoint(m2::PointD const & lhs, m2::PointD const & rhs)
{
  return mercator::DistanceOnEarth(lhs, rhs) < 1e-3;
}

struct SquaredEarthDistanceFromSegmentToPoint
{
  double operator()(m2::PointD const & start, m2::PointD const & finish, m2::PointD const & point) const
  {
    auto const projection = m2::ParametrizedSegment<m2::PointD>(start, finish).ClosestPointTo(point);
    auto const distanceM = mercator::DistanceOnEarth(point, projection);
    return distanceM * distanceM;
  }
};

std::vector<m2::PointD> GetDirectedPoints(kml::TrackGeometry const & line, Direction direction)
{
  std::vector<m2::PointD> points;
  points.reserve(line.size());
  if (direction == Direction::Forward)
    for (auto const & point : line)
      points.push_back(point.GetPoint());
  else
    for (auto it = line.rbegin(); it != line.rend(); ++it)
      points.push_back(it->GetPoint());
  return points;
}

struct ClosestProjection
{
  m2::PointD m_point;
  size_t m_lineIndex = 0;
  size_t m_segmentIndex = 0;
  double m_distanceM = std::numeric_limits<double>::max();
};

std::optional<ClosestProjection> FindClosestProjectionOnPoints(std::vector<m2::PointD> const & points,
                                                               m2::PointD const & target)
{
  std::optional<ClosestProjection> best;
  for (size_t segmentIndex = 0; segmentIndex + 1 < points.size(); ++segmentIndex)
  {
    auto const projection =
        m2::ParametrizedSegment<m2::PointD>(points[segmentIndex], points[segmentIndex + 1]).ClosestPointTo(target);
    auto const distanceM = mercator::DistanceOnEarth(target, projection);
    if (best && distanceM >= best->m_distanceM)
      continue;

    best = ClosestProjection{projection, 0 /* lineIndex */, segmentIndex, distanceM};
  }
  return best;
}

std::optional<ClosestProjection> FindClosestProjection(kml::MultiGeometry const & geometry,
                                                       m2::PointD const & currentPosition, Direction direction)
{
  std::optional<ClosestProjection> best;
  for (size_t lineIndex = 0; lineIndex < geometry.m_lines.size(); ++lineIndex)
  {
    auto const points = GetDirectedPoints(geometry.m_lines[lineIndex], direction);
    if (points.size() < 2)
      continue;

    auto candidate = FindClosestProjectionOnPoints(points, currentPosition);
    if (!candidate || (best && candidate->m_distanceM >= best->m_distanceM))
      continue;

    candidate->m_lineIndex = lineIndex;
    best = candidate;
  }
  return best;
}

// The stretch of |points| between the two projections, ordered so it starts at |from|.
std::vector<m2::PointD> SliceBetween(std::vector<m2::PointD> const & points, ClosestProjection const & from,
                                     ClosestProjection const & to)
{
  auto const offsetOf = [&points](ClosestProjection const & projection)
  { return mercator::DistanceOnEarth(points[projection.m_segmentIndex], projection.m_point); };

  bool const forward = from.m_segmentIndex < to.m_segmentIndex ||
                       (from.m_segmentIndex == to.m_segmentIndex && offsetOf(from) <= offsetOf(to));
  auto const & first = forward ? from : to;
  auto const & last = forward ? to : from;

  std::vector<m2::PointD> slice;
  slice.reserve(last.m_segmentIndex - first.m_segmentIndex + 2);
  slice.push_back(first.m_point);
  for (size_t i = first.m_segmentIndex + 1; i <= last.m_segmentIndex; ++i)
    if (!IsSamePoint(slice.back(), points[i]))
      slice.push_back(points[i]);
  if (!IsSamePoint(slice.back(), last.m_point))
    slice.push_back(last.m_point);

  if (!forward)
    std::reverse(slice.begin(), slice.end());
  return slice;
}

std::vector<m2::PointD> GetRemainingPoints(std::vector<m2::PointD> const & directed,
                                           ClosestProjection const & projection)
{
  ASSERT_LESS(projection.m_segmentIndex + 1, directed.size(), ());

  std::vector<m2::PointD> remaining;
  remaining.reserve(directed.size() - projection.m_segmentIndex);
  remaining.push_back(projection.m_point);
  for (size_t i = projection.m_segmentIndex + 1; i < directed.size(); ++i)
    if (!IsSamePoint(remaining.back(), directed[i]))
      remaining.push_back(directed[i]);
  return remaining;
}

std::vector<m2::PointD> Simplify(std::vector<m2::PointD> const & points)
{
  ASSERT_GREATER_OR_EQUAL(points.size(), 2, ());

  std::vector<m2::PointD> simplified;
  SimplifyDP(points.begin(), points.end(), kSimplificationToleranceM * kSimplificationToleranceM,
             SquaredEarthDistanceFromSegmentToPoint{},
             [&simplified](m2::PointD const & point) { simplified.push_back(point); });
  return simplified;
}

// Reduces a raw stretch of track points to the centerline of a Plan.
std::optional<Plan> MakePlanFromPoints(std::vector<m2::PointD> const & points, size_t lineIndex)
{
  if (points.size() < 2)
    return std::nullopt;

  if (LengthM(points) < kMinTrackRemainderM)
    return std::nullopt;

  auto simplified = Simplify(points);
  if (simplified.size() < 2)
    return std::nullopt;

  return Plan{std::move(simplified), lineIndex};
}
}  // namespace

std::optional<Plan> MakePlan(kml::MultiGeometry const & geometry, m2::PointD const & currentPosition,
                             Direction direction)
{
  auto const projection = FindClosestProjection(geometry, currentPosition, direction);
  if (!projection)
    return std::nullopt;

  auto const & line = geometry.m_lines[projection->m_lineIndex];
  auto const directed = GetDirectedPoints(line, direction);
  // This is only the initial selection, not navigation/rebuilding. Near the shared start/finish,
  // following a loop means going around it in the requested direction, not finishing immediately.
  if (mercator::DistanceOnEarth(currentPosition, directed.front()) <= kLoopJoinRadiusM &&
      mercator::DistanceOnEarth(directed.front(), directed.back()) <= 2.0 * kLoopJoinRadiusM &&
      LengthM(directed) > 4.0 * kLoopJoinRadiusM)
  {
    return MakePlanFromPoints(directed, projection->m_lineIndex);
  }

  auto const remaining = GetRemainingPoints(directed, *projection);
  return MakePlanFromPoints(remaining, projection->m_lineIndex);
}

std::optional<Plan> MakePlanTo(kml::MultiGeometry const & geometry, m2::PointD const & currentPosition,
                               m2::PointD const & destination)
{
  // The destination sits on the track, so its projection picks the line to follow. Choosing the line
  // by the user's position instead could land on a different line of a multi-line track and route to
  // somewhere they did not tap.
  auto const finish = FindClosestProjection(geometry, destination, Direction::Forward);
  if (!finish)
    return std::nullopt;

  auto const points = GetDirectedPoints(geometry.m_lines[finish->m_lineIndex], Direction::Forward);
  auto const start = FindClosestProjectionOnPoints(points, currentPosition);
  if (!start)
    return std::nullopt;

  return MakePlanFromPoints(SliceBetween(points, *start, *finish), finish->m_lineIndex);
}

std::vector<m2::PointD> GetRemainingCenterline(std::vector<m2::PointD> const & centerline, size_t legIndex,
                                               m2::PointD const & position)
{
  auto const indices = routing::GetTrackLegIndices(centerline);
  CHECK_LESS(legIndex + 1, indices.size(), ());
  auto const begin = indices[legIndex];
  std::vector<m2::PointD> const leg(centerline.begin() + begin, centerline.begin() + indices[legIndex + 1] + 1);
  auto projection = FindClosestProjectionOnPoints(leg, position);
  CHECK(projection, ());
  projection->m_segmentIndex += begin;
  auto remaining = GetRemainingPoints(centerline, *projection);
  if (remaining.size() < 2 || LengthM(remaining) < kMinTrackRemainderM)
    return {};
  return remaining;
}

std::vector<m2::PointD> MakeDetourCenterline(std::vector<m2::PointD> const & remaining, m2::PointD const & stop)
{
  return GetRemainingCenterline(remaining, 0, stop);
}

std::vector<m2::PointD> MakeDetourCheckpoints(std::vector<m2::PointD> const & centerline,
                                              m2::PointD const & currentPosition, std::optional<m2::PointD> stop)
{
  auto points = MakeCheckpoints(centerline, centerline.front());
  // A stop on the track is already the rejoin checkpoint, not a zero-length return leg.
  if (stop && mercator::DistanceOnEarth(*stop, centerline.front()) >= kMinTrackRemainderM)
    points.insert(points.begin(), *stop);
  points.insert(points.begin(), currentPosition);
  return points;
}

std::vector<m2::PointD> MakeCheckpoints(std::vector<m2::PointD> const & centerline, m2::PointD const & currentPosition)
{
  return routing::MakeTrackCheckpoints(centerline, currentPosition);
}
}  // namespace track_following
