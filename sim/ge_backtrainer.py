"""
GE backtrainer — Anthony's "we know the answers already": train the same
net on HISTORICAL timeseries where every outcome is known, instead of
waiting for live drips. Pulls per-item history from the wiki API
(1h bars ~2 weeks + 5m bars ~30h for the most liquid items), builds the
exact same features/targets as the live trainer, trains with proper
epochs and a chronological train/test split, and reports honestly vs
the martingale baseline. Brain -> ge-brain-hist.json.

  py sim/ge_backtrainer.py --items 350 --epochs 8
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.request

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ge_rl_trainer import Net, features, ge_tax  # same brain, same physics

API = "https://prices.runescape.wiki/api/v1/osrs"
UA = {"User-Agent": "RuneAI GE backtrainer (github.com/lordbasilaiassistant-sudo/runeai)"}
OUT = os.path.join(os.path.expanduser("~/.runelite/runeai"), "ge-brain-hist.json")


def fetch(path):
    req = urllib.request.Request(API + path, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def liquid_items(n):
    """Top-n items by live 5m volume — the universe worth learning."""
    five = fetch("/5m")["data"]
    vols = []
    for sid, v in five.items():
        vol = (v.get("highPriceVolume") or 0) + (v.get("lowPriceVolume") or 0)
        if vol > 0:
            vols.append((vol, int(sid)))
    vols.sort(reverse=True)
    return [iid for _, iid in vols[:n]]


def series_samples(iid, step):
    """(features, target, item, is_test) rows from one item's history."""
    try:
        data = fetch(f"/timeseries?timestep={step}&id={iid}")["data"]
    except Exception:
        return []
    rows = [d for d in data
            if d.get("avgHighPrice") and d.get("avgLowPrice")]
    out = []
    prev_mid = 0
    cut = int(len(rows) * 0.8)  # chronological split: test on the future
    for i in range(len(rows) - 1):
        r, nxt = rows[i], rows[i + 1]
        lo, hi = r["avgLowPrice"], r["avgHighPrice"]
        if lo < 100:
            prev_mid = (lo + hi) / 2
            continue
        vol = (r.get("highPriceVolume") or 0) + (r.get("lowPriceVolume") or 0)
        hour = time.gmtime(r["timestamp"]).tm_hour
        x = features(lo, hi, vol, prev_mid, hour)
        target = math.log(max(nxt["avgHighPrice"], 2) / max(lo, 2))
        mart = math.log(max(hi, 2) / max(lo, 2))
        out.append((x, target, mart, iid, i >= cut))
        prev_mid = (lo + hi) / 2
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", type=int, default=350)
    ap.add_argument("--epochs", type=int, default=8)
    args = ap.parse_args()

    print("selecting the most liquid items…")
    ids = liquid_items(args.items)
    print(f"pulling history for {len(ids)} items (1h + 5m bars)…")

    samples = []
    for k, iid in enumerate(ids):
        samples += series_samples(iid, "1h")
        samples += series_samples(iid, "5m")
        if (k + 1) % 50 == 0:
            print(f"  {k+1}/{len(ids)} items, {len(samples):,} samples so far")
        time.sleep(0.25)  # polite to the wiki

    train = [s for s in samples if not s[4]]
    test = [s for s in samples if s[4]]
    print(f"\ntotal {len(samples):,} samples -> train {len(train):,} / test {len(test):,}")
    if len(train) < 1000:
        print("not enough history — aborting")
        return

    Xtr = np.array([s[0] for s in train])
    ytr = np.array([s[1] for s in train])
    ids_tr = [s[3] for s in train]
    Xte = np.array([s[0] for s in test])
    yte = np.array([s[1] for s in test])
    ids_te = [s[3] for s in test]
    mart_te = np.array([s[2] for s in test])

    mart_tr = np.array([s_[2] for s_ in train])

    rng = np.random.default_rng(7)
    net = Net(rng)
    # RESIDUAL LEARNING: the net predicts the CORRECTION to the martingale,
    # starting at ~zero — it begins AT the baseline and can only earn gains
    net.W2 *= 0.05
    net.b2[:] = 0
    ytr_res = ytr - mart_tr
    yte_res = yte - mart_te
    order = np.arange(len(ytr))
    B = 256
    for ep in range(args.epochs):
        rng.shuffle(order)
        mse = 0.0
        nb = 0
        for s0 in range(0, len(order), B):
            idx = order[s0:s0 + B]
            mse += net.train(Xtr[idx], ytr_res[idx],
                             [ids_tr[i] for i in idx], lr=0.02)
            nb += 1
        # honest out-of-sample check every epoch (baseline + correction)
        pte = mart_te + net.forward(Xte, ids_te)
        te = float(np.mean((pte - yte) ** 2))
        print(f"  epoch {ep+1}/{args.epochs}: train MSE {mse/nb:.5f} | TEST MSE {te:.5f}")

    pte = mart_te + net.forward(Xte, ids_te)
    net_mse = float(np.mean((pte - yte) ** 2))
    mart_mse = float(np.mean((mart_te - yte) ** 2))
    rel_err = np.abs(np.exp(pte) - np.exp(yte)) / np.exp(yte)
    reward = float(np.mean(np.exp(-rel_err * 40)))
    exact = int((rel_err < 0.002).sum())
    beats = net_mse < mart_mse

    print("\n=== BACKTRAIN FINAL (out-of-sample: the FUTURE 20% of history) ===")
    print(f"  net MSE {net_mse:.5f} vs martingale {mart_mse:.5f} -> "
          f"{'BEATS BASELINE ✓' if beats else 'does not beat baseline ✗'}")
    print(f"  exact hits {exact:,}/{len(yte):,} | avg reward {reward:.3f}")
    print(f"  item personalities learned: {len(net.item_bias)}")

    payload = dict(net=net.dump(),
                   metrics=dict(train=len(train), test=len(yte),
                                net_mse=net_mse, martingale_mse=mart_mse,
                                beats_baseline=beats, exact_hits=exact,
                                avg_reward=reward))

    # A losing run must never replace a saved brain that wins: this file is the
    # live trainer's warm start, and a small --items run can diverge and quietly
    # destroy a good one (which is exactly how this was found).
    incumbent = None
    if os.path.exists(OUT):
        try:
            with open(OUT, encoding="utf-8") as f:
                m = json.load(f).get("metrics", {})
            incumbent = m if m.get("beats_baseline") else None
        except (OSError, ValueError):
            pass
    if not beats and incumbent:
        alt = OUT.replace(".json", "-rejected.json")
        with open(alt, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        print(f"  REJECTED — this run lost to the baseline and {os.path.basename(OUT)} "
              f"already holds one that beats it (net MSE {incumbent['net_mse']:.5f} over "
              f"{incumbent.get('test', '?')} test rows). Kept the incumbent; run -> {alt}")
        return
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f)
    print(f"  brain -> {OUT} (adoption still gated on beats_baseline)")


if __name__ == "__main__":
    main()
