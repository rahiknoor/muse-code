#!/usr/bin/env python3
"""Add locked business_score (0-100) + score_breakdown to Main rows of Product Shortlist v2."""
import json, re
from collections import Counter

DIR = '/home/hatch/workspace/research_notes/product-shortlist-v2-20261006'
d = json.load(open(DIR + '/products.json'))

def num(s):
    """'1.2M'->1200000, '829K'->829000, '10k+'->10000, '5,157+'->5157"""
    s = s.replace(',', '').strip()
    m = re.match(r'([\d.]+)\s*([kKmM])?', s)
    if not m: return 0.0
    v = float(m.group(1)); u = (m.group(2) or '').lower()
    return v * (1000000 if u == 'm' else 1000 if u == 'k' else 1)

def find_sold(ev):
    m = re.search(r'([\d,.]+\s*[kKmM]?)\+?\s*(?:units?\s+)?sold', ev or '', re.I)
    return num(m.group(1)) if m else 0.0

def find_reviews(ev):
    vals = []
    for pat in [r'([\d,.]+\s*[kKmM]?)\+?\s*(?:item\s+)?reviews?', r'([\d,.]+\s*[kKmM]?)\+?\s*ratings?']:
        for mm in re.finditer(pat, ev or '', re.I):
            vals.append(num(mm.group(1)))
    return max(vals) if vals else 0.0

def find_orders24(ev):
    m = re.search(r'([\d,.]+)\+?\s*orders in 24h', ev or '', re.I)
    return num(m.group(1)) if m else 0.0

def find_units7d(ev):
    m = re.search(r'([\d,.]+\s*[kKmM]?)\s*units?/7d', ev or '', re.I)
    return num(m.group(1)) / 7 if m else 0.0

def find_watchers(ev):
    m = re.search(r'([\d,.]+)\s*watchers?', ev or '', re.I)
    return num(m.group(1)) if m else 0.0

def find_carts(ev):
    vals = []
    for pat in [r'([\d,.]+)\s*in carts', r"([\d,.]+)\+\s*people's carts"]:
        for mm in re.finditer(pat, ev or '', re.I):
            vals.append(num(mm.group(1)))
    return max(vals) if vals else 0.0

def demand_scale(v):
    if v >= 10000: return 20
    if v >= 1000: return 15
    if v >= 500: return 12
    if v >= 100: return 8
    return 4

def velocity_score(ev):
    ev = ev or ''
    scores = []
    if '500+ bought since yesterday' in ev: scores.append((10, "'500+ bought since yesterday'"))
    if '100+ bought since yesterday' in ev: scores.append((8, "'100+ bought since yesterday'"))
    o24 = find_orders24(ev)
    if o24 >= 400: scores.append((8, f"{o24:g}+ orders in 24h"))
    d7 = find_units7d(ev)
    if d7 >= 500: scores.append((10, "strong 7-day unit velocity"))
    w, c = find_watchers(ev), find_carts(ev)
    if w >= 500 or c >= 50:
        scores.append((6, f"{w:g} watchers / {c:g} carts"))
    elif w > 0 or c > 0:
        scores.append((3, f"{w:g} watchers / {c:g} carts (moderate)"))
    elif any(k in ev.lower() for k in ['almost sold out', 'low stock', 'trending', 'repeat buyers', 'almost gone']):
        scores.append((3, "soft velocity signal (sellout/low-stock/trending/repeat)"))
    if not scores: return 0, "no velocity signal stated"
    return max(scores, key=lambda x: x[0])

RET_MAP = {'Low': 10, 'Low-Medium': 7, 'Medium': 4, 'High': 0}
def return_score(rr):
    m = re.match(r'(Low-Medium|Low|Medium|High)', (rr or '').strip())
    tok = m.group(1) if m else None
    return (RET_MAP[tok], tok) if tok else (0, 'unparsed')

DEMO_NO = {'coffee','fragrance','parfum','edp',' oud','serum','cream','moisturizer','treat','jerky',
    'chew','trash bag','poop bag','sock','tote','scarf','beanie','glove','belt','underwear','briefs',
    'bracelet','necklace','bangle','candle holder','mirror','dog tag','spa','basket','soap',
    'portrait','print','game','vase','planter','dog bed','pillow','insoles','rosemary','deodorizer',
    'water','hat'}
DEMO_YES = {'posture','mount','phone holder','ssd','foundation','hair removal','toy','flapping','brush',
    'roller','mat','feeder','harness','kit','organizer','led','light','humidifier','frother','shaver',
    'mandoline','slicer','shower','charger','fan','doorbell','earbud','squishy','mascara','brow gel',
    'loops','tula','vitamasques','color wow','mice','case','tool','spray','pore','accessories'}
def five_sec_demo(name):
    n = name.lower()
    if any(k in n for k in DEMO_NO): return False
    return any(k in n for k in DEMO_YES)

BAND_ECO = {'in-band': 5, 'native-value': 5, 'premium': 4, 'bundle-up': 3}
MARGIN_ECO = {'High': 15, 'Medium': 10, 'Low': 5, 'Tight': 0}
LANE_SELL = {'desire': 8, 'problem-solver': 6, 'necessity': 5}
CREA_SELL = {'High': 4, 'Medium': 2, 'Low': 0}

unparsed_ret = []
for r in d['main']:
    ev = r.get('buyer_evidence', '')
    # --- Demand (30)
    sold = find_sold(ev)
    if sold > 0:
        d_pts, d_base = demand_scale(sold), f"{sold:g} sold"
    else:
        rev = find_reviews(ev)
        if rev > 0:
            d_pts, d_base = min(demand_scale(rev), 15), f"{rev:g} reviews/ratings as demand proxy (capped)"
        else:
            d_pts, d_base = 4, "no sold/review count stated (floor)"
    v_pts, v_base = velocity_score(ev)
    demand = d_pts + v_pts
    # --- Economics (30)
    m_pts = MARGIN_ECO[r['margin_tier']]
    r_pts = 10 if r['replenishable'] else 0
    b_pts = BAND_ECO[r['price_band_fit']]
    economics = m_pts + r_pts + b_pts
    # --- Friction (25)
    ret_pts, ret_tok = return_score(r.get('return_risk'))
    if ret_tok is None: unparsed_ret.append(r['name'])
    comp_pts = {'Low': 8, 'Medium': 4, 'High': 0}[r['competition']]
    ship_pts = {'light': 4, 'medium': 2, 'bulky': 0}[r['ship_weight_tier']]
    rev_pts = {'ok': 3, 'n/a': 2, 'check': 0}[r['review_authenticity']]
    friction = ret_pts + comp_pts + ship_pts + rev_pts
    # --- Sellability (15)
    lane_pts = LANE_SELL[r['lane']]
    crea_pts = CREA_SELL[r['creative_availability']]
    demo = five_sec_demo(r['name'])
    demo_pts = 3 if demo else 0
    sellability = lane_pts + crea_pts + demo_pts
    total = demand + economics + friction + sellability
    assert total <= 100, r['name']
    r['business_score'] = total
    r['score_breakdown'] = {
        'demand': demand,
        'demand_basis': f"sold scale {d_pts} ({d_base}) + velocity {v_pts} ({v_base})",
        'economics': economics,
        'economics_basis': f"margin {r['margin_tier']} {m_pts} + replenishable {r_pts} + band {r['price_band_fit']} {b_pts}",
        'friction': friction,
        'friction_basis': f"return {ret_tok} {ret_pts} + competition {r['competition']} {comp_pts} + ship {r['ship_weight_tier']} {ship_pts} + reviews {r['review_authenticity']} {rev_pts}",
        'sellability': sellability,
        'sellability_basis': f"lane {r['lane']} {lane_pts} + creative {r['creative_availability']} {crea_pts} + 5s demo {'yes' if demo else 'no'} {demo_pts}",
        'total': total,
    }

for sec in ['leads', 'cuts']:
    for r in d[sec]:
        r['business_score'] = None
        r['score_breakdown'] = None

# score_rank: sort Main by (-score, rank), assign
ordered = sorted(d['main'], key=lambda r: (-r['business_score'], r.get('rank', 999)))
for i, r in enumerate(ordered, 1):
    r['score_rank'] = i
d['main'] = ordered

json.dump(d, open(DIR + '/products.json', 'w'), indent=1, ensure_ascii=False)
print('unparsed return_risk:', unparsed_ret)
print('score distribution:', dict(sorted(Counter(r['business_score'] for r in d['main']).items())))
print()
print('TOP 15 by score:')
for r in ordered[:15]:
    b = r['score_breakdown']
    print(f"#{r['score_rank']} [{r['business_score']}] {r['name'][:52]} | ${r['price']} | D{b['demand']}/E{b['economics']}/F{b['friction']}/S{b['sellability']}")
print()
print('BOTTOM 5:')
for r in ordered[-5:]:
    b = r['score_breakdown']
    print(f"#{r['score_rank']} [{r['business_score']}] {r['name'][:52]} | D{b['demand']}/E{b['economics']}/F{b['friction']}/S{b['sellability']}")
