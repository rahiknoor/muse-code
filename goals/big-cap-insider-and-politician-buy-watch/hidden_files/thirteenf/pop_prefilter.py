"""Pop-candidate pre-filter (Bottom Finders technique #2, stage 1).
For each ticker: distance from 52-week low + volume-vs-20d-average (Yahoo chart API).
Prints candidates: within 12% of 52w low AND volume ratio >= 1.5.
Short-float verification (Finviz) happens only for these candidates via the live browser.
Usage: python3 pop_prefilter.py TICKER1,TICKER2,...
"""
import json, sys, time, urllib.request

def bars(ticker):
    now = int(time.time())
    p1 = now - 400 * 86400  # >1yr for 52w low
    url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{ticker}"
           f"?period1={p1}&period2={now}&interval=1d")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    r = d["chart"]["result"][0]
    q = r["indicators"]["quote"][0]
    out = []
    for c, v in zip(q["close"] or [], q["volume"] or []):
        if c:
            out.append((c, v or 0))
    return out

def main():
    tickers = sys.argv[1].split(",")
    cands = []
    for t in tickers:
        try:
            b = bars(t)
            if len(b) < 60:
                continue
            closes = [c for c, _ in b]
            vols = [v for _, v in b]
            low52 = min(closes[-260:])
            last = closes[-1]
            dist = 100.0 * (last - low52) / low52
            avg20 = sum(vols[-21:-1]) / 20 if len(vols) >= 21 else 0
            ratio = vols[-1] / avg20 if avg20 else 0
            flag = "CANDIDATE" if dist <= 12 and ratio >= 1.5 else ""
            if flag:
                cands.append((t, dist, ratio, low52, last))
            print(f"{t:<8} {dist:6.1f}% above 52w low  vol {ratio:4.1f}x  {flag}")
            time.sleep(0.5)
        except Exception as e:
            print(f"{t:<8} ERROR {e}")
    print()
    if cands:
        print("POP CANDIDATES (near support + heavy volume):")
        for t, dist, ratio, low, last in cands:
            print(f"  {t}: {dist:.1f}% above 52w low (${low:.2f}), vol {ratio:.1f}x, last ${last:.2f}")
    else:
        print("No pop candidates this week.")

main()
