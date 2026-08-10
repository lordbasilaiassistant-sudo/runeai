"""
GE RL trainer — learns to predict sell prices from live market data,
no RuneLite needed. Anthony's spec: see the buy side, predict what it
will sell for; when reality arrives, reward by accuracy (exact hit =
heavy reward) while hunting the best profits.

Loop (every 60s, default 75 minutes):
  1. Pull /latest + /5m for every item.
  2. Score LAST cycle's predictions against the actual sell (high) side:
     reward = exp(-|relative error| * 40)  -> ~1.0 for exact, ~0 for wildly off.
  3. Online SGD update of a small MLP (numpy): features -> predicted sell.
  4. Policy layer: the net's top-5 predicted-profit picks are logged, then
     graded next cycle against the best realizable picks (regret).
  5. Persist brain + history; final report vs the martingale baseline.

  py sim/ge_rl_trainer.py --minutes 75

Adoption gate (never-lie rule): the plugin only ever uses this brain if the
final report shows it BEATS the martingale baseline out of sample.
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

API = "https://prices.runescape.wiki/api/v1/osrs"
UA = {"User-Agent": "RuneAI GE trainer (github.com/lordbasilaiassistant-sudo/runeai)"}
DATA_DIR = os.path.expanduser("~/.runelite/runeai")
BRAIN = os.path.join(DATA_DIR, "ge-brain.json")
HISTORY = os.path.join(DATA_DIR, "market-history.jsonl")


def fetch(path):
    req = urllib.request.Request(API + path, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def ge_tax(p):
    return 0 if p < 50 else min(int(p * 0.02), 5_000_000)


class Net:
    """Tiny MLP: 7 features -> 16 tanh -> predicted log(sell/buy) ratio."""

    def __init__(self, rng):
        self.W1 = rng.normal(0, 0.3, (7, 16))
        self.b1 = np.zeros(16)
        self.W2 = rng.normal(0, 0.3, (16, 1))
        self.b2 = np.zeros(1)

    def forward(self, x):
        self.x = x
        self.h = np.tanh(x @ self.W1 + self.b1)
        return (self.h @ self.W2 + self.b2)[:, 0]

    def train(self, x, y, lr=0.02):
        pred = self.forward(x)
        err = (pred - y)[:, None]                    # dLoss/dOut (MSE/2)
        gW2 = self.h.T @ err / len(y)
        gb2 = err.mean(0)
        dh = err @ self.W2.T * (1 - self.h ** 2)
        gW1 = self.x.T @ dh / len(y)
        gb1 = dh.mean(0)
        self.W2 -= lr * gW2
        self.b2 -= lr * gb2
        self.W1 -= lr * gW1
        self.b1 -= lr * gb1
        return float(np.mean((pred - y) ** 2))

    def dump(self):
        return dict(W1=self.W1.tolist(), b1=self.b1.tolist(),
                    W2=self.W2.tolist(), b2=self.b2.tolist())


def features(low, high, vol, prev_mid, hour):
    spread = max(high - low, 1)
    mom = (low + high) / 2 / prev_mid - 1 if prev_mid else 0.0
    return [
        math.log10(max(low, 2)) / 8,
        math.log10(spread) / 8,
        math.log10(max(vol, 1)) / 6,
        max(-0.2, min(0.2, mom)) * 5,
        math.sin(2 * math.pi * hour / 24),
        math.cos(2 * math.pi * hour / 24),
        1.0,
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=75)
    ap.add_argument("--interval", type=int, default=60)
    args = ap.parse_args()

    rng = np.random.default_rng(7)
    net = Net(rng)
    deadline = time.time() + args.minutes * 60
    prev = {}          # id -> (low, high, vol, mid)
    pending = None     # (ids, X, base_high) from last cycle
    picks = None       # last cycle's top-5 predicted-profit item ids
    n_train = mart_sse = net_sse = 0
    exact_hits = total_reward = 0.0
    cycles = regret_sum = 0

    print(f"GE RL trainer: {args.minutes}min, poll {args.interval}s. Brain -> {BRAIN}")
    while time.time() < deadline:
        t0 = time.time()
        try:
            latest = fetch("/latest")["data"]
            five = fetch("/5m")["data"]
        except Exception as e:
            print(f"  fetch failed ({e}); retrying next cycle")
            time.sleep(args.interval)
            continue

        hour = time.gmtime().tm_hour + time.gmtime().tm_min / 60
        rows = {}
        for sid, q in latest.items():
            if not q.get("high") or not q.get("low"):
                continue
            v = five.get(sid, {})
            vol = (v.get("highPriceVolume") or 0) + (v.get("lowPriceVolume") or 0)
            if q["low"] < 100 or vol < 20:
                continue
            rows[int(sid)] = (q["low"], q["high"], vol)

        # 1. score + train on last cycle's predictions
        if pending:
            ids, X, base_high = pending
            y, Xs, mart = [], [], []
            realized = {}
            for i, iid in enumerate(ids):
                cur = rows.get(iid)
                if not cur:
                    continue
                actual_ratio = math.log(cur[1] / max(base_high[i][0], 2))
                y.append(actual_ratio)
                Xs.append(X[i])
                mart.append(math.log(base_high[i][1] / max(base_high[i][0], 2)))
                realized[iid] = cur
            if y:
                Xs = np.array(Xs)
                ya = np.array(y)
                preds = net.forward(Xs)
                rel_err = np.abs(np.exp(preds) - np.exp(ya)) / np.exp(ya)
                reward = np.exp(-rel_err * 40)          # exact -> ~1, off -> ~0
                exact_hits += float((rel_err < 0.002).sum())
                total_reward += float(reward.sum())
                net_sse += float(((preds - ya) ** 2).sum())
                mart_sse += float(((np.array(mart) - ya) ** 2).sum())
                n_train += len(ya)
                net.train(Xs, ya)

                # 2. grade last cycle's profit picks (policy regret)
                if picks:
                    def prof(iid):
                        if iid not in realized:
                            return 0
                        lo0 = dict(zip(ids, [b[0] for b in base_high])).get(iid, 0)
                        hi1 = realized[iid][1]
                        return hi1 - ge_tax(hi1) - lo0 - 1
                    got = sum(sorted((prof(i) for i in picks), reverse=True)[:5])
                    best = sum(sorted((prof(i) for i in realized), reverse=True)[:5])
                    regret_sum += max(0, best - got)

        # 3. make this cycle's predictions + profit picks
        ids, X, base_high = [], [], []
        for iid, (lo, hi, vol) in rows.items():
            pm = prev.get(iid, (0, 0, 0, 0))[3]
            ids.append(iid)
            X.append(features(lo, hi, vol, pm, hour))
            base_high.append((lo, hi))
            prev[iid] = (lo, hi, vol, (lo + hi) / 2)
        if ids:
            preds = net.forward(np.array(X))
            pred_sell = [b[0] * math.exp(p) for b, p in zip(base_high, preds)]
            pred_profit = [ps - ge_tax(int(ps)) - b[0] - 1
                           for ps, b in zip(pred_sell, base_high)]
            order = np.argsort(pred_profit)[::-1][:5]
            picks = [ids[i] for i in order]
            pending = (ids, X, base_high)

        cycles += 1
        if cycles % 5 == 0 and n_train:
            print(f"  cycle {cycles}: {n_train} samples | net MSE {net_sse/n_train:.5f} "
                  f"vs martingale {mart_sse/n_train:.5f} "
                  f"({'WINNING' if net_sse < mart_sse else 'still behind'}) | "
                  f"exact hits {exact_hits:.0f} | avg reward {total_reward/max(1,n_train):.3f} | "
                  f"regret/cycle {regret_sum/max(1,cycles):,.0f} gp")
            with open(BRAIN, "w", encoding="utf-8") as f:
                json.dump(dict(net=net.dump(),
                               metrics=dict(samples=n_train, net_mse=net_sse / n_train,
                                            martingale_mse=mart_sse / n_train,
                                            beats_baseline=bool(net_sse < mart_sse),
                                            exact_hits=exact_hits,
                                            avg_reward=total_reward / max(1, n_train))), f)
        with open(HISTORY, "a", encoding="utf-8") as f:
            f.write(json.dumps(dict(t=time.time(),
                                    n=len(rows))) + "\n")

        time.sleep(max(1, args.interval - (time.time() - t0)))

    print("\n=== FINAL ===")
    if n_train:
        print(f"samples {n_train} | net MSE {net_sse/n_train:.5f} vs martingale {mart_sse/n_train:.5f}")
        print(f"BEATS BASELINE: {net_sse < mart_sse} | exact hits {exact_hits:.0f} "
              f"| avg reward {total_reward/max(1,n_train):.3f}")
        print(f"brain saved -> {BRAIN} (plugin adoption gated on beats_baseline)")
    else:
        print("no samples — API trouble?")


if __name__ == "__main__":
    main()
