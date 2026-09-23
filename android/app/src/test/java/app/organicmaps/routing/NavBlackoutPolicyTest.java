package app.organicmaps.routing;

import static app.organicmaps.routing.NavBlackoutPolicy.AFTER_TURN_M;
import static app.organicmaps.routing.NavBlackoutPolicy.BEFORE_TURN_M;
import static app.organicmaps.routing.NavBlackoutPolicy.COUNTDOWN_MS;
import static app.organicmaps.routing.NavBlackoutPolicy.IDLE_MS;
import static app.organicmaps.routing.NavBlackoutPolicy.MIN_BLACKOUT_M;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.routing.NavBlackoutPolicy.State;
import org.junit.Test;

public class NavBlackoutPolicyTest
{
  /// Just past a turn, with the next one 5 km ahead, and left alone long enough.
  private static NavBlackoutPolicy leftAloneAfterTurn()
  {
    final NavBlackoutPolicy policy = new NavBlackoutPolicy(0);
    assertFalse(policy.onFix(5000, IDLE_MS));
    return policy;
  }

  private static NavBlackoutPolicy black()
  {
    final NavBlackoutPolicy policy = leftAloneAfterTurn();
    assertTrue(policy.onFix(5000 - AFTER_TURN_M, IDLE_MS));
    assertTrue(policy.onTick(IDLE_MS + COUNTDOWN_MS));
    assertEquals(State.BLACK, policy.getState());
    return policy;
  }

  @Test
  public void countsDownOnlyWellPastTheLastTurn()
  {
    final NavBlackoutPolicy policy = leftAloneAfterTurn();
    assertFalse(policy.onFix(4901, IDLE_MS));
    assertEquals(State.LIT, policy.getState());
    assertTrue(policy.onFix(4900, IDLE_MS));
    assertEquals(State.COUNTDOWN, policy.getState());
  }

  @Test
  public void countsDownOnlyAfterTheIdlePeriod()
  {
    final NavBlackoutPolicy policy = new NavBlackoutPolicy(0);
    assertFalse(policy.onFix(5000, 0));
    assertFalse(policy.onFix(4000, IDLE_MS - 1));
    assertTrue(policy.onFix(4000, IDLE_MS));
    assertEquals(State.COUNTDOWN, policy.getState());
  }

  @Test
  public void countdownShowsWholeSecondsAndEndsBlack()
  {
    final NavBlackoutPolicy policy = leftAloneAfterTurn();
    final long start = IDLE_MS + 1;
    assertTrue(policy.onFix(4000, start));
    assertEquals(3, policy.getCountdownSeconds(start));
    assertEquals(2, policy.getCountdownSeconds(start + 1000));
    assertEquals(1, policy.getCountdownSeconds(start + 2999));
    assertFalse(policy.onTick(start + COUNTDOWN_MS - 1));
    assertTrue(policy.onTick(start + COUNTDOWN_MS));
    assertEquals(State.BLACK, policy.getState());
  }

  @Test
  public void tapDuringCountdownKeepsTheScreenOn()
  {
    final NavBlackoutPolicy policy = leftAloneAfterTurn();
    assertTrue(policy.onFix(4000, IDLE_MS));
    final long tap = IDLE_MS + 1000;
    assertTrue(policy.wake(tap));
    assertFalse(policy.onTick(tap + COUNTDOWN_MS));
    assertFalse(policy.onFix(3900, tap + IDLE_MS - 1));
    assertEquals(State.LIT, policy.getState());
    assertTrue(policy.onFix(3900, tap + IDLE_MS));
    assertEquals(State.COUNTDOWN, policy.getState());
  }

  @Test
  public void neverBlacksOutForAShortStretch()
  {
    final NavBlackoutPolicy policy = new NavBlackoutPolicy(0);
    final double legStart = AFTER_TURN_M + BEFORE_TURN_M + MIN_BLACKOUT_M;
    assertFalse(policy.onFix(legStart, IDLE_MS));
    // Far enough past the last turn, but the next one is already within a blackout of waking.
    assertFalse(policy.onFix(legStart - AFTER_TURN_M - 1, IDLE_MS));
    assertEquals(State.LIT, policy.getState());
  }

  @Test
  public void countdownStopsWhenTheTurnComesInRange()
  {
    final NavBlackoutPolicy policy = new NavBlackoutPolicy(0);
    assertFalse(policy.onFix(2000, IDLE_MS));
    assertTrue(policy.onFix(BEFORE_TURN_M + MIN_BLACKOUT_M, IDLE_MS));
    assertTrue(policy.onFix(BEFORE_TURN_M + MIN_BLACKOUT_M - 1, IDLE_MS + 500));
    assertEquals(State.LIT, policy.getState());
  }

  @Test
  public void wakesAtTheDistanceBeforeTheTurnWhateverTheSpeed()
  {
    final NavBlackoutPolicy policy = black();
    assertFalse(policy.onFix(201, IDLE_MS + COUNTDOWN_MS + 1));
    assertTrue(policy.onFix(200, IDLE_MS + COUNTDOWN_MS + 2));
    assertEquals(State.LIT, policy.getState());
  }

  @Test
  public void blacksOutAgainPastTheNextTurn()
  {
    final NavBlackoutPolicy policy = black();
    long now = IDLE_MS + COUNTDOWN_MS;
    assertTrue(policy.onFix(BEFORE_TURN_M, ++now));
    assertFalse(policy.onFix(5, now += IDLE_MS));
    // The route moves on to a turn 3 km further.
    assertFalse(policy.onFix(3000, ++now));
    assertFalse(policy.onFix(3000 - AFTER_TURN_M + 1, ++now));
    assertTrue(policy.onFix(3000 - AFTER_TURN_M, ++now));
    assertEquals(State.COUNTDOWN, policy.getState());
  }

  @Test
  public void standingStillJitterIsNotANewTurn()
  {
    final NavBlackoutPolicy policy = leftAloneAfterTurn();
    assertFalse(policy.onFix(4950, IDLE_MS));
    assertFalse(policy.onFix(4970, IDLE_MS));
    assertTrue(policy.onFix(5000 - AFTER_TURN_M, IDLE_MS));
  }

  @Test
  public void tapWakesFromBlack()
  {
    final NavBlackoutPolicy policy = black();
    assertTrue(policy.wake(IDLE_MS + COUNTDOWN_MS + 1));
    assertEquals(State.LIT, policy.getState());
  }
}
