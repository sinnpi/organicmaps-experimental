#pragma once

#include "kml/types.hpp"

#include "geometry/point2d.hpp"

#include <cstddef>
#include <optional>
#include <vector>

namespace track_following
{
enum class Direction
{
  Forward,
  Reverse
};

// The centerline of the corridor an OSM route along an imported track is biased toward. The first
// point is the closest projection of |currentPosition| onto the selected track line and the last
// point is the end of that line in the requested direction. It follows the track closely enough for
// the corridor to tell the roads the track runs along from the ones it deliberately avoids, so its
// length is set by the shape of the track rather than by a point budget.
struct Plan
{
  std::vector<m2::PointD> m_centerline;
  size_t m_lineIndex = 0;
};

// Chooses the closest line from |geometry|, takes its remaining part in |direction| and drops its
// recording noise. The router stays close to the result (see IRouter::SetTrackCorridor), which snaps
// the track onto roads and trails supported by the selected routing profile.
std::optional<Plan> MakePlan(kml::MultiGeometry const & geometry, m2::PointD const & currentPosition,
                             Direction direction);

// Same, but ending at |destination| -- a point the user picked on the track -- rather than at one of
// the track's ends. Which way along the track that means travelling follows from where the two
// projections fall relative to each other, so it is not a separate choice.
std::optional<Plan> MakePlanTo(kml::MultiGeometry const & geometry, m2::PointD const & currentPosition,
                               m2::PointD const & destination);

// The point half way along |centerline| by distance travelled. Its points sit where the track
// changes shape rather than at a fixed spacing, so the middle of the vector is not the middle of
// the track.
m2::PointD PointAtHalfLength(std::vector<m2::PointD> const & centerline);
}  // namespace track_following
