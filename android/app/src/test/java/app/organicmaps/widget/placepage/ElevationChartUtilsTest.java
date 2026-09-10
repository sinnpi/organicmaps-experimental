package app.organicmaps.widget.placepage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.github.mikephil.charting.data.Entry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ElevationChartUtilsTest
{
  private static final float EPS = 0.001f;

  /** A valley: 100 m up to 300 m at 2 km, back down to 50 m at 4 km. */
  private static List<Entry> profile()
  {
    return Arrays.asList(new Entry(0f, 100f), new Entry(1000f, 200f), new Entry(2000f, 300f), new Entry(3000f, 150f),
                         new Entry(4000f, 50f));
  }

  @Test
  public void spansWholeProfile()
  {
    assertArrayEquals(new float[] {50f, 300f}, ElevationChartUtils.computeAltitudeRange(profile(), 0f, 4000f), EPS);
  }

  @Test
  public void interpolatesBothEdgesOfAWindowInsideOneSegment()
  {
    // Between 0 and 1000 m the profile rises linearly from 100 m to 200 m.
    assertArrayEquals(new float[] {125f, 175f}, ElevationChartUtils.computeAltitudeRange(profile(), 250f, 750f), EPS);
  }

  @Test
  public void includesPeaksInsideTheWindow()
  {
    // The 2 km summit lies between the interpolated edges: 250 m on the way up, 225 m on the way down.
    assertArrayEquals(new float[] {225f, 300f}, ElevationChartUtils.computeAltitudeRange(profile(), 1500f, 2500f), EPS);
  }

  @Test
  public void clampsAWindowRunningPastTheEnd()
  {
    assertArrayEquals(new float[] {50f, 150f}, ElevationChartUtils.computeAltitudeRange(profile(), 3000f, 9000f), EPS);
  }

  @Test
  public void handlesDegenerateInput()
  {
    assertNull(ElevationChartUtils.computeAltitudeRange(new ArrayList<>(), 0f, 1000f));
    assertNull(ElevationChartUtils.computeAltitudeRange(profile(), 2000f, 1000f));
    // A single point has the same altitude everywhere.
    final List<Entry> single = Arrays.asList(new Entry(0f, 42f));
    assertArrayEquals(new float[] {42f, 42f}, ElevationChartUtils.computeAltitudeRange(single, 0f, 5000f), EPS);
  }
}
