package com.runeai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The scoreboard's whole value is that it cannot be gamed by doing nothing, so
 * these lock down the three ways a "beat your last session" board usually lies:
 * a rate extrapolated from a few seconds, empty sessions padding the ranking,
 * and the live session being counted as its own competition.
 */
public class SessionHistoryTest
{
	private static SessionHistory.Session session(long flipPnl, long activeMs)
	{
		final SessionHistory.Session s = new SessionHistory.Session();
		s.setFlipPnl(flipPnl);
		s.setActiveMs(activeMs);
		s.setUnitsSold(flipPnl == 0 ? 0 : 1);
		return s;
	}

	@Test
	public void gpPerHourIsMeasuredOverActiveTime()
	{
		// one hour of trading for 100k is 100k/h
		assertEquals(100_000, SessionHistory.gpPerHour(100_000, 3_600_000L));
		// half an hour for 100k is 200k/h
		assertEquals(200_000, SessionHistory.gpPerHour(100_000, 1_800_000L));
		// losses stay losses
		assertEquals(-50_000, SessionHistory.gpPerHour(-50_000, 3_600_000L));
	}

	@Test
	public void aFewSecondsOfTradingCannotClaimAnHourlyRate()
	{
		// 10k in four seconds is NOT 9,000,000 gp/h — it is measured as a minute
		assertEquals(600_000, SessionHistory.gpPerHour(10_000, 4_000L));
		assertEquals(SessionHistory.gpPerHour(10_000, 60_000L),
			SessionHistory.gpPerHour(10_000, 4_000L));
		// and no time at all is no rate, not a divide by zero
		assertEquals(0, SessionHistory.gpPerHour(10_000, 0));
	}

	@Test
	public void emptySessionsAreNeverKept()
	{
		assertFalse(SessionHistory.worthKeeping(new SessionHistory.Session()));
		assertFalse(SessionHistory.worthKeeping(null));
		assertTrue(SessionHistory.worthKeeping(session(1, 60_000)));
		final SessionHistory.Session lost = new SessionHistory.Session();
		lost.setFlipPnl(-500);
		assertTrue("a losing session is still a session", SessionHistory.worthKeeping(lost));
	}

	@Test
	public void theFirstSessionHasNothingToBeat()
	{
		final SessionHistory.Scoreboard s =
			SessionHistory.score(session(5_000, 3_600_000L), new ArrayList<>());
		assertEquals(0, s.getRanked());
		assertEquals(0, s.getBeaten());
		assertEquals(0, s.getToBeatLast());
		assertEquals(0, s.getToBeatBest());
		assertEquals(5_000, s.getCurPnl());
	}

	@Test
	public void theGapToBeatIsStatedInGpAndClosesAtZero()
	{
		final List<SessionHistory.Session> past = Arrays.asList(
			session(20_000, 3_600_000L),   // the best
			session(9_000, 3_600_000L));   // the last
		// behind: exactly the gp needed to go one past each mark
		SessionHistory.Scoreboard s = SessionHistory.score(session(6_000, 3_600_000L), past);
		assertEquals(3_001, s.getToBeatLast());
		assertEquals(14_001, s.getToBeatBest());
		assertEquals(0, s.getBeaten());
		assertEquals(2, s.getRanked());
		// past the last one but not the best
		s = SessionHistory.score(session(9_001, 3_600_000L), past);
		assertEquals(0, s.getToBeatLast());
		assertEquals(11_000, s.getToBeatBest());
		assertEquals(1, s.getBeaten());
		// past everything
		s = SessionHistory.score(session(20_001, 3_600_000L), past);
		assertEquals(0, s.getToBeatLast());
		assertEquals(0, s.getToBeatBest());
		assertEquals(2, s.getBeaten());
	}

	@Test
	public void lastMeansMostRecentAndBestMeansHighest()
	{
		final List<SessionHistory.Session> past = Arrays.asList(
			session(50_000, 3_600_000L),
			session(1_000, 3_600_000L));
		final SessionHistory.Scoreboard s = SessionHistory.score(session(0, 60_000), past);
		assertEquals("last is the most recent row, not the best one", 1_000, s.getLastPnl());
		assertEquals(50_000, s.getBestPnl());
	}

	@Test
	public void paddingTheHistoryWithEmptySessionsDoesNotInflateTheRank()
	{
		final List<SessionHistory.Session> padded = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			padded.add(new SessionHistory.Session()); // logged in, did nothing
		}
		padded.add(session(9_000, 3_600_000L));
		final SessionHistory.Scoreboard s = SessionHistory.score(session(100, 3_600_000L), padded);
		assertEquals("only real sessions are ranked", 1, s.getRanked());
		assertEquals(0, s.getBeaten());
		assertEquals(9_000, s.getLastPnl());
	}
}
