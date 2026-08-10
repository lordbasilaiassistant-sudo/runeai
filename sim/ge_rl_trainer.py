"""
GE RL trainer — RESIDUAL online learning on live market data, no RuneLite
needed. Anthony's spec: see the buy side, predict what it will sell for;
when reality arrives, reward by accuracy (exact hit = heavy reward) while
hunting the best profits.

v2 (issue #3): the net no longer predicts the price level from scratch — it
predicts the CORRECTION to the martingale ("next sell = this sell"), warm
started from the historical brain (ge-brain-hist.json) that the backtrainer
proved out of sample. Physics is not relearned every session; the live loop
only fine-tunes the correction. Structurally this means the model STARTS at
the baseline instead of climbing towards it from noise.

Loop (every 60s, default 75 minutes):
  1. Pull /latest + /5m for every item.
  2. Score LAST cycle's predictions against the actual sell (high) side:
     reward = exp(-|relative error| * 40)  -> ~1.0 for exact, ~0 for wildly off.
  3. Online SGD on the RESIDUAL (actual - martingale), same tiny MLP.
  4. Policy layer: the net's top-5 predicted-profit picks are logged, then
     graded next cycle against the best realizable picks (regret).
  5. Persist brain + a REPLAYABLE row log; final report vs two references:
     the martingale baseline and the frozen (untuned) historical brain.

  py sim/ge_rl_trainer.py --minutes 75
  py sim/ge_rl_trainer.py --replay ~/.runelite/runeai/market-history.jsonl
  py sim/ge_rl_trainer.py --replay <log> --stride 5   # ask the 5-minute question

OPEN QUESTION, measured 2026-08-10 on replayed live rows: at the 60s default
the historical correction carries no usable signal — the trust gain drives to
0.000 and the loop reproduces the martingale (x1.001 over 9,706 samples).
Whether a longer horizon works is UNRESOLVED, and the trap is worth recording:
--stride 5 first scored x0.987 with gain 1.94, which did NOT replicate one
snapshot later (x1.024, gain 0.066). A stride-5 replay of a 20-cycle log gets
only ~4 scored batches, so it is noise either way. Record hours of log, then
sweep --stride, before believing any horizon claim.

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
WARM_BRAIN = os.path.join(DATA_DIR, "ge-brain-hist.json")  # backtrainer output
HISTORY = os.path.join(DATA_DIR, "market-history.jsonl")


def fetch(path):
    req = urllib.request.Request(API + path, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def ge_tax(p):
    return 0 if p < 50 else min(int(p * 0.02), 5_000_000)


class Net:
    """Tiny MLP + per-item personality: shared 7->16 tanh body generalizes
    across items; a learned per-item bias lets frequently-traded items earn
    their OWN signature (mixed-effects style, L2-shrunk so rare items fall
    back to the general model).

    Since v2 the output is a RESIDUAL on top of the martingale, never the
    price level itself — see `residual_init()`."""

    def __init__(self, rng):
        self.W1 = rng.normal(0, 0.3, (7, 16))
        self.b1 = np.zeros(16)
        self.W2 = rng.normal(0, 0.3, (16, 1))
        self.b2 = np.zeros(1)
        self.item_bias = {}   # item id -> learned personal offset
        # Horizon trust. The historical brain learns corrections for 1h/5m bars;
        # the live loop predicts a 60s step, where only a fraction of that move
        # has happened. `gain` is the online least-squares scale applied to the
        # correction — the body proposes, the gain decides how much to believe.
        # It starts at 0 (= exactly the martingale) and can only rise if the
        # correction demonstrably reduces error, so the live loop cannot lose to
        # its own baseline the way level-prediction did.
        self.gain = 1.0
        self._gxy = self._gxx = 0.0

    def residual_init(self, scale=0.05):
        """Start the head at ~zero: the model begins AT the baseline and can
        only earn its way above it."""
        self.W2 = self.W2 * scale
        self.b2 = np.zeros_like(self.b2)
        self.item_bias = {}
        return self

    def copy(self):
        c = Net.__new__(Net)
        c.W1 = self.W1.copy()
        c.b1 = self.b1.copy()
        c.W2 = self.W2.copy()
        c.b2 = self.b2.copy()
        c.item_bias = dict(self.item_bias)
        c.gain = self.gain
        c._gxy = c._gxx = 0.0
        return c

    def forward(self, x, ids=None):
        self.x = x
        self.h = np.tanh(x @ self.W1 + self.b1)
        core = (self.h @ self.W2 + self.b2)[:, 0]
        if ids is not None:
            core = core + np.array([self.item_bias.get(i, 0.0) for i in ids])
        self.core = core
        return self.gain * core

    def train(self, x, y, ids=None, lr=0.02, learn_gain=False):
        pred = self.forward(x, ids)
        # The body always fits the FULL correction; the gain (below) decides how
        # much of it to apply. Decoupled so a distrusted body still keeps learning.
        err = (self.core - y)[:, None]               # dLoss/dCore (MSE/2)
        gW2 = self.h.T @ err / len(y)
        gb2 = err.mean(0)
        dh = err @ self.W2.T * (1 - self.h ** 2)
        gW1 = self.x.T @ dh / len(y)
        gb1 = dh.mean(0)
        if learn_gain:
            # closed-form online regression of the target on the raw correction:
            # no learning rate to tune, and it is fit only on data already scored
            self._gxy = self._gxy * 0.99 + float(np.dot(y, self.core))
            self._gxx = self._gxx * 0.99 + float(np.dot(self.core, self.core))
            if self._gxx > 1e-18:
                self.gain = float(np.clip(self._gxy / self._gxx, 0.0, 2.0))
        self.W2 -= lr * gW2
        self.b2 -= lr * gb2
        self.W1 -= lr * gW1
        self.b1 -= lr * gb1
        if ids is not None:
            for i, iid in enumerate(ids):
                b = self.item_bias.get(iid, 0.0)
                self.item_bias[iid] = b * 0.999 - 0.05 * lr * float(err[i][0]) * 40
        return float(np.mean((pred - y) ** 2))

    def dump(self):
        return dict(W1=self.W1.tolist(), b1=self.b1.tolist(),
                    W2=self.W2.tolist(), b2=self.b2.tolist(),
                    gain=self.gain,
                    item_bias={str(k): round(v, 5) for k, v in self.item_bias.items()
                               if abs(v) > 0.001})


def load_net(path, rng=None):
    """Rebuild a Net from a saved brain file (either trainer's output)."""
    with open(path, encoding="utf-8") as f:
        b = json.load(f)["net"]
    net = Net(rng if rng is not None else np.random.default_rng(0))
    net.W1 = np.array(b["W1"])
    net.b1 = np.array(b["b1"])
    net.W2 = np.array(b["W2"])
    net.b2 = np.array(b["b2"])
    net.item_bias = {int(k): v for k, v in b.get("item_bias", {}).items()}
    net.gain = float(b.get("gain", 1.0))
    return net


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


class ResidualLearner:
    """One cycle of the live loop, shared by the live run and --replay so the
    measured numbers are reproducible off the recorded rows."""

    def __init__(self, net, warm=None, lr=0.02):
        self.net = net
        self.warm = warm            # frozen reference: the historical brain, untuned
        self.lr = lr
        self.prev = {}              # id -> mid price last cycle
        self.pending = None         # (ids, X, base, mart) from last cycle
        self.picks = None
        self.n = 0
        self.net_sse = self.mart_sse = self.warm_sse = 0.0
        self.exact_hits = self.total_reward = 0.0
        self.cycles = self.regret_sum = 0

    def step(self, rows, hour):
        """rows: {item_id: (low, high, vol)} for this cycle."""
        # --- 1. score + train on last cycle's predictions ---
        if self.pending:
            ids, X, base, mart_prev = self.pending
            Xs, y, mart, ids_used, lo_map = [], [], [], [], {}
            realized = {}
            for i, iid in enumerate(ids):
                cur = rows.get(iid)
                if not cur:
                    continue
                lo0, hi0 = base[i]
                y.append(math.log(cur[1] / max(lo0, 2)))
                mart.append(mart_prev[i])
                ids_used.append(iid)
                Xs.append(X[i])
                lo_map[iid] = lo0
                realized[iid] = cur
            if y:
                Xs = np.array(Xs)
                ya = np.array(y)
                marta = np.array(mart)
                resid_target = ya - marta                    # what the net must learn
                preds = marta + self.net.forward(Xs, ids_used)
                rel_err = np.abs(np.exp(preds) - np.exp(ya)) / np.exp(ya)
                self.exact_hits += float((rel_err < 0.002).sum())
                self.total_reward += float(np.exp(-rel_err * 40).sum())
                self.net_sse += float(((preds - ya) ** 2).sum())
                self.mart_sse += float(((marta - ya) ** 2).sum())
                if self.warm is not None:
                    wpred = marta + self.warm.forward(Xs, ids_used)
                    self.warm_sse += float(((wpred - ya) ** 2).sum())
                self.n += len(ya)
                self.net.train(Xs, resid_target, ids_used, lr=self.lr, learn_gain=True)

                # --- 2. grade last cycle's profit picks (policy regret) ---
                if self.picks:
                    def prof(iid):
                        if iid not in realized or iid not in lo_map:
                            return 0
                        hi1 = realized[iid][1]
                        return hi1 - ge_tax(hi1) - lo_map[iid] - 1
                    got = sum(sorted((prof(i) for i in self.picks), reverse=True)[:5])
                    best = sum(sorted((prof(i) for i in lo_map), reverse=True)[:5])
                    self.regret_sum += max(0, best - got)

        # --- 3. make this cycle's predictions + profit picks ---
        ids, X, base, mart = [], [], [], []
        for iid, (lo, hi, vol) in rows.items():
            ids.append(iid)
            X.append(features(lo, hi, vol, self.prev.get(iid, 0), hour))
            base.append((lo, hi))
            mart.append(math.log(max(hi, 2) / max(lo, 2)))
            self.prev[iid] = (lo + hi) / 2
        if ids:
            preds = np.array(mart) + self.net.forward(np.array(X), ids)
            pred_sell = [b[0] * math.exp(p) for b, p in zip(base, preds)]
            pred_profit = [ps - ge_tax(int(ps)) - b[0] - 1
                           for ps, b in zip(pred_sell, base)]
            order = np.argsort(pred_profit)[::-1][:5]
            self.picks = [ids[i] for i in order]
            self.pending = (ids, X, base, mart)
        self.cycles += 1

    def metrics(self):
        n = max(1, self.n)
        m = dict(samples=self.n,
                 net_mse=self.net_sse / n,
                 martingale_mse=self.mart_sse / n,
                 beats_baseline=bool(self.n and self.net_sse < self.mart_sse),
                 exact_hits=self.exact_hits,
                 avg_reward=self.total_reward / n,
                 regret_per_cycle=self.regret_sum / max(1, self.cycles),
                 horizon_gain=self.net.gain,
                 residual=True)
        if self.warm is not None:
            m["frozen_hist_mse"] = self.warm_sse / n
        return m

    def line(self):
        m = self.metrics()
        frozen = (f" | frozen-hist {m['frozen_hist_mse']:.3e}"
                  if "frozen_hist_mse" in m else "")
        ratio = m['net_mse'] / m['martingale_mse'] if m['martingale_mse'] else float('nan')
        return (f"{m['samples']} samples | net MSE {m['net_mse']:.3e} "
                f"vs martingale {m['martingale_mse']:.3e}{frozen} "
                f"(x{ratio:.4f} — {'WINNING' if m['beats_baseline'] else 'still behind'}) | "
                f"gain {m['horizon_gain']:.3f} | "
                f"exact hits {m['exact_hits']:.0f} | avg reward {m['avg_reward']:.3f} | "
                f"regret/cycle {m['regret_per_cycle']:,.0f} gp")


def collect_rows(latest, five, min_price=100, min_vol=20):
    rows = {}
    for sid, q in latest.items():
        if not q.get("high") or not q.get("low"):
            continue
        v = five.get(sid, {})
        vol = (v.get("highPriceVolume") or 0) + (v.get("lowPriceVolume") or 0)
        if q["low"] < min_price or vol < min_vol:
            continue
        rows[int(sid)] = (q["low"], q["high"], vol)
    return rows


def build_learner(args):
    rng = np.random.default_rng(args.seed)
    warm_path = None if args.warm == "none" else (args.warm or WARM_BRAIN)
    warm = None
    if warm_path and os.path.exists(warm_path):
        net = load_net(warm_path, rng)
        warm = net.copy()          # frozen reference — never trained, gain 1.0
        net.gain = args.warm_gain  # start AT the baseline; earn the correction back
        src = (f"warm start from {os.path.basename(warm_path)} "
               f"(fine-tuning it, trust gain starts at {args.warm_gain})")
    else:
        net = Net(rng).residual_init()
        net.gain = args.warm_gain
        src = "cold start (no historical brain found) — residual head at ~zero"
    print(f"  brain: {src}")
    return ResidualLearner(net, warm, lr=args.lr)


def save_brain(learner, source):
    with open(BRAIN, "w", encoding="utf-8") as f:
        json.dump(dict(net=learner.net.dump(),
                       metrics=learner.metrics(),
                       warm_start=source), f)


def replay(args):
    """Re-run the exact same loop over a recorded row log — no network, and
    the numbers are reproducible instead of one-shot."""
    learner = build_learner(args)
    cycles = seen = 0
    with open(args.replay, encoding="utf-8") as f:
        for ln in f:
            try:
                rec = json.loads(ln)
            except json.JSONDecodeError:
                continue
            if not rec.get("rows"):
                continue        # pre-v2 line: count only, nothing to replay
            seen += 1
            if (seen - 1) % args.stride:
                continue        # widen the prediction horizon to stride cycles
            rows = {int(k): tuple(v) for k, v in rec["rows"].items()}
            tm = time.gmtime(rec["t"])
            learner.step(rows, tm.tm_hour + tm.tm_min / 60)
            cycles += 1
    print(f"replayed {cycles} cycles (stride {args.stride}) of {seen} recorded, "
          f"from {args.replay}")
    if not learner.n:
        print("no replayable cycles (log has no `rows` — record a live run first)")
        return
    print("\n=== REPLAY FINAL ===")
    print(" ", learner.line())
    if args.save:
        save_brain(learner, f"replay:{os.path.basename(args.replay)}")
        print(f"  brain saved -> {BRAIN}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=75)
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--lr", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--warm", default=None,
                    help="historical brain to fine-tune, or 'none' for cold start")
    ap.add_argument("--warm-gain", type=float, default=0.0,
                    help="initial trust in the borrowed correction (0 = start at "
                         "the martingale and earn it back; 1 = apply it as-is)")
    ap.add_argument("--replay", default=None,
                    help="replay a recorded market-history.jsonl instead of going live")
    ap.add_argument("--stride", type=int, default=1,
                    help="replay every Nth recorded cycle — widens the prediction "
                         "horizon, e.g. stride 5 on a 60s log asks the 5-minute question")
    ap.add_argument("--log-rows", dest="log_rows", action="store_true", default=True,
                    help="record each cycle's rows so the run can be replayed (default on)")
    ap.add_argument("--no-log-rows", dest="log_rows", action="store_false")
    ap.add_argument("--no-save", dest="save", action="store_false", default=True)
    args = ap.parse_args()

    if args.replay:
        replay(args)
        return

    learner = build_learner(args)
    deadline = time.time() + args.minutes * 60
    print(f"GE RL trainer (residual): {args.minutes}min, poll {args.interval}s. Brain -> {BRAIN}")
    while time.time() < deadline:
        t0 = time.time()
        try:
            latest = fetch("/latest")["data"]
            five = fetch("/5m")["data"]
        except Exception as e:
            print(f"  fetch failed ({e}); retrying next cycle")
            time.sleep(args.interval)
            continue

        now = time.gmtime()
        rows = collect_rows(latest, five)
        learner.step(rows, now.tm_hour + now.tm_min / 60)

        if learner.cycles % 5 == 0 and learner.n:
            print(f"  cycle {learner.cycles}: {learner.line()}")
            if args.save:
                save_brain(learner, os.path.basename(WARM_BRAIN)
                           if learner.warm is not None else "cold")
        rec = dict(t=time.time(), n=len(rows))
        if args.log_rows:
            rec["rows"] = {str(k): list(v) for k, v in rows.items()}
        with open(HISTORY, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")

        time.sleep(max(1, args.interval - (time.time() - t0)))

    print("\n=== FINAL ===")
    if learner.n:
        m = learner.metrics()
        print(" ", learner.line())
        print(f"BEATS BASELINE: {m['beats_baseline']}")
        if "frozen_hist_mse" in m:
            print(f"  (frozen historical brain, no live tuning: "
                  f"{m['frozen_hist_mse']:.6f} — the tuning is worth "
                  f"{(m['frozen_hist_mse'] - m['net_mse']):+.6f})")
        if args.save:
            save_brain(learner, os.path.basename(WARM_BRAIN)
                       if learner.warm is not None else "cold")
            print(f"brain saved -> {BRAIN} (plugin adoption gated on beats_baseline)")
        if args.log_rows:
            print(f"rows recorded -> {HISTORY} (re-measure with --replay)")
    else:
        print("no samples — API trouble?")


if __name__ == "__main__":
    main()
