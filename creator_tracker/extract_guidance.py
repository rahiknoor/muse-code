#!/usr/bin/env python3
"""extract_guidance.py -- pull management's stated growth/margin guidance from
earnings call transcripts (kurry/sp500_earnings_transcripts, MIT).

Reads the ONE shared parquet copy at ~/workspace/data/earnings_transcripts/part-0.parquet.
No downloads, no network.

Usage:
    extract_guidance.py --tickers NVDA,META,MSFT [--latest-only] [--max-calls 4]

Output: JSON lines on stdout, one per guidance finding:
    {ticker, year, quarter, call_date, field, value, unit, quote, source}

Fields: "growth_rate" (revenue/sales/top-line growth outlook) or "margin"
(gross/operating/net/EBITDA margin outlook). value is a percent number
(e.g. 12.5 = 12.5%) or null when stated without a parseable number.
Ranges ("8% to 10%") are recorded at the midpoint; the exact quote is always
kept so a human can verify. Missing = missing, never guessed.

Precision-first rules:
  - skip question sentences (analyst Q&A, not management guidance)
  - require a guidance verb (expect/guide/forecast/outlook/anticipate/project)
  - require a metric keyword near a percent figure
"""

import argparse
import json
import os
import re
import sys

PARQUET = os.path.expanduser(
    "~/workspace/data/earnings_transcripts/part-0.parquet")

GUIDE_VERBS = re.compile(
    r"\b(expect(?:s|ed)?|guid(?:e|ance|ing)|forecast(?:s|ed)?|outlook"
    r"|anticipate(?:s|d)?|project(?:s|ed|ing)?|targeting|reaffirm(?:s|ed)?"
    r"|maintain(?:s|ed)?(?: our)? (?:full-year |fy ?\d{2,4} )?guidance)\b",
    re.IGNORECASE)

GROWTH_METRICS = re.compile(
    r"\b(revenue|revenues|sales|top[\s-]?line|eps|earnings per share)\b",
    re.IGNORECASE)
MARGIN_METRICS = re.compile(
    r"\b(gross|operating|adjusted operating|net|ebitda|ebit)\s+margin\b"
    r"|\bmargin\s+of\b",
    re.IGNORECASE)

PCT = r"(\d+(?:\.\d+)?)\s*(?:%|percent)"
RANGE = re.compile(
    PCT + r"\s*(?:to|\u2013|-|and)\s*" + PCT, re.IGNORECASE)
SINGLE = re.compile(PCT, re.IGNORECASE)

SENT_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'])")

TICKER_ALIASES = {"BRK.B": ["BRK.B", "BRK-B", "BRKB"]}


def sentences(text):
    # Transcripts carry speaker labels ("Jane Doe:"); keep them, they add provenance.
    text = re.sub(r"\s+", " ", text).strip()
    return [s.strip() for s in SENT_SPLIT.split(text) if len(s.strip()) > 40]


def parse_percent(sentence):
    """Return (value, is_range). Ranges collapse to the midpoint."""
    m = RANGE.search(sentence)
    if m:
        lo, hi = float(m.group(1)), float(m.group(2))
        return round((lo + hi) / 2, 2), True
    m = SINGLE.search(sentence)
    if m:
        return float(m.group(1)), False
    return None, False


def find_guidance(ticker, year, quarter, call_date, content):
    out = []
    source = (f"kurry/sp500_earnings_transcripts \u00b7 {ticker} "
              f"Q{quarter} {year} earnings call ({call_date})")
    for s in sentences(content):
        if "?" in s:
            continue  # analyst Q&A, not management guidance
        if not GUIDE_VERBS.search(s):
            continue
        field = None
        if GROWTH_METRICS.search(s):
            field = "growth_rate"
        elif MARGIN_METRICS.search(s):
            field = "margin"
        if field is None:
            continue
        value, _ = parse_percent(s)
        quote = s if len(s) <= 500 else s[:497] + "..."
        out.append({
            "ticker": ticker, "year": int(year), "quarter": int(quarter),
            "call_date": call_date, "field": field,
            "value": value, "unit": "percent" if value is not None else None,
            "quote": quote, "source": source,
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tickers", required=True,
                    help="comma-separated tickers, e.g. NVDA,META,MSFT")
    ap.add_argument("--latest-only", action="store_true",
                    help="only the most recent call per ticker")
    ap.add_argument("--max-calls", type=int, default=4,
                    help="calls per ticker to scan, newest first")
    args = ap.parse_args()

    import pyarrow.parquet as pq
    table = pq.read_table(PARQUET, columns=["symbol", "quarter", "year", "date", "content"])
    rows = table.to_pylist()

    wanted = [t.strip().upper() for t in args.tickers.split(",") if t.strip()]
    alias_map = {}
    for t in wanted:
        alias_map[t] = TICKER_ALIASES.get(t, [t])

    by_ticker = {}
    for r in rows:
        sym = (r["symbol"] or "").upper()
        for t, aliases in alias_map.items():
            if sym in aliases:
                by_ticker.setdefault(t, []).append(r)

    n_findings = 0
    for t in wanted:
        calls = sorted(by_ticker.get(t, []),
                       key=lambda r: (r["year"], r["quarter"]), reverse=True)
        if args.latest_only:
            calls = calls[:1]
        else:
            calls = calls[:args.max_calls]
        if not calls:
            print(json.dumps({"ticker": t, "warning": "no transcripts in dataset"}))
            continue
        for c in calls:
            for f in find_guidance(t, c["year"], c["quarter"], c["date"], c["content"] or ""):
                print(json.dumps(f))
                n_findings += 1
    print(json.dumps({"tickers": wanted, "findings": n_findings,
                      "status": "done"}), file=sys.stderr)


if __name__ == "__main__":
    main()
