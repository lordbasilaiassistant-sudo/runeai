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
		keyName = "dangerModel",
		name = "Danger-aware warnings",
		description = "Use the danger model trained on your own recorded ticks (py train/train_damage_model.py) as a coarse risk prior: in a context that has actually hurt you, the low-HP EAT warning fires earlier and repeats sooner. It never predicts an attack and never marks a tile. No trained model on disk means no change at all"
	)
	default boolean dangerModel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trapGuard",
		name = "Thin-book trap guard",
		description = "On illiquid items whose only recent high print is a whale overpay, mute reprice coaching instead of quoting the whale's price back at you"
	)
	default boolean trapGuard()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trapBoard",
		name = "Overnight trap board",
		description = "Suggest cheap patience orders parked just under the round numbers whales type — needs sim/whale_trap_report.py to have written trap-board.json"
	)
	default boolean trapBoard()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trapSlots",
		name = "Trap slots",
		description = "GE slots reserved for blue-moon patience orders. Every other slot stays on the quick-flip lane — the board only ever suggests enough to top this up"
	)
	default int trapSlots()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "flipLanes",
		name = "Quick-flip lane first",
		description = "Split suggestions into QUICK (fills in minutes, compounds during a session) and OVERNIGHT (patience orders). Session suggestions come from the quick lane only"
	)
	default boolean flipLanes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "quickCycleSecs",
		name = "Quick cycle ceiling (s)",
		description = "Longest full buy-then-sell cycle that still counts as a quick flip. Anything slower is an overnight candidate"
	)
	default int quickCycleSecs()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "overnightMode",
		name = "Heading offline",
		description = "Flip this on before you log out: every free slot becomes a patience order instead of just the reserved trap slots, because a held slot costs nothing while you are away"
	)
	default boolean overnightMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "tradeAudit",
		name = "Audit P&L vs GE history",
		description = "When you open the in-game Trade History, read it and diff it against the profit RuneAI booked, then report the disagreements. Read-only — it only looks at an interface you opened"
	)
	default boolean tradeAudit()
	{
		return true;
	}

	@ConfigItem(
		keyName = "anomalyAlert",
		name = "Price anomaly alert",
		description = "Call out an item whose price has ripped away from where it was, on real volume and on both sides of the book. It states what moved and by how much — an RWT pump, an update panic and a bot ban look identical to a ranker, so the call stays yours"
	)
	default boolean anomalyAlert()
	{
		return true;
	}

	@ConfigItem(
		keyName = "anomalyPercent",
		name = "Anomaly threshold %",
		description = "How far the mid has to move over the ~6 minute price window before it counts as a dislocation"
	)
	default int anomalyPercent()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "anomalyHeldOnly",
		name = "Anomalies: only what I hold",
		description = "Alert only for items you are actually exposed to — carried, on a GE offer, or bought and not yet sold. Off means the whole market is watched"
	)
	default boolean anomalyHeldOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sessionScore",
		name = "Session scoreboard",
		description = "Rank this session's realised flip profit against your last session and your best one, from .runelite/runeai/session-history.json"
	)
	default boolean sessionScore()
	{
		return true;
	}

	@ConfigItem(
		keyName = "itemStats",
		name = "Per-item results",
		description = "Show what each item has actually paid you — flips, measured fill time, realised gp — instead of only what the ranker predicts"
	)
	default boolean itemStats()
	{
		return true;
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
