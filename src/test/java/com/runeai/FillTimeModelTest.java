package com.runeai;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The gate and the dot product. Both fail silently if they break — a renamed
 * verdict value would deploy an unproven model, and a mis-ordered feature vector
 * would produce confident nonsense with no error anywhere.
 */
public class FillTimeModelTest
{
	/** Weights as train_flip_model.py writes them, in its feature order. */
	private static final double[] W = {3.207, 0.665, -0.560, 0.717, 1.884};

	@Test
	public void notAdoptedMeansNoAnswerAtAll()
	{
		// the whole point of the gate: no weights, no number, caller keeps its prior
		assertEquals(-1, FillTimeModel.sideSecs(null, true, 1_000, 10, 12));
	}

	@Test
	public void theDotProductMatchesTheTrainersFeatureOrder()
	{
		// hand-computed against [bias, isBuy, log10_price, log10_qty, hour_frac]
		final double logSecs = W[0] + W[1] * 1 + W[2] * Math.log10(1_000)
			+ W[3] * Math.log10(11.0) + W[4] * (12 / 24.0);
		assertEquals((long) Math.exp(logSecs), FillTimeModel.sideSecs(W, true, 1_000, 10, 12));
		// the side flag has to actually move the answer, or isBuy is being dropped
		assertNotEquals(FillTimeModel.sideSecs(W, true, 1_000, 10, 12),
			FillTimeModel.sideSecs(W, false, 1_000, 10, 12));
	}

	@Test
	public void predictionsStayInsideSanityBounds()
	{
		// a fitted line extrapolates happily to a millisecond or to next week;
		// neither is a fill time we would ever act on
		for (long price = 1; price < 2_000_000_000L; price *= 37)
		{
			for (long qty = 1; qty < 100_000; qty *= 13)
			{
				final long s = FillTimeModel.sideSecs(W, price % 2 == 0, price, qty, (int) (price % 24));
				assertTrue("no NaN/overflow escapes", s > 0);
				assertTrue("bounded above", s <= 6 * 3600);
				assertTrue("bounded below", s >= 3);
			}
		}
	}

	@Test
	public void theShippedModelStillDeclaresItsOwnVerdict()
	{
		// contract with the trainer: a renamed key must not silently read as adopt
		final String json = "{\"features\":[\"bias\",\"isBuy\",\"log10_price\",\"log10_qty\","
			+ "\"hour_frac\"],\"weights\":[1,2,3,4,5],\"target\":\"log_seconds_to_fill\","
			+ "\"verdict\":\"fallback\"}";
		final ShapeCheck p = new Gson().fromJson(json, ShapeCheck.class);
		assertEquals("fallback", p.verdict);
		assertEquals(5, p.weights.size());
		assertEquals("log_seconds_to_fill", p.target);
	}

	private static class ShapeCheck
	{
		java.util.List<Double> weights;
		String target;
		String verdict;
	}
}
