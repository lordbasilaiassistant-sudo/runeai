"""
Flip fill-time model v2 — trained on YOUR real GE fills.

Reconstructs offer episodes from events-*.jsonl (geOffer events), then fits a
tiny ridge regression predicting log(seconds-to-fill) from side, price level,
quantity and hour. Exports weights to flip_model.json for in-plugin dot-product
inference, and prints the per-item fill table.

WHAT CHANGED IN v2, and why the v1 verdict could not be trusted:

  1. v1 scored the model on the SAME 13 rows it fitted, against a baseline
     computed on those same rows. Five parameters on thirteen points will beat
     an in-sample median almost by construction. Every number here is now
     out-of-sample: K-fold, with the baseline median recomputed inside each
     training fold so both sides face identical ignorance about the held-out
     rows.
  2. v1 trained on offline fills. Those complete at an unknown moment while the
     player is logged out, and the plugin stamps their duration from the offer's
     placement — one of them was 28,691s. They are excluded.
  3. "beats_baseline" was a boolean on a 2.8% in-sample edge. Adoption now needs
     a MARGIN, on the target actually fitted, over a minimum sample. The plugin
     reads `verdict`; anything but "adopt" means fall back to observed fill times.

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
OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "com", "runeai", "flip_model.json"))

FEATURES = ["bias", "isBuy", "log10_price", "log10_qty", "hour_frac"]
LAM = 1.0            # ridge penalty: tiny data, regularize hard
MIN_SAMPLES = 40     # below this the CV estimate is itself noise
MIN_GAIN = 0.15      # out-of-sample error must fall 15%+ to earn deployment
QUICK_CEILING = 300  # matches FlipLane.DEFAULT_QUICK_CYCLE_SECS (full cycle)


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
    """Completed offers with a duration we actually watched elapse.

    Offline fills are dropped: the offer completed at an unknown moment while
    the client was closed, so its "fillSecs" measures the logout, not the market.
    """
    out, skipped_offline = [], 0
    for e in evs:
        if e.get("fillSecs") is None or e.get("qtySold", 0) <= 0:
            continue
        if e.get("offline"):
            skipped_offline += 1
            continue
        out.append(dict(
            item=e["item"], buying=e["state"] == "BOUGHT", price=e["price"],
            qty=e["qtySold"], secs=max(1, e["fillSecs"]),
            suggested=e.get("suggested", False),
            lane=e.get("lane", "?"),
            hour=int(e["t"][11:13]) if "t" in e else 12,
        ))
    return out, skipped_offline


def design(eps):
    return np.array([[1.0, 1.0 if ep["buying"] else 0.0,
                      math.log10(max(2, ep["price"])),
                      math.log10(max(1, ep["qty"]) + 1),
                      ep["hour"] / 24.0] for ep in eps])


def ridge(X, y):
    return np.linalg.solve(X.T @ X + LAM * np.eye(X.shape[1]), X.T @ y)


def cross_validate(X, y, k, seed=7):
    """K-fold out-of-sample error for the model AND for the median baseline.

    The baseline median is refit inside each training fold, so neither side ever
    sees a held-out row. Errors are collected in log space (what the model fits,
    and what ranking cares about — relative speed) and in seconds.
    """
    n = len(y)
    rng = np.random.default_rng(seed)
    order = rng.permutation(n)
    folds = np.array_split(order, k)
    m_log, b_log, m_sec, b_sec = [], [], [], []
    for f in folds:
        te = np.array(f)
        tr = np.array([i for i in order if i not in set(f.tolist())])
        if len(tr) < X.shape[1] + 1:
            continue
        w = ridge(X[tr], y[tr])
        base = float(np.median(y[tr]))
        for i in te:
            p = float(X[i] @ w)
            m_log.append(abs(p - y[i]))
            b_log.append(abs(base - y[i]))
            m_sec.append(abs(math.exp(p) - math.exp(y[i])))
            b_sec.append(abs(math.exp(base) - math.exp(y[i])))
    return (float(np.mean(m_log)), float(np.mean(b_log)),
            float(np.median(m_sec)), float(np.median(b_sec)))


def main():
    evs = load_events()
    eps, skipped = episodes(evs)
    print(f"geOffer events: {len(evs)}   usable completed fills: {len(eps)}"
          f"   (dropped {skipped} offline fills of unknown duration)")
    if len(eps) < 10:
        print("not enough completed fills yet — flip more, then retrain")
        return

    secs = sorted(ep["secs"] for ep in eps)
    quick = [s for s in secs if s * 2 <= QUICK_CEILING]
    print(f"fill seconds: median {secs[len(secs)//2]}  p90 {secs[int(len(secs)*.9)]}  "
          f"max {secs[-1]}   |  {len(quick)}/{len(secs)} would cycle inside "
          f"the {QUICK_CEILING}s quick ceiling")
    lanes = defaultdict(int)
    for ep in eps:
        lanes[ep["lane"]] += 1
    print(f"lane labels on these fills: {dict(lanes)}"
          f"{'  (? = logged before lanes existed)' if lanes.get('?') else ''}")

    by_item = defaultdict(lambda: dict(n=0, buys=0, sells=0, tot_secs=0))
    for ep in eps:
        s = by_item[ep["item"]]
        s["n"] += 1
        s["tot_secs"] += ep["secs"]
        s["buys" if ep["buying"] else "sells"] += 1
    print("\n=== PER-ITEM FILL STATS (completed, online offers) ===")
    print(f"  {'item id':>8s} {'fills':>6s} {'buys':>5s} {'sells':>6s} {'avg fill':>9s}")
    for iid, s in sorted(by_item.items(), key=lambda kv: -kv[1]["n"])[:20]:
        print(f"  {iid:>8d} {s['n']:>6d} {s['buys']:>5d} {s['sells']:>6d} "
              f"{s['tot_secs']/s['n']:>8.0f}s")

    X = design(eps)
    y = np.array([math.log(ep["secs"]) for ep in eps])
    k = min(10, max(2, len(eps) // 5))
    cv_log, base_log, cv_sec, base_sec = cross_validate(X, y, k)
    gain_log = (base_log - cv_log) / base_log if base_log > 0 else 0.0
    gain_sec = (base_sec - cv_sec) / base_sec if base_sec > 0 else 0.0

    w = ridge(X, y)   # ship the fit on everything; the CV above is what judges it
    adopt = len(eps) >= MIN_SAMPLES and gain_log >= MIN_GAIN and gain_sec > 0
    why = ("beats the median baseline out of sample by a clear margin"
           if adopt else
           f"needs n>={MIN_SAMPLES} (have {len(eps)}) and log-error gain "
           f">={MIN_GAIN:.0%} with no loss in seconds "
           f"(have {gain_log:+.1%} log, {gain_sec:+.1%} sec)")

    model = dict(
        features=FEATURES, weights=w.tolist(), target="log_seconds_to_fill",
        verdict="adopt" if adopt else "fallback",
        verdict_reason=why,
        metrics=dict(
            n=len(eps), folds=k,
            cv_log_mae=cv_log, baseline_log_mae=base_log, log_gain=gain_log,
            cv_median_abs_secs=cv_sec, baseline_median_abs_secs=base_sec,
            secs_gain=gain_sec,
            median_fill_secs=secs[len(secs) // 2],
        ),
        note=("All metrics are K-fold out-of-sample with the baseline median "
              "refit inside each training fold. The plugin ranks by cycle "
              "velocity either way; this model only sharpens the cycle-time "
              "estimate when verdict == 'adopt'."),
    )
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

    print(f"\n=== FILL-TIME MODEL v2 ({k}-fold out-of-sample) ===")
    print(f"  samples {len(eps)}")
    print(f"  log-space MAE  {cv_log:.3f} vs baseline {base_log:.3f}  -> {gain_log:+.1%}")
    print(f"  median |err|   {cv_sec:.0f}s vs baseline {base_sec:.0f}s  -> {gain_sec:+.1%}")
    print(f"  weights {['%.3f' % v for v in w]}")
    print(f"  VERDICT: {model['verdict'].upper()} — {why}")
    print(f"  -> {OUT}")


if __name__ == "__main__":
    main()
