package com.runeai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

/**
 * Captures everything the RuneLite API exposes into one big map.
 * MUST be called on the client thread.
 */
final class GameStateSnapshot
{
	private GameStateSnapshot()
	{
	}

	static Map<String, Object> capture(Client client)
	{
		final Map<String, Object> root = new LinkedHashMap<>();

		// ---- meta / world ----
		final Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("capturedAt", Instant.now().toString());
		meta.put("gameState", client.getGameState().name());
		meta.put("world", client.getWorld());
		meta.put("worldTypes", client.getWorldType().toString());
		meta.put("tickCount", client.getTickCount());
		meta.put("gameCycle", client.getGameCycle());
		meta.put("fps", client.getFPS());
		meta.put("plane", client.getPlane());
		meta.put("baseX", client.getBaseX());
		meta.put("baseY", client.getBaseY());
		meta.put("mapRegions", client.getMapRegions());
		meta.put("instanced", client.isInInstancedRegion());
		meta.put("canvas", client.getCanvasWidth() + "x" + client.getCanvasHeight());
		meta.put("resized", client.isResized());
		root.put("meta", meta);

		final Player lp = client.getLocalPlayer();
		final WorldPoint me = lp != null ? lp.getWorldLocation() : null;

		// ---- local player ----
		if (lp != null && me != null)
		{
			final Map<String, Object> p = new LinkedHashMap<>();
			p.put("name", lp.getName());
			p.put("combatLevel", lp.getCombatLevel());
			p.put("x", me.getX());
			p.put("y", me.getY());
			p.put("plane", me.getPlane());
			p.put("regionId", me.getRegionID());
			p.put("animation", lp.getAnimation());
			p.put("poseAnimation", lp.getPoseAnimation());
			p.put("graphic", lp.getGraphic());
			p.put("healthRatio", lp.getHealthRatio());
			p.put("healthScale", lp.getHealthScale());
			p.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
			p.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER) + "/" + client.getRealSkillLevel(Skill.PRAYER));
			p.put("runEnergyPercent", client.getEnergy() / 100.0);
			p.put("weight", client.getWeight());
			p.put("specPercent", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10);
			p.put("interacting", lp.getInteracting() != null ? lp.getInteracting().getName() : null);
			root.put("player", p);
		}

		// ---- skills ----
		final Map<String, Object> skills = new LinkedHashMap<>();
		for (Skill s : Skill.values())
		{
			final Map<String, Object> sk = new LinkedHashMap<>();
			sk.put("level", client.getRealSkillLevel(s));
			sk.put("boosted", client.getBoostedSkillLevel(s));
			sk.put("xp", client.getSkillExperience(s));
			skills.put(s.getName(), sk);
		}
		skills.put("totalLevel", client.getTotalLevel());
		skills.put("overallXp", client.getOverallExperience());
		root.put("skills", skills);

		// ---- containers ----
		root.put("inventory", items(client, InventoryID.INVENTORY));
		root.put("equipment", items(client, InventoryID.EQUIPMENT));
		root.put("bank", items(client, InventoryID.BANK));

		// ---- NPCs ----
		final List<Map<String, Object>> npcs = new ArrayList<>();
		for (NPC npc : client.getNpcs())
		{
			if (npc == null)
			{
				continue;
			}
			final Map<String, Object> n = new LinkedHashMap<>();
			final WorldPoint wp = npc.getWorldLocation();
			n.put("index", npc.getIndex());
			n.put("id", npc.getId());
			n.put("name", npc.getName());
			n.put("combatLevel", npc.getCombatLevel());
			n.put("x", wp.getX());
			n.put("y", wp.getY());
			n.put("distance", me != null ? me.distanceTo(wp) : -1);
			n.put("animation", npc.getAnimation());
			n.put("graphic", npc.getGraphic());
			n.put("healthRatio", npc.getHealthRatio());
			n.put("healthScale", npc.getHealthScale());
			n.put("interacting", npc.getInteracting() != null ? npc.getInteracting().getName() : null);
			npcs.add(n);
		}
		root.put("npcs", npcs);

		// ---- other players ----
		final List<Map<String, Object>> players = new ArrayList<>();
		for (Player pl : client.getPlayers())
		{
			if (pl == null || pl == lp)
			{
				continue;
			}
			final Map<String, Object> n = new LinkedHashMap<>();
			final WorldPoint wp = pl.getWorldLocation();
			n.put("name", pl.getName());
			n.put("combatLevel", pl.getCombatLevel());
			n.put("x", wp.getX());
			n.put("y", wp.getY());
			n.put("distance", me != null ? me.distanceTo(wp) : -1);
			n.put("animation", pl.getAnimation());
			n.put("graphic", pl.getGraphic());
			players.add(n);
		}
		root.put("players", players);

		// ---- scene sweep: ground items + nearby objects ----
		final List<Map<String, Object>> groundItems = new ArrayList<>();
		final List<Map<String, Object>> nearbyObjects = new ArrayList<>();
		final Set<Long> seenObjects = new HashSet<>();
		int objectCount = 0;

		final Scene scene = client.getScene();
		final Tile[][][] tiles = scene.getTiles();
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

				final List<TileItem> tis = tile.getGroundItems();
				if (tis != null)
				{
					for (TileItem ti : tis)
					{
						final Map<String, Object> gi = new LinkedHashMap<>();
						final WorldPoint wp = tile.getWorldLocation();
						gi.put("id", ti.getId());
						gi.put("name", client.getItemDefinition(ti.getId()).getName());
						gi.put("quantity", ti.getQuantity());
						gi.put("x", wp.getX());
						gi.put("y", wp.getY());
						gi.put("distance", me != null ? me.distanceTo(wp) : -1);
						groundItems.add(gi);
					}
				}

				final GameObject[] gos = tile.getGameObjects();
				if (gos != null)
				{
					for (GameObject go : gos)
					{
						if (go == null || !seenObjects.add(go.getHash()))
						{
							continue;
						}
						objectCount++;
						final WorldPoint wp = go.getWorldLocation();
						final int dist = me != null ? me.distanceTo(wp) : -1;
						if (dist >= 0 && dist <= 12 && nearbyObjects.size() < 80)
						{
							final ObjectComposition def = client.getObjectDefinition(go.getId());
							if (def != null && def.getName() != null && !"null".equals(def.getName()))
							{
								final Map<String, Object> o = new LinkedHashMap<>();
								o.put("id", go.getId());
								o.put("name", def.getName());
								o.put("x", wp.getX());
								o.put("y", wp.getY());
								o.put("distance", dist);
								nearbyObjects.add(o);
							}
						}
					}
				}
			}
		}
		root.put("groundItems", groundItems);
		root.put("gameObjectCount", objectCount);
		root.put("nearbyObjects", nearbyObjects);

		// ---- varps (nonzero only — raw game settings/state ints) ----
		final Map<String, Integer> varps = new LinkedHashMap<>();
		final int[] raw = client.getVarps();
		for (int i = 0; i < raw.length; i++)
		{
			if (raw[i] != 0)
			{
				varps.put(String.valueOf(i), raw[i]);
			}
		}
		root.put("varpsNonZero", varps);

		// ---- open interfaces (root widget groups) ----
		final List<Integer> widgetGroups = new ArrayList<>();
		for (Widget w : client.getWidgetRoots())
		{
			if (w != null && !w.isHidden())
			{
				widgetGroups.add(w.getId() >> 16);
			}
		}
		root.put("openWidgetGroups", widgetGroups);

		// ---- camera + menu ----
		final Map<String, Object> cam = new LinkedHashMap<>();
		cam.put("x", client.getCameraX());
		cam.put("y", client.getCameraY());
		cam.put("z", client.getCameraZ());
		cam.put("pitch", client.getCameraPitch());
		cam.put("yaw", client.getCameraYaw());
		root.put("camera", cam);

		final List<String> menu = new ArrayList<>();
		for (MenuEntry e : client.getMenuEntries())
		{
			menu.add(e.getOption() + " " + e.getTarget());
		}
		root.put("menuEntries", menu);

		return root;
	}

	private static List<Map<String, Object>> items(Client client, InventoryID id)
	{
		final List<Map<String, Object>> out = new ArrayList<>();
		final ItemContainer c = client.getItemContainer(id);
		if (c == null)
		{
			return out;
		}
		for (net.runelite.api.Item item : c.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", item.getId());
			m.put("name", client.getItemDefinition(item.getId()).getName());
			m.put("quantity", item.getQuantity());
			out.add(m);
		}
		return out;
	}
}
