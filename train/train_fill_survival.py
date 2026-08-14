"""
Weibull AFT fill-time model, fitted by CENSORED maximum likelihood — the
honest replacement for regressing on filled offers only.

Model: T ~ Weibull(k, lambda(x)),  lambda = exp(mu + beta . x)
  x = [buySide, log10(price)/8, sin(2*pi*hour/24), cos(2*pi*hour/24)]

Likelihood per episode (t = seconds, e = 1 fill / 0 cancel):
  e * [ log k + (k-1) log t - k log lambda ] - (t/lambda)^k
The censored half contributes only survival mass -(t/lambda)^k — a cancel says
"at least this long", never "exactly this long".

Verdict (never-lie rule): 5-fold CV. Adopt only if the covariate model beats an
intercept-only Weibull on held-out log-likelihood AND Harrell's C-index > 0.52.
The shipped parameters are refit on ALL data; the verdict is measured on data
the fit never saw.

  py train/backfill_episodes.py          # first, mine the logs
  py train/train_fill_survival.py        # writes ~/.runelite/runeai/fill-survival.json
"""
import json
import math
import os
import sys

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(__file__)
SOURCES = [
    os.path.join(HERE, "episodes-backfill.jsonl"),
    os.path.expanduser("~/.runelite/runeai/episodes.jsonl"),
]
OUT = os.path.expanduser("~/.runelite/runeai/fill-survival.json")
MIN_EPISODES = 120
MIN_EVENTS = 40


def load():
    t, e, X = [], [], []
    for path in SOURCES:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for ln in f:
                try:
                    r = json.loads(ln)
                except json.JSONDecodeError:
                    continue
                secs = r.get("secs")
                if secs is None or secs <= 0:
                    continue
                # live-plugin rows carry event under "event"; both sources agree
                ev = int(r.get("event", 0))
                buy = 1.0 if r.get("buy") else 0.0
                price = max(2, int(r.get("price", 2)))
                hour = float(r.get("hour", 12))
                t.append(float(min(secs, 48 * 3600)))  # clamp: a week-old ask is not a fill-time
                e.append(ev)
                X.append([buy,
                          math.log10(price) / 8.0,
                          math.sin(2 * math.pi * hour / 24),
                          math.cos(2 * math.pi * hour / 24)])
    return np.array(t), np.array(e, dtype=float), np.array(X)


def fit(t, e, X, iters=4000, lr=0.03):
    """Adam ascent on the censored Weibull log-likelihood. Returns (mu, beta, k, ll)."""
    n, p = X.shape
    theta = np.zeros(p + 2)                     # [mu, beta..., log k]
    theta[0] = math.log(np.median(t))
    m = np.zeros_like(theta)
    v = np.zeros_like(theta)
    logt = np.log(t)
    for it in range(1, iters + 1):
        mu, beta, logk = theta[0], theta[1:-1], theta[-1]
        k = math.exp(logk)
        loglam = mu + X @ beta
        u = k * (logt - loglam)                 # log z
        z = np.exp(np.clip(u, -60, 60))         # (t/lambda)^k
        # gradients of sum ll
        dll_dloglam = k * (z - e)               # ∂ll/∂loglam per row
        g_mu = float(dll_dloglam.sum())
        g_beta = X.T @ dll_dloglam
        g_logk = float((e * (1 + u) - z * u).sum())
        g = np.concatenate(([g_mu], g_beta, [g_logk]))
        m = 0.9 * m + 0.1 * g
        v = 0.999 * v + 0.001 * g * g
        theta += lr * (m / (1 - 0.9 ** it)) / (np.sqrt(v / (1 - 0.999 ** it)) + 1e-8)
        theta[-1] = min(theta[-1], 2.0)         # k <= e^2: fills are noisy, not clockwork
    mu, beta, logk = theta[0], theta[1:-1], theta[-1]
    k = math.exp(logk)
    return mu, beta, k, loglik(t, e, X, mu, beta, k)


def loglik(t, e, X, mu, beta, k):
    loglam = mu + X @ beta
    u = k * (np.log(t) - loglam)
    z = np.exp(np.clip(u, -60, 60))
    return float((e * (math.log(k) + u - np.log(t)) - z).sum())


def cindex(t, e, X, mu, beta, k):
    """Harrell's C on held-out rows: a shorter predicted lambda should mean an
    earlier observed fill. Comparable pairs: i filled before j's observed time."""
    lam = np.exp(mu + X @ beta)
    num = den = 0
    n = len(t)
    for i in range(n):
        if not e[i]:
            continue
        for j in range(n):
            if i == j or t[i] >= t[j]:
                continue
            den += 1
            if lam[i] < lam[j]:
                num += 1
            elif lam[i] == lam[j]:
                num += 0.5
    return num / den if den else float("nan")


def main():
    t, e, X = load()
    n, events = len(t), int(e.sum())
    print(f"episodes: {n} ({events} fills, {n - events} censored)")
    if n < MIN_EPISODES or events < MIN_EVENTS:
        print(f"not enough data (need {MIN_EPISODES} episodes / {MIN_EVENTS} fills) — no artifact written")
        return

    rng = np.random.default_rng(7)
    order = rng.permutation(n)
    folds = np.array_split(order, 5)
    ll_model = ll_base = 0.0
    cs = []
    for f in folds:
        mask = np.ones(n, bool)
        mask[f] = False
        mu, beta, k, _ = fit(t[mask], e[mask], X[mask])
        mub, _, kb, _ = fit(t[mask], e[mask], np.zeros((int(mask.sum()), 1)))
        ll_model += loglik(t[f], e[f], X[f], mu, beta, k)
        ll_base += loglik(t[f], e[f], np.zeros((len(f), 1)), mub, np.zeros(1), kb)
        c = cindex(t[f], e[f], X[f], mu, beta, k)
        if not math.isnan(c):
            cs.append(c)
    c_mean = float(np.mean(cs)) if cs else float("nan")
    adopt = bool(ll_model > ll_base and c_mean > 0.52)
    print(f"5-fold held-out loglik: model {ll_model:.1f} vs intercept-only {ll_base:.1f} "
          f"({'WINS' if ll_model > ll_base else 'loses'})")
    print(f"held-out C-index: {c_mean:.3f} (0.5 = coin flip; gate 0.52)")
    print(f"VERDICT: {'ADOPT' if adopt else 'NOT ADOPTED — plugin keeps its prior'}")

    # ship the full-data refit; the verdict above is what gates it
    mu, beta, k, ll = fit(t, e, X)
    med_all = math.exp(mu) * math.log(2) ** (1 / k)
    print(f"full fit: k={k:.3f}, baseline median ~{med_all:,.0f}s, "
          f"beta={np.round(beta, 4).tolist()}")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(dict(mu=mu, k=k, beta=beta.tolist(),
                       features=["buySide", "log10(price)/8", "sin(hour)", "cos(hour)"],
                       verdict=dict(adopt=adopt, cindex=round(c_mean, 4),
                                    ll_model=round(ll_model, 2), ll_baseline=round(ll_base, 2),
                                    n=n, events=events)), f)
    print(f"-> {OUT} (adopt={adopt})")


if __name__ == "__main__":
    main()
