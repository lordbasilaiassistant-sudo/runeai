package com.runeai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The lane split is pure arithmetic, so it is testable without a client. What
 * these lock down is the rule that made the split worth building: a book that is
 * thin or trapped is never quick however good the estimate looks, and the ceiling
 * is the only thing separating a velocity pick from a patience order.
 */
public class FlipLaneTest
{
	private static final long CEIL = FlipLane.DEFAULT_QUICK_CYCLE_SECS;

	@Test
	public void thickFastBooksAreTheQuickLane()
	{
		// a liquid book with a normal spread and a fast estimate
		assertEquals(FlipLane.QUICK, FlipLane.classify(60, 1_000, 1_050, 500, CEIL));
		// right on the ceiling still counts
		assertEquals(FlipLane.QUICK, FlipLane.classify(CEIL, 1_000, 1_050, 500, CEIL));
	}

	@Test
	public void slowThinAndTrappedBooksAreTheLongLane()
	{
		// one second past the ceiling is a patience order, not a quick flip
		assertEquals(FlipLane.LONG, FlipLane.classify(CEIL + 1, 1_000, 1_050, 500, CEIL));
		// under the liquidity floor the estimate is extrapolating from nothing
		assertEquals(FlipLane.LONG, FlipLane.classify(10, 1_000, 1_050, 5, CEIL));
		// the whale-print trap: never a quick flip whatever the number says
		assertEquals(FlipLane.LONG, FlipLane.classify(10, 100, 45_080, 0, CEIL));
	}

	@Test
	public void thePriorGetsSlowerAsTheBookThins()
	{
		// monotone in volume — the only property the prior actually claims
		long prev = 0;
		for (long vol = 2_000; vol >= 10; vol /= 2)
		{
			final long secs = FlipLane.priorCycleSecs(vol);
			assertTrue("thinner book must not be faster", secs >= prev);
			prev = secs;
		}
		// the liquidity floor sits outside the default quick ceiling, a thick book
		// well inside it — that calibration is what makes the ceiling meaningful
		assertTrue(FlipLane.priorCycleSecs(FlipService.THIN_VOL_5M) > CEIL);
		assertTrue(FlipLane.priorCycleSecs(300) < CEIL);
	}

	@Test
	public void aCycleIsBothSides()
	{
		assertEquals(2 * FlipLane.priorSideSecs(400), FlipLane.priorCycleSecs(400));
	}

	@Test
	public void aQueueOrderNeverClaimsToFillInSeconds()
	{
		// however thick the book, a queue order waits behind it — the old 5s floor
		// printed "~10s" cycles that never happened, which is the complaint "nothing
		// is actually quick as it says" in one number
		assertTrue(FlipLane.priorSideSecs(1_000_000) >= 30);
		assertTrue(FlipLane.priorCycleSecs(1_000_000) >= 60);
	}
}
