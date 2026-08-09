# Getting Started with RuneAI

**Last updated: 2026-08-09**

RuneAI is an AI buddy and coach for **Old School RuneScape**, built as a **RuneLite plugin**. It watches your game state and helps you click the right thing at the right time, stay alive, and keep your profits up. **RuneAI does not play the game for you — it is not a bot and it never sends inputs to the client.** Every click is still yours.

This guide covers both audiences:

- **OSRS players** who have never touched a compiler — follow *Quick start* top to bottom.
- **Developers** — same steps, plus the *Developer notes* section at the end.

RuneAI is **not on the RuneLite Plugin Hub yet**. Today you run it from source with `./gradlew run`, which launches RuneLite with the plugin already loaded.

- Repo: <https://github.com/lordbasilaiassistant-sudo/runeai>
- Support the project: <https://ko-fi.com/broketobuilt> (there is a Ko-fi button in the RuneAI sidebar panel)

---

## What RuneAI actually does

| Feature | What you see |
| --- | --- |
| Combat awareness overlay | NPCs attacking you get a red model outline and a name label; your current target gets a cyan outline. |
| Low HP alert | Under attack below your HP threshold: a pulsing top-center `EAT — HP x/y` banner plus a voice callout when you still have food, or `NO FOOD — get out / bank` when nothing in your inventory has an `Eat` action. |
| Idle click guidance | If you stall mid-task, RuneAI flashes the tile of the next thing to click (fishing spot, rocks, tree, range/fire/stove, anvil/furnace, furnace/spinning wheel/loom, or your last combat target) with a bobbing arrow and a `Click: <name>` label. |
| Potion reminder | Fighting unpotted with a boost potion in your inventory triggers a `Pot up — <potion>` alert. |
| Loot calls | A nearby drop worth at least your minimum GE value flashes its tile with `name · value gp`. Items you dropped yourself are never flashed. |
| Bank nudge | Gathering with a full 28-slot inventory triggers `Inventory full — bank it` — unless RuneAI has classed the session as an xp grind. |
| gp vs xp goal | RuneAI works out whether you are banking loot or dropping it, shows it next to the activity (`Mining · gp`), and stops nagging power-trainers to bank. |
| F2P bond ladder | On a free world, a **Bond fund** row tracks your total worth against the live GE bond price and calls it out when you can afford a bond. |
| Voice callouts | Local **Kokoro TTS** WAV files bundled inside the plugin. No internet, no API key, no cloud TTS. |
| Rune the mascot | An animated companion that bobs, blinks, and **lip-syncs to the live audio amplitude** of whatever line is playing, with a speech bubble of the text. |
| Session P&L | A live gp ledger in the sidebar from inventory + equipment GE-value deltas. |
| Data layer | Per-tick state vectors, a full event stream, and a login snapshot written to your own machine. |
| Auto-screenshots (temporary) | On by default: saves up to 8 PNGs per session to `~/.runelite/runeai/shots/` when a useful moment fires, for the README. Toggle it off with **Auto-screenshot useful moments**. |

### Voice lines that exist today

Eight bundled clips (mono 16-bit PCM, 24 kHz), each with a 12-second per-line cooldown:

| Key | Spoken line |
| --- | --- |
| `idle` | "You're idle. Click the highlighted tile." |
| `eat` | "Low health. Eat now." |
| `bank` | "Inventory full. Bank your items." |
| `loot` | "Good drop. Grab it." |
| `attacked` | "You're under attack." |
| `pot` | "Pot up. Drink your potion." |
| `shot` | "Got the shot. Close RuneLite when you are ready." |
| `bond` | "You can afford a bond. Time to go members." |

---

## Prerequisites

You need two things installed before anything else.

1. **JDK 11 or newer.** The project compiles with `options.release.set(11)`, so Java 11 is the floor. Any modern JDK (11, 17, 21) works. Check with:
   ```
   java -version
   ```
2. **Git**, to clone the repo. Check with:
   ```
   git --version
   ```

You do **not** need to install Gradle — the repo ships the Gradle wrapper (`gradlew` / `gradlew.bat`), which downloads the right Gradle version for you on first run.

You also do not need RuneLite installed separately. `./gradlew run` pulls the RuneLite client as a dependency and starts it.

---

## Quick start (players and developers)

### 1. Clone the repo

```
git clone https://github.com/lordbasilaiassistant-sudo/runeai.git
cd runeai
```

### 2. Run it

macOS / Linux / Git Bash:

```
./gradlew run
```

Windows PowerShell or Command Prompt:

```
.\gradlew.bat run
```

The first run downloads Gradle and the RuneLite client, so it can take several minutes. Later runs are fast. When it finishes, the RuneLite client window opens with RuneAI already loaded (the `run` task launches RuneLite in `--developer-mode --debug`).

### 3. Log in

Log into Old School RuneScape in that RuneLite window exactly as you normally would. Once you are in-game, RuneAI posts a chat line:

```
RuneAI online. Welcome back, <your name>!
```

That greeting is your confirmation the plugin is live. About 8 ticks later, RuneAI writes a full game-state snapshot to disk. The greeting and the snapshot happen once per login, so a world hop or a relog produces another pair.

### 4. Find the sidebar panel

Look at the **right-hand icon bar** of the RuneLite window — the same column that holds the wrench and the other plugin icons. RuneAI adds a blue/purple rounded icon with a white "A" mark, tooltip **RuneAI**, near the top (it registers with priority 1). Click it to open the panel.

The panel shows:

- **Game state** — e.g. `LOGGED_IN`
- **Player** — your display name
- **Activity** — what RuneAI thinks you are doing, derived from recent XP drops (Combat, Fishing, Mining, Woodcutting, Cooking, Smithing, Crafting, …), suffixed with the session goal (`Mining · gp`), or `—` if it cannot tell
- **Session P&L** — your gp ledger for this session
- **Bond fund** — on a free world, your total worth as a percentage of the live GE bond price (`members ✓` on a members world; a trailing `*` means your bank has not been opened yet, so only carried items are counted)
- **NPCs loaded**, **Players loaded**, **Events logged**
- A **♥ Support us on Ko-fi** button that opens <https://ko-fi.com/broketobuilt>

### 5. Move the mascot (Alt-drag)

Rune spawns in the **bottom-right** of the game view. To move it:

1. Hold **Alt**.
2. Left-click and drag Rune anywhere on the client.
3. Release. RuneLite remembers the position.

This is standard RuneLite overlay dragging — the mascot overlay is registered as movable. If Alt-drag does nothing, open the RuneLite **Configuration** (wrench) panel and confirm overlay dragging is not disabled in your client settings.

---

## Tweaking the config

Open the **wrench icon** in the RuneLite sidebar (Configuration), then find **RuneAI** in the plugin list (type "RuneAI" in the search box). Every setting below lives in the `runeai` config group.

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Login greeting | `greeting` | `Welcome back` | The message RuneAI sends in chat when you log in. |
| Log live events | `logEvents` | on | Stream all game events to `events-*.jsonl`. **Restart the plugin to apply.** |
| Log varbit changes | `logVarbits` | on | Include raw varbit/varp changes in the event stream (very chatty on login). |
| Auto-screenshot useful moments | `screenshotMode` | on | **Temporary.** Saves up to 8 PNGs per session to `~/.runelite/runeai/shots/` ~0.7s after guidance/alerts fire, one every 15s at most, each followed by a chat line and the `shot` voice callout. |
| Track session profit/loss | `trackPnl` | on | The live gp ledger. |
| Show mascot | `showMascot` | on | Show or hide Rune. |
| Voice callouts | `voiceCallouts` | on | Spoken guidance. Turn off for total silence. |
| Min loot value (gp) | `minLootValue` | `100` | Only flash and call out drops worth at least this much. |
| Show overlays | `showOverlays` | on | Combat outlines, tile flashes, and the alert banner. |
| Idle click guidance | `guideIdle` | on | Flash the next tile to click when you go idle mid-task. |
| Potion reminder | `potReminder` | on | Remind you to drink a boost potion when fighting unpotted. |
| Low HP alert | `lowHpWarn` | on | The `EAT` warning. |
| Low HP threshold % | `lowHpPercent` | `33` | HP percent that triggers the EAT warning. |
| Record tick vectors | `recordTicks` | on | Write one fixed-shape state record per game tick to `ticks-*.jsonl`. **Restart the plugin to apply.** |
| Heartbeat interval (ticks) | `heartbeatTicks` | `10` | Write a compact player-state line to the event stream every N ticks. |

### Turning things on and off quickly

- **Silence the voice:** uncheck **Voice callouts**. Takes effect immediately — no restart.
- **Hide Rune:** uncheck **Show mascot**. Immediate.
- **Hide all in-world drawing:** uncheck **Show overlays**. This kills combat outlines, tile flashes, and the alert banner, but leaves the sidebar panel and voice alone.
- **Stop the P&L ledger:** uncheck **Track session profit/loss**.
- **Stop the screenshots:** uncheck **Auto-screenshot useful moments**. Immediate.
- **Stop writing files:** uncheck **Log live events** and **Record tick vectors**, then toggle the RuneAI plugin off and back on (those two are only read when the plugin starts up).

### How Session P&L works

RuneAI values every item in your **inventory plus equipment** at its GE price (coins count as 1 gp each) and tracks the delta. Loot kept and pickups count up; drops and consumed supplies count down.

**The ledger pauses while a bank, Grand Exchange, deposit box, or shop interface is open**, because those are transfers, not gains or losses. Withdrawing 10M from the bank does not show up as 10M profit.

Logging out only clears the *baseline* RuneAI compares against, so logging back in does not book your whole kit as profit. The running total keeps counting until you disable the plugin — it is a plugin-session number, not a login-session number.

---

## Where your data files land

Everything RuneAI writes goes to the `runeai` folder inside your RuneLite directory:

| Platform | Path |
| --- | --- |
| Windows | `C:\Users\<you>\.runelite\runeai\` |
| macOS / Linux | `~/.runelite/runeai/` |

Three kinds of file, all timestamped `yyyyMMdd-HHmmss`, plus a `shots/` sub-folder of PNGs while screenshot mode is on:

| File | Contents |
| --- | --- |
| `events-<stamp>.jsonl` | The live event stream. One JSON object per line: `{"t": iso-time, "tick": n, "e": type, ...}`. Types include `gameState`, `chat`, `stat`, `hitsplat`, `death`, `animation`, `graphic`, `graphicsObject`, `projectile`, `interacting`, `container`, `click`, `npcSpawn`/`npcDespawn`, `playerSpawn`/`playerDespawn`, `itemSpawn`/`itemDespawn`, `varbit`, `heartbeat`, `pnl`. |
| `ticks-<stamp>.jsonl` | One fixed-shape vector per game tick: position, region, hp/hpMax, prayer, run energy, spec, animation, pose, graphic, damage taken, session P&L, detected activity, goal mode (`gp`/`xp`), session XP gained, members-world flag, total GE worth, worn item ids, current target NPC id, and the nearest 8 NPCs with distance/animation/health-ratio/"is it attacking me". |
| `snapshot-<stamp>.json` | A pretty-printed full game-state dump written ~8 ticks after each login (one per login, not one per session). |
| `shots/shot-<trigger>-<stamp>.png` | Screenshots from **Auto-screenshot useful moments**, max 8 per session. |

**These files are local only. They are never uploaded anywhere, and they are never committed to the repo.** They are your data, on your disk. Delete the folder any time — RuneAI recreates it on next start.

The tick vectors exist so a danger model can be trained on real play. `train/train_damage_model.py` reads `~/.runelite/runeai/ticks-*.jsonl` and fits a numpy logistic regression for "will I take damage within the next 3 ticks", writing `train/damage_model.json`. Run it with `py train/train_damage_model.py` (needs numpy). It refuses to train on fewer than 500 recorded ticks.

---

## Troubleshooting

### `./gradlew run` fails

- **"java: command not found" or an unsupported class-version error.** You are missing a JDK or running one older than 11. Install JDK 11 or newer and re-run. Confirm with `java -version`.
- **`Permission denied` running `./gradlew` on macOS or Linux.** Run `chmod +x gradlew` once, then try again.
- **On Windows, `./gradlew` is not recognized.** Use `.\gradlew.bat run` in PowerShell/CMD, or use Git Bash where `./gradlew` works.
- **Dependency resolution failures on first run.** The build pulls from `repo.runelite.net` and Maven Central; a corporate proxy, VPN, or offline machine will break this. Retry on a normal connection.
- **The build succeeded before and now fails oddly.** Delete the `build/` folder and run again.
- **RuneLite version drift.** `build.gradle` pins `runeLiteVersion = 'latest.release'`, so a fresh RuneLite release can change the API under you. If compilation breaks against a new client release, that is the usual cause — `git pull` for a fix, or pin a known-good version locally.

### No sound

1. Check **Voice callouts** is enabled in the RuneAI config.
2. Remember the **12-second per-line cooldown** — the same callout will not repeat inside 12 seconds. It can feel like silence when you are re-triggering one condition.
3. Voice only fires when a trigger actually happens. Standing safely in a bank produces no callouts by design.
4. Check your OS mixer and your default audio output device. RuneAI plays through the Java Sound API (`javax.sound.sampled`), which uses the system default output — if you switched headsets after launching, restart the client.
5. Look at the RuneLite logs for `voice clip missing:` or `voice playback failed for` — those are logged as warnings when the WAV resource cannot be found or the audio line cannot open.

### Overlay not visible

1. Check **Show overlays** is enabled for in-world drawing, and **Show mascot** for Rune.
2. You must be logged in. Overlays draw nothing when there is no local player.
3. Rune defaults to the **bottom-right** of the game view and can be dragged off-screen by accident. Alt-drag it back, or toggle **Show mascot** off and on.
4. Combat outlines only appear for NPCs interacting with you or being attacked by you — they are not "highlight everything" markers.
5. Tile flashes are short-lived (roughly 6 to 12 ticks) and only trigger on their conditions: idle guidance needs a detected activity plus about 4 idle ticks, loot flashes need a drop within 8 tiles worth at least **Min loot value**.
6. If overlays are on but nothing ever appears while you clearly qualify, check the RuneLite log output in the terminal you launched from — stack traces from overlay rendering land there.

### The sidebar panel is missing

The RuneAI icon is added when the plugin starts. If it is gone, the plugin was disabled — open the wrench/Configuration panel, find RuneAI, and re-enable it. Toggling the plugin off and on is also how you apply `logEvents` and `recordTicks` changes.

### No data files appear

`logEvents` and `recordTicks` are only read at plugin startup. Change them, then toggle the plugin off and on. On start, RuneLite's log prints the exact paths (`RuneAI event stream -> …`, `RuneAI tick vectors -> …`).

---

## Developer notes

- **Source layout:** `src/main/java/com/runeai/` — `RuneAIPlugin` (event wiring, guidance rules, P&L), `RuneAIConfig` (config interface, group `runeai`), `RuneAIOverlay` (in-world drawing: outlines, tile markers, alert banner), `MascotOverlay` (Rune), `VoicePlayer` (WAV playback + amplitude envelope), `RuneAIPanel` (sidebar `PluginPanel`), `GameStateSnapshot` (full-state capture, client thread only), `EventLog` (append-only JSONL writer, flushes every 50 lines).
- **Voice resources:** `src/main/resources/com/runeai/voice/{idle,eat,bank,loot,attacked,pot,shot,bond}.wav`, loaded by key at `/com/runeai/voice/<key>.wav`. To add a line, drop in a WAV, add the key to `VoicePlayer.LINES`, and call `voice.play("<key>")`.
- **Launcher:** `src/test/java/com/runeai/RuneAIPluginTest.java` calls `ExternalPluginManager.loadBuiltin(RuneAIPlugin.class)` then `RuneLite.main(args)`. The Gradle `run` task uses it as `mainClass` with `--developer-mode --debug`.
- **Build tasks:** `./gradlew run` (launch), `./gradlew build` (compile), `./gradlew shadowJar` (fat jar — `build/libs/runeai-unspecified-all.jar`, since `build.gradle` sets no project `version`).
- **Plugin metadata:** `runelite-plugin.properties` declares `displayName=RuneAI`, `version=0.1.0`, `plugins=com.runeai.RuneAIPlugin`.
- **Lip-sync mechanic:** `VoicePlayer` decodes the WAV to PCM, computes an RMS amplitude envelope at roughly 30 hops per second normalized to the clip peak, then walks the envelope using the clip's real `getFramePosition()` while it plays. `MascotOverlay` reads `getMouth()` (0..1) each frame to size the mouth and add bounce. The mouth is driven by actual playback, not a timer.
- **Activity detection** comes from XP drops only (`onStatChanged`, real gains not boosts) and expires after 50 ticks without XP — cheap and exact, no heuristics on animations.
- **Goal inference (`goalMode()`):** the session is `"xp"` when dropped GE value is at least half of gained GE value, otherwise `"gp"`. Drops are attributed by watching for a `Drop` menu click and matching the resulting `ItemSpawned` on the same item id within 3 ticks — that spawn is also excluded from loot flashes.
- **Bond ladder:** evaluated in `updateGuidance()` on ticks where `tick % 50 == 0`. `totalWorth()` = last-seen bank value (captured whenever the `BANK` container changes) plus the current inventory+equipment holding at GE prices; the alert fires once per plugin session and only on a non-members world with a known bank value.

---

## FAQ

**Is RuneAI a bot?**
No. RuneAI reads game state and draws overlays, plays audio, and updates a panel. It never clicks, moves, or sends any input to the game. You do all the playing.

**Is it allowed?**
RuneAI is a RuneLite plugin in the same category as other information and overlay plugins — it displays what the client already knows. It automates no gameplay input. You are responsible for your own account and for following Jagex's rules.

**Is RuneAI on the RuneLite Plugin Hub?**
Not yet. Today you run it from source with `./gradlew run`.

**Does RuneAI send my data anywhere?**
No. The event stream, tick vectors, and login snapshot are written to `~/.runelite/runeai/` on your own machine and stay there. Nothing is uploaded and nothing is committed to the repo.

**Does the voice need an internet connection or an API key?**
No. The voice lines are Kokoro TTS WAV files bundled as resources inside the plugin and played locally.

**Why is a callout not repeating?**
Each voice line has a 12-second cooldown so RuneAI does not nag.

**Why did my P&L not move when I withdrew from the bank?**
By design. The ledger pauses while a bank, Grand Exchange, deposit box, or shop window is open, because those are transfers rather than profit or loss.

**How do I move the mascot?**
Hold **Alt** and drag Rune anywhere on the client.

**Where do I report a bug or ask for a feature?**
Open an issue at <https://github.com/lordbasilaiassistant-sudo/runeai>.

**How do I support development?**
<https://ko-fi.com/broketobuilt>, or the **♥ Support us on Ko-fi** button in the RuneAI sidebar panel.

---

*Screenshots coming.*
