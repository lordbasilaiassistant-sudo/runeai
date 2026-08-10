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

	private final OkHttpClient http;
	private final Gson gson;

	private final Map<Integer, int[]> limits = new ConcurrentHashMap<>();   // id -> {limit}
	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> membersItem = new ConcurrentHashMap<>();
	private volatile List<Flip> allFlips = List.of();
	private final Map<Integer, long[]> quotes = new ConcurrentHashMap<>(); // id -> {buyAt, sellAt (capped), volHr}
	private final Map<Integer, long[]> books = new ConcurrentHashMap<>();  // id -> {low, high RAW, vol5m, highTime}
	private final java.util.Set<Integer> trapItems = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Long> predictedMid = new ConcurrentHashMap<>(); // last scan's forecast
	private final Map<Integer, java.util.ArrayDeque<Long>> midHist = new ConcurrentHashMap<>(); // ~30min window
	private final Map<Integer, Double> momentum = new ConcurrentHashMap<>();   // slope over the window
	volatile double lastScanAvgErr = -1; // global market-surprise gauge, logged each scan
	private final AtomicLong lastFast = new AtomicLong();
	private final AtomicLong lastSlow = new AtomicLong();
	private volatile String cachedFive;
	private volatile long budget = -1;       // carried coins; -1 = unknown
	private volatile boolean f2pOnly;
	private volatile long traderMaxPrice = Long.MAX_VALUE;
	private volatile int traderLevel = 1;
	private volatile long quickCeilingSecs = FlipLane.DEFAULT_QUICK_CYCLE_SECS;
	private volatile boolean lanesOn = true;
	private volatile int quickSlots = 3;

	void setTraderTier(int level, long maxPrice)
	{
		traderLevel = level;
		traderMaxPrice = maxPrice;
	}

	/** Lane boundary and slot split from config; pushed in with the other context. */
	void setLaneConfig(boolean enabled, long quickCycleSecs, int quickLaneSlots)
	{
		lanesOn = enabled;
		quickCeilingSecs = Math.max(30, quickCycleSecs);
		quickSlots = Math.max(1, quickLaneSlots);
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
	}

	private final ItemMemory itemMemory;
	private final FillTimeModel fillModel;

	@Inject
	FlipService(OkHttpClient http, Gson gson, ItemMemory itemMemory, FillTimeModel fillModel)
	{
		this.http = http;
		this.gson = gson;
		this.itemMemory = itemMemory;
		this.fillModel = fillModel;
	}

	String fillModelStatus()
	{
		return fillModel.status();
	}

	/** Player context from the plugin: carried coins + world type. */
	void setContext(long coins, boolean f2p)
	{
		budget = coins;
		f2pOnly = f2p;
	}

	/** Flips the PLAYER can actually do: world-type filtered, budget filtered,
	 *  ranked by achievable gp/hr with their capital. */
	List<Flip> getTopFlips()
	{
		final long b = budget;
		final List<Flip> out = new ArrayList<>();
		for (Flip f : allFlips)
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
			if (f.getBuyAt() > traderMaxPrice)
			{
				continue; // above this trader level's tier — level up to unlock
			}
			out.add(f);
		}
		if (b > 0)
		{
			// VELOCITY, not margin: gp per cycle-second x learned memory. Proven-fast
			// items float up, recent stalls sink, unknowns keep a little exploration
			// optimism. A 2gp margin cycling every 40s beats a 50gp margin that sits
			// for eight minutes, and ranking by margin is what made sells stall.
			out.sort(Comparator.comparingDouble(f ->
				-velocity(f, slotCapital(b))
					* itemMemory.scoreMultiplier(f.getItemId())
					* momentumFactor(f.getItemId())));
		}
		final List<Flip> top = out.subList(0, Math.min(8, out.size()));
		final long now = System.currentTimeMillis();
		for (Flip f : top)
		{
			suggested.put(f.getItemId(), new long[]{f.getBuyAt(), f.getSellAt(), now});
			noteLane(f.getItemId(), f.getLane());
		}
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
		return f.getNet() * Math.max(0.001, Math.min(throughput, afford)) / cyc;
	}

	/** One quick slot's share of the coins — the lane runs them in parallel. */
	long slotCapital(long coins)
	{
		return coins <= 0 ? 0 : (long) (coins * capitalShare());
	}

	/** Even split across the quick slots. #4 replaces this with the evolved genome. */
	double capitalShare()
	{
		return 1.0 / Math.max(1, quickSlots);
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
		if (fillModel.adopted())
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
	 */
	private double momentumFactor(int itemId)
	{
		final Double m = momentum.get(itemId);
		if (m == null)
		{
			return 1.0;
		}
		if (m < -0.01)
		{
			return 0.3;   // falling knife
		}
		if (m > 0.05)
		{
			return 0.6;   // spike — likely reverts before your sell fills
		}
		if (m > 0.005)
		{
			return 1.15;  // gentle rise: the sell side is coming to meet you
		}
		return 1.0;
	}

	static int geTax(int sellPrice)
	{
		if (sellPrice < 50)
		{
			return 0;
		}
		return (int) Math.min(sellPrice * 0.02, 5_000_000);
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
			fillModel.ensureLoaded(); // off the client thread, and needed below
			final JsonObject latest = gson.fromJson(latestBody, JsonObject.class).getAsJsonObject("data");
			final JsonObject five = gson.fromJson(fiveBody, JsonObject.class).getAsJsonObject("data");
			final List<Flip> flips = new ArrayList<>();
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
					vol = (v.has("highPriceVolume") ? v.get("highPriceVolume").getAsLong() : 0)
						+ (v.has("lowPriceVolume") ? v.get("lowPriceVolume").getAsLong() : 0);
				}
				// PREDICT -> SCORE -> PUNISH: last scan forecast "mid holds";
				// grade it now, demote items that surprised us (negative reward)
				final long mid = (high + low) / 2;
				final Long pred = predictedMid.get(id);
				if (pred != null && pred > 0)
				{
					final double err = Math.abs(mid - pred) / (double) pred;
					itemMemory.recordPredictionError(id, err);
					errSum += err;
					errN++;
				}
				predictedMid.put(id, mid);
				// momentum over the last ~30 min of scans: the falling-knife detector
				final java.util.ArrayDeque<Long> h = midHist.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
				h.addLast(mid);
				while (h.size() > 6)
				{
					h.removeFirst();
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
				// ROI over 30% on a liquid item = stale outlier price, not free money
				if (net <= 0 || net * 100.0 / buyAt > 30)
				{
					continue;
				}
				final double unitsHr = Math.min(limits.get(id)[0] / 4.0, vol * 12 * 0.05);
				flips.add(new Flip(id, name, buyAt, sellAt, net,
					net * 100.0 / buyAt, (long) (net * unitsHr), unitsHr,
					membersItem.getOrDefault(id, true), cyc, lane));
			}
			flips.sort(Comparator.comparingLong(f -> -f.gpHr));
			allFlips = flips;
			itemMemory.flush(); // one write for the whole scan's memory updates
			lastScanAvgErr = errN > 0 ? errSum / errN : -1;
			log.info("flip scan: {} candidates, market surprise {}",
				flips.size(), lastScanAvgErr >= 0 ? String.format("%.2f%%", lastScanAvgErr * 100) : "n/a");
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
