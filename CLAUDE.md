# CLAUDE.md

Last updated: 2026-08-09

**Read [AGENTS.md](./AGENTS.md) first — it is the canonical guidance for this repo.** It covers what
RuneAI is, the architecture map, the dev loop, and the coding conventions. This file exists only so
Claude Code finds the pointer plus the rules that must never be broken.

## One-paragraph context

RuneAI is a RuneLite plugin for Old School RuneScape: an AI buddy/coach that reads game state and helps
the player click the right thing at the right time, stay alive, and keep profits up. **It is not a bot**
— it never plays the game for the player. Source is `src/main/java/com/runeai/`; triggers live in
`RuneAIPlugin`, overlays are draw-only, voice is local Kokoro WAVs bundled as resources. It runs from
source (`./gradlew run`) and is not on the Plugin Hub.

## Dev loop

```bash
./gradlew compileTestJava   # after every edit
./gradlew run               # launches com.runeai.RuneAIPluginTest (RuneLite dev client)
```

Kill the running `RuneAIPluginTest` java process (or close the RuneLite window) before relaunching.

## Hard rules

1. **Never commit recorded game data** — `~/.runelite/runeai/*.jsonl`, `snapshot-*.json`,
   `src/main/resources/com/runeai/damage_model.json`. They contain account names and play history. Keep `.gitignore` intact.
2. **Recordings stay local.** No upload endpoint, no telemetry, no third-party server.
3. **Never auto-submit to the RuneLite Plugin Hub** without Anthony asking for it in that session.
4. **Never make RuneAI act for the player.** No input injection, no automated clicking, no menu entries
   that send actions to the server. Buddy, not bot.
5. **Do not commit build artifacts** (`build/`, `.class`).
6. **Do not claim a feature works from the code alone** — only in-game confirmation from the user counts.
