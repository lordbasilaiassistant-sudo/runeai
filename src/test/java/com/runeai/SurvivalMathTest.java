package com.runeai;

import java.util.ArrayDeque;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The survival model's arithmetic and the two new market-structure inputs:
 * the 4h buy-limit window and the order-flow factor. All pure, all pinned.
 */
public class SurvivalMathTest
{
	private static final double[] NO_BETA = {0, 0, 0, 0};

	@Test
	public void theMedianIsTheWeibullMedianExactly()
	{
		// with no covariates, lambda = e^mu; median = lambda * ln2^(1/k).
		// k=1 (exponential): median = lambda * ln 2
		final double mu = Math.log(600);
		assertEquals((long) (600 * Math.log(2)),
			SurvivalFillModel.medianSecs(mu, 1.0, NO_BETA, true, 1_000, 12));
		// k=2: median = lambda * sqrt(ln 2)
		assertEquals((long) (600 * Math.sqrt(Math.log(2))),
			SurvivalFillModel.medianSecs(mu, 2.0, NO_BETA, true, 1_000, 12));
	}

	@Test
	public void fillProbabilityIsAProperCdf()
	{
		final double mu = Math.log(300);
		assertEquals(0, SurvivalFillModel.pFillWithin(mu, 1.2, NO_BETA, true, 500, 12, 0), 1e-12);
		double prev = 0;
		for (long tsec : new long[]{30, 60, 300, 900, 3600, 36_000})
		{
			final double p = SurvivalFillModel.pFillWithin(mu, 1.2, NO_BETA, true, 500, 12, tsec);
			assertTrue("CDF must not decrease", p >= prev);
			assertTrue("probability stays in [0,1]", p >= 0 && p <= 1);
			prev = p;
		}
		// at t = lambda the Weibull CDF is 1 - 1/e regardless of k
		assertEquals(1 - 1 / Math.E,
			SurvivalFillModel.pFillWithin(mu, 1.7, NO_BETA, true, 500, 12, 300), 1e-9);
	}

	@Test
	public void aPositiveSideBetaSlowsThatSideDown()
	{
		// beta[0] acts on the buy side: positive = buys take longer
		final double[] beta = {0.5, 0, 0, 0};
		final double mu = Math.log(300);
		final long buyMed = SurvivalFillModel.medianSecs(mu, 1.3, beta, true, 1_000, 12);
		final long sellMed = SurvivalFillModel.medianSecs(mu, 1.3, beta, false, 1_000, 12);
		assertTrue(buyMed > sellMed);
	}

	// ================= buy-limit window =================

	@Test
	public void theLimitWindowForgetsPurchasesOlderThanFourHours()
	{
		final ArrayDeque<long[]> w = new ArrayDeque<>();
		final long now = 10_000_000_000L;
		w.addLast(new long[]{now - 4 * 3600_000L - 1, 500});  // aged out
		w.addLast(new long[]{now - 3 * 3600_000L, 300});      // still counts
		w.addLast(new long[]{now - 60_000L, 200});            // still counts
		assertEquals(500, RuneAIPlugin.usedInWindow(w, now));
		assertEquals("pruned in place", 2, w.size());
		// exactly at the boundary is out — the game's window has reopened
		final ArrayDeque<long[]> edge = new ArrayDeque<>();
		edge.addLast(new long[]{now - 4 * 3600_000L, 100});
		assertEquals(0, RuneAIPlugin.usedInWindow(edge, now));
		assertEquals(0, RuneAIPlugin.usedInWindow(null, now));
	}

	// ================= order flow =================

	@Test
	public void theFlowFactorTiltsButNeverDominates()
	{
		assertEquals(1.0, FlipService.flowFactor(0), 1e-12);
		assertTrue(FlipService.flowFactor(0.5) > 1.0);
		assertTrue(FlipService.flowFactor(-0.5) < 1.0);
		// clamped at ±25% however one-sided the tape looks
		assertEquals(1.25, FlipService.flowFactor(1.0), 1e-12);
		assertEquals(0.75, FlipService.flowFactor(-1.0), 1e-12);
		assertEquals(1.25, FlipService.flowFactor(37), 1e-12);
	}
}
