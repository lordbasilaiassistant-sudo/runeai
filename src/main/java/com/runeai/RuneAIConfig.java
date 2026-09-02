package com.runeai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("runeai")
public interface RuneAIConfig extends Config
{
	/**
	 * The only part of RuneAI that can talk to anything off the player's
	 * machine, which is why it is boxed off in its own section, defaults to off,
	 * and carries the third-party warning on its master switch.
	 */
	@ConfigSection(
		name = "Streamer mode",
		description = "Off by default. An LLM co-host that narrates your session while you play, so the RuneLite window can be your only OBS source. Needs your own API key and sends a short text summary of recent events to the endpoint you choose",
		position = 100,
		closedByDefault = true
	)
	String STREAMER = "streamer";

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
		keyName = "quickInstaOnly",
		name = "Quick lane: insta prices only",
		description = "Quick picks quote the instant-buy and instant-sell prices — both sides fill on placement — and only items still profitable after tax at those prices qualify. When none exist right now, the fastest queue flips are shown instead, labeled with their honest wait estimate"
	)
	default boolean quickInstaOnly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionResume",
		name = "Resume session after relaunch",
		description = "A session is a stretch of flipping, not a stretch of staying logged in. Logging out to wait on offers and coming back continues the same session — profits, counts and gp/h carry on. A new session only starts after the gap below"
	)
	default boolean sessionResume()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionGapMins",
		name = "New session after (min)",
		description = "Minutes away from the client before the next launch counts as a NEW session instead of resuming the last one"
	)
	default int sessionGapMins()
	{
		return 480;
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

	// ================= streamer mode (opt-in, off by default) =================

	@ConfigItem(
		keyName = "streamerMode",
		name = "Streamer mode",
		description = "An LLM co-host that commentates your session out loud while you play. OFF sends nothing, ever: no beats are collected and no request is built. ON sends a short text summary of recent events (item names, gp, levels, hitpoints) to the endpoint you configure below — never your account name, your position, or your recordings",
		warning = "Streamer mode sends a short text summary of what happens in your session to a THIRD-PARTY SERVER that is not run by RuneAI or RuneLite — the LLM endpoint you configure, and optionally ElevenLabs for speech. You supply your own API keys and you pay for the usage. Your local recordings are never sent. Enable only if you want this.",
		section = STREAMER,
		position = 101
	)
	default boolean streamerMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "streamerBaseUrl",
		name = "LLM base URL",
		description = "Any OpenAI-compatible chat-completions endpoint, e.g. https://api.groq.com/openai/v1 or https://api.openai.com/v1. Must be https — a plaintext http:// endpoint is refused, because every request carries your API key. Blank means the co-host stays silent no matter what else is set",
		section = STREAMER,
		position = 102
	)
	default String streamerBaseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "streamerModel",
		name = "LLM model",
		description = "Model id to ask for, e.g. llama-3.3-70b-versatile, gpt-4o-mini. Must exist on the endpoint above",
		section = STREAMER,
		position = 103
	)
	default String streamerModel()
	{
		return "llama-3.3-70b-versatile";
	}

	@ConfigItem(
		keyName = "streamerKey",
		name = "LLM API key",
		description = "Your own key for that endpoint. Stored in your RuneLite profile like any other plugin setting — it is not encrypted, so treat it the way you treat the rest of that file",
		secret = true,
		section = STREAMER,
		position = 104
	)
	default String streamerKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "streamerSecs",
		name = "Seconds between lines",
		description = "Floor on how often the co-host may speak. Every line is a paid request, and a commentator who never shuts up is worse than one who waits for something to happen. Minimum 15",
		section = STREAMER,
		position = 105
	)
	default int streamerSecs()
	{
		return 45;
	}

	@ConfigItem(
		keyName = "streamerPersona",
		name = "Co-host persona",
		description = "One line describing the voice you want, e.g. 'a dry veteran caster who has seen every drop table'. Blank uses the default",
		section = STREAMER,
		position = 106
	)
	default String streamerPersona()
	{
		return "";
	}

	@ConfigItem(
		keyName = "streamerChat",
		name = "Also print in chat",
		description = "Echo each commentary line into the game chat box as well as the mascot's speech bubble. Useful when the bubble is off-camera",
		section = STREAMER,
		position = 107
	)
	default boolean streamerChat()
	{
		return false;
	}

	@ConfigItem(
		keyName = "streamerTtsKey",
		name = "ElevenLabs key (optional)",
		description = "Optional, and a SECOND third-party server. The bundled Kokoro voice lines are pre-rendered files, so there is no local synthesiser for arbitrary commentary — with no key here the co-host is text-only in the mascot's speech bubble, which is the default",
		secret = true,
		section = STREAMER,
		position = 108
	)
	default String streamerTtsKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "streamerVoiceId",
		name = "ElevenLabs voice id",
		description = "Voice id from your ElevenLabs Voices page. Ignored without a key above. The default is a premade voice a free account can use — LIBRARY voices are rejected on the free tier with HTTP 402, so if the co-host goes text-only, that is usually why",
		section = STREAMER,
		position = 109
	)
	default String streamerVoiceId()
	{
		// measured 2026-08-10 against a free-tier account: this premade voice returns
		// pcm_24000 with HTTP 200, while a library voice id returns 402
		return "CwhRBWXzGAHq8TQ4Fs17";
	}
}
