"""
Flip fill-time model v1 — trained on YOUR real GE fills.

Reconstructs offer episodes from events-*.jsonl (geOffer events), then fits
a tiny ridge regression predicting log(seconds-to-fill) from side, price
level, quantity and hour. Exports weights to flip_model.json for in-plugin
dot-product inference. Also prints the per-item session stats table.

  py train/train_flip_model.py
"""
import glob
import json
import math
import os
import sys
from collections import defaultdict

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

EV_GLOB = os.path.expanduser("~/.runelite/runeai/events-*.jsonl")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flip_model.json")


def load_events():
    evs = []
    for path in sorted(glob.glob(EV_GLOB)):
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    e = json.loads(line)
                    if e.get("e") == "geOffer":
                        evs.append(e)
                except json.JSONDecodeError:
                    pass
    return evs


def episodes(evs):
    """Completed offers with fill duration (fillSecs logged at completion)."""
    out = []
    for e in evs:
        if e.get("fillSecs") is not None and e.get("qtySold", 0) > 0:
            buying = e["state"] == "BOUGHT"
            out.append(dict(
                item=e["item"], buying=buying, price=e["price"],
                qty=e["qtySold"], secs=max(1, e["fillSecs"]),
                suggested=e.get("suggested", False),
                hour=int(e["t"][11:13]) if "t" in e else 12,
            ))
    return out


def main():
    evs = load_events()
    eps = episodes(evs)
    print(f"geOffer events: {len(evs)}   completed fills with timing: {len(eps)}")
    if len(eps) < 10:
        print("not enough completed fills yet — flip more, then retrain")
        return

    # per-item session stats (the table Anthony asked for)
    by_item = defaultdict(lambda: dict(n=0, buys=0, sells=0, tot_secs=0))
    for ep in eps:
        s = by_item[ep["item"]]
        s["n"] += 1
        s["tot_secs"] += ep["secs"]
        s["buys" if ep["buying"] else "sells"] += 1
    print("\n=== PER-ITEM FILL STATS (completed offers) ===")
    print(f"  {'item id':>8s} {'fills':>6s} {'buys':>5s} {'sells':>6s} {'avg fill':>9s}")
    for iid, s in sorted(by_item.items(), key=lambda kv: -kv[1]["n"]):
        print(f"  {iid:>8d} {s['n']:>6d} {s['buys']:>5d} {s['sells']:>6d} {s['tot_secs']/s['n']:>8.0f}s")

    # features: [1, isBuy, log10(price), log10(qty), hour/24]
    X = np.array([[1.0, 1.0 if ep["buying"] else 0.0,
                   math.log10(max(2, ep["price"])),
                   math.log10(max(1, ep["qty"]) + 1),
                   ep["hour"] / 24.0] for ep in eps])
    y = np.array([math.log(ep["secs"]) for ep in eps])

    # ridge closed form (tiny data -> regularize hard)
    lam = 1.0
    w = np.linalg.solve(X.T @ X + lam * np.eye(X.shape[1]), X.T @ y)
    pred = X @ w
    mae = float(np.mean(np.abs(np.exp(pred) - np.exp(y))))
    base = float(np.mean(np.abs(np.median(np.exp(y)) - np.exp(y))))

    model = dict(
        features=["bias", "isBuy", "log10_price", "log10_qty", "hour_frac"],
        weights=w.tolist(), target="log_seconds_to_fill",
        metrics=dict(n=len(eps), mae_secs=mae, baseline_mae_secs=base,
                     beats_baseline=bool(mae < base)),
        note="provisional — retrain as fills accumulate; deploy only if beats baseline",
    )
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

    print(f"\n=== FILL-TIME MODEL v1 ===")
    print(f"  samples {len(eps)} | MAE {mae:.0f}s vs always-median baseline {base:.0f}s"
          f" -> {'BEATS baseline ✓' if mae < base else 'does NOT beat baseline yet ✗'}")
    print(f"  weights {['%.3f' % v for v in w]}")
    print(f"  -> {OUT}")
    print("  gate: the plugin only uses this when beats_baseline is true.")


if __name__ == "__main__":
    main()
