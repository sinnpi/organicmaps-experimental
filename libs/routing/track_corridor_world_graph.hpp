#pragma once

#include "routing/joint_segment.hpp"
#include "routing/route_weight.hpp"
#include "routing/segment.hpp"
#include "routing/world_graph.hpp"

#include "geometry/point2d.hpp"

#include <array>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace routing
{
// Wraps another WorldGraph and biases the search toward staying close to |centerline| (e.g. an
// imported GPX/KML track), instead of purely minimizing time/distance. Used by track-following
// navigation so a single ordinary point-to-point route (no via-points) is pulled toward the track:
// edges near the free radius are unaffected, edges further away get a soft time penalty proportional
// to their distance from the corridor, edges standing in for more track than their own length pay
// for the stretch they skip, and edges beyond the (progressively widenable) outer radius are dropped
// so a single noisy/offset track point can't blow up the search space.
// Both penalties scale the edge's own weight, so the caller prices roads by distance rather than by
// the profile's road-class preference (see IndexRouter::CalculateTrackFollowingRoute): otherwise the
// preference is denominated in the same units as the penalties and outbids them on shallow
// deviations, which are barely shorter than the track and so are charged the least for skipping it.
class TrackCorridorWorldGraph final : public WorldGraph
{
public:
  TrackCorridorWorldGraph(WorldGraph & inner, std::vector<m2::PointD> centerline);

  // Restrict matching to the current ordered leg, dropping cached penalties from the previous leg.
  void SetCenterline(std::vector<m2::PointD> centerline);

  // Turn generation needs the actual road junctions, not the last leg's filtered search graph.
  void ClearCorridor() { m_centerline.clear(); }

  // Widens the hard search-space bound. Call again and retry the route search if the previous
  // attempt failed to find a path (mirrors the progressive radius widening used for start/finish
  // snapping in IndexRouter::PointsOnEdgesSnapping::FindBestSegments).
  void SetMaxCorridorRadiusM(double radiusM) { m_maxCorridorRadiusM = radiusM; }

  // WorldGraph overrides:
  // @{
  using WorldGraph::GetEdgeList;

  void GetEdgeList(astar::VertexData<Segment, RouteWeight> const & vertexData, bool isOutgoing, bool useRoutingOptions,
                   bool useAccessConditional, SegmentEdgeListT & edges) override;
  void GetEdgeList(astar::VertexData<JointSegment, RouteWeight> const & vertexData, Segment const & segment,
                   bool isOutgoing, bool useAccessConditional, JointEdgeListT & edges,
                   WeightListT & parentWeights) override;

  bool CheckLength(RouteWeight const & weight, double startToFinishDistanceM) const override
  {
    return m_inner.CheckLength(weight, startToFinishDistanceM);
  }

  LatLonWithAltitude const & GetJunction(Segment const & segment, bool front) override
  {
    return m_inner.GetJunction(segment, front);
  }
  ms::LatLon const & GetPoint(Segment const & segment, bool front) override { return m_inner.GetPoint(segment, front); }
  bool IsOneWay(NumMwmId mwmId, uint32_t featureId) override { return m_inner.IsOneWay(mwmId, featureId); }
  bool IsPassThroughAllowed(NumMwmId mwmId, uint32_t featureId) override
  {
    return m_inner.IsPassThroughAllowed(mwmId, featureId);
  }
  void ClearCachedGraphs() override { m_inner.ClearCachedGraphs(); }
  void SetMode(WorldGraphMode mode) override { m_inner.SetMode(mode); }
  WorldGraphMode GetMode() const override { return m_inner.GetMode(); }

  RouteWeight HeuristicCostEstimate(ms::LatLon const & from, ms::LatLon const & to) override
  {
    return m_inner.HeuristicCostEstimate(from, to);
  }
  RouteWeight CalcSegmentWeight(Segment const & segment, EdgeEstimator::Purpose purpose) override
  {
    return m_inner.CalcSegmentWeight(segment, purpose);
  }
  RouteWeight CalcLeapWeight(ms::LatLon const & from, ms::LatLon const & to, NumMwmId mwmId) const override
  {
    return m_inner.CalcLeapWeight(from, to, mwmId);
  }
  RouteWeight CalcOffroadWeight(ms::LatLon const & from, ms::LatLon const & to,
                                EdgeEstimator::Purpose purpose) const override
  {
    return m_inner.CalcOffroadWeight(from, to, purpose);
  }
  double CalculateETA(Segment const & from, Segment const & to, time_t arrivalTime) override
  {
    return m_inner.CalculateETA(from, to, arrivalTime);
  }
  double CalculateETAWithoutPenalty(Segment const & segment) override
  {
    return m_inner.CalculateETAWithoutPenalty(segment);
  }

  void ForEachTransition(NumMwmId numMwmId, bool isEnter, TransitionFnT const & fn) override
  {
    m_inner.ForEachTransition(numMwmId, isEnter, fn);
  }

  bool IsRoutingOptionsGood(Segment const & segment) override { return m_inner.IsRoutingOptionsGood(segment); }
  RoutingOptions GetRoutingOptions(Segment const & segment) override { return m_inner.GetRoutingOptions(segment); }
  void SetRoutingOptions(RoutingOptions routingOptions) override { m_inner.SetRoutingOptions(routingOptions); }

  void SetAStarParents(bool forward, Parents<Segment> & parents) override { m_inner.SetAStarParents(forward, parents); }
  void SetAStarParents(bool forward, Parents<JointSegment> & parents) override
  {
    m_inner.SetAStarParents(forward, parents);
  }
  void DropAStarParents() override { m_inner.DropAStarParents(); }

  bool AreWavesConnectible(Parents<Segment> & forwardParents, Segment const & commonVertex,
                           Parents<Segment> & backwardParents) override
  {
    return m_inner.AreWavesConnectible(forwardParents, commonVertex, backwardParents);
  }
  bool AreWavesConnectible(Parents<JointSegment> & forwardParents, JointSegment const & commonVertex,
                           Parents<JointSegment> & backwardParents,
                           FakeConverterT const & fakeFeatureConverter) override
  {
    return m_inner.AreWavesConnectible(forwardParents, commonVertex, backwardParents, fakeFeatureConverter);
  }

  std::unique_ptr<TransitInfo> GetTransitInfo(Segment const & segment) override
  {
    return m_inner.GetTransitInfo(segment);
  }
  std::vector<RouteSegment::SpeedCamera> GetSpeedCamInfo(Segment const & segment) override
  {
    return m_inner.GetSpeedCamInfo(segment);
  }
  Maxspeed GetSpeedLimit(Segment const & segment) override { return m_inner.GetSpeedLimit(segment); }

  IndexGraph & GetIndexGraph(NumMwmId numMwmId) override { return m_inner.GetIndexGraph(numMwmId); }
  CrossMwmGraph & GetCrossMwmGraph() override { return m_inner.GetCrossMwmGraph(); }
  void GetTwinsInner(Segment const & segment, bool isOutgoing, TwinSegmentsListT & twins) override
  {
    m_inner.GetTwinsInner(segment, isOutgoing, twins);
  }
  RouteWeight GetCrossBorderPenalty(NumMwmId mwmId1, NumMwmId mwmId2) override
  {
    return m_inner.GetCrossBorderPenalty(mwmId1, mwmId2);
  }
  // @}

private:
  // Where a point falls on the centerline: how far off it lies, and how far along the track the
  // closest point on it is.
  struct Projection
  {
    double m_distanceM = 0.0;
    double m_arcM = 0.0;
  };

  // What the corridor makes of a segment, cached because both are pure functions of its geometry.
  // |m_factor| is the fraction of its own weight the segment pays on top for straying off the track
  // and for the track it skips; |m_distanceM| is what the hard bound is applied to, kept separate
  // because that bound widens between attempts while the geometry does not change.
  struct Penalty
  {
    double m_distanceM = 0.0;
    double m_factor = 0.0;
  };

  // Scales |weight| up by the penalty |judged| has earned. |judged| must be the segment the edge's
  // own weight was computed from, so that both A* waves price it alike. Returns false (dropping the
  // edge) if it lies beyond the current hard bound.
  // A segment's middle and its two ends, and where each of them falls on the centerline.
  using PointsT = std::array<m2::PointD, 3>;
  using ProjectionsT = std::array<Projection, 3>;

  bool AdjustForCorridor(Segment const & judged, RouteWeight & weight) const;
  Penalty CalcPenalty(Segment const & judged) const;
  ProjectionsT ProjectOnCorridor(PointsT const & points) const;

  WorldGraph & m_inner;
  std::vector<m2::PointD> m_centerline;
  // Distance along the centerline to each of its points, so a projection can be turned into a
  // position along the track in constant time.
  std::vector<double> m_arcLengthM;
  double m_maxCorridorRadiusM;
  mutable std::unordered_map<Segment, Penalty> m_penaltyCache;
};
}  // namespace routing
