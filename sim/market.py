"""
GE market intelligence — RSI + momentum on live wiki price data.

The GE is a real market with a free public API (prices.runescape.wiki).
RSI(14) on the 1h series flags oversold (<30, buy candidate) and
overbought (>70, sell candidate) items — merching as another income
activity, and later live flip advice in the plugin.

  py sim/market.py                  # watchlist RSI report
  py sim/market.py --flips          # whole-market flip scanner (tax-aware)
  py sim/market.py --flips --f2p    # only items a free account can trade
"""
import json
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

API = "https://prices.runescape.wiki/api/v1/osrs"
UA = {"User-Agent": "RuneAI-plugin-sim (github.com/lordbasilaiassistant-sudo/runeai)"}

WATCHLIST = {
    13190: "Old school bond",
    4151: "Abyssal whip",
    4587: "Dragon scimitar",
    385: "Shark",
    532: "Big bones",
    561: "Nature rune",
    2: "Cannonball",
    12934: "Zulrah's scales",
}


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def fetch_mapping():
    """id -> full mapping row (name, members, limit, …)."""
    return {m["id"]: m for m in fetch(f"{API}/mapping")}


def tradeable_ids(mapping, universe="all"):
    """Which items an account of this type can actually trade.

    The wiki mapping's `members` flag is the only source of truth for this, and
    skipping it is not cosmetic: an "F2P" backtest that leaves it out fills the
    book with members items and reports a pace a free account could never hit.
    A members account trades everything, so only "f2p" narrows the universe.
    """
    if universe == "f2p":
        return {iid for iid, m in mapping.items() if not m.get("members")}
    return set(mapping)


def rsi(closes, period=14):
    if len(closes) < period + 1:
        return None
    gains, losses = [], []
    for i in range(1, len(closes)):
        d = closes[i] - closes[i - 1]
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    ag = sum(gains[:period]) / period
    al = sum(losses[:period]) / period
    for i in range(period, len(gains)):
        ag = (ag * (period - 1) + gains[i]) / period
        al = (al * (period - 1) + losses[i]) / period
    if al == 0:
        return 100.0
    return 100 - 100 / (1 + ag / al)


def analyze(item_id, name):
    try:
        ts = fetch(f"{API}/timeseries?timestep=1h&id={item_id}")["data"]
    except Exception as e:
        return f"  {name:20s} fetch failed: {e}"
    closes = [d["avgHighPrice"] for d in ts if d.get("avgHighPrice")]
    if len(closes) < 20:
        return f"  {name:20s} not enough data"
    r = rsi(closes)
    last = closes[-1]
    wk = (last - closes[max(0, len(closes) - 168)]) / closes[max(0, len(closes) - 168)] * 100
    sig = "BUY zone" if r < 30 else "SELL zone" if r > 70 else "hold"
    return f"  {name:20s} {last:>12,} gp  RSI(14) {r:5.1f}  7d {wk:+6.2f}%   {sig}"


# ---- GE tax (VERIFY exemption details vs wiki: 2% seller-side, 5M cap/item,
# sub-50gp sales and bonds exempt) ----
GE_TAX_RATE = 0.02
GE_TAX_CAP = 5_000_000
TAX_EXEMPT = {13190}


def ge_tax(sell_price: int, item_id: int = -1) -> int:
    if sell_price < 50 or item_id in TAX_EXEMPT:
        return 0
    return min(int(sell_price * GE_TAX_RATE), GE_TAX_CAP)


def flip_scan(top=20, min_price=100, min_vol_5m=10, universe="all"):
    """Whole-market flip finder: buy at insta-sell+1, sell at insta-buy-1,
    net of GE tax, throughput bounded by buy limit and real 5m volume."""
    mapping = fetch_mapping()
    allowed = tradeable_ids(mapping, universe)
    latest = fetch(f"{API}/latest")["data"]
    five = fetch(f"{API}/5m")["data"]
    rows = []
    for sid, q in latest.items():
        iid = int(sid)
        m = mapping.get(iid)
        v = five.get(sid, {})
        if not m or iid not in allowed or not q.get("high") or not q.get("low"):
            continue
        vol = (v.get("highPriceVolume", 0) + v.get("lowPriceVolume", 0))
        if q["low"] < min_price or vol < min_vol_5m:
            continue
        buy_at = q["low"] + 1
        sell_at = q["high"] - 1
        net = sell_at - ge_tax(sell_at, iid) - buy_at
        if net <= 0:
            continue
        limit = m.get("limit") or 100
        units_hr = min(limit / 4.0, vol * 12 * 0.10)  # 10% market share, 4h limit window
        rows.append(dict(name=m["name"], buy=buy_at, sell=sell_at, net=net,
                         roi=net / buy_at * 100, units_hr=units_hr,
                         gp_hr=net * units_hr, capital=buy_at * min(limit, 100)))
    rows.sort(key=lambda r: -r["gp_hr"])
    return rows[:top]


def flip_report(universe="all"):
    rows = flip_scan(universe=universe)
    who = "F2P-tradeable items only" if universe == "f2p" else "whole market"
    print(f"=== GE FLIP SCANNER — live, tax-aware (buy low+1 / sell high-1, 2% tax) — {who} ===")
    print(f"  {'item':28s} {'buy at':>10s} {'sell at':>10s} {'net/flip':>9s} {'roi%':>6s} {'est gp/hr':>11s}")
    for r in rows:
        print(f"  {r['name'][:28]:28s} {r['buy']:>10,} {r['sell']:>10,} {r['net']:>9,} "
              f"{r['roi']:>5.1f}% {r['gp_hr']:>11,.0f}")
    if rows:
        med = sorted(r['gp_hr'] for r in rows)[len(rows)//2]
        print(f"\n  median top-20 gp/hr {med:,.0f} — suggestions, not orders; margins move fast.")


if __name__ == "__main__":
    if "--flips" in sys.argv:
        flip_report(universe="f2p" if "--f2p" in sys.argv else "all")
        sys.exit(0)
    print("=== GE MARKET — live wiki prices, RSI(14) on 1h series ===")
    for iid, name in WATCHLIST.items():
        print(analyze(iid, name))
    print("\n(RSI <30 oversold = buy candidate, >70 overbought = sell candidate.")
    print(" Signals are inputs for judgment, not orders — never auto-trade.)")
