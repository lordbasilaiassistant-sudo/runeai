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
	private long lastSellProfit; // most recent realized sell profit, for item memory

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

	/** Max item price unlocked at a trader level — gp stack and skill grow together. */
	static long traderMaxPrice(int level)
	{
		return (long) (500 * Math.pow(1.135, level));
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
		loadFlipBasis();
		loadTrader();
		loadClog();
		loadOfferState();
		chatCommandManager.registerCommandAsync("!profit", (msg, txt) ->
		{
			msg.getMessageNode().setValue(String.format(
				"<col=00b4ff>RuneAI</col> flips: %,d gp this session (%,d gp/h)",
				flipRealized, lastFlipGpHr));
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

	@Override
	protected void shutDown() throws Exception
	{
		chatCommandManager.unregisterCommand("!profit");
		chatCommandManager.unregisterCommand("!lvl");
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
		log.info("RuneAI plugin stopped");
	}

	// ================= snapshot =================

	private void writeSnapshot()
	{
		try
		{
			final Map<String, Object> snap = GameStateSnapshot.capture(client);
			final File f = new File(DATA_DIR, "snapshot-" + LocalDateTime.now().format(STAMP) + ".json");
			Files.write(f.toPath(), prettyGson.toJson(snap).getBytes(StandardCharsets.UTF_8));
			log.info("RuneAI snapshot written: {} ({} KB)", f.getAbsolutePath(), f.length() / 1024);
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
	}

	private Map<String, Object> m()
	{
		return new LinkedHashMap<>();
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
				// restart-safe: if we tracked this exact offer before the client
				// restarted, resume its counters — never re-book old fills
				final long[] saved = savedOfferState.get(String.valueOf(slot));
				if (saved != null && saved[0] == o.getItemId() && saved[1] == o.getPrice())
				{
					tr.lastQty = (int) saved[2];
					tr.lastSpent = saved[3];
					tr.startMs = saved[4];
					// restore the stall clock too — a quiet offer must not look
					// freshly-filled just because the client restarted
					tr.lastFillMs = saved.length > 5 ? saved[5] : saved[4];
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
					lastSellProfit = profit;
					lifetimeRealized += profit;
					if (!carryover)
					{
						flipRealized += profit;
						unitsSold += dQty;
					}
					panel.setFlipPnl(flipRealized);

					// TRADE COLLECTION LOG: first profitable real buy->sell of an item
					if (profit > 0 && realBasis && tradeClog.add(o.getItemId()))
					{
						saveClog();
						final String iname = client.getItemDefinition(o.getItemId()).getName();
						overlay.setAlert("NEW TRADE COLLECTED: " + iname + "!", client.getTickCount() + 10);
						mascot.celebrate("New trade logged: " + iname + "!");
						panel.addCollected(iname, itemManager.getImage(o.getItemId()));
					}

					// TRADER xp: positive profit only, real OSRS curve
					if (profit > 0)
					{
						traderXp += profit * 0.1; // 0.1 xp per gp: 99 = ~130M lifetime profit
						saveTrader();
						final int nl = traderLevelFor(traderXp);
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
		final boolean sug = flipService.wasSuggested(o.getItemId(), o.getPrice(), buying);
		if (st == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| st == net.runelite.api.GrandExchangeOfferState.SOLD)
		{
			if (tr != null && tr.itemId == o.getItemId())
			{
				fillSecs = (nowMs - tr.startMs) / 1000;
				if (!offlineFill)
				{
					(buying ? buyFillSecs : sellFillSecs).add(fillSecs);
				}
				itemMemory.recordFill(o.getItemId(), offlineFill ? -1 : fillSecs,
					buying ? 0 : lastSellProfit);
			}
			if (sug)
			{
				sugFills++;
			}
			mascot.celebrate(buying
				? "Bought! Now sell it."
				: "Cha-ching! Sold.");
			offerTracks[slot] = null;
		}
		else if (st == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY
			|| st == net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL)
		{
			if (sug)
			{
				sugCancels++; // our call stalled long enough that they pulled it
			}
			itemMemory.recordStall(o.getItemId()); // it stopped working — learn that
			offerTracks[slot] = null;
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
			flipService.setTraderTier(traderLevel, traderMaxPrice(traderLevel));
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
					sugg.add(sd);
				}
				final Map<String, Object> d = m();
				d.put("suggestions", sugg);
				d.put("budget", coins);
				d.put("marketSurprise", flipService.lastScanAvgErr);
				emit("flipScan", d);
			}
			geFlipOverlay.setTrader(traderLevel,
				traderLevel < 99 ? (traderXp - TRADER_XP[traderLevel - 1])
					/ Math.max(1.0, TRADER_XP[traderLevel] - TRADER_XP[traderLevel - 1]) : 1.0);
			panel.setFlips(flipService.getTopFlips());

			// slot utilization: an idle slot is wasted throughput
			int active = 0;
			final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			final int slots = membersW ? 8 : 3;
			for (int i = 0; i < Math.min(slots, offers.length); i++)
			{
				final net.runelite.api.GrandExchangeOfferState os = offers[i].getState();
				if (os != net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					active++;
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
			geFlipOverlay.setStats(active, slots, median(buyFillSecs), median(sellFillSecs),
				sugFills, sugCancels, flipGpHr, flipRealized, lifetimeRealized,
				(int) unitsBought, (int) unitsSold,
				firstOfferMs > 0 ? (System.currentTimeMillis() - firstOfferMs) / 60_000 : 0);
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

		recordTickVector(lp);
		updateGuidance(lp);
		damageTakenThisTick = 0;
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
				savedOfferState = gson.fromJson(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
					new com.google.gson.reflect.TypeToken<Map<String, long[]>>(){}.getType());
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
					out.put(String.valueOf(i),
						new long[]{t.itemId, t.price, t.lastQty, t.lastSpent, t.startMs, t.lastFillMs});
				}
			}
			Files.write(new File(DATA_DIR, "offer-state.json").toPath(),
				gson.toJson(out).getBytes(StandardCharsets.UTF_8));
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
			Files.write(new File(DATA_DIR, "trade-clog.json").toPath(),
				gson.toJson(tradeClog).getBytes(StandardCharsets.UTF_8));
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
			panel.addCollected(client.getItemDefinition(id).getName(), itemManager.getImage(id));
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
			Files.write(new File(DATA_DIR, "trader.json").toPath(),
				gson.toJson(Map.of("xp", traderXp, "lifetime", lifetimeRealized))
					.getBytes(StandardCharsets.UTF_8));
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
			Files.write(new File(DATA_DIR, "flip-basis.json").toPath(),
				gson.toJson(raw).getBytes(StandardCharsets.UTF_8));
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
			if (underAttack && hp * 100 / max <= config.lowHpPercent())
			{
				if (countInventoryAction("Eat") > 0)
				{
					overlay.setAlert("EAT — HP " + hp + "/" + max, tick + 2);
					voice.play("eat");
				}
				else if (tick - lastFoodWarnTick >= 100)
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
	 * Fixed-shape per-tick state record — the NN training corpus.
	 * One line per game tick; dmgTaken is the built-in label for
	 * "should I have been warned last tick?"
	 */
	private void recordTickVector(Player lp)
	{
		if (tickLog == null)
		{
			return;
		}

		final Map<String, Object> d = m();
		final WorldPoint wp = lp.getWorldLocation();
		d.put("x", wp.getX());
		d.put("y", wp.getY());
		d.put("plane", wp.getPlane());
		d.put("region", wp.getRegionID());
		d.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		d.put("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
		d.put("pray", client.getBoostedSkillLevel(Skill.PRAYER));
		d.put("energy", client.getEnergy() / 100.0);
		d.put("spec", client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
		d.put("anim", lp.getAnimation());
		d.put("pose", lp.getPoseAnimation());
		d.put("graphic", lp.getGraphic());
		d.put("dmgTaken", damageTakenThisTick);
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

		final Actor inter = lp.getInteracting();
		d.put("targetNpcId", inter instanceof NPC ? ((NPC) inter).getId() : -1);

		// nearest 8 NPCs, sorted by distance — the local threat picture
		final List<NPC> npcs = new ArrayList<>(client.getNpcs());
		npcs.removeIf(n -> n == null || n.getName() == null);
		npcs.sort(Comparator.comparingInt(n -> wp.distanceTo(n.getWorldLocation())));
		final List<Map<String, Object>> near = new ArrayList<>();
		for (int i = 0; i < Math.min(8, npcs.size()); i++)
		{
			final NPC n = npcs.get(i);
			final Map<String, Object> nm = m();
			nm.put("id", n.getId());
			nm.put("dist", wp.distanceTo(n.getWorldLocation()));
			nm.put("anim", n.getAnimation());
			nm.put("hr", n.getHealthRatio());
			nm.put("atkMe", n.getInteracting() == lp);
			near.add(nm);
		}
		d.put("npcCount", npcs.size());
		d.put("npcs", near);

		tickLog.log("tick", client.getTickCount(), d);
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

	/** P&L pauses while bank / GE / deposit box / shop is open — those are transfers, not gains/losses. */
	private boolean pnlPaused()
	{
		for (int group : new int[]{12, 465, 192, 300})
		{
			final net.runelite.api.widgets.Widget w = client.getWidget(group, 0);
			if (w != null && !w.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	private long itemValue(int id)
	{
		return id == net.runelite.api.ItemID.COINS_995 ? 1 : itemManager.getItemPrice(id);
	}

	private void updatePnl()
	{
		if (!config.trackPnl())
		{
			return;
		}
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

		if (lastHolding != null && !pnlPaused())
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

		// loot flash: nearby drop worth picking up (GE value filter)
		final Player lp = client.getLocalPlayer();
		if (lp != null && lp.getWorldLocation().distanceTo(wp) <= 8)
		{
			final int value = itemManager.getItemPrice(event.getItem().getId()) * event.getItem().getQuantity();

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
					overlay.flashTile(wp, RuneAIOverlay.LOOT, name + " · " + value + " gp",
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
		final long v = (long) itemManager.getItemPrice(event.getItem().getId()) * event.getItem().getQuantity();
		final long tw = totalWorth();
		d.put("value", v);
		d.put("worthRatio", tw > 0 ? (double) v / tw : 0.0);
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
