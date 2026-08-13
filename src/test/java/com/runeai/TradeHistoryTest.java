package com.runeai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The audit's arithmetic. The point of an audit is that it can FAIL, so these
 * check both directions: real disagreement is reported with the right sign, and
 * agreement — including the sell-side tax convention we are not entitled to
 * assume — is not reported as disagreement.
 *
 * <p>The parser tests deliberately do not assert a wording. The exact row text
 * is only knowable from a live client, so what is locked down is the behaviour
 * that matters either way: an unreadable row returns null instead of a guess.
 */
public class TradeHistoryTest
{
	private static TradeHistory.Entry buy(int id, int qty, long price)
	{
		return new TradeHistory.Entry(id, qty, price, true);
	}

	private static TradeHistory.Entry sell(int id, int qty, long price)
	{
		return new TradeHistory.Entry(id, qty, price, false);
	}

	/**
	 * The audit's own failure mode, and the one that actually bit: on a fresh
	 * install the game lists trades from before RuneAI existed, every one of them
	 * comes back UNBOOKED, and the player is told their ledger is millions out
	 * because of trades it was never asked to book.
	 */
	@Test
	public void tradesOlderThanOurLedgerAreNotOurDiscrepancies()
	{
		// the game remembers four trades; we have booked one of them
		final List<TradeHistory.Entry> game = Arrays.asList(
			buy(453, 1000, 150), sell(453, 1000, 160), buy(561, 500, 90), sell(561, 500, 100));
		final List<TradeHistory.Entry> booked = Collections.singletonList(sell(561, 500, 100));
		final List<TradeHistory.Discrepancy> diffs = TradeHistory.reconcile(game, booked);
		assertEquals(3, diffs.size()); // reconcile still reports everything it found

		final List<TradeHistory.Discrepancy> real =
			TradeHistory.auditable(diffs, game.size(), booked.size());
		assertTrue("nothing predating the ledger may reach the player", real.isEmpty());
		assertEquals(0, TradeHistory.netDelta(real));
		assertEquals(3, TradeHistory.predatingLedger(diffs, game.size(), booked.size()));
	}

	/** The filter must not become a way for real holes to disappear. */
	@Test
	public void aGapInsideOurOwnLedgerIsStillReported()
	{
		final List<TradeHistory.Entry> game = Arrays.asList(
			sell(453, 1000, 160), sell(561, 500, 100));
		// two booked offers, so nothing is old enough to be excused
		final List<TradeHistory.Entry> booked = Arrays.asList(
			sell(453, 1000, 160), sell(999, 1, 1));
		final List<TradeHistory.Discrepancy> diffs = TradeHistory.reconcile(game, booked);
		final List<TradeHistory.Discrepancy> real =
			TradeHistory.auditable(diffs, game.size(), booked.size());
		assertEquals(1, real.size());
		assertEquals(TradeHistory.Kind.UNBOOKED, real.get(0).getKind());
		assertEquals(561, real.get(0).getItemId());
		assertEquals(50_000, TradeHistory.netDelta(real)); // a sell we never counted
	}

	/** A price disagreement is never "old" — only unbooked rows can predate us. */
	@Test
	public void onlyUnbookedRowsCanBeExcusedAsOld()
	{
		final List<TradeHistory.Entry> game = Arrays.asList(
			sell(453, 1000, 170), buy(561, 500, 90), buy(562, 500, 90));
		final List<TradeHistory.Entry> booked = Collections.singletonList(sell(453, 1000, 160));
		final List<TradeHistory.Discrepancy> diffs = TradeHistory.reconcile(game, booked);
		final List<TradeHistory.Discrepancy> real =
			TradeHistory.auditable(diffs, game.size(), booked.size());
		assertEquals(1, real.size());
		assertEquals(TradeHistory.Kind.PRICE, real.get(0).getKind());
	}

	@Test
	public void anAgreeingLedgerReportsNothing()
	{
		final List<TradeHistory.Entry> game = Arrays.asList(buy(453, 1000, 150), sell(453, 1000, 160));
		final List<TradeHistory.Entry> booked = Arrays.asList(buy(453, 1000, 150), sell(453, 1000, 160));
		assertTrue(TradeHistory.reconcile(game, booked).isEmpty());
		assertEquals(0, TradeHistory.netDelta(TradeHistory.reconcile(game, booked)));
	}

	@Test
	public void aSellThatFilledHigherThanWeBookedIsMoneyWeUnderCounted()
	{
		// we booked the ask; the buyer's standing offer was higher
		final List<TradeHistory.Discrepancy> ds = TradeHistory.reconcile(
			Collections.singletonList(sell(453, 1000, 165)),
			Collections.singletonList(sell(453, 1000, 160)));
		assertEquals(1, ds.size());
		assertEquals(TradeHistory.Kind.PRICE, ds.get(0).getKind());
		assertEquals("5 gp x 1000 that our P&L never counted", 5_000, ds.get(0).getGpDelta());
	}

	@Test
	public void aBuyThatCostMoreThanWeBookedIsProfitWeOverstated()
	{
		// same absolute gap, opposite sign: a cost we under-counted overstated profit
		final List<TradeHistory.Discrepancy> ds = TradeHistory.reconcile(
			Collections.singletonList(buy(453, 1000, 155)),
			Collections.singletonList(buy(453, 1000, 150)));
		assertEquals(1, ds.size());
		assertEquals(-5_000, ds.get(0).getGpDelta());
	}

	@Test
	public void theSellSideTaxConventionIsNotAssumedEitherWay()
	{
		// the interface may quote the ask or the coins that landed after the 2%;
		// both readings of the same trade have to count as agreement
		final long ask = 1_000;
		final long afterTax = ask - FlipService.geTax((int) ask);
		assertTrue(TradeHistory.priceAgrees(ask, ask, false));
		assertTrue(TradeHistory.priceAgrees(afterTax, ask, false));
		// a buy is never taxed, so there is no second reading to allow
		assertFalse(TradeHistory.priceAgrees(afterTax, ask, true));
		// and it is not a licence to accept any nearby number
		assertFalse(TradeHistory.priceAgrees(ask - 100, ask, false));
		assertTrue(TradeHistory.reconcile(
			Collections.singletonList(sell(453, 5, afterTax)),
			Collections.singletonList(sell(453, 5, ask))).isEmpty());
	}

	@Test
	public void aTradeWeNeverBookedIsReportedWhole()
	{
		final List<TradeHistory.Discrepancy> ds = TradeHistory.reconcile(
			Collections.singletonList(sell(453, 100, 200)),
			Collections.singletonList(buy(999, 1, 1)));
		assertEquals(1, ds.size());
		assertEquals(TradeHistory.Kind.UNBOOKED, ds.get(0).getKind());
		assertEquals(20_000, ds.get(0).getGpDelta());
		// and a purchase we never booked is money out, not in
		assertEquals(-20_000, TradeHistory.reconcile(
			Collections.singletonList(buy(453, 100, 200)),
			Collections.singletonList(sell(999, 1, 1))).get(0).getGpDelta());
	}

	@Test
	public void quantityDisagreementIsItsOwnFinding()
	{
		final List<TradeHistory.Discrepancy> ds = TradeHistory.reconcile(
			Collections.singletonList(sell(453, 100, 200)),   // game: 100 sold
			Collections.singletonList(sell(453, 60, 200)));   // us: 60 booked
		assertEquals(1, ds.size());
		assertEquals(TradeHistory.Kind.QTY, ds.get(0).getKind());
		assertEquals(8_000, ds.get(0).getGpDelta());
	}

	@Test
	public void eachBookedOfferIsMatchedAtMostOnce()
	{
		// two identical sells in the game's history, one in ours: exactly one of
		// them is unbooked, and the other must not be reported as well
		final List<TradeHistory.Discrepancy> ds = TradeHistory.reconcile(
			Arrays.asList(sell(453, 10, 100), sell(453, 10, 100)),
			Collections.singletonList(sell(453, 10, 100)));
		assertEquals(1, ds.size());
		assertEquals(TradeHistory.Kind.UNBOOKED, ds.get(0).getKind());
	}

	@Test
	public void emptyInputsAreNotFindings()
	{
		assertTrue(TradeHistory.reconcile(new ArrayList<>(), new ArrayList<>()).isEmpty());
		assertTrue(TradeHistory.reconcile(null, null).isEmpty());
		assertTrue(TradeHistory.reconcile(new ArrayList<>(),
			Collections.singletonList(sell(1, 1, 1))).isEmpty());
	}

	@Test
	public void anUnreadableRowIsNullRatherThanAGuess()
	{
		// no direction anywhere in the text
		assertNull(TradeHistory.parseRow(453, 100, Arrays.asList("Coal", "150 coins each")));
		// direction but no price
		assertNull(TradeHistory.parseRow(453, 100, Arrays.asList("Bought", "Coal")));
		// no item behind the row at all
		assertNull(TradeHistory.parseRow(0, 100, Arrays.asList("Sold", "150 coins each")));
		assertNull(TradeHistory.parseRow(453, 100, null));
	}

	@Test
	public void aRowStatingPricePerItemIsRead()
	{
		final TradeHistory.Entry e =
			TradeHistory.parseRow(453, 1000, Arrays.asList("<col=ff9040>Coal</col>",
				"Sold", "150 coins each"));
		assertEquals(453, e.getItemId());
		assertEquals(1000, e.getQty());
		assertEquals(150, e.getUnitPrice());
		assertFalse(e.isBought());
	}

	@Test
	public void aRowStatingOnlyATotalIsDividedBackToTheUnitPrice()
	{
		final TradeHistory.Entry e =
			TradeHistory.parseRow(453, 1000, Arrays.asList("Coal", "Bought for 150,000 coins"));
		assertEquals(150, e.getUnitPrice());
		assertTrue(e.isBought());
	}

	@Test
	public void formattingTagsAndSeparatorsDoNotConfuseTheNumbers()
	{
		assertEquals(Arrays.asList(1_234_567L, 12L), TradeHistory.numbers("1,234,567 coins x12"));
		assertTrue(TradeHistory.numbers("no digits here").isEmpty());
		assertEquals("sold 1,000 coal", TradeHistory.plain("<col=00ff00>Sold</col>  1,000   Coal"));
	}
}
