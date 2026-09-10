package app.organicmaps.sdk.location;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.JunctionInfo;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;

class RouteSimulationProvider extends BaseLocationProvider
{
  private static final String TAG = RouteSimulationProvider.class.getSimpleName();
  private static final long INTERVAL_MS = 1000;

  // A typical cycling speed. Route junctions are spaced arbitrarily, so the simulation advances by
  // distance rather than by one junction per tick: emitting a junction per second would imply wild
  // speed swings, which makes power measurements incomparable between runs.
  static final double DEFAULT_SPEED_MPS = 5.0;

  private final JunctionInfo[] mPoints;
  // Distance in metres from the first point to point i, so the position at any travelled distance
  // can be found by advancing a cursor rather than rescanning the route.
  private final double[] mDistanceToPoint;
  private final double mSpeedMps;

  private double mDistanceTravelled = 0.0;
  private int mSegment = 0;
  private boolean mActive = false;

  RouteSimulationProvider(@NonNull Context context, @NonNull Listener listener, JunctionInfo[] points, double speedMps)
  {
    super(listener);
    mPoints = points;
    mSpeedMps = speedMps;
    mDistanceToPoint = new double[points.length];

    final float[] result = new float[1];
    for (int i = 1; i < points.length; ++i)
    {
      Location.distanceBetween(points[i - 1].mLat, points[i - 1].mLon, points[i].mLat, points[i].mLon, result);
      mDistanceToPoint[i] = mDistanceToPoint[i - 1] + result[0];
    }
  }

  @Override
  public void start(long interval)
  {
    Logger.i(TAG, "speed = " + mSpeedMps + " m/s, points = " + mPoints.length
                      + ", length = " + (mPoints.length > 0 ? mDistanceToPoint[mPoints.length - 1] : 0) + " m");
    if (mActive)
      throw new IllegalStateException("Already started");
    if (mPoints.length < 2)
      throw new IllegalArgumentException("A route needs at least two points to be simulated");
    mActive = true;
    UiThread.runLater(this::nextPoint);
  }

  @Override
  public void stop()
  {
    Logger.i(TAG);
    mActive = false;
  }

  /**
   * Index of the segment containing {@code travelled}, searching forward from {@code fromSegment}.
   * The travelled distance only grows, so the cursor never has to walk backwards.
   */
  static int advanceSegment(@NonNull double[] distanceToPoint, int fromSegment, double travelled)
  {
    int segment = fromSegment;
    while (segment < distanceToPoint.length - 2 && distanceToPoint[segment + 1] < travelled)
      ++segment;
    return segment;
  }

  /** How far along the segment {@code travelled} falls, in [0, 1]. */
  static double fractionInSegment(@NonNull double[] distanceToPoint, int segment, double travelled)
  {
    final double length = distanceToPoint[segment + 1] - distanceToPoint[segment];
    // Zero-length segments occur in real routes where two junctions coincide.
    if (length <= 0)
      return 0.0;
    return Math.min((travelled - distanceToPoint[segment]) / length, 1.0);
  }

  public void nextPoint()
  {
    if (!mActive)
      return;

    final double routeLength = mDistanceToPoint[mPoints.length - 1];
    if (mDistanceTravelled > routeLength)
    {
      Logger.i(TAG, "Finished the route");
      mActive = false;
      return;
    }

    mSegment = advanceSegment(mDistanceToPoint, mSegment, mDistanceTravelled);
    final double fraction = fractionInSegment(mDistanceToPoint, mSegment, mDistanceTravelled);

    final JunctionInfo from = mPoints[mSegment];
    final JunctionInfo to = mPoints[mSegment + 1];

    final Location location = new Location(LocationUtils.FUSED_PROVIDER);
    // Linear interpolation in degrees: segments are short enough that the error is far below the
    // accuracy this reports.
    location.setLatitude(from.mLat + (to.mLat - from.mLat) * fraction);
    location.setLongitude(from.mLon + (to.mLon - from.mLon) * fraction);
    location.setAccuracy(1.0f);
    location.setSpeed((float) mSpeedMps);

    final float[] result = new float[2];
    Location.distanceBetween(from.mLat, from.mLon, to.mLat, to.mLon, result);
    location.setBearing(result[1]);

    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    location.setTime(System.currentTimeMillis());

    mListener.onLocationChanged(location);
    mDistanceTravelled += mSpeedMps * INTERVAL_MS / 1000.0;

    UiThread.runLater(this::nextPoint, INTERVAL_MS);
  }
}
