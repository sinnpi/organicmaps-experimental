#include "testing/testing.hpp"

#include "routing/routing_tests/index_graph_tools.hpp"

#include "routing/track_corridor_world_graph.hpp"

#include "traffic/traffic_cache.hpp"

#include "routing/base/astar_vertex_data.hpp"

#include "routing/edge_estimator.hpp"
#include "routing/fake_ending.hpp"
#include "routing/index_graph_starter.hpp"
#include "routing/route_weight.hpp"
#include "routing/segment.hpp"
#include "routing/world_graph.hpp"

#include "indexer/classificator_loader.hpp"

#include "geometry/mercator.hpp"
#include "geometry/point2d.hpp"

#include <memory>
#include <vector>

namespace track_corridor_world_graph_tests
{
using namespace routing;
using namespace routing_test;

namespace
{
void EnsureClassificatorLoaded()
{
  static bool const loaded = []
  {
    classificator::Load();
    return true;
  }();
  (void)loaded;
}
}  // namespace

// Builds a small rectangle: a "near" (on-track) road directly connecting left and right, and a
// parallel "far" road (offset ~55m) reachable only via two connector roads at either end. The far
// road is given a higher speed so a plain shortest-time search prefers it over the on-track road.
//
//   left        right
//    *-----far-----*     (feature 1, faster)
//    |              |
//   (feature 2)  (feature 3)
//    |              |
//    *-----near-----*    (feature 0, on the track's centerline)
//
std::unique_ptr<SingleVehicleWorldGraph> BuildParallelRoadsGraph(m2::PointD const & nearLeft,
                                                                 m2::PointD const & nearRight,
                                                                 m2::PointD const & farLeft,
                                                                 m2::PointD const & farRight)
{
  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points({nearLeft, nearRight}));
  loader->AddRoad(1, false, 12.0 /* speed, faster than the near road */, RoadGeometry::Points({farLeft, farRight}));
  loader->AddRoad(2, false, 5.0, RoadGeometry::Points({nearLeft, farLeft}));
  loader->AddRoad(3, false, 5.0, RoadGeometry::Points({nearRight, farRight}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  return BuildWorldGraph(std::move(loader), estimator,
                         {MakeJoint({{0, 0}, {2, 0}}), MakeJoint({{1, 0}, {2, 1}}), MakeJoint({{0, 1}, {3, 0}}),
                          MakeJoint({{1, 1}, {3, 1}})});
}

UNIT_TEST(TrackCorridorWorldGraph_PlainGraphPrefersFasterParallelRoad)
{
  EnsureClassificatorLoaded();

  auto const nearLeft = mercator::FromLatLon(0.0, 0.0);
  auto const nearRight = mercator::FromLatLon(0.0, 0.003);
  auto const farLeft = mercator::FromLatLon(0.0005, 0.0);
  auto const farRight = mercator::FromLatLon(0.0005, 0.003);

  auto graph = BuildParallelRoadsGraph(nearLeft, nearRight, farLeft, farRight);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, nearLeft, *graph);
  auto const finish = MakeFakeEnding(0, 0, nearRight, *graph);
  auto starter = MakeStarter(start, finish, *graph);

  // Without any corridor bias, the faster parallel road (reached via the connectors) wins on pure
  // travel time even though it never touches the on-track road at all.
  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {nearLeft, farLeft, farRight, nearRight});
}

UNIT_TEST(TrackCorridorWorldGraph_BiasesTowardTrackOverFasterParallelRoad)
{
  EnsureClassificatorLoaded();

  auto const nearLeft = mercator::FromLatLon(0.0, 0.0);
  auto const nearRight = mercator::FromLatLon(0.0, 0.003);
  auto const farLeft = mercator::FromLatLon(0.0005, 0.0);
  auto const farRight = mercator::FromLatLon(0.0005, 0.003);

  auto graph = BuildParallelRoadsGraph(nearLeft, nearRight, farLeft, farRight);

  std::vector<m2::PointD> const centerline = {nearLeft, nearRight};
  TrackCorridorWorldGraph corridorGraph(*graph, centerline);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, nearLeft, corridorGraph);
  auto const finish = MakeFakeEnding(0, 0, nearRight, corridorGraph);
  auto starter = MakeStarter(start, finish, corridorGraph);

  // With the corridor centered on the near road, the one-time penalty for straying ~55m away
  // outweighs the faster parallel road's time advantage: the search now stays on the track.
  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {nearLeft, nearRight});
}

// IndexGraph::CalculateEdgeWeight prices a directed edge u->v as the weight of v no matter which
// direction it is discovered from, so the forward and backward A* waves agree on what it costs. The
// corridor penalty must preserve that: keying it off the edge's target instead would make the
// backward wave price the edge by u, and the two waves would meet on a path neither of them costed.
// A road that cuts a bend the track goes round starts and ends on the track, so judging a segment by
// one of its ends prices the whole shortcut as if it were on the track. The middle is what tells the
// two apart.
//
//        B                A -> B -> C is the track (features 0 and 1);
//       / \               A ----> C  is the shortcut (feature 2), shorter and just as fast.
//      A---C
//
std::unique_ptr<SingleVehicleWorldGraph> BuildBendWithShortcutGraph(m2::PointD const & a, m2::PointD const & b,
                                                                    m2::PointD const & c)
{
  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points({a, b}));
  loader->AddRoad(1, false, 5.0, RoadGeometry::Points({b, c}));
  loader->AddRoad(2, false, 5.0, RoadGeometry::Points({a, c}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  return BuildWorldGraph(std::move(loader), estimator,
                         {MakeJoint({{0, 0}, {2, 0}}), MakeJoint({{0, 1}, {1, 0}}), MakeJoint({{1, 1}, {2, 1}})});
}

UNIT_TEST(TrackCorridorWorldGraph_PlainGraphCutsTheBend)
{
  EnsureClassificatorLoaded();

  auto const a = mercator::FromLatLon(0.0, 0.0);
  auto const b = mercator::FromLatLon(0.0005, 0.0015);
  auto const c = mercator::FromLatLon(0.0, 0.003);

  auto graph = BuildBendWithShortcutGraph(a, b, c);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, a, *graph);
  auto const finish = MakeFakeEnding(1, 0, c, *graph);
  auto starter = MakeStarter(start, finish, *graph);

  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {a, c});
}

UNIT_TEST(TrackCorridorWorldGraph_StaysOnTheBendItsTrackGoesRound)
{
  EnsureClassificatorLoaded();

  auto const a = mercator::FromLatLon(0.0, 0.0);
  auto const b = mercator::FromLatLon(0.0005, 0.0015);
  auto const c = mercator::FromLatLon(0.0, 0.003);

  auto graph = BuildBendWithShortcutGraph(a, b, c);

  std::vector<m2::PointD> const centerline = {a, b, c};
  TrackCorridorWorldGraph corridorGraph(*graph, centerline);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, a, corridorGraph);
  auto const finish = MakeFakeEnding(1, 0, c, corridorGraph);
  auto starter = MakeStarter(start, finish, corridorGraph);

  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {a, b, c});
}

// The profile's road-class preference and the corridor's penalties are both denominated in the
// edge's own weight, so a shortcut on a road the profile likes better arrives already discounted. A
// shallow bend is where that bites hardest: it is barely shorter than the track, so what it is
// charged for skipping it is small, while the discount is not.
//
//         M              P0 -> A -> M -> B -> P4 is the track (feature 0), a 20m bend over a 300m
//        / \             chord; A --------> B is the shortcut (feature 1), 300m of a road the
//   P0--A---B--P4        profile prices three times cheaper per metre.
//
std::vector<m2::PointD> MakeShallowBend()
{
  // A lead-in and a lead-out either side, so the fake endings do not cover the segments the two
  // routes actually differ on.
  return {mercator::FromLatLon(0.0, -0.00027), mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.00018, 0.00135),
          mercator::FromLatLon(0.0, 0.0027), mercator::FromLatLon(0.0, 0.00297)};
}

std::unique_ptr<SingleVehicleWorldGraph> BuildShallowBendGraph(std::vector<m2::PointD> const & track,
                                                               EdgeEstimator::Strategy strategy)
{
  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points(track));
  loader->AddRoad(1, false, 15.0 /* speed, three times cheaper per metre */,
                  RoadGeometry::Points({track[1], track[3]}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  estimator->SetStrategy(strategy);
  return BuildWorldGraph(std::move(loader), estimator, {MakeJoint({{0, 1}, {1, 0}}), MakeJoint({{0, 3}, {1, 1}})});
}

std::unique_ptr<IndexGraphStarter> MakeShallowBendStarter(std::vector<m2::PointD> const & track, WorldGraph & graph)
{
  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, track.front(), graph);
  auto const finish = MakeFakeEnding(0, 3 /* segmentIdx */, track.back(), graph);
  return MakeStarter(start, finish, graph);
}

UNIT_TEST(TrackCorridorWorldGraph_LosesAShallowBendPricedByRoadClass)
{
  EnsureClassificatorLoaded();

  auto const track = MakeShallowBend();
  auto graph = BuildShallowBendGraph(track, EdgeEstimator::Strategy::Normal);

  TrackCorridorWorldGraph corridorGraph(*graph, track);
  auto starter = MakeShallowBendStarter(track, corridorGraph);

  // The corridor alone cannot hold this: the shortcut never leaves the free radius and skips only
  // ~3m of track over 300m, so what it pays is nowhere near what the profile takes off it.
  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {track[0], track[1], track[3], track[4]});
}

UNIT_TEST(TrackCorridorWorldGraph_HoldsAShallowBendWhenRoadsArePricedByDistance)
{
  EnsureClassificatorLoaded();

  auto const track = MakeShallowBend();
  // What IndexRouter::CalculateTrackFollowingRoute does for the whole search. With every road at the
  // same price per metre the road class stops competing, and a shortcut is left paying for the
  // stretch of track it replaces on top of the distance it saved.
  auto graph = BuildShallowBendGraph(track, EdgeEstimator::Strategy::Shortest);

  TrackCorridorWorldGraph corridorGraph(*graph, track);
  auto starter = MakeShallowBendStarter(track, corridorGraph);

  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, track);
}

// Distance from the centerline says nothing about how much track a road stands in for. A bypass of
// a stretch the track switchbacks up runs alongside it the whole way -- never leaving the free radius
// -- yet replaces several times its own length of track. Only the ground it covers along the track
// tells it apart from the track itself.
//
//      /\  /\  /\        A -> ... -> D zigzags (feature 0), 12 legs of 56m for 300m of progress;
//     /  \/  \/  \       A --------> D is the bypass (feature 1), one straight 300m segment whose
//    A============D      middle sits right on the track and whose every point is within ~22m of it.
//
std::unique_ptr<SingleVehicleWorldGraph> BuildSwitchbackWithBypassGraph(std::vector<m2::PointD> const & zigzag)
{
  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points(zigzag));
  loader->AddRoad(1, false, 5.0, RoadGeometry::Points({zigzag.front(), zigzag.back()}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  return BuildWorldGraph(
      std::move(loader), estimator,
      {MakeJoint({{0, 0}, {1, 0}}), MakeJoint({{0, static_cast<uint32_t>(zigzag.size() - 1)}, {1, 1}})});
}

std::vector<m2::PointD> MakeZigzag()
{
  // 25m east per leg, alternating 50m north and back: far enough off the bypass to be worth cutting,
  // close enough that the bypass never strays outside the corridor's free radius.
  std::vector<m2::PointD> zigzag;
  for (size_t i = 0; i <= 12; ++i)
    zigzag.push_back(mercator::FromLatLon(i % 2 == 0 ? 0.0 : 0.00045, i * 0.000225));
  return zigzag;
}

UNIT_TEST(TrackCorridorWorldGraph_PlainGraphBypassesSwitchbacks)
{
  EnsureClassificatorLoaded();

  auto const zigzag = MakeZigzag();
  auto graph = BuildSwitchbackWithBypassGraph(zigzag);

  // Both endings sit on the track, so the bypass is a real edge the corridor gets to judge -- a
  // fake ending would cover it with a starter-priced edge the corridor never sees.
  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, zigzag.front(), *graph);
  auto const finish = MakeFakeEnding(0, static_cast<uint32_t>(zigzag.size() - 2), zigzag.back(), *graph);
  auto starter = MakeStarter(start, finish, *graph);

  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {zigzag.front(), zigzag.back()});
}

UNIT_TEST(TrackCorridorWorldGraph_TakesTheSwitchbacksItsTrackTakes)
{
  EnsureClassificatorLoaded();

  auto const zigzag = MakeZigzag();
  auto graph = BuildSwitchbackWithBypassGraph(zigzag);

  TrackCorridorWorldGraph corridorGraph(*graph, zigzag);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, zigzag.front(), corridorGraph);
  auto const finish = MakeFakeEnding(0, static_cast<uint32_t>(zigzag.size() - 2), zigzag.back(), corridorGraph);
  auto starter = MakeStarter(start, finish, corridorGraph);

  TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, zigzag);
}

UNIT_TEST(TrackCorridorWorldGraph_PricesAnEdgeAlikeInBothDirections)
{
  EnsureClassificatorLoaded();

  auto const left = mercator::FromLatLon(0.0, 0.0);
  auto const mid = mercator::FromLatLon(0.0, 0.0015);
  auto const right = mercator::FromLatLon(0.0, 0.003);

  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points({left, mid, right}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  auto graph = BuildWorldGraph(std::move(loader), estimator, {} /* joints */);

  // Slanted away from the road, so the two ends of the road are at clearly different distances from
  // the corridor -- both outside the free radius and inside the hard bound, i.e. both penalized, but
  // by different amounts. A direction-dependent penalty therefore shows up as a weight mismatch.
  std::vector<m2::PointD> const centerline = {mercator::FromLatLon(0.0, 0.0), mercator::FromLatLon(0.0018, 0.003)};
  TrackCorridorWorldGraph corridorGraph(*graph, centerline);

  Segment const u(kTestNumMwmId, 0 /* featureId */, 0 /* segmentIdx */, true /* forward */);
  Segment const v(kTestNumMwmId, 0 /* featureId */, 1 /* segmentIdx */, true /* forward */);

  auto const weightOf = [&corridorGraph](Segment const & from, Segment const & to, bool isOutgoing)
  {
    WorldGraph::SegmentEdgeListT edges;
    corridorGraph.GetEdgeList(astar::VertexData<Segment, RouteWeight>(from, RouteWeight(0.0)), isOutgoing,
                              true /* useRoutingOptions */, false /* useAccessConditional */, edges);
    for (auto const & edge : edges)
      if (edge.GetTarget() == to)
        return edge.GetWeight();
    TEST(false, ("No edge between", from, "and", to));
    return RouteWeight();
  };

  auto const forward = weightOf(u, v, true /* isOutgoing */);
  auto const backward = weightOf(v, u, false /* isOutgoing */);
  TEST_EQUAL(forward, backward, ("The two A* waves must agree on what traversing u->v costs."));

  // Guard against the assertion above passing simply because nothing was penalized at all.
  auto const plainForward = [&]
  {
    WorldGraph::SegmentEdgeListT edges;
    graph->GetEdgeList(astar::VertexData<Segment, RouteWeight>(u, RouteWeight(0.0)), true /* isOutgoing */,
                       true /* useRoutingOptions */, false /* useAccessConditional */, edges);
    TEST_EQUAL(edges.size(), 1, ());
    return edges[0].GetWeight();
  }();
  TEST(plainForward < forward, ("The corridor should have made this off-track edge more expensive."));
}

UNIT_TEST(TrackCorridorWorldGraph_DropsEdgesBeyondHardBoundAndRecoversAfterWidening)
{
  EnsureClassificatorLoaded();

  auto const left = mercator::FromLatLon(0.0, 0.0);
  auto const mid = mercator::FromLatLon(0.0, 0.0015);
  auto const right = mercator::FromLatLon(0.0, 0.003);

  // Three points (two segments) so the left-to-right route must actually traverse a real interior
  // vertex via WorldGraph::GetEdgeList, instead of collapsing into a single fake start-to-finish edge.
  auto loader = std::make_unique<TestGeometryLoader>();
  loader->AddRoad(0 /* featureId */, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points({left, mid, right}));

  traffic::TrafficCache const trafficCache;
  auto estimator = CreateEstimatorForCar(trafficCache);
  auto graph = BuildWorldGraph(std::move(loader), estimator, {} /* joints */);

  // ~2.2km away from the only road: beyond the default hard bound, but within a widened one.
  std::vector<m2::PointD> const centerline = {mercator::FromLatLon(0.02, 0.0), mercator::FromLatLon(0.02, 0.003)};
  TrackCorridorWorldGraph corridorGraph(*graph, centerline);

  auto const start = MakeFakeEnding(0 /* featureId */, 0 /* segmentIdx */, left, corridorGraph);
  auto const finish = MakeFakeEnding(0, 1 /* segmentIdx */, right, corridorGraph);

  {
    auto starter = MakeStarter(start, finish, corridorGraph);
    TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::NoPath, {});
  }

  corridorGraph.SetMaxCorridorRadiusM(5000.0);
  {
    auto starter = MakeStarter(start, finish, corridorGraph);
    TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, {left, mid, right});
  }
}
}  // namespace track_corridor_world_graph_tests
