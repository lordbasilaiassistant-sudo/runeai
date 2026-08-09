# RuneAI knowledge design — how BiS and method knowledge stays true

Last updated: 2026-08-09

RuneAI's hard rule: **it must never tell the player something wrong.** A confident wrong
recommendation is worse than silence. So game knowledge (best-in-slot gear, training methods,
boss strategies) is never generated as free text by a model — it comes from three tiers, each
with a different truth guarantee, and nothing reaches the screen without one of them backing it.

## Tier 1 — Computed truth (cannot hallucinate)

The OSRS Wiki maintains machine-readable item data: equipment slot, attack/defence bonuses,
strength/prayer/ranged bonuses, level requirements, members flag, GE price. "Best F2P melee
torso at 40 Defence under 100k gp" is a **database query** — filter by slot + F2P + the
player's actual levels (which RuneAI reads live) + budget (which RuneAI tracks live), sort by
the relevant bonus. A query over real stat tables can return a *stale* answer but never an
*invented* item. This tier answers all "what should I wear/wield" questions.

Planned implementation: periodic fetch of the wiki item-stats dump + GE price API into a local
cache shipped with (or downloaded by) the plugin; a pure-function BiS resolver over it.

## Tier 2 — Curated strategy (cited, never invented)

Boss/monster strategies, safespots, prayer choices, and method guides are parsed from OSRS Wiki
strategy pages into structured local data, each entry carrying its source URL and fetch date.
Recommendations from this tier are always attributable ("per OSRS Wiki"). If a page can't be
parsed cleanly, the entry doesn't exist — no fallback to model guesses.

## Tier 3 — Learned layer (measured on this player)

The tick-vector corpus records per tick: gear worn (`equip`), detected activity (`activity`),
goal mode (`gp` vs `xp`), members status (`members`), damage taken (`dmgTaken`), xp gained
(`xpGained`), session P&L (`pnl`) and total worth (`worth`) — see
[DATA-LAYER.md](./DATA-LAYER.md) for the exact schema. From that, models learn what
*this player's* setups actually achieve — xp/hr, gp/hr, damage intake per gear/method combo —
and rank Tier 1/2 options by measured outcomes. Tier 3 personalizes and validates; it is never
allowed to originate an item or method fact.

## The gate

Before any gear/method advice renders or speaks:

1. Is it the output of a Tier 1 query over real stat data? → allowed.
2. Is it a Tier 2 entry with a source URL? → allowed, attributed.
3. Is it a Tier 3 ranking of already-allowed options, backed by ≥ a minimum sample of the
   player's own ticks? → allowed.
4. Anything else — including any LLM free-text about game facts — is blocked.

This mirrors the two-gate principle: a schema-valid model output is not truth; only a source
of truth is truth. Every rejected/blocked suggestion is logged, because failures teach the
core models too.

## F2P / P2P awareness

Every knowledge query is filtered by the player's actual world type (RuneAI reads
`WorldType.MEMBERS` live). F2P mode also activates the bond ladder: total account worth
(carried + bank, GE-priced) vs the live GE bond price — membership unlocks better methods,
so reaching bond affordability is itself a standing recommendation.
