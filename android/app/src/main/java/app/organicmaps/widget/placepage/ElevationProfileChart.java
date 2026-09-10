package app.organicmaps.widget.placepage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.Nullable;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointD;

public class ElevationProfileChart extends LineChart
{
  private static final float CAPTURE_RADIUS_DP = 22f;

  /**
   * Receives two-finger gestures instead of letting the chart zoom its own viewport. Used by the
   * navigation profile, which anchors the viewport to the user's position and so must translate a
   * pinch into a change of the distance shown rather than a viewport transform.
   */
  public interface OnWindowScaleListener
  {
    void onWindowScaleStart();

    /**
     * @param spanRatio Finger distance at the start of the pinch divided by the current one:
     *                  greater than 1 when the fingers close in.
     */
    void onWindowScale(float spanRatio);
  }

  private boolean mIsSelecting;
  private boolean mSelectConfirmed;
  private boolean mAlwaysSelectOnDrag;
  @Nullable
  private OnWindowScaleListener mWindowScaleListener;
  private boolean mPinching;
  private float mPinchStartSpan;
  private float mMarkerScreenX;
  private float mTouchStartX;
  private float mLastHighlightedX = Float.NaN;
  private int mTouchSlop;

  public ElevationProfileChart(Context context)
  {
    super(context);
  }

  public ElevationProfileChart(Context context, AttributeSet attrs)
  {
    super(context, attrs);
  }

  public ElevationProfileChart(Context context, AttributeSet attrs, int defStyle)
  {
    super(context, attrs, defStyle);
  }

  @Override
  protected void init()
  {
    super.init();
    mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
  }

  /**
   * Makes a one-finger drag always move the marker, even when the chart is zoomed in, where a drag
   * would otherwise pan the viewport. Used by the navigation profile, whose viewport follows the
   * user's position and must not be panned by hand.
   */
  public void setAlwaysSelectOnDrag(boolean alwaysSelect)
  {
    mAlwaysSelectOnDrag = alwaysSelect;
  }

  public void setOnWindowScaleListener(@Nullable OnWindowScaleListener listener)
  {
    mWindowScaleListener = listener;
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev)
  {
    getParent().requestDisallowInterceptTouchEvent(true);
    return super.onInterceptTouchEvent(ev);
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    if (!mTouchEnabled)
      return super.onTouchEvent(event);

    final int action = event.getActionMasked();

    if (mWindowScaleListener != null && handleWindowScale(event, action))
      return true;

    if (action == MotionEvent.ACTION_DOWN)
    {
      mLastHighlightedX = Float.NaN;
      mMarkerScreenX = getCurrentHighlightScreenX();
      mIsSelecting = mAlwaysSelectOnDrag || !isZoomedIn() || isTouchNearHighlight(event.getX(), mMarkerScreenX);
      // When zoomed, highlight immediately. When not zoomed, wait for finger to move past touchSlop
      // to distinguish drag from pinch-zoom start. In always-select mode a pinch also begins with a
      // single finger, so wait there too, or the pinch scrubs before its second finger lands.
      mSelectConfirmed = isZoomedIn() && !mAlwaysSelectOnDrag;
      mTouchStartX = event.getX();
    }

    // Second finger → zoom, stop selecting.
    if (action == MotionEvent.ACTION_POINTER_DOWN)
    {
      mIsSelecting = false;
      // Preserve current marker position if the user already dragged it.
      if (mSelectConfirmed || isZoomedIn())
        mMarkerScreenX = getCurrentHighlightScreenX();
      mSelectConfirmed = false;
      if (hasHighlight())
        performHighlightAtScreenX(mMarkerScreenX);
    }

    // SELECT: marker follows finger on drag; taps delegated to super's onSingleTapUp.
    if (mIsSelecting)
    {
      if (action == MotionEvent.ACTION_MOVE)
      {
        if (!mSelectConfirmed && Math.abs(event.getX() - mTouchStartX) > mTouchSlop)
          mSelectConfirmed = true;
        if (mSelectConfirmed)
          performHighlightAtScreenX(event.getX());
      }
      if (isZoomedIn())
      {
        // Super doesn't see events when zoomed, so handle taps ourselves.
        if (action == MotionEvent.ACTION_UP)
          performHighlightAtScreenX(event.getX());
        return true;
      }
      // Reset mLastHighlighted so super's onSingleTapUp → performHighlight
      // doesn't toggle OFF the highlight we're about to set.
      if (action == MotionEvent.ACTION_UP)
        mChartTouchListener.setLastHighlighted(null);
      return super.onTouchEvent(event);
    }

    // PAN / ZOOM: chart moves, marker stays at fixed screen-X.
    boolean result = super.onTouchEvent(event);
    if (action == MotionEvent.ACTION_MOVE && hasHighlight())
      performHighlightAtScreenX(mMarkerScreenX);
    return result;
  }

  /**
   * Hands a two-finger gesture to the window-scale listener. Once a pinch starts, the rest of the
   * gesture is consumed so that neither the built-in zoom nor a selection can resume half way
   * through, when a lifted finger would otherwise look like a fresh drag.
   *
   * @return True while the gesture belongs to the listener.
   */
  private boolean handleWindowScale(MotionEvent event, int action)
  {
    switch (action)
    {
    case MotionEvent.ACTION_POINTER_DOWN:
      if (!mPinching && event.getPointerCount() == 2)
      {
        mPinching = true;
        mPinchStartSpan = spacing(event);
        mIsSelecting = false;
        mSelectConfirmed = false;
        mWindowScaleListener.onWindowScaleStart();
      }
      return mPinching;
    case MotionEvent.ACTION_MOVE:
      if (mPinching && event.getPointerCount() >= 2)
      {
        final float span = spacing(event);
        if (span > 0f && mPinchStartSpan > 0f)
          mWindowScaleListener.onWindowScale(mPinchStartSpan / span);
      }
      return mPinching;
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_CANCEL:
      if (!mPinching)
        return false;
      mPinching = false;
      return true;
    default: return mPinching;
    }
  }

  private static float spacing(MotionEvent event)
  {
    final float dx = event.getX(0) - event.getX(1);
    final float dy = event.getY(0) - event.getY(1);
    return (float) Math.hypot(dx, dy);
  }

  @Override
  public void computeScroll()
  {
    float prevLowestX = getLowestVisibleX();
    super.computeScroll();
    // During deceleration (fling) the viewport changes without touch events.
    // Keep the marker at its fixed screen position.
    if (!mIsSelecting && getLowestVisibleX() != prevLowestX && hasHighlight())
      performHighlightAtScreenX(mMarkerScreenX);
  }

  private boolean hasHighlight()
  {
    final Highlight[] h = getHighlighted();
    return h != null && h.length > 0;
  }

  private boolean isZoomedIn()
  {
    // Threshold above 1.0 to ignore floating-point jitter around the default scale.
    return getViewPortHandler().getScaleX() > 1.01f;
  }

  private float getCurrentHighlightScreenX()
  {
    final Highlight[] highlighted = getHighlighted();
    if (highlighted == null || highlighted.length == 0)
      return getWidth() / 2f;

    // The user-selected highlight is always last: ChartController.selectAtDistance()
    // passes [curPos, h] in that order; RouteElevationChartController uses a single highlight.
    final Highlight last = highlighted[highlighted.length - 1];
    final MPPointD pix = getTransformer(YAxis.AxisDependency.LEFT).getPixelForValues(last.getX(), last.getY());
    final float result = (float) pix.x;
    MPPointD.recycleInstance(pix);
    return result;
  }

  private boolean isTouchNearHighlight(float touchX, float highlightScreenX)
  {
    if (!hasHighlight())
      return false;

    final float captureRadiusPx = CAPTURE_RADIUS_DP * getResources().getDisplayMetrics().density;
    return Math.abs(touchX - highlightScreenX) <= captureRadiusPx;
  }

  private void performHighlightAtScreenX(float screenX)
  {
    if (getHighlighter() == null)
      return;
    // Cheap pre-check: convert screen → data X without allocating a Highlight.
    final MPPointD pos = getTransformer(YAxis.AxisDependency.LEFT).getValuesByTouchPoint(screenX, 0);
    final float dataX = (float) pos.x;
    MPPointD.recycleInstance(pos);
    if (dataX == mLastHighlightedX)
      return;
    final Highlight h = getHighlighter().getHighlight(screenX, 0);
    if (h != null)
    {
      mLastHighlightedX = h.getX();
      highlightValue(h, true);
    }
  }
}
