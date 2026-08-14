"""
Mine the recorded event streams into fill-time EPISODES — including the ones
that never filled.

The old fill model trained on completed offers only. The logs hold ~4 cancels
for every fill, and a cancel is a measurement ("this long was not enough"), not
a missing value. This script reconstructs one row per offer outcome:

  {"secs": s, "event": 1|0, "buy": 1|0, "price": p, "hour": h, "offline": b}

  event=1  the offer filled after s seconds (fillSecs the plugin measured)
  event=0  the offer was cancelled after s seconds — right-censored

Cancels only count when the offer's PLACEMENT was seen in the same event file:
an age computed from a resumed offer's first re-fire would understate the wait
and bias the model fast — the exact failure this data exists to fix.

  py train/backfill_episodes.py            # writes train/episodes-backfill.jsonl
"""
import glob
import json
import os
import sys
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding="utf-8")

DATA_DIR = os.path.expanduser("~/.runelite/runeai")
OUT = os.path.join(os.path.dirname(__file__), "episodes-backfill.jsonl")

LIVE = {"BUYING", "SELLING"}
FILLED = {"BOUGHT", "SOLD"}
CANCELLED = {"CANCELLED_BUY", "CANCELLED_SELL"}
BUY_SIDE = {"BUYING", "BOUGHT", "CANCELLED_BUY"}


def parse_t(iso):
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()


def main():
    files = sorted(glob.glob(os.path.join(DATA_DIR, "events-*.jsonl")))
    rows, fills, cancels, skipped = [], 0, 0, 0
    for path in files:
        placed = {}  # slot -> (item, price, t0)
        try:
            fh = open(path, encoding="utf-8")
        except OSError:
            continue
        with fh:
            for ln in fh:
                try:
                    r = json.loads(ln)
                except json.JSONDecodeError:
                    continue
                if r.get("e") != "geOffer":
                    continue
                slot, item, price = r.get("slot"), r.get("item"), r.get("price")
                state = r.get("state", "")
                try:
                    t = parse_t(r["t"])
                except (KeyError, ValueError):
                    continue
                hour = datetime.fromtimestamp(t, tz=timezone.utc).hour
                buy = 1 if state in BUY_SIDE else 0
                if state in LIVE:
                    cur = placed.get(slot)
                    if not cur or cur[0] != item or cur[1] != price:
                        placed[slot] = (item, price, t)
                elif state in FILLED:
                    # the plugin already measured the true duration, resumed
                    # offers included — trust its clock over file arithmetic
                    secs = r.get("fillSecs")
                    if secs is not None and secs >= 0:
                        rows.append(dict(secs=max(1, int(secs)), event=1, buy=buy,
                                         price=int(price or 1), hour=hour,
                                         offline=bool(r.get("offline"))))
                        fills += 1
                    else:
                        skipped += 1
                    placed.pop(slot, None)
                elif state in CANCELLED:
                    cur = placed.pop(slot, None)
                    if cur and cur[0] == item and cur[1] == price and t > cur[2]:
                        rows.append(dict(secs=max(1, int(t - cur[2])), event=0, buy=buy,
                                         price=int(price or 1), hour=hour,
                                         offline=bool(r.get("offline"))))
                        cancels += 1
                    else:
                        skipped += 1  # placement not seen in this file: age unknowable

    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    print(f"episodes: {len(rows)} ({fills} fills, {cancels} censored cancels, "
          f"{skipped} skipped as unknowable) from {len(files)} event files")
    print(f"-> {OUT}")


if __name__ == "__main__":
    main()
