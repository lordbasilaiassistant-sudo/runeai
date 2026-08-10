package com.runeai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The anomaly predicate, which exists to interrupt a human being. Every false
 * positive spends attention that the plugin cannot give back, so what these lock
 * down is the gate that separates "the market repriced" from "one person typed a
 * big number": the instant-SELL side has to have moved too.
 */
public class AnomalyTest
{
	private static final double PCT = 25;
	private static final long VOL = FlipService.THIN_VOL_5M;

	@Test
	public void aRealTwoSidedRipFires()
	{
		// mid 1000 -> 1400 (+40%), and the low side came with it (+40%)
		assertTrue(FlipService.anomalous(1_000, 1_400, 900, 1_260, VOL, PCT));
		// and the same thing downward — a crash is exactly as worth knowing
		assertTrue(FlipService.anomalous(1_000, 600, 900, 540, VOL, PCT));
	}

	@Test
	public void oneWhaleOverpayingIsNotAnAnomaly()
	{
		// the instant-buy print rips the mid 30%, but the price everyone else is
		// actually trading at has not moved at all — this is the trap board's
		// business, not an alert's
		assertFalse(FlipService.anomalous(1_000, 1_300, 900, 900, VOL, PCT));
		// even a small drift on the low side is not enough: it must travel at
		// least half as far as the mid
		assertFalse(FlipService.anomalous(1_000, 1_300, 900, 990, VOL, PCT));
		// exactly half as far does count
		assertTrue(FlipService.anomalous(1_000, 1_300, 1_000, 1_150, VOL, PCT));
	}

	@Test
	public void thetwoSidesHaveToAgreeOnDirection()
	{
		// mid up 30%, low side DOWN — the book is widening, not repricing
		assertFalse(FlipService.anomalous(1_000, 1_300, 1_000, 800, VOL, PCT));
	}

	@Test
	public void aThinBookNeverCounts()
	{
		// same 40% move on both sides, but nothing is trading behind it
		assertFalse(FlipService.anomalous(1_000, 1_400, 900, 1_260,
			FlipService.THIN_VOL_5M - 1, PCT));
		assertFalse(FlipService.anomalous(1_000, 1_400, 900, 1_260, 0, PCT));
	}

	@Test
	public void theThresholdIsTheThreshold()
	{
		// just under 25% does not fire, at 25% it does
		assertFalse(FlipService.anomalous(1_000, 1_249, 1_000, 1_500, VOL, PCT));
		assertTrue(FlipService.anomalous(1_000, 1_250, 1_000, 1_500, VOL, PCT));
		// and the threshold is a knob, not a constant
		assertTrue(FlipService.anomalous(1_000, 1_100, 1_000, 1_100, VOL, 10));
		assertFalse(FlipService.anomalous(1_000, 1_100, 1_000, 1_100, VOL, 30));
	}

	@Test
	public void garbageReferencesNeverFire()
	{
		// no history yet, or a book with no sell side: not an anomaly, absence of data
		assertFalse(FlipService.anomalous(0, 1_400, 900, 1_260, VOL, PCT));
		assertFalse(FlipService.anomalous(1_000, 1_400, 0, 1_260, VOL, PCT));
		assertFalse(FlipService.anomalous(1_000, 1_400, 900, 0, VOL, PCT));
	}
}
