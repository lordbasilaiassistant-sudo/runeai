# AGENTS.md — RuneAI contributor & agent guide

Last updated: 2026-08-09

This is the canonical guidance file for anyone (human or AI agent) working in this repo.
`CLAUDE.md` is a pointer to this file. Read this before editing code.

Repo: `https://github.com/lordbasilaiassistant-sudo/runeai`
Package: `com.runeai` · Gradle project name: `runeai` · Java release target: **11**

---

## 1. What RuneAI is (and is not)

RuneAI is a **RuneLite plugin** for **Old School RuneScape (OSRS)**: an AI buddy / coach that watches
game state and helps the player click the right thing at the right time, stay alive, and keep profits up.

- It is **not a bot**. It does not play the game, does not inject input, does not click for the player.
- Everything it does is **read game state → draw an overlay, speak a line, or update the sidebar panel**.
- The human always makes every click.

The product line to keep true in code and docs: *"a buddy, not a bot."* Any feature proposal that
performs an action on the player's behalf is out of scope and must be rejected, not implemented.

Current status: runs from source (`./gradlew run`). **Not on the RuneLite Plugin Hub.**
Support link shipped in the sidebar panel: `https://ko-fi.com/broketobuilt`.

---

## 2. Architecture map

All source lives in `src/main/java/com/runeai/`.

| File | Responsibility (one line) |
| --- | --- |
| `RuneAIPlugin.java` | The plugin entry point and **the only place triggers live** — event subscriptions, activity detection, guidance rules, P&L ledger, data recording. |
| `RuneAIConfig.java` | `@ConfigGroup("runeai")` config interface — every user-facing toggle and threshold. |
| `RuneAIOverlay.java` | Draw-only game-view overlay: NPC outlines, animated tile flashes with bobbing arrow, top-center alert banner. Exposes `flashTile()` / `setAlert()`. |
| `MascotOverlay.java` | Draw-only mascot "Rune" — a movable wisp that bobs, blinks, lip-syncs to live audio amplitude, and shows a speech bubble. |
| `VoicePlayer.java` | Loads a bundled WAV from resources, plays it on a daemon thread, computes an amplitude envelope, exposes `getMouth()` (0..1) and `getSpeakingText()` for the mascot. Per-key 12s cooldown. |
| `RuneAIPanel.java` | RuneLite sidebar `PluginPanel` — game state, player, activity (+ gp/xp goal), session P&L, bond fund, NPC/player/event counts, and the Ko-fi button. |
| `GameStateSnapshot.java` | Static one-shot capture of the whole client state into a `Map` for a JSON dump. **Client thread only.** |
| `EventLog.java` | Append-only JSONL writer (`{"t":iso,"tick":n,"e":type,...}`), flushes every 50 lines. |
| `src/test/java/com/runeai/RuneAIPluginTest.java` | Dev launcher — `ExternalPluginManager.loadBuiltin(RuneAIPlugin.class)` then `RuneLite.main(args)`. This is `./gradlew run`'s main class. |
| `train/train_damage_model.py` | Numpy logistic regression over recorded tick vectors → `train/damage_model.json` (P(damage within 3 ticks)). Not yet wired into the plugin. |
| `runelite-plugin.properties` | Hub manifest (displayName, version, `plugins=com.runeai.RuneAIPlugin`). |

### Data flow

```
RuneLite events ──> RuneAIPlugin (@Subscribe handlers)
                       ├─> EventLog        -> ~/.runelite/runeai/events-*.jsonl
                       ├─> tick vectors    -> ~/.runelite/runeai/ticks-*.jsonl
                       ├─> GameStateSnapshot -> ~/.runelite/runeai/snapshot-*.json (once per login, ~8 ticks after)
                       ├─> RuneAIOverlay.flashTile()/setAlert()   (draw)
                       ├─> VoicePlayer.play(key)                  (speak)  -> MascotOverlay lip-sync
                       └─> RuneAIPanel.setX()                     (sidebar)
```

### Feature-to-code index

- **Activity detection** — `RuneAIPlugin.currentActivity()`, derived from the most recent real XP gain
  (`onStatChanged`), stale after 50 ticks. Combat skills collapse to `"Combat"`.
- **Idle click guidance** — `updateGuidance()` + `findNearestClickable()`; NPC-based for Fishing/Combat,
  scene-scan by object-name keyword for Mining/Woodcutting/Cooking/Smithing/Crafting.
- **Low HP / EAT alert** — `updateGuidance()`, gated on `lowHpWarn()` + `lowHpPercent()`. Branches on
  `countInventoryAction("Eat")`: food present → `EAT — HP x/y` + `eat` voice; nothing edible →
  `NO FOOD — get out / bank` + `bank` voice, throttled by `lastFoodWarnTick` to every 100 ticks.
- **Potion reminder** — `checkPotions()`, inventory name-match, fires only when the boosted level is not
  already above the real level, throttled to every 100 ticks.
- **Loot flash** — `onItemSpawned()`, within 8 tiles, GE value ≥ `minLootValue()`, and **not** our own drop
  (`lastDropClickItemId` set by a `Drop` menu click within the last 3 ticks).
- **Goal inference** — `goalMode()` returns `"xp"` once `droppedValue * 2 >= gainedValue` (with
  `gainedValue > 0`), else `"gp"`. `gainedValue` accrues positive P&L deltas; `droppedValue` accrues
  self-dropped item value in `onItemSpawned()`. Written per tick as `goal`; shown after the activity in the
  panel.
- **Bank nudge** — `onItemContainerChanged()` when inventory count ≥ 28, the activity is non-combat, the
  goal is not `"xp"`, and it has been >150 ticks since our last drop.
- **Bond ladder** — `updateGuidance()` on `tick % 50 == 0`: `totalWorth()` (last-seen `bankValue` +
  inventory/equipment at GE price) vs `ItemID.OLD_SCHOOL_BOND`; `panel.setBond(...)` every check, one-shot
  alert + `bond` voice guarded by `bondAnnounced`. `bankValue` is refreshed on `BANK` container changes.
- **Session P&L** — `updatePnl()`: inventory + equipment quantity deltas priced at GE value
  (coins count as 1 gp each). `pnlPaused()` suppresses the ledger while widget groups
  12 / 465 / 192 / 300 (bank, GE, deposit box, shop) are open, because those are transfers, not profit.
  `GameState.LOGIN_SCREEN` clears `lastHolding` only — `sessionPnl` is not reset.
- **Voice** — `VoicePlayer.LINES` is the source of truth for the seven keys and their text:
  `idle`, `eat`, `bank`, `loot`, `attacked`, `pot`, `bond`.

---

## 3. Dev loop

From the repo root, `C:/Users/drlor/OneDrive/Desktop/RuneAIPlugin`:

```bash
./gradlew compileTestJava     # fast correctness check — run this after every edit
./gradlew run                 # launch RuneLite dev client with RuneAI loaded
```

`./gradlew run` executes `com.runeai.RuneAIPluginTest` with `--developer-mode --debug`.

**Kill the previous client before relaunching.** A running `RuneAIPluginTest` JVM holds the Gradle
build outputs and the plugin will not reload; `./gradlew run` will either fail to write classes or you
will be looking at stale code. On Windows:

```powershell
Get-Process java -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -like '*RuneAIPluginTest*' } |
  Stop-Process -Force
```

Or close the RuneLite window before rebuilding. Then `./gradlew run` again.

To log in to the dev client with a Jagex account, follow
`https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts`.

**Only the user can confirm in-game behavior.** Do not use screen capture or computer-use tools to
interact with RuneScape — automating game input violates Jagex's third-party client guidelines and
risks the account. After a change: state what to test, offer to launch, and wait for the user's
confirmation. A clean JVM start is not a passing test.

Training the danger model on locally recorded ticks:

```bash
py train/train_damage_model.py   # reads ~/.runelite/runeai/ticks-*.jsonl, writes train/damage_model.json
```

(`py`, not `python`, on this machine. Needs 500+ recorded ticks or it exits early.)

---

## 4. Conventions

### Client thread

- Every `net.runelite.api.Client` call must run on the client thread. `@Subscribe` handlers and
  `Overlay.render()` already are; anything else needs `clientThread.invoke()`.
- `GameStateSnapshot.capture(client)` is client-thread-only — it walks the whole 104×104 scene.
- Swing/panel updates go the other way: `RuneAIPanel` wraps every setter in `SwingUtilities.invokeLater`.
  Keep it that way when adding fields.
- Never do blocking network or disk IO on the client thread. `VoicePlayer` deliberately plays on its own
  daemon thread (`runeai-voice`) and never touches `Client`.

### Overlays are draw-only

`RuneAIOverlay` and `MascotOverlay` render what they are told and nothing more. They must not decide
*when* something should be highlighted or spoken.

**All triggers live in `RuneAIPlugin`.** A new coaching behavior is: detect the condition in a
`@Subscribe` handler or in `updateGuidance()`, then call `overlay.flashTile(...)`, `overlay.setAlert(...)`,
`voice.play(key)`, or `panel.setX(...)`. If you find yourself adding game logic inside a `render()`
method, move it. `render()` runs every frame — keep the work in there minimal.

### Config

- Group name is `runeai`. **Never rename an existing `keyName` or the group** without a migration —
  renaming silently resets users' saved settings.
- Every new coaching feature ships with a `@ConfigItem` toggle, defaulted to whatever is sane, and the
  code path checks it before doing anything.

### Voice lines

Clips are bundled resources: `src/main/resources/com/runeai/voice/<key>.wav`, loaded by
`VoicePlayer.speak()` at `/com/runeai/voice/<key>.wav`. Current keys: `idle`, `eat`, `bank`, `loot`,
`attacked`, `pot`, `bond`. All seven shipped files are mono 16-bit PCM at 24 kHz.

To add or change a line:

1. Add/edit the entry in `VoicePlayer.LINES` (the map is both the caption shown in the mascot's speech
   bubble and the text to synthesize).
2. Regenerate the WAV locally with **Kokoro TTS via `kokoro-js`, voice `af_heart`**, written as
   **16-bit PCM WAV** (mono). The envelope code in `VoicePlayer.speak()` reads signed 16-bit
   little-endian frames — any other sample format breaks lip-sync.
3. Save it as `src/main/resources/com/runeai/voice/<key>.wav` with the filename exactly matching the key
   passed to `voice.play(key)`.
4. `./gradlew compileTestJava && ./gradlew run` and listen to it in the dev client.

Voice is 100% local. There is no TTS network call at runtime and no audio is uploaded anywhere.

### RuneLite API hygiene

- Use `net.runelite.api.gameval` constants (`ItemID`, `InterfaceID`, `ObjectID`) instead of magic numbers
  where they exist. The hardcoded P&L widget groups in `pnlPaused()` are a known debt — replace them with
  gameval `InterfaceID` constants rather than adding more raw ints.
- `LinkBrowser.browse(...)` for URLs, never `java.awt.Desktop` (see the Ko-fi button).
- `@Inject Gson` / `@Inject OkHttpClient` — never construct your own; do not add RuneLite's transitive
  deps to `build.gradle`.
- No reflection, no JNI/JNA, no `ProcessBuilder`, no dynamic classloading, no Java serialization —
  all are forbidden in hub plugins and all are avoidable here.
- File IO stays inside `RuneLite.RUNELITE_DIR`; RuneAI uses `~/.runelite/runeai/` exclusively.
- `log.debug()` for per-tick/per-event diagnostics. `log.info()` only for startup/shutdown or rare events.
- Clean up in `shutDown()`: remove the nav button, remove both overlays, close both `EventLog`s.
  Anything you add in `startUp()` must be undone there.

### Jagex third-party client limits worth remembering

Even though RuneAI is not on the hub yet, keep it hub-eligible: no next-attack prediction, no prayer
switch indicators, no projectile landing indicators, no attack counters, no "stand here" boss
indicators, no input injection, no autotyping, no menu entries that send actions to the server, and no
crowdsourcing of other players' data.

---

## 5. Hard rules

1. **Never commit recorded game data.** `~/.runelite/runeai/*.jsonl`, `snapshot-*.json`, and
   `train/damage_model.json` are local-only — they contain account names and play history. `.gitignore`
   already covers `*.jsonl`, `snapshot-*.json`, and `train/damage_model.json`; do not weaken it, and do
   not copy recordings into the repo tree "just for a test."
2. **Recordings stay on the player's machine.** No upload endpoint, no telemetry, no third-party server.
   Any config item that would send data off-machine must be opt-in, off by default, and carry the
   RuneLite third-party-server warning string.
3. **Never auto-submit to the RuneLite Plugin Hub.** No PR to `runelite/plugin-hub`, no release tag
   intended as a hub submission, without Anthony explicitly asking for it in that session.
4. **Never make RuneAI act for the player.** No input injection, no automated clicking, no menu actions
   sent to the server. Buddy, not bot.
5. **Do not commit build artifacts** (`build/`, `.class` files) and do not add a
   `META-INF/services/net.runelite.client.plugins.Plugin` file.
6. **Do not claim a feature works from the code alone.** Compiling is not testing; a launched JVM is not
   a passing test. Only an in-game confirmation counts.
