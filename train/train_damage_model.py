"""
RuneAI danger model — P(take damage within next 3 ticks) from tick vectors.

v2 (issue #11): RESIDUAL + BASELINE-GATED, the same shape the GE trainers use
(sim/ge_backtrainer.py, sim/ge_rl_trainer.py).

  1. BASELINE — the dumb model that already works: the empirical damage rate of
     the activity you are doing, shrunk toward the global rate so a rare
     activity cannot invent a wild number. "You are in Combat, ~1% of combat
     ticks are followed by a hit" is most of the available signal, and the old
     v1 model had to relearn it from scratch every run.
  2. RESIDUAL — logistic weights are a CORRECTION applied on top of the
     baseline's log-odds, initialised at zero. The model therefore STARTS at
     the baseline and can only earn its way above it; it cannot lose to its own
     baseline by wandering, the way level-prediction did on the GE side.
  3. TRUST GAIN — a scalar multiplier on the correction, fitted on a validation
     slice the weights never saw, clipped to [0, 2]. If the correction does not
     reduce validation loss the gain goes to 0 and the model reproduces the
     baseline exactly. The body proposes, the gain decides how much to believe.
  4. VERDICT — held-out log loss vs the baseline, written into the model JSON as
     `verdict.beats_baseline`. The plugin must refuse to use a model whose
     verdict is false. A run that loses never overwrites a saved model that won.

Splits are CHRONOLOGICAL and SESSION-LEVEL (whole recording files, oldest
first): 60% train / 20% validation (gain only) / 20% held-out test. Cutting a
session in half would let the test slice share a session's conditions with
train, which flatters the model.

Pure numpy on purpose: the weights export to JSON and run INSIDE the Java
plugin as one dot product per tick (no sidecar, no dependency).

Usage:  py train/train_damage_model.py
Reads:  ~/.runelite/runeai/ticks-*.jsonl
Writes: src/main/resources/com/runeai/damage_model.json  (baseline + weights + feature spec + verdict)
        ~/.runelite/runeai/damage_model-rejected.json  (a run the guard refused;
        kept outside the repo so no recorded-data artifact lands in git)

MEASURED 2026-08-10 on the whole 48-file corpus: 24,078 ticks, 5 damage ticks,
15 positive labels (0.062%). Every positive is in the F2P era; the members-era
half (12,478 ticks) contains zero damage. That is far below what any verdict
can be built on, so this run gates itself off — see MIN_TEST_POS. The gate is
the deliverable; a positive verdict needs a real P2P combat corpus.
"""
import glob
import json
import math
import os
import sys

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

DATA_DIR = os.path.expanduser("~/.runelite/runeai")
TICK_GLOB = os.path.join(DATA_DIR, "ticks-*.jsonl")
OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "com", "runeai", "damage_model.json"))
REJECTED = os.path.join(DATA_DIR, "damage_model-rejected.json")

HORIZON = 3       # label: damage taken within the next N ticks
HISTORY = 5       # feature window: this tick + the previous 4
PRIOR_K = 200.0   # shrinkage strength of a per-activity rate toward the global rate
L2 = 1e-3
LR = 1.0
EPOCHS = 3000

MIN_TICKS = 500       # below this there is nothing to train on at all
MIN_VAL_POS = 10      # below this the trust gain is forced to 0 (believe nothing)
MIN_TEST_POS = 20     # below this no verdict may be positive, however good it looks

FEATURES = [
    "hpFrac",           # hp / hpMax
    "recentDmg",        # damage taken over the last HISTORY ticks, / hpMax, cap 1
    "recentHit",        # any damage in the last HISTORY ticks, 0/1
    "prayFrac",         # pray / hpMax (proxy scale)
    "npcCount",         # /10, cap 1
    "nearestDist",      # /10, cap 1
    "nearestAtkMe",     # nearest NPC is interacting with me, 0/1
    "nearestAnim",      # nearest NPC mid-animation (attack windup), 0/1
    "nearestHasHp",     # nearest NPC shows a health bar (it is in combat), 0/1
    "attackersOn",      # count of NPCs with atkMe, /5, cap 1
    "attackerAdjacent", # an attacking NPC is within 1 tile, 0/1
    "inCombatAnim",     # local player anim != -1, 0/1
    "hasTarget",        # targetNpcId != -1, 0/1
    "moving",           # position changed since the previous tick, 0/1
    "bias",
]

FEATURE_DOC = {
    "hpFrac": "boosted HITPOINTS / real HITPOINTS",
    "recentDmg": "sum of dmgTaken over this tick and the previous 4, / hpMax, capped at 1",
    "recentHit": "1 if any dmgTaken > 0 over this tick and the previous 4",
    "prayFrac": "boosted PRAYER / hpMax, capped at 1",
    "npcCount": "number of named NPCs in the scene / 10, capped at 1",
    "nearestDist": "distance to the nearest NPC / 10, capped at 1 (30 if no NPC)",
    "nearestAtkMe": "1 if the nearest NPC is interacting with the local player",
    "nearestAnim": "1 if the nearest NPC's animation != -1",
    "nearestHasHp": "1 if the nearest NPC's health ratio >= 0 (health bar visible)",
    "attackersOn": "count of NPCs interacting with the player / 5, capped at 1",
    "attackerAdjacent": "1 if any NPC interacting with the player is within 1 tile",
    "inCombatAnim": "1 if the local player's animation != -1",
    "hasTarget": "1 if the player is interacting with an NPC",
    "moving": "1 if the player's world position differs from the previous tick",
    "bias": "constant 1.0",
}


def featurize(row, hist):
    """hist = the up-to-(HISTORY-1) rows immediately BEFORE this one, oldest
    first. Only past state is read — the label lives strictly in the future."""
    hp_max = max(1, row.get("hpMax", 1))
    npcs = row.get("npcs", [])
    nearest = npcs[0] if npcs else None
    attackers = [n for n in npcs if n.get("atkMe")]
    window = list(hist) + [row]
    recent_dmg = sum(r.get("dmgTaken", 0) for r in window)
    prev = hist[-1] if hist else None
    moved = bool(prev) and (prev.get("x") != row.get("x") or prev.get("y") != row.get("y"))
    return [
        row.get("hp", 0) / hp_max,
        min(1.0, recent_dmg / hp_max),
        1.0 if recent_dmg > 0 else 0.0,
        min(1.0, row.get("pray", 0) / hp_max),
        min(1.0, row.get("npcCount", 0) / 10.0),
        min(1.0, (nearest["dist"] if nearest else 30) / 10.0),
        1.0 if (nearest and nearest.get("atkMe")) else 0.0,
        1.0 if (nearest and nearest.get("anim", -1) != -1) else 0.0,
        1.0 if (nearest and nearest.get("hr", -1) >= 0) else 0.0,
        min(1.0, len(attackers) / 5.0),
        1.0 if any(n.get("dist", 99) <= 1 for n in attackers) else 0.0,
        1.0 if row.get("anim", -1) != -1 else 0.0,
        1.0 if row.get("targetNpcId", -1) != -1 else 0.0,
        1.0 if moved else 0.0,
        1.0,
    ]


def activity_of(row):
    """currentActivity() returns null when no XP has landed in 50 ticks, and gson
    drops null fields — so an absent key is its own bucket, not missing data."""
    return row.get("activity") or "none"


def load_sessions():
    """One entry per recording file, oldest first (filenames are timestamps).
    Labels come from the session's own future, never across a file boundary."""
    sessions = []
    malformed = 0
    for path in sorted(glob.glob(TICK_GLOB)):
        rows = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    malformed += 1  # live session mid-write / truncated tail
        if not rows:
            continue
        samples = []
        for i, row in enumerate(rows):
            fut = rows[i + 1: i + 1 + HORIZON]
            label = 1.0 if any(r.get("dmgTaken", 0) > 0 for r in fut) else 0.0
            samples.append((featurize(row, rows[max(0, i - HISTORY + 1): i]),
                            label, activity_of(row), bool(row.get("members"))))
        sessions.append((os.path.basename(path), samples))
    return sessions, malformed


def census(sessions):
    """What the corpus actually contains — printed every run so a thin corpus
    can never be mistaken for a trained one."""
    ticks = pos = mem_ticks = mem_pos = 0
    by_act = {}
    for _, samples in sessions:
        for _, y, act, mem in samples:
            ticks += 1
            pos += int(y)
            if mem:
                mem_ticks += 1
                mem_pos += int(y)
            a = by_act.setdefault(act, [0, 0])
            a[0] += 1
            a[1] += int(y)
    return dict(sessions=len(sessions), ticks=ticks, positives=pos,
                posRate=pos / max(1, ticks),
                membersTicks=mem_ticks, membersPositives=mem_pos,
                byActivity={k: dict(ticks=v[0], positives=v[1],
                                    posRate=v[1] / max(1, v[0]))
                            for k, v in sorted(by_act.items())})


def split_sessions(sessions, frac_test=0.2, frac_val=0.2):
    """Chronological, whole sessions, allocated from the most recent BACKWARDS.

    Walking forwards instead lets one oversized final session straddle the last
    boundary and leave the held-out slice empty — which is exactly what a
    6,400-tick session did to this corpus. Filling test first guarantees the
    newest sessions are the ones held out."""
    total = sum(len(s) for _, s in sessions)

    def back_from(end, want):
        i, n = end, 0
        while i > 1 and n < want * total:   # never consume the last train session
            i -= 1
            n += len(sessions[i][1])
        return i

    i_test = back_from(len(sessions), frac_test)
    i_val = back_from(i_test, frac_val)
    flat = lambda part: [s for _, samples in part for s in samples]
    return flat(sessions[:i_val]), flat(sessions[i_val:i_test]), flat(sessions[i_test:])


def pack(rows):
    X = np.array([r[0] for r in rows], dtype=float) if rows else np.zeros((0, len(FEATURES)))
    y = np.array([r[1] for r in rows], dtype=float) if rows else np.zeros(0)
    acts = [r[2] for r in rows]
    return X, y, acts


def baseline_rates(y, acts):
    """Per-activity empirical rate, shrunk toward the global rate by PRIOR_K
    pseudo-ticks. Fitted on TRAIN only."""
    glob_rate = float(y.mean()) if len(y) else 0.0
    agg = {}
    for yi, a in zip(y, acts):
        c = agg.setdefault(a, [0, 0.0])
        c[0] += 1
        c[1] += yi
    table = {a: (n_pos + PRIOR_K * glob_rate) / (n + PRIOR_K)
             for a, (n, n_pos) in agg.items()}
    return glob_rate, table


def base_probs(acts, glob_rate, table):
    return np.clip(np.array([table.get(a, glob_rate) for a in acts]), 1e-6, 1 - 1e-6)


def logit(p):
    return np.log(p / (1 - p))


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


def log_loss(y, p):
    p = np.clip(p, 1e-12, 1 - 1e-12)
    return float(-(y * np.log(p) + (1 - y) * np.log(1 - p)).mean())


def brier(y, p):
    return float(((p - y) ** 2).mean())


def auc(y, p):
    """Rank statistic with averaged ties — a constant predictor scores 0.5, not 1.
    Returns None (-> JSON null) when one class is absent; a bare NaN is not legal
    JSON and would hand the plugin a model file it cannot parse."""
    n_pos = float((y == 1).sum())
    n_neg = float((y == 0).sum())
    if not n_pos or not n_neg:
        return None
    order = np.argsort(p, kind="mergesort")
    sp = p[order]
    r = np.empty(len(sp))
    i = 0
    while i < len(sp):
        j = i
        while j + 1 < len(sp) and sp[j + 1] == sp[i]:
            j += 1
        r[i:j + 1] = (i + j) / 2.0 + 1.0
        i = j + 1
    ranks = np.empty(len(sp))
    ranks[order] = r
    return float((ranks[y == 1].sum() - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg))


def train_residual(X, y, offset):
    """Logistic weights as a correction on top of the baseline's log-odds.
    w starts at zero, so the model starts exactly AT the baseline."""
    w = np.zeros(X.shape[1])
    reg = np.ones(X.shape[1])
    reg[-1] = 0.0  # never regularise the bias
    for _ in range(EPOCHS):
        p = sigmoid(offset + X @ w)
        w -= LR * (X.T @ (p - y) / max(1, len(y)) + L2 * reg * w)
    return w


def fit_gain(X, y, offset, w):
    """How much of the correction to believe, chosen on data the weights never
    saw. Grid search on a single scalar: no learning rate, no local minima to
    miss, and gain 0 is always in the search space so the baseline is the floor."""
    if (y == 1).sum() < MIN_VAL_POS:
        return 0.0, (f"validation slice holds only {int((y == 1).sum())} positive "
                     f"labels (< {MIN_VAL_POS}) — the correction is untestable, "
                     f"gain pinned to 0 (= exactly the baseline)")
    core = X @ w
    grid = np.arange(0.0, 2.0001, 0.02)
    losses = [log_loss(y, sigmoid(offset + g * core)) for g in grid]
    g = float(grid[int(np.argmin(losses))])
    return g, f"fitted on {len(y):,} validation ticks ({int((y == 1).sum())} positive)"


def load_incumbent():
    if not os.path.exists(OUT):
        return None
    try:
        with open(OUT, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def main():
    sessions, malformed = load_sessions()
    stats = census(sessions)

    print(f"=== CORPUS ({stats['sessions']} non-empty sessions, "
          f"{malformed} malformed lines skipped) ===")
    print(f"  ticks {stats['ticks']:,} | positive labels (damage within {HORIZON} ticks) "
          f"{stats['positives']:,} ({100 * stats['posRate']:.3f}%)")
    print(f"  members-era ticks {stats['membersTicks']:,} | positives "
          f"{stats['membersPositives']:,}")
    for a, v in stats["byActivity"].items():
        print(f"    {a:<12} {v['ticks']:>7,} ticks  {v['positives']:>5,} pos  "
              f"{100 * v['posRate']:.3f}%")

    if stats["ticks"] < MIN_TICKS:
        print(f"\nonly {stats['ticks']} ticks recorded — play more first "
              f"(want {MIN_TICKS}+, ideally hours)")
        return

    train, val, test = split_sessions(sessions)
    Xtr, ytr, atr = pack(train)
    Xva, yva, ava = pack(val)
    Xte, yte, ate = pack(test)
    print(f"\nchronological session split — train {len(ytr):,} ({int(ytr.sum())} pos) | "
          f"val {len(yva):,} ({int(yva.sum())} pos) | "
          f"test {len(yte):,} ({int(yte.sum())} pos)")

    if not len(yte) or not len(ytr):
        print("split produced an empty slice — record more sessions")
        return

    # --- baseline: the dumb model the correction has to beat ---
    glob_rate, table = baseline_rates(ytr, atr)
    print(f"\nbaseline (per-activity rate, shrunk toward global by {PRIOR_K:.0f} "
          f"pseudo-ticks, fitted on train only): global {glob_rate:.5f}")
    for a, p in sorted(table.items()):
        print(f"    {a:<12} {p:.5f}")

    off_tr = logit(base_probs(atr, glob_rate, table))
    off_va = logit(base_probs(ava, glob_rate, table))
    off_te = logit(base_probs(ate, glob_rate, table))

    # --- residual correction, then how much of it to trust ---
    w = train_residual(Xtr, ytr, off_tr)
    gain, gain_note = fit_gain(Xva, yva, off_va, w)
    print(f"\ntrust gain {gain:.2f} — {gain_note}")

    p_base = sigmoid(off_te)
    p_model = sigmoid(off_te + gain * (Xte @ w))
    m = dict(baselineLogLoss=log_loss(yte, p_base), modelLogLoss=log_loss(yte, p_model),
             baselineBrier=brier(yte, p_base), modelBrier=brier(yte, p_model),
             baselineAUC=auc(yte, p_base), modelAUC=auc(yte, p_model))
    margin = m["baselineLogLoss"] - m["modelLogLoss"]   # >0 = the model is better
    test_pos = int(yte.sum())

    if test_pos < MIN_TEST_POS:
        beats = False
        reason = (f"held-out slice holds {test_pos} positive labels (< {MIN_TEST_POS}) — "
                  f"no verdict is measurable on this corpus, whatever the loss says")
    elif margin > 0:
        beats = True
        reason = (f"held-out log loss {m['modelLogLoss']:.6f} beats the per-activity "
                  f"baseline {m['baselineLogLoss']:.6f} over {test_pos} positives")
    else:
        beats = False
        reason = (f"held-out log loss {m['modelLogLoss']:.6f} does not beat the "
                  f"per-activity baseline {m['baselineLogLoss']:.6f}")

    print("\n=== HELD-OUT (the most recent 20% of sessions, never trained on) ===")
    print(f"  log loss  model {m['modelLogLoss']:.6f} vs baseline {m['baselineLogLoss']:.6f}"
          f"  (margin {margin:+.6f})")
    print(f"  Brier     model {m['modelBrier']:.6f} vs baseline {m['baselineBrier']:.6f}")
    num = lambda v: "n/a" if v is None else f"{v:.3f}"
    print(f"  AUC       model {num(m['modelAUC'])} vs baseline {num(m['baselineAUC'])}")
    print(f"  VERDICT   beats_baseline={beats} — {reason}")

    payload = dict(
        version=2,
        horizonTicks=HORIZON,
        historyTicks=HISTORY,
        features=FEATURES,
        featureDoc=FEATURE_DOC,
        weights=w.tolist(),
        gain=gain,
        baselineGlobal=glob_rate,
        baselineByActivity=table,
        baselinePriorK=PRIOR_K,
        corpus=stats,
        split=dict(train=dict(ticks=len(ytr), positives=int(ytr.sum())),
                   val=dict(ticks=len(yva), positives=int(yva.sum())),
                   test=dict(ticks=len(yte), positives=test_pos)),
        metrics=m,
        arena=dict(ticks=stats["ticks"], testPositives=test_pos),
        verdict=dict(beats_baseline=beats, margin=margin,
                     metric="held-out log loss (baseline - model; > 0 = model better)",
                     minTestPositives=MIN_TEST_POS, reason=reason),
    )

    # A losing run must never replace a saved model that wins, and a run judged on
    # fewer held-out positives must never replace one judged on more — the same
    # guard the GE backtrainer and the self-play champion carry.
    inc = load_incumbent()
    inc_beats = bool((inc or {}).get("verdict", {}).get("beats_baseline"))
    inc_pos = int((inc or {}).get("arena", {}).get("testPositives", 0))
    refuse = None
    if inc_beats and not beats:
        refuse = (f"this run has no verdict and {os.path.basename(OUT)} already holds a "
                  f"model that beats its baseline ({inc_pos} held-out positives)")
    elif inc_beats and beats and test_pos < inc_pos:
        refuse = (f"this run was judged on {test_pos} held-out positives, the saved model "
                  f"on {inc_pos} — the smaller arena does not get to overwrite the larger")
    if refuse:
        with open(REJECTED, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, allow_nan=False)
        print(f"\n  REJECTED — {refuse}. Kept the incumbent; this run -> {REJECTED}")
        return

    # allow_nan=False: a NaN or Infinity would serialise as a bare token that is
    # not legal JSON, and the plugin would fail to load the model at runtime.
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, allow_nan=False)
    print(f"\n  model -> {OUT}")
    print("  The plugin must read verdict.beats_baseline and refuse to warn from a "
          "model whose verdict is false.")


if __name__ == "__main__":
    main()
