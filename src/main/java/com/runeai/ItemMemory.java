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
 */
@Slf4j
@Singleton
class ItemMemory
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "item-memory.json");
	private static final double ALPHA = 0.3;            // EWMA: recent flips dominate
	private static final long STALL_PENALTY_MS = 20 * 60_000;

	private final Gson gson;
	private final Map<Integer, Stats> memory = new ConcurrentHashMap<>();

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
	}

	@Inject
	ItemMemory(Gson gson)
	{
		this.gson = gson;
		load();
	}

	void recordFill(int itemId, long fillSecs, long profit)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		s.fills++;
		if (fillSecs >= 0) // offline fills (-1): unknown duration, skip speed stats
		{
			s.ewmaFillSecs = s.ewmaFillSecs * (1 - ALPHA) + fillSecs * ALPHA;
		}
		if (profit != 0)
		{
			s.ewmaProfit = s.ewmaProfit * (1 - ALPHA) + profit * ALPHA;
			s.totalProfit += profit;
		}
		s.lastFillMs = System.currentTimeMillis();
		save();
	}

	/** Negative reward: the market moved against our forecast for this item. */
	void recordPredictionError(int itemId, double relErr)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		s.ewmaPredErr = s.ewmaPredErr * 0.7 + relErr * 0.3;
		save();
	}

	void recordStall(int itemId)
	{
		final Stats s = memory.computeIfAbsent(itemId, k -> new Stats());
		s.stalls++;
		s.ewmaFillSecs = s.ewmaFillSecs * (1 - ALPHA) + 900 * ALPHA; // a stall = slow signal
		s.lastStallMs = System.currentTimeMillis();
		save();
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
		double m = 1.0;
		final long now = System.currentTimeMillis();
		if (now - s.lastStallMs < STALL_PENALTY_MS)
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

	Stats statsFor(int itemId)
	{
		return memory.get(itemId);
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

	private synchronized void save()
	{
		try
		{
			FILE.getParentFile().mkdirs();
			final Map<String, Stats> raw = new java.util.HashMap<>();
			for (Map.Entry<Integer, Stats> e : memory.entrySet())
			{
				raw.put(String.valueOf(e.getKey()), e.getValue());
			}
			Files.write(FILE.toPath(), gson.toJson(raw).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			log.warn("item memory save failed", ex);
		}
	}
}
