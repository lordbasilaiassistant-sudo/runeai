package com.runeai;

import com.google.gson.Gson;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The fill-time model from {@code train/train_flip_model.py}: a five-weight
 * ridge regression on log(seconds-to-fill), applied here as a dot product.
 *
 * <p><b>It only ever sharpens an estimate the quick lane already makes.</b>
 * Ranking is by cycle velocity whether or not this loads — the model just
 * replaces the volume prior with something fitted when it has earned that, and
 * this player's own measured fills still outrank both.
 *
 * <p>Adoption is gated on the model file's OWN {@code verdict} field, which the
 * trainer sets from K-fold out-of-sample error against a median baseline refit
 * inside each fold. Missing file, unparseable file, missing verdict, or any
 * verdict other than {@code "adopt"} means this returns -1 and the caller keeps
 * its prior. At the time of writing the shipped model says {@code fallback}.
 *
 * <p>Two sources, in order: {@code ~/.runelite/runeai/flip_model.json} if the
 * player has retrained locally, else the copy bundled from {@code train/}. The
 * local override is what makes a retrain take effect without a rebuild.
 */
@Slf4j
@Singleton
class FillTimeModel
{
	private static final String RESOURCE = "/com/runeai/flip_model.json";
	private static final File OVERRIDE =
		new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "flip_model.json");
	/** Refuse anything the weights cannot honestly speak to. */
	private static final long MIN_SECS = 3;
	private static final long MAX_SECS = 6 * 3600;

	private final Gson gson;
	private volatile boolean loaded;
	private volatile double[] weights;
	private volatile String verdict = "unloaded";
	private volatile String source = "none";

	/** Gson target — field names are the trainer's JSON keys. */
	private static class Payload
	{
		java.util.List<String> features;
		java.util.List<Double> weights;
		String target;
		String verdict;
	}

	@Inject
	FillTimeModel(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Read the model once. Call from an off-client thread — the price scan
	 * callback is one, which is where the estimate is needed anyway.
	 */
	synchronized void ensureLoaded()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		try
		{
			String body = null;
			if (OVERRIDE.exists())
			{
				body = new String(Files.readAllBytes(OVERRIDE.toPath()), StandardCharsets.UTF_8);
				source = "local retrain";
			}
			else
			{
				try (InputStream in = FillTimeModel.class.getResourceAsStream(RESOURCE))
				{
					if (in != null)
					{
						body = new String(readAll(in), StandardCharsets.UTF_8);
						source = "bundled";
					}
				}
			}
			if (body == null)
			{
				verdict = "missing";
				log.info("fill-time model: no file — ranking uses observed fill times");
				return;
			}
			final Payload p = gson.fromJson(body, Payload.class);
			if (p == null || p.weights == null || p.weights.size() != 5
				|| !"log_seconds_to_fill".equals(p.target))
			{
				verdict = "unusable";
				log.warn("fill-time model: unexpected shape, ignoring");
				return;
			}
			final double[] w = new double[5];
			for (int i = 0; i < 5; i++)
			{
				w[i] = p.weights.get(i);
			}
			weights = w;
			verdict = p.verdict == null ? "no verdict" : p.verdict;
			log.info("fill-time model ({}): verdict {} -> {}", source, verdict,
				adopted() ? "SHARPENING cycle estimates" : "not used, observed fill times only");
		}
		catch (Exception ex)
		{
			verdict = "load failed";
			log.warn("fill-time model load failed", ex);
		}
	}

	private static byte[] readAll(InputStream in) throws java.io.IOException
	{
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/** True only when the model's own out-of-sample verdict says to use it. */
	boolean adopted()
	{
		return weights != null && "adopt".equals(verdict);
	}

	String status()
	{
		return verdict + " (" + source + ")";
	}

	/**
	 * Predicted seconds for ONE side to fill, or -1 when not adopted.
	 * Features are the trainer's, in its order:
	 * {@code [bias, isBuy, log10_price, log10_qty, hour_frac]}.
	 */
	long sideSecs(boolean buying, long price, long qty, int hourOfDay)
	{
		return sideSecs(adopted() ? weights : null, buying, price, qty, hourOfDay);
	}

	/** Pure form, so the arithmetic can be tested without a file or a client. */
	static long sideSecs(double[] w, boolean buying, long price, long qty, int hourOfDay)
	{
		if (w == null)
		{
			return -1;
		}
		final double logSecs = w[0]
			+ w[1] * (buying ? 1 : 0)
			+ w[2] * Math.log10(Math.max(2, price))
			+ w[3] * Math.log10(Math.max(1, qty) + 1.0)
			+ w[4] * (hourOfDay / 24.0);
		final double secs = Math.exp(logSecs);
		if (Double.isNaN(secs) || Double.isInfinite(secs))
		{
			return -1;
		}
		return (long) Math.max(MIN_SECS, Math.min(MAX_SECS, secs));
	}

	/** Both sides of a round trip, or -1 when not adopted. */
	long cycleSecs(long price, long qty, int hourOfDay)
	{
		final long buy = sideSecs(true, price, qty, hourOfDay);
		final long sell = sideSecs(false, price, qty, hourOfDay);
		return buy < 0 || sell < 0 ? -1 : buy + sell;
	}
}
