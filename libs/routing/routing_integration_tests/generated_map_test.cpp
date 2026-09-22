#include "routing/routing_integration_tests/generated_map_test.hpp"

#include "routing/route.hpp"
#include "routing/segment.hpp"

#include "generator/routing_helpers.hpp"

#include "platform/local_country_file.hpp"

#include "base/geo_object_id.hpp"

namespace integration
{
using platform::LocalCountryFile;

GeneratedMapTest::GeneratedMapTest(std::string const & osmFile, std::string const & mwmName,
                                   routing::VehicleType vehicleType)
  : m_mwmName(mwmName)
{
  m_generator.BuildFB(osmFile, mwmName);
  m_generator.BuildFeatures(mwmName);
  m_generator.BuildRouting(mwmName, mwmName);
  m_generator.BuildCrossMwm(mwmName, mwmName);

  m_mwmPath = m_generator.GetMwmPath(mwmName);
  m_components = std::make_unique<VehicleRouterComponents>(
      std::vector<LocalCountryFile>{LocalCountryFile::MakeTemporary(m_mwmPath)}, vehicleType);
}

std::set<uint64_t> GeneratedMapTest::GetUsedOsmWays(routing::Route const & route)
{
  std::set<uint64_t> ways;
  for (auto const & [way, distanceM] : GetOsmWayDistancesMeters(route))
    ways.insert(way);
  return ways;
}

std::map<uint64_t, double> GeneratedMapTest::GetOsmWayDistancesMeters(routing::Route const & route)
{
  // ParseWaysFeatureIdToOsmIdMapping fills only way features, so GetSerialId() is the OSM way id.
  routing::FeatureIdToOsmId const fid2osm = m_generator.LoadFID2OsmID(m_mwmName);

  std::map<uint64_t, double> distances;
  double previousM = 0.0;
  for (auto const & routeSegment : route.GetRouteSegments())
  {
    double const distanceM = routeSegment.GetDistFromBeginningMeters() - previousM;
    previousM = routeSegment.GetDistFromBeginningMeters();
    auto const & segment = routeSegment.GetSegment();
    if (!segment.IsRealSegment())
      continue;

    auto const it = fid2osm.find(segment.GetFeatureId());
    if (it != fid2osm.end())
      distances[it->second.GetSerialId()] += distanceM;
  }
  return distances;
}
}  // namespace integration
