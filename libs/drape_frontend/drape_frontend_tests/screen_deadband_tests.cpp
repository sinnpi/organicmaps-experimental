#include "testing/testing.hpp"

#include "drape_frontend/screen_deadband.hpp"

#include "geometry/screenbase.hpp"

#include <cmath>

namespace screen_deadband_tests
{
// A phone-shaped viewport, so the corner distances below are of a realistic magnitude.
int constexpr kWidth = 1080;
int constexpr kHeight = 2160;
double constexpr kScale = 1.0;

ScreenBase MakeScreen(m2::PointD const & org = m2::PointD(0.0, 0.0), double angle = 0.0, double scale = kScale)
{
  ScreenBase screen;
  screen.OnSize(0, 0, kWidth, kHeight);
  screen.SetFromParams(org, angle, scale);
  return screen;
}

UNIT_TEST(ScreenDeadband_IdenticalScreensDoNotMove)
{
  auto const screen = MakeScreen();
  TEST_ALMOST_EQUAL_ABS(df::MaxPixelShift(screen, screen), 0.0, 1e-9, ());
  TEST(df::IsScreenChangeBelowDeadband(screen, screen, 1.5), ());
}

UNIT_TEST(ScreenDeadband_PureTranslationMeasuresInPixels)
{
  // At scale 1 a global unit is a pixel, so shifting the origin by 10 moves every corner by 10.
  auto const before = MakeScreen();
  auto const after = MakeScreen(m2::PointD(10.0, 0.0));

  TEST_ALMOST_EQUAL_ABS(df::MaxPixelShift(before, after), 10.0, 1e-6, ());
  TEST(!df::IsScreenChangeBelowDeadband(before, after, 1.5), ());
  TEST(df::IsScreenChangeBelowDeadband(before, after, 20.0), ());
}

UNIT_TEST(ScreenDeadband_SubPixelTranslationIsBelowTheDeadband)
{
  // The case the deadband exists for: at cycling speed the map creeps by well under a pixel per
  // frame, and redrawing the scene for that produces an identical image.
  auto const before = MakeScreen();
  auto const after = MakeScreen(m2::PointD(0.4, 0.0));

  TEST_LESS(df::MaxPixelShift(before, after), 1.5, ());
  TEST(df::IsScreenChangeBelowDeadband(before, after, 1.5), ());
}

UNIT_TEST(ScreenDeadband_RotationIsCaughtEvenThoughTheCentreHoldsStill)
{
  // Rotation about the viewport centre leaves the centre pixel where it was, so a metric based on
  // the centre point would report no change at all. The corners sweep, and that is what is seen.
  auto const before = MakeScreen();
  auto const after = MakeScreen(m2::PointD(0.0, 0.0), 0.5 * math::pi / 180.0 /* 0.5 degrees */);

  double const shift = df::MaxPixelShift(before, after);
  // Half the diagonal is ~1207 px, so half a degree sweeps a corner by roughly 10 px.
  TEST_GREATER(shift, 5.0, (shift));
  TEST(!df::IsScreenChangeBelowDeadband(before, after, 1.5), (shift));
}

UNIT_TEST(ScreenDeadband_ZoomIsCaught)
{
  auto const before = MakeScreen();
  auto const after = MakeScreen(m2::PointD(0.0, 0.0), 0.0, kScale * 1.05);

  TEST_GREATER(df::MaxPixelShift(before, after), 1.5, ());
  TEST(!df::IsScreenChangeBelowDeadband(before, after, 1.5), ());
}

UNIT_TEST(ScreenDeadband_ShiftIsSymmetric)
{
  auto const before = MakeScreen();
  auto const after = MakeScreen(m2::PointD(7.0, 3.0));

  TEST_ALMOST_EQUAL_ABS(df::MaxPixelShift(before, after), df::MaxPixelShift(after, before), 1e-6, ());
}

UNIT_TEST(ScreenDeadband_NonPositiveThresholdDisablesIt)
{
  // Zero is how the feature is turned off, so identical screens must still report "redraw".
  auto const screen = MakeScreen();
  TEST(!df::IsScreenChangeBelowDeadband(screen, screen, 0.0), ());
  TEST(!df::IsScreenChangeBelowDeadband(screen, screen, -1.0), ());
}

UNIT_TEST(ScreenDeadband_ResizedViewportAlwaysRedraws)
{
  auto const before = MakeScreen();

  ScreenBase after;
  after.OnSize(0, 0, kWidth, kHeight / 2);
  after.SetFromParams(m2::PointD(0.0, 0.0), 0.0, kScale);

  TEST(!df::IsScreenChangeBelowDeadband(before, after, 1000.0), ());
}
}  // namespace screen_deadband_tests
