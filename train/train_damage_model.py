"""
RuneAI v1 danger model — P(take damage within next 3 ticks) from tick vectors.

Pure numpy logistic regression on purpose: the trained weights export to JSON
and run INSIDE the Java plugin as a single dot product per tick (~0ms, no
sidecar, no dependencies). Upgrade to a real NN sidecar only after this
baseline is beaten by enough data.

Usage:  py train/train_damage_model.py
Reads:  ~/.runelite/runeai/ticks-*.jsonl
Writes: train/damage_model.json  (weights + feature spec + metrics)
"""
import glob
import json
import os
import sys

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

TICK_GLOB = os.path.expanduser("~/.runelite/runeai/ticks-*.jsonl")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "damage_model.json")
HORIZON = 3  # label: damage taken within the next N ticks

FEATURES = [
    "hpFrac",        # hp / hpMax
    "prayFrac",      # pray / hpMax proxy scale
    "npcCount",      # /10, capped 1
    "nearestDist",   # /10, capped 1
    "nearestAtkMe",  # 0/1
    "nearestAnim",   # nearest NPC mid-animation (attack windup) 0/1
    "attackersOn",   # count of npcs with atkMe true, /5 capped 1
    "inCombatAnim",  # local player anim != -1
    "bias",
]


def featurize(row):
    hp_max = max(1, row.get("hpMax", 1))
    npcs = row.get("npcs", [])
    nearest = npcs[0] if npcs else None
    attackers = sum(1 for n in npcs if n.get("atkMe"))
    return [
        row.get("hp", 0) / hp_max,
        min(1.0, row.get("pray", 0) / hp_max),
        min(1.0, row.get("npcCount", 0) / 10.0),
        min(1.0, (nearest["dist"] if nearest else 30) / 10.0),
        1.0 if (nearest and nearest.get("atkMe")) else 0.0,
        1.0 if (nearest and nearest.get("anim", -1) != -1) else 0.0,
        min(1.0, attackers / 5.0),
        1.0 if row.get("anim", -1) != -1 else 0.0,
        1.0,
    ]


def load():
    rows = []
    for path in sorted(glob.glob(TICK_GLOB)):
        with open(path, encoding="utf-8") as f:
            session = [json.loads(line) for line in f if line.strip()]
        # label each tick from the session's own future
        for i, row in enumerate(session):
            fut = session[i + 1 : i + 1 + HORIZON]
            label = 1.0 if any(r.get("dmgTaken", 0) > 0 for r in fut) else 0.0
            rows.append((featurize(row), label))
    return rows


def main():
    rows = load()
    if len(rows) < 500:
        print(f"only {len(rows)} ticks recorded — play more first (want 500+, ideally hours)")
        return

    X = np.array([r[0] for r in rows])
    y = np.array([r[1] for r in rows])
    n = len(y)
    split = int(n * 0.8)
    Xtr, ytr, Xte, yte = X[:split], y[:split], X[split:], y[split:]

    w = np.zeros(X.shape[1])
    lr = 0.5
    for epoch in range(300):
        p = 1 / (1 + np.exp(-Xtr @ w))
        w -= lr * Xtr.T @ (p - ytr) / len(ytr)

    p_te = 1 / (1 + np.exp(-Xte @ w))
    # AUC via rank statistic
    order = np.argsort(p_te)
    ranks = np.empty(len(p_te))
    ranks[order] = np.arange(1, len(p_te) + 1)
    n_pos, n_neg = ytr.sum() and yte.sum(), (1 - yte).sum()
    n_pos = yte.sum()
    auc = ((ranks[yte == 1].sum() - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg)) if n_pos and n_neg else float("nan")
    acc = float(((p_te > 0.5) == yte).mean())
    base = float(1 - yte.mean()) if yte.mean() < 0.5 else float(yte.mean())

    model = {
        "features": FEATURES,
        "weights": w.tolist(),
        "horizonTicks": HORIZON,
        "metrics": {"ticks": n, "posRate": float(y.mean()), "testAcc": acc,
                    "baseline": base, "testAUC": float(auc)},
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

    print(f"ticks={n} posRate={y.mean():.3f}")
    print(f"testAcc={acc:.3f} (always-majority baseline {base:.3f}) AUC={auc:.3f}")
    print(f"model -> {OUT}")
    print("AUC > 0.75 = signal worth wiring into the plugin as a live warning.")


if __name__ == "__main__":
    main()
