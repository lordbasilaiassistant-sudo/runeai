"""
Anomaly watcher — logs every fresh "someone massively overpaid" print on
the GE. These are the blue moons: impatient whales, fat fingers, RWT-ish
flows smashing standing high-ball sells. The log becomes the dataset for
the patience-order strategy: WHICH items catch whales, how often, how big.

  py sim/anomaly_watch.py --minutes 480
Log -> ~/.runelite/runeai/anomalies.jsonl  (dedup by item+print time)
"""
import argparse
import json
import os
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
API = "https://prices.runescape.wiki/api/v1/osrs"
UA = {"User-Agent": "RuneAI anomaly watch (github.com/lordbasilaiassistant-sudo/runeai)"}
LOG = os.path.expanduser("~/.runelite/runeai/anomalies.jsonl")


def fetch(p):
    r = urllib.request.Request(API + p, headers=UA)
    with urllib.request.urlopen(r, timeout=20) as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=480)
    ap.add_argument("--ratio", type=float, default=5.0)
    ap.add_argument("--min-overpay", type=int, default=10_000)
    args = ap.parse_args()

    mapping = {m["id"]: m["name"] for m in fetch("/mapping")}
    seen = set()
    if os.path.exists(LOG):
        with open(LOG, encoding="utf-8") as f:
            for line in f:
                try:
                    e = json.loads(line)
                    seen.add((e["id"], e["printTime"]))
                except json.JSONDecodeError:
                    pass

    deadline = time.time() + args.minutes * 60
    print(f"watching for {args.ratio}x+ overpays ({args.minutes}min) -> {LOG}")
    n_new = 0
    while time.time() < deadline:
        t0 = time.time()
        try:
            latest = fetch("/latest")["data"]
        except Exception as e:
            print(f"fetch failed ({e})")
            time.sleep(60)
            continue
        for sid, q in latest.items():
            hi, lo, ht = q.get("high"), q.get("low"), q.get("highTime")
            if not hi or not lo or lo < 50 or not ht:
                continue
            if hi >= lo * args.ratio and hi - lo >= args.min_overpay:
                iid = int(sid)
                key = (iid, ht)
                if key in seen:
                    continue
                seen.add(key)
                n_new += 1
                rec = dict(t=time.time(), id=iid, name=mapping.get(iid, "?"),
                           paid=hi, real=lo, ratio=round(hi / lo, 1), printTime=ht)
                with open(LOG, "a", encoding="utf-8") as f:
                    f.write(json.dumps(rec) + "\n")
                print(f"  ANOMALY {rec['ratio']}x: {rec['name']} paid {hi:,} (real {lo:,})")
        time.sleep(max(5, 60 - (time.time() - t0)))
    print(f"done: {n_new} new anomaly prints logged")


if __name__ == "__main__":
    main()
