#include "map/route_places.hpp"

#include "search/engine.hpp"

#include "indexer/classificator.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_data.hpp"

#include "geometry/mercator.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <optional>

namespace route_places
{
bool MatchesCategory(feature::TypesHolder const & types, Category category)
{
  auto const & c = classif();
  switch (category)
  {
  case Category::Water:
  {
    // Explicit negative tags win, including the negative subtypes used by older maps.
    static std::array const excluded = {c.GetTypeByPath({"drinking_water", "no"}),
                                        c.GetTypeByPath({"natural", "spring", "drinking_water_no"}),
                                        c.GetTypeByPath({"man_made", "water_tap", "drinking_water_no"}),
                                        c.GetTypeByPath({"man_made", "water_well", "drinking_water_no"}),
                                        c.GetTypeByPath({"amenity", "water_point", "drinking_water_no"})};
    if (std::any_of(excluded.begin(), excluded.end(), [&](uint32_t type) { return types.Has(type); }))
      return false;
    return types.Has(c.GetTypeByPath({"amenity", "drinking_water"})) ||
           types.Has(c.GetTypeByPath({"drinking_water", "yes"}));
  }
  case Category::Groceries:
    return types.Has(c.GetTypeByPath({"shop", "supermarket"})) || types.Has(c.GetTypeByPath({"shop", "convenience"})) ||
           types.Has(c.GetTypeByPath({"shop", "bakery"})) || types.Has(c.GetTypeByPath({"shop", "greengrocer"})) ||
           types.Has(c.GetTypeByPath({"shop", "deli"}));
  case Category::Cafe: return types.Has(c.GetTypeByPath({"amenity", "cafe"}));
  }
  UNREACHABLE();
}

std::vector<Position> Project(std::vector<RoutePoint> const & route, m2::PointD const & point, double corridorMeters)
{
  std::vector<Position> positions;
  std::optional<Position> best;
  bool beyondEnd = false;
  auto const flush = [&]
  {
    if (best && !beyondEnd)
      positions.push_back(*best);
    best.reset();
    beyondEnd = false;
  };

  if (route.size() < 2)
    return positions;
  size_t first = 0;
  size_t last = route.size() - 1;
  while (first < last && route[first].m_point == route[first + 1].m_point)
    ++first;
  while (last > first && route[last].m_point == route[last - 1].m_point)
    --last;

  for (size_t i = first + 1; i <= last; ++i)
  {
    auto const & a = route[i - 1];
    auto const & b = route[i];
    auto const direction = b.m_point - a.m_point;
    double const lengthSquared = direction.SquaredLength();
    if (lengthSquared == 0)
      continue;
    double const t = DotProduct(point - a.m_point, direction) / lengthSquared;
    double const clamped = std::clamp(t, 0.0, 1.0);
    auto const projection = a.m_point + direction * clamped;
    double const offset = mercator::DistanceOnEarth(point, projection);
    if (offset > corridorMeters)
    {
      flush();
      continue;
    }
    if (!best || offset < best->m_offsetMeters)
    {
      best = Position{a.m_distanceMeters + clamped * (b.m_distanceMeters - a.m_distanceMeters), offset};
      // Don't attach a place behind the clipped start (or beyond the finish) to an endpoint.
      beyondEnd = (i == first + 1 && t < 0) || (i == last && t > 1);
    }
    // A long segment can enter AND leave the corridor. Flush at its exit even if the next
    // segment returns through the same place (e.g. a sparse out-and-back track).
    if (mercator::DistanceOnEarth(point, b.m_point) > corridorMeters)
      flush();
  }
  flush();
  return positions;
}

std::vector<size_t> SelectOnTheWay(std::vector<Position> const & places)
{
  std::vector<size_t> selected;
  std::vector<size_t> aside;
  // Distances along the route of what is shown already, the corridor first: a place aside is only
  // worth a detour where the way itself offers nothing nearby.
  std::vector<double> taken;

  for (size_t i = 0; i < places.size(); ++i)
    if (places[i].m_offsetMeters <= kCorridorMeters)
    {
      selected.push_back(i);
      taken.push_back(places[i].m_distanceMeters);
    }
    else if (places[i].m_offsetMeters <= kAsideCorridorMeters)
      aside.push_back(i);

  // Closest to the way first, so that an empty stretch gets the least of a detour.
  std::stable_sort(aside.begin(), aside.end(), [&places](size_t lhs, size_t rhs)
  { return places[lhs].m_offsetMeters < places[rhs].m_offsetMeters; });

  for (size_t const i : aside)
  {
    double const distanceMeters = places[i].m_distanceMeters;
    if (std::any_of(taken.begin(), taken.end(),
                    [distanceMeters](double shown) { return std::fabs(shown - distanceMeters) < kEmptyStretchMeters; }))
      continue;

    selected.push_back(i);
    taken.push_back(distanceMeters);
  }
  return selected;
}

std::vector<std::weak_ptr<search::ProcessorHandle>> Search(search::Engine & engine, DataSource const & dataSource,
                                                           std::vector<RoutePoint> route, Callback callback)
{
  CHECK_GREATER_OR_EQUAL(route.size(), 2, ());
  m2::RectD viewport;
  for (auto const & p : route)
    viewport.Add(mercator::RectByCenterXYAndSizeInMeters(p.m_point, kCorridorMeters));

  auto geometry = std::make_shared<std::vector<RoutePoint> const>(std::move(route));
  // Stable category queries, not translated UI labels or free-text name matches.
  std::array<char const *, 3> constexpr queries = {"drinking water ", "groceries ", "cafe "};
  std::vector<std::weak_ptr<search::ProcessorHandle>> handles;
  for (size_t i = 0; i < queries.size(); ++i)
  {
    auto const category = static_cast<Category>(i);
    search::SearchParams params;
    params.m_query = queries[i];
    params.m_inputLocale = "en";
    params.m_mode = search::Mode::Viewport;
    params.m_categorialRequest = true;
    params.m_viewport = viewport;
    params.m_position = geometry->front().m_point;
    params.m_maxNumResults = search::SearchParams::kDefaultNumResultsInViewport;
    params.m_onResults = [geometry, &dataSource, callback, category](search::Results const & results)
    {
      if (!results.IsEndMarker())
        return;
      std::vector<Place> places;
      for (auto const & result : results)
      {
        if (result.GetResultType() != search::Result::Type::Feature)
          continue;
        auto const positions = Project(*geometry, result.GetFeatureCenter());
        if (positions.empty())
          continue;
        // Check all feature types, not just search's representative type (e.g. a spring may also
        // carry drinking_water=yes). Feature objects are local to this worker thread.
        bool matches = false;
        dataSource.ReadFeature([&](FeatureType & ft) { matches = MatchesCategory(feature::TypesHolder(ft), category); },
                               result.GetFeatureID());
        if (matches)
          for (auto const & position : positions)
            places.push_back({result, position});
      }
      std::sort(places.begin(), places.end(), [](Place const & a, Place const & b)
      { return a.m_position.m_distanceMeters < b.m_position.m_distanceMeters; });
      callback(category, std::move(places));
    };
    handles.push_back(engine.Search(std::move(params)));
  }
  return handles;
}
}  // namespace route_places
