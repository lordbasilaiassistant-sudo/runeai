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
| `RuneAIPanel.java` | RuneLite sidebar `PluginPanel` — game state, player, activity (+ gp/xp goal), session P&L, session scoreboard, live flips, per-lane and per-item results, trap board, bond fund, NPC/player/event counts, and the Ko-fi button. |
| `GameStateSnapshot.java` | Static one-shot capture of the whole client state into a `Map` for a JSON dump. **Client thread only.** |
| `EventLog.java` | Append-only JSONL writer (`{"t":iso,"tick":n,"e":type,...}`), flushes every 50 lines. |
| `FlipService.java` | Live GE intelligence off the wiki prices API: books, tax-aware margins, trap detection, lane classification, the velocity ranker, and the anomaly watch. Suggestions only. |
| `FlipLane.java` | The QUICK / LONG taxonomy — pure classifier plus the volume-based cycle-time prior. |
| `ItemMemory.java` | Per-item bandit memory persisted to `~/.runelite/runeai/item-memory.json`, with per-lane ledgers, recovery-based cooldown expiry, and the per-item results ranking (`rank()`). |
| `TradeHistory.java` | Reads the in-game GE Trade History interface (read-only) and diffs it against our booked offers. Parse and reconciliation are pure statics; the widget walk is client-thread. |
| `SessionHistory.java` | Per-session result rows in `~/.runelite/runeai/session-history.json` plus the "beat your last session" comparison math. All ranking is `static` and argument-fed, so it tests without a client. |
| `FillTimeModel.java` | Dot-product inference for `train/flip_model.json`. Gated on that file's `verdict`. |
| `GeBrain.java` | 7-16-1 tanh forward pass for `~/.runelite/runeai/ge-brain-hist.json`. Gated on `metrics.beats_baseline`. Moves ranking, never a coached price. |
| `Champion.java` | Evolved policy genome from `~/.runelite/runeai/ge-champion.json`. Gated on `emergent_edge`; every getter falls back to the constant it replaced. |
| `TrapBoard.java` | Whale-trap history from `~/.runelite/runeai/trap-board.json` (written by `sim/whale_trap_report.py`). |
| `DangerModel.java` | P(damage within 3 ticks) from `train/damage_model.json`. Per-activity baseline always; logistic residual only when `verdict.beats_baseline`. Its one consumer is the low-HP warning threshold. |
| `StreamerService.java` | **The only code in RuneAI that talks to a machine that is not the player's.** Opt-in LLM co-host: reads the `emit()` stream, builds a compact prompt, posts it to a BYO-key OpenAI-compatible endpoint, speaks the reply through the mascot. Default OFF and inert by construction. |
| `GeFlipOverlay.java` / `GeSlotStampOverlay.java` | Draw-only GE advice: quick-lane picks, offer coach, slot stamps, reprice steps. |
| `src/test/java/com/runeai/RuneAIPluginTest.java` | Dev launcher — `ExternalPluginManager.loadBuiltin(RuneAIPlugin.class)` then `RuneLite.main(args)`. This is `./gradlew run`'s main class. |
| `train/train_damage_model.py` | Numpy logistic regression over recorded tick vectors → `train/damage_model.json` (P(damage within 3 ticks)). Read back by `DangerModel`. |
| `train/train_flip_model.py` | Ridge regression on log(seconds-to-fill) → `train/flip_model.json`, judged K-fold out-of-sample. |
| `runelite-plugin.properties` | Hub manifest (displayName, version, `plugins=com.runeai.RuneAIPlugin`). |

### Learned artifacts and their gates

Five models can influence what the plugin says. **Every one of them is gated on a
verdict the training run wrote into its own file, and every gate falls back to
the behaviour that existed before the model.** A missing file, an unparseable
one, or a losing verdict must never degrade anything — if you add a sixth, it
follows the same rule.

| Artifact | Where | Gate field | Adopted today |
| --- | --- | --- | --- |
| Fill-time model | `train/flip_model.json` (bundled via `processResources`; `~/.runelite/runeai/flip_model.json` overrides) | `verdict == "adopt"` | **No** — +6.7% log-space out-of-sample, 58% worse in seconds |
| Price brain | `~/.runelite/runeai/ge-brain-hist.json` | `metrics.beats_baseline` | Yes, marginally (MSE 0.000805 vs martingale 0.000807) |
| Champion genome | `~/.runelite/runeai/ge-champion.json` | `emergent_edge` | Yes (held-out 2,843,679 gp vs 527,095 default) |
| Trap board | `~/.runelite/runeai/trap-board.json` | file present | Yes |
| Danger model | `train/damage_model.json` (bundled via `processResources`; `~/.runelite/runeai/damage_model.json` overrides) | `verdict.beats_baseline` | **Baseline half only** — the residual is muted (0 held-out positives) |

The danger model is the one artifact whose gate is **partial**, and that is
deliberate. It has two halves with different evidential standing:

- The **per-activity baseline** is counted arithmetic — "Combat ticks were
  followed by damage 6.5x more often than your average tick." It needs no
  verdict, because it is not a claim about anything beyond what was counted.
- The **logistic residual** is a learned opinion, and `DangerModel.effectiveGain`
  forces its gain to 0 unless `verdict.beats_baseline` is true, whatever gain the
  file itself carries. A retrain on a real P2P combat corpus turns it on with no
  code change.

`train/damage_model.json` is **gitignored** — it is derived from recorded play.
`processResources` copies it into the jar when it exists, so a clean checkout
just builds without it and the feature is inert. Never commit it.

Two rules that are easy to get wrong:

- **Ranking, not pricing.** A model may reorder candidates. Every number the
  plugin reads back to the player stays anchored to the live book and its 3x cap.
- **Horizons do not transfer for free.** The brain and the champion were trained
  on 1h bars; the live loop scans every 60s. Parameters that are pure shares
  (`vol_share`, `conc`) carry over unchanged. Anything with time in it
  (`roi_floor`) must be converted pro-rata, and the brain's opinion is clamped to
  a tie-breaker.

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
- **Danger-aware urgency** — `RuneAIPlugin.updateDanger()` scores each tick through `DangerModel` and
  `DangerModel.urgencyBoost()` converts that into HP percentage points added to the EAT threshold: one
  5-point step per doubling above 2x this player's average tick, capped at 15. An elevated context also
  reads `EAT NOW`, holds the banner two ticks longer, and halves the no-food repeat to 50 ticks. Zero
  boost reproduces the old behaviour byte for byte. Gated on `dangerModel()`.
  **This is a coarse prior, not an attack predictor** — it moves a threshold and nothing else. Anything
  tick-precise (next-attack timing, "stand here", attack counters) is out of bounds; see §4.
  `RuneAIPlugin.sampleTick()` builds ONE `DangerModel.Tick` per game tick and hands it to both the
  recorder and the model, so the corpus's features and the live features cannot drift apart.
  `DangerModelTest` pins the feature arithmetic against numbers the Python trainer printed.
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
  (coins count as 1 gp each). `pnlPaused()` suppresses the ledger across transfers — `TRANSFER_GROUPS`
  is `BANKMAIN`, `GE_OFFERS`, `BANK_DEPOSITBOX`, `SHOPMAIN`, `TRADEMAIN` and `TRADECONFIRM`. The pause
  also holds for `TRANSFER_GRACE_TICKS` after the interface closes, and `transferUiOpen()` is polled
  every tick, because a player trade settles as the confirm screen tears down: without the grace window
  the inventory change lands after the widget is gone and the whole transfer books as profit (a 3.5M gift
  from an alt read as +3,514,022 earned). `GameState.LOGIN_SCREEN` clears `lastHolding` only —
  `sessionPnl` is not reset.
- **GE trade history audit** — `TradeHistory` + `RuneAIPlugin.readTradeHistoryIfDue()`, triggered by
  `WidgetLoaded` on `InterfaceID.GE_HISTORY` and read two ticks later (the interface loads empty and a
  script fills it). READ-ONLY widget reading of an interface the player opened. `bookedOffers` holds our
  side at one row per finished offer — the granularity the game reports — and only for offers watched
  from placement, so a completed offer re-firing as `BOUGHT` on login cannot invent a second trade.
  `reconcile()` is pure and accepts BOTH sell-price conventions (ask, or ask minus the 2%) because which
  one the interface quotes is not something we get to assume. Gated on `tradeAudit()`.
  **The row parse is the uncertain half**: item id and stack size come off the widget model, but
  direction and price are read out of display text whose wording is only knowable from a live client.
  `parseRow()` returns null rather than guessing, and every raw row is written to the event log as
  `geHistoryRow` the first time the interface opens each session. Tighten the parser from that log.
- **Price anomaly alert** — detected in `FlipService.anomalous()` inside the existing 60s quote scan
  (there is exactly one price poller and it must stay that way), triggered in
  `RuneAIPlugin.checkAnomalies()`. Two gates: real volume, and the move must appear on the instant-SELL
  side too — a whale overpay moves the mid without moving what anyone else pays, and that is the trap
  board's business, not an alert's. Throttled to one alert per item per 30 min; every dislocation is
  still written to the event log as `anomaly` whether or not it alerted. `RuneAIPlugin.exposure()`
  answers "does the player hold this" from inventory/equipment, live GE offers, and `flip-basis.json`.
  It never suggests a trade — an RWT pump and an update panic are indistinguishable from the tape.
  Gated on `anomalyAlert()` / `anomalyPercent()` / `anomalyHeldOnly()`.
- **Session scoreboard** — `SessionHistory`; one row per session in `session-history.json`, ranked by
  realised flip profit. gp/h is measured over ACTIVE time (first offer placed → now) with a one-minute
  floor, empty sessions are never written, and the live row is never part of its own comparison.
  Gated on `sessionScore()`.
- **Per-item results** — `ItemMemory.rank()` → `panel.setItemStats(...)`, top 6 by realised gp. An item
  never timed reports `untimed` rather than the bandit's 300s starting value. Gated on `itemStats()`.
- **Voice** — `VoicePlayer.LINES` is the source of truth for the seven keys and their text:
  `idle`, `eat`, `bank`, `loot`, `attacked`, `pot`, `bond`.
- **Streamer mode** — `StreamerService`, gated on `streamerMode()` (**default off**). `RuneAIPlugin.emit()`
  hands every event to `observe()`, which keeps seven types (`levelUp`, `geOffer`, `anomaly`, `guidance`,
  `death`, `itemSpawn`, `pnl`) and discards the rest; `beatOf()` turns one event into one sentence.
  `tick()` runs from the game tick, and does nothing but compare a clock and (at most) one non-blocking
  `enqueue`. Output goes to `VoicePlayer.say()` → the mascot's bubble, plus optional chat drained on the
  client thread via `pollChatLine()`. `emit("levelUp", …)` and `emit("guidance", …)` were added for this
  and also improve the recorded stream — a level-up used to be recoverable only by diffing `stat` lines.
  **See §5.7 before touching any of it.**

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

Voice is 100% local **for the bundled coaching lines**. There is no TTS network call for them and no
audio is uploaded anywhere.

The one exception is opt-in streamer mode, and the reason is worth understanding before anyone
"fixes" it: **the Kokoro clips are pre-rendered files, not a synthesiser.** There is no local TTS
inside the plugin, so arbitrary commentary text cannot be spoken locally today. `VoicePlayer.say()`
therefore takes bytes from an optional BYO-key service and plays them through the **same** envelope and
clip code as the bundled lines (`playPcm`), so the mascot lip-syncs identically; with no key it shows
the line in the bubble instead, which is the default. The service is asked for raw signed 16-bit mono
PCM at 24 kHz precisely because that is what the bundled WAVs decode to.

`VoicePlayer.isRawPcm()` decides whether bytes may be played, **from the declared content type**, not
from the bytes. Do not reintroduce a magic-byte sniff for mp3: `0xFF 0xFF` is both an mp3 frame sync
and the sample value -1, and real speech starts near silence — that heuristic rejected live PCM in
testing. Magic bytes may still reject a mislabelled body; they may never approve one.

### RuneLite API hygiene

- Use `net.runelite.api.gameval` constants (`ItemID`, `InterfaceID`, `ObjectID`) instead of magic numbers
  where they exist. The P&L widget-group debt is **paid** — `TRANSFER_GROUPS` is gameval `InterfaceID`
  constants now. Keep it that way; do not add a raw group id anywhere.
  Two things worth knowing when you need a new interface id: the gameval names cross-check against
  RuneLite's own legacy `net.runelite.api.widgets.WidgetID` (they agree on every group that exists in
  both), and each group has a nested component class — `InterfaceID.GeHistory`, `InterfaceID.Tradeconfirm`
  — whose component names identify the screen far better than the number does.
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
7. **Streamer mode is the only off-machine path, and it stays boxed.** It is `streamerMode`,
   default **false**, and every entry point checks it on its first line, so with it off no beat is
   collected and no request is built. The player supplies their own endpoint and their own keys; a
   blank base URL or a blank key means nothing is ever sent, whatever else is configured. What may
   leave: item names, gp amounts, skill levels, hitpoints, and a one-line session summary — a
   highlight reel of at most `MAX_BEATS` lines. What may **never** leave: the recordings, the event
   log, the tick vectors, the player's name, and their position. If you add a beat, ask what it puts
   on the wire before you ask whether it is entertaining. Rules 1 and 2 above are not relaxed here.
