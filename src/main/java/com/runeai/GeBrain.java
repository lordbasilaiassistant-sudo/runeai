package com.runeai;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The backtrained price brain from {@code sim/ge_backtrainer.py}: a 7-16-1 tanh
 * net plus a learned per-item bias, read from
 * {@code ~/.runelite/runeai/ge-brain-hist.json}. Local file, never committed.
 *
 * <p>The net predicts a RESIDUAL on top of the martingale (log high/low), not a
 * price level — it starts at the baseline and can only earn its way above it.
 * The feature vector and the forward pass here mirror {@code features()} and
 * {@code Net.forward()} in {@code sim/ge_rl_trainer.py} exactly; if those change,
 * this must change with them or the weights are being fed the wrong columns.
 *
 * <p><b>It moves ranking, never a coached price.</b> Every number the plugin
 * reads back to the player stays anchored to the live book and its 3x cap. The
 * brain was trained on 1h and 5m bars while the live loop scans every 60s, and
 * that horizon gap is unresolved (see commit 98a58fa) — good enough to reorder
 * candidates, not to originate a price a human types into the GE.
 *
 * <p>Gated on the file's own {@code metrics.beats_baseline}. No file, no metrics,
 * or a losing verdict means {@link #adopted()} is false and every caller keeps
 * the behaviour it had before.
 */
@Slf4j
@Singleton
class GeBrain
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "ge-brain-hist.json");
	private static final long RECHECK_MS = 5 * 60_000;
	private static final int IN = 7;

	private final Gson gson;
	private long lastCheck;
	private long loadedStamp = -1;
	private volatile Net net;
	private volatile String status = "unloaded";

	private static class Payload
	{
		Net net;
		Metrics metrics;
	}

	private static class Metrics
	{
		boolean beats_baseline;
		double net_mse;
		double martingale_mse;
		int test;
	}

	private static class Net
	{
		double[][] W1;
		double[] b1;
		double[][] W2;
		double[] b2;
		double gain = 1.0;
		Map<String, Double> item_bias;
	}

	@Inject
	GeBrain(Gson gson)
	{
		this.gson = gson;
	}

	boolean adopted()
	{
		return net != null;
	}

	String status()
	{
		return status;
	}

	/** Read (or re-read) the brain. Off the client thread only — this is disk IO. */
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
				status = "no brain file";
				return;
			}
			final long stamp = FILE.lastModified();
			if (stamp == loadedStamp)
			{
				return; // unchanged since the last read
			}
			loadedStamp = stamp;
			final Payload p = gson.fromJson(
				new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), Payload.class);
			if (p == null || p.net == null || p.metrics == null)
			{
				net = null;
				status = "unreadable";
				return;
			}
			if (!p.metrics.beats_baseline)
			{
				net = null;
				status = "verdict: does not beat baseline";
				log.info("GE brain present but not adopted — it lost to the martingale");
				return;
			}
			if (p.net.W1 == null || p.net.W1.length != IN || p.net.W2 == null
				|| p.net.b1 == null || p.net.b1.length != p.net.W1[0].length
				|| p.net.W2.length != p.net.b1.length || p.net.b2 == null)
			{
				net = null;
				status = "shape mismatch";
				log.warn("GE brain shape is not {}x{}x1 — ignoring", IN, p.net.b1 == null ? -1 : p.net.b1.length);
				return;
			}
			net = p.net;
			status = String.format("adopted (MSE %.5f vs martingale %.5f over %,d held-out rows)",
				p.metrics.net_mse, p.metrics.martingale_mse, p.metrics.test);
			log.info("GE brain {} — {} item personalities", status,
				p.net.item_bias == null ? 0 : p.net.item_bias.size());
		}
		catch (Exception ex)
		{
			net = null;
			status = "load failed";
			log.warn("GE brain load failed", ex);
		}
	}

	/**
	 * The trainer's feature vector, same order, same scaling.
	 * {@code [log10(low)/8, log10(spread)/8, log10(vol)/6, clamped momentum x5,
	 * sin(hour), cos(hour), 1]}.
	 */
	static double[] features(long low, long high, long vol, long prevMid, int hour)
	{
		final double spread = Math.max(high - low, 1);
		final double mom = prevMid > 0 ? (low + high) / 2.0 / prevMid - 1 : 0.0;
		return new double[]{
			Math.log10(Math.max(low, 2)) / 8.0,
			Math.log10(spread) / 8.0,
			Math.log10(Math.max(vol, 1)) / 6.0,
			Math.max(-0.2, Math.min(0.2, mom)) * 5,
			Math.sin(2 * Math.PI * hour / 24.0),
			Math.cos(2 * Math.PI * hour / 24.0),
			1.0,
		};
	}

	/**
	 * The learned correction to log(nextHigh/low), or 0 when not adopted —
	 * zero being exactly "the martingale, unchanged".
	 */
	double residual(int itemId, long low, long high, long vol, long prevMid, int hour)
	{
		final Net n = net;
		if (n == null)
		{
			return 0;
		}
		return forward(n, features(low, high, vol, prevMid, hour), itemId);
	}

	private static double forward(Net n, double[] x, int itemId)
	{
		final int hidden = n.b1.length;
		double core = n.b2[0];
		for (int j = 0; j < hidden; j++)
		{
			double z = n.b1[j];
			for (int i = 0; i < IN; i++)
			{
				z += x[i] * n.W1[i][j];
			}
			core += Math.tanh(z) * n.W2[j][0];
		}
		if (n.item_bias != null)
		{
			final Double b = n.item_bias.get(String.valueOf(itemId));
			if (b != null)
			{
				core += b;
			}
		}
		return n.gain * core;
	}

	/**
	 * Where the brain thinks this item's next sell print lands, given today's
	 * book. Returns -1 when not adopted.
	 */
	long predictedSell(int itemId, long low, long high, long vol, long prevMid, int hour)
	{
		if (net == null || low <= 0)
		{
			return -1;
		}
		final double martingale = Math.log(Math.max(high, 2) / (double) Math.max(low, 2));
		final double p = low * Math.exp(martingale + residual(itemId, low, high, vol, prevMid, hour));
		if (Double.isNaN(p) || Double.isInfinite(p) || p <= 0)
		{
			return -1;
		}
		// the same ceiling every other quote obeys: a forecast is not a licence to
		// price above what a real two-sided book supports
		return (long) Math.min(p, low * FlipService.MAX_QUOTE_MULT);
	}
}
