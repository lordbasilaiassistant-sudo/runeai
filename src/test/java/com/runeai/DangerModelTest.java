package com.runeai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The feature vector is a contract with train/train_damage_model.py. Hand the
 * weights the wrong columns, the wrong order or the wrong scale and nothing
 * throws — the model just becomes confidently wrong about when you are about to
 * be hit, which is the one place this plugin must not be confidently wrong.
 *
 * <p>Every expected number below was produced by running the trainer's own
 * {@code featurize()} / {@code sigmoid()} / {@code logit()} on the same
 * hand-built rows, not by reasoning about what they ought to be.
 */
public class DangerModelTest
{
	/** A row shaped like one line of ticks-*.jsonl. */
	private static DangerModel.Tick tick(int hp, int hpMax, int pray, int dmg, int anim,
		int target, int x, int y, int npcCount, DangerModel.Npc... npcs)
	{
		return new DangerModel.Tick(hp, hpMax, pray, dmg, anim, target, x, y,
			npcCount, Arrays.asList(npcs));
	}

	private static DangerModel.Tick skilling()
	{
		return tick(42, 50, 31, 0, 624, -1, 3200, 3200, 0);
	}

	private static List<DangerModel.Tick> corneredHistory()
	{
		return Arrays.asList(
			tick(30, 50, 0, 4, -1, 100, 3210, 3215, 6),
			tick(26, 50, 0, 3, 422, 100, 3211, 3215, 6));
	}

	private static DangerModel.Tick cornered()
	{
		return tick(23, 50, 0, 2, 422, 100, 3211, 3215, 6,
			new DangerModel.Npc(100, 1, 451, 18, true),
			new DangerModel.Npc(101, 3, -1, -1, true),
			new DangerModel.Npc(102, 9, -1, -1, false));
	}

	private static DangerModel.Tick saturated()
	{
		final DangerModel.Npc[] mob = new DangerModel.Npc[8];
		for (int i = 0; i < mob.length; i++)
		{
			mob[i] = new DangerModel.Npc(i, 1, 5, 0, true);
		}
		return tick(99, 10, 99, 40, 1, 7, 0, 0, 40, mob);
	}

	@Test
	public void featureNamesAndOrderMatchTheTrainer()
	{
		assertArrayEquals(new String[]{
			"hpFrac", "recentDmg", "recentHit", "prayFrac", "npcCount", "nearestDist",
			"nearestAtkMe", "nearestAnim", "nearestHasHp", "attackersOn",
			"attackerAdjacent", "inCombatAnim", "hasTarget", "moving", "bias",
		}, DangerModel.FEATURES);
	}

	/**
	 * Skilling with an empty scene. The absent-NPC case is the one that silently
	 * breaks: the trainer substitutes distance 30, which caps to 1.0, so "nothing
	 * near" and "something 10 tiles away" are the same column value — and a Java
	 * side that used 0 instead would read as "an NPC is standing on me".
	 */
	@Test
	public void quietSkillingTickMatchesThePythonFeaturizer()
	{
		assertArrayEquals(new double[]{
			0.84, 0.0, 0.0, 0.62, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0,
		}, DangerModel.features(skilling(), List.of()), 1e-12);
	}

	/** Under attack, damage across the window, standing still. */
	@Test
	public void corneredTickMatchesThePythonFeaturizer()
	{
		assertArrayEquals(new double[]{
			0.46, 0.18, 1.0, 0.0, 0.6, 0.1, 1.0, 1.0, 1.0, 0.4, 1.0, 1.0, 1.0, 0.0, 1.0,
		}, DangerModel.features(cornered(), corneredHistory()), 1e-12);
	}

	/**
	 * Every cap saturating at once. Note hpFrac 9.9 — the trainer does NOT cap
	 * that one, so an overheal has to come through as greater than 1 here too.
	 */
	@Test
	public void saturatedTickMatchesThePythonFeaturizer()
	{
		assertArrayEquals(new double[]{
			9.9, 1.0, 1.0, 1.0, 1.0, 0.1, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 1.0,
		}, DangerModel.features(saturated(), List.of()), 1e-12);
	}

	/** Movement is read off the immediately previous row, and is 0 without one. */
	@Test
	public void movingReadsTheLastRowOnly()
	{
		final DangerModel.Tick here = tick(50, 50, 0, 0, -1, -1, 3200, 3200, 0);
		final DangerModel.Tick there = tick(50, 50, 0, 0, -1, -1, 3201, 3200, 0);
		assertEquals(0.0, DangerModel.features(here, List.of())[13], 1e-12);
		assertEquals(1.0, DangerModel.features(here, List.of(there))[13], 1e-12);
		// two rows back moved, one row back did not: the trainer looks at hist[-1]
		assertEquals(0.0, DangerModel.features(here, Arrays.asList(there, here))[13], 1e-12);
	}

	/**
	 * The damage window is this tick plus the four before it. The trainer slices
	 * that window at the call site and its {@code featurize} then trusts what it
	 * is handed; {@link DangerModel#features} re-applies the cap itself, so a
	 * caller that hands over a longer history gets the trainer's answer rather
	 * than a quietly inflated one.
	 */
	@Test
	public void recentDamageWindowStopsAtFourRowsBack()
	{
		final DangerModel.Tick hit = tick(50, 50, 0, 5, -1, -1, 3200, 3200, 0);
		final DangerModel.Tick calm = tick(50, 50, 0, 0, -1, -1, 3200, 3200, 0);

		// four rows back: inside the window — 5 damage over a 50 hp pool
		final double[] inside = DangerModel.features(calm, Arrays.asList(hit, calm, calm, calm));
		assertEquals(0.1, inside[1], 1e-12);
		assertEquals(1.0, inside[2], 1e-12);

		// one row further back: outside it, and the flag goes with it
		final List<DangerModel.Tick> tooLong = new ArrayList<>();
		tooLong.add(hit);
		for (int i = 0; i < 4; i++)
		{
			tooLong.add(calm);
		}
		final double[] outside = DangerModel.features(calm, tooLong);
		assertEquals(0.0, outside[1], 1e-12);
		assertEquals(0.0, outside[2], 1e-12);

		// damage on the scored tick itself always counts
		assertEquals(0.1, DangerModel.features(hit, List.of())[1], 1e-12);
	}

	/**
	 * The residual arithmetic, against numbers the trainer printed for the same
	 * weights. Gain 0 has to land back on the baseline exactly — that is the
	 * state the shipped model is in, and the whole gate depends on it.
	 */
	@Test
	public void probabilityMatchesThePythonArithmetic()
	{
		final double[] w = {
			0.10, -0.25, 0.40, 0.05, -0.15, -0.60, 0.90, 0.20, 0.35,
			0.75, 1.10, 0.30, 0.55, -0.10, -0.80,
		};
		final double[] quiet = DangerModel.features(skilling(), List.of());
		final double[] hot = DangerModel.features(cornered(), corneredHistory());
		final double[] capped = DangerModel.features(saturated(), List.of());
		final double combat = 0.00845768474427912;

		// gain 0 -> the baseline, whatever the features say
		assertEquals(0.008457684744279116, DangerModel.probability(combat, 0, w, quiet), 1e-15);
		assertEquals(0.008457684744279116, DangerModel.probability(combat, 0, w, hot), 1e-15);
		assertEquals(0.008457684744279116, DangerModel.probability(combat, 0, w, capped), 1e-15);

		// a believed residual sharpens it in both directions
		assertEquals(0.0037152536268606363, DangerModel.probability(combat, 0.84, w, quiet), 1e-15);
		assertEquals(0.10742226292822445, DangerModel.probability(combat, 0.84, w, hot), 1e-15);
		assertEquals(0.24471772320589943, DangerModel.probability(combat, 0.84, w, capped), 1e-15);

		assertEquals(0.07069170583052163, DangerModel.probability(0.25, 1.5, w, quiet), 1e-15);
		assertEquals(0.9741155546023961, DangerModel.probability(0.25, 1.5, w, hot), 1e-15);
		assertEquals(0.9954875290553645, DangerModel.probability(0.25, 1.5, w, capped), 1e-15);
	}

	/**
	 * The verdict gate. A model that has not beaten its baseline contributes its
	 * baseline and nothing else, however confident its own gain field is.
	 */
	@Test
	public void aLosingVerdictMutesTheResidualEntirely()
	{
		assertEquals(0.0, DangerModel.effectiveGain(false, 1.4), 1e-12);
		assertEquals(0.0, DangerModel.effectiveGain(false, 0.0), 1e-12);
		assertEquals(1.4, DangerModel.effectiveGain(true, 1.4), 1e-12);
		// the trainer clips to [0, 2]; a hand-edited file does not get to exceed it
		assertEquals(2.0, DangerModel.effectiveGain(true, 9.0), 1e-12);
		assertEquals(0.0, DangerModel.effectiveGain(true, Double.NaN), 1e-12);
		assertEquals(0.0, DangerModel.effectiveGain(true, -1), 1e-12);
	}

	/**
	 * What the guidance actually does with the number. The shipped model's own
	 * baselines: Combat runs 6.48x the corpus-wide rate, so it earns two
	 * doublings above the elevation floor and moves the EAT threshold 10 points.
	 * Everything else this player has recorded sits BELOW average and moves it
	 * not at all — a prior that fires everywhere is not a prior.
	 */
	@Test
	public void urgencyBoostOnlyRespondsToAnElevatedContext()
	{
		final double global = 0.0013058239749281796;
		assertEquals(10, DangerModel.urgencyBoost(0.00845768474427912, global));  // Combat
		assertEquals(0, DangerModel.urgencyBoost(0.0006159547051548018, global)); // Prayer
		assertEquals(0, DangerModel.urgencyBoost(0.0005924644961190041, global)); // none

		// exactly at the floor is the first step; just under it is silence
		assertEquals(5, DangerModel.urgencyBoost(2 * global, global));
		assertEquals(0, DangerModel.urgencyBoost(1.999 * global, global));
		assertEquals(10, DangerModel.urgencyBoost(4 * global, global));
		assertEquals(15, DangerModel.urgencyBoost(8 * global, global));
		// and it stays capped no matter how hot the residual gets
		assertEquals(15, DangerModel.urgencyBoost(0.99, global));

		// no model, no boost
		assertEquals(0, DangerModel.urgencyBoost(-1, global));
		assertEquals(0, DangerModel.urgencyBoost(0.5, -1));
	}

	/** No model file means no opinion — never a default one. */
	@Test
	public void anUnloadedModelSaysNothing()
	{
		final DangerModel m = new DangerModel(new com.google.gson.Gson());
		assertTrue("a fresh model must not claim to be loaded", !m.loaded());
		assertEquals(-1, m.probability("Combat", cornered(), corneredHistory()), 1e-12);
		assertEquals(-1, m.observe("Combat", cornered()), 1e-12);
		assertEquals(-1, m.globalRate(), 1e-12);
		assertEquals(0, DangerModel.urgencyBoost(m.observe("Combat", cornered()), m.globalRate()));
	}

	/**
	 * The real path, end to end: read the model this machine actually trained,
	 * validate it, apply the verdict gate, score a live-shaped tick, and turn
	 * that into the threshold change the guidance will make.
	 *
	 * <p>The model is a local artifact derived from recorded play and is never
	 * committed, so a clean checkout skips this — which is itself the assertion
	 * that matters most: no file, no opinion, no crash.
	 */
	@Test
	public void theTrainedModelLoadsAndGatesItself()
	{
		final DangerModel m = new DangerModel(new com.google.gson.Gson());
		m.loadNow();
		org.junit.Assume.assumeTrue("no trained model on this machine — "
			+ "run py train/train_damage_model.py", m.loaded());
		org.junit.Assume.assumeTrue("model was retrained — regenerate these references, "
			+ "they are this corpus's measured rates: " + m.status(),
			m.status().contains("24,078 ticks"));

		// the verdict on this corpus is false (0 held-out positives), so the
		// residual is muted and the model IS its per-activity baseline
		assertTrue("a losing verdict must not advertise a residual",
			m.status().contains("per-activity baseline only"));
		assertEquals(0.0013058239749281796, m.globalRate(), 1e-15);

		// Combat: 6.48x this player's average tick -> the EAT threshold moves 10 points
		final double combat = m.observe("Combat", cornered());
		assertEquals(0.00845768474427912, combat, 1e-12);
		assertEquals(10, DangerModel.urgencyBoost(combat, m.globalRate()));

		// the same tick while not in a tracked activity: below average, no change
		final double idle = m.observe(null, skilling());
		assertEquals(0.0005924644961190041, idle, 1e-12);
		assertEquals(0, DangerModel.urgencyBoost(idle, m.globalRate()));

		// an activity the corpus has never seen falls back to the global rate
		assertEquals(m.globalRate(), m.observe("Firemaking", skilling()), 1e-12);
	}

	/** An absent activity is the corpus's "none" bucket, not a missing key. */
	@Test
	public void nullActivityIsItsOwnBucket()
	{
		assertEquals("none", DangerModel.activityKey(null));
		assertEquals("none", DangerModel.activityKey(""));
		assertEquals("Combat", DangerModel.activityKey("Combat"));
	}
}
