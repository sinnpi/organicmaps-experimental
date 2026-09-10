#pragma once

#include "geometry/point2d.hpp"

#include <cstddef>
#include <vector>

namespace routing
{
// Ordered centerline indices, including both ends. Split before a stretch revisits earlier ground,
// so each leg can be matched against its own corridor without skipping a loop or an out-and-back.
// Indices, rather than nearest-point projections, distinguish repeated visits to the same place.
std::vector<size_t> GetTrackLegIndices(std::vector<m2::PointD> const & centerline);

std::vector<m2::PointD> MakeTrackCheckpoints(std::vector<m2::PointD> const & centerline,
                                             m2::PointD const & currentPosition);
}  // namespace routing
