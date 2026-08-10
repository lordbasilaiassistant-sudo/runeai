"""
Whale-trap report — turns the raw anomaly log into the patience-order playbook.

anomaly_watch.py records every "someone massively overpaid" print. This reads
that log and answers the three questions the strategy actually needs:

  1. WHICH items catch whales, and how often (strike frequency per item).
  2. HOW BIG the overpay is when they strike (what the lottery ticket pays).
  3. WHERE the strikes land — whales type round numbers, so a sell order parked
     just UNDER a round number is the one their offer crosses first.

Writes a compact board the plugin reads at ~/.runelite/runeai/trap-board.json.
Nothing here touches the repo tree: the log and the board are both local data.

  py sim/whale_trap_report.py            # analyse + write the board
  py sim/whale_trap_report.py --top 40   # longer board
"""
import argparse
import json
import os
import sys
import time
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
LOG = os.path.expanduser("~/.runelite/runeai/anomalies.jsonl")
BOARD = os.path.expanduser("~/.runelite/runeai/trap-board.json")

# the steps a human types into the price box, coarsest first
ROUND_STEPS = [10_000_000, 5_000_000, 1_000_000, 500_000, 250_000, 100_000,
               50_000, 25_000, 10_000, 5_000, 1_000]

# name fragments that mark the corridors the log keeps hitting
CORRIDORS = [
    ("league cosmetics", ("Trailblazer", "Twisted", "Shattered", "Raging", "Ectoplasm")),
    ("holiday/event cosmetics", ("Villager", "Woven", "Desert", "Elven", "Fremennik",
                                 "Yellow robe", "Purple hat", "Bunny", "Santa")),
    ("poisoned weapons", ("(p)", "(p+)", "(p++)")),
    ("flatpacks", ("flatpack",)),
]


def corridor(name):
    for label, frags in CORRIDORS:
        if any(fr in name for fr in frags):
            return label
    return "other"


def snap_step(price):
    """Two-significant-figure tick — the granularity humans type prices at."""
    mag = 10 ** (len(str(int(price))) - 1)
    return max(1, mag // 10)


def snap_down(price):
    """Largest human-looking number <= price (multiple of its half-decade tick)."""
    step = snap_step(price)
    return max(1, (int(price) // step) * step)


def roundness(price):
    """Coarsest step this price is an exact multiple of (0 = not round)."""
    for step in ROUND_STEPS:
        if price % step == 0:
            return step
    return 0


def trailing_zeros(price):
    n, z = int(price), 0
    while n % 10 == 0 and n > 0:
        n //= 10
        z += 1
    return z


def median(v):
    s = sorted(v)
    return s[len(s) // 2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=25, help="rows written to the board")
    ap.add_argument("--under", type=float, default=0.01,
                    help="fraction below the round number to park the sell order")
    ap.add_argument("--log", default=LOG)
    ap.add_argument("--out", default=BOARD)
    args = ap.parse_args()

    if not os.path.exists(args.log):
        print(f"no anomaly log at {args.log} — run sim/anomaly_watch.py first")
        return 1

    prints, seen = [], set()
    with open(args.log, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            key = (e["id"], e["printTime"])
            if key in seen:
                continue
            seen.add(key)
            e["overpay"] = e["paid"] - e["real"]
            prints.append(e)

    if not prints:
        print("anomaly log is empty")
        return 1

    total_overpay = sum(p["overpay"] for p in prints)
    span_h = (max(p["printTime"] for p in prints)
              - min(p["printTime"] for p in prints)) / 3600.0
    print(f"{len(prints)} prints · {total_overpay:,} gp total overpay · "
          f"{span_h / 24:.1f} days of tape · median overpay {median([p['overpay'] for p in prints]):,} gp")

    # ---- where do the strikes land? whales type round numbers ----
    buckets = defaultdict(int)
    for p in prints:
        buckets[roundness(p["paid"])] += 1
    print("\nROUND-NUMBER CLUSTERING (exact multiples of):")
    for step in ROUND_STEPS + [0]:
        n = buckets.get(step, 0)
        if n:
            print(f"  {('not round' if step == 0 else f'{step:,}'):>12}  {n:>3}  "
                  f"{n * 100 / len(prints):>5.1f}%  {'#' * n}")
    non_round = buckets.get(0, 0)
    print(f"  -> {len(prints) - non_round}/{len(prints)} "
          f"({(len(prints) - non_round) * 100 / len(prints):.0f}%) land on a round number")

    zeros = defaultdict(int)
    for p in prints:
        zeros[trailing_zeros(p["paid"])] += 1
    print("  trailing zeros on the paid price: "
          + " · ".join(f"{z}:{n}" for z, n in sorted(zeros.items())))

    # the near-misses matter as much: 99,000 / 29,999 / 19,970 are humans aiming
    # at a round number and shaving it, which is exactly the ask we want to be under
    near = sum(1 for p in prints
               if 0 < (snap_down(p["paid"]) + snap_step(p["paid"]) - p["paid"])
               <= snap_step(p["paid"]) * 0.1)
    print(f"  {near}/{len(prints)} sit within 10% UNDER the next tick up "
          f"(the shaved-round-number pattern)")
    tops = defaultdict(int)
    for p in prints:
        tops[str(p["paid"])[:2]] += 1
    print("  most common leading 2 digits: "
          + " · ".join(f"{d}xx({n})" for d, n in sorted(tops.items(), key=lambda kv: -kv[1])[:8]))

    # ---- which corridors ----
    corr = defaultdict(lambda: [0, 0])
    for p in prints:
        c = corr[corridor(p["name"])]
        c[0] += 1
        c[1] += p["overpay"]
    print("\nCORRIDORS:")
    for name, (n, gp) in sorted(corr.items(), key=lambda kv: -kv[1][1]):
        print(f"  {name:<26}{n:>3} prints  {gp:>12,} gp  ({gp * 100 / total_overpay:.0f}%)")

    # ---- per item ----
    by_item = defaultdict(list)
    for p in prints:
        by_item[p["id"]].append(p)

    now = time.time()
    rows = []
    for iid, ps in by_item.items():
        paid = [p["paid"] for p in ps]
        real = median([p["real"] for p in ps])
        strike = median(paid)
        # Park the ask just UNDER the observed strike, snapped down to a human
        # tick. Two reasons, both from the tape above: a repeat of the same print
        # crosses it, and sitting a shade below the number everyone else types
        # wins price priority when the whale finally shows up.
        list_at = max(real + 1, snap_down(strike * (1 - args.under)))
        rows.append({
            "id": iid,
            "name": ps[0]["name"],
            "strikes": len(ps),
            "lastPrint": max(p["printTime"] for p in ps),
            "ageDays": round((now - max(p["printTime"] for p in ps)) / 86400.0, 1),
            "real": real,
            "medPaid": strike,
            "maxPaid": max(paid),
            "medOverpay": median([p["overpay"] for p in ps]),
            "listAt": list_at,
            "corridor": corridor(ps[0]["name"]),
            # payoff per gp risked — with no repeat strikes in the tape this is the
            # only measured signal; frequency is a CORRIDOR prior, not per-item
            "payoffX": round(strike / max(1, real), 1),
        })

    rows.sort(key=lambda r: -r["payoffX"])
    print(f"\nTOP {min(args.top, len(rows))} PATIENCE-ORDER TICKETS (payoff per gp risked):")
    print(f"{'item':<32}{'buy@':>8}{'list@':>10}{'x':>7}{'age':>7}  corridor")
    for r in rows[:args.top]:
        print(f"{r['name'][:31]:<32}{r['real']:>8,}{r['listAt']:>10,}"
              f"{r['payoffX']:>6.0f}x{r['ageDays']:>6.0f}d  {r['corridor']}")

    repeat = [r for r in rows if r["strikes"] > 1]
    print(f"\nrepeat-strike items: {len(repeat)}/{len(rows)}"
          + ("  — every print in the tape is a DIFFERENT item, so per-item strike"
             "\n  frequency is unmeasurable here: the frequency signal lives at the"
             "\n  corridor level, and the per-item signal is payoff per gp risked."
             if not repeat else ": " + ", ".join(r["name"] for r in repeat[:6])))

    board = {
        "generated": int(now),
        "prints": len(prints),
        "totalOverpay": total_overpay,
        "tapeDays": round(span_h / 24, 2),
        "roundPct": round((len(prints) - non_round) * 100 / len(prints)),
        "corridors": {k: {"prints": v[0], "overpay": v[1]} for k, v in corr.items()},
        "items": rows[:args.top],
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(board, f, indent=1)
    print(f"\nboard -> {args.out} ({len(board['items'])} tickets)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
