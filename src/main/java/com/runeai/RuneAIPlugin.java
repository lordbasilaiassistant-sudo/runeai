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
	description = "RuneAI data layer — full game state snapshots + live event stream",
	tags = {"ai", "runeai", "data"}
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
	private Gson gson;

	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	@Inject
	private RuneAIOverlay overlay;

	@Inject
	private VoicePlayer voice;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

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
	private Skill lastXpSkill;
	private int lastXpTick = -1000;
	private int lastTargetNpcId = -1;
	private WorldPoint lastPos;
	private int idleTicks;
	private int lastGuideTick;
	private boolean wasUnderAttack;

	@Override
	protected void startUp() throws Exception
	{
		DATA_DIR.mkdirs();
		prettyGson = gson.newBuilder().setPrettyPrinting().create();

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
		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);
		log.info("RuneAI plugin started (data dir {})", DATA_DIR.getAbsolutePath());
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(overlay);
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
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			panel.setPlayer(null);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final Player lp = client.getLocalPlayer();
		if (lp == null)
		{
			return;
		}

		panel.setCounts(client.getNpcs().size(), client.getPlayers().size(),
			eventLog != null ? eventLog.getLines() : 0);

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

	private void updateGuidance(Player lp)
	{
		final int tick = client.getTickCount();

		// remember current combat target for "attack the next one" guidance
		if (lp.getInteracting() instanceof NPC)
		{
			lastTargetNpcId = ((NPC) lp.getInteracting()).getId();
		}

		// attack + low HP awareness
		final boolean underAttack = client.getNpcs().stream()
			.anyMatch(n -> n != null && n.getInteracting() == lp);
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
				overlay.setAlert("EAT — HP " + hp + "/" + max, tick + 2);
				voice.play("eat");
			}
		}

		final String activity = currentActivity();
		panel.setActivity(activity == null ? "—" : activity);

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

		if (idleTicks >= 4 && tick - lastGuideTick >= 6)
		{
			lastGuideTick = tick;
			final Object[] target = findNearestClickable(activity, pos);
			if (target != null)
			{
				overlay.flashTile((WorldPoint) target[0], RuneAIOverlay.GUIDE,
					"Click: " + target[1], tick + 6);
				voice.play("idle");
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

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// gathering with a full inventory -> nudge to bank
		if (event.getContainerId() == net.runelite.api.InventoryID.INVENTORY.getId()
			&& event.getItemContainer().count() >= 28)
		{
			final String act = currentActivity();
			if (act != null && !"Combat".equals(act))
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
			if (value >= config.minLootValue())
			{
				final String name = client.getItemDefinition(event.getItem().getId()).getName();
				overlay.flashTile(wp, RuneAIOverlay.LOOT, name + " · " + value + " gp",
					client.getTickCount() + 12);
				voice.play("loot");
			}
		}

		final Map<String, Object> d = m();
		d.put("id", event.getItem().getId());
		d.put("qty", event.getItem().getQuantity());
		d.put("pos", wp.getX() + "," + wp.getY() + "," + wp.getPlane());
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
