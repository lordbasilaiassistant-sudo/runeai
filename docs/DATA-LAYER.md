# RuneAI Data Layer Reference

Last updated: 2026-08-09

RuneAI is an AI buddy and coach for Old School RuneScape (OSRS) that runs as a RuneLite plugin. It does **not** play the game for you and it is not a bot — it watches game state and helps the player click the right thing at the right time, stay alive, and keep profits up. This document is the precise reference for the data the plugin records while it does that.

Everything described here is written to **`~/.runelite/runeai/`** on your own machine. Nothing is uploaded anywhere. See [Privacy guarantees](#privacy-guarantees).

Source of truth for this document:
- `src/main/java/com/runeai/RuneAIPlugin.java`
- `src/main/java/com/runeai/GameStateSnapshot.java`
- `src/main/java/com/runeai/EventLog.java`
- `src/main/java/com/runeai/RuneAIConfig.java`
- `train/train_damage_model.py`

## Overview: what gets written

| File | Written by | Cadence | Purpose |
| --- | --- | --- | --- |
| `ticks-<yyyyMMdd-HHmmss>.jsonl` | `RuneAIPlugin.recordTickVector()` | one line per game tick | fixed-shape training corpus for the danger model |
| `events-<yyyyMMdd-HHmmss>.jsonl` | `RuneAIPlugin.emit()` | one line per game event | variable-shape event stream (chat, hitsplats, clicks, spawns…) |
| `snapshot-<yyyyMMdd-HHmmss>.json` | `GameStateSnapshot.capture()` | once per login, ~8 ticks after | one full dump of everything the RuneLite API exposes |

The data directory is `new File(RuneLite.RUNELITE_DIR, "runeai")` — on Windows that resolves to `C:\Users\<you>\.runelite\runeai\`. Every filename is stamped with `DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")`: the two JSONL names are stamped once in `startUp()`, so each plugin session gets its own pair, while snapshot names are stamped at write time.

### Shared JSONL envelope

Both `.jsonl` files are written by `EventLog`, which prefixes every record with the same three fields before merging in the payload:

```json
{"t": "2026-08-09T18:04:11.302Z", "tick": 41827, "e": "hitsplat", "...": "payload fields"}
```

- `t` — ISO-8601 instant (`Instant.now().toString()`) at write time
- `tick` — `client.getTickCount()` when the record was logged
- `e` — record type (`"tick"` for the tick file; the event type for the event file)

The writer is append-only, UTF-8, and flushes every 50 lines. Killing the client mid-session can therefore lose up to 49 trailing lines.

### Recording switches

| Config key | Default | Effect |
| --- | --- | --- |
| `recordTicks` | `true` | writes `ticks-*.jsonl` (restart the plugin to apply) |
| `logEvents` | `true` | writes `events-*.jsonl` (restart the plugin to apply) |
| `logVarbits` | `true` | includes `varbit` events in the event stream (very chatty on login) |
| `heartbeatTicks` | `10` | interval for the `heartbeat` event |
| `trackPnl` | `true` | enables the session profit/loss ledger that feeds the `pnl` field, the `pnl` event, and (through `gainedValue`) the `goal` field |

Both log files are opened once in `startUp()`. Toggling `recordTicks` or `logEvents` mid-session does nothing until the plugin restarts.

## `ticks-*.jsonl` — the tick vector schema

`recordTickVector(Player lp)` runs from `onGameTick` for every game tick while the local player exists and `recordTicks` is enabled. It writes one line with `"e": "tick"` and the following fields, in this order.

### Player position and state

| Field | Type | Source | Meaning |
| --- | --- | --- | --- |
| `x` | int | `WorldPoint.getX()` | world X of the local player |
| `y` | int | `WorldPoint.getY()` | world Y |
| `plane` | int | `WorldPoint.getPlane()` | floor level (0 = ground) |
| `region` | int | `WorldPoint.getRegionID()` | region id, the cheapest "where am I" key |
| `hp` | int | `getBoostedSkillLevel(HITPOINTS)` | current hitpoints |
| `hpMax` | int | `getRealSkillLevel(HITPOINTS)` | hitpoints level |
| `pray` | int | `getBoostedSkillLevel(PRAYER)` | current prayer points |
| `energy` | double | `client.getEnergy() / 100.0` | run energy as a percentage |
| `spec` | int | `getVarpValue(SPECIAL_ATTACK_PERCENT) / 10` | special attack energy, 0–100 |
| `anim` | int | `Player.getAnimation()` | current animation id, `-1` when not animating |
| `pose` | int | `Player.getPoseAnimation()` | idle/walk/run pose animation id |
| `graphic` | int | `Player.getGraphic()` | current spot-anim id, `-1` when none |
| `dmgTaken` | int | accumulated hitsplats | see [label semantics](#dmgtaken-label-semantics) |
| `pnl` | long | `sessionPnl` | see [pnl semantics](#pnl-field-semantics) |
| `activity` | string / null | `currentActivity()` | `"Combat"`, or the `Skill.getName()` of the most recent XP gain; `null` when no XP has landed in the last 50 ticks |
| `goal` | string | `goalMode()` | `"xp"` or `"gp"` — see [goal semantics](#goal-field-semantics) |
| `xpGained` | long | `xpGainedSession` | total real XP gained since the plugin started, summed across all skills |
| `members` | bool | `client.getWorldType().contains(MEMBERS)` | whether the current world is a members world |
| `worth` | long | `totalWorth()` | GE value of inventory + equipment plus the bank as of the last time it was opened (`0` contribution until then) |
| `equip` | int[] | `InventoryID.EQUIPMENT` | item ids of every worn item, empty-slot entries removed; unordered relative to slot index, so treat it as a set, not a slot map |
| `targetNpcId` | int | `Player.getInteracting()` | NPC id the player is interacting with, or `-1` if none / not an NPC |

### Local threat picture

| Field | Type | Meaning |
| --- | --- | --- |
| `npcCount` | int | number of named NPCs in the loaded scene (nulls and unnamed NPCs removed) |
| `npcs` | array | the **nearest 8** of those NPCs, sorted ascending by `WorldPoint.distanceTo` |

Each entry of `npcs` is:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | int | NPC id |
| `dist` | int | tile distance from the local player |
| `anim` | int | NPC animation id, `-1` when idle — a non-`-1` value is the attack-windup signal |
| `hr` | int | `getHealthRatio()`, RuneLite's raw health bar ratio (`-1` when no bar is shown) |
| `atkMe` | bool | `true` when that NPC's interaction target is the local player |

Example line (abridged):

```json
{"t":"2026-08-09T18:04:11.302Z","tick":41827,"e":"tick","x":3222,"y":3218,"plane":0,
 "region":12850,"hp":31,"hpMax":45,"pray":12,"energy":88.0,"spec":100,"anim":422,
 "pose":808,"graphic":-1,"dmgTaken":4,"pnl":16340,"activity":"Combat","goal":"gp","xpGained":41250,
 "members":false,"worth":2841900,"equip":[1155,1115,1323,1061],"targetNpcId":3105,
 "npcCount":6,"npcs":[{"id":3105,"dist":1,"anim":5528,"hr":18,"atkMe":true}]}
```

### `dmgTaken` label semantics

`dmgTaken` is the plugin's built-in supervision label — it exists so the tick file is self-labelling and needs no annotation pass.

- `onHitsplatApplied` adds `event.getHitsplat().getAmount()` to `damageTakenThisTick` whenever the hitsplat's actor is the local player. Every hitsplat counts, including 0-damage splats (a 0 adds nothing) and damage from any source.
- `onGameTick` writes the tick vector **first**, then runs guidance, then resets `damageTakenThisTick = 0`.
- So `dmgTaken` on a line is the total damage the local player took in the window since the previous tick's reset. `0` means an unharmed tick.

The field is raw damage, not a class label. The classification target used for training is derived from it — see the trainer section.

### `pnl` field semantics

`pnl` carries the running session profit/loss in gp at the moment the tick was recorded, so any tick window can be scored for "was this profitable?" without replaying the event stream.

The ledger lives in `updatePnl()` and works like this:

1. On every `ItemContainerChanged` for `INVENTORY` or `EQUIPMENT`, the plugin builds a `Map<itemId, quantity>` over **inventory + equipment combined**.
2. It diffs that holding against the previous holding, valuing each id delta with `itemManager.getItemPrice(id)` — with coins (`ItemID.COINS_995`) hard-valued at 1 gp each.
3. The summed delta is added to `sessionPnl`, pushed to the sidebar panel, and emitted as a `pnl` event.
4. **Ledger pause:** `pnlPaused()` returns `true` when any of widget groups `12`, `465`, `192`, `300` (bank, Grand Exchange, deposit box, shop) has a visible root widget. While paused the diff is skipped and only the baseline holding is refreshed — banking and GE trades are transfers, not gains or losses.
5. The baseline is cleared (`lastHolding = null`) on `GameState.LOGIN_SCREEN`, so the first container change after login re-baselines rather than counting your whole kit as profit.

Consequences worth knowing when you train on this field: consumed supplies count **down**, picked-up loot counts **up**, and `pnl` is cumulative per plugin session, never per hour and never reset by activity changes. Note that step 5 clears only the *baseline*: `sessionPnl` itself survives logouts and world hops and is only zeroed when the plugin is restarted.

### `goal` field semantics

`goalMode()` classifies the session as a **gp grind** or an **xp grind** from what you do with the value you gain:

- `gainedValue` accumulates every **positive** P&L delta (see above), so it only moves while the ledger is running and unpaused.
- `droppedValue` accumulates the GE value of items **you** dropped. A drop is identified by a `Drop` menu click (`onMenuOptionClicked`) followed by an `ItemSpawned` for the same item id within 3 ticks and within 8 tiles. Those spawns are excluded from loot flashes.
- The result is `"xp"` when `gainedValue > 0 && droppedValue * 2 >= gainedValue`, i.e. once you have dropped at least half of what you gained. Otherwise `"gp"` — including at the very start of a session, before anything has been gained.

Downstream this field is not just a label: the full-inventory bank nudge is suppressed while the session is `"xp"`, and the sidebar prints it after the activity (`Mining · gp`).

### `worth`, `members` and the bond ladder

`worth` is `totalWorth()`: `max(0, bankValue)` plus every item in the last recorded inventory+equipment holding at `itemValue()` prices. `bankValue` starts at `-1` (unknown) and is recomputed each time the `BANK` item container changes — so `worth` counts carried items only until the player opens a bank once, then counts everything.

`members` comes straight from `client.getWorldType()`. The pair drives the F2P bond ladder in `updateGuidance()`, evaluated on ticks where `tick % 50 == 0`: it prices `ItemID.OLD_SCHOOL_BOND` from the GE, pushes `panel.setBond(...)`, and — on a non-members world, with a known bank value and `worth >= bondPrice` — fires the `You can afford a BOND — go members!` alert and the `bond` voice line exactly once per plugin session.

## `events-*.jsonl` — event stream types

Every record here is written by `emit(String type, Map data)`, which stamps the shared envelope and sets `"e"` to the type below. All events are skipped entirely when `logEvents` is `false`.

Several event payloads embed an **actor object** produced by `actorInfo(Actor)`:

```json
{"name":"Guard","type":"npc","id":3105,"pos":"3222,3218,0"}
```

- `name` — actor name (may be `null`)
- `type` — `"npc"` or `"player"`; NPCs also carry `id`, players carry `local` (`true` for you)
- `pos` — `"x,y,plane"` string, omitted when the actor has no world location
- the whole object is `null` when the actor is `null`

### Event type catalogue

| `e` | Emitted from | Payload fields |
| --- | --- | --- |
| `gameState` | `onGameStateChanged` | `state` (RuneLite `GameState` name) |
| `heartbeat` | `onGameTick`, every `heartbeatTicks` ticks | `pos` (`"x,y,plane"`), `region`, `anim`, `hpRatio`, `energy`, `npcs` (count), `players` (count), `interacting` (name or `null`) |
| `chat` | `onChatMessage` | `type` (`ChatMessageType` name), `sender`, `msg` |
| `stat` | `onStatChanged` | `skill`, `level`, `boosted`, `xp` |
| `hitsplat` | `onHitsplatApplied` | `target` (actor), `amount`, `hitType` (hitsplat type), `mine` (bool) |
| `death` | `onActorDeath` | `actor` (actor) |
| `animation` | `onAnimationChanged` | `actor` (actor), `anim` — only emitted when the new animation is not `-1` |
| `graphic` | `onGraphicChanged` | `actor` (actor), `graphic` — only emitted when the new graphic is not `-1` |
| `graphicsObject` | `onGraphicsObjectCreated` | `id`, `pos` — standalone tile spot-anims, i.e. AoE telegraphs and "fire on the floor" |
| `projectile` | `onProjectileMoved` | `id`, `target` (actor) — logged once per projectile, on first sighting only |
| `interacting` | `onInteractingChanged` | `source` (actor), `target` (actor) |
| `pnl` | `updatePnl()` | `delta` (gp change), `total` (running `sessionPnl`) — only when the delta is non-zero and the ledger is not paused |
| `container` | `onItemContainerChanged` | `containerId`, `itemCount` |
| `click` | `onMenuOptionClicked` | `option`, `target`, `action` (`MenuAction` name or `null`), `id` |
| `npcSpawn` | `onNpcSpawned` | `npc` (actor) |
| `npcDespawn` | `onNpcDespawned` | `npc` (actor) |
| `playerSpawn` | `onPlayerSpawned` | `player` (actor) |
| `playerDespawn` | `onPlayerDespawned` | `player` (actor) |
| `itemSpawn` | `onItemSpawned` | `id`, `qty`, `pos` |
| `itemDespawn` | `onItemDespawned` | `id`, `pos` |
| `varbit` | `onVarbitChanged` | `varbit` (varbit id), `varp` (varp id), `value` — gated behind the `logVarbits` config key |

Note that `projectile` deduplicates through a `WeakHashMap`-backed set, so a projectile that moves over several ticks appears exactly once.

## `snapshot-*.json` — full login snapshot

`GameStateSnapshot.capture(Client)` writes one pretty-printed JSON object. It is triggered automatically once per login: on the first tick where the local player has a name, the plugin schedules `snapshotAtTick = client.getTickCount() + 8`, so the dump happens about eight ticks later, once the scene has settled. It must run on the client thread.

### Sections

| Key | Contents |
| --- | --- |
| `meta` | `capturedAt` (ISO instant), `gameState`, `world`, `worldTypes`, `tickCount`, `gameCycle`, `fps`, `plane`, `baseX`, `baseY`, `mapRegions`, `instanced`, `canvas` (`"WxH"`), `resized` |
| `player` | `name`, `combatLevel`, `x`, `y`, `plane`, `regionId`, `animation`, `poseAnimation`, `graphic`, `healthRatio`, `healthScale`, `hp` (`"cur/max"` string), `prayer` (`"cur/max"` string), `runEnergyPercent`, `weight`, `specPercent`, `interacting` — omitted entirely if there is no local player |
| `skills` | one object per `Skill` enum entry keyed by `Skill.getName()` with `level`, `boosted`, `xp`, plus `totalLevel` and `overallXp`. On RuneLite 1.12.35 that is 25 entries — the 24 skills plus `Overall`, which is an enum member and therefore captured like any other |
| `inventory` | array of `{id, name, quantity}` for `InventoryID.INVENTORY` |
| `equipment` | array of `{id, name, quantity}` for `InventoryID.EQUIPMENT` |
| `bank` | array of `{id, name, quantity}` for `InventoryID.BANK` — populated only if the bank container has been loaded this session |
| `npcs` | every NPC in the scene: `index`, `id`, `name`, `combatLevel`, `x`, `y`, `distance`, `animation`, `graphic`, `healthRatio`, `healthScale`, `interacting` |
| `players` | every other player in the scene (local player excluded): `name`, `combatLevel`, `x`, `y`, `distance`, `animation`, `graphic` |
| `groundItems` | every ground item on the current plane across the full 104×104 scene: `id`, `name`, `quantity`, `x`, `y`, `distance` |
| `gameObjectCount` | count of distinct game objects seen in the scene sweep (deduplicated by object hash) |
| `nearbyObjects` | up to 80 named game objects within 12 tiles: `id`, `name`, `x`, `y`, `distance` |
| `varpsNonZero` | map of varp index (as a string) to value, for every non-zero entry of `client.getVarps()` |
| `openWidgetGroups` | ids of visible root widget groups (`w.getId() >> 16`) — the "which interfaces are open" fingerprint |
| `camera` | `x`, `y`, `z`, `pitch`, `yaw` |
| `menuEntries` | current right-click menu as `"option target"` strings |

Snapshots are large (the plugin logs their size in KB) and contain your account name, bank contents, and varps. Treat them as private files.

## `train/train_damage_model.py` — the danger model trainer

### What it predicts

A binary classifier for **P(the player takes damage within the next 3 game ticks)**. `HORIZON = 3`.

Labelling is done inside the trainer, per session file: for tick `i`, the label is `1.0` if any of ticks `i+1 … i+3` in the same file has `dmgTaken > 0`, else `0.0`. That is why `dmgTaken` is stored raw per tick — the future window, not the current line, is the target.

### Inputs and outputs

```
Usage:  py train/train_damage_model.py
Reads:  ~/.runelite/runeai/ticks-*.jsonl
Writes: src/main/resources/com/runeai/damage_model.json  (weights + feature spec + metrics)
```

The trainer refuses to run on thin data: fewer than 500 total ticks and it prints `only N ticks recorded — play more first (want 500+, ideally hours)` and exits.

### Features

Nine features in fixed order, all derived from a single tick line by `featurize(row)`. Order matters — it is the same order as the exported weight vector.

| # | Name | Computation |
| --- | --- | --- |
| 0 | `hpFrac` | `hp / max(1, hpMax)` |
| 1 | `prayFrac` | `min(1.0, pray / hpMax)` — prayer scaled by the hp-max proxy |
| 2 | `npcCount` | `min(1.0, npcCount / 10.0)` |
| 3 | `nearestDist` | `min(1.0, dist_of_npcs[0] / 10.0)`, using `30` when there are no NPCs |
| 4 | `nearestAtkMe` | `1.0` if `npcs[0].atkMe`, else `0.0` |
| 5 | `nearestAnim` | `1.0` if `npcs[0].anim != -1` (nearest NPC mid-animation / attack windup), else `0.0` |
| 6 | `attackersOn` | `min(1.0, count(npcs where atkMe) / 5.0)` |
| 7 | `inCombatAnim` | `1.0` if the local player's `anim != -1`, else `0.0` |
| 8 | `bias` | constant `1.0` |

Because `nearestDist`, `nearestAtkMe` and `nearestAnim` all read `npcs[0]`, they depend on the tick writer's distance sort — the plugin already sorts the `npcs` array ascending by distance.

### Training and metrics

Plain numpy logistic regression, deliberately: no scikit-learn, no framework, no sidecar process.

- 80/20 chronological split (`split = int(n * 0.8)`) — no shuffle, so the test set is later gameplay than the train set.
- 300 full-batch gradient descent epochs at learning rate `0.5` on the sigmoid cross-entropy gradient `X.T @ (sigmoid(Xw) - y) / n`.
- Reported metrics: `ticks`, `posRate` (share of positive labels), `testAcc`, `baseline` (always-predict-majority accuracy), `testAUC` (computed from the rank statistic).

The trainer prints the guidance line `AUC > 0.75 = signal worth wiring into the plugin as a live warning.` Below that bar, the model is not better than the existing rule-based `lowHpWarn` alert and should not be shipped.

### `damage_model.json` shape

```json
{
  "features": ["hpFrac", "prayFrac", "npcCount", "nearestDist", "nearestAtkMe",
               "nearestAnim", "attackersOn", "inCombatAnim", "bias"],
  "weights": [ ... 9 floats, same order ... ],
  "horizonTicks": 3,
  "metrics": {"ticks": 0, "posRate": 0.0, "testAcc": 0.0, "baseline": 0.0, "testAUC": 0.0}
}
```

### How weights deploy back into Java

The whole point of choosing logistic regression is the deployment path. The model is nine floats. To score a tick inside the plugin you build the same nine-element feature vector from live client state, take a **single dot product** against `weights`, and pass it through a sigmoid:

```
p = 1 / (1 + exp(-(w · f)))
```

That is roughly zero milliseconds per tick, adds no runtime dependency, no Python process, no network call, and no model file format beyond plain JSON. A neural-net sidecar is only worth doing once this baseline has been beaten with substantially more data — which is exactly what the tick recorder is accumulating.

## Privacy guarantees

- **Local only.** Every file described here is written to `~/.runelite/runeai/` on your own machine — via `java.nio.file.Files`. The plugin contains no HTTP client, no telemetry, no analytics, and no upload path of any kind.
- **Nothing is committed.** The repository `.gitignore` excludes `*.jsonl`, `snapshot-*.json`, and `src/main/resources/com/runeai/damage_model.json`, under the comment `# recorded game data must never go public (contains account names)`.
- **You can turn it off.** Set `recordTicks` to `false` to stop tick vectors, `logEvents` to `false` to stop the event stream, and `logVarbits` to `false` to drop varbit records from the stream. Restart the plugin for the two file switches to take effect; the varbit switch applies immediately.
- **You can delete it.** The recordings are ordinary text files. Deleting the contents of `~/.runelite/runeai/` removes everything the plugin has ever recorded; a new session simply starts fresh files.
- **The data is identifying.** Snapshots include your account name, skills, inventory, equipment and bank. Event streams include your chat messages. Never paste raw recordings into a public issue, Discord, or pull request.

## FAQ

### Does RuneAI send my game data anywhere?
No. The plugin writes JSON files to `~/.runelite/runeai/` and nothing else. There is no network code in the data layer.

### Is RuneAI a bot?
No. RuneAI is an AI companion and coach for OSRS. It reads game state and produces overlays, alerts, and voice callouts. It never sends input to the game.

### Where exactly are the files on Windows?
`C:\Users\<you>\.runelite\runeai\` — the same `.runelite` folder RuneLite already uses.

### How much data do I need before training the danger model?
At least 500 recorded ticks or the trainer exits. 500 ticks is about five minutes of play; the script's own advice is "ideally hours".

### Why JSONL instead of a database?
Append-only JSON Lines survives a client crash, streams line-by-line into numpy or pandas without a parser, and is trivially inspectable in a text editor. One line per tick, one line per event.

### How do I run the plugin to start recording?
RuneAI is not on the RuneLite Plugin Hub yet. Clone the repository and run it from source with `./gradlew run`.

---

Support development: [ko-fi.com/broketobuilt](https://ko-fi.com/broketobuilt) — there is also a Ko-fi button in the RuneAI sidebar panel.
