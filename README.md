# RuneAI

A RuneLite plugin that watches the game state and actively helps you play better — like having a coach looking over your shoulder.

## What it does

- **Idle click guidance** — it knows what you're doing (mining, fishing, woodcutting, cooking, smithing, combat) from your xp drops. Go idle mid-task and it flashes the next thing to click with an animated green marker: the nearest rock, tree, fishing spot, range, anvil, or free target NPC.
- **Voice callouts** — spoken nudges: "You're idle", "Low health, eat now", "Inventory full, bank your items", "Good drop, grab it", "You're under attack". Per-line cooldowns so it never nags.
- **Combat awareness** — anything attacking you gets a red outline and name label; your current target gets a cyan outline. An animated EAT banner appears when you're under attack at low HP.
- **Smart loot flashes** — nearby drops worth picking up (GE value filter, configurable) get a pulsing gold marker with name and value. Junk stays quiet.
- **Full data layer** — every game tick is recorded as a fixed-shape state vector (`~/.runelite/runeai/ticks-*.jsonl`), every game event streams to `events-*.jsonl`, and a full game-state snapshot auto-saves at login. This corpus trains the models behind smarter future guidance.

Everything is automatic — no buttons to press. Every feature can be toggled in the plugin config.

## Development

```
./gradlew run          # launch RuneLite with the plugin loaded (dev mode)
py train/train_damage_model.py   # train the danger model on your recorded ticks
```

## Status

In active development, tested on free-to-play. Not yet submitted to the Plugin Hub.
