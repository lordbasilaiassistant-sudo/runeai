package com.runeai;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The evolved policy genome from {@code sim/self_play.py}, read from
 * {@code ~/.runelite/runeai/ge-champion.json}. Local file, never committed.
 *
 * <p>These parameters replace hand-tuned constants in the ranker. Every getter
 * falls back to the constant it replaced, so a missing file, an unreadable one,
 * or a champion that showed no edge leaves the plugin exactly as it was.
 *
 * <p>Gated on {@code emergent_edge} — the champion beating the default policy on
 * a held-out future window it never evolved on. The file also records the arena
 * it won on ({@code items} x {@code bars}); a crown won on a toy arena is
 * rejected by the trainer before it ever reaches this file.
 *
 * <p><b>Horizon caveat, and it is not a footnote.</b> The league evolves on
 * HOURLY bars: one "cycle" there is an hour. Parameters that are pure shares —
 * {@code vol_share}, {@code conc} — carry over unchanged because they have no
 * time in them. {@code roi_floor} does: 2.4% per hourly bar is a rate of return,
 * and demanding that same 2.4% from a forty-second cycle would delete the entire
 * quick lane. It is converted pro-rata to the cycle it is applied to
 * ({@link #roiFloorFor(long)}) rather than adopted raw or silently dropped.
 */
@Slf4j
@Singleton
class Champion
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "ge-champion.json");
	private static final long RECHECK_MS = 5 * 60_000;
	private static final long LEAGUE_BAR_SECS = 3600;

	// the hand-tuned constants, kept as the fallbacks they always were
	static final double DEFAULT_MOM_BUY_CUT = -0.01;
	static final double DEFAULT_MOM_SPIKE_CUT = 0.05;
	static final double DEFAULT_VOL_SHARE = 0.05;
	static final double DEFAULT_ROI_FLOOR = 0.0;

	private final Gson gson;
	private long lastCheck;
	private long loadedStamp = -1;
	private volatile Genome genome;
	private volatile String status = "unloaded";

	private static class Payload
	{
		Genome genome;
		boolean emergent_edge;
		double test_pnl;
		double default_test_pnl;
		int items;
		int bars;
	}

	private static class Genome
	{
		double roi_floor;
		double mom_buy_cut;
		double mom_spike_cut;
		double vol_share;
		double conc;
		double hold_bars;
	}

	@Inject
	Champion(Gson gson)
	{
		this.gson = gson;
	}

	boolean adopted()
	{
		return genome != null;
	}

	String status()
	{
		return status;
	}

	/** Read (or re-read) the champion. Off the client thread only — disk IO. */
	synchronized void ensureLoaded()
	{
		final long now = System.currentTimeMillis();
		if (now - lastCheck < RECHECK_MS)
		{
			return;
		}
		lastCheck = now;
		try
		{
			if (!FILE.exists())
			{
				// deleting the file un-adopts the genome — every getter falls
				// back to the constant it replaced, as the gate rule requires
				genome = null;
				loadedStamp = -1;
				status = "no champion file";
				return;
			}
			final long stamp = FILE.lastModified();
			if (stamp == loadedStamp)
			{
				return;
			}
			loadedStamp = stamp;
			final Payload p = gson.fromJson(
				new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), Payload.class);
			if (p == null || p.genome == null)
			{
				genome = null;
				status = "unreadable";
				return;
			}
			if (!p.emergent_edge)
			{
				genome = null;
				status = "verdict: no edge over the default policy";
				log.info("champion genome present but not adopted — evolution overfit");
				return;
			}
			// a genome of zeros deserialized from a renamed key would silently mean
			// "no floor, no volume, no capital" — refuse it rather than trade on it
			if (p.genome.vol_share <= 0 || p.genome.conc <= 0)
			{
				genome = null;
				status = "genome missing vol_share/conc";
				log.warn("champion genome has no usable shares — key names may have changed");
				return;
			}
			genome = p.genome;
			status = String.format("adopted (held-out %,.0f gp vs default %,.0f on %d items x %d bars)",
				p.test_pnl, p.default_test_pnl, p.items, p.bars);
			log.info("champion genome {} — roi_floor {} /h, vol_share {}, conc {}",
				status, p.genome.roi_floor, p.genome.vol_share, p.genome.conc);
		}
		catch (Exception ex)
		{
			genome = null;
			status = "load failed";
			log.warn("champion load failed", ex);
		}
	}

	/** Momentum below this is a falling knife and the policy will not buy into it. */
	double momBuyCut()
	{
		return genome == null ? DEFAULT_MOM_BUY_CUT : genome.mom_buy_cut;
	}

	/** Momentum above this is a spike likely to revert before the sell fills. */
	double momSpikeCut()
	{
		return genome == null ? DEFAULT_MOM_SPIKE_CUT : genome.mom_spike_cut;
	}

	/** Share of an item's traded volume this policy is willing to be. */
	double volShare()
	{
		return genome == null ? DEFAULT_VOL_SHARE : genome.vol_share;
	}

	/** Share of the bankroll a single position may take. */
	double conc(double fallback)
	{
		return genome == null ? fallback : genome.conc;
	}

	/**
	 * The ROI floor converted from the league's hourly bar to the cycle it is
	 * being applied to, so it stays the same RATE of return rather than becoming
	 * an impossible per-cycle demand. Capped at the raw floor: a cycle longer than
	 * an hour never has to clear more than the champion itself did.
	 */
	double roiFloorFor(long cycleSecs)
	{
		if (genome == null)
		{
			return DEFAULT_ROI_FLOOR;
		}
		final double scaled = genome.roi_floor * Math.max(1, cycleSecs) / (double) LEAGUE_BAR_SECS;
		return Math.min(genome.roi_floor, scaled);
	}
}
