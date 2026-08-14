package com.runeai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Live GE flip intelligence from the wiki prices API — tax-aware margins,
 * suggested buy/sell prices, throughput bounded by buy limits and volume.
 * Suggestions only; RuneAI never trades for you.
 */
@Slf4j
@Singleton
class FlipService
{
	private static final String API = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String UA = "RuneAI RuneLite plugin (github.com/lordbasilaiassistant-sudo/runeai)";
	private static final long FAST_MS = 60_000;      // quotes: keep up with undercut wars
	private static final long SLOW_MS = 5 * 60_000;  // volumes: change slowly

	/**
	 * A real two-sided book keeps the instant-buy print within a small multiple of
	 * the instant-sell print. Past this, "high" is a PRINT, not a price — one whale
	 * paying 45k for a 100gp hat — and every number derived from it is fiction.
	 * Hard ceiling on anything we ever quote back at the player.
	 */
	static final double MAX_QUOTE_MULT = 3.0;
	/** Liquidity floor, same 5-minute volume the ranker already requires. */
	static final long THIN_VOL_5M = 30;
	/** Scans kept in the rolling price window. Six at 60s each is a ~6 minute look-back. */
	private static final int WINDOW_SCANS = 6;

	private final OkHttpClient http;
	private final Gson gson;

	private final Map<Integer, int[]> limits = new ConcurrentHashMap<>();   // id -> {limit}
	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> membersItem = new ConcurrentHashMap<>();
	private volatile List<Flip> allFlips = List.of();
	private final Map<Integer, long[]> quotes = new ConcurrentHashMap<>(); // id -> {buyAt, sellAt (capped), volHr}
	private final Map<Integer, long[]> books = new ConcurrentHashMap<>();  // id -> {low, high RAW, vol5m, highTime}
	/**
	 * Order flow, kept SPLIT: id -> {instaBuyVol, instaSellVol}. The 5m feed
	 * reports buyer-initiated and seller-initiated volume separately, and summing
	 * them (as the ranker used to) throws away the single most standard
	 * microstructure signal there is — who is crossing the spread. More
	 * insta-buys than insta-sells means demand is lifting the book, which is
	 * exactly the condition under which YOUR sell side clears.
	 */
	private final Map<Integer, long[]> flow = new ConcurrentHashMap<>();
	/** This player's buy-limit consumption in the rolling 4h window: id -> units bought. */
	private volatile Map<Integer, Long> limitUsed = Map.of();
	private final java.util.Set<Integer> trapItems = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Long> predictedMid = new ConcurrentHashMap<>(); // last scan's forecast
	/** Rolling mid window: {@link #WINDOW_SCANS} scans at {@link #FAST_MS}, so ~6 minutes. */
	private final Map<Integer, java.util.ArrayDeque<Long>> midHist = new ConcurrentHashMap<>();
	/** The instant-sell side over the same window — the half a whale print cannot fake. */
	private final Map<Integer, java.util.ArrayDeque<Long>> lowHist = new ConcurrentHashMap<>();
	private final Map<Integer, Long> prevMids = new ConcurrentHashMap<>();  // the mid one scan back, for the brain
	private final Map<Integer, Double> momentum = new ConcurrentHashMap<>();   // slope over the window
	volatile double lastScanAvgErr = -1; // global market-surprise gauge, logged each scan
	private final AtomicLong lastFast = new AtomicLong();
	private final AtomicLong lastSlow = new AtomicLong();
	private volatile String cachedFive;
	private volatile long budget = -1;       // carried coins; -1 = unknown
	private volatile boolean f2pOnly;
	private volatile long quickCeilingSecs = FlipLane.DEFAULT_QUICK_CYCLE_SECS;
	private volatile boolean lanesOn = true;
	private volatile int quickSlots = 3;
	private volatile boolean instaOnly = true;
	/** The player's open buy basis: id -> {qty, totalCost}. Pushed from the plugin. */
	private volatile Map<Integer, long[]> basis = Map.of();

	/**
	 * A full insta cycle: both sides fill on placement, the rest is walking to the
	 * booth and relisting. This is the honest number the quick lane is graded on.
	 */
	static final long INSTA_CYCLE_SECS = 30;

	/** Lane boundary and slot split from config; pushed in with the other context. */
	void setLaneConfig(boolean enabled, long quickCycleSecs, int quickLaneSlots, boolean quickInstaOnly)
	{
		if (instaOnly != quickInstaOnly)
		{
			cachedFrom = null; // the filter changed — a cached ranking is stale
		}
		lanesOn = enabled;
		quickCeilingSecs = Math.max(30, quickCycleSecs);
		quickSlots = Math.max(1, quickLaneSlots);
		instaOnly = quickInstaOnly;
	}

	/** Open-position snapshot so coaching can refuse to sell below what was paid. */
	void setBasisSnapshot(Map<Integer, long[]> b)
	{
		basis = b;
	}

	/** Units bought per item inside the rolling 4h window, from the plugin's ledger. */
	void setLimitUsed(Map<Integer, Long> used)
	{
		limitUsed = used;
	}

	/**
	 * Buy-limit capacity LEFT in this 4h window. The interface enforces the
	 * limit silently — an offer past it just sits — so a suggestion that
	 * ignores consumption is a coached dead slot.
	 */
	long remainingLimit(int itemId)
	{
		return Math.max(0, limitFor(itemId) - limitUsed.getOrDefault(itemId, 0L));
	}

	/**
	 * Signed order-flow imbalance in [-1, 1]: (instaBuys − instaSells) / total
	 * over the last 5 minutes. Positive = buyers are crossing the spread =
	 * demand — the side that clears YOUR sell. 0 when unknown.
	 */
	double ofi(int itemId)
	{
		final long[] f = flow.get(itemId);
		if (f == null || f[0] + f[1] <= 0)
		{
			return 0;
		}
		return (f[0] - f[1]) / (double) (f[0] + f[1]);
	}

	/**
	 * The OFI ranking factor, clamped so one 5m window can tilt but never
	 * dominate. Symmetric and linear: no cliff for the momentum factor to
	 * fight with.
	 */
	static double flowFactor(double ofi)
	{
		return Math.max(0.75, Math.min(1.25, 1 + 0.25 * ofi));
	}

	/**
	 * gp per slot-second of the CURRENT best pick — the opportunity cost of
	 * leaving a slot parked on something worse. 0 while the board is empty.
	 */
	double bestVelocity()
	{
		final List<Flip> top = cachedTop;
		final long b = budget;
		return top.isEmpty() || b <= 0 ? 0 : velocity(top.get(0), slotCapital(b));
	}

	/** Average gp paid per unit still held of this item, or -1 with no open basis. */
	long avgCost(int itemId)
	{
		final long[] b = basis.get(itemId);
		return b == null || b[0] <= 0 ? -1 : b[1] / b[0];
	}

	/**
	 * The lowest sell price that still returns more than what was paid, after tax.
	 * The floor under every reprice suggestion: coaching a sell below this is
	 * coaching a realized loss, which is a decision, never a "reprice".
	 */
	static long breakevenSell(long avgCost)
	{
		long p = avgCost < 49 ? avgCost + 1 : (long) Math.ceil((avgCost + 1) / 0.98);
		if (geTax(p) >= 5_000_000)
		{
			p = avgCost + 5_000_001; // tax is capped up here; the 2% form overshoots
		}
		while (p - geTax(p) <= avgCost)
		{
			p++; // guard: the returned price must clear the cost
		}
		while (p - 1 - geTax(p - 1) > avgCost)
		{
			p--; // tighten: it is the FLOOR, not a padded number
		}
		return p;
	}

	/**
	 * What crossing the spread nets: buy at the instant-buy print, sell at the
	 * instant-sell print, pay the tax. Positive means the flip is genuinely quick —
	 * both sides fill on placement instead of waiting in a queue.
	 */
	static long instaNet(long low, long high)
	{
		return low - geTax(low) - high;
	}

	@Value
	static class Flip
	{
		int itemId;
		String name;
		int buyAt;
		int sellAt;
		int net;
		double roi;
		long gpHr;
		double unitsHr;
		boolean members;
		long cycleSecs;
		FlipLane lane;
		/** True when buyAt/sellAt are insta prices — both sides fill on placement. */
		boolean insta;
	}

	private final ItemMemory itemMemory;
	private final FillTimeModel fillModel;
	private final GeBrain brain;
	private final Champion champion;
	private final SurvivalFillModel survival;

	@Inject
	FlipService(OkHttpClient http, Gson gson, ItemMemory itemMemory, FillTimeModel fillModel,
		GeBrain brain, Champion champion, SurvivalFillModel survival)
	{
		this.http = http;
		this.gson = gson;
		this.itemMemory = itemMemory;
		this.fillModel = fillModel;
		this.brain = brain;
		this.champion = champion;
		this.survival = survival;
	}

	String survivalStatus()
	{
		return survival.status();
	}

	String fillModelStatus()
	{
		return fillModel.status();
	}

	String brainStatus()
	{
		return brain.status();
	}

	String championStatus()
	{
		return champion.status();
	}

	/** Player context from the plugin: carried coins + world type. */
	void setContext(long coins, boolean f2p)
	{
		budget = coins;
		f2pOnly = f2p;
	}

	/**
	 * How long a computed ranking is served from cache. The inputs only change
	 * on a 60s scan or a context push, so a second is generous.
	 */
	private static final long TOP_CACHE_MS = 1_000;

	private volatile List<Flip> cachedTop = List.of();
	private volatile List<Flip> cachedFrom;
	private volatile long cachedTopMs;
	private volatile long cachedTopBudget = Long.MIN_VALUE;

	/**
	 * Flips the PLAYER can actually do: world-type filtered, budget filtered,
	 * ranked by achievable gp/hr with their capital.
	 *
	 * <p><b>Memoized, and it has to be.</b> Two overlays call this from
	 * {@code render()}, which runs every frame — so this filter-and-sort over
	 * every quotable item in the game was running fifty-plus times a second on
	 * the client thread, and each pass also re-stamped {@link #suggested} with a
	 * fresh timestamp, which meant {@link #wasSuggested} could not expire for as
	 * long as the GE window stayed open. The cache is invalidated by a new scan
	 * (a different {@code allFlips}), by a change of budget, or by age.
	 */
	List<Flip> getTopFlips()
	{
		final long b = budget;
		final List<Flip> source = allFlips;
		final long now = System.currentTimeMillis();
		if (source == cachedFrom && b == cachedTopBudget && now - cachedTopMs < TOP_CACHE_MS)
		{
			return cachedTop;
		}
		final List<Flip> out = new ArrayList<>();
		boolean anyInsta = false;
		for (Flip f : source)
		{
			if (lanesOn && f.getLane() != FlipLane.QUICK)
			{
				continue; // the session lane is the quick lane; patience lives on the board
			}
			if (f2pOnly && f.isMembers())
			{
				continue;
			}
			if (b >= 0 && f.getBuyAt() > b)
			{
				continue;
			}
			if (remainingLimit(f.getItemId()) <= 0)
			{
				continue; // 4h buy limit spent — an offer would just sit, whatever the margin
			}
			// the ONLY affordability gate is the actual coin stack — a trader-level
			// price tier used to hide flips the player could pay for, which made the
			// level a tax on the bankroll instead of a scoreboard
			anyInsta |= f.isInsta();
			out.add(f);
		}
		// insta-only mode: when true insta margins exist they are the whole lane;
		// with none on the board the queue flips stay visible (honestly labeled)
		// rather than showing an empty box
		if (instaOnly && anyInsta)
		{
			out.removeIf(f -> !f.isInsta());
		}
		if (b > 0)
		{
			// VELOCITY, not margin: gp per cycle-second x learned memory. Proven-fast
			// items float up, recent stalls sink, unknowns keep a little exploration
			// optimism. A 2gp margin cycling every 40s beats a 50gp margin that sits
			// for eight minutes, and ranking by margin is what made sells stall.
			// insta margins outrank every queue flip: a price that fills on
			// placement is the only kind the quick lane can honestly call quick
			out.sort(Comparator.comparing(Flip::isInsta).reversed()
				.thenComparing(Comparator.comparingDouble((Flip f) ->
					-velocity(f, slotCapital(b))
						* itemMemory.scoreMultiplier(f.getItemId())
						* momentumFactor(f.getItemId())
						* flowFactor(ofi(f.getItemId()))
						* brainFactor(f.getItemId(), f.getBuyAt(), f.getNet()))));
		}
		final List<Flip> top = List.copyOf(out.subList(0, Math.min(8, out.size())));
		for (Flip f : top)
		{
			suggested.put(f.getItemId(), new long[]{f.getBuyAt(), f.getSellAt(), now});
			noteLane(f.getItemId(), f.getLane());
		}
		cachedTop = top;
		cachedFrom = source;
		cachedTopBudget = b;
		cachedTopMs = now;
		return top;
	}

	// suggestion attribution: did the user trade what we showed, near our price?
	private final Map<Integer, long[]> suggested = new ConcurrentHashMap<>(); // id -> {buy, sell, whenMs}
	// which lane we proposed an item UNDER, so its outcome is booked to that lane
	// even after the live book has moved on
	private final Map<Integer, long[]> suggestedLane = new ConcurrentHashMap<>(); // id -> {ordinal, whenMs}

	private void noteLane(int itemId, FlipLane lane)
	{
		suggestedLane.put(itemId, new long[]{lane.ordinal(), System.currentTimeMillis()});
	}

	/** The trap board proposes its picks outside the ranker — tag them too. */
	void notePatienceOrder(int itemId)
	{
		noteLane(itemId, FlipLane.LONG);
	}

	/**
	 * The lane an offer on this item belongs to. A lane we actually proposed wins
	 * for an hour; after that (or for an item we never suggested) the live book
	 * decides, which is the same rule the ranker uses.
	 */
	FlipLane laneFor(int itemId)
	{
		final long[] s = suggestedLane.get(itemId);
		if (s != null && System.currentTimeMillis() - s[1] < 60 * 60_000)
		{
			return s[0] == FlipLane.LONG.ordinal() ? FlipLane.LONG : FlipLane.QUICK;
		}
		final long[] bk = books.get(itemId);
		if (bk == null)
		{
			return FlipLane.QUICK;
		}
		return FlipLane.classify(estCycleSecs(itemId, bk[2]), bk[0], bk[1], bk[2], quickCeilingSecs);
	}

	/**
	 * gp per cycle-second for ONE slot: the quick lane's whole thesis in one
	 * number. Units in a cycle are whichever binds first — what the book and the
	 * buy limit will pass in that time, or what the slot's share of the coins can
	 * carry. Dividing by cycle time is what makes compounding visible: when the
	 * book is the constraint this reduces to gp/hr and cycle time drops out, and
	 * when capital is the constraint (a big bankroll on cheap items) the fastest
	 * turnaround wins, which is the regime this player is actually in.
	 */
	double velocity(Flip f, long slotCapital)
	{
		final long cyc = Math.max(1, f.getCycleSecs());
		final double throughput = f.getUnitsHr() * cyc / 3600.0;
		final double afford = slotCapital > 0
			? (double) slotCapital / Math.max(1, f.getBuyAt())
			: throughput;
		// three binds, tightest wins: what the book passes, what the coins carry,
		// what the 4h buy limit still allows
		final double units = Math.min(Math.min(throughput, afford),
			Math.max(0, remainingLimit(f.getItemId())));
		return f.getNet() * Math.max(0.001, units) / cyc;
	}

	/** One quick slot's share of the coins — the lane runs them in parallel. */
	long slotCapital(long coins)
	{
		return coins <= 0 ? 0 : (long) (coins * capitalShare());
	}

	/**
	 * How much of the bankroll one position may take: the champion's evolved
	 * concentration when it has an edge, otherwise an even split across the quick
	 * slots. A share is a share — it carries no time in it, so unlike the ROI
	 * floor it moves from the league's hourly bars to a live cycle untouched.
	 */
	double capitalShare()
	{
		return champion.conc(1.0 / Math.max(1, quickSlots));
	}

	/**
	 * Seconds for a full buy-then-sell cycle of this item.
	 *
	 * <p>This player's own measured fills win where they exist — a confidence blend
	 * over the prior, so two fills nudge and ten fills decide. With no history at
	 * all it is the prior alone. The prior is the fitted fill-time model when that
	 * model's own out-of-sample verdict says {@code adopt}, and otherwise the
	 * volume shape, which is a shape and not a measurement.
	 */
	long estCycleSecs(int itemId, long vol5m)
	{
		long prior = FlipLane.priorCycleSecs(vol5m);
		// The survival model is the only estimator that has seen the offers that
		// did NOT fill (fitted 2026-08-14: k=0.43 — decreasing hazard — and a
		// measured ~10min median side vs the shape prior's tens of seconds). Its
		// v0 features carry no volume, so it CALIBRATES the level while the
		// volume shape keeps the relative ordering: geometric blend of the two.
		if (survival.adopted())
		{
			final long[] bk = books.get(itemId);
			final long price = bk != null ? bk[0] + 1 : 1000;
			final long s = survival.cycleSecs(price,
				java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).getHour());
			if (s > 0)
			{
				prior = Math.max(1, (long) Math.sqrt((double) prior * s));
			}
		}
		else if (fillModel.adopted())
		{
			// qty is the order the ranker would actually place at the prior cycle
			// length — computed first so the model is never asked to predict from
			// a quantity that depends on its own answer
			final int[] lim = limits.get(itemId);
			final long[] bk = books.get(itemId);
			final long price = bk != null ? bk[0] + 1 : 0;
			final double units = Math.min(lim != null ? lim[0] : 100,
				vol5m * 12 * 0.05 * prior / 3600.0);
			final long modelled = fillModel.cycleSecs(price, (long) Math.max(1, units),
				java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).getHour());
			if (modelled > 0)
			{
				prior = modelled;
			}
		}
		final long observed = itemMemory.observedCycleSecs(itemId);
		if (observed <= 0)
		{
			return prior;
		}
		final ItemMemory.Stats st = itemMemory.statsFor(itemId);
		final double conf = st.getFills() / (st.getFills() + 3.0);
		return (long) Math.max(1, (1 - conf) * prior + conf * observed);
	}

	/** Last scan's cycle estimate, or -1 if the item was not in it. */
	long cycleSecsFor(int itemId)
	{
		final Long c = cycleSecs.get(itemId);
		return c == null ? -1 : c;
	}

	private final Map<Integer, Long> cycleSecs = new ConcurrentHashMap<>();

	/**
	 * Live quote + qty/profit coaching for ANY item (the offer-setup coach).
	 * The sell side is CAPPED at {@link #MAX_QUOTE_MULT}x the low side, so a whale
	 * overpay print can never become a price we read back to the player.
	 */
	long[] quoteFor(int itemId)
	{
		return quotes.get(itemId); // {buyAt, sellAt (capped), volHr} or null
	}

	/** Raw, uncapped book for display/analysis only: {low, high, vol5m, highTime}. */
	long[] bookFor(int itemId)
	{
		return books.get(itemId);
	}

	/**
	 * THE trap definition — one place, used by the reprice coach (mute), the flip
	 * ranker (exclude) and the trap board (these ARE the patience-order targets).
	 * A huge spread with no volume behind it is not a margin; it is one impatient
	 * buyer and an empty book.
	 *
	 * <p>Deliberately a PURE FUNCTION of one scan's quote data — no history, no
	 * memory, no blacklist. An item is a trap only while its book looks like one;
	 * the moment liquidity comes back it is an ordinary flip candidate again. Any
	 * recovery/revisit logic built on top can rely on that.
	 */
	static boolean trapBook(long low, long high, long vol5m)
	{
		return low > 0 && high >= low * MAX_QUOTE_MULT && vol5m < THIN_VOL_5M;
	}

	/** Live classification, re-decided for every item on every 60s scan. */
	boolean isTrap(int itemId)
	{
		return trapItems.contains(itemId);
	}

	/** Highest price we will ever coach for this item, whatever the tape says. */
	long saneCeiling(int itemId)
	{
		final long[] b = books.get(itemId);
		return b == null ? Long.MAX_VALUE : (long) (b[0] * MAX_QUOTE_MULT);
	}

	int limitFor(int itemId)
	{
		final int[] l = limits.get(itemId);
		return l != null ? l[0] : 0;
	}

	String nameFor(int itemId)
	{
		return names.getOrDefault(itemId, "item");
	}

	long getBudget()
	{
		return budget;
	}

	boolean wasSuggested(int itemId, int price, boolean buying)
	{
		final long[] s = suggested.get(itemId);
		if (s == null || System.currentTimeMillis() - s[2] > 45 * 60_000)
		{
			return false;
		}
		final long ref = buying ? s[0] : s[1];
		return ref > 0 && Math.abs(price - ref) <= ref * 0.03;
	}

	/**
	 * Momentum gate learned from the battleaxe loss: buying into a falling
	 * mid is how "margins" become losses. Falling -> 0.3x, flat/gently
	 * rising -> boosted, vertical spike -> damped (mean reversion risk).
	 *
	 * <p>The two cut points are the champion genome's when it has an edge, and
	 * the hand-tuned ones otherwise.
	 */
	private double momentumFactor(int itemId)
	{
		final Double m = momentum.get(itemId);
		if (m == null)
		{
			return 1.0;
		}
		if (m < champion.momBuyCut())
		{
			return 0.3;   // falling knife
		}
		if (m > champion.momSpikeCut())
		{
			return 0.6;   // spike — likely reverts before your sell fills
		}
		if (m > 0.005)
		{
			return 1.15;  // gentle rise: the sell side is coming to meet you
		}
		return 1.0;
	}

	/**
	 * The backtrained brain's opinion, as a ranking multiplier only.
	 *
	 * <p>It compares the sell print the brain expects against the one the book
	 * currently supports: a forecast below our ask demotes the item, a forecast
	 * above it promotes. Clamped hard both ways because the brain learned on 1h
	 * and 5m bars and is being asked about a 60-second step — an unresolved
	 * horizon gap that makes it a tie-breaker, not an oracle. Returns 1.0 when
	 * the brain is absent or lost to its baseline.
	 */
	private double brainFactor(int itemId, int buyAt, int net)
	{
		if (!brain.adopted() || net <= 0)
		{
			return 1.0;
		}
		final long[] b = books.get(itemId);
		if (b == null)
		{
			return 1.0;
		}
		final Long prevMid = prevMids.get(itemId);
		final long predSell = brain.predictedSell(itemId, b[0], b[1], b[2],
			prevMid == null ? 0 : prevMid,
			java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).getHour());
		if (predSell <= 0)
		{
			return 1.0;
		}
		final double predNet = predSell - geTax(predSell) - buyAt;
		return Math.max(0.25, Math.min(2.0, predNet / net));
	}

	// ================= anomaly watch =================

	/**
	 * A price that ripped away from where it was — the blue moons the whole
	 * flip engine is deliberately unable to judge.
	 *
	 * <p>An RWT pump, an update panic and a bot farm getting banned all look
	 * identical to a ranker: a big move on real volume. Which one it is decides
	 * whether you sell into it or hold, and that call needs the news, the
	 * patch notes and a human. So this is reported, never acted on.
	 */
	@Value
	static class Anomaly
	{
		int itemId;
		String name;
		long refMid;       // where the mid was at the start of the window
		long mid;          // where it is now
		double movePct;    // signed, over the window
		double scanPct;    // signed, over the last scan alone
		long vol5m;
		int spanMins;      // how far back the window the move is measured over reaches
	}

	private volatile List<Anomaly> freshAnomalies = List.of();
	private volatile double anomalyPct = 25;

	/** Move size that counts as a dislocation, from config. */
	void setAnomalyThreshold(double percent)
	{
		anomalyPct = Math.max(5, percent);
	}

	/**
	 * THE anomaly predicate. Pure, so the threshold behaviour is testable without
	 * a client or a live book.
	 *
	 * <p>Two gates, and the second one is the whole point. Volume has to be real
	 * ({@link #THIN_VOL_5M}), and the move has to show up on the instant-SELL
	 * side too. One whale overpaying moves the instant-buy print and therefore
	 * the mid, while the price everyone else is actually trading at has not moved
	 * at all — that is a print, not a dislocation, and the trap board already
	 * exists to farm it. Requiring the low side to travel at least half as far,
	 * in the same direction, is what separates "the market repriced" from "one
	 * person typed a big number".
	 */
	static boolean anomalous(long refMid, long mid, long refLow, long low, long vol5m, double thresholdPct)
	{
		if (refMid <= 0 || refLow <= 0 || low <= 0 || vol5m < THIN_VOL_5M)
		{
			return false;
		}
		final double midMove = (mid - refMid) * 100.0 / refMid;
		if (Math.abs(midMove) < thresholdPct)
		{
			return false;
		}
		final double lowMove = (low - refLow) * 100.0 / refLow;
		return Math.signum(lowMove) == Math.signum(midMove)
			&& Math.abs(lowMove) >= thresholdPct / 2;
	}

	/**
	 * Everything the last scan flagged, biggest move first. Drains: each scan's
	 * findings are handed over once, so the trigger side cannot re-alert on the
	 * same measurement just because it looked again.
	 */
	List<Anomaly> drainAnomalies()
	{
		final List<Anomaly> out = freshAnomalies;
		freshAnomalies = List.of();
		return out;
	}

	static int geTax(int sellPrice)
	{
		return (int) geTax((long) sellPrice);
	}

	/** Long form for callers whose price can exceed int range (brain forecasts). */
	static long geTax(long sellPrice)
	{
		if (sellPrice < 50)
		{
			return 0;
		}
		return (long) Math.min(sellPrice * 0.02, 5_000_000);
	}

	void maybeRefresh()
	{
		final long now = System.currentTimeMillis();
		if (now - lastFast.get() < FAST_MS)
		{
			return;
		}
		lastFast.set(now);
		final boolean slowDue = now - lastSlow.get() >= SLOW_MS || cachedFive == null;
		if (slowDue)
		{
			lastSlow.set(now);
		}
		if (names.isEmpty())
		{
			get("/mapping", body ->
			{
				for (JsonElement e : gson.fromJson(body, com.google.gson.JsonArray.class))
				{
					final JsonObject o = e.getAsJsonObject();
					final int id = o.get("id").getAsInt();
					names.put(id, o.get("name").getAsString());
					membersItem.put(id, o.has("members") && o.get("members").getAsBoolean());
					limits.put(id, new int[]{o.has("limit") && !o.get("limit").isJsonNull()
						? o.get("limit").getAsInt() : 100});
				}
				fetchPrices(true);
			});
		}
		else
		{
			fetchPrices(slowDue);
		}
	}

	private void fetchPrices(boolean refreshVolumes)
	{
		if (refreshVolumes)
		{
			get("/5m", fiveBody ->
			{
				cachedFive = fiveBody;
				fetchLatestAndCompute();
			});
		}
		else
		{
			fetchLatestAndCompute();
		}
	}

	private void fetchLatestAndCompute()
	{
		final String fiveBody = cachedFive;
		if (fiveBody == null)
		{
			return;
		}
		get("/latest", latestBody ->
		{
			// all learned artifacts load here: an OkHttp callback thread, never
			// the client thread, and every one of them is gated on its own verdict
			fillModel.ensureLoaded();
			brain.ensureLoaded();
			champion.ensureLoaded();
			survival.ensureLoaded();
			final JsonObject latest = gson.fromJson(latestBody, JsonObject.class).getAsJsonObject("data");
			final JsonObject five = gson.fromJson(fiveBody, JsonObject.class).getAsJsonObject("data");
			final List<Flip> flips = new ArrayList<>();
			final List<Anomaly> found = new ArrayList<>();
			double errSum = 0;
			int errN = 0;
			for (Map.Entry<String, JsonElement> e : latest.entrySet())
			{
				final JsonObject q = e.getValue().getAsJsonObject();
				if (q.get("high").isJsonNull() || q.get("low").isJsonNull())
				{
					continue;
				}
				final int id = Integer.parseInt(e.getKey());
				final String name = names.get(id);
				if (name == null)
				{
					continue;
				}
				final int high = q.get("high").getAsInt();
				final int low = q.get("low").getAsInt();
				// stale-spread guard: both sides must have traded RECENTLY, or the
				// "margin" is a mirage nobody is actually paying (0/100 fills)
				final long nowS = System.currentTimeMillis() / 1000;
				final long highT = q.has("highTime") && !q.get("highTime").isJsonNull() ? q.get("highTime").getAsLong() : 0;
				final long lowT = q.has("lowTime") && !q.get("lowTime").isJsonNull() ? q.get("lowTime").getAsLong() : 0;
				final boolean fresh = nowS - highT < 600 && nowS - lowT < 600;
				long vol = 0;
				final JsonElement v5 = five.get(e.getKey());
				if (v5 != null)
				{
					final JsonObject v = v5.getAsJsonObject();
					// SPLIT, not summed: highPriceVolume is buyer-initiated (someone
					// crossed to the ask), lowPriceVolume is seller-initiated. Their
					// imbalance is the order-flow signal; their sum is just liquidity.
					final long bv = v.has("highPriceVolume") ? v.get("highPriceVolume").getAsLong() : 0;
					final long sv = v.has("lowPriceVolume") ? v.get("lowPriceVolume").getAsLong() : 0;
					vol = bv + sv;
					flow.put(id, new long[]{bv, sv});
				}
				// PREDICT -> SCORE -> PUNISH: last scan forecast "mid holds";
				// grade it now, demote items that surprised us (negative reward)
				// long math: high + low overflows int on anything above ~1.07B gp
				final long mid = ((long) high + low) / 2;
				final Long pred = predictedMid.get(id);
				if (pred != null && pred > 0)
				{
					final double err = Math.abs(mid - pred) / (double) pred;
					itemMemory.recordPredictionError(id, err);
					errSum += err;
					errN++;
				}
				predictedMid.put(id, mid);
				// momentum over the rolling window of scans: the falling-knife detector
				final java.util.ArrayDeque<Long> h = midHist.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
				final java.util.ArrayDeque<Long> lh = lowHist.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
				if (!h.isEmpty())
				{
					prevMids.put(id, h.peekLast()); // the brain's momentum feature
				}
				// ANOMALY WATCH, judged before this scan joins the window: has the
				// price ripped away from where the window started, on volume, on
				// both sides? Measured here because the 60s quote refresh is already
				// the only price poller — a watcher with its own timer would be a
				// second one, disagreeing with this one on every boundary.
				if (fresh && h.size() >= 3 && lh.size() >= 3
					&& !trapBook(low, high, vol)
					&& anomalous(h.peekFirst(), mid, lh.peekFirst(), low, vol, anomalyPct))
				{
					final long refMid = h.peekFirst();
					final Long lastMid = h.peekLast();
					found.add(new Anomaly(id, name, refMid, mid,
						(mid - refMid) * 100.0 / refMid,
						lastMid == null || lastMid <= 0 ? 0 : (mid - lastMid) * 100.0 / lastMid,
						vol, (int) Math.max(1, h.size() * FAST_MS / 60_000)));
				}
				h.addLast(mid);
				lh.addLast((long) low);
				while (h.size() > WINDOW_SCANS)
				{
					h.removeFirst();
				}
				while (lh.size() > WINDOW_SCANS)
				{
					lh.removeFirst();
				}
				if (h.size() >= 3)
				{
					momentum.put(id, (mid - h.peekFirst()) / (double) h.peekFirst());
				}
				// SANITY CAP before anything downstream sees it: the coached sell
				// side is min(book high, 3x the low side). On a healthy item the cap
				// never binds; on a trap item it is the difference between "rebuy @
				// 45,080" and a number a human would actually pay.
				final long cappedSell = Math.min(high - 1L, (long) (low * MAX_QUOTE_MULT));
				books.put(id, new long[]{low, high, vol, highT});
				quotes.put(id, new long[]{low + 1, Math.max(low + 1, cappedSell), vol * 12});
				final boolean trap = trapBook(low, high, vol);
				if (trap)
				{
					trapItems.add(id);
				}
				else
				{
					trapItems.remove(id);
				}
				final int buyAt = low + 1;
				final int sellAt = (int) Math.max(buyAt, cappedSell);
				final int net = sellAt - geTax(sellAt) - buyAt;
				final long cyc = estCycleSecs(id, vol);
				cycleSecs.put(id, cyc);
				final FlipLane lane = FlipLane.classify(cyc, low, high, vol, quickCeilingSecs);
				// RECOVERY, not a clock: an item this player gave up on becomes
				// suggestible again the moment its own book shows it cycling for a
				// profit at a reasonable speed for a few scans running.
				itemMemory.recordBookHealth(id, fresh && !trap && vol >= THIN_VOL_5M
					&& net > 0 && lane == FlipLane.QUICK);
				// Trap items are never spread grinds. Today the liquidity+freshness
				// filters below already keep all 76 known whale-corridor items out of
				// the ranking (measured), but that is a side effect of thresholds the
				// ranking work will keep re-tuning — so say it outright here. Judged
				// on THIS scan only: an item whose book recovers is eligible again on
				// the very next refresh.
				if (trap)
				{
					continue;
				}
				if (low < 100 || vol < THIN_VOL_5M || !fresh)
				{
					continue;
				}
				// INSTA candidate first: crossing the spread — buy at the instant-buy
				// print, sell at the instant-sell print — still profits after tax.
				// Rare, and the only flip that is quick the way the word means it:
				// both sides fill on placement instead of waiting in a queue.
				final long iNet = instaNet(low, high);
				final double iRoi = iNet / (double) high;
				if (iNet > 0 && iRoi <= 0.30 && iRoi >= champion.roiFloorFor(INSTA_CYCLE_SECS))
				{
					final double iUnitsHr = Math.min(limits.get(id)[0] / 4.0, vol * 12 * champion.volShare());
					flips.add(new Flip(id, name, high, low, (int) iNet,
						iNet * 100.0 / high, (long) (iNet * iUnitsHr), iUnitsHr,
						membersItem.getOrDefault(id, true), INSTA_CYCLE_SECS, FlipLane.QUICK, true));
					continue;
				}
				// ROI over 30% on a liquid item = stale outlier price, not free money.
				// The floor underneath is the champion's, converted from its hourly
				// bar to THIS cycle so it stays a rate of return rather than an
				// impossible demand on a forty-second flip.
				final double roi = net / (double) buyAt;
				if (net <= 0 || roi > 0.30 || roi < champion.roiFloorFor(cyc))
				{
					continue;
				}
				final double unitsHr = Math.min(limits.get(id)[0] / 4.0, vol * 12 * champion.volShare());
				flips.add(new Flip(id, name, buyAt, sellAt, net,
					net * 100.0 / buyAt, (long) (net * unitsHr), unitsHr,
					membersItem.getOrDefault(id, true), cyc, lane, false));
			}
			flips.sort(Comparator.comparingLong(f -> -f.gpHr));
			allFlips = flips;
			found.sort(Comparator.comparingDouble(a -> -Math.abs(a.movePct)));
			freshAnomalies = found;
			itemMemory.flush(); // one write for the whole scan's memory updates
			lastScanAvgErr = errN > 0 ? errSum / errN : -1;
			log.info("flip scan: {} candidates, {} anomalies, market surprise {} | brain {} | champion {} | fill model {} | survival {}",
				flips.size(), found.size(),
				lastScanAvgErr >= 0 ? String.format("%.2f%%", lastScanAvgErr * 100) : "n/a",
				brain.status(), champion.status(), fillModel.status(), survival.status());
		});
	}

	private void get(String path, java.util.function.Consumer<String> onBody)
	{
		final Request req = new Request.Builder()
			.url(API + path)
			.header("User-Agent", UA)
			.build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("wiki price fetch failed: {}", path, e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onBody.accept(r.body().string());
					}
				}
				catch (Exception ex)
				{
					log.warn("flip parse failed: {}", path, ex);
				}
			}
		});
	}
}
