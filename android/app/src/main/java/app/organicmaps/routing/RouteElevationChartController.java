package app.organicmaps.routing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.widget.placepage.ElevationChartUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared presentation of route elevation, before starting and during navigation. */
public class RouteElevationChartController
{
  @NonNull
  private final Context mContext;
  @Nullable
  private final LineChart mChart;
  @Nullable
  private final TextView mMaxAltitude;
  @Nullable
  private final TextView mMinAltitude;
  @Nullable
  private final ColorStateList mAltitudeTextColors;

  @Nullable
  private ElevationSelectionListener mListener;
  @NonNull
  private List<Entry> mValues = Collections.emptyList();
  private int mMinLabelAltitude = Integer.MIN_VALUE;
  private int mMaxLabelAltitude = Integer.MIN_VALUE;
  private boolean mLowPowerMode;

  public interface ElevationSelectionListener
  {
    void onElevationPointSelected(double distanceMeters);
    void onElevationPointDeselected();
  }

  public RouteElevationChartController(@NonNull View view)
  {
    mContext = view.getContext();
    mChart = view.findViewById(R.id.elevation_profile_chart);
    mMaxAltitude = view.findViewById(R.id.highest_altitude);
    mMinAltitude = view.findViewById(R.id.lowest_altitude);
    mAltitudeTextColors = mMaxAltitude == null ? null : mMaxAltitude.getTextColors();

    if (mChart == null)
      return;

    ElevationChartUtils.setupRouteChart(mChart, mContext);
    mChart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
      @Override
      public void onValueSelected(Entry e, Highlight h)
      {
        if (mListener != null && !mValues.isEmpty())
          mListener.onElevationPointSelected(h.getX());
      }

      @Override
      public void onNothingSelected()
      {
        if (mListener != null)
          mListener.onElevationPointDeselected();
      }
    });
  }

  public void setListener(@Nullable ElevationSelectionListener listener)
  {
    mListener = listener;
  }

  public void clearSelection()
  {
    if (mChart != null)
      mChart.highlightValue(null, false);
  }

  public void setData(@Nullable RouteAltitudeData data)
  {
    setData(data, true /* resetZoom */);
  }

  /** Navigation owns its viewport; the preview resets to the whole route after layout. */
  void setData(@Nullable RouteAltitudeData data, boolean resetZoom)
  {
    clearSelection();
    mValues = Collections.emptyList();
    mMinLabelAltitude = Integer.MIN_VALUE;
    mMaxLabelAltitude = Integer.MIN_VALUE;
    if (mChart == null)
      return;

    if (data == null || data.getSize() < 2)
    {
      mChart.clear();
      if (mMaxAltitude != null)
        mMaxAltitude.setText("");
      if (mMinAltitude != null)
        mMinAltitude.setText("");
      return;
    }

    mValues = new ArrayList<>(data.getSize());
    for (int i = 0; i < data.getSize(); i++)
      mValues.add(new Entry((float) data.getDistance(i), data.getAltitude(i), i));

    setAltitudeRange(data.getMinAltitude(), data.getMaxAltitude());
    ElevationChartUtils.setChartData(mChart, mValues, mContext, resetZoom);
    applyStyle();
  }

  @NonNull
  List<Entry> getValues()
  {
    return mValues;
  }

  /** Labels describe the same altitude range used to scale the profile. */
  void setAltitudeRange(float minAltitude, float maxAltitude)
  {
    if (mChart == null)
      return;

    ElevationChartUtils.configureYAxisBounds(mChart, minAltitude, maxAltitude);
    final int min = Math.round(minAltitude);
    final int max = Math.round(maxAltitude);
    if (mMinAltitude != null && min != mMinLabelAltitude)
      mMinAltitude.setText(Framework.nativeFormatAltitude(min));
    if (mMaxAltitude != null && max != mMaxLabelAltitude)
      mMaxAltitude.setText(Framework.nativeFormatAltitude(max));
    mMinLabelAltitude = min;
    mMaxLabelAltitude = max;
  }

  void setLowPowerMode(boolean enabled)
  {
    mLowPowerMode = enabled;
    applyStyle();
  }

  private void applyStyle()
  {
    if (mChart == null)
      return;

    mChart.setBackgroundColor(mLowPowerMode ? Color.BLACK : ThemeUtils.getColor(mContext, R.attr.cardBackground));
    mChart.getXAxis().setTextColor(
        mLowPowerMode ? 0xFF8A8A8A : ThemeUtils.getColor(mContext, R.attr.elevationProfileAxisLabelColor));
    final int dividerColor =
        mLowPowerMode ? 0xFF242424 : ThemeUtils.getColor(mContext, androidx.appcompat.R.attr.dividerHorizontal);
    mChart.getXAxis().setAxisLineColor(dividerColor);
    mChart.getAxisLeft().setGridColor(dividerColor);

    for (TextView label : new TextView[] {mMinAltitude, mMaxAltitude})
    {
      if (label == null)
        continue;
      if (mLowPowerMode)
      {
        label.setBackgroundColor(Color.BLACK);
        label.setTextColor(0xFFBDBDBD);
      }
      else
      {
        label.setBackgroundResource(ThemeUtils.getResource(mContext, R.attr.altitudeBg));
        label.setTextColor(mAltitudeTextColors);
      }
    }

    if (mChart.getData() != null)
    {
      final LineDataSet dataSet = (LineDataSet) mChart.getData().getDataSetByIndex(0);
      ElevationChartUtils.applyLineDataSetStyle(dataSet, mContext);
      if (mLowPowerMode)
      {
        dataSet.setColor(0xFFBDBDBD);
        dataSet.setFillColor(0xFFBDBDBD);
        dataSet.setFillAlpha(18);
        dataSet.setHighLightColor(Color.WHITE);
      }
      mChart.notifyDataSetChanged();
    }
    mChart.invalidate();
  }
}
