package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.widget.placepage.ElevationChartUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class RouteElevationChartControllerTest
{
  private final Context mContext = mock(Context.class);
  private final LineChart mChart = mock(LineChart.class);
  private MockedStatic<ElevationChartUtils> mChartUtils;
  private MockedStatic<ThemeUtils> mThemeUtils;
  private RouteElevationChartController mController;

  private static RouteAltitudeData profile()
  {
    return new RouteAltitudeData(new double[] {0, 1000, 2000}, new int[] {-50, 300, 100}, 350, 200, -50, 300);
  }

  @Before
  public void setUp()
  {
    mChartUtils = mockStatic(ElevationChartUtils.class);
    mThemeUtils = mockStatic(ThemeUtils.class);
    final View view = mock(View.class);
    when(view.getContext()).thenReturn(mContext);
    when(view.findViewById(R.id.elevation_profile_chart)).thenReturn(mChart);
    when(mChart.getXAxis()).thenReturn(mock(XAxis.class));
    when(mChart.getAxisLeft()).thenReturn(mock(YAxis.class));
    mController = new RouteElevationChartController(view);
  }

  @After
  public void tearDown()
  {
    mThemeUtils.close();
    mChartUtils.close();
  }

  @Test
  public void previewAndNavigationUseTheSameSetupAndProfile()
  {
    mChartUtils.verify(() -> ElevationChartUtils.setupRouteChart(mChart, mContext));
    mController.setData(profile());
    final List<Entry> preview = mController.getValues();
    mChartUtils.verify(() -> ElevationChartUtils.setChartData(mChart, preview, mContext, true));

    mController.setData(profile(), false /* resetZoom */);
    final List<Entry> navigation = mController.getValues();
    mChartUtils.verify(() -> ElevationChartUtils.setChartData(mChart, navigation, mContext, false));
    assertEquals(3, navigation.size());
    for (int i = 0; i < navigation.size(); i++)
    {
      assertEquals(preview.get(i).getX(), navigation.get(i).getX(), 0f);
      assertEquals(preview.get(i).getY(), navigation.get(i).getY(), 0f);
    }
    assertEquals(-50f, navigation.get(0).getY(), 0f);
    mChartUtils.verify(() -> ElevationChartUtils.configureYAxisBounds(mChart, -50f, 300f), times(2));
  }

  @Test
  public void navigationWindowUsesTheSameAltitudeBoundsAsThePreview()
  {
    mController.setData(profile(), false);
    mController.setAltitudeRange(125f, 175f);
    mChartUtils.verify(() -> ElevationChartUtils.configureYAxisBounds(mChart, 125f, 175f));
    // Flat terrain must also go through the shared padding/rounding logic.
    mController.setAltitudeRange(100f, 100f);
    mChartUtils.verify(() -> ElevationChartUtils.configureYAxisBounds(mChart, 100f, 100f));
  }

  @Test
  public void clearsMissingEmptyAndSinglePointProfiles()
  {
    mController.setData(profile());
    mController.setData(null);
    assertTrue(mController.getValues().isEmpty());
    mController.setData(new RouteAltitudeData(new double[] {}, new int[] {}, 0, 0, 0, 0));
    assertTrue(mController.getValues().isEmpty());
    mController.setData(new RouteAltitudeData(new double[] {0}, new int[] {100}, 0, 0, 100, 100));
    assertTrue(mController.getValues().isEmpty());
    verify(mChart, times(3)).clear();
  }

  @Test
  public void rebuildReplacesPointsAndClearsSelectionWithoutResettingNavigationZoom()
  {
    mController.setData(profile(), false);
    mController.setData(new RouteAltitudeData(new double[] {0, 500}, new int[] {200, 100}, 0, 100, 100, 200), false);
    assertEquals(2, mController.getValues().size());
    assertEquals(500f, mController.getValues().get(1).getX(), 0f);
    verify(mChart, times(2)).highlightValue(null, false);
    verify(mChart, times(0)).post(any());
  }

  @Test
  public void bothContextsReceiveInterpolatedSelectionDistances()
  {
    final ArgumentCaptor<OnChartValueSelectedListener> callback =
        ArgumentCaptor.forClass(OnChartValueSelectedListener.class);
    verify(mChart).setOnChartValueSelectedListener(callback.capture());
    final RouteElevationChartController.ElevationSelectionListener listener =
        mock(RouteElevationChartController.ElevationSelectionListener.class);
    mController.setListener(listener);
    mController.setData(profile());
    final Highlight highlight = new Highlight(750f, 212.5f, 0);
    callback.getValue().onValueSelected(new Entry(1000f, 300f), highlight);
    verify(listener).onElevationPointSelected(750.0);
    callback.getValue().onNothingSelected();
    verify(listener).onElevationPointDeselected();

    mController.setData(null);
    callback.getValue().onValueSelected(new Entry(1000f, 300f), highlight);
    verify(listener, times(1)).onElevationPointSelected(750.0);
  }

  @Test
  public void lowPowerStyleSurvivesARebuildAndRestoresTheSharedStyle()
  {
    final LineDataSet dataSet = mock(LineDataSet.class);
    final LineData data = mock(LineData.class);
    when(data.getDataSetByIndex(0)).thenReturn(dataSet);
    when(mChart.getData()).thenReturn(data);
    mController.setLowPowerMode(true);
    mController.setData(profile(), false);
    verify(dataSet, times(2)).setFillAlpha(18);
    verify(mChart, times(2)).setBackgroundColor(Color.BLACK);

    mController.setLowPowerMode(false);
    mChartUtils.verify(() -> ElevationChartUtils.applyLineDataSetStyle(dataSet, mContext), times(3));
  }
}
