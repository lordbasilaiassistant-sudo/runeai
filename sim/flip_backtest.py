"""
Walk-forward bankroll backtest — Anthony's question: how fast does the
MODEL turn 10k into 10M on real historical data, with its own P&L?

Replays the last ~2 weeks of hourly bars. Each hour the trained brain
(ge-brain-hist.json) predicts every liquid item's next-hour sell price,
picks its top-3 flips (3 F2P slots), buys at this bar's avgLow+1 and
sells at NEXT bar's actual avgHigh-1 minus 2% tax — bounded by buy
limits (per 4h), 10% of real bar volume, and available capital.
A no-brain baseline (raw spread ranking) runs the same gauntlet.

HONESTY BOX: fills are idealized (you get the bar's average prices,
always fill within the hour). Treat results as an upper bound on pace,
not a promise. Past data, no game updates, no competition from you.

  py sim/flip_backtest.py --start 10000
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

API = "https://prices.runescape.wiki/api/v1/osrs"
UA = {"User-Agent": "RuneAI backtest (github.com/lordbasilaiassistant-sudo/runeai)"}
BRAIN = os.path.expanduser("~/.runelite/runeai/ge-brain-hist.json")


def fetch(path):
    req = urllib.request.Request(API + path, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def load_brain():
    b = json.load(open(BRAIN, encoding="utf-8"))["net"]
    net = Net(np.random.default_rng(0))
    net.W1 = np.array(b["W1"])
    net.b1 = np.array(b["b1"])
    net.W2 = np.array(b["W2"])
    net.b2 = np.array(b["b2"])
    net.item_bias = {int(k): v for k, v in b.get("item_bias", {}).items()}
    return net


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", type=int, default=10_000)
    ap.add_argument("--items", type=int, default=300)
    ap.add_argument("--slots", type=int, default=3)
    args = ap.parse_args()

    net = load_brain()
    print("selecting liquid items + limits…")
    five = fetch("/5m")["data"]
    vols = sorted(((v.get("highPriceVolume", 0) or 0) + (v.get("lowPriceVolume", 0) or 0), int(k))
                  for k, v in five.items())
    ids = [iid for _, iid in vols[::-1][:args.items]]
    mapping = {m["id"]: (m.get("limit") or 100) for m in fetch("/mapping")}

    print(f"pulling 1h history for {len(ids)} items…")
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
    print(f"{len(hist)} items, {len(stamps)} hourly bars "
          f"({(stamps[-1]-stamps[0])/86400:.1f} days of history)\n")

    def run_policy(use_net):
        cap = float(args.start)
        curve = [cap]
        bought_window = defaultdict(list)  # item -> [(t, units)] for 4h limits
        milestones = {}
        for ti in range(len(stamps) - 1):
            t, tn = stamps[ti], stamps[ti + 1]
            cands = []
            for iid, h in hist.items():
                r, rn = h.get(t), h.get(tn)
                if not r or not rn:
                    continue
                lo, hi = r["avgLowPrice"], r["avgHighPrice"]
                vol = (r.get("highPriceVolume") or 0) + (r.get("lowPriceVolume") or 0)
                if vol < 20 or lo + 1 > cap:
                    continue
                prev = h.get(stamps[ti - 1]) if ti else None
                pm = (prev["avgLowPrice"] + prev["avgHighPrice"]) / 2 if prev else 0
                hour = time.gmtime(t).tm_hour
                x = features(lo, hi, vol, pm, hour)
                mart = math.log(max(hi, 2) / max(lo, 2))
                if use_net:
                    pred = mart + net.forward(np.array([x]), [iid])[0]
                else:
                    pred = mart
                pred_sell = lo * math.exp(pred)
                pred_net = pred_sell - ge_tax(int(pred_sell)) - (lo + 1)
                if pred_net <= 0:
                    continue
                cands.append((pred_net / (lo + 1), iid, lo, vol, rn))
            cands.sort(reverse=True)

            spend_left = cap
            for score, iid, lo, vol, rn in cands[:args.slots]:
                recent = sum(u for tt, u in bought_window[iid] if t - tt < 4 * 3600)
                limit_left = max(0, mapping.get(iid, 100) - recent)
                units = int(min(limit_left, vol * 0.10,
                                (spend_left / args.slots) / (lo + 1)))
                if units < 1:
                    continue
                buy_cost = units * (lo + 1)
                sell_px = rn["avgHighPrice"] - 1
                proceeds = units * (sell_px - ge_tax(sell_px))
                cap += proceeds - buy_cost
                spend_left -= buy_cost
                bought_window[iid].append((t, units))
            curve.append(cap)
            for m in (100_000, 1_000_000, 10_000_000):
                if cap >= m and m not in milestones:
                    milestones[m] = (t - stamps[0]) / 86400
        return cap, curve, milestones

    for label, use_net in (("MODEL (trained brain)", True), ("baseline (raw spread)", False)):
        cap, curve, ms = run_policy(use_net)
        days = (stamps[-1] - stamps[0]) / 86400
        growth = (cap / args.start) ** (1 / max(days, 0.1))
        print(f"=== {label} ===")
        print(f"  {args.start:,} gp -> {cap:,.0f} gp over {days:.1f} days "
              f"({(cap/args.start - 1)*100:,.0f}% total, x{growth:.2f}/day)")
        for m, d in sorted(ms.items()):
            print(f"  reached {m:,} gp on day {d:.1f}")
        if 10_000_000 not in ms and cap > args.start and growth > 1:
            need = math.log(10_000_000 / args.start) / math.log(growth)
            print(f"  10M projection at this rate: ~{need:.0f} days")
        print()


if __name__ == "__main__":
    main()
