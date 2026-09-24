#include "testing/testing.hpp"

#include "map/framework.hpp"

#include "drape_frontend/visual_params.hpp"

#include "geometry/any_rect2d.hpp"
#include "geometry/mercator.hpp"

#include "base/math.hpp"

namespace framework_viewport_tests
{
namespace
{
class TestFramework final : public Framework
{
public:
  TestFramework() : Framework({}, false /* loadMaps */) {}

  using Framework::FindTracksInTapPosition;

  void SetViewport(ScreenBase const & screen, m2::RectD const & visibleViewport)
  {
    m_currentModelView = screen;
    m_visibleViewport = visibleViewport;
  }
};
}  // namespace

UNIT_TEST(Framework_GetViewportCenter)
{
  m2::RectD const pixelRect(0.0, 0.0, 1000.0, 1000.0);
  m2::RectD const visibleViewport(0.0, 0.0, 1000.0, 700.0);
  m2::PointD const target(185.0, 5.0);
  double constexpr kEps = 1e-7;

  TestFramework framework;
  for (bool const perspective : {false, true})
  {
    ScreenBase screen;
    screen.SetFromRects(m2::AnyRectD(m2::RectD(170.0, -10.0, 190.0, 10.0)), pixelRect);
    if (perspective)
      screen.ApplyPerspective(math::pi4, math::pi4, math::pi / 3.0);
    screen.MatchGandP3d(target, visibleViewport.Center());

    framework.SetViewport(screen, visibleViewport);
    auto const actual = framework.GetViewportCenter();
    TEST_ALMOST_EQUAL_ABS(actual.x, mercator::WrapX(target.x), kEps, (perspective));
    TEST_ALMOST_EQUAL_ABS(actual.y, target.y, kEps, (perspective));
  }
}
UNIT_TEST(Framework_LowPowerSelectsVisibleTracks)
{
  df::VisualParams::Init(1.0, 256);
  TestFramework framework;
  auto & bookmarks = framework.GetBookmarkManager();
  auto const point = mercator::FromLatLon(0.0, 0.005);
  ScreenBase screen;
  screen.SetFromRects(m2::AnyRectD(m2::RectD(-0.01, -0.01, 0.02, 0.01)), m2::RectD(0, 0, 1000, 1000));
  framework.SetViewport(screen, screen.PixelRect());

  kml::TrackData data;
  data.m_layers.emplace_back();
  data.m_geometry.AddLine({{mercator::FromLatLon(0.0, 0.0), 0}, {mercator::FromLatLon(0.0, 0.01), 0}});
  data.m_geometry.AddTimestamps({});
  auto const categoryId = bookmarks.CreateBookmarkCategory("Test tracks");
  auto const trackId = bookmarks.GetEditSession().CreateTrack(std::move(data))->GetId();
  bookmarks.GetEditSession().AttachTrack(trackId, categoryId);
  bookmarks.SetTrackSelectionInfo({trackId, point, 0.0}, false /* notifyListeners */);

  place_page::BuildInfo tap;
  tap.m_mercator = point;
  framework.SetLowPowerNavigationMode(true);
  auto candidates = framework.FindTracksInTapPosition(tap);
  TEST_EQUAL(candidates.size(), 1, ());
  TEST_EQUAL(candidates.front().m_trackId, trackId, ());

  bookmarks.GetEditSession().SetIsVisible(categoryId, false);
  TEST(framework.FindTracksInTapPosition(tap).empty(), ());
  bookmarks.GetEditSession().SetIsVisible(categoryId, true);

  bookmarks.GetEditSession().SetTrackVisibility(trackId, false);
  TEST(framework.FindTracksInTapPosition(tap).empty(), ());
  bookmarks.GetEditSession().SetTrackVisibility(trackId, true);

  framework.SetLowPowerNavigationMode(false);
  candidates = framework.FindTracksInTapPosition(tap);
  TEST_EQUAL(candidates.size(), 1, ());
  TEST_EQUAL(candidates.front().m_trackId, trackId, ());
}
}  // namespace framework_viewport_tests
