#pragma once

#include "search/result.hpp"

#include "geometry/point2d.hpp"

#include <cstddef>
#include <functional>
#include <memory>
#include <vector>

class DataSource;
namespace search
{
class Engine;
class ProcessorHandle;
}  // namespace search
namespace feature
{
class TypesHolder;
}

namespace route_places
{
// Keep in sync with sdk.routing.RoutePlace.
enum class Category
{
  Water,
  Groceries,
  Cafe
};

double constexpr kCorridorMeters = 500.0;
double constexpr kMaxAheadMeters = 50000.0;
// A place just ridden past is still worth offering -- turning back a few minutes' ride for water is
// normal -- but anything further behind is not on the way any more.
double constexpr kMaxBehindMeters = 1000.0;

// Where the way has nothing in the corridor for a whole stretch of this length, the places aside
// are taken instead, the closest to the way first, but no further off than the wide corridor: on an
// empty road a spring a couple of kilometres away is worth knowing about, on a busy one it only
// gets in the way.
double constexpr kEmptyStretchMeters = 10000.0;
double constexpr kAsideCorridorMeters = 5000.0;

struct RoutePoint
{
  double m_distanceMeters;
  m2::PointD m_point;
};

struct Position
{
  double m_distanceMeters;
  // Straight-line distance to the route, NOT the length of a navigable detour.
  double m_offsetMeters;
};

struct Place
{
  search::Result m_result;
  Position m_position;
};

bool MatchesCategory(feature::TypesHolder const & types, Category category);
// One position per visit to the corridor, so loops and out-and-back rides can show a place again.
std::vector<Position> Project(std::vector<RoutePoint> const & route, m2::PointD const & point,
                              double corridorMeters = kCorridorMeters);

// Which of |places| -- each the projection of a candidate onto the route, in the order the
// candidates were given -- are worth showing. Returns indices into |places|.
std::vector<size_t> SelectOnTheWay(std::vector<Position> const & places);

using Callback = std::function<void(Category, std::vector<Place>)>;
// Independent requests: these do not change the user's search session or its map marks.
// The route is an immutable, clipped snapshot with absolute distances from route start.
// Calls back on search worker threads, once per category; results may be incomplete.
std::vector<std::weak_ptr<search::ProcessorHandle>> Search(search::Engine & engine, DataSource const & dataSource,
                                                           std::vector<RoutePoint> route, Callback callback);
}  // namespace route_places
