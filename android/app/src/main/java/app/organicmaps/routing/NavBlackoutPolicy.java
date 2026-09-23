package app.organicmaps.routing;

/**
 * Decides when navigation may black the screen out: some way past a turn, while the next one is still far
 * off, and only once nobody has touched the screen for a while. A short countdown comes first, so that the
 * screen does not go black under a user who is looking at it.
 *
 * Distances rather than times: riding speeds differ too much between cyclists for one lead time to fit all.
 */
final class NavBlackoutPolicy
{
  enum State
  {
    LIT,
    COUNTDOWN,
    BLACK
  }

  /// How far past a turn the screen stays on, for a look at where the route went.
  static final double AFTER_TURN_M = 100.0;
  /// How far before a turn the screen comes back on.
  static final double BEFORE_TURN_M = 200.0;
  /// A blackout shorter than this is not worth the flicker.
  static final double MIN_BLACKOUT_M = 250.0;
  /// A growing distance to the turn means the route has moved on to the next one, or has been rebuilt.
  /// Wider than the jitter of the position along the route while standing still.
  static final double NEW_TURN_JUMP_M = 30.0;
  static final long IDLE_MS = 10_000;
  static final long COUNTDOWN_MS = 3_000;

  private State mState = State.LIT;
  private long mLastActivityMs;
  private long mCountdownStartMs;
  /// Distance to the turn when the route moved on to it, to tell how far the last turn is behind.
  private double mLegStartM = Double.NaN;
  private double mLastDistToTurnM = Double.NaN;

  NavBlackoutPolicy(long nowMs)
  {
    mLastActivityMs = nowMs;
  }

  State getState()
  {
    return mState;
  }

  /// Whole seconds left of the countdown, as they are to be shown: 3, 2, 1.
  int getCountdownSeconds(long nowMs)
  {
    final long leftMs = Math.max(0, COUNTDOWN_MS - (nowMs - mCountdownStartMs));
    return (int) ((leftMs + 999) / 1000);
  }

  /// @return whether the state changed
  boolean onFix(double distToTurnM, long nowMs)
  {
    if (Double.isNaN(mLastDistToTurnM) || distToTurnM > mLastDistToTurnM + NEW_TURN_JUMP_M)
      mLegStartM = distToTurnM;
    mLastDistToTurnM = distToTurnM;

    final boolean farFromTurns =
        mLegStartM - distToTurnM >= AFTER_TURN_M && distToTurnM >= BEFORE_TURN_M + MIN_BLACKOUT_M;
    switch (mState)
    {
    case LIT:
      if (!farFromTurns || nowMs - mLastActivityMs < IDLE_MS)
        return false;
      mCountdownStartMs = nowMs;
      return setState(State.COUNTDOWN);
    case COUNTDOWN:
      if (!farFromTurns)
        return setState(State.LIT);
      return onTick(nowMs);
    case BLACK: return distToTurnM <= BEFORE_TURN_M && setState(State.LIT);
    }
    return false;
  }

  /// Moves the countdown on, between fixes.
  /// @return whether the state changed
  boolean onTick(long nowMs)
  {
    if (mState != State.COUNTDOWN || nowMs - mCountdownStartMs < COUNTDOWN_MS)
      return false;
    return setState(State.BLACK);
  }

  /// Lights the screen, cancelling a countdown or a blackout, and holds it on for another idle period.
  /// @return whether the state changed
  boolean wake(long nowMs)
  {
    mLastActivityMs = nowMs;
    return setState(State.LIT);
  }

  private boolean setState(State state)
  {
    if (mState == state)
      return false;
    mState = state;
    return true;
  }
}
