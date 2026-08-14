package com.runeai;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.GameObject;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "RuneAI",
	description = "AI buddy for OSRS — idle click guidance, voice callouts, lip-syncing mascot, smart loot alerts, session P&L",
	tags = {"ai", "runeai", "assistant", "overlay"}
)
public class RuneAIPlugin extends Plugin
{
	private static final File DATA_DIR = new File(RuneLite.RUNELITE_DIR, "runeai");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RuneAIConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	@Inject
	private RuneAIOverlay overlay;

	@Inject
	private GeFlipOverlay geFlipOverlay;

	@Inject
	private GeSlotStampOverlay geSlotStampOverlay;

	@Inject
	private VoicePlayer voice;

	@Inject
	private MascotOverlay mascot;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private FlipService flipService;

	@Inject
	private ItemMemory itemMemory;

	@Inject
	private TrapBoard trapBoard;

	@Inject
	private SessionHistory sessionHistory;

	@Inject
	private DangerModel dangerModel;

	@Inject
	private StreamerService streamer;

	@Inject
	private net.runelite.client.chat.ChatCommandManager chatCommandManager;

	// trade collection log: items PROFITABLY flipped (real buy->sell) at least once
	private final Set<Integer> tradeClog = new java.util.HashSet<>();
	private long lastFlipGpHr;

	// realized GE flip tracking (fills are post-tax in the coins we receive)
	private final Map<Integer, long[]> flipBasis = new java.util.HashMap<>(); // id -> {qty, totalCost}
	private long flipRealized;

	// per-slot offer lifecycle: fill TIME is the pph lever
	private static final class OfferTrack
	{
		int itemId;
		int price;
		boolean buying;
		long startMs;
		int lastQty;      // for delta accounting: partial fills count immediately
		long lastSpent;
		long lastFillMs;  // stall detection: time since anything moved
		long realized;    // profit booked across ALL this offer's partials, not just the last one
		// the lane this offer was PLACED under. Frozen at placement: an overnight
		// trap whose book heals by morning is still an overnight outcome.
		FlipLane lane = FlipLane.QUICK;
	}
	private final OfferTrack[] offerTracks = new OfferTrack[8];
	private Map<String, long[]> savedOfferState = new java.util.HashMap<>();
	private final List<Long> buyFillSecs = new ArrayList<>();
	private final List<Long> sellFillSecs = new ArrayList<>();
	private int sugFills, sugCancels;
	private long unitsBought, unitsSold;
	private long firstOfferMs;
	private long sessionStartMs;
	private long lifetimeRealized; // persists in trader.json — all-time flip profit
	private long lastFlipScanLogMs;
	private long lastLoginMs;
	private int sessionFills;    // offers completed THIS session — the scoreboard's count

	// ---- TRADER: the flipping skill. profit gp -> xp on the real OSRS curve;
	// each level raises the max item value you can flip (lvl 99 ~ max cash play)
	private static final int[] TRADER_XP = new int[100];
	static
	{
		int acc = 0;
		for (int n = 1; n < 100; n++)
		{
			acc += (int) Math.floor(n + 300 * Math.pow(2, n / 7.0));
			TRADER_XP[n] = acc / 4;
		}
	}
	private double traderXp;
	private int traderLevel = 1;

	static int traderLevelFor(double xp)
	{
		int lvl = 1;
		for (int l = 2; l < 100; l++)
		{
			if (xp >= TRADER_XP[l - 1])
			{
				lvl = l;
			}
		}
		return lvl;
	}

	private Gson prettyGson;
	private RuneAIPanel panel;
	private NavigationButton navButton;
	private EventLog eventLog;
	private EventLog tickLog;
	private boolean greeted;
	private int snapshotAtTick = -1;
	private int damageTakenThisTick;
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());

	// ---- activity/guidance state ----
	private static final Set<Skill> COMBAT_SKILLS = Set.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS);
	private final Map<Skill, Integer> lastXp = new java.util.EnumMap<>(Skill.class);
	private final Map<Skill, Integer> lastLevel = new java.util.EnumMap<>(Skill.class);
	private Skill lastXpSkill;
	private int lastXpTick = -1000;
	private int lastTargetNpcId = -1;
	private WorldPoint lastPos;
	private int idleTicks;
	private int lastGuideTick;
	private int lastPotRemindTick;
	private int lastKillTick = -1000;
	private boolean wasUnderAttack;
	/** HP percentage points the danger prior is currently adding to the EAT threshold. */
	private int dangerBoost;
	private boolean streamerStarted;

	// pet follower state: Rune trails the player like a real OSRS pet
	private WorldPoint petTile;
	private WorldPoint petPrevTile;

	// ---- session P&L ledger (inventory+equipment deltas at GE value) ----
	private Map<Integer, Integer> lastHolding;
	private long sessionPnl;

	// ---- goal inference: gp grind vs xp grind ----
	private long xpGainedSession;
	private long gainedValue;
	private long droppedValue;
	private int lastDropTick = -1000;
	private int lastFoodWarnTick = -1000;
	private int lastEatEventTick = -1000;
	private int lastDropClickItemId = -1;
	private int lastDropClickTick = -1000;

	// ---- F2P bond ladder: total worth vs live GE bond price ----
	private long bankValue = -1; // unknown until the bank is opened once
	private boolean bondAnnounced;

	// bones worth burying on sight — prayer xp beats their GE price (big bones and up)
	private static final Set<String> PRAYER_BONES = Set.of(
		"big bones", "babydragon bones", "dragon bones", "wyvern bones",
		"wyrm bones", "drake bones", "hydra bones", "lava dragon bones",
		"dagannoth bones", "superior dragon bones");


	@Override
	protected void startUp() throws Exception
	{
		DATA_DIR.mkdirs();
		prettyGson = gson.newBuilder().setPrettyPrinting().create();
		resetSessionState();

		// one-time migration: the old loot defaults (100 gp / 0.05%) proved too chatty
		// and were already persisted into the profile — unset so the new defaults apply
		if (config.minLootValue() == 100)
		{
			configManager.unsetConfiguration("runeai", "minLootValue");
		}
		if (config.lootWorthPercent() == 0.05)
		{
			configManager.unsetConfiguration("runeai", "lootWorthPercent");
		}

		if (config.logEvents())
		{
			final File f = new File(DATA_DIR, "events-" + LocalDateTime.now().format(STAMP) + ".jsonl");
			eventLog = new EventLog(f, gson);
			log.info("RuneAI event stream -> {}", f.getAbsolutePath());
		}

		if (config.recordTicks())
		{
			final File f = new File(DATA_DIR, "ticks-" + LocalDateTime.now().format(STAMP) + ".jsonl");
			tickLog = new EventLog(f, gson);
			log.info("RuneAI tick vectors -> {}", f.getAbsolutePath());
		}

		panel = new RuneAIPanel();
		final BufferedImage icon = RuneAIPanel.createIcon();
		navButton = NavigationButton.builder()
			.tooltip("RuneAI")
			.icon(icon)
			.priority(1)
			.panel(panel)
			.build();
		sessionStartMs = System.currentTimeMillis();
		// a session survives logging out — waiting on offers IS part of the session.
		// Resuming restores the counters AND moves sessionStartMs back to the real
		// start, which is also what lets offers placed before the relaunch book
		// into this session instead of being quarantined as carryover.
		final SessionHistory.Session resumed = config.sessionResume()
			? sessionHistory.resumeIfRecent(sessionStartMs, config.sessionGapMins() * 60_000L)
			: null;
		if (resumed != null)
		{
			sessionStartMs = resumed.getStartMs();
			flipRealized = resumed.getFlipPnl();
			sessionPnl = resumed.getLedgerPnl();
			unitsBought = resumed.getUnitsBought();
			unitsSold = resumed.getUnitsSold();
			sessionFills = resumed.getFills();
			// keep the ACTIVE-time clock: offline waiting must not dilute gp/h,
			// so the first-offer mark is rebuilt from the accumulated active time
			firstOfferMs = resumed.getActiveMs() > 0
				? System.currentTimeMillis() - resumed.getActiveMs() : 0;
		}
		else
		{
			sessionHistory.begin(sessionStartMs);
		}
		voice.start(); // the queue is torn down in shutDown(); re-arm it
		dangerModel.loadAsync(); // reads a file: never on the client thread
		// the co-host is not started at all unless the player asked for it: with the
		// toggle off nothing here ever collects a beat or builds a request
		streamerStarted = config.streamerMode();
		if (streamerStarted)
		{
			streamer.start();
		}
		panel.setViewsEnabled(config.sessionScore(), config.itemStats(), config.anomalyAlert());
		loadFlipBasis();
		loadTrader();
		loadClog();
		loadOfferState();
		loadBookedOffers();
		// a resumed session and the lifetime total are already known — show them
		// now rather than after the first sell
		panel.setFlipPnl(flipRealized, lifetimeRealized);
		panel.setPnl(sessionPnl);
		chatCommandManager.registerCommandAsync("!profit", (msg, txt) ->
		{
			msg.getMessageNode().setValue(String.format(
				"<col=00b4ff>RuneAI</col> flips: %,d gp this session (%,d gp/h) · %,d gp all-time",
				flipRealized, lastFlipGpHr, lifetimeRealized));
			clientThread.invoke(() -> client.refreshChat());
		});
		chatCommandManager.registerCommandAsync("!lvl", (msg, txt) ->
		{
			if (txt != null && txt.toLowerCase().contains("trader"))
			{
				msg.getMessageNode().setValue(String.format(
					"<col=00b4ff>Trader level %d</col> — %,.0f xp · %d items in trade log",
					traderLevel, traderXp, tradeClog.size()));
				clientThread.invoke(() -> client.refreshChat());
			}
		});
		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);
		overlayManager.add(mascot);
		overlayManager.add(geFlipOverlay);
		overlayManager.add(geSlotStampOverlay);
		log.info("RuneAI plugin started (data dir {})", DATA_DIR.getAbsolutePath());
	}

	/**
	 * Wipe everything that belongs to ONE run of the plugin.
	 *
	 * <p>RuneLite does not build a new plugin object when you toggle RuneAI off
	 * and on — {@code startUp()} runs again on the same instance with all of its
	 * fields exactly as the last run left them. Every {@code loadX()} below then
	 * reads the same file into the same live collection a second time, and
	 * {@code bookedOffers} in particular is an {@code addAll}: one disable/enable
	 * and the trade audit is diffing the game's history against a doubled ledger.
	 * The session counters have the milder version of the same problem — a "new"
	 * session that opens holding the previous one's P&L.
	 */
	private void resetSessionState()
	{
		bookedOffers.clear();
		flipBasis.clear();
		tradeClog.clear();
		savedOfferState = new java.util.HashMap<>();
		java.util.Arrays.fill(offerTracks, null);
		buyFillSecs.clear();
		sellFillSecs.clear();
		anomalyFeed.clear();
		lastAnomalyAlertMs.clear();
		lastXp.clear();
		lastLevel.clear();
		flipRealized = 0;
		sessionPnl = 0;
		gainedValue = 0;
		droppedValue = 0;
		xpGainedSession = 0;
		sessionFills = 0;
		unitsBought = 0;
		unitsSold = 0;
		sugFills = 0;
		sugCancels = 0;
		firstOfferMs = 0;
		lastFlipGpHr = 0;
		lastFlipScanLogMs = 0;
		bankValue = -1;
		bondAnnounced = false;
		lastHolding = null;
		greeted = false;
		snapshotAtTick = -1;
		clogPopulated = false; // the panel is rebuilt too — it needs repopulating
		historyReadTick = -1;
		historyRetried = false;
		historyRawLogged = false;
		dangerBoost = 0;
		wasUnderAttack = false;
		petTile = null;
		petPrevTile = null;
	}

	@Override
	protected void shutDown() throws Exception
	{
		chatCommandManager.unregisterCommand("!profit");
		chatCommandManager.unregisterCommand("!lvl");
		streamer.stop();
		voice.shutdown();
		sessionHistory.finish();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(overlay);
		overlayManager.remove(mascot);
		overlayManager.remove(geFlipOverlay);
		overlayManager.remove(geSlotStampOverlay);
		if (eventLog != null)
		{
			eventLog.close();
			eventLog = null;
		}
		if (tickLog != null)
		{
			tickLog.close();
			tickLog = null;
		}
		// the ledgers are written off-thread; make sure the last snapshot landed
		AsyncWriter.flush(2_000);
		log.info("RuneAI plugin stopped");
	}

	// ================= snapshot =================

	private void writeSnapshot()
	{
		try
		{
			// the CAPTURE has to be on the client thread — it walks the whole scene.
			// The write does not, and this is the biggest file RuneAI produces.
			final Map<String, Object> snap = GameStateSnapshot.capture(client);
			final File f = new File(DATA_DIR, "snapshot-" + LocalDateTime.now().format(STAMP) + ".json");
			final String json = prettyGson.toJson(snap);
			AsyncWriter.write(f, json);
			log.info("RuneAI snapshot queued: {} ({} KB)", f.getAbsolutePath(), json.length() / 1024);
		}
		catch (Exception ex)
		{
			log.error("snapshot failed", ex);
		}
	}

	// ================= event stream =================

	private void emit(String type, Map<String, Object> data)
	{
		if (eventLog != null)
		{
			eventLog.log(type, client.getTickCount(), data);
		}
		feedStreamer(type, data);
	}

	private Map<String, Object> m()
	{
		return new LinkedHashMap<>();
	}

	/**
	 * The streamer co-host reads the SAME event stream the recorder writes —
	 * there is one event system and this is it. The name resolver is passed
	 * rather than looked up later because {@code emit} runs on the client thread
	 * and nothing downstream of here does.
	 */
	private void feedStreamer(String type, Map<String, Object> data)
	{
		if (!config.streamerMode())
		{
			return;
		}
		streamer.observe(type, data, id ->
		{
			final net.runelite.api.ItemComposition def = client.getItemDefinition(id);
			return def == null ? null : def.getName();
		});
	}

	private Map<String, Object> actorInfo(Actor a)
	{
		if (a == null)
		{
			return null;
		}
		final Map<String, Object> m = m();
		m.put("name", a.getName());
		if (a instanceof NPC)
		{
			m.put("type", "npc");
			m.put("id", ((NPC) a).getId());
		}
		else if (a instanceof Player)
		{
			m.put("type", "player");
			m.put("local", a == client.getLocalPlayer());
		}
		final WorldPoint wp = a.getWorldLocation();
		if (wp != null)
		{
			m.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
		}
		return m;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		panel.setGameState(state.name());
		emit("gameState", Map.of("state", state.name()));

		if (state == GameState.LOGGED_IN)
		{
			greeted = false;
			lastLoginMs = System.currentTimeMillis();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			panel.setPlayer(null);
			lastHolding = null;
		}

		if (state != GameState.LOGGED_IN)
		{
			// the danger window is five CONSECUTIVE ticks; a logout is not four ticks ago
			dangerModel.reset();
			dangerBoost = 0;
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(net.runelite.api.events.GrandExchangeOfferChanged event)
	{
		final net.runelite.api.GrandExchangeOffer o = event.getOffer();
		if (o == null || o.getItemId() <= 0)
		{
			return;
		}
		final int slot = event.getSlot();
		final net.runelite.api.GrandExchangeOfferState st = o.getState();
		final boolean buying = st == net.runelite.api.GrandExchangeOfferState.BUYING
			|| st == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| st == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
		final long nowMs = System.currentTimeMillis();

		// lifecycle: new offer starts the clock, completion/cancel reads it
		OfferTrack tr = offerTracks[slot];
		if (st == net.runelite.api.GrandExchangeOfferState.BUYING
			|| st == net.runelite.api.GrandExchangeOfferState.SELLING)
		{
			if (tr == null || tr.itemId != o.getItemId() || tr.price != o.getPrice())
			{
				tr = new OfferTrack();
				tr.itemId = o.getItemId();
				tr.price = o.getPrice();
				tr.buying = buying;
				tr.startMs = nowMs;
				tr.lastFillMs = nowMs;
				tr.lane = flipService.laneFor(o.getItemId());
				// restart-safe: if we tracked this exact offer before the client
				// restarted, resume its counters — never re-book old fills.
				// remove() not get(): this is a ONE-SHOT resume of what was on disk
				// at startup. Left in the map it also matches the next offer this
				// session puts in the same slot for the same item at the same price,
				// which resumes counters that offer never earned and swallows its fills.
				final long[] saved = savedOfferState.remove(String.valueOf(slot));
				if (saved != null && saved[0] == o.getItemId() && saved[1] == o.getPrice())
				{
					tr.lastQty = (int) saved[2];
					tr.lastSpent = saved[3];
					tr.startMs = saved[4];
					// restore the stall clock too — a quiet offer must not look
					// freshly-filled just because the client restarted
					tr.lastFillMs = saved.length > 5 ? saved[5] : saved[4];
					// and the lane, or an overnight trap reopens as a quick flip
					// and books its 8-hour fill against the velocity stats
					if (saved.length > 6 && saved[6] == FlipLane.LONG.ordinal())
					{
						tr.lane = FlipLane.LONG;
					}
					tr.realized = saved.length > 7 ? saved[7] : 0;
				}
				offerTracks[slot] = tr;
				saveOfferState();
				// session pph clock starts at the first offer PLACED this session
				if (firstOfferMs == 0 && tr.startMs >= sessionStartMs)
				{
					firstOfferMs = nowMs;
				}
			}
		}
		else if (tr == null
			&& (st == net.runelite.api.GrandExchangeOfferState.BOUGHT
				|| st == net.runelite.api.GrandExchangeOfferState.SOLD
				|| st == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY
				|| st == net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL))
		{
			// THE offline-fill fix. An offer that completed while the client was
			// closed re-fires here in a terminal state on login, and the old code
			// only resumed tracks for LIVE offers — so every gp earned while logged
			// out was silently never booked: no basis update, no profit, no lifetime.
			// That is the exact workflow of a flipper who logs out to wait on offers.
			// The saved track has the pre-shutdown counters, so the delta below books
			// exactly the units that filled offline and nothing that was already booked.
			final long[] saved = savedOfferState.remove(String.valueOf(slot));
			if (saved != null && saved[0] == o.getItemId() && saved[1] == o.getPrice())
			{
				tr = new OfferTrack();
				tr.itemId = o.getItemId();
				tr.price = o.getPrice();
				tr.buying = buying;
				tr.startMs = saved[4];
				tr.lastQty = (int) saved[2];
				tr.lastSpent = saved[3];
				tr.lastFillMs = saved.length > 5 ? saved[5] : saved[4];
				if (saved.length > 6 && saved[6] == FlipLane.LONG.ordinal())
				{
					tr.lane = FlipLane.LONG;
				}
				tr.realized = saved.length > 7 ? saved[7] : 0;
				offerTracks[slot] = tr;
			}
		}

		// DELTA ACCOUNTING: book every new unit filled since the last event —
		// partial fills, aborts with partials, and completions all count
		if (tr != null && tr.itemId == o.getItemId())
		{
			final int dQty = o.getQuantitySold() - tr.lastQty;
			final long dCoins = o.getSpent() - tr.lastSpent;
			if (dQty > 0)
			{
				tr.lastFillMs = nowMs;
				// carryover = offer placed before this session: books to LIFETIME,
				// never distorts this session's P&L or gp/h
				final boolean carryover = tr.startMs < sessionStartMs;
				if (buying)
				{
					flipBasis.merge(o.getItemId(), new long[]{dQty, dCoins},
						(a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
					if (!carryover)
					{
						unitsBought += dQty;
					}
				}
				else
				{
					final long[] basis = flipBasis.get(o.getItemId());
					final boolean realBasis = basis != null && basis[0] > 0;
					long cost = 0;
					if (basis != null && basis[0] > 0)
					{
						final long avg = basis[1] / basis[0];
						final long q = Math.min(dQty, basis[0]);
						cost = avg * q;
						basis[0] -= q;
						basis[1] -= cost;
						if (q < dQty)
						{
							cost += syntheticCost(o.getItemId(), o.getPrice(), dQty - q);
						}
					}
					else
					{
						// no recorded basis (bought before tracking): assume market
						// buy price so profit = the spread, never the full proceeds
						cost = syntheticCost(o.getItemId(), o.getPrice(), dQty);
					}
					// sell − buy − tax, computed explicitly: 2% per item (floored),
					// only on taxable items — never trust the API field's semantics
					final long gross = (long) dQty * o.getPrice();
					final long tax = (long) FlipService.geTax(o.getPrice()) * dQty;
					final long profit = gross - tax - cost;
					tr.realized += profit;
					lifetimeRealized += profit;
					// persist on EVERY sell: losses used to update lifetime in memory
					// only, so a restart quietly un-lost them and the all-time number
					// drifted optimistic forever
					saveTrader();
					if (!carryover)
					{
						flipRealized += profit;
						unitsSold += dQty;
					}
					panel.setFlipPnl(flipRealized, lifetimeRealized);

					// TRADE COLLECTION LOG: first profitable real buy->sell of an item
					if (profit > 0 && realBasis && tradeClog.add(o.getItemId()))
					{
						saveClog();
						final String iname = client.getItemDefinition(o.getItemId()).getName();
						overlay.setAlert("NEW TRADE COLLECTED: " + iname + "!", client.getTickCount() + 10);
						mascot.celebrate("New trade logged: " + iname + "!");
						panel.addCollected(iname, itemManager.getImage(o.getItemId()), profit);
					}

					// TRADER xp: positive profit only, real OSRS curve
					if (profit > 0)
					{
						traderXp += profit * 0.1; // 0.1 xp per gp: 99 = ~130M lifetime profit
						saveTrader();
						final int nl = traderLevelFor(traderXp);
						// the level is a scoreboard, NOT a gate: it never hides a flip
						// the player has the cash for
						if (nl > traderLevel)
						{
							traderLevel = nl;
							overlay.setAlert("TRADER level " + nl + "!", client.getTickCount() + 10);
							voice.play("levelup");
							mascot.celebrate("Trader level " + nl + "!");
						}
					}
				}
				tr.lastQty = o.getQuantitySold();
				tr.lastSpent = o.getSpent();
				saveFlipBasis();
				saveOfferState();
			}
		}

		// fills arriving in the first seconds after login happened OFFLINE at an
		// unknown time — book the money, quarantine the timing
		final boolean offlineFill = nowMs - lastLoginMs < 15_000;
		Long fillSecs = null;
		final FlipLane lane = tr != null ? tr.lane : flipService.laneFor(o.getItemId());
		final boolean sug = flipService.wasSuggested(o.getItemId(), o.getPrice(), buying);
		if (st == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| st == net.runelite.api.GrandExchangeOfferState.SOLD)
		{
			if (tr != null && tr.itemId == o.getItemId())
			{
				fillSecs = (nowMs - tr.startMs) / 1000;
				// quick-lane speed stats are session measurements; a patience order
				// is not slow, it is patient, and it keeps its own ledger
				if (!offlineFill && lane == FlipLane.QUICK)
				{
					(buying ? buyFillSecs : sellFillSecs).add(fillSecs);
				}
				// the offer's WHOLE realized profit, not just the final partial's —
				// a 1000-unit sell that filled in ten chunks used to book one chunk
				itemMemory.recordFill(o.getItemId(), lane, offlineFill ? -1 : fillSecs,
					buying ? 0 : tr.realized);
				if (tr.startMs >= sessionStartMs)
				{
					sessionFills++; // carryover offers belong to the session that placed them
				}
				// one row per finished offer, matching how the game's own history
				// reports it. Only offers we watched from placement are booked: an
				// offer that completed while the client was closed re-fires as
				// BOUGHT on login, and booking that would invent a second trade
				bookOffer(o.getItemId(), o.getQuantitySold(), o.getPrice(), buying);
			}
			if (sug)
			{
				sugFills++;
			}
			mascot.celebrate(buying
				? "Bought! Now sell it."
				: "Cha-ching! Sold.");
			offerTracks[slot] = null;
			// and persist the EMPTY slot. offer-state.json is what a restart
			// resumes from, so a finished offer left in it is a live trap: place a
			// new offer for the same item at the same price after a restart and it
			// resumes the old counters, quantitySold starts below lastQty, and
			// every unit of the new offer books as zero — silently, forever.
			saveOfferState();
		}
		else if (st == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY
			|| st == net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL)
		{
			if (sug)
			{
				sugCancels++; // our call stalled long enough that they pulled it
			}
			if (tr != null && tr.itemId == o.getItemId())
			{
				// a pulled offer that filled partly is still a trade the game
				// recorded, so the audit has to know about it
				bookOffer(o.getItemId(), o.getQuantitySold(), o.getPrice(), buying);
			}
			itemMemory.recordStall(o.getItemId(), lane); // it stopped working — learn that
			offerTracks[slot] = null;
			saveOfferState(); // same as above: a cancelled slot must not be resumable
		}

		final Map<String, Object> d = m();
		d.put("slot", slot);
		d.put("item", o.getItemId());
		d.put("state", st.name());
		d.put("price", o.getPrice());
		d.put("qtySold", o.getQuantitySold());
		d.put("spent", o.getSpent());
		d.put("suggested", sug);
		d.put("offline", offlineFill);
		d.put("lane", lane.name());
		// both accounting views logged so the real coin stack can arbitrate
		d.put("taxCalc", (long) FlipService.geTax(o.getPrice()) * o.getQuantitySold());
		if (fillSecs != null)
		{
			d.put("fillSecs", fillSecs);
		}
		emit("geOffer", d);


	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final Player lp = client.getLocalPlayer();
		if (lp == null)
		{
			return;
		}

		// pet follower: move to the player's previous tile, teleport-catchup if left behind
		final WorldPoint me = lp.getWorldLocation();
		if (petTile == null || petTile.distanceTo(me) > 5 || petTile.getPlane() != me.getPlane())
		{
			petTile = me.dx(-1); // spawn/catchup beside the player, like a real pet
			petPrevTile = petTile;
			mascot.setPetTiles(petPrevTile, petTile, System.currentTimeMillis());
		}
		else if (lastPos != null && !lastPos.equals(me) && petTile.distanceTo(me) > 1)
		{
			petPrevTile = petTile;
			petTile = lastPos; // step into the tile the player just left
			mascot.setPetTiles(petPrevTile, petTile, System.currentTimeMillis());
		}

		// the ledger's pause marker has to be kept fresh every tick, not only when
		// an inventory change happens to ask: a trade window can open and close
		// with no container event of its own, and then the settlement lands with
		// nothing remembering that a transfer just happened
		if (transferUiOpen())
		{
			lastTransferUiTick = client.getTickCount();
		}
		readTradeHistoryIfDue();

		flipService.maybeRefresh();
		if (client.getTickCount() % 10 == 0)
		{
			long coins = 0;
			final net.runelite.api.ItemContainer inv =
				client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
			if (inv != null)
			{
				for (net.runelite.api.Item it : inv.getItems())
				{
					if (it != null && it.getId() == net.runelite.api.ItemID.COINS_995)
					{
						coins += it.getQuantity();
					}
				}
			}
			final boolean membersW = client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS);
			flipService.setContext(coins, !membersW);
			flipService.setLaneConfig(config.flipLanes(), config.quickCycleSecs(),
				(membersW ? 8 : 3) - Math.max(0, config.trapSlots()), config.quickInstaOnly());
			// open positions, deep-copied off the live map: the overlays use this to
			// refuse to coach a sell below what was actually paid
			final Map<Integer, long[]> basisSnap = new java.util.HashMap<>();
			for (Map.Entry<Integer, long[]> e : flipBasis.entrySet())
			{
				if (e.getValue()[0] > 0)
				{
					basisSnap.put(e.getKey(), e.getValue().clone());
				}
			}
			flipService.setBasisSnapshot(basisSnap);
			// log the scan itself: call -> outcome training needs what was
			// suggested and when, including the calls the player ignored
			if (System.currentTimeMillis() - lastFlipScanLogMs > 5 * 60_000)
			{
				lastFlipScanLogMs = System.currentTimeMillis();
				final List<Map<String, Object>> sugg = new ArrayList<>();
				for (FlipService.Flip f : flipService.getTopFlips())
				{
					final Map<String, Object> sd = m();
					sd.put("id", f.getItemId());
					sd.put("buy", f.getBuyAt());
					sd.put("sell", f.getSellAt());
					sd.put("volHr", f.getUnitsHr() * 20);
					sd.put("cycleSecs", f.getCycleSecs());
					sd.put("lane", f.getLane().name());
					sd.put("velocity", flipService.velocity(f, flipService.slotCapital(coins)));
					sugg.add(sd);
				}
				final Map<String, Object> d = m();
				d.put("suggestions", sugg);
				d.put("budget", coins);
				d.put("marketSurprise", flipService.lastScanAvgErr);
				d.put("fillModel", flipService.fillModelStatus());
				d.put("brain", flipService.brainStatus());
				d.put("champion", flipService.championStatus());
				emit("flipScan", d);
			}
			geFlipOverlay.setTrader(traderLevel,
				traderLevel < 99 ? (traderXp - TRADER_XP[traderLevel - 1])
					/ Math.max(1.0, TRADER_XP[traderLevel] - TRADER_XP[traderLevel - 1]) : 1.0);
			panel.setFlips(flipService.getTopFlips());

			// slot utilization: an idle slot is wasted throughput
			int active = 0;
			int trapActive = 0;
			final Set<Integer> onOffer = new java.util.HashSet<>();
			final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			final int slots = membersW ? 8 : 3;
			for (int i = 0; i < Math.min(slots, offers.length); i++)
			{
				final net.runelite.api.GrandExchangeOfferState os = offers[i].getState();
				if (os != net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					active++;
					onOffer.add(offers[i].getItemId());
					if (flipService.isTrap(offers[i].getItemId()))
					{
						trapActive++; // a parked patience order, still costing a slot
					}
				}
			}
			long flipGpHr; // kept for chat command
			flipGpHr = firstOfferMs > 0
				? flipRealized * 3600_000L / Math.max(60_000, System.currentTimeMillis() - firstOfferMs)
				: 0;
			lastFlipGpHr = flipGpHr;
			final long[] starts = new long[8];
			final long[] placed = new long[8];
			for (int i = 0; i < 8; i++)
			{
				starts[i] = offerTracks[i] != null ? offerTracks[i].lastFillMs : 0;
				placed[i] = offerTracks[i] != null ? offerTracks[i].startMs : 0;
			}
			geSlotStampOverlay.setOfferStarts(starts, placed);
			// sell-first allocator: flip goods in the bag = dead capital
			final java.util.List<String> pendingSells = new ArrayList<>();
			final net.runelite.api.ItemContainer pinv =
				client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
			if (pinv != null)
			{
				for (net.runelite.api.Item it : pinv.getItems())
				{
					if (it == null || it.getId() <= 0)
					{
						continue;
					}
					final int cid = itemManager.canonicalize(it.getId());
					final long[] basis = flipBasis.get(cid);
					if (basis != null && basis[0] > 0 && pendingSells.size() < 3)
					{
						pendingSells.add(it.getQuantity() + "× "
							+ client.getItemDefinition(cid).getName());
					}
				}
			}
			geFlipOverlay.setPendingSells(pendingSells);
			// MODE-AWARE ADVISOR. Mid-session the patience board is a small rotating
			// corner of the slot budget and everything else compounds in the quick
			// lane. Heading offline inverts that: a held slot costs nothing once you
			// log out, so every free slot is worth parking a patience order in.
			trapBoard.maybeReload();
			final boolean offline = config.overnightMode();
			final int trapBudget = offline
				? slots - active
				: Math.min(slots - active, config.trapSlots() - trapActive);
			final java.util.List<TrapBoard.Pick> picks = trapPicks(coins, trapBudget, onOffer);
			geFlipOverlay.setTraps(picks, offline);
			panel.setTraps(picks, offline);
			geFlipOverlay.setStats(active, slots, median(buyFillSecs), median(sellFillSecs),
				sugFills, sugCancels, flipGpHr, flipRealized, lifetimeRealized,
				(int) unitsBought, (int) unitsSold,
				firstOfferMs > 0 ? (System.currentTimeMillis() - firstOfferMs) / 60_000 : 0);
			panel.setLanes(itemMemory.laneTotals(FlipLane.QUICK),
				itemMemory.laneTotals(FlipLane.LONG));

			// the co-host follows the toggle at runtime, not only at startup
			final boolean wantStreamer = config.streamerMode();
			if (wantStreamer != streamerStarted)
			{
				streamerStarted = wantStreamer;
				if (wantStreamer)
				{
					streamer.start();
				}
				else
				{
					streamer.stop();
				}
			}
			if (wantStreamer)
			{
				streamer.setContext(streamerContext());
			}
			panel.setStreamer(wantStreamer ? streamer.status() : null);

			panel.setDanger(config.dangerModel() ? dangerModel.status() : null,
				config.dangerModel() && dangerModel.loaded()
					? Math.min(95, config.lowHpPercent() + dangerBoost) : -1,
				dangerBoost > 0);

			// results views: what this session and each item have ACTUALLY paid
			panel.setViewsEnabled(config.sessionScore(), config.itemStats(), config.anomalyAlert());
			flipService.setAnomalyThreshold(config.anomalyPercent());
			if (config.anomalyAlert())
			{
				checkAnomalies();
			}
			if (config.sessionScore())
			{
				panel.setSessionScore(sessionHistory.update(flipRealized, sessionPnl,
					(int) unitsBought, (int) unitsSold, sessionFills,
					firstOfferMs > 0 ? System.currentTimeMillis() - firstOfferMs : 0));
			}
			if (config.itemStats())
			{
				panel.setItemStats(itemMemory.topItems(6,
					id -> client.getItemDefinition(id).getName()));
			}
		}

		panel.setCounts(client.getNpcs().size(), client.getPlayers().size(),
			eventLog != null ? eventLog.getLines() : 0);
		populateClogPanel();

		final String name = lp.getName();
		if (name != null)
		{
			panel.setPlayer(name);
			if (!greeted)
			{
				greeted = true;
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=00b4ff>RuneAI</col> online. " + config.greeting() + ", " + name + "!", null);
				snapshotAtTick = client.getTickCount() + 8; // auto full dump once the scene settles
			}
		}

		if (snapshotAtTick != -1 && client.getTickCount() >= snapshotAtTick)
		{
			snapshotAtTick = -1;
			writeSnapshot();
		}

		final int interval = Math.max(1, config.heartbeatTicks());
		if (client.getTickCount() % interval == 0)
		{
			final Map<String, Object> d = m();
			final WorldPoint wp = lp.getWorldLocation();
			d.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
			d.put("region", wp.getRegionID());
			d.put("anim", lp.getAnimation());
			d.put("hpRatio", lp.getHealthRatio());
			d.put("energy", client.getEnergy() / 100.0);
			d.put("npcs", client.getNpcs().size());
			d.put("players", client.getPlayers().size());
			d.put("interacting", lp.getInteracting() != null ? lp.getInteracting().getName() : null);
			emit("heartbeat", d);
		}

		// the co-host: a clock check and at most one non-blocking enqueue, and only
		// when the player has switched it on
		if (config.streamerMode())
		{
			streamer.tick(System.currentTimeMillis());
			String line;
			while ((line = streamer.pollChatLine()) != null)
			{
				if (config.streamerChat())
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"<col=00b4ff>RuneAI</col> " + line, null);
				}
			}
		}

		// one sample, two consumers — skipped entirely when neither wants it
		if (tickLog != null || config.dangerModel())
		{
			final DangerModel.Tick sample = sampleTick(lp);
			recordTickVector(lp, sample);
			updateDanger(sample);
		}
		else
		{
			dangerBoost = 0;
		}
		updateGuidance(lp);
		damageTakenThisTick = 0;
	}

	/**
	 * The overnight trap portfolio — a small ROTATION, not a portfolio. It only
	 * ever proposes enough to top the reserved trap slots back up, so the rest of
	 * the GE stays on the quick-flip lane. Items already on offer are skipped,
	 * which is what makes it rotate: park one and the next ticket comes up.
	 *
	 * <p>History (which items catch whales, and the number they type) comes from
	 * the trap board; the price to actually PAY comes from today's live book,
	 * because the log's "real" price is as old as the print. Cheap tickets only —
	 * a lottery never gets more than a tenth of the stack.
	 */
	private List<TrapBoard.Pick> trapPicks(long coins, int budget, Set<Integer> onOffer)
	{
		if (!config.trapBoard() || budget <= 0)
		{
			return Collections.emptyList();
		}
		final long wallet = coins > 0 ? coins / 10 : 0;
		final List<TrapBoard.Pick> out = new ArrayList<>();
		long spent = 0;
		for (TrapBoard.Ticket t : trapBoard.getTickets())
		{
			if (out.size() >= budget)
			{
				break;
			}
			if (onOffer.contains(t.getId()))
			{
				continue; // already parked — rotate to the next ticket
			}
			final long[] book = flipService.bookFor(t.getId());
			if (book != null && !flipService.isTrap(t.getId()))
			{
				continue; // the book healed — whatever caught the whale is gone
			}
			final long buyAt = (book != null && book[0] > 0 ? book[0] : t.getReal()) + 1;
			if (buyAt <= 0 || t.getListAt() < buyAt * 5)
			{
				continue; // the ask has to be a real multiple of the ticket price
			}
			long qty = Math.max(1, Math.min(flipService.limitFor(t.getId()), 10));
			if (wallet > 0)
			{
				qty = Math.min(qty, (wallet - spent) / buyAt);
				if (qty <= 0)
				{
					continue;
				}
			}
			out.add(new TrapBoard.Pick(t.getId(), t.getName(), buyAt, qty,
				t.getListAt(), t.getPayoffX(), t.getCorridor()));
			flipService.notePatienceOrder(t.getId()); // whatever happens here books LONG
			spent += qty * buyAt;
		}
		return out;
	}

	// ================= GE trade history audit =================

	/**
	 * Offers we completed, at the granularity the game's own history uses: one
	 * row per finished offer, not per partial fill. Kept so the interface has
	 * something to be diffed against.
	 */
	private final List<TradeHistory.Entry> bookedOffers = new ArrayList<>();
	private static final int BOOKED_KEEP = 40;
	private int historyReadTick = -1;
	private boolean historyRetried;
	private boolean historyRawLogged;

	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.GE_HISTORY
			&& config.tradeAudit())
		{
			// the interface loads empty and a script fills it, so read a couple of
			// ticks later rather than racing the thing we came to measure
			historyReadTick = client.getTickCount() + 2;
			historyRetried = false;
		}
	}

	/**
	 * Let the game audit our ledger. Read-only: these widgets are on screen
	 * because the player opened them, and nothing here sends anything anywhere.
	 */
	private void readTradeHistoryIfDue()
	{
		if (historyReadTick < 0 || client.getTickCount() < historyReadTick)
		{
			return;
		}
		historyReadTick = -1;
		final List<TradeHistory.Row> rows = TradeHistory.read(client);
		if (rows.isEmpty() && !historyRetried && TradeHistory.isOpen(client))
		{
			historyRetried = true; // the script may still be building the list
			historyReadTick = client.getTickCount() + 6;
			return;
		}
		if (rows.isEmpty())
		{
			return;
		}
		final List<TradeHistory.Entry> parsed = new ArrayList<>();
		int unreadable = 0;
		for (TradeHistory.Row r : rows)
		{
			if (r.getParsed() != null)
			{
				parsed.add(r.getParsed());
			}
			else
			{
				unreadable++;
			}
			// the shape of these rows is only knowable from a live client, so the
			// first look each session records what they actually said. The next
			// parser revision comes from THIS, not from another guess.
			if (!historyRawLogged)
			{
				final Map<String, Object> d = m();
				d.put("item", r.getItemId());
				d.put("qty", r.getQty());
				d.put("parsed", r.getParsed() != null);
				d.put("text", r.getRawText());
				emit("geHistoryRow", d);
			}
		}
		historyRawLogged = true;

		// with nothing booked yet, every row the game shows is "unbooked" and the
		// audit would be shouting about the absence of its own data
		if (bookedOffers.isEmpty())
		{
			panel.setAudit(String.format("%d trades on record, none booked yet", rows.size()), true,
				"RuneAI books offers it watched from placement. Flip once and reopen this to audit.");
			return;
		}

		final List<TradeHistory.Discrepancy> diffs =
			TradeHistory.reconcile(parsed, bookedOffers);
		final int matched = parsed.size() - diffs.size();

		// The game's history reaches further back than our ledger does, so its
		// oldest rows are legitimately unbooked and are not our discrepancies.
		// See TradeHistory.auditable — every diff is still written to the event
		// log below, because the log is the dataset and the alert is the
		// interruption, and they are allowed different budgets.
		final List<TradeHistory.Discrepancy> real =
			TradeHistory.auditable(diffs, parsed.size(), bookedOffers.size());
		final int older = diffs.size() - real.size();
		final long net = TradeHistory.netDelta(real);

		final Map<String, Object> d = m();
		d.put("rows", rows.size());
		d.put("parsed", parsed.size());
		d.put("unreadable", unreadable);
		d.put("matched", matched);
		d.put("predateLedger", older);
		d.put("netDelta", net);
		final List<Map<String, Object>> ds = new ArrayList<>();
		for (TradeHistory.Discrepancy x : diffs)
		{
			final Map<String, Object> e = m();
			e.put("item", x.getItemId());
			e.put("kind", x.getKind().name());
			e.put("gpDelta", x.getGpDelta());
			e.put("detail", x.getDetail());
			ds.add(e);
		}
		d.put("discrepancies", ds);
		emit("geHistoryAudit", d);

		final int audited = parsed.size() - older;
		final String summary = real.isEmpty()
			? String.format("ledger matches the game on %d of %d trades", matched, audited)
			: String.format("%d of %d trades disagree · %+,d gp", real.size(), audited, net);
		final StringBuilder tip = new StringBuilder("<html>");
		tip.append(String.format("%d rows read, %d readable", rows.size(), parsed.size()));
		if (older > 0)
		{
			tip.append(String.format("<br>%d row(s) predate RuneAI's ledger — not audited", older));
		}
		if (unreadable > 0)
		{
			tip.append(String.format("<br>%d row(s) the parser could not read — logged as geHistoryRow", unreadable));
		}
		for (TradeHistory.Discrepancy x : real)
		{
			tip.append("<br>").append(client.getItemDefinition(x.getItemId()).getName())
				.append(": ").append(x.getDetail());
		}
		panel.setAudit(summary, real.isEmpty(), tip.append("</html>").toString());

		// one summary line, not one per row — an audit that spams is an audit
		// nobody reads
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=00b4ff>RuneAI</col> trade history audit — " + summary
				+ (unreadable > 0 ? String.format(" (%d row(s) unreadable)", unreadable) : ""), null);
		if (!real.isEmpty())
		{
			overlay.setAlert(String.format("LEDGER OFF BY %+,d gp", net), client.getTickCount() + 12);
		}
		log.info("GE history audit: {} rows, {} parsed, {} matched, {} predate the ledger, net {}",
			rows.size(), parsed.size(), matched, older, net);
	}

	/** Record a finished offer at the granularity the game's history reports. */
	private void bookOffer(int itemId, int qty, int price, boolean buying)
	{
		if (qty <= 0)
		{
			return;
		}
		bookedOffers.add(new TradeHistory.Entry(itemId, qty, price, buying));
		while (bookedOffers.size() > BOOKED_KEEP)
		{
			bookedOffers.remove(0);
		}
		saveBookedOffers();
	}

	private void loadBookedOffers()
	{
		try
		{
			final File f = new File(DATA_DIR, "booked-offers.json");
			if (f.exists())
			{
				final List<TradeHistory.Entry> raw = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
					new com.google.gson.reflect.TypeToken<List<TradeHistory.Entry>>(){}.getType());
				if (raw != null)
				{
					bookedOffers.addAll(raw);
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("booked offers load failed", ex);
		}
	}

	private void saveBookedOffers()
	{
		try
		{
			// every save() below runs on the client thread, inside a GE event or the
			// tick loop — serialise here, let AsyncWriter do the blocking part
			AsyncWriter.write(new File(DATA_DIR, "booked-offers.json"), gson.toJson(bookedOffers));
		}
		catch (Exception ex)
		{
			log.warn("booked offers save failed", ex);
		}
	}

	// ================= anomaly watch =================

	/**
	 * One alert per item per half hour, however many scans keep flagging it. A
	 * dislocation stays flagged for as long as the price stays away from where it
	 * was, so without this the same crash would re-announce itself every minute.
	 */
	private static final long ANOMALY_RECALL_MS = 30 * 60_000;
	private final Map<Integer, Long> lastAnomalyAlertMs = new java.util.HashMap<>();
	private final List<String[]> anomalyFeed = new ArrayList<>();

	/**
	 * Report the blue moons; decide nothing. A 30% rip is an RWT pump, an update
	 * panic, or a ban wave clearing a bot farm's supply — those want opposite
	 * trades, they are indistinguishable from the tape, and the difference lives
	 * in patch notes and a Reddit thread. So the plugin states what moved, by how
	 * much, against what, and whether the player is exposed to it. The trade is
	 * theirs.
	 */
	private void checkAnomalies()
	{
		final List<FlipService.Anomaly> found = flipService.drainAnomalies();
		if (found.isEmpty())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		FlipService.Anomaly pick = null;
		String pickWhy = null;
		int logged = 0;
		for (FlipService.Anomaly a : found)
		{
			final String why = exposure(a.getItemId());
			// every fresh dislocation is recorded even when the alert is filtered or
			// throttled — the log is the dataset, the alert is the interruption, and
			// they are allowed to have different budgets
			if (logged++ < 40)
			{
				final Map<String, Object> d = m();
				d.put("item", a.getItemId());
				d.put("name", a.getName());
				d.put("refMid", a.getRefMid());
				d.put("mid", a.getMid());
				d.put("movePct", a.getMovePct());
				d.put("scanPct", a.getScanPct());
				d.put("vol5m", a.getVol5m());
				d.put("spanMins", a.getSpanMins());
				d.put("exposure", why);
				emit("anomaly", d);
			}
			if (config.anomalyHeldOnly() && why == null)
			{
				continue; // logged above; just not worth an interruption
			}
			final Long last = lastAnomalyAlertMs.get(a.getItemId());
			if (last != null && now - last < ANOMALY_RECALL_MS)
			{
				continue;
			}
			// the list arrives biggest-move-first, so the first survivor is the
			// biggest; a position then outranks a larger move you have no stake in
			if (pick == null || (why != null && pickWhy == null))
			{
				pick = a;
				pickWhy = why;
			}
		}
		if (pick == null)
		{
			return;
		}
		lastAnomalyAlertMs.put(pick.getItemId(), now);
		final boolean up = pick.getMovePct() > 0;
		final String headline = String.format("%s %+.0f%% in %dm",
			pick.getName(), pick.getMovePct(), pick.getSpanMins());
		final String detail = pickWhy != null
			? String.format("%,d → %,d · %s", pick.getRefMid(), pick.getMid(), pickWhy)
			: String.format("%,d → %,d · %,d traded/5m", pick.getRefMid(), pick.getMid(), pick.getVol5m());

		anomalyFeed.add(0, new String[]{headline, detail, up ? "up" : "down",
			pickWhy != null ? "held" : ""});
		while (anomalyFeed.size() > 5)
		{
			anomalyFeed.remove(anomalyFeed.size() - 1);
		}
		panel.setAnomalies(new ArrayList<>(anomalyFeed));

		overlay.setAlert(pickWhy != null ? headline + " — YOU HOLD IT" : headline,
			client.getTickCount() + 12);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", String.format(
			"<col=ff8c28>RuneAI</col> %s — mid %,d → %,d on %,d traded/5m%s. "
				+ "Pump, update panic or ban wave? That one is your call.",
			headline, pick.getRefMid(), pick.getMid(), pick.getVol5m(),
			pickWhy != null ? " (" + pickWhy + ")" : ""), null);
		if (pickWhy != null && up)
		{
			mascot.celebrate("Something you hold just jumped.");
		}
	}

	/**
	 * Why this player cares about an item, or null if they do not: carried,
	 * sitting on a GE offer, or bought and not yet sold. An anomaly on something
	 * you own is a decision; on anything else it is trivia.
	 */
	private String exposure(int itemId)
	{
		long carried = 0;
		for (net.runelite.api.InventoryID cid : new net.runelite.api.InventoryID[]{
			net.runelite.api.InventoryID.INVENTORY, net.runelite.api.InventoryID.EQUIPMENT})
		{
			final net.runelite.api.ItemContainer c = client.getItemContainer(cid);
			if (c == null)
			{
				continue;
			}
			for (net.runelite.api.Item it : c.getItems())
			{
				if (it != null && it.getId() > 0 && itemManager.canonicalize(it.getId()) == itemId)
				{
					carried += it.getQuantity();
				}
			}
		}
		if (carried > 0)
		{
			return String.format("you hold %,d", carried);
		}
		final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (net.runelite.api.GrandExchangeOffer o : offers)
			{
				if (o != null && o.getItemId() == itemId
					&& o.getState() != net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					return "you have it on a GE offer";
				}
			}
		}
		final long[] basis = flipBasis.get(itemId);
		if (basis != null && basis[0] > 0)
		{
			return String.format("%,d bought @ %,d, unsold", basis[0], basis[1] / Math.max(1, basis[0]));
		}
		return null;
	}

	private long syntheticCost(int itemId, int sellPrice, long qty)
	{
		final long[] q = flipService.quoteFor(itemId);
		final long unit = q != null && q[0] > 0 ? q[0] : sellPrice;
		return unit * qty;
	}

	private static long median(List<Long> v)
	{
		if (v.isEmpty())
		{
			return -1;
		}
		final List<Long> c = new ArrayList<>(v);
		Collections.sort(c);
		return c.get(c.size() / 2);
	}

	// basis survives restarts — otherwise items bought before a relaunch
	// book as zero-cost and the P&L flatters itself ("looks" != "is")
	private void loadFlipBasis()
	{
		try
		{
			final File f = new File(DATA_DIR, "flip-basis.json");
			if (f.exists())
			{
				final Map<String, long[]> raw = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
					new com.google.gson.reflect.TypeToken<Map<String, long[]>>(){}.getType());
				for (Map.Entry<String, long[]> e : raw.entrySet())
				{
					flipBasis.put(Integer.parseInt(e.getKey()), e.getValue());
				}
				log.info("flip basis loaded: {} items", flipBasis.size());
			}
		}
		catch (Exception ex)
		{
			log.warn("flip basis load failed", ex);
		}
	}

	private void loadOfferState()
	{
		try
		{
			final File f = new File(DATA_DIR, "offer-state.json");
			if (f.exists())
			{
				final Map<String, long[]> raw = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
					new com.google.gson.reflect.TypeToken<Map<String, long[]>>(){}.getType());
				if (raw != null)
				{
					// never let a truncated or empty file leave the resume map null:
					// it is dereferenced from a GE event on the client thread
					savedOfferState = raw;
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("offer state load failed", ex);
		}
	}

	private void saveOfferState()
	{
		try
		{
			final Map<String, long[]> out = new java.util.HashMap<>();
			for (int i = 0; i < 8; i++)
			{
				final OfferTrack t = offerTracks[i];
				if (t != null)
				{
					out.put(String.valueOf(i), new long[]{t.itemId, t.price, t.lastQty,
						t.lastSpent, t.startMs, t.lastFillMs, t.lane.ordinal(), t.realized});
				}
			}
			AsyncWriter.write(new File(DATA_DIR, "offer-state.json"), gson.toJson(out));
		}
		catch (Exception ex)
		{
			log.warn("offer state save failed", ex);
		}
	}

	private void loadClog()
	{
		try
		{
			final File f = new File(DATA_DIR, "trade-clog.json");
			if (f.exists())
			{
				final java.util.List<Double> raw = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
					new com.google.gson.reflect.TypeToken<java.util.List<Double>>(){}.getType());
				for (Double d : raw)
				{
					tradeClog.add(d.intValue());
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("clog load failed", ex);
		}
	}

	private void saveClog()
	{
		try
		{
			AsyncWriter.write(new File(DATA_DIR, "trade-clog.json"), gson.toJson(tradeClog));
		}
		catch (Exception ex)
		{
			log.warn("clog save failed", ex);
		}
	}

	private boolean clogPopulated;

	private void populateClogPanel()
	{
		if (clogPopulated)
		{
			return;
		}
		clogPopulated = true;
		for (int id : tradeClog)
		{
			// the tooltip carries the receipt: what this item has paid all-time
			final ItemMemory.Stats st = itemMemory.statsFor(id);
			panel.addCollected(client.getItemDefinition(id).getName(), itemManager.getImage(id),
				st != null ? st.getTotalProfit() : 0);
		}
	}

	private void loadTrader()
	{
		try
		{
			final File f = new File(DATA_DIR, "trader.json");
			if (f.exists())
			{
				final Map<?, ?> raw = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Map.class);
				traderXp = ((Number) raw.get("xp")).doubleValue();
				if (raw.get("lifetime") != null)
				{
					lifetimeRealized = ((Number) raw.get("lifetime")).longValue();
				}
				traderLevel = traderLevelFor(traderXp);
			}
		}
		catch (Exception ex)
		{
			log.warn("trader load failed", ex);
		}
	}

	private void saveTrader()
	{
		try
		{
			AsyncWriter.write(new File(DATA_DIR, "trader.json"),
				gson.toJson(Map.of("xp", traderXp, "lifetime", lifetimeRealized)));
		}
		catch (Exception ex)
		{
			log.warn("trader save failed", ex);
		}
	}

	private void saveFlipBasis()
	{
		try
		{
			final Map<String, long[]> raw = new java.util.HashMap<>();
			for (Map.Entry<Integer, long[]> e : flipBasis.entrySet())
			{
				if (e.getValue()[0] > 0)
				{
					raw.put(String.valueOf(e.getKey()), e.getValue());
				}
			}
			AsyncWriter.write(new File(DATA_DIR, "flip-basis.json"), gson.toJson(raw));
		}
		catch (Exception ex)
		{
			log.warn("flip basis save failed", ex);
		}
	}

	// ================= activity guidance =================

	/** What is the user doing? Derived from recent xp drops — free, exact signal. */
	private String currentActivity()
	{
		if (lastXpSkill == null || client.getTickCount() - lastXpTick > 50)
		{
			return null;
		}
		return COMBAT_SKILLS.contains(lastXpSkill) ? "Combat" : lastXpSkill.getName();
	}

	/**
	 * Is this session a gp grind or an xp grind? Power-trainers drop most of
	 * what they gain; bankers keep it. The NN gets this as a per-tick signal.
	 */
	private String goalMode()
	{
		return gainedValue > 0 && droppedValue * 2 >= gainedValue ? "xp" : "gp";
	}

	/** Count inventory items that carry a given action, e.g. "Eat" or "Drink". */
	private int countInventoryAction(String action)
	{
		final net.runelite.api.ItemContainer inv =
			client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		int count = 0;
		for (net.runelite.api.Item item : inv.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			final String[] actions = client.getItemDefinition(item.getId()).getInventoryActions();
			if (actions != null)
			{
				for (String a : actions)
				{
					if (action.equals(a))
					{
						count++;
						break;
					}
				}
			}
		}
		return count;
	}

	private void updateGuidance(Player lp)
	{
		final int tick = client.getTickCount();

		// remember current combat target for "attack the next one" guidance
		if (lp.getInteracting() instanceof NPC)
		{
			lastTargetNpcId = ((NPC) lp.getInteracting()).getId();
		}

		// attack + low HP awareness
		// combat-capable NPCs only — a GE clerk turning to face you is not an attack
		final boolean underAttack = client.getNpcs().stream()
			.anyMatch(n -> n != null && n.getCombatLevel() > 0 && n.getInteracting() == lp);
		if (underAttack && !wasUnderAttack && lp.getInteracting() == null)
		{
			voice.play("attacked");
		}
		wasUnderAttack = underAttack;

		if (config.lowHpWarn())
		{
			final int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
			final int max = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
			// the danger prior buys HP percentage points, never a new alert: in a
			// context that has actually hurt this player, the same warning fires
			// earlier and repeats sooner. Zero boost = exactly the old behaviour.
			final boolean elevated = dangerBoost > 0;
			final int threshold = Math.min(95, config.lowHpPercent() + dangerBoost);
			if (underAttack && hp * 100 / max <= threshold)
			{
				// the warning is worth one line in the event stream, but only when it
				// STARTS: it re-evaluates every tick and a low fight would otherwise
				// write a hundred identical rows
				if (tick - lastEatEventTick >= 50)
				{
					lastEatEventTick = tick;
					final Map<String, Object> d = m();
					d.put("kind", "eat");
					d.put("hp", hp);
					d.put("max", max);
					d.put("threshold", threshold);
					d.put("elevated", elevated);
					emit("guidance", d);
				}
				if (countInventoryAction("Eat") > 0)
				{
					overlay.setAlert((elevated ? "EAT NOW — HP " : "EAT — HP ") + hp + "/" + max,
						tick + (elevated ? 4 : 2));
					voice.play("eat");
				}
				else if (tick - lastFoodWarnTick >= (elevated ? 50 : 100))
				{
					// low, under attack, and NOTHING edible left — that's the real emergency
					lastFoodWarnTick = tick;
					overlay.setAlert("NO FOOD — get out / bank", tick + 5);
					voice.play("bank");
				}
			}
		}

		final String activity = currentActivity();
		panel.setActivity(activity == null ? "—" : activity + " · " + goalMode());

		// F2P bond ladder: check every ~30s
		if (tick % 50 == 0)
		{
			final boolean members = client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS);
			final long bondPrice = itemManager.getItemPrice(net.runelite.api.ItemID.OLD_SCHOOL_BOND);
			final long worth = totalWorth();
			panel.setBond(members, worth, bondPrice, bankValue >= 0);
			if (!members && !bondAnnounced && bankValue >= 0 && bondPrice > 0 && worth >= bondPrice)
			{
				bondAnnounced = true;
				overlay.setAlert("You can afford a BOND — go members!", tick + 10);
				voice.play("bond");
			}
		}

		// fighting unpotted with a boost potion in the bag -> pot up
		if (config.potReminder() && "Combat".equals(activity) && tick - lastPotRemindTick >= 100)
		{
			checkPotions(tick);
		}

		if (!config.guideIdle() || activity == null)
		{
			idleTicks = 0;
			lastPos = lp.getWorldLocation();
			return;
		}

		// idle = no animation, not moving, not interacting
		final WorldPoint pos = lp.getWorldLocation();
		final boolean idle = lp.getAnimation() == -1
			&& pos.equals(lastPos)
			&& lp.getInteracting() == null;
		lastPos = pos;
		idleTicks = idle ? idleTicks + 1 : 0;

		// combat has natural gaps (death anims, looting, burying) — be patient there,
		// and never nag right after a kill
		final boolean combat = "Combat".equals(activity);
		final int needIdle = combat ? 17 : 8;
		if (combat && tick - lastKillTick < 25)
		{
			return;
		}

		if (idleTicks >= needIdle && tick - lastGuideTick >= 10)
		{
			lastGuideTick = tick;
			final Object[] target = findNearestClickable(activity, pos);
			if (target != null)
			{
				overlay.flashTile((WorldPoint) target[0], RuneAIOverlay.GUIDE,
					"Click: " + target[1], tick + 8);
				if (!combat)
				{
					// the voice nag is for AFK skilling; in combat the flash is enough
					voice.play("idle");
				}
			}
		}
	}

	private void checkPotions(int tick)
	{
		final net.runelite.api.ItemContainer inv =
			client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		if (inv == null)
		{
			return;
		}
		for (net.runelite.api.Item item : inv.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			final String name = client.getItemDefinition(item.getId()).getName();
			final String lower = name.toLowerCase();
			if (!lower.contains("potion"))
			{
				continue;
			}
			final Skill boosts;
			if (lower.contains("strength"))
			{
				boosts = Skill.STRENGTH;
			}
			else if (lower.contains("attack") || lower.contains("combat"))
			{
				boosts = Skill.ATTACK;
			}
			else if (lower.contains("defence"))
			{
				boosts = Skill.DEFENCE;
			}
			else if (lower.contains("ranging"))
			{
				boosts = Skill.RANGED;
			}
			else if (lower.contains("magic"))
			{
				boosts = Skill.MAGIC;
			}
			else
			{
				continue;
			}
			if (client.getBoostedSkillLevel(boosts) <= client.getRealSkillLevel(boosts))
			{
				lastPotRemindTick = tick;
				overlay.setAlert("Pot up — " + name, tick + 5);
				voice.play("pot");
				return;
			}
		}
	}

	/** Nearest thing to click for the current activity: {WorldPoint, name} or null. */
	private Object[] findNearestClickable(String activity, WorldPoint me)
	{
		// NPC-based activities
		if ("Fishing".equals(activity) || "Combat".equals(activity))
		{
			NPC best = null;
			int bestDist = 27;
			for (NPC n : client.getNpcs())
			{
				if (n == null || n.getName() == null)
				{
					continue;
				}
				final boolean match = "Fishing".equals(activity)
					? n.getName().contains("Fishing spot")
					: n.getId() == lastTargetNpcId && n.getInteracting() == null;
				if (!match)
				{
					continue;
				}
				final int d = me.distanceTo(n.getWorldLocation());
				if (d < bestDist)
				{
					bestDist = d;
					best = n;
				}
			}
			return best != null ? new Object[]{best.getWorldLocation(), best.getName()} : null;
		}

		// object-based activities
		final String[] keywords;
		switch (activity)
		{
			case "Mining":
				keywords = new String[]{"rocks"};
				break;
			case "Woodcutting":
				keywords = new String[]{"tree"};
				break;
			case "Cooking":
				keywords = new String[]{"range", "fire", "stove"};
				break;
			case "Smithing":
				keywords = new String[]{"anvil", "furnace"};
				break;
			case "Crafting":
				keywords = new String[]{"furnace", "spinning wheel", "loom"};
				break;
			default:
				return null;
		}

		GameObject best = null;
		String bestName = null;
		int bestDist = 27;
		final Tile[][][] tiles = client.getScene().getTiles();
		final int plane = client.getPlane();
		for (int x = 0; x < 104; x++)
		{
			for (int y = 0; y < 104; y++)
			{
				final Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}
				final GameObject[] gos = tile.getGameObjects();
				if (gos == null)
				{
					continue;
				}
				for (GameObject go : gos)
				{
					if (go == null)
					{
						continue;
					}
					final int d = me.distanceTo(go.getWorldLocation());
					if (d >= bestDist)
					{
						continue;
					}
					final net.runelite.api.ObjectComposition def = client.getObjectDefinition(go.getId());
					if (def == null || def.getName() == null)
					{
						continue;
					}
					final String name = def.getName().toLowerCase();
					for (String kw : keywords)
					{
						if (name.contains(kw))
						{
							bestDist = d;
							best = go;
							bestName = def.getName();
							break;
						}
					}
				}
			}
		}
		return best != null ? new Object[]{best.getWorldLocation(), bestName} : null;
	}

	/**
	 * One read of this tick's threat state, in the shape a row of
	 * {@code ticks-*.jsonl} has. The recorder that writes the training corpus and
	 * the model that infers from it are handed the SAME object, so the features
	 * trained on and the features inferred from cannot drift apart — which is the
	 * failure mode {@link DangerModel} would never notice and never report.
	 *
	 * <p>Client thread only, and the NPC list is the nearest 8 by distance
	 * because that is exactly what the corpus holds.
	 */
	private DangerModel.Tick sampleTick(Player lp)
	{
		final WorldPoint wp = lp.getWorldLocation();
		final List<NPC> npcs = new ArrayList<>(client.getNpcs());
		npcs.removeIf(n -> n == null || n.getName() == null);
		npcs.sort(Comparator.comparingInt(n -> wp.distanceTo(n.getWorldLocation())));
		final List<DangerModel.Npc> near = new ArrayList<>();
		for (int i = 0; i < Math.min(8, npcs.size()); i++)
		{
			final NPC n = npcs.get(i);
			near.add(new DangerModel.Npc(n.getId(), wp.distanceTo(n.getWorldLocation()),
				n.getAnimation(), n.getHealthRatio(), n.getInteracting() == lp));
		}
		final Actor inter = lp.getInteracting();
		return new DangerModel.Tick(
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			damageTakenThisTick,
			lp.getAnimation(),
			inter instanceof NPC ? ((NPC) inter).getId() : -1,
			wp.getX(), wp.getY(), npcs.size(), near);
	}

	/**
	 * Fixed-shape per-tick state record — the NN training corpus.
	 * One line per game tick; dmgTaken is the built-in label for
	 * "should I have been warned last tick?"
	 */
	private void recordTickVector(Player lp, DangerModel.Tick s)
	{
		if (tickLog == null)
		{
			return;
		}

		final Map<String, Object> d = m();
		final WorldPoint wp = lp.getWorldLocation();
		d.put("x", s.x);
		d.put("y", s.y);
		d.put("plane", wp.getPlane());
		d.put("region", wp.getRegionID());
		d.put("hp", s.hp);
		d.put("hpMax", s.hpMax);
		d.put("pray", s.pray);
		d.put("energy", client.getEnergy() / 100.0);
		d.put("spec", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
		d.put("anim", s.anim);
		d.put("pose", lp.getPoseAnimation());
		d.put("graphic", lp.getGraphic());
		d.put("dmgTaken", s.dmgTaken);
		d.put("pnl", sessionPnl);
		d.put("activity", currentActivity());
		d.put("goal", goalMode());
		d.put("xpGained", xpGainedSession);
		d.put("members", client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS));
		d.put("worth", totalWorth());

		// worn gear ids — lets models correlate equipment with outcomes (BiS learning)
		final net.runelite.api.ItemContainer equip =
			client.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT);
		final List<Integer> gear = new ArrayList<>();
		if (equip != null)
		{
			for (net.runelite.api.Item item : equip.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					gear.add(item.getId());
				}
			}
		}
		d.put("equip", gear);
		d.put("targetNpcId", s.targetNpcId);

		// nearest 8 NPCs, sorted by distance — the local threat picture
		final List<Map<String, Object>> near = new ArrayList<>();
		for (DangerModel.Npc n : s.npcs)
		{
			final Map<String, Object> nm = m();
			nm.put("id", n.id);
			nm.put("dist", n.dist);
			nm.put("anim", n.anim);
			nm.put("hr", n.hr);
			nm.put("atkMe", n.atkMe);
			near.add(nm);
		}
		d.put("npcCount", s.npcCount);
		d.put("npcs", near);

		tickLog.log("tick", client.getTickCount(), d);
	}

	/**
	 * Where the trained danger model touches the game — and the only place. It
	 * raises the urgency of the low-HP warning that already existed and does
	 * nothing else: no tile marker, no attack timer, no "it hits in two ticks".
	 * A coarse prior on a threshold, which is the side of the hub line this has
	 * to stay on.
	 *
	 * <p>Today the shipped model's verdict is false, so it IS its per-activity
	 * baseline: "Combat ticks have hurt you 6.5x more often than your average
	 * tick" — worth warning a little earlier for, and worth nothing more. A
	 * retrain that earns a positive verdict sharpens the same number per tick
	 * with no change here.
	 */
	/**
	 * The one line of session context the co-host is given. Deliberately made of
	 * numbers the player can already see in the sidebar — no account name, no
	 * position, nothing the player has not already put on their own stream.
	 */
	private String streamerContext()
	{
		final String activity = currentActivity();
		final long mins = (System.currentTimeMillis() - sessionStartMs) / 60_000;
		return String.format("%s · %d min in · session %+,d gp · flips %+,d gp · trader level %d",
			activity == null ? "no activity detected" : activity + " (" + goalMode() + " grind)",
			mins, sessionPnl, flipRealized, traderLevel);
	}

	private void updateDanger(DangerModel.Tick sample)
	{
		if (!config.dangerModel())
		{
			dangerModel.reset();
			dangerBoost = 0;
			return;
		}
		dangerBoost = DangerModel.urgencyBoost(
			dangerModel.observe(currentActivity(), sample), dangerModel.globalRate());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final Map<String, Object> d = m();
		d.put("type", event.getType().name());
		d.put("sender", event.getName());
		d.put("msg", event.getMessage());
		emit("chat", d);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// activity detection: only real xp gains count, not boost changes
		final Integer prev = lastXp.put(event.getSkill(), event.getXp());
		if (prev != null && event.getXp() > prev)
		{
			lastXpSkill = event.getSkill();
			lastXpTick = client.getTickCount();
			xpGainedSession += event.getXp() - prev;
		}

		// LEVEL UP — prev level known this session (filters the login stat sync)
		final Integer prevLevel = lastLevel.put(event.getSkill(), event.getLevel());
		if (prevLevel != null && event.getLevel() > prevLevel)
		{
			overlay.setAlert(event.getSkill().getName() + " level " + event.getLevel() + "!",
				client.getTickCount() + 8);
			voice.play("levelup");
			// its own event type: a level-up was only ever recoverable from the log by
			// diffing consecutive stat lines, which is work the writer can just do
			final Map<String, Object> lu = m();
			lu.put("skill", event.getSkill().getName());
			lu.put("level", event.getLevel());
			lu.put("from", prevLevel);
			emit("levelUp", lu);
		}

		final Map<String, Object> d = m();
		d.put("skill", event.getSkill().getName());
		d.put("level", event.getLevel());
		d.put("boosted", event.getBoostedLevel());
		d.put("xp", event.getXp());
		emit("stat", d);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			damageTakenThisTick += event.getHitsplat().getAmount();
		}
		final Map<String, Object> d = m();
		d.put("target", actorInfo(event.getActor()));
		d.put("amount", event.getHitsplat().getAmount());
		d.put("hitType", event.getHitsplat().getHitsplatType());
		d.put("mine", event.getHitsplat().isMine());
		emit("hitsplat", d);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		// our kill dying starts the loot/bury window — no idle nagging during it
		final Player lp = client.getLocalPlayer();
		final Actor a = event.getActor();
		if (lp != null && a instanceof NPC
			&& (a.getInteracting() == lp || lp.getInteracting() == a))
		{
			lastKillTick = client.getTickCount();
		}
		emit("death", Map.of("actor", actorInfo(event.getActor())));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		final Actor a = event.getActor();
		if (a == null || a.getAnimation() == -1)
		{
			return;
		}
		final Map<String, Object> d = m();
		d.put("actor", actorInfo(a));
		d.put("anim", a.getAnimation());
		emit("animation", d);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		final Actor a = event.getActor();
		if (a == null || a.getGraphic() == -1)
		{
			return;
		}
		final Map<String, Object> d = m();
		d.put("actor", actorInfo(a));
		d.put("graphic", a.getGraphic());
		emit("graphic", d);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		// standalone spot-anims on tiles — AoE telegraphs, "fire on the floor"
		final Map<String, Object> d = m();
		d.put("id", event.getGraphicsObject().getId());
		final WorldPoint wp = WorldPoint.fromLocal(client, event.getGraphicsObject().getLocation());
		d.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
		emit("graphicsObject", d);
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		final Projectile p = event.getProjectile();
		if (!seenProjectiles.add(p))
		{
			return; // only log each projectile once, on first sighting
		}
		final Map<String, Object> d = m();
		d.put("id", p.getId());
		d.put("target", actorInfo(p.getInteracting()));
		emit("projectile", d);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		final Map<String, Object> d = m();
		d.put("source", actorInfo(event.getSource()));
		d.put("target", actorInfo(event.getTarget()));
		emit("interacting", d);
	}

	// ================= session P&L =================

	/**
	 * Interfaces behind which items move without anyone earning anything. Bank,
	 * GE, deposit box, shop — and BOTH player-trade screens, because 3.5M arriving
	 * from your own alt is a transfer in exactly the same way a bank withdrawal is.
	 *
	 * <p>gameval constants rather than the raw ints this used to carry. Verified
	 * against RuneLite's own legacy mapping, which agrees on every group that
	 * existed there: {@code BANKMAIN}/{@code BANK_GROUP_ID} 12,
	 * {@code GE_OFFERS}/{@code GRAND_EXCHANGE_GROUP_ID} 465,
	 * {@code BANK_DEPOSITBOX}/{@code DEPOSIT_BOX_GROUP_ID} 192,
	 * {@code SHOPMAIN}/{@code SHOP_GROUP_ID} 300 and
	 * {@code TRADEMAIN}/{@code PLAYER_TRADE_SCREEN_GROUP_ID} 335.
	 * {@code TRADECONFIRM} has no legacy counterpart; it is the second screen,
	 * which its own components name for it — {@code TRADE2ACCEPT},
	 * {@code YOU_WILL_GIVE}, {@code YOU_WILL_RECEIVE}.
	 */
	private static final int[] TRANSFER_GROUPS = {
		net.runelite.api.gameval.InterfaceID.BANKMAIN,
		net.runelite.api.gameval.InterfaceID.GE_OFFERS,
		net.runelite.api.gameval.InterfaceID.BANK_DEPOSITBOX,
		net.runelite.api.gameval.InterfaceID.SHOPMAIN,
		net.runelite.api.gameval.InterfaceID.TRADEMAIN,
		net.runelite.api.gameval.InterfaceID.TRADECONFIRM,
	};

	/**
	 * Ticks after a transfer interface closes during which the ledger stays
	 * paused.
	 *
	 * <p>This is the actual bug, not the missing group id. A trade settles as the
	 * confirm screen tears down, so the inventory change can land on the far side
	 * of "is the widget open right now" and book the whole transfer as profit —
	 * which is how a 3.5M gift from an alt showed up as +3,514,022 earned. Holding
	 * the pause for a few ticks lets the arriving items be absorbed into the
	 * baseline instead of counted as a gain.
	 */
	private static final int TRANSFER_GRACE_TICKS = 5;
	private int lastTransferUiTick = -1000;

	private boolean transferUiOpen()
	{
		for (int group : TRANSFER_GROUPS)
		{
			final net.runelite.api.widgets.Widget w = client.getWidget(group, 0);
			if (w != null && !w.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	/** P&L pauses across transfers — those move items, they do not earn them. */
	private boolean pnlPaused()
	{
		if (transferUiOpen())
		{
			lastTransferUiTick = client.getTickCount();
			return true;
		}
		return client.getTickCount() - lastTransferUiTick <= TRANSFER_GRACE_TICKS;
	}

	private long itemValue(int id)
	{
		return id == net.runelite.api.ItemID.COINS_995 ? 1 : itemManager.getItemPrice(id);
	}

	/**
	 * The session ledger, and the carried-holdings snapshot it is derived from.
	 *
	 * <p>The snapshot is taken whether or not {@code trackPnl} is on, and the
	 * gate now covers only the ledger arithmetic. It used to be the first line of
	 * this method, which meant switching P&L tracking off also left
	 * {@link #lastHolding} permanently null — and {@link #totalWorth()} is built
	 * on it. Three unrelated features quietly degraded: the bond ladder counted
	 * the bank and nothing the player was carrying, the wealth-relative loot
	 * threshold collapsed to its flat gp floor, and the recorded tick vector's
	 * {@code worth} column went wrong for the whole session.
	 */
	private void updatePnl()
	{
		final Map<Integer, Integer> holding = new java.util.HashMap<>();
		for (net.runelite.api.InventoryID cid : new net.runelite.api.InventoryID[]{
			net.runelite.api.InventoryID.INVENTORY, net.runelite.api.InventoryID.EQUIPMENT})
		{
			final net.runelite.api.ItemContainer c = client.getItemContainer(cid);
			if (c == null)
			{
				continue;
			}
			for (net.runelite.api.Item item : c.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					holding.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}

		if (config.trackPnl() && lastHolding != null && !pnlPaused())
		{
			long delta = 0;
			final Set<Integer> ids = new java.util.HashSet<>(holding.keySet());
			ids.addAll(lastHolding.keySet());
			for (int id : ids)
			{
				final int diff = holding.getOrDefault(id, 0) - lastHolding.getOrDefault(id, 0);
				if (diff != 0)
				{
					delta += diff * itemValue(id);
				}
			}
			if (delta != 0)
			{
				sessionPnl += delta;
				if (delta > 0)
				{
					gainedValue += delta;
				}
				panel.setPnl(sessionPnl);
				final Map<String, Object> d = m();
				d.put("delta", delta);
				d.put("total", sessionPnl);
				emit("pnl", d);
			}
		}
		lastHolding = holding;
	}

	/** Everything we can see, valued at GE prices: carried + last-seen bank. */
	private long totalWorth()
	{
		long worth = Math.max(0, bankValue);
		if (lastHolding != null)
		{
			for (Map.Entry<Integer, Integer> e : lastHolding.entrySet())
			{
				worth += e.getValue() * itemValue(e.getKey());
			}
		}
		return worth;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == net.runelite.api.InventoryID.INVENTORY.getId()
			|| event.getContainerId() == net.runelite.api.InventoryID.EQUIPMENT.getId())
		{
			updatePnl();
		}
		else if (event.getContainerId() == net.runelite.api.InventoryID.BANK.getId())
		{
			// bank open recalibrates total worth — members items count, F2P can still SELL them on GE
			long v = 0;
			for (net.runelite.api.Item item : event.getItemContainer().getItems())
			{
				if (item != null && item.getId() > 0)
				{
					v += (long) item.getQuantity() * itemValue(item.getId());
				}
			}
			bankValue = v;
		}

		// full inventory only means "bank" for a BANKING gatherer with no supplies:
		// food/pots in the bag = the trip continues (they free slots as you use them),
		// power-trainers drop instead, and combat is never nudged
		if (event.getContainerId() == net.runelite.api.InventoryID.INVENTORY.getId()
			&& event.getItemContainer().count() >= 28)
		{
			final String act = currentActivity();
			if (act != null && !"Combat".equals(act)
				&& !"xp".equals(goalMode())
				&& client.getTickCount() - lastDropTick > 150
				&& countInventoryAction("Eat") == 0
				&& countInventoryAction("Drink") == 0)
			{
				overlay.setAlert("Inventory full — bank it", client.getTickCount() + 8);
				voice.play("bank");
			}
		}

		final Map<String, Object> d = m();
		d.put("containerId", event.getContainerId());
		d.put("itemCount", event.getItemContainer().count());
		emit("container", d);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Drop".equals(event.getMenuOption()))
		{
			lastDropClickItemId = event.getId();
			lastDropClickTick = client.getTickCount();
		}

		final Map<String, Object> d = m();
		d.put("option", event.getMenuOption());
		d.put("target", event.getMenuTarget());
		d.put("action", event.getMenuAction() != null ? event.getMenuAction().name() : null);
		d.put("id", event.getId());
		emit("click", d);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		emit("npcSpawn", Map.of("npc", actorInfo(event.getNpc())));
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		emit("npcDespawn", Map.of("npc", actorInfo(event.getNpc())));
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		emit("playerSpawn", Map.of("player", actorInfo(event.getPlayer())));
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		emit("playerDespawn", Map.of("player", actorInfo(event.getPlayer())));
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		final WorldPoint wp = event.getTile().getWorldLocation();

		// LONG math, once, for both consumers. Stack value overflows an int at
		// ~2.1B, which a dropped stack of anything expensive clears easily — and
		// an overflowed value wraps NEGATIVE, so the single loot flash worth
		// shouting about is the one that silently fails the threshold test. The
		// event-stream copy below was already computing this in long; the alert
		// path was not, and they are the same number.
		final long value = (long) itemManager.getItemPrice(event.getItem().getId())
			* event.getItem().getQuantity();

		// loot flash: nearby drop worth picking up (GE value filter)
		final Player lp = client.getLocalPlayer();
		if (lp != null && lp.getWorldLocation().distanceTo(wp) <= 8)
		{

			// spawned right after we clicked Drop on this item id -> OUR drop:
			// feeds the xp-vs-gp goal signal instead of flashing as loot
			final boolean ourDrop = event.getItem().getId() == lastDropClickItemId
				&& client.getTickCount() - lastDropClickTick <= 3;
			if (ourDrop)
			{
				droppedValue += value;
				lastDropTick = client.getTickCount();
			}
			else
			{
				final String name = client.getItemDefinition(event.getItem().getId()).getName();

				// "good drop" is relative to wealth: flat gp floor OR a % of total worth,
				// whichever is higher — 100gp matters at 100k worth, not at 1B
				final long worth = totalWorth();
				final long threshold = Math.max(config.minLootValue(),
					(long) (worth * config.lootWorthPercent() / 100.0));

				if (PRAYER_BONES.contains(name.toLowerCase()))
				{
					// prayer xp beats GE price — always worth burying, but bones every
					// kill are routine: silent flash only, the voice is for real loot
					overlay.flashTile(wp, RuneAIOverlay.LOOT, name + " · bury",
						client.getTickCount() + 12);
				}
				else if (value >= threshold)
				{
					overlay.flashTile(wp, RuneAIOverlay.LOOT,
						String.format("%s · %,d gp", name, value),
						client.getTickCount() + 12);
					voice.play("loot");
				}
			}
		}

		final Map<String, Object> d = m();
		d.put("id", event.getItem().getId());
		d.put("qty", event.getItem().getQuantity());
		d.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
		// absolute AND wealth-relative value — models learn that "good drop" scales with the bank
		final long tw = totalWorth();
		d.put("value", value);
		d.put("worthRatio", tw > 0 ? (double) value / tw : 0.0);
		emit("itemSpawn", d);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		final Map<String, Object> d = m();
		d.put("id", event.getItem().getId());
		final WorldPoint wp = event.getTile().getWorldLocation();
		d.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
		emit("itemDespawn", d);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.logVarbits())
		{
			return;
		}
		final Map<String, Object> d = m();
		d.put("varbit", event.getVarbitId());
		d.put("varp", event.getVarpId());
		d.put("value", event.getValue());
		emit("varbit", d);
	}

	@Provides
	RuneAIConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneAIConfig.class);
	}
}
