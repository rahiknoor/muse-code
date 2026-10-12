"""Confluence scorer: 4 signals per ticker from 13F snapshots.
Signals:
  1. sticky20: Big Three combined % of shares outstanding >= 20 (Q2)
  2. big3_align: 2+ of Big Three increased shares Q1->Q2 (>1% of prior, else noise)
  3. active_align: 2+ of 5 active managers increased shares Q1->Q2 (>1% of prior)
  4. anchor8: single largest holder across all 8 filers > 8% of shares out (Q2)
Priority = 3+ signals. Run: python3 confluence.py
"""
import json, glob, os

D = os.path.expanduser('~/workspace/goals/big-cap-insider-and-politician-buy-watch/hidden_files/thirteenf')
S = D + '/snapshots'
TICKERS = "AMD,AMZN,AVGO,BRK.B,CELH,CRM,CVX,GOOG,GRAB,INTC,IREN,JNJ,JPM,LLY,MA,META,MSFT,NFLX,NOW,NVDA,ORCL,PFE,PLTR,QCOM,QXO,RH,SOFI,TSLA,UNH,V,WH,XOM".split(',')
CUSIPS = json.load(open(D + '/ticker_cusips.json'))
META = json.load(open(D + '/ticker_meta.json'))

VANG_KEYS = ['vanguard_capital','vanguard_portfolio','vanguard_fiduciary','vanguard_aus',
             'vanguard_marketing','vanguard_personalized','vanguard_assetmgmt',
             'vanguard_nattrust','vanguard_globaladv']
ACTIVE_KEYS = ['active_orbis','active_affinity','active_mfn','active_alphawave','active_finepoint']

def load(key, q):
    p = f'{S}/{key}_{q}.json'
    return json.load(open(p))['holdings'] if os.path.exists(p) else {}

def shares(holdings, ticker):
    return sum(holdings.get(c, {}).get('shares', 0) for c in CUSIPS.get(ticker, []))

# per-ticker shares per filer per quarter
Q1 = {}; Q2 = {}
for key in VANG_KEYS + ['blackrock','state_street'] + ACTIVE_KEYS:
    Q1[key] = load(key, '2026Q1'); Q2[key] = load(key, '2026Q2')

def vanguard_shares(Q, ticker):
    return sum(shares(Q[k], ticker) for k in VANG_KEYS)

results = []
for t in TICKERS:
    so = META[t]['shares_out']
    v1, v2 = vanguard_shares(Q1, t), vanguard_shares(Q2, t)
    b1, b2 = shares(Q1['blackrock'], t), shares(Q2['blackrock'], t)
    s1, s2 = shares(Q1['state_street'], t), shares(Q2['state_street'], t)
    # signal 1: sticky
    sticky = 100.0*(v2+b2+s2)/so if so else 0
    sig_sticky = sticky >= 20
    # signal 2: big3 alignment (increases >1% of prior; new positions count)
    def up(old, new):
        if new <= 0: return False
        if old <= 0: return True
        return (new-old)/old > 0.01
    big3ups = sum([up(v1,v2), up(b1,b2), up(s1,s2)])
    sig_big3 = big3ups >= 2
    # signal 3: active alignment
    aup = 0; active_detail = []
    for k in ACTIVE_KEYS:
        o, n = shares(Q1[k], t), shares(Q2[k], t)
        if up(o, n):
            aup += 1; active_detail.append(k.replace('active_',''))
    sig_active = aup >= 2
    # signal 4: anchor holder >8%
    best_name, best_pct = '', 0
    allq = {'Vanguard':v2,'BlackRock':b2,'StateStreet':s2}
    for k in ACTIVE_KEYS:
        allq[k.replace('active_','').title()] = shares(Q2[k], t)
    for name, sh in allq.items():
        pct = 100.0*sh/so if so else 0
        if pct > best_pct: best_pct, best_name = pct, name
    sig_anchor = best_pct > 8
    score = sum([sig_sticky, sig_big3, sig_active, sig_anchor])
    results.append({
        'ticker': t, 'score': score, 'sticky': round(sticky,1),
        'big3ups': big3ups, 'active_ups': aup, 'active_who': active_detail,
        'anchor': f"{best_name} {best_pct:.1f}%",
        'sigs': [s for s, on in [('STICKY',sig_sticky),('BIG3',sig_big3),('ACTIVE',sig_active),('ANCHOR',sig_anchor)] if on],
        'deltas': {'v': v2-v1, 'b': b2-b1, 's': s2-s1},
    })

results.sort(key=lambda r: (-r['score'], -r['sticky']))
print(f"{'#':<3}{'Ticker':<8}{'Score':<7}{'Sticky%':<9}{'Big3+':<7}{'Act+':<6}{'Anchor':<22}Signals")
for i, r in enumerate(results, 1):
    if r['score'] >= 2 or i <= 12:
        print(f"{i:<3}{r['ticker']:<8}{r['score']:<7}{r['sticky']:<9}{r['big3ups']:<7}{r['active_ups']:<6}{r['anchor']:<22}{','.join(r['sigs'])}")
print()
print("=== PRIORITY (>=3 signals) ===")
for r in results:
    if r['score'] >= 3:
        d = r['deltas']
        print(f"{r['ticker']}: score {r['score']} | sticky {r['sticky']}% | big3 deltas V{d['v']:+,} B{d['b']:+,} S{d['s']:+,} | active: {r['active_who']} | anchor {r['anchor']} | {','.join(r['sigs'])}")
