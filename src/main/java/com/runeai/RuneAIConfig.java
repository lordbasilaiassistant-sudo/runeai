package com.runeai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runeai")
public interface RuneAIConfig extends Config
{
	@ConfigItem(
		keyName = "greeting",
		name = "Login greeting",
		description = "Message RuneAI sends in chat when you log in"
	)
	default String greeting()
	{
		return "Welcome back";
	}

	@ConfigItem(
		keyName = "logEvents",
		name = "Log live events",
		description = "Stream all game events to .runelite/runeai/events-*.jsonl (restart plugin to apply)"
	)
	default boolean logEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logVarbits",
		name = "Log varbit changes",
		description = "Include raw varbit/varp changes in the event stream (very chatty on login)"
	)
	default boolean logVarbits()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPnl",
		name = "Track session profit/loss",
		description = "Live gp ledger: loot kept and pickups count up, drops and consumed supplies count down; banking/GE are neutral transfers"
	)
	default boolean trackPnl()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMascot",
		name = "Show mascot",
		description = "Rune, the lip-syncing companion — Alt-drag to move it anywhere"
	)
	default boolean showMascot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "voiceCallouts",
		name = "Voice callouts",
		description = "Spoken guidance: idle nudges, eat warnings, bank reminders, loot calls"
	)
	default boolean voiceCallouts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minLootValue",
		name = "Min loot value (gp)",
		description = "Flat gp floor — drops below this never call out"
	)
	default int minLootValue()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "lootWorthPercent",
		name = "Loot threshold (% of worth)",
		description = "A drop must also be worth this percent of your total worth — 'good drop' scales with your bank (0 = flat floor only)"
	)
	default double lootWorthPercent()
	{
		return 0.2;
	}

	@ConfigItem(
		keyName = "showOverlays",
		name = "Show overlays",
		description = "Draw combat outlines, tile flashes, and alerts on the game view"
	)
	default boolean showOverlays()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideIdle",
		name = "Idle click guidance",
		description = "When you go idle mid-task, flash the tile of the next thing to click"
	)
	default boolean guideIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "potReminder",
		name = "Potion reminder",
		description = "Remind you to drink a boost potion when fighting unpotted with one in your inventory"
	)
	default boolean potReminder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lowHpWarn",
		name = "Low HP alert",
		description = "Show an EAT warning when under attack with low hitpoints"
	)
	default boolean lowHpWarn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lowHpPercent",
		name = "Low HP threshold %",
		description = "HP percent that triggers the EAT warning"
	)
	default int lowHpPercent()
	{
		return 33;
	}

	@ConfigItem(
		keyName = "recordTicks",
		name = "Record tick vectors",
		description = "Write a fixed-shape state record every game tick to .runelite/runeai/ticks-*.jsonl — NN training data (restart plugin to apply)"
	)
	default boolean recordTicks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "heartbeatTicks",
		name = "Heartbeat interval (ticks)",
		description = "Write a compact player-state line to the event stream every N game ticks"
	)
	default int heartbeatTicks()
	{
		return 10;
	}
}
