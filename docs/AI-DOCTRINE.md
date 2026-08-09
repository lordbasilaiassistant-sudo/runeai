# AI doctrine — what the best game-playing systems teach RuneAI

Last updated: 2026-08-09

Goal: a perfect all-in-one model that could essentially play OSRS — surfaced,
for now and deliberately, as a RuneLite plugin that shows the *player* exactly
what to do. The human is the actuator; we build the brain.

## Lessons taken (and how each maps to us)

**AlphaGo / AlphaZero — the simulator is the teacher.**
Superhuman play came from self-play inside a perfect rules engine plus search
(MCTS) over simulated futures. We just built our rules engine (`sim/`). The
progression planner is greedy today; its upgrade path is exactly AlphaZero's:
search over the unlock DAG + activity choices, with a learned value function
(long-run worth + xp + unlocks) replacing hand priorities. OSRS's slow, discrete
decisions (what to train, what to buy, which boss) are *ideal* for search.

**AlphaStar / OpenAI Five — bootstrap from humans, then exceed them.**
They started from imitation on human replays before self-play. Our tick corpus
IS the replay archive — an imitation baseline ("what would Anthony do") comes
free, then sim self-play improves on it. Their APM caps were a constraint;
ours is a feature: advice must be *human-followable*, and the corpus measures
real human timing tolerances, so the policy is trained to only propose clicks
a person can execute.

**MuZero — learn the model only where rules are hidden.**
We don't need to learn combat (published, exact). We DO need learned models
for the hidden parts: the GE market (RSI now → learned price dynamics later)
and other players. Spend model capacity only where the wiki can't tell us.

**Go-Explore / curriculum learning — walls are checkpoints, not failures.**
The sim already does this primitively: kill-count learning curves (Scurrius
before Graardor), and shelve-and-retry on Jad until gear improves. Formalize:
a curriculum over account states, fresh → mid → maxed, iron and regular.

**Voyager (LLM in Minecraft) — the LLM writes the skill library, offline.**
Voyager's LLM authored reusable code skills, validated by execution. Ours:
the LLM compiles wiki strategy pages into candidate triggers/callouts
("Graardor melee slam → pray melee"), each validated IN THE SIM before it
ever reaches the plugin. The LLM is a compiler with a test suite, never a
runtime decider — the two-gates law holds.

**Cicero (Diplomacy) — split strategy from communication.**
Cicero paired a planner with a language model for the human-facing layer.
Same split here: sim+planner decide, voice/mascot communicate. Neither does
the other's job.

## The architecture this adds up to

1. **Rules engine** (done, calibrating): exact math, curated world data,
   corpus-validated.
2. **Imitation baseline** (next): behavior cloning from tick corpus.
3. **Planner**: search (beam/MCTS) over the sim for macro decisions —
   activity, gear, unlock order, bond timing. Value net when data allows.
4. **Micro layer**: rule triggers + small nets for tick-scale advice
   (eat/move/pray/switch), latency-free inside the plugin.
5. **Knowledge compiler**: LLM turns wiki pages into sim-tested triggers
   and data entries, with provenance. Blocked from runtime.
6. **League/curriculum**: many simulated account archetypes training in
   parallel so advice generalizes to *anyone's* account, not just one.

The plugin stays the delivery vehicle: the model plays the game in sim
millions of times so that in the real client it can whisper the one thing
that matters *this tick* to a human holding the mouse.
