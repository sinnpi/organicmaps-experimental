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
// point is normally the closest projection of |currentPosition| onto the selected track line and the
// last point is the end of that line in the requested direction. Near a loop's shared start/finish,
// the whole line is kept in that direction instead. It follows the track closely enough for
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

// Remaining track on the current ordered leg and all later legs. Projection is restricted to that
// leg so a nearby later visit to the same road cannot discard an unvisited loop.
std::vector<m2::PointD> GetRemainingCenterline(std::vector<m2::PointD> const & centerline, size_t legIndex,
                                               m2::PointD const & position);

// Choose a rejoin point ahead, within the first remaining leg. Returns empty at the track's end.
std::vector<m2::PointD> MakeDetourCenterline(std::vector<m2::PointD> const & remaining, m2::PointD const & stop);

// The track before that rejoin point: what is still to be ridden before the detour leaves the track.
// The leg heading for |stop| is biased toward it, so a detour does not straighten the stretch of
// track it has not reached yet. Empty when the rejoin point is where we already are.
std::vector<m2::PointD> MakeApproachCenterline(std::vector<m2::PointD> const & remaining, m2::PointD const & stop);

// Route from |currentPosition| to the selected track's end, with ordered internal checkpoints
// separating repeated visits to the same road. See routing::GetTrackLegIndices.
std::vector<m2::PointD> MakeCheckpoints(std::vector<m2::PointD> const & centerline, m2::PointD const & currentPosition);

// Prefix an approach to the rejoin point, optionally via an unvisited stop. The leg heading for the
// stop follows MakeApproachCenterline; the one coming back from it is off-track by definition and is
// routed without a corridor. The remaining legs follow |centerline| as usual.
std::vector<m2::PointD> MakeDetourCheckpoints(std::vector<m2::PointD> const & centerline,
                                              m2::PointD const & currentPosition, std::optional<m2::PointD> stop);
}  // namespace track_following
