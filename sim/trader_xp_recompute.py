"""
Trader xp recompute — the honest number, replayed from the event logs.

Early flipping sessions booked the FULL sell proceeds as profit: the plugin had
no cost basis for items bought before offer tracking existed, so every sell
looked like free money and Trader xp ran away. (Cost basis landed in 7d9326b,
~1h13m after geOffer logging in 8043829 — everything sold in that window is
inflated.) The event logs kept every real fill price, so the truth is
recoverable: replay the whole geOffer history, build a real per-item cost
basis out of the recorded buys, and let a sell earn profit-xp ONLY for the
units it can actually match against a recorded buy. Units with no basis earn
ZERO — that is the honest part; guessing a market price for them is what got
us here.

Same physics as the plugin: gross = qty * offer price, tax = geTax(price) per
unit (RuneAIPlugin.java:505-507), xp = profit * 0.1 on positive profit only
(RuneAIPlugin.java:528-530), level from the OSRS-shaped curve
(RuneAIPlugin.java:154-178). Writes ~/.runelite/runeai/trader.json in the
schema loadTrader() expects ({"xp":double,"lifetime":long},
RuneAIPlugin.java:907-942), backing the old one up as trader.json.bak.

  py sim/trader_xp_recompute.py --dry-run     # print, touch nothing
  py sim/trader_xp_recompute.py               # write the corrected trader.json

Local data only — never commit trader.json or the events files.
"""
import argparse
import glob
import json
import math
import os
import re
import shutil
import sys
import time
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding="utf-8")
DATA_DIR = os.path.expanduser("~/.runelite/runeai")

# ---- plugin physics, mirrored exactly -------------------------------------

# FlipService.geTax:197 — 2% of the ask, truncated, free under 50gp, 5M cap.
def ge_tax(price):
    if price < 50:
        return 0
    return int(min(price * 0.02, 5_000_000))


# RuneAIPlugin.java:154-163 — the trader curve (OSRS xp table / 4).
TRADER_XP = [0] * 100
_acc = 0
for _n in range(1, 100):
    _acc += int(math.floor(_n + 300 * math.pow(2, _n / 7.0)))
    TRADER_XP[_n] = _acc // 4


# RuneAIPlugin.java:167-178
def trader_level_for(xp):
    lvl = 1
    for l in range(2, 100):
        if xp >= TRADER_XP[l - 1]:
            lvl = l
    return lvl


XP_PER_GP = 0.1  # RuneAIPlugin.java:530

BUY_STATES = {"BUYING", "BOUGHT", "CANCELLED_BUY"}
SELL_STATES = {"SELLING", "SOLD", "CANCELLED_SELL"}

# ---- log reading ----------------------------------------------------------

TS = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$")


def epoch_nanos(t):
    """EventLog stamps Instant.now() with variable-length nanos — parse both."""
    m = TS.match(t or "")
    if not m:
        return 0
    secs = datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(
        tzinfo=timezone.utc).timestamp()
    frac = (m.group(2) or "").ljust(9, "0")[:9]
    return int(secs) * 1_000_000_000 + int(frac or 0)


def load_offers(data_dir):
    """Every geOffer line from every events file, in true chronological order."""
    files = sorted(glob.glob(os.path.join(data_dir, "events-*.jsonl")))
    rows, span = [], {}
    for fi, path in enumerate(files):
        with open(path, encoding="utf-8") as f:
            for li, line in enumerate(f):
                if '"geOffer"' not in line:
                    continue
                try:
                    e = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if e.get("e") != "geOffer":
                    continue
                ns = epoch_nanos(e.get("t"))
                rows.append((ns, fi, li, e))
                lo, hi = span.get(path, (ns, ns))
                span[path] = (min(lo, ns), max(hi, ns))
    # sort by timestamp, ties broken by file then line: within one session the
    # file order IS the order; across sessions the clock arbitrates
    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    overlaps = []
    ordered = sorted(span.items(), key=lambda kv: kv[1][0])
    for (pa, (_, ea)), (pb, (sb, _)) in zip(ordered, ordered[1:]):
        if sb < ea:
            overlaps.append((os.path.basename(pa), os.path.basename(pb)))
    return files, [r[3] for r in rows], overlaps


# ---- the replay -----------------------------------------------------------

class Replay:
    def __init__(self):
        self.slots = {}          # slot -> [item, price, cum qty, cum spent]
        self.basis = {}          # item -> [qty, total cost] — real buys only
        self.xp = 0.0
        self.lifetime = 0        # honest realized profit (losses included)
        self.naive_xp = 0.0      # the old bug: proceeds booked as pure profit
        self.offers = 0          # distinct offers seen
        self.fills = 0           # delta events that moved units
        self.bought_units = self.sold_units = 0
        self.spent = self.proceeds = 0
        self.matched_units = self.unbased_units = 0
        self.unbased_gross = 0
        self.tax_mismatch = 0
        self.per_item = {}       # item -> [profit, xp, units sold, unbased]

    def run(self, events):
        for e in events:
            slot = e.get("slot")
            item = e.get("item")
            price = e.get("price") or 0
            qty = e.get("qtySold") or 0
            spent = e.get("spent") or 0
            state = e.get("state") or ""
            if item is None or slot is None:
                continue

            if "taxCalc" in e and e["taxCalc"] != ge_tax(price) * qty:
                self.tax_mismatch += 1

            # offer identity is (slot, item, price); a qty that went BACKWARD
            # means the slot was re-used for a fresh offer at the same ask.
            # NOTE: unlike the plugin we never clear the slot on BOUGHT/SOLD —
            # the client re-emits terminal events (and re-emits them again on
            # every login), and clearing would re-book the whole offer.
            tr = self.slots.get(slot)
            if tr is None or tr[0] != item or tr[1] != price or qty < tr[2]:
                tr = [item, price, 0, 0]
                self.slots[slot] = tr
                self.offers += 1

            d_qty = qty - tr[2]
            d_spent = spent - tr[3]
            tr[2], tr[3] = qty, spent
            if d_qty <= 0:
                continue
            self.fills += 1

            if state in BUY_STATES:
                self.buy(item, d_qty, d_spent)
            elif state in SELL_STATES:
                self.sell(item, price, d_qty, d_spent)

    def buy(self, item, qty, coins):
        """Real coins really spent — the only cost basis we will ever trust."""
        b = self.basis.setdefault(item, [0, 0])
        b[0] += qty
        b[1] += coins
        self.bought_units += qty
        self.spent += coins

    def sell(self, item, price, qty, coins):
        self.sold_units += qty
        self.proceeds += coins
        unit_tax = ge_tax(price)

        b = self.basis.get(item)
        have = min(qty, b[0]) if b else 0
        if have > 0:
            avg = b[1] // b[0]          # plugin uses integer avg (line 487)
            cost = avg * have
            b[0] -= have
            b[1] -= cost
        else:
            cost = 0
        missing = qty - have
        self.matched_units += have
        self.unbased_units += missing
        self.unbased_gross += (price - unit_tax) * missing

        # only the units with a real basis are priced; the rest earn nothing
        profit = have * (price - unit_tax) - cost
        self.lifetime += profit
        gained = profit * XP_PER_GP if profit > 0 else 0.0
        self.xp += gained
        # what the pre-basis code booked: every unit, full proceeds, no cost
        self.naive_xp += max(0, qty * (price - unit_tax)) * XP_PER_GP

        row = self.per_item.setdefault(item, [0, 0.0, 0, 0])
        row[0] += profit
        row[1] += gained
        row[2] += qty
        row[3] += missing


# ---- reporting ------------------------------------------------------------

def read_trader(path):
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="print the recomputation, write nothing")
    ap.add_argument("--data-dir", default=DATA_DIR)
    ap.add_argument("--top", type=int, default=8, help="per-item rows to show")
    args = ap.parse_args()

    trader_path = os.path.join(args.data_dir, "trader.json")
    old = read_trader(trader_path)
    old_xp = float(old.get("xp") or 0)
    old_life = int(old.get("lifetime") or 0)

    files, events, overlaps = load_offers(args.data_dir)
    if not events:
        print(f"no geOffer events under {args.data_dir} — nothing to replay")
        return 1
    for a, b in overlaps:
        print(f"WARNING: {a} and {b} overlap in time — two clients logging?")

    r = Replay()
    r.run(events)
    xp = r.xp
    lvl, old_lvl = trader_level_for(xp), trader_level_for(old_xp)
    inflation = old_xp - xp

    print(f"replayed {len(events):,} geOffer events from {len(files)} log files")
    print(f"  {r.offers:,} distinct offers · {r.fills:,} fill deltas")
    print(f"  bought {r.bought_units:,} units for {r.spent:,} gp"
          f"  ·  sold {r.sold_units:,} units for {r.proceeds:,} gp (pre-tax)")
    print(f"  sold units WITH real basis {r.matched_units:,}"
          f"  ·  without (earn 0 xp) {r.unbased_units:,}"
          f"  = {r.unbased_gross:,} gp of proceeds not counted as profit")
    if r.tax_mismatch:
        print(f"  note: {r.tax_mismatch:,} events disagree with the logged taxCalc")
    print()
    print(f"  old xp      {old_xp:15,.1f}  (level {old_lvl}, lifetime {old_life:,} gp)")
    print(f"  honest xp   {xp:15,.1f}  (level {lvl}, lifetime {r.lifetime:,} gp)")
    print(f"  inflation   {inflation:15,.1f}  "
          f"({inflation / old_xp * 100:.1f}% of the old number)"
          if old_xp else f"  inflation   {inflation:15,.1f}")
    print(f"  (booking full proceeds as profit — the old bug — would give"
          f" {r.naive_xp:,.1f} xp)")

    if args.top:
        rows = sorted(r.per_item.items(), key=lambda kv: -kv[1][1])[:args.top]
        if rows:
            print("\n  top items by honest xp:")
            for item, (profit, gained, sold, unbased) in rows:
                print(f"    id {item:<7} {gained:12,.1f} xp  "
                      f"profit {profit:>12,} gp  sold {sold:>6,}"
                      f"  ({unbased:,} unbased)")

    newest = max((os.path.getmtime(p) for p in files), default=0)
    if time.time() - newest < 300:
        print("\n  WARNING: an events file was written in the last 5 minutes —"
              " a client is live.\n  It holds traderXp in memory and will"
              " overwrite trader.json on its next\n  profitable sell. Restart"
              " it to pick this up.")

    if args.dry_run:
        print(f"\ndry run — {trader_path} untouched")
        return 0

    if os.path.exists(trader_path):
        shutil.copy2(trader_path, trader_path + ".bak")
        print(f"\nbacked up -> {trader_path}.bak")
    out = dict(old)
    out["xp"] = xp
    out["lifetime"] = r.lifetime
    with open(trader_path, "w", encoding="utf-8") as f:
        f.write(json.dumps(out, separators=(",", ":")))
    print(f"wrote {trader_path}: xp {xp:,.1f} (level {lvl}), "
          f"lifetime {r.lifetime:,} gp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
