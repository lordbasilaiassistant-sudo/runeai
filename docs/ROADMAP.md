# RuneAI Roadmap

Last updated: 2026-08-09

RuneAI is an AI buddy and coach for **Old School RuneScape**, built as a **RuneLite plugin**. It watches
the game state every tick and helps you click the right thing at the right time, stay alive, and keep your
profits going up.

**RuneAI does not play the game for you.** It never sends input to the client, never automates clicks, and
is not a bot. Every action stays yours — RuneAI only observes, warns, points, and talks.

The goal, stated plainly: **the most all-in-one AI companion plugin on RuneLite — a buddy that won't let you
down, won't let you die, and won't let your profits go down.**

Repo: https://github.com/lordbasilaiassistant-sudo/runeai
Support: https://ko-fi.com/broketobuilt (Ko-fi button lives in the RuneAI sidebar panel)

---

## How to read this document

Every line is marked with its real state:

- **[done]** — implemented in the current source and runnable today via `./gradlew run`.
- **[next]** — designed, partly scaffolded, not shipped. Do not assume it works.
- **[later]** — intent only. No code exists.

Nothing here is marked done unless it exists in `src/main/java/com/runeai/`.

---

## Phase 1 — Foundation (done 2026-08-09)

### Data layer [done]

Everything RuneAI learns from is recorded locally first.

| Output | File | Written by |
| --- | --- | --- |
| Per-tick state vectors | `~/.runelite/runeai/ticks-*.jsonl` | `RuneAIPlugin.recordTickVector` |
| Live event stream | `~/.runelite/runeai/events-*.jsonl` | `EventLog` |
| Full login snapshot | `~/.runelite/runeai/snapshot-*.json` | `GameStateSnapshot.capture` |

- The tick vector holds position, region, HP, prayer, run energy, special-attack percent, animation, pose,
  graphic, damage taken this tick, session P&L, detected activity, goal mode (`gp`/`xp`), session XP gained,
  members-world flag,
  total GE worth, worn item ids, current target NPC id, and the nearest 8 NPCs (id, distance, animation,
  health ratio, whether they are attacking you). `dmgTaken` is the built-in training label.
- The event stream covers chat, stats, hitsplats, deaths, animations, graphics, ground spot-anims,
  projectiles, interaction changes, item container changes, menu clicks, NPC/player spawns and despawns,
  ground item spawns and despawns, varbit changes, and a periodic heartbeat line.
- The login snapshot dumps meta/world, local player, all skills, inventory, equipment, bank, NPCs, other
  players, ground items, nearby objects, non-zero varps, open widget groups, camera, and menu entries.

**This data is local only and is never committed.** `.gitignore` blocks `*.jsonl`, `snapshot-*.json`, and
`train/damage_model.json` because recordings contain account names.

### Rule-based guidance [done]

- **Activity detection** from real XP drops — Mining, Woodcutting, Fishing, Cooking, Smithing, Crafting, and
  Combat (any of Attack, Strength, Defence, Ranged, Magic, Hitpoints).
- **Idle click guidance** — after 4 idle ticks mid-task, the nearest matching thing to click gets a green
  animated tile marker with a bobbing arrow and a label: rocks, tree, fishing spot, range/fire/stove,
  anvil/furnace, spinning wheel/loom, or the next free NPC of the type you were fighting.
- **Combat awareness overlay** — anything attacking you is outlined red and name-labelled; your current
  target is outlined cyan.
- **Low HP alert** — under attack below the configured HP percentage (default 33%): a pulsing top-centre
  `EAT — HP x/y` banner when something in the inventory carries the `Eat` action, or `NO FOOD — get out /
  bank` (rate-limited to once per 100 ticks) when nothing does.
- **Potion reminder** — fighting unpotted with a strength/attack/combat/defence/ranging/magic potion in the
  inventory triggers a `Pot up` alert, rate-limited to once per 100 ticks.
- **Loot flashes** — nearby ground drops at or above the configured GE value (default 100 gp) get a gold
  pulsing tile marker with item name and value. Junk stays silent, and so does anything you dropped yourself.
- **gp-vs-xp goal inference** — dropped value versus gained value classifies the session as a gp grind or an
  xp grind; it shows next to the activity in the panel and rides along in every tick vector as `goal`.
- **Full-inventory nudge** — filling 28 slots during a non-combat, non-xp-grind activity (and not within 150
  ticks of your own last drop) triggers `Inventory full — bank it`.
- **F2P bond ladder** — every 50 ticks, total worth (inventory + equipment + last-seen bank) is compared
  against the live GE bond price; the panel shows the percentage and a one-shot alert fires when you can
  afford one.

### Voice callouts [done]

- Eight bundled WAV lines (mono 16-bit PCM, 24 kHz) rendered offline with **local Kokoro TTS**: `idle`,
  `eat`, `bank`, `loot`, `attacked`, `pot`, `bond` (`src/main/resources/com/runeai/voice/`).
- Playback is local, offline, and dependency-free — no network calls, no cloud TTS, no API key.
- Each line has a 12-second per-key cooldown so the companion never nags.

### Mascot "Rune" [done]

- A drawn wisp companion overlay (`MascotOverlay`) anchored bottom-right, Alt-draggable anywhere.
- **Lip-sync is driven by live audio amplitude**: `VoicePlayer` computes an RMS envelope of the WAV at
  ~30 hops per second and reads the clip's actual frame position during playback, so the mouth moves with
  the real waveform rather than a canned animation.
- Blinking, drifting pupils, breathing glow, squash-and-stretch bob, orbiting sparkles, and a wrapped speech
  bubble showing the line currently being spoken.

### Session P&L [done]

- A live gp ledger built from **inventory + equipment deltas priced at GE value** (coins counted at 1 gp).
- The ledger **pauses while bank, Grand Exchange, deposit box, or shop interfaces are open**, because those
  are transfers, not profit or loss.
- Running total shows in the sidebar panel (green positive, red negative) and is written into every tick
  vector as `pnl` plus a `pnl` event on each change.
- Returning to the login screen clears only the comparison baseline; the running total keeps counting until
  the plugin is restarted.

### Sidebar panel [done]

`RuneAIPanel` shows game state, player name, detected activity (with the gp/xp goal), session P&L, bond
fund, NPCs loaded, players loaded, events logged, the data directory, and the Ko-fi support button.

### Config keys shipped [done]

Config group `runeai`, 15 keys:

`greeting`, `logEvents`, `logVarbits`, `trackPnl`, `showMascot`, `voiceCallouts`,
`minLootValue`, `showOverlays`, `guideIdle`, `potReminder`, `lowHpWarn`, `lowHpPercent`, `recordTicks`,
`heartbeatTicks`.

Most features above have their own toggle. The bank nudge and the bond ladder do not yet — they are only
silenced by `showOverlays` / `voiceCallouts`, which is a gap worth closing before hub submission.

---

## Phase 2 — Learned guidance (next)

### Trained danger model [next]

`train/train_damage_model.py` exists and runs today; **its output is not yet wired into the plugin.**

- Trains a pure-numpy logistic regression on recorded tick vectors to predict
  **P(take damage within the next 3 ticks)**.
- Nine features: `hpFrac`, `prayFrac`, `npcCount`, `nearestDist`, `nearestAtkMe`, `nearestAnim`,
  `attackersOn`, `inCombatAnim`, `bias`.
- Refuses to train on fewer than 500 recorded ticks and prints test accuracy, majority baseline, and AUC.
- Writes `train/damage_model.json` (weights + feature spec + metrics).
- Ship gate stated in the trainer itself: **AUC above 0.75** before the score becomes a live warning.
- Remaining work: load the JSON weights inside the plugin and evaluate one dot product per tick, so the
  warning fires on the **same tick** the danger appears rather than after the hitsplat lands. Logistic
  regression was chosen deliberately so this needs no sidecar process and no runtime dependency; a real
  neural net is only justified once this baseline is beaten with more data.

### Richer activity coverage [next]

Today seven activities are recognised — two NPC-based (Combat, Fishing) and five object-keyword sets
(Mining, Woodcutting, Cooking, Smithing, Crafting). Planned additions: Firemaking,
Fletching, Herblore, Thieving, Hunter, Farming, Runecraft, Agility courses, and slayer-task awareness —
each with its own "next thing to click" target rule.

### Mascot skins and emotes [next]

Alternate Rune appearances and reactive emotes — cheering a rare drop, flinching on a big hit, sleeping
while you are idle out of combat, celebrating a level-up.

### Per-activity profit rates [next]

Split the single session P&L number into gp/hour per detected activity, so RuneAI can tell you which method
is actually paying and when a trip has gone unprofitable.

### Other Phase 2 candidates [next]

- Prayer-point and run-energy warnings from the values already recorded each tick.
- Death-avoidance escalation: teleport prompt when predicted damage exceeds remaining HP.
- Session summary written at logout.

---

## Phase 3 — Distribution (later)

### Plugin Hub submission [later]

RuneAI is **not on the RuneLite Plugin Hub yet**. It currently runs from source:

```
./gradlew run                     # launch RuneLite with RuneAI loaded (developer mode)
py train/train_damage_model.py    # train the danger model on your own recorded ticks
```

Current metadata: `runelite-plugin.properties` declares `displayName=RuneAI`, `version=0.1.0`,
`plugins=com.runeai.RuneAIPlugin`, `author=Anthony`, `build=standard`. Its `description` still reads
"RuneAI test plugin — sidebar panel + login greeting", which no longer describes the plugin and must be
rewritten before submission (the `@PluginDescriptor` in `RuneAIPlugin.java` says "RuneAI data layer — full
game state snapshots + live event stream", which is also now too narrow). The Java 11 release target comes
from `build.gradle` (`options.release.set(11)`), not from the properties file.

Before submitting we intend to: finish the danger-model integration, widen activity coverage, remove the
give the bank nudge and bond ladder their own config toggles, fix the
plugin description strings, review the data layer for anything a reviewer would flag, confirm no
automation-adjacent behaviour anywhere in the codebase.

### Documentation and media [later]

A real in-game screenshot lives in the README (docs/img/runeai-in-action.png), captured by a since-removed auto-screenshot helper.

---

## Design principles

1. **Companion, not bot.** RuneAI never clicks, never moves your character, never queues an action. If a
   feature would require sending input to the game, it is out of scope permanently.
2. **Local first.** Voice runs offline from bundled WAVs. Recordings stay in `~/.runelite/runeai/` and are
   never uploaded or committed.
3. **Silence is a feature.** Cooldowns, value filters, and per-feature toggles exist so the buddy stays
   useful instead of annoying.
4. **Ship the measurable version.** The danger model has a stated AUC gate before it is allowed to speak.

---

## FAQ

### Is RuneAI a bot?

No. RuneAI is a read-only RuneLite plugin. It reads game state and draws overlays, plays voice lines, and
writes local logs. It never sends clicks, keypresses, or any input to Old School RuneScape.

### Is RuneAI on the RuneLite Plugin Hub?

Not yet. It currently runs from source with `./gradlew run`. Plugin Hub submission is a Phase 3 item.

### Where does RuneAI store its data?

In `~/.runelite/runeai/` — `ticks-*.jsonl`, `events-*.jsonl`, `snapshot-*.json`, and, while the temporary
uploaded.

### Does the voice need an internet connection or an API key?

No. All eight voice lines are WAV files generated ahead of time with local Kokoro TTS and bundled as plugin
resources.

### How is session profit calculated?

RuneAI values your inventory and equipment at Grand Exchange prices and tracks the change over time. The
ledger pauses while bank, Grand Exchange, deposit box, or shop windows are open, so moving items around is
not counted as profit or loss.

### What is the trained danger model?

A logistic regression over recorded tick vectors that estimates the probability of taking damage within the
next three game ticks. The trainer exists and runs; the live in-plugin warning is planned, not shipped.

### How do I support development?

The RuneAI sidebar panel has a Ko-fi button: https://ko-fi.com/broketobuilt
