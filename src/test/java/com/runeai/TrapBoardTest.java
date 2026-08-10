package com.runeai;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The two things that can break silently: the trap threshold, and the field-name
 * contract between sim/whale_trap_report.py and the Java board loader (a renamed
 * key deserializes to zeros with no error anywhere).
 */
public class TrapBoardTest
{
	@Test
	public void whalePrintOnAnEmptyBookIsATrap()
	{
		// the reported bug: a ~100gp hat with one 45k print and no volume
		assertTrue(FlipService.trapBook(100, 45_080, 0));
		// a real two-sided book is not, however wide the spread looks
		assertFalse(FlipService.trapBook(1_000, 1_050, 500));
		// a 3x spread WITH volume behind it is a market, not a trap
		assertFalse(FlipService.trapBook(154, 545, 400));
		// a quiet book with a normal spread is not a trap either
		assertFalse(FlipService.trapBook(5_000, 5_400, 2));
	}

	@Test
	public void coachedPriceIsCappedAtThreeTimesTheLowSide()
	{
		final long low = 100, high = 45_080;
		final long capped = Math.min(high - 1, (long) (low * FlipService.MAX_QUOTE_MULT));
		assertEquals(300, capped);
		// the cap must not bind on a healthy book
		assertEquals(1_049, Math.min(1_050 - 1, (long) (1_000 * FlipService.MAX_QUOTE_MULT)));
	}

	@Test
	public void boardTicketMatchesTheAnalysisOutput()
	{
		// one row exactly as sim/whale_trap_report.py writes it
		final String json = "{\"id\":25019,\"name\":\"Trailblazer top (t2)\",\"strikes\":1,"
			+ "\"lastPrint\":1786327057,\"ageDays\":0.5,\"real\":122,\"medPaid\":400000,"
			+ "\"maxPaid\":400000,\"medOverpay\":399878,\"listAt\":390000,"
			+ "\"corridor\":\"league cosmetics\",\"payoffX\":3278.7}";
		final TrapBoard.Ticket t = new Gson().fromJson(json, TrapBoard.Ticket.class);
		assertEquals(25019, t.getId());
		assertEquals("Trailblazer top (t2)", t.getName());
		assertEquals(122, t.getReal());
		assertEquals(400_000, t.getMedPaid());
		assertEquals(390_000, t.getListAt());
		assertEquals("league cosmetics", t.getCorridor());
		assertEquals(3278.7, t.getPayoffX(), 0.01);
		// the ask has to sit UNDER the number the whale typed or it never crosses
		assertTrue(t.getListAt() < t.getMedPaid());
	}
}
