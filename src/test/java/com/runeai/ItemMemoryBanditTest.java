package com.runeai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The line between "an item this player has traded" and "an item the price scan
 * happened to see".
 *
 * <p>{@code recordPredictionError} runs for every item in the wiki's latest dump
 * — several thousand of them, once a minute — and it creates a {@code Stats}
 * entry for each. That made {@code memory.get(id) != null} true for essentially
 * the whole game within one scan of login, which silently retired the bandit's
 * exploration bonus (nothing was ever "unknown" again) and turned the persisted
 * memory file into a multi-megabyte rewrite every sixty seconds.
 *
 * <p>{@link ItemMemory#traded} is the distinction both of those needed.
 */
public class ItemMemoryBanditTest
{
	/** What the scan loop leaves behind for an item nobody has traded. */
	private static ItemMemory.Stats scannedOnly()
	{
		final ItemMemory.Stats s = new ItemMemory.Stats();
		s.setEwmaPredErr(0.004);
		return s;
	}

	@Test
	public void aScanTouchedItemIsNotATradedItem()
	{
		assertFalse(ItemMemory.traded(scannedOnly()));
		assertFalse(ItemMemory.traded(new ItemMemory.Stats()));
		assertFalse(ItemMemory.traded(null));
	}

	@Test
	public void aFillAStallOrRealisedProfitAllCountAsTraded()
	{
		final ItemMemory.Stats filled = new ItemMemory.Stats();
		filled.setFills(1);
		assertTrue(ItemMemory.traded(filled));

		final ItemMemory.Stats pulled = new ItemMemory.Stats();
		pulled.setStalls(1);
		assertTrue(ItemMemory.traded(pulled));

		// a loss is a result too — it must survive the save filter or the item
		// comes back tomorrow with no memory of having burned this player
		final ItemMemory.Stats lost = new ItemMemory.Stats();
		lost.setTotalProfit(-4_000);
		assertTrue(ItemMemory.traded(lost));
	}

	/**
	 * The ranking consequence: an item the scan has merely seen must still rank
	 * as unexplored, exactly as it did before the memory map filled up with the
	 * whole game.
	 */
	@Test
	public void theExplorationBonusSurvivesTheScanLoop()
	{
		final ItemMemory mem = new ItemMemory(new com.google.gson.Gson());
		final int neverSeen = 1_000_001;
		final int scanned = 1_000_002;
		final double optimism = mem.scoreMultiplier(neverSeen);
		assertEquals("an item with no entry at all is the reference point",
			1.05, optimism, 1e-9);

		// one scan's predictability update — the only thing that touched it
		mem.recordPredictionError(scanned, 0.0);
		assertEquals("a scanned-but-untraded item ranks like an unknown one",
			optimism, mem.scoreMultiplier(scanned), 1e-9);
	}
}
