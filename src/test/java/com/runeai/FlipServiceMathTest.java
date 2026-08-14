package com.runeai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The two pieces of pure arithmetic the tracking overhaul leans on: the
 * breakeven floor under every sell suggestion, and the insta-cross margin the
 * quick lane is now honest about. Both are static so they test without a
 * client, a book, or a basis file.
 */
public class FlipServiceMathTest
{
	@Test
	public void breakevenAlwaysReturnsMoreThanWasPaid()
	{
		// the one property that IS the feature: selling at breakeven never loses
		for (long avg : new long[]{1, 10, 49, 50, 100, 999, 12_345, 1_000_000, 300_000_000L})
		{
			final long be = FlipService.breakevenSell(avg);
			assertTrue("breakeven must clear the cost after tax (avg=" + avg + ")",
				be - FlipService.geTax(be) > avg);
			// and it is the FLOOR, not a padded number: one gp less fails
			assertTrue("one gp under breakeven must not clear the cost (avg=" + avg + ")",
				(be - 1) - FlipService.geTax(be - 1) <= avg);
		}
	}

	@Test
	public void breakevenBelowTheTaxFloorIsJustCostPlusOne()
	{
		// items under 50 gp are never taxed, so breakeven is cost + 1
		assertEquals(31, FlipService.breakevenSell(30));
	}

	@Test
	public void instaNetIsTheCrossedSpreadAfterTax()
	{
		// low (instant-sell) above high (instant-buy): a genuinely quick margin.
		// sell 1000 pays 980 after 2% tax, buy costs 900 -> +80
		assertEquals(80, FlipService.instaNet(1_000, 900));
		// normal book (low under high): crossing the spread pays negative
		assertTrue(FlipService.instaNet(900, 1_000) < 0);
		// sub-50gp items are untaxed either way
		assertEquals(5, FlipService.instaNet(45, 40));
	}
}
