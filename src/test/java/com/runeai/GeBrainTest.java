package com.runeai;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.Test;

/**
 * The feature vector is a contract with sim/ge_rl_trainer.py. Feed the weights
 * the wrong columns, in the wrong order, or on the wrong scale and nothing
 * throws — you just get confident nonsense driving the ranking. These pin the
 * exact arithmetic of {@code features()} so a change on either side breaks a
 * test instead of quietly mis-ranking every suggestion.
 */
public class GeBrainTest
{
	@Test
	public void featuresMatchTheTrainersDefinition()
	{
		// features(low=1000, high=1100, vol=500, prev_mid=1000, hour=6)
		final double[] x = GeBrain.features(1_000, 1_100, 500, 1_000, 6);
		assertEquals(7, x.length);
		assertEquals(Math.log10(1_000) / 8.0, x[0], 1e-12);
		assertEquals(Math.log10(100) / 8.0, x[1], 1e-12);   // spread = high - low
		assertEquals(Math.log10(500) / 6.0, x[2], 1e-12);
		// mom = mid/prev - 1 = 1050/1000 - 1 = 0.05, then x5
		assertEquals(0.25, x[3], 1e-12);
		assertEquals(Math.sin(2 * Math.PI * 6 / 24.0), x[4], 1e-12);
		assertEquals(Math.cos(2 * Math.PI * 6 / 24.0), x[5], 1e-12);
		assertEquals(1.0, x[6], 1e-12);
	}

	@Test
	public void momentumIsClampedAndZeroWithoutHistory()
	{
		// no previous mid: the trainer uses 0.0, not a divide by zero
		assertEquals(0.0, GeBrain.features(1_000, 1_100, 500, 0, 0)[3], 1e-12);
		// a doubling is clamped to +0.2 before the x5, i.e. 1.0
		assertEquals(1.0, GeBrain.features(2_000, 2_200, 500, 1_000, 0)[3], 1e-12);
		// and a halving to the negative bound
		assertEquals(-1.0, GeBrain.features(400, 440, 500, 1_000, 0)[3], 1e-12);
	}

	@Test
	public void degenerateBooksStillProduceFiniteFeatures()
	{
		// spread of zero would be log10(0) = -inf without the max(spread, 1)
		for (double v : GeBrain.features(1, 1, 0, 0, 23))
		{
			assertTrue("no NaN or infinity reaches the weights", Double.isFinite(v));
		}
	}

	/**
	 * Full forward-pass parity against sim/ge_rl_trainer.py's {@code Net.forward}.
	 *
	 * <p>The references below were produced by running that Python against the
	 * brain trained on 29,695 held-out rows. The brain is a local file that is
	 * never committed, so this skips in a clean checkout — and it skips again if
	 * the brain is retrained, because different weights mean different (correct)
	 * answers and a stale expectation would be worse than no test.
	 */
	@Test
	public void javaForwardPassMatchesThePythonTrainer()
	{
		final GeBrain b = new GeBrain(new Gson());
		b.ensureLoaded();
		Assume.assumeTrue("no local brain to compare against", b.adopted());
		Assume.assumeTrue("brain was retrained — regenerate these references",
			b.status().contains("29,695 held-out rows"));

		final long[][] in = {
			{2, 1_000, 1_100, 500, 1_000, 6},
			{10127, 5_000, 5_200, 120, 4_900, 0},
			{2026, 200, 260, 9_000, 205, 13},
			{99999, 50, 55, 40, 0, 23},          // unknown item: no personality bias
			{4740, 1_500, 4_600, 31, 1_400, 18},
		};
		final double[] python = {
			0.0004118165120804672,
			-0.005225213736687915,
			-0.09372429960382123,
			0.0017469124754369207,
			-0.038796879908774014,
		};
		for (int i = 0; i < in.length; i++)
		{
			final long[] c = in[i];
			assertEquals("case " + i + " diverged from the trainer", python[i],
				b.residual((int) c[0], c[1], c[2], c[3], c[4], (int) c[5]), 1e-15);
		}
	}
}
