"""
Self-play league — Anthony's AlphaGo spec: the AI competes against its own
last session's P&L, forever trying to beat itself. Emergent strategy, not
hand-tuned rules.

A population of POLICIES (parameter genomes: momentum gates, ROI floors,
capital concentration, volume share, patience) replays the same historical
window. Each generation must BEAT THE INCUMBENT CHAMPION's P&L to take the
crown; champions breed, losers die. The final champion is judged on a
held-out future window it never evolved on (no cheating), against both the
default policy and its own ancestor.

  py sim/self_play.py --gens 12 --pop 14 --start 200000
Champion genome -> ~/.runelite/runeai/ge-champion.json
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.request
from collections import defaultdict

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ge_rl_trainer import Net, features, ge_tax
from flip_backtest import fetch, load_brain
from market import fetch_mapping, tradeable_ids

CHAMP = os.path.expanduser("~/.runelite/runeai/ge-champion.json")

DEFAULT = dict(roi_floor=0.005, mom_buy_cut=-0.01, mom_spike_cut=0.05,
               vol_share=0.10, conc=0.33, hold_bars=1)
RANGES = dict(roi_floor=(0.0, 0.05), mom_buy_cut=(-0.05, 0.0),
              mom_spike_cut=(0.01, 0.15), vol_share=(0.02, 0.25),
              conc=(0.15, 0.9), hold_bars=(1, 3))


def pull_history(n_items, universe="all"):
    raw_map = fetch_mapping()
    allowed = tradeable_ids(raw_map, universe)
    five = fetch("/5m")["data"]
    vols = sorted(((v.get("highPriceVolume", 0) or 0) + (v.get("lowPriceVolume", 0) or 0), int(k))
                  for k, v in five.items() if int(k) in allowed)
    ids = [iid for _, iid in vols[::-1][:n_items]]
    mapping = {iid: (m.get("limit") or 100) for iid, m in raw_map.items()}
    hist = {}
    for k, iid in enumerate(ids):
        try:
            rows = [r for r in fetch(f"/timeseries?timestep=1h&id={iid}")["data"]
                    if r.get("avgHighPrice") and r.get("avgLowPrice") and r["avgLowPrice"] >= 100]
            if len(rows) > 50:
                hist[iid] = {r["timestamp"]: r for r in rows}
        except Exception:
            pass
        if (k + 1) % 60 == 0:
            print(f"  {k+1}/{len(ids)}")
        time.sleep(0.2)
    stamps = sorted(set(t for h in hist.values() for t in h))
    return hist, stamps, mapping


def run_policy(pol, net, hist, stamps, mapping, start_cap, slots=3):
    cap = float(start_cap)
    bought = defaultdict(list)
    busy = [0] * slots
    prev_mid = {}
    for ti in range(len(stamps) - 1):
        t, tn = stamps[ti], stamps[ti + 1]
        hold = int(round(pol["hold_bars"]))
        sell_i = min(ti + hold, len(stamps) - 1)
        ts = stamps[sell_i]
        cands = []
        for iid, h in hist.items():
            r, rs = h.get(t), h.get(ts)
            if not r or not rs:
                continue
            lo, hi = r["avgLowPrice"], r["avgHighPrice"]
            vol = (r.get("highPriceVolume") or 0) + (r.get("lowPriceVolume") or 0)
            if vol < 20 or lo + 1 > cap:
                continue
            pm = prev_mid.get(iid, 0)
            mom = ((lo + hi) / 2 / pm - 1) if pm else 0.0
            prev_mid[iid] = (lo + hi) / 2
            if mom < pol["mom_buy_cut"] or mom > pol["mom_spike_cut"]:
                continue  # falling knife / spike chase — policy's call
            x = features(lo, hi, vol, pm, time.gmtime(t).tm_hour)
            pred = math.log(max(hi, 2) / max(lo, 2)) + net.forward(np.array([x]), [iid])[0]
            ps = lo * math.exp(pred)
            pnet = ps - ge_tax(int(ps)) - (lo + 1)
            roi = pnet / (lo + 1)
            if roi < pol["roi_floor"]:
                continue
            cands.append((roi, iid, lo, vol, rs))
        cands.sort(reverse=True)
        free = [i for i in range(slots) if busy[i] <= t]
        spend = cap
        for roi, iid, lo, vol, rs in cands[:len(free)]:
            recent = sum(u for tt, u in bought[iid] if t - tt < 4 * 3600)
            units = int(min(max(0, mapping.get(iid, 100) - recent),
                            vol * pol["vol_share"], (spend * pol["conc"]) / (lo + 1)))
            if units < 1:
                continue
            cost = units * (lo + 1)
            px = rs["avgHighPrice"] - 1
            cap += units * (px - ge_tax(px)) - cost
            spend -= cost
            bought[iid].append((t, units))
            busy[free.pop(0)] = ts + 3600
    return cap


def mutate(pol, rng, scale=0.25):
    child = dict(pol)
    for k, (lo, hi) in RANGES.items():
        if rng.random() < 0.5:
            child[k] = float(np.clip(child[k] + rng.normal(0, (hi - lo) * scale), lo, hi))
    return child


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=12)
    ap.add_argument("--pop", type=int, default=14)
    ap.add_argument("--start", type=int, default=200_000)
    ap.add_argument("--items", type=int, default=250)
    ap.add_argument("--universe", choices=("all", "f2p"), default="all",
                    help="f2p = evolve only on items a free account can trade")
    args = ap.parse_args()

    net = load_brain()
    print("pulling league arena (history)…")
    hist, stamps, mapping = pull_history(args.items, args.universe)
    cut = int(len(stamps) * 0.7)
    trainW = stamps[:cut]      # the league evolves here
    testW = stamps[cut:]       # the crown is defended HERE (never seen)
    print(f"{len(hist)} items | evolve on {len(trainW)} bars, judge on {len(testW)} bars\n")

    rng = np.random.default_rng(42)
    champion = dict(DEFAULT)
    champ_pnl = run_policy(champion, net, hist, trainW, mapping, args.start)
    print(f"gen 0 incumbent (default policy): {champ_pnl:,.0f} gp")

    pop = [mutate(champion, rng) for _ in range(args.pop)]
    lineage = []
    for g in range(1, args.gens + 1):
        scored = sorted(((run_policy(p, net, hist, trainW, mapping, args.start), p)
                         for p in pop), key=lambda x: -x[0])
        best_pnl, best = scored[0]
        beat = best_pnl > champ_pnl
        print(f"gen {g}: best {best_pnl:,.0f} gp vs champion {champ_pnl:,.0f} "
              f"-> {'DETHRONED ✓' if beat else 'champion holds'}")
        if beat:
            champion, champ_pnl = dict(best), best_pnl
            lineage.append(dict(gen=g, pnl=best_pnl))
        elites = [p for _, p in scored[:3]] + [champion]
        pop = [mutate(elites[i % len(elites)], rng, scale=0.25 * (0.9 ** g))
               for i in range(args.pop)]

    # the only judgment that counts: unseen future, vs default AND ancestor
    d_test = run_policy(DEFAULT, net, hist, testW, mapping, args.start)
    c_test = run_policy(champion, net, hist, testW, mapping, args.start)
    print("\n=== HELD-OUT JUDGMENT (future the league never saw) ===")
    print(f"  default policy : {d_test:,.0f} gp")
    print(f"  evolved champ  : {c_test:,.0f} gp -> "
          f"{'EMERGENT EDGE ✓' if c_test > d_test else 'no real edge (evolution overfit) ✗'}")
    print(f"  champion genome: { {k: round(v, 4) for k, v in champion.items()} }")

    arena = len(hist) * len(stamps)      # how much market this crown was won on
    payload = dict(genome=champion, train_pnl=champ_pnl,
                   test_pnl=c_test, default_test_pnl=d_test,
                   emergent_edge=bool(c_test > d_test),
                   universe=args.universe, items=len(hist), bars=len(stamps),
                   arena=arena, lineage=lineage)

    # Same guard as the backtrainer: a crown won on a toy arena, or won without
    # an edge, must not replace one won on a real one. A 30-item smoke run
    # silently overwrote a full champion once — that is what this prevents.
    incumbent = None
    if os.path.exists(CHAMP):
        try:
            with open(CHAMP, encoding="utf-8") as f:
                incumbent = json.load(f)
        except (OSError, ValueError):
            incumbent = None
    if incumbent and incumbent.get("emergent_edge") and (
            not payload["emergent_edge"] or arena < (incumbent.get("arena") or 0)):
        alt = CHAMP.replace(".json", "-rejected.json")
        with open(alt, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)
        print(f"  REJECTED — incumbent champion was won on a bigger arena "
              f"({incumbent.get('items','?')} items x {incumbent.get('bars','?')} bars"
              f" vs {len(hist)}x{len(stamps)}) or this run showed no edge. "
              f"Kept it; this run -> {alt}")
        return
    with open(CHAMP, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"  champion -> {CHAMP} (plugin adoption gated on emergent_edge)")


if __name__ == "__main__":
    main()
