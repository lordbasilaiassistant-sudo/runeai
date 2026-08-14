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
 * Time-to-fill as a SURVIVAL problem, which is the only honest framing.
 *
 * <p>The old fill-time regression trained on filled offers alone — but this
 * player's log holds ~4 cancelled offers for every completed one, and an offer
 * pulled after 20 minutes is not a missing data point, it is a measurement:
 * "at this price, in this book, 20 minutes was not enough". Ignoring the
 * cancels is survivorship bias, and it is mathematically WHY the old estimates
 * said "quick" about offers that sat — the model had literally never seen an
 * offer sit.
 *
 * <p>The artifact is a Weibull AFT model fitted by censored maximum likelihood
 * in {@code train/train_fill_survival.py}: {@code T ~ Weibull(k, lambda(x))}
 * with {@code lambda = exp(mu + beta·x)}. From it:
 * <ul>
 *   <li>median fill time = {@code lambda * ln(2)^(1/k)}</li>
 *   <li>P(fill within t) = {@code 1 - exp(-(t/lambda)^k)}</li>
 * </ul>
 *
 * <p>Same discipline as every learned artifact here: gated on the verdict the
 * trainer wrote from held-out data, and a missing/losing file changes nothing.
 */
@Slf4j
@Singleton
class SurvivalFillModel
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "fill-survival.json");
	private static final long RECHECK_MS = 5 * 60_000;
	/** Sanity clamp on anything we ever predict: 5 seconds to 6 hours. */
	private static final long MIN_SECS = 5, MAX_SECS = 6 * 3600;

	private final Gson gson;
	private long lastCheck;
	private long loadedStamp = -1;
	private volatile Params params;
	private volatile String status = "unloaded";

	/** The fitted artifact, in exactly the shape the trainer writes. */
	static class Params
	{
		double mu;
		double k;
		double[] beta;          // {buySide, log10(price)/8, sin(hour), cos(hour)}
		Verdict verdict;
	}

	static class Verdict
	{
		boolean adopt;
		double cindex;
		double ll_model;
		double ll_baseline;
		int n;
		int events;
	}

	@Inject
	SurvivalFillModel(Gson gson)
	{
		this.gson = gson;
	}

	boolean adopted()
	{
		return params != null;
	}

	String status()
	{
		return status;
	}

	/** Read (or re-read) the artifact. Off the client thread only — disk IO. */
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
				status = "no survival file";
				return;
			}
			final long stamp = FILE.lastModified();
			if (stamp == loadedStamp)
			{
				return;
			}
			loadedStamp = stamp;
			final Params p = gson.fromJson(
				new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), Params.class);
			if (p == null || p.beta == null || p.beta.length < 4 || p.k <= 0)
			{
				params = null;
				status = "unreadable";
				return;
			}
			if (p.verdict == null || !p.verdict.adopt)
			{
				params = null;
				status = p.verdict == null ? "no verdict" : String.format(
					"verdict: not adopted (C-index %.3f on %d episodes)", p.verdict.cindex, p.verdict.n);
				log.info("survival fill model present but not adopted: {}", status);
				return;
			}
			params = p;
			status = String.format("adopted (C %.3f, %d episodes / %d fills, k=%.2f)",
				p.verdict.cindex, p.verdict.n, p.verdict.events, p.k);
			log.info("survival fill model {}", status);
		}
		catch (Exception ex)
		{
			params = null;
			status = "load failed";
			log.warn("survival fill model load failed", ex);
		}
	}

	// ================= pure math (static, testable without a file) =================

	/** The AFT scale for one offer's features. */
	static double lambda(double mu, double[] beta, boolean buying, long price, double hourUtc)
	{
		final double logp = Math.log10(Math.max(2, price)) / 8.0;
		final double x = mu
			+ beta[0] * (buying ? 1 : 0)
			+ beta[1] * logp
			+ beta[2] * Math.sin(2 * Math.PI * hourUtc / 24)
			+ beta[3] * Math.cos(2 * Math.PI * hourUtc / 24);
		return Math.exp(x);
	}

	/** Median seconds for ONE side to fill: {@code lambda * ln(2)^(1/k)}. */
	static long medianSecs(double mu, double k, double[] beta, boolean buying, long price, double hourUtc)
	{
		final double m = lambda(mu, beta, buying, price, hourUtc) * Math.pow(Math.log(2), 1.0 / k);
		return (long) Math.max(MIN_SECS, Math.min(MAX_SECS, m));
	}

	/** P(this side fills within t seconds) = {@code 1 - exp(-(t/lambda)^k)}. */
	static double pFillWithin(double mu, double k, double[] beta, boolean buying, long price,
		double hourUtc, long tSecs)
	{
		if (tSecs <= 0)
		{
			return 0;
		}
		final double l = lambda(mu, beta, buying, price, hourUtc);
		return 1 - Math.exp(-Math.pow(tSecs / l, k));
	}

	// ================= instance accessors over the loaded artifact =================

	/** Median seconds for one side, or -1 when the model is not adopted. */
	long medianSideSecs(boolean buying, long price, double hourUtc)
	{
		final Params p = params;
		return p == null ? -1 : medianSecs(p.mu, p.k, p.beta, buying, price, hourUtc);
	}

	/**
	 * A full cycle from this player's own measured distribution: median buy-side
	 * plus median sell-side. -1 when not adopted, so callers keep their prior.
	 */
	long cycleSecs(long price, double hourUtc)
	{
		final Params p = params;
		if (p == null)
		{
			return -1;
		}
		return Math.min(2 * MAX_SECS,
			medianSecs(p.mu, p.k, p.beta, true, price, hourUtc)
				+ medianSecs(p.mu, p.k, p.beta, false, price, hourUtc));
	}

	/** P(one side fills within t), or -1 when not adopted. */
	double pWithin(boolean buying, long price, double hourUtc, long tSecs)
	{
		final Params p = params;
		return p == null ? -1 : pFillWithin(p.mu, p.k, p.beta, buying, price, hourUtc, tSecs);
	}
}
