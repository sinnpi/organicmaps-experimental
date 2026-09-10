#pragma once

#include "geometry/screenbase.hpp"

namespace df
{
// While following a route the camera moves smoothly and predictably, so consecutive frames differ
// by a fraction of a pixel: at cycling speed and z17 the map advances roughly 1 px per frame at
// 30 fps. Redrawing the whole scene for that is work whose result nobody can see.
//
// The change between two model views is measured as the largest distance any viewport corner
// travels on screen, which folds panning, rotation and zoom into one number in pixels -- a rotation
// about the viewport centre moves no pixels at the centre but sweeps the corners, and separate
// translation and angle thresholds would have to be tuned against each other to express that.
double MaxPixelShift(ScreenBase const & from, ScreenBase const & to);

// True when redrawing would not produce a perceptibly different image.
bool IsScreenChangeBelowDeadband(ScreenBase const & lastDrawn, ScreenBase const & current, double thresholdPx);
}  // namespace df
