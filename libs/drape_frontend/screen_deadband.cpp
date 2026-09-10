#include "drape_frontend/screen_deadband.hpp"

#include <algorithm>
#include <array>

namespace df
{
double MaxPixelShift(ScreenBase const & from, ScreenBase const & to)
{
  m2::RectD const & pxRect = to.PixelRect();
  std::array<m2::PointD, 4> const corners = {pxRect.LeftTop(), pxRect.RightTop(), pxRect.RightBottom(),
                                             pxRect.LeftBottom()};

  // Each corner is taken to the world through the new view and back to the screen through the old
  // one; the distance it lands from where it started is how far that piece of the world moved.
  double maxShift = 0.0;
  for (auto const & corner : corners)
    maxShift = std::max(maxShift, corner.Length(from.GtoP(to.PtoG(corner))));

  return maxShift;
}

bool IsScreenChangeBelowDeadband(ScreenBase const & lastDrawn, ScreenBase const & current, double thresholdPx)
{
  if (thresholdPx <= 0.0)
    return false;

  // A resized viewport has to be redrawn whatever the corners say, and comparing corner positions
  // across differently sized rects would not mean anything anyway.
  if (lastDrawn.PixelRect() != current.PixelRect())
    return false;

  return MaxPixelShift(lastDrawn, current) < thresholdPx;
}
}  // namespace df
