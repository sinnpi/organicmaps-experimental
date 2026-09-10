package app.organicmaps.widget.placepage;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.StringUtils;
import com.github.mikephil.charting.charts.BarLineChartBase;
import com.github.mikephil.charting.formatter.DefaultValueFormatter;

public class AxisValueFormatter extends DefaultValueFormatter
{
  private static final int DEF_DIGITS = 1;
  @NonNull
  private final BarLineChartBase mChart;

  private float mOrigin;

  public AxisValueFormatter(@NonNull BarLineChartBase chart)
  {
    super(DEF_DIGITS);
    mChart = chart;
  }

  /// Shifts the labels so that they read as distances from |origin| rather than from the start of
  /// the chart. Used by the navigation profile to label the terrain ahead of the user.
  public void setOrigin(float origin)
  {
    mOrigin = origin;
  }

  @Override
  public String getFormattedValue(float value)
  {
    return StringUtils.nativeFormatDistance(Math.max(0f, value - mOrigin)).toString(mChart.getContext());
  }
}
