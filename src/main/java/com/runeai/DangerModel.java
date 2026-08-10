package com.runeai;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The danger model from {@code train/train_damage_model.py}: P(take damage
 * within the next 3 ticks), read back here as one dot product per game tick.
 *
 * <p>The trained artifact has two halves and they are gated separately, which is
 * the whole point of its design:
 *
 * <ul>
 *   <li><b>Baseline</b> — the empirical damage rate of the activity you are
 *       doing, shrunk toward the global rate. It is fitted arithmetic over
 *       counted ticks, not a learned opinion, so it is usable the moment the
 *       file parses. "You are in Combat and Combat ticks take damage 14x more
 *       often than the rest of your play" is a legitimate risk PRIOR and it
 *       costs nothing to believe.</li>
 *   <li><b>Residual</b> — logistic weights that sharpen the baseline per tick,
 *       multiplied by a trust gain. This half is gated on the file's own
 *       {@code verdict.beats_baseline}. When the verdict is false the gain is
 *       forced to 0 here regardless of what the file says, and the model
 *       reproduces the baseline exactly. When a future retrain on real P2P
 *       combat earns a positive verdict, the sharpened correction starts
 *       applying with no code change.</li>
 * </ul>
 *
 * <p><b>This is a coarse prior, not an attack predictor.</b> Its only consumer
 * raises the urgency of the low-HP EAT warning that already existed — it warns
 * you earlier in a context that historically hurts. It does not say when the
 * next attack lands, does not mark tiles, and does not count attack ticks; those
 * are all things a hub reviewer would reject and none of them are here.
 *
 * <p>Two sources, in order: {@code ~/.runelite/runeai/damage_model.json} if the
 * player has retrained locally, else the copy bundled from {@code train/} by
 * {@code processResources}. That bundled copy exists only on a machine where the
 * trainer has been run — {@code train/damage_model.json} is gitignored because
 * it is derived from recorded play, so a clean checkout builds with no model at
 * all and the feature is simply inert. Missing, unparseable, wrong shape, wrong
 * feature list: {@link #probability} returns -1 and every caller keeps the
 * behaviour it had before.
 *
 * <p>Loading is one-shot and happens on its own daemon thread — disk IO must
 * never touch the client thread. Retrain, then restart the plugin.
 */
@Slf4j
@Singleton
class DangerModel
{
	/**
	 * The trainer's FEATURES list, same names, same order. If the file disagrees
	 * with this array the model is refused: feeding weights the wrong columns
	 * throws nothing, it just produces confident nonsense.
	 */
	static final String[] FEATURES = {
		"hpFrac", "recentDmg", "recentHit", "prayFrac", "npcCount", "nearestDist",
		"nearestAtkMe", "nearestAnim", "nearestHasHp", "attackersOn",
		"attackerAdjacent", "inCombatAnim", "hasTarget", "moving", "bias",
	};

	/** Trainer constants. A file that disagrees is a file trained differently. */
	static final int HORIZON = 3;
	static final int HISTORY = 5;
	private static final int VERSION = 2;

	/**
	 * How far above your own average a context has to sit before the warning
	 * threshold moves at all. Twice the corpus-wide damage rate — below that the
	 * prior has nothing to say and the guidance stays exactly as configured.
	 */
	static final double ELEVATED = 2.0;
	/** HP percentage points added to the EAT threshold per doubling above {@link #ELEVATED}. */
	static final int BOOST_STEP = 5;
	/** Ceiling on that boost. A prior may nudge the threshold, never own it. */
	static final int MAX_BOOST = 15;

	private static final String RESOURCE = "/com/runeai/damage_model.json";
	private static final File OVERRIDE =
		new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "damage_model.json");

	private final Gson gson;
	private final AtomicBoolean loadStarted = new AtomicBoolean();
	/** Up to {@link #HISTORY} - 1 previous ticks, oldest first — the trainer's window. */
	private final Deque<Tick> history = new ArrayDeque<>();

	private volatile Model model;
	private volatile String status = "not loaded";

	@Inject
	DangerModel(Gson gson)
	{
		this.gson = gson;
	}

	/** One NPC as the tick recorder writes it: the fields the model reads. */
	static final class Npc
	{
		final int id;
		final int dist;
		final int anim;
		final int hr;
		final boolean atkMe;

		Npc(int id, int dist, int anim, int hr, boolean atkMe)
		{
			this.id = id;
			this.dist = dist;
			this.anim = anim;
			this.hr = hr;
			this.atkMe = atkMe;
		}
	}

	/**
	 * One tick of state, shaped exactly like a row of {@code ticks-*.jsonl}.
	 * The recorder and the model are fed the SAME object every tick, so the
	 * features trained on and the features inferred from cannot drift.
	 * {@code npcs} is the nearest 8, sorted by distance, as recorded.
	 */
	static final class Tick
	{
		final int hp;
		final int hpMax;
		final int pray;
		final int dmgTaken;
		final int anim;
		final int targetNpcId;
		final int x;
		final int y;
		final int npcCount;
		final List<Npc> npcs;

		Tick(int hp, int hpMax, int pray, int dmgTaken, int anim, int targetNpcId,
			int x, int y, int npcCount, List<Npc> npcs)
		{
			this.hp = hp;
			this.hpMax = hpMax;
			this.pray = pray;
			this.dmgTaken = dmgTaken;
			this.anim = anim;
			this.targetNpcId = targetNpcId;
			this.x = x;
			this.y = y;
			this.npcCount = npcCount;
			this.npcs = npcs == null ? List.of() : npcs;
		}
	}

	/** Gson target — field names are the trainer's JSON keys. */
	private static class Payload
	{
		int version;
		int horizonTicks;
		int historyTicks;
		List<String> features;
		List<Double> weights;
		double gain;
		double baselineGlobal;
		Map<String, Double> baselineByActivity;
		Corpus corpus;
		Verdict verdict;
	}

	private static class Corpus
	{
		int ticks;
		int positives;
	}

	private static class Verdict
	{
		boolean beats_baseline;
		String reason;
	}

	/** The validated, immutable thing inference actually runs against. */
	private static final class Model
	{
		final double[] weights;
		final double gain;
		final double globalRate;
		final Map<String, Double> byActivity;

		Model(double[] weights, double gain, double globalRate, Map<String, Double> byActivity)
		{
			this.weights = weights;
			this.gain = gain;
			this.globalRate = globalRate;
			this.byActivity = byActivity;
		}
	}

	/**
	 * Start the one-shot load on a daemon thread. Safe to call from the client
	 * thread and safe to call repeatedly; only the first call does anything.
	 */
	void loadAsync()
	{
		if (!loadStarted.compareAndSet(false, true))
		{
			return;
		}
		final Thread t = new Thread(this::loadNow, "runeai-danger-load");
		t.setDaemon(true);
		t.start();
	}

	/** The blocking form. {@link #loadAsync()} is what the plugin calls. */
	void loadNow()
	{
		try
		{
			String body = null;
			String source = null;
			if (OVERRIDE.exists())
			{
				body = new String(Files.readAllBytes(OVERRIDE.toPath()), StandardCharsets.UTF_8);
				source = "local retrain";
			}
			else
			{
				try (InputStream in = DangerModel.class.getResourceAsStream(RESOURCE))
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
				status = "no model — run py train/train_damage_model.py";
				log.info("danger model: no file, low-HP guidance is unchanged");
				return;
			}
			final Payload p = gson.fromJson(body, Payload.class);
			final String bad = validate(p);
			if (bad != null)
			{
				status = bad;
				log.warn("danger model ({}) refused: {}", source, bad);
				return;
			}
			final double[] w = new double[FEATURES.length];
			for (int i = 0; i < w.length; i++)
			{
				w[i] = p.weights.get(i);
			}
			final boolean beats = p.verdict != null && p.verdict.beats_baseline;
			final double gain = effectiveGain(beats, p.gain);
			model = new Model(w, gain, p.baselineGlobal,
				p.baselineByActivity == null ? Map.of() : Map.copyOf(p.baselineByActivity));
			status = String.format("%s · %s (%,d ticks, %d damage windows)",
				source,
				gain > 0 ? String.format("baseline + %.2fx residual", gain)
					: "per-activity baseline only",
				p.corpus == null ? 0 : p.corpus.ticks,
				p.corpus == null ? 0 : p.corpus.positives);
			log.info("danger model {}{}", status,
				beats ? "" : " — verdict says the residual does not beat its baseline, so it is muted");
		}
		catch (Exception ex)
		{
			status = "load failed";
			log.warn("danger model load failed", ex);
		}
	}

	/** Null when the payload is usable, otherwise the reason it is not. */
	private static String validate(Payload p)
	{
		if (p == null)
		{
			return "unparseable";
		}
		if (p.version != VERSION)
		{
			return "version " + p.version + ", expected " + VERSION;
		}
		if (p.horizonTicks != HORIZON || p.historyTicks != HISTORY)
		{
			return "trained on a different window (" + p.historyTicks + " back, "
				+ p.horizonTicks + " ahead)";
		}
		if (p.features == null || !p.features.equals(Arrays.asList(FEATURES)))
		{
			return "feature list does not match this build";
		}
		if (p.weights == null || p.weights.size() != FEATURES.length)
		{
			return "weight count does not match the feature list";
		}
		for (Double w : p.weights)
		{
			if (w == null || !Double.isFinite(w))
			{
				return "non-finite weight";
			}
		}
		if (!Double.isFinite(p.baselineGlobal) || p.baselineGlobal <= 0 || p.baselineGlobal >= 1)
		{
			return "baseline rate out of range";
		}
		return null;
	}

	private static byte[] readAll(InputStream in) throws java.io.IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/**
	 * How much of the residual to believe. A losing verdict means none of it,
	 * whatever the file's own gain says, and the model collapses to its baseline.
	 * Clipped to the trainer's own [0, 2] range.
	 */
	static double effectiveGain(boolean beatsBaseline, double fileGain)
	{
		if (!beatsBaseline || !Double.isFinite(fileGain) || fileGain <= 0)
		{
			return 0;
		}
		return Math.min(2.0, fileGain);
	}

	boolean loaded()
	{
		return model != null;
	}

	String status()
	{
		return status;
	}

	/** The corpus-wide damage rate the boost is measured against, or -1. */
	double globalRate()
	{
		final Model m = model;
		return m == null ? -1 : m.globalRate;
	}

	/** Drop the history window — a logout is not four ticks ago. */
	synchronized void reset()
	{
		history.clear();
	}

	/**
	 * Score this tick against the window of previous ticks, then remember it.
	 * Returns the probability, or -1 when there is no usable model.
	 *
	 * <p>Order matters and mirrors the trainer: a row is featurized against the
	 * rows BEFORE it, never including itself in its own history.
	 */
	synchronized double observe(String activity, Tick row)
	{
		final double p = probability(activity, row, new ArrayList<>(history));
		history.addLast(row);
		while (history.size() > HISTORY - 1)
		{
			history.removeFirst();
		}
		return p;
	}

	/**
	 * P(damage within {@link #HORIZON} ticks), or -1 when not loaded.
	 * {@code hist} is the up-to-{@link #HISTORY}-1 rows immediately before
	 * {@code row}, oldest first.
	 */
	double probability(String activity, Tick row, List<Tick> hist)
	{
		final Model m = model;
		if (m == null || row == null)
		{
			return -1;
		}
		final Double byAct = m.byActivity.get(activityKey(activity));
		final double base = byAct == null ? m.globalRate : byAct;
		return probability(base, m.gain, m.weights, features(row, hist));
	}

	/**
	 * {@code currentActivity()} returns null when no xp has landed in 50 ticks,
	 * and gson drops null fields — so in the corpus an absent activity is its own
	 * bucket named "none", not missing data. Same bucket here.
	 */
	static String activityKey(String activity)
	{
		return activity == null || activity.isEmpty() ? "none" : activity;
	}

	/**
	 * The trainer's arithmetic, isolated so it can be tested without a file:
	 * {@code sigmoid(logit(baseline) + gain * (x . w))}. Gain 0 returns the
	 * baseline exactly, which is the state the shipped model is in.
	 */
	static double probability(double base, double gain, double[] w, double[] x)
	{
		final double b = Math.max(1e-6, Math.min(1 - 1e-6, base));
		double z = Math.log(b / (1 - b));
		if (gain > 0)
		{
			double core = 0;
			for (int i = 0; i < w.length && i < x.length; i++)
			{
				core += w[i] * x[i];
			}
			z += gain * core;
		}
		return 1.0 / (1.0 + Math.exp(-Math.max(-30, Math.min(30, z))));
	}

	/**
	 * The trainer's {@code featurize(row, hist)}, same order, same scaling, same
	 * caps. Only past state is read — the label lives strictly in the future.
	 */
	static double[] features(Tick row, List<Tick> hist)
	{
		final double hpMax = Math.max(1, row.hpMax);
		final List<Tick> h = hist == null ? List.of() : hist;

		int recentDmg = row.dmgTaken;
		for (int i = Math.max(0, h.size() - (HISTORY - 1)); i < h.size(); i++)
		{
			recentDmg += h.get(i).dmgTaken;
		}
		final Tick prev = h.isEmpty() ? null : h.get(h.size() - 1);
		final boolean moved = prev != null && (prev.x != row.x || prev.y != row.y);

		final Npc nearest = row.npcs.isEmpty() ? null : row.npcs.get(0);
		int attackers = 0;
		boolean adjacent = false;
		for (Npc n : row.npcs)
		{
			if (n.atkMe)
			{
				attackers++;
				if (n.dist <= 1)
				{
					adjacent = true;
				}
			}
		}

		return new double[]{
			row.hp / hpMax,
			Math.min(1.0, recentDmg / hpMax),
			recentDmg > 0 ? 1.0 : 0.0,
			Math.min(1.0, row.pray / hpMax),
			Math.min(1.0, row.npcCount / 10.0),
			Math.min(1.0, (nearest == null ? 30 : nearest.dist) / 10.0),
			nearest != null && nearest.atkMe ? 1.0 : 0.0,
			nearest != null && nearest.anim != -1 ? 1.0 : 0.0,
			nearest != null && nearest.hr >= 0 ? 1.0 : 0.0,
			Math.min(1.0, attackers / 5.0),
			adjacent ? 1.0 : 0.0,
			row.anim != -1 ? 1.0 : 0.0,
			row.targetNpcId != -1 ? 1.0 : 0.0,
			moved ? 1.0 : 0.0,
			1.0,
		};
	}

	/**
	 * HP percentage points to add to the EAT threshold, from how far this tick's
	 * risk sits above the player's own average tick. Zero unless the context is
	 * at least {@link #ELEVATED}x normal, then one {@link #BOOST_STEP} per
	 * doubling, capped at {@link #MAX_BOOST}.
	 *
	 * <p>Deliberately chunky. The output is "warn a bit earlier", so a smooth
	 * per-tick number would be false precision and would edge toward the kind of
	 * live danger readout a hub reviewer rejects.
	 */
	static int urgencyBoost(double p, double globalRate)
	{
		if (!(p > 0) || !(globalRate > 0))
		{
			return 0;
		}
		final double ratio = p / globalRate;
		if (ratio < ELEVATED)
		{
			return 0;
		}
		final int steps = 1 + (int) Math.floor(Math.log(ratio / ELEVATED) / Math.log(2));
		return Math.min(MAX_BOOST, steps * BOOST_STEP);
	}
}
