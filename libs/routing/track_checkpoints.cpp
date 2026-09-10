#include "routing/track_checkpoints.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"
#include "geometry/segment2d.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <optional>

namespace routing
{
namespace
{
// Ignore GPS jitter and small junction manoeuvres, but distinguish separate visits to a road.
double constexpr kRevisitRadiusM = 40.0;
double constexpr kMinExcursionM = 200.0;

// Distance along the track to the middle of an excursion between two nearby segments. Using the
// actual projections matters: a short jitter followed by a long segment is not a long excursion.
std::optional<double> ExcursionMiddle(m2::PointD const & a, m2::PointD const & b, double fromM, m2::PointD const & c,
                                      m2::PointD const & d, double toM)
{
  std::optional<double> middle;
  double longestM = kMinExcursionM;
  auto const consider = [&](m2::PointD const & first, m2::PointD const & last)
  {
    if (mercator::DistanceOnEarth(first, last) > kRevisitRadiusM)
      return;
    double const beginM = fromM + mercator::DistanceOnEarth(a, first);
    double const endM = toM + mercator::DistanceOnEarth(c, last);
    if (endM - beginM > longestM)
    {
      longestM = endM - beginM;
      middle = (beginM + endM) * 0.5;
    }
  };
  m2::ParametrizedSegment<m2::PointD> const first(a, b), last(c, d);
  consider(a, last.ClosestPointTo(a));
  consider(b, last.ClosestPointTo(b));
  consider(first.ClosestPointTo(c), c);
  consider(first.ClosestPointTo(d), d);
  auto const intersection = m2::Intersect({a, b}, {c, d}, 1e-12);
  if (intersection.m_type == m2::IntersectionResult::Type::One)
    consider(intersection.m_point, intersection.m_point);
  return middle;
}
}  // namespace

std::vector<size_t> GetTrackLegIndices(std::vector<m2::PointD> const & centerline)
{
  CHECK_GREATER_OR_EQUAL(centerline.size(), 2, ());
  std::vector<double> distances(centerline.size());
  for (size_t i = 1; i < centerline.size(); ++i)
    distances[i] = distances[i - 1] + mercator::DistanceOnEarth(centerline[i - 1], centerline[i]);

  std::vector<size_t> indices = {0};
  size_t end = 2;
  while (end < centerline.size())
  {
    auto const begin = indices.back();
    std::optional<double> middle;
    for (size_t previous = begin + 1; previous < end; ++previous)
    {
      if (distances[end] - distances[previous - 1] < kMinExcursionM)
        continue;
      middle = ExcursionMiddle(centerline[previous - 1], centerline[previous], distances[previous - 1],
                               centerline[end - 1], centerline[end], distances[end - 1]);
      if (middle)
        break;
    }
    // Nearly closed recordings need protection even when the endpoint gap exceeds GPS noise.
    auto const lengthM = distances[end] - distances[begin];
    if (!middle && lengthM > kMinExcursionM &&
        mercator::DistanceOnEarth(centerline[begin], centerline[end]) < 0.25 * lengthM)
      middle = distances[begin] + 0.5 * lengthM;

    if (!middle)
    {
      ++end;
      continue;
    }
    // Put the checkpoint inside the excursion, not at its shared entrance/exit. Rescan from there
    // because the remainder may contain another loop (or the return leg of a nested excursion).
    auto const it = std::lower_bound(distances.begin() + begin + 1, distances.begin() + end, *middle);
    auto const next = std::min(static_cast<size_t>(it - distances.begin()), end - 1);
    ASSERT_GREATER(next, begin, ());
    indices.push_back(next);
    end = next + 2;
  }
  indices.push_back(centerline.size() - 1);
  return indices;
}

std::vector<m2::PointD> MakeTrackCheckpoints(std::vector<m2::PointD> const & centerline,
                                             m2::PointD const & currentPosition)
{
  auto const indices = GetTrackLegIndices(centerline);
  std::vector<m2::PointD> points;
  points.reserve(indices.size());
  points.push_back(currentPosition);
  for (size_t i = 1; i < indices.size(); ++i)
    points.push_back(centerline[indices[i]]);
  return points;
}
}  // namespace routing
