"""
GE market intelligence — RSI + momentum on live wiki price data.

The GE is a real market with a free public API (prices.runescape.wiki).
RSI(14) on the 1h series flags oversold (<30, buy candidate) and
overbought (>70, sell candidate) items — merching as another income
activity, and later live flip advice in the plugin.

  py sim/market.py            # watchlist report
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


if __name__ == "__main__":
    print("=== GE MARKET — live wiki prices, RSI(14) on 1h series ===")
    for iid, name in WATCHLIST.items():
        print(analyze(iid, name))
    print("\n(RSI <30 oversold = buy candidate, >70 overbought = sell candidate.")
    print(" Signals are inputs for judgment, not orders — never auto-trade.)")
