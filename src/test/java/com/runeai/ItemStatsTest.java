package com.runeai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The per-item results table. What matters here is that it reports MEASUREMENTS:
 * an item that was never timed must not borrow the 300-second default the bandit
 * starts every item on, and an item only ever traded overnight must report the
 * long lane's clock rather than the quick lane's untouched prior.
 */
public class ItemStatsTest
{
	private static ItemMemory.Stats traded(int quickFills, double quickSecs, long profit)
	{
		final ItemMemory.Stats s = new ItemMemory.Stats();
		s.setFills(quickFills);
		s.setTotalProfit(profit);
		s.setEwmaFillSecs(quickSecs);
		final ItemMemory.Lane q = new ItemMemory.Lane();
		q.setFills(quickFills);
		q.setTotalProfit(profit);
		q.setEwmaFillSecs(quickSecs);
		s.setQuick(q);
		return s;
	}

	@Test
	public void rankedByWhatEachItemActuallyPaid()
	{
		final Map<Integer, ItemMemory.Stats> mem = new HashMap<>();
		mem.put(1, traded(3, 100, 5_000));
		mem.put(2, traded(9, 80, 40_000));
		mem.put(3, traded(2, 50, -1_200));
		final List<ItemMemory.ItemStat> top = ItemMemory.rank(mem, 10, id -> "item" + id);
		assertEquals(3, top.size());
		assertEquals(2, top.get(0).getItemId());
		assertEquals(1, top.get(1).getItemId());
		assertEquals("a loser still shows up — it is a result", 3, top.get(2).getItemId());
		assertEquals(40_000, top.get(0).getTotalProfit());
		assertEquals("item2", top.get(0).getName());
	}

	@Test
	public void theLimitIsRespectedAndOnlyShownRowsAreNamed()
	{
		final Map<Integer, ItemMemory.Stats> mem = new HashMap<>();
		for (int i = 1; i <= 20; i++)
		{
			mem.put(i, traded(1, 60, i * 100));
		}
		final int[] namedCalls = {0};
		final List<ItemMemory.ItemStat> top = ItemMemory.rank(mem, 5, id ->
		{
			namedCalls[0]++;
			return "item" + id;
		});
		assertEquals(5, top.size());
		assertEquals("naming needs the client — never pay for rows nobody sees", 5, namedCalls[0]);
		assertEquals(20, top.get(0).getItemId()); // highest profit first
	}

	@Test
	public void untradedItemsAreNotResults()
	{
		final Map<Integer, ItemMemory.Stats> mem = new HashMap<>();
		// prediction-error only: the ranker has an opinion, the player never traded it
		final ItemMemory.Stats opinionOnly = new ItemMemory.Stats();
		opinionOnly.setEwmaPredErr(0.05);
		mem.put(7, opinionOnly);
		assertTrue(ItemMemory.rank(mem, 10, id -> "x").isEmpty());

		// a pulled offer IS a result, even with no fill behind it
		final ItemMemory.Stats stalledOnly = new ItemMemory.Stats();
		stalledOnly.setStalls(2);
		mem.put(8, stalledOnly);
		final List<ItemMemory.ItemStat> out = ItemMemory.rank(mem, 10, id -> "x");
		assertEquals(1, out.size());
		assertEquals(8, out.get(0).getItemId());
		assertEquals(2, out.get(0).getStalls());
	}

	@Test
	public void anUntimedItemReportsUntimedRatherThanTheDefault()
	{
		final Map<Integer, ItemMemory.Stats> mem = new HashMap<>();
		// offline fills only: money booked, duration never measured, so the
		// aggregate EWMA is still sitting on its untouched 300s starting value
		final ItemMemory.Stats offlineOnly = new ItemMemory.Stats();
		offlineOnly.setFills(4);
		offlineOnly.setTotalProfit(9_000);
		mem.put(11, offlineOnly);
		assertEquals(-1, ItemMemory.rank(mem, 10, id -> "x").get(0).getAvgFillSecs());
	}

	@Test
	public void anOvernightOnlyItemReportsTheLongLaneClock()
	{
		final Map<Integer, ItemMemory.Stats> mem = new HashMap<>();
		final ItemMemory.Stats s = new ItemMemory.Stats();
		s.setFills(2);
		s.setTotalProfit(250_000);
		final ItemMemory.Lane overnight = new ItemMemory.Lane();
		overnight.setFills(2);
		overnight.setTotalProfit(250_000);
		overnight.setEwmaFillSecs(28_800); // eight hours: a success in this lane
		s.setOvernight(overnight);
		mem.put(21, s);
		final ItemMemory.ItemStat stat = ItemMemory.rank(mem, 10, id -> "x").get(0);
		assertEquals(28_800, stat.getAvgFillSecs());
		assertEquals(0, stat.getQuickFills());
		assertEquals(2, stat.getLongFills());
		assertEquals(250_000, stat.getLongProfit());
	}
}
