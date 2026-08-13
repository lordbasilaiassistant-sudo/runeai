package com.runeai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The flip AI's long-term memory: per-item outcomes learned from THIS
 * player's real trades, persisted forever. Exponentially-weighted stats
 * mean fresh results dominate and stale patterns fade — an item that
 * "works" keeps getting suggested until it measurably stops working.
 * This is online learning (a contextual bandit), running live.
 *
 * <p>Outcomes are booked per {@link FlipLane}: the aggregate speed/score fields
 * are the QUICK lane's bandit and only quick-lane results move them, while the
 * long lane keeps its own ledger. A patience order that filled overnight, or one
 * the player pulled, says nothing about how fast the item cycles in a session.
 *
 * <p>The persisted shape stays {@code {"<itemId>": Stats}} so older files load
 * unchanged; the lane ledgers are new fields inside {@code Stats}.
 */
@Slf4j
@Singleton
class ItemMemory
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "item-memory.json");
	private static final double ALPHA = 0.3;            // EWMA: recent flips dominate
	private static final long STALL_PENALTY_MS = 20 * 60_000;
	/**
	 * Consecutive healthy scans that end a cooldown early. At one scan a minute
	 * this is three minutes of the book measurably cycling for a profit — the
	 * point of the rule is that the cooldown ends on EVIDENCE, not on a clock.
	 */
	private static final int RECOVERY_SCANS = 3;

	private final Gson gson;
	private final Map<Integer, Stats> memory = new ConcurrentHashMap<>();
	private volatile boolean dirty;

	/** One lane's outcome ledger for one item. */
	@Data
	static class Lane
	{
		int fills;
		int stalls;
		int wins;               // completed sells that made money
		int losses;             // completed sells that lost money
		double ewmaFillSecs = 300;
		long totalProfit;
		long lastFillMs;
	}

	@Data
	static class Stats
	{
		int fills;              // completed offers
		int stalls;             // cancels / gave-up
		double ewmaFillSecs = 300;
		double ewmaProfit;      // per completed sell
		long lastFillMs;
		long lastStallMs;
		long totalProfit;
		double ewmaPredErr;     // market predictability: how much mid moves vs our forecast
		long lastFillSecs;      // the last DURATION actually measured, not the EWMA
		long cooldownClearedMs; // a measured recovery after lastStallMs lifts the penalty
		int healthyScans;       // consecutive scans showing a profitable, liquid book
		int recoveries;         // how many times this item earned its way back
		Lane quick = new Lane();
		Lane overnight = new Lane();
	}

	@Inject
	ItemMemory(Gson gson)
	{
		this.gson = gson;
		load();
	}

	private Lane laneOf(Stats s, FlipLane lane)
	{
		if (lane == FlipLane.LONG)
		{
			if (s.overnight == null)
			{
				s.overnight = new Lane();
			}
			return s.overnight;
		}
		if (s.quick == null)
		{
			s.quick = new Lane();
		}
		return s.quick;
	}

	void recordFill(int itemId, FlipLane lane, long fillSecs, long profit)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		final Lane l = laneOf(s, lane);
		s.fills++;
		l.fills++;
		if (profit > 0)
		{
			l.wins++;
		}
		else if (profit < 0)
		{
			l.losses++;
		}
		if (fillSecs >= 0) // offline fills (-1): unknown duration, skip speed stats
		{
			l.ewmaFillSecs = l.ewmaFillSecs * (1 - ALPHA) + fillSecs * ALPHA;
			// only the quick lane's own results are allowed to move the speed
			// number the quick ranker reads
			if (lane == FlipLane.QUICK)
			{
				s.ewmaFillSecs = s.ewmaFillSecs * (1 - ALPHA) + fillSecs * ALPHA;
				s.lastFillSecs = fillSecs;
			}
		}
		if (profit != 0)
		{
			s.ewmaProfit = s.ewmaProfit * (1 - ALPHA) + profit * ALPHA;
			s.totalProfit += profit;
			l.totalProfit += profit;
		}
		s.lastFillMs = System.currentTimeMillis();
		l.lastFillMs = s.lastFillMs;
		save();
	}

	/** Negative reward: the market moved against our forecast for this item. */
	void recordPredictionError(int itemId, double relErr)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		s.ewmaPredErr = s.ewmaPredErr * 0.7 + relErr * 0.3;
		dirty = true; // scan-rate update: one file write per scan, not per item
	}

	/**
	 * Has this player actually traded the item, or is the entry just the scan
	 * loop's predictability bookkeeping?
	 *
	 * <p>{@link #recordPredictionError} runs for EVERY item in the wiki's latest
	 * dump — several thousand of them, every 60 seconds — so within a minute of
	 * startup {@code memory.get(id)} is non-null for essentially the whole game.
	 * That is fine as a live signal and wrong as an answer to "do we know this
	 * item": it is what silently retired the bandit's exploration bonus, and it is
	 * why {@link #save} refuses to persist these rows.
	 */
	static boolean traded(Stats s)
	{
		return s != null && (s.fills > 0 || s.stalls > 0 || s.totalProfit != 0);
	}

	void recordStall(int itemId, FlipLane lane)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		final Lane l = laneOf(s, lane);
		s.stalls++;
		l.stalls++;
		if (lane == FlipLane.QUICK)
		{
			// a quick pick that had to be pulled IS slow-signal; an abandoned
			// patience order is not, so only the quick lane pays this penalty
			s.ewmaFillSecs = s.ewmaFillSecs * (1 - ALPHA) + 900 * ALPHA;
			s.lastStallMs = System.currentTimeMillis();
		}
		save();
	}

	/**
	 * Recovery-based cooldown expiry — the fix for "reliable items never resurface".
	 *
	 * <p>A stall used to demote an item for a fixed twenty minutes AND push its
	 * fill-time estimate toward fifteen minutes, so a demoted item was rarely
	 * suggested, rarely got a fresh fill, and therefore never healed. Now the live
	 * scan reports whether the item's book is cycling profitably again, and a run
	 * of {@link #RECOVERY_SCANS} healthy scans ends the cooldown and pulls the
	 * stall-inflated estimate back toward the last duration actually MEASURED.
	 *
	 * @param healthy fresh two-sided book, above the liquidity floor, positive net
	 *                after tax, and a cycle estimate inside the quick ceiling
	 */
	void recordBookHealth(int itemId, boolean healthy)
	{
		final Stats s = memory.get(itemId);
		if (s == null)
		{
			return; // never traded: nothing to recover from
		}
		if (!healthy)
		{
			if (s.healthyScans != 0)
			{
				s.healthyScans = 0;
				dirty = true;
			}
			return;
		}
		if (s.lastStallMs <= s.cooldownClearedMs)
		{
			return; // not cooling down
		}
		s.healthyScans++;
		dirty = true;
		if (s.healthyScans < RECOVERY_SCANS)
		{
			return;
		}
		s.healthyScans = 0;
		s.recoveries++;
		s.cooldownClearedMs = System.currentTimeMillis();
		// the 900s stall penalty was a guess about the future; the book just
		// disagreed with it, so stop carrying it as if it were measurement
		s.ewmaFillSecs = s.lastFillSecs > 0
			? (s.ewmaFillSecs + s.lastFillSecs) / 2
			: Math.min(s.ewmaFillSecs, 300);
		log.debug("item {} recovered: cooldown lifted, fill estimate back to {}s",
			itemId, (long) s.ewmaFillSecs);
	}

	/** True while a stall penalty is still in force and unrecovered. */
	boolean isCoolingDown(int itemId)
	{
		final Stats s = memory.get(itemId);
		return s != null && coolingDown(s, System.currentTimeMillis());
	}

	private static boolean coolingDown(Stats s, long now)
	{
		return s.lastStallMs > s.cooldownClearedMs && now - s.lastStallMs < STALL_PENALTY_MS;
	}

	/**
	 * Bandit multiplier on the market-math score:
	 *  - proven fast + profitable for this player -> boosted (exploit)
	 *  - recently stalled -> demoted hard until it re-proves (adapt)
	 *  - never tried -> small optimism bonus (explore)
	 */
	double scoreMultiplier(int itemId)
	{
		final Stats s = memory.get(itemId);
		if (s == null)
		{
			return 1.05; // exploration optimism for the unknown
		}
		// an entry the scan loop created for its predictability EWMA is not a
		// traded item, and must not read as one — otherwise every item on the
		// board becomes "known" one scan after login and nothing is ever explored
		double m = traded(s) ? 1.0 : 1.05;
		final long now = System.currentTimeMillis();
		if (coolingDown(s, now))
		{
			m *= 0.25; // it stopped working — back off until it re-proves
		}
		if (s.fills >= 2)
		{
			// confidence-weighted speed factor: measured 60s fills ~ x2,
			// measured 10-minute fills ~ x0.4
			final double conf = s.fills / (s.fills + 3.0);
			final double speed = Math.max(0.3, Math.min(2.0, 240.0 / Math.max(30, s.ewmaFillSecs)));
			m *= (1 - conf) + conf * speed;
			if (s.ewmaProfit > 0)
			{
				m *= 1.15; // it has actually paid this player before
			}
			if (s.totalProfit < 0)
			{
				m *= 0.5;  // it has actually burned this player before
			}
		}
		// unpredictable markets are where margins evaporate mid-flip:
		// >3% average surprise between scans halves the score
		if (s.ewmaPredErr > 0)
		{
			m *= 1.0 / (1.0 + 15.0 * s.ewmaPredErr);
		}
		return m;
	}

	/** Measured seconds for one full cycle of this item, or -1 with no evidence. */
	long observedCycleSecs(int itemId)
	{
		final Stats s = memory.get(itemId);
		if (s == null || s.fills < 2 || s.ewmaFillSecs <= 0)
		{
			return -1;
		}
		return (long) (s.ewmaFillSecs * 2);
	}

	Stats statsFor(int itemId)
	{
		return memory.get(itemId);
	}

	/**
	 * One item's line in the per-item results view: what this player actually got
	 * out of it, not what the market says it is worth.
	 */
	@lombok.Value
	static class ItemStat
	{
		int itemId;
		String name;
		int fills;
		int stalls;
		long avgFillSecs;   // -1 when no fill was ever timed (offline fills only)
		long totalProfit;
		int quickFills;
		int longFills;
		long quickProfit;
		long longProfit;
	}

	/**
	 * The per-item results table, ranked by what each item has actually paid.
	 *
	 * <p>Static and fed its own map so the ranking can be tested without a
	 * client, a data directory, or this player's real trade history.
	 *
	 * @param namer item id -> display name; the caller owns that lookup because
	 *              it needs the client
	 */
	static java.util.List<ItemStat> rank(Map<Integer, Stats> mem, int limit,
		java.util.function.IntFunction<String> namer)
	{
		final java.util.List<Map.Entry<Integer, Stats>> traded = new java.util.ArrayList<>();
		for (Map.Entry<Integer, Stats> e : mem.entrySet())
		{
			final Stats s = e.getValue();
			if (s != null && (s.fills > 0 || s.stalls > 0))
			{
				traded.add(e);
			}
		}
		traded.sort(java.util.Comparator
			.comparingLong((Map.Entry<Integer, Stats> e) -> e.getValue().totalProfit).reversed()
			.thenComparing(e -> -e.getValue().fills));
		final java.util.List<ItemStat> out = new java.util.ArrayList<>();
		// name lookups need the client, so only the rows that will be SHOWN pay for
		// one — this runs on the tick loop against every item ever traded
		for (Map.Entry<Integer, Stats> e : traded.subList(0, Math.min(Math.max(0, limit), traded.size())))
		{
			final Stats s = e.getValue();
			final Lane q = s.quick == null ? new Lane() : s.quick;
			final Lane l = s.overnight == null ? new Lane() : s.overnight;
			// the aggregate EWMA is the QUICK lane's clock by construction, so an
			// item only ever traded overnight reports the long lane's number
			// instead of a 300s default it never earned
			final long avg = q.fills > 0 ? (long) s.ewmaFillSecs
				: l.fills > 0 && l.ewmaFillSecs > 0 ? (long) l.ewmaFillSecs
				: -1;
			out.add(new ItemStat(e.getKey(), namer.apply(e.getKey()), s.fills, s.stalls,
				avg, s.totalProfit, q.fills, l.fills, q.totalProfit, l.totalProfit));
		}
		return out;
	}

	/** Live per-item results for the panel. */
	java.util.List<ItemStat> topItems(int limit, java.util.function.IntFunction<String> namer)
	{
		return rank(memory, limit, namer);
	}

	/** Whole-lane totals for the ledger the panel and overlay show. */
	Lane laneTotals(FlipLane lane)
	{
		final Lane out = new Lane();
		out.ewmaFillSecs = 0;
		double secsWeight = 0;
		for (Stats s : memory.values())
		{
			final Lane l = laneOf(s, lane);
			out.fills += l.fills;
			out.stalls += l.stalls;
			out.wins += l.wins;
			out.losses += l.losses;
			out.totalProfit += l.totalProfit;
			if (l.fills > 0)
			{
				out.ewmaFillSecs += l.ewmaFillSecs * l.fills;
				secsWeight += l.fills;
				out.lastFillMs = Math.max(out.lastFillMs, l.lastFillMs);
			}
		}
		out.ewmaFillSecs = secsWeight > 0 ? out.ewmaFillSecs / secsWeight : 0;
		return out;
	}

	/** One write per price scan for the scan-rate updates that set {@link #dirty}. */
	void flush()
	{
		if (dirty)
		{
			dirty = false;
			save();
		}
	}

	private void load()
	{
		try
		{
			if (FILE.exists())
			{
				final Map<String, Stats> raw = gson.fromJson(
					new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8),
					new TypeToken<Map<String, Stats>>(){}.getType());
				for (Map.Entry<String, Stats> e : raw.entrySet())
				{
					memory.put(Integer.parseInt(e.getKey()), e.getValue());
				}
				log.info("item memory loaded: {} items", memory.size());
			}
		}
		catch (Exception ex)
		{
			log.warn("item memory load failed", ex);
		}
	}

	/**
	 * Persist the items this player has actually traded.
	 *
	 * <p>Two things are deliberate. Only {@link #traded} rows are written — the
	 * scan loop touches every item on the wiki every minute, and persisting those
	 * turned a few-KB file into a multi-megabyte rewrite once a minute, forever.
	 * The predictability EWMA on an untraded item is worth exactly one session and
	 * is rebuilt within a scan of the next one.
	 *
	 * <p>And the write itself is handed to {@link AsyncWriter}: this is called
	 * from {@code recordFill}, which runs on the client thread inside a GE event.
	 */
	private synchronized void save()
	{
		try
		{
			final Map<String, Stats> raw = new java.util.HashMap<>();
			for (Map.Entry<Integer, Stats> e : memory.entrySet())
			{
				if (traded(e.getValue()))
				{
					raw.put(String.valueOf(e.getKey()), e.getValue());
				}
			}
			AsyncWriter.write(FILE, gson.toJson(raw));
		}
		catch (Exception ex)
		{
			log.warn("item memory save failed", ex);
		}
	}
}
