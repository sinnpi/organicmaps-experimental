#include "testing/testing.hpp"

#include "routing/routing_tests/index_graph_tools.hpp"
#include "routing/track_checkpoints.hpp"
#include "routing/track_corridor_world_graph.hpp"

#include "geometry/mercator.hpp"
#include "indexer/classificator_loader.hpp"
#include "traffic/traffic_cache.hpp"

#include <algorithm>
#include <map>
#include <vector>

namespace track_checkpoints_tests
{
using namespace routing;
using namespace routing_test;

namespace
{
m2::PointD Point(double x, double y)
{
  return mercator::FromLatLon(y * 0.01, x * 0.01);
}

// Each distinct piece of road is bidirectional. Repeated visits use the same feature, as they do
// in OSM. Match every leg and check the full geometry, not just distance or a set of visited roads.
void TestTrack(std::vector<m2::PointD> track)
{
  classificator::Load();
  for (bool reverse : {false, true})
  {
    if (reverse)
      std::reverse(track.begin(), track.end());
    auto loader = std::make_unique<TestGeometryLoader>();
    std::map<std::pair<m2::PointD, m2::PointD>, uint32_t> roads;
    std::map<m2::PointD, Joint> joints;
    std::vector<uint32_t> features;
    for (size_t i = 1; i < track.size(); ++i)
    {
      auto const [a, b] = std::minmax(track[i - 1], track[i]);
      auto const [it, inserted] = roads.emplace(std::make_pair(a, b), roads.size());
      auto const id = it->second;
      if (inserted)
      {
        loader->AddRoad(id, false /* oneWay */, 5.0 /* speed */, RoadGeometry::Points({a, b}));
        joints[a].AddPoint({id, 0});
        joints[b].AddPoint({id, 1});
      }
      features.push_back(id);
    }
    std::vector<Joint> graphJoints;
    for (auto const & [point, joint] : joints)
      graphJoints.push_back(joint);
    traffic::TrafficCache const traffic;
    auto graph = BuildWorldGraph(std::move(loader), CreateEstimatorForCar(traffic), graphJoints);
    TrackCorridorWorldGraph corridor(*graph, track);
    auto const indices = GetTrackLegIndices(track);
    TEST_EQUAL(indices.front(), 0, ());
    TEST_EQUAL(indices.back(), track.size() - 1, ());
    auto const checkpoints = MakeTrackCheckpoints(track, track.front());
    TEST_EQUAL(checkpoints.size(), indices.size(), ());
    for (size_t i = 1; i < indices.size(); ++i)
    {
      auto const from = indices[i - 1];
      auto const to = indices[i];
      TEST_GREATER(to, from, ());
      TEST_EQUAL(checkpoints[i], track[to], ());
      std::vector<m2::PointD> const leg(track.begin() + from, track.begin() + to + 1);
      corridor.SetCenterline(leg);
      auto const start = MakeFakeEnding(features[from], 0, track[from], corridor);
      auto const finish = MakeFakeEnding(features[to - 1], 0, track[to], corridor);
      auto starter = MakeStarter(start, finish, corridor);
      TestRouteGeometry(*starter, AlgorithmForWorldGraph::Result::OK, leg);
    }
  }
}
}  // namespace

UNIT_TEST(TrackCheckpoints_ClosedLoop)
{
  TestTrack({Point(0, 0), Point(1, 0), Point(1, 1), Point(0, 1), Point(0, 0)});
}

UNIT_TEST(TrackCheckpoints_ExcursionOnAnOtherwiseOpenTrack)
{
  // A side loop returns to B. An endpoint-only or whole-track corridor can skip B-C-D-B entirely.
  TestTrack({Point(0, 0), Point(1, 0), Point(1, 1), Point(2, 1), Point(1, 0), Point(2, 0)});
}

UNIT_TEST(TrackCheckpoints_RepeatedRoadWithOutAndBack)
{
  TestTrack({Point(0, 0), Point(1, 0), Point(1, 1), Point(1, 0), Point(2, 0)});
}

UNIT_TEST(TrackCheckpoints_NestedLoop)
{
  // A smaller excursion in the middle of a larger loop, analogous to the Osnabrück GPX.
  TestTrack({Point(0, 0), Point(2, 0), Point(2, 1), Point(3, 1), Point(3, 2), Point(2, 1), Point(2, 2), Point(0, 2),
             Point(0, 0)});
}

UNIT_TEST(TrackCheckpoints_TwoLapsKeepBothVisits)
{
  TestTrack({Point(0, 0), Point(1, 0), Point(1, 1), Point(0, 1), Point(0, 0), Point(1, 0), Point(1, 1), Point(0, 1),
             Point(0, 0)});
}

UNIT_TEST(TrackCheckpoints_CrossingInsideSegments)
{
  // Neither segment has a recorded point at the crossing (1, 1).
  std::vector<m2::PointD> const track = {Point(0, 0), Point(2, 2), Point(0, 2), Point(2, 0), Point(3, 0)};
  auto const indices = GetTrackLegIndices(track);
  TEST_GREATER(indices.size(), 2, ());
  TEST_EQUAL(indices.front(), 0, ());
  TEST_EQUAL(indices.back(), track.size() - 1, ());
}

UNIT_TEST(TrackCheckpoints_StraightTrackAndRecordingNoiseNeedNoInternalPoints)
{
  std::vector<m2::PointD> const straight = {Point(0, 0), Point(1, 0), Point(2, 0)};
  TEST_EQUAL(GetTrackLegIndices(straight), (std::vector<size_t>{0, 2}), ());
  std::vector<m2::PointD> const noise = {Point(0, 0), Point(0.001, 0), Point(0, 0), Point(1, 0)};
  TEST_EQUAL(GetTrackLegIndices(noise), (std::vector<size_t>{0, 3}), ());
}
}  // namespace track_checkpoints_tests
