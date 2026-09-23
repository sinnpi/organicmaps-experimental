#include "testing/testing.hpp"

#include "map/route_places.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_with_custom_mwms.hpp"

#include "search/engine.hpp"

#include "storage/country_info_getter.hpp"

#include "indexer/categories_holder.hpp"

#include "indexer/classificator.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/feature_data.hpp"

#include "geometry/mercator.hpp"

#include <array>
#include <chrono>
#include <future>

namespace route_places_tests
{
using namespace route_places;

UNIT_TEST(RoutePlaces_Categories)
{
  classificator::Load();
  auto const & c = classif();
  feature::TypesHolder types;
  types.Add(c.GetTypeByPath({"natural", "spring"}));
  TEST(!MatchesCategory(types, Category::Water), ());
  types.Add(c.GetTypeByPath({"drinking_water", "yes"}));
  TEST(MatchesCategory(types, Category::Water), ());
  types.Add(c.GetTypeByPath({"drinking_water", "no"}));
  TEST(!MatchesCategory(types, Category::Water), ());

  for (auto const & path : {std::vector<std::string>{"natural", "spring", "drinking_water_no"},
                            {"man_made", "water_tap", "drinking_water_no"},
                            {"man_made", "water_well", "drinking_water_no"},
                            {"amenity", "water_point", "drinking_water_no"}})
  {
    types.Assign(c.GetTypeByPath({"amenity", "drinking_water"}));
    TEST(MatchesCategory(types, Category::Water), ());
    types.Add(c.GetTypeByPath(path));
    TEST(!MatchesCategory(types, Category::Water), (path));
  }
  types.Assign(c.GetTypeByPath({"amenity", "fountain"}));
  TEST(!MatchesCategory(types, Category::Water), ());
  for (auto const * shop : {"supermarket", "convenience", "bakery", "greengrocer", "deli"})
  {
    types.Assign(c.GetTypeByPath({"shop", shop}));
    TEST(MatchesCategory(types, Category::Groceries), (shop));
    TEST(!MatchesCategory(types, Category::Cafe), (shop));
    TEST(!MatchesCategory(types, Category::Water), (shop));
  }
  types.Assign(c.GetTypeByPath({"amenity", "cafe"}));
  TEST(MatchesCategory(types, Category::Cafe), ());
  TEST(!MatchesCategory(types, Category::Groceries), ());
}

UNIT_TEST(RoutePlaces_Projection)
{
  auto const a = mercator::FromLatLon(0, 0);
  auto const b = mercator::FromLatLon(0, 0.02);
  std::vector<RoutePoint> const route = {{1000, a}, {3000, b}};
  auto positions = Project(route, mercator::FromLatLon(0.001, 0.01));
  TEST_EQUAL(positions.size(), 1, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_distanceMeters, 2000.0, 0.01, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_offsetMeters,
                        mercator::DistanceOnEarth(mercator::FromLatLon(0.001, 0.01), mercator::FromLatLon(0, 0.01)),
                        0.001, ());
  TEST(Project(route, mercator::FromLatLon(0.005, 0.01)).empty(), ("Outside corridor"));
  TEST(Project(route, mercator::FromLatLon(0, -0.001)).empty(), ("Behind clipped start"));
  TEST(Project(route, mercator::FromLatLon(0, 0.021)).empty(), ("Beyond finish"));
  TEST_EQUAL(Project(route, a).size(), 1, ());
  TEST_EQUAL(Project(route, b).size(), 1, ());
  TEST(Project({}, a).empty(), ());
  TEST(Project({{1000, a}}, a).empty(), ());
  TEST(Project({{1000, a}, {1000, a}}, a).empty(), ());

  std::vector<RoutePoint> const duplicated = {{1000, a}, {1000, a}, {3000, b}, {3000, b}};
  TEST(Project(duplicated, mercator::FromLatLon(0, -0.001)).empty(), ());
  TEST(Project(duplicated, mercator::FromLatLon(0, 0.021)).empty(), ());
  TEST_EQUAL(Project(duplicated, mercator::FromLatLon(0, 0.01)).size(), 1, ());
}

UNIT_TEST(RoutePlaces_RepeatedVisits)
{
  auto const a = mercator::FromLatLon(0, 0);
  auto const b = mercator::FromLatLon(0, 0.02);
  auto const middle = mercator::FromLatLon(0, 0.01);
  // Sparse geometry must not merge the outward and return encounters.
  auto positions = Project({{0, a}, {2000, b}, {4000, a}}, middle);
  TEST_EQUAL(positions.size(), 2, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_distanceMeters, 1000.0, 0.01, ());
  TEST_ALMOST_EQUAL_ABS(positions[1].m_distanceMeters, 3000.0, 0.01, ());

  // Extra vertices inside the corridor do not create duplicate encounters.
  positions = Project({{0, a}, {1000, middle}, {2000, b}}, middle);
  TEST_EQUAL(positions.size(), 1, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_distanceMeters, 1000.0, 0.01, ());
  // Clipping past the first visit preserves the later visit and absolute distance.
  positions = Project({{2000, b}, {4000, a}}, middle);
  TEST_EQUAL(positions.size(), 1, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_distanceMeters, 3000.0, 0.01, ());
}
UNIT_TEST(RoutePlaces_ProjectionCorridor)
{
  auto const a = mercator::FromLatLon(0, 0);
  auto const b = mercator::FromLatLon(0, 0.02);
  std::vector<RoutePoint> const route = {{1000, a}, {3000, b}};
  // Half a kilometre off the way: only a search told to look that far aside reaches it.
  auto const aside = mercator::FromLatLon(0.005, 0.01);
  TEST(Project(route, aside).empty(), ());
  auto const positions = Project(route, aside, kAsideCorridorMeters);
  TEST_EQUAL(positions.size(), 1, ());
  TEST_ALMOST_EQUAL_ABS(positions[0].m_distanceMeters, 2000.0, 0.01, ());
  TEST(Project(route, mercator::FromLatLon(0.05, 0.01), kAsideCorridorMeters).empty(), ());
}

UNIT_TEST(RoutePlaces_SelectOnTheWay)
{
  // Everything in the corridor is on the way, however close together it lies.
  TEST_EQUAL(SelectOnTheWay({{1000, 10}, {1200, 400}, {40000, 0}}), (std::vector<size_t>{0, 1, 2}), ());

  // A place aside is worth a detour only where the way itself offers nothing for a whole stretch.
  TEST_EQUAL(SelectOnTheWay({{1000, 10}, {5000, 3000}}), (std::vector<size_t>{0}), ());
  TEST_EQUAL(SelectOnTheWay({{1000, 10}, {12000, 3000}}), (std::vector<size_t>{0, 1}), ());
  // Of the places aside on an empty stretch, the one closest to the way is the one taken.
  TEST_EQUAL(SelectOnTheWay({{12000, 3000}, {13000, 1000}}), (std::vector<size_t>{1}), ());
  // Further off than the wide corridor is not on the way at all.
  TEST(SelectOnTheWay({{12000, 6000}}).empty(), ());
  TEST(SelectOnTheWay({}).empty(), ());
}

class RoutePlacesSearchTest : public generator::tests_support::TestWithCustomMwms
{};

UNIT_CLASS_TEST(RoutePlacesSearchTest, RoutePlaces_OfflineCategoryQueries)
{
  using namespace generator::tests_support;
  TestPOI tap(mercator::FromLatLon(0.001, 0.005), "Tap", "en");
  tap.SetTypes({{"amenity", "drinking_water"}});
  TestPOI spring(mercator::FromLatLon(0.001, 0.006), "Potable spring", "en");
  spring.SetTypes({{"natural", "spring"}, {"drinking_water", "yes"}});
  TestPOI unknownSpring(mercator::FromLatLon(0.001, 0.007), "Unknown spring", "en");
  unknownSpring.SetTypes({{"natural", "spring"}});
  TestPOI shop(mercator::FromLatLon(0.001, 0.008), "Shop", "en");
  shop.SetTypes({{"shop", "convenience"}});
  TestPOI cafe(mercator::FromLatLon(0.001, 0.009), "Cafe", "en");
  cafe.SetTypes({{"amenity", "cafe"}});
  TestPOI distant(mercator::FromLatLon(0.01, 0.01), "Distant cafe", "en");
  distant.SetTypes({{"amenity", "cafe"}});
  BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
  {
    for (auto const * poi : {&tap, &spring, &unknownSpring, &shop, &cafe, &distant})
      builder.Add(*poi);
  });

  std::array<std::promise<std::vector<Place>>, 3> promises;
  auto info = storage::CountryInfoReader::CreateCountryInfoGetter(GetPlatform());
  search::Engine engine(m_dataSource, GetDefaultCategories(), *info, {"en", 2});
  auto handles = Search(engine, m_dataSource, {{0, mercator::FromLatLon(0, 0)}, {2200, mercator::FromLatLon(0, 0.02)}},
                        [&](Category category, std::vector<Place> places)
  { promises[static_cast<size_t>(category)].set_value(std::move(places)); });
  TEST_EQUAL(handles.size(), 3, ());
  std::array<size_t, 3> const expectedCounts = {2, 1, 1};
  for (size_t i = 0; i < promises.size(); ++i)
  {
    auto future = promises[i].get_future();
    TEST(future.wait_for(std::chrono::seconds(30)) == std::future_status::ready, (i));
    auto const places = future.get();
    TEST_EQUAL(places.size(), expectedCounts[i], (i));
    for (auto const & place : places)
      TEST_LESS(place.m_position.m_offsetMeters, kCorridorMeters, (i));
  }
}
}  // namespace route_places_tests
