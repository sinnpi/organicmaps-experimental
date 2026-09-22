#include "routing/track_corridor_world_graph.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <tuple>

namespace routing
{
namespace
{
// No penalty within ordinary GPS/OSM alignment noise.
double constexpr kFreeRadiusM = 20.0;
// Deviation beyond the free radius at which an edge costs twice its normal time. Tuned so a road
// running a street apart from the track loses to the on-track road even when it is nominally faster.
double constexpr kPenaltyScaleM = 15.0;
// A shortcut pays for the track it skips at its own pace, which alone only makes it break even with
// the track. Doubling that is what decides it: track following prices every road by distance (see
// IndexRouter::CalculateTrackFollowingRoute), so a shortcut ends up paying for the stretch of track
// it replaces plus the distance it saved, and the track wins by exactly what the shortcut saved.
double constexpr kSkippedTrackFactor = 2.0;
// Below this a segment has no meaningful direction, so what it appears to skip is projection noise.
double constexpr kMinJudgedSegmentLengthM = 1.0;
// Limit the distance-from-track charge for unavoidable detours or offset recordings. Do not cap
// the skipped-track charge: a short connector must still pay for a long loop it bypasses.
double constexpr kMaxDistancePenaltyFactor = 10.0;
// Initial hard search-space bound. Widened by the caller (see SetMaxCorridorRadiusM) and the route
// search retried if this is too tight to find any path at all.
double constexpr kDefaultMaxCorridorRadiusM = 500.0;
}  // namespace

TrackCorridorWorldGraph::TrackCorridorWorldGraph(WorldGraph & inner, std::vector<m2::PointD> centerline)
  : m_inner(inner)
  , m_maxCorridorRadiusM(kDefaultMaxCorridorRadiusM)
{
  SetCenterline(std::move(centerline));
}

void TrackCorridorWorldGraph::SetCenterline(std::vector<m2::PointD> centerline)
{
  CHECK_GREATER_OR_EQUAL(centerline.size(), 2, ());
  m_centerline = std::move(centerline);
  m_penaltyCache.clear();
  m_arcLengthM.assign(m_centerline.size(), 0.0);
  for (size_t i = 1; i < m_centerline.size(); ++i)
    m_arcLengthM[i] = m_arcLengthM[i - 1] + mercator::DistanceOnEarth(m_centerline[i - 1], m_centerline[i]);
}

TrackCorridorWorldGraph::ProjectionsT TrackCorridorWorldGraph::ProjectOnCorridor(PointsT const & points) const
{
  // Scanned rather than indexed on purpose: m4::Tree does narrow this to a handful of candidates,
  // but its 4d range traversal measured several times more expensive than projecting onto every
  // segment, at every centerline length we produce. Walking the centerline is what costs here, not
  // the projection onto each of its legs, so all of |points| share the one traversal -- measured
  // close to twice as fast as scanning once per point.
  struct Best
  {
    double m_squared = std::numeric_limits<double>::max();
    m2::PointD m_point;
    size_t m_index = 1;
  };
  std::array<Best, std::tuple_size_v<PointsT>> best;

  for (size_t i = 1; i < m_centerline.size(); ++i)
  {
    m2::ParametrizedSegment<m2::PointD> const leg(m_centerline[i - 1], m_centerline[i]);
    for (size_t k = 0; k < points.size(); ++k)
    {
      auto const projection = leg.ClosestPointTo(points[k]);
      double const squared = points[k].SquaredLength(projection);
      if (squared < best[k].m_squared)
        best[k] = {squared, projection, i};
    }
  }

  // Only the closest projection of each point is converted to metres: DistanceOnEarth is
  // trigonometric and would otherwise dominate the search.
  ProjectionsT projections;
  for (size_t k = 0; k < points.size(); ++k)
    projections[k] = {mercator::DistanceOnEarth(points[k], best[k].m_point),
                      m_arcLengthM[best[k].m_index - 1] +
                          mercator::DistanceOnEarth(m_centerline[best[k].m_index - 1], best[k].m_point)};
  return projections;
}

TrackCorridorWorldGraph::Penalty TrackCorridorWorldGraph::CalcPenalty(Segment const & judged) const
{
  auto const back = mercator::FromLatLon(m_inner.GetPoint(judged, false /* front */));
  auto const front = mercator::FromLatLon(m_inner.GetPoint(judged, true /* front */));

  auto const projections = ProjectOnCorridor({(back + front) * 0.5, back, front});
  auto const & middle = projections[0];
  auto const & atBack = projections[1];
  auto const & atFront = projections[2];

  // Judge how far off the track the segment lies by its middle rather than by an end. An end is
  // shared with the segments either side of it, so the junction where a shortcut rejoins the track
  // makes the leg arriving there look on-track and the last stretch of a detour comes for free. The
  // middle is also the one point both directions of the same road agree on, which either end is not.
  Penalty penalty;
  penalty.m_distanceM = middle.m_distanceM;

  if (penalty.m_distanceM > kFreeRadiusM)
  {
    // Scaling the edge's own weight rather than adding a flat cost keeps the bias independent of how
    // OSM happens to split a road into segments: a flat per-edge penalty would punish a finely
    // subdivided road far more than a single long segment covering the same ground.
    penalty.m_factor = std::min((penalty.m_distanceM - kFreeRadiusM) / kPenaltyScaleM, kMaxDistancePenaltyFactor);
  }

  // How much track the segment covers, against how long it is. A segment running along the track
  // advances a metre of track per metre travelled; one cutting a corner advances more, and the
  // surplus is the stretch of track it skips. Distance from the centerline cannot see that on its
  // own -- a bypass across a shallow bend, or straight up a slope the track switchbacks, stays well
  // inside the free radius while standing in for several times its own length of track. Charged at
  // the segment's own pace, so a shortcut costs at least the piece of track it replaces no matter
  // how finely it is split: the surpluses of its segments sum to the whole stretch skipped.
  auto const lengthM = mercator::DistanceOnEarth(back, front);
  if (lengthM > kMinJudgedSegmentLengthM)
  {
    auto const coveredM = std::fabs(atFront.m_arcM - atBack.m_arcM);
    if (coveredM > lengthM)
      penalty.m_factor += kSkippedTrackFactor * (coveredM - lengthM) / lengthM;
  }

  return penalty;
}

TrackCorridorWorldGraph::Penalty const & TrackCorridorWorldGraph::GetPenalty(Segment const & judged) const
{
  auto const cached = m_penaltyCache.find(judged);
  return cached != m_penaltyCache.end() ? cached->second
                                       : m_penaltyCache.emplace(judged, CalcPenalty(judged)).first->second;
}

RouteWeight TrackCorridorWorldGraph::CalcSegmentWeight(Segment const & segment, EdgeEstimator::Purpose purpose)
{
  auto weight = m_inner.CalcSegmentWeight(segment, purpose);
  if (purpose == EdgeEstimator::Purpose::Weight && !m_centerline.empty())
  {
    // Starter-owned partial roads are priced here, without going through GetEdgeList. Otherwise
    // a shortcut incident to a snapped endpoint/checkpoint escapes the corridor penalty entirely.
    // The starter scales this weight by the traversed fraction, just like the underlying road cost.
    auto const factor = GetPenalty(segment).m_factor;
    if (factor > 0.0)
      weight += RouteWeight(weight.GetWeight() * factor);
  }
  return weight;
}

bool TrackCorridorWorldGraph::AdjustForCorridor(Segment const & judged, RouteWeight & weight) const
{
  if (m_centerline.empty())
    return true;

  auto const & penalty = GetPenalty(judged);
  if (penalty.m_distanceM > m_maxCorridorRadiusM)
    return false;

  if (penalty.m_factor > 0.0)
    weight += RouteWeight(weight.GetWeight() * penalty.m_factor);

  return true;
}

void TrackCorridorWorldGraph::GetEdgeList(astar::VertexData<Segment, RouteWeight> const & vertexData, bool isOutgoing,
                                          bool useRoutingOptions, bool useAccessConditional, SegmentEdgeListT & edges)
{
  m_inner.GetEdgeList(vertexData, isOutgoing, useRoutingOptions, useAccessConditional, edges);

  // IndexGraph::CalculateEdgeWeight prices a directed edge u->v as the weight of v regardless of
  // which way it is discovered, so that the forward and backward A* waves agree on its cost. The
  // corridor penalty has to be keyed off that same segment, otherwise the two waves would price the
  // same edge differently and meet on a path neither of them actually costed.
  size_t writeIdx = 0;
  for (size_t readIdx = 0; readIdx < edges.size(); ++readIdx)
  {
    auto const & judged = isOutgoing ? edges[readIdx].GetTarget() : vertexData.m_vertex;

    RouteWeight weight = edges[readIdx].GetWeight();
    if (!AdjustForCorridor(judged, weight))
      continue;

    edges[writeIdx] = SegmentEdge(edges[readIdx].GetTarget(), weight);
    ++writeIdx;
  }
  edges.erase(edges.begin() + writeIdx, edges.end());
}

void TrackCorridorWorldGraph::GetEdgeList(astar::VertexData<JointSegment, RouteWeight> const & vertexData,
                                          Segment const & segment, bool isOutgoing, bool useAccessConditional,
                                          JointEdgeListT & edges, WeightListT & parentWeights)
{
  m_inner.GetEdgeList(vertexData, segment, isOutgoing, useAccessConditional, edges, parentWeights);
  ASSERT_EQUAL(edges.size(), parentWeights.size(), ());

  // Only the hard bound is enforced here, not the soft penalty. IndexGraphStarterJoints rewrites the
  // weights it gets back (m_savedWeight / parentWeights) so that a penalty applied to a joint edge is
  // not the penalty the search ends up using, and cannot be made symmetric across the two waves of the
  // bidirectional search -- the same reason the cross-MWM border penalty there is applied after all
  // weight calculations rather than here. Track following therefore runs in NoLeaps mode, where the
  // Segment overload above does the real work; keeping the bound means that if some other caller ever
  // does run this graph in Joints mode, the corridor still constrains the search instead of being
  // silently ignored.
  size_t writeIdx = 0;
  for (size_t readIdx = 0; readIdx < edges.size(); ++readIdx)
  {
    // The target joint is the chain that gets traversed in either direction, so judging it by its own
    // two ends is direction-safe. Both are checked so a chain that leaves the corridor mid-way is
    // dropped too.
    auto const & target = edges[readIdx].GetTarget();

    RouteWeight ignored = edges[readIdx].GetWeight();
    if (!AdjustForCorridor(target.GetSegment(true /* start */), ignored) ||
        !AdjustForCorridor(target.GetSegment(false /* start */), ignored))
    {
      continue;
    }

    edges[writeIdx] = edges[readIdx];
    parentWeights[writeIdx] = parentWeights[readIdx];
    ++writeIdx;
  }
  edges.erase(edges.begin() + writeIdx, edges.end());
  parentWeights.erase(parentWeights.begin() + writeIdx, parentWeights.end());
}
}  // namespace routing
