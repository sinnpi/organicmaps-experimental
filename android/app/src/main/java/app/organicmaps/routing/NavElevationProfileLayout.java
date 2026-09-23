package app.organicmaps.routing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.RelativeLayout;
import androidx.annotation.Nullable;
import app.organicmaps.widget.placepage.ElevationProfileChart;

/** Lets a pinch start over the chart, a place marker, or an altitude/window label. */
public class NavElevationProfileLayout extends RelativeLayout
{
  @Nullable
  private ElevationProfileChart.OnWindowScaleListener mScaleListener;
  private boolean mPinching;
  private int mFirstPointerId;
  private int mSecondPointerId;
  private float mStartSpan;

  public NavElevationProfileLayout(Context context, AttributeSet attrs)
  {
    super(context, attrs);
  }

  public void setOnWindowScaleListener(@Nullable ElevationProfileChart.OnWindowScaleListener listener)
  {
    mScaleListener = listener;
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean dispatchTouchEvent(MotionEvent event)
  {
    final int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN)
      mPinching = false;

    if (mScaleListener != null && action == MotionEvent.ACTION_POINTER_DOWN && !mPinching
        && event.getPointerCount() == 2)
    {
      mFirstPointerId = event.getPointerId(0);
      mSecondPointerId = event.getPointerId(1);
      mStartSpan = span(event);
      mPinching = true;
      // The first finger may have started a scrub or pressed a marker. Cancel that child before
      // taking over the gesture, so lifting the fingers cannot turn the pinch into a click.
      final MotionEvent cancel = MotionEvent.obtain(event);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      super.dispatchTouchEvent(cancel);
      cancel.recycle();
      mScaleListener.onWindowScaleStart();
      return true;
    }

    if (mPinching)
    {
      if (action == MotionEvent.ACTION_MOVE && mStartSpan > 0f)
      {
        final int first = event.findPointerIndex(mFirstPointerId);
        final int second = event.findPointerIndex(mSecondPointerId);
        if (first >= 0 && second >= 0)
        {
          final float dx = event.getX(first) - event.getX(second);
          final float dy = event.getY(first) - event.getY(second);
          final float currentSpan = (float) Math.hypot(dx, dy);
          if (currentSpan > 0f)
            mScaleListener.onWindowScale(mStartSpan / currentSpan);
        }
      }
      // Consume the whole gesture, including the remaining finger after a pointer is lifted.
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        mPinching = false;
      return true;
    }
    return super.dispatchTouchEvent(event);
  }

  // Empty parts of the profile must still be able to start a pinch.
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    return true;
  }

  private static float span(MotionEvent event)
  {
    return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
  }
}
