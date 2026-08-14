package com.runeai;

/**
 * The two games a flipper actually plays, made explicit.
 *
 * <p>{@link #QUICK} is the velocity lane: thin margins that CYCLE — buy, sell,
 * redeploy the same coins minutes later. It is graded on gp per cycle-second and
 * it is what the advisor shows during an active session.
 *
 * <p>{@link #LONG} is the patience lane: overnight asks and whale traps, where a
 * held slot costs nothing because the player is logged out. It is graded on
 * strike rate and payoff multiple over days.
 *
 * <p>Keeping them apart is not cosmetic. A six-hour overnight fill is a SUCCESS
 * in the long lane, and booking it into the quick lane's speed statistics is how
 * a reliable quick item gets recorded as "slow" and stops resurfacing. Same in
 * reverse: cancelling a patience order is not evidence the item is a bad flip.
 */
enum FlipLane
{
	QUICK,
	LONG;

	/** Default lane boundary in seconds of FULL cycle (buy fill + sell fill). */
	static final long DEFAULT_QUICK_CYCLE_SECS = 300;

	/**
	 * Queue depth prior: how many units sit ahead of yours at the front of a
	 * two-sided book. Purely a PRIOR — it is a shape (thicker book fills faster),
	 * not a measured constant, and any real fill this player records for the item
	 * overrides it. Calibrated so the liquidity floor ({@code vol5m == 30}) lands
	 * just outside the quick lane and a 300-per-5m book lands well inside it.
	 */
	private static final double QUEUE_UNITS = 20.0;

	/**
	 * Estimated seconds for ONE side to fill on a book trading {@code vol5m}
	 * units per five minutes, before this player's own history is consulted.
	 */
	static long priorSideSecs(long vol5m)
	{
		final double perUnit = 300.0 / Math.max(1L, vol5m);
		// floor 30s/side: a queue order waits behind the whole book however thick
		// it is — the old 5s floor quoted "~10s" cycles no queue order ever hits
		return (long) Math.max(30, Math.min(1800, QUEUE_UNITS * perUnit));
	}

	/** A full cycle is both sides, so the prior is simply doubled. */
	static long priorCycleSecs(long vol5m)
	{
		return priorSideSecs(vol5m) * 2;
	}

	/**
	 * The classifier: predicted cycle time plus book thinness decide the lane.
	 *
	 * <p>A trap book or a book under the liquidity floor is never quick however
	 * fast the estimate looks — the estimate is derived from volume, so on an
	 * empty book it is extrapolating from nothing.
	 */
	static FlipLane classify(long cycleSecs, long low, long high, long vol5m, long quickCeilingSecs)
	{
		if (FlipService.trapBook(low, high, vol5m) || vol5m < FlipService.THIN_VOL_5M)
		{
			return LONG;
		}
		return cycleSecs <= quickCeilingSecs ? QUICK : LONG;
	}

	String label()
	{
		return this == QUICK ? "QUICK" : "OVERNIGHT";
	}
}
