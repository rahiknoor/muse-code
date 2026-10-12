#!/usr/bin/env python3
"""Enrich Product Shortlist v2 with 8 new categorization fields (all estimated, labeled as such)."""
import json, re, copy

DIR = '/home/hatch/workspace/research_notes/product-shortlist-v2-20261006'
d = json.load(open(DIR + '/products.json'))

def price_low(p):
    m = re.findall(r'\$([\d,]+\.?\d*)', str(p))
    return float(m[0].replace(',', '')) if m else 0.0

def has(n, *kws):
    n = n.lower()
    return any(k in n for k in kws)

# ---------- 8. price_band_fit ----------
BUNDLE_OVERRIDES = set()  # (section, idx) with price<10 that need bundling
NATIVE_P10 = {  # price < $10, natural standalone impulse price
    ('main',11),('main',12),('main',13),('main',14),('main',15),('main',16),
    ('main',20),('main',23),('main',24),('main',27),('main',28),('main',36),
    ('main',37),('main',49),('main',22),
    ('leads',7),('leads',8),('leads',9),('leads',10),('leads',11),('leads',13),
    ('leads',14),('leads',19),('leads',21),('leads',25),('leads',27),
    ('leads',46),('leads',49),('leads',62),
    ('cuts',11),('cuts',12),
}
BUNDLE_P10 = {  # price < $10 but needs bundling/multipack to work
    ('main',0),('main',17),('main',18),('main',29),
    ('leads',12),('leads',15),('leads',16),('leads',17),('leads',18),
}

def price_band_fit(sec, i, p):
    if p >= 50: return 'premium'
    if p >= 15: return 'in-band'
    if p >= 10: return 'bundle-up'
    if (sec, i) in NATIVE_P10: return 'native-value'
    if (sec, i) in BUNDLE_P10: return 'bundle-up'
    return 'native-value'

# ---------- 1. replenishable ----------
REPL_TRUE = {'coffee','treat','jerky','chew','deodorizer','spray','serum','cream','moisturizer',
    'pads','mascara','brow gel','brow','lashes','lip ','lip treatment','trash bag','poop bag',
    'soap','balm','eye mask','rosemary','fragrance','parfum','edp',' oud','perfume','water',
    'body care'}
REPL_FALSE_FORCE = {'bala bars','bala bangles'}  # fitness weights, not consumable
def replenishable(name):
    n = name.lower()
    if any(k in n for k in REPL_FALSE_FORCE): return False
    # 'chew' in a toy/ball name (chew toy) is not a consumable
    kws = [k for k in REPL_TRUE if k != 'chew'] if ('chew' in n and ('toy' in n or 'ball' in n)) else REPL_TRUE
    return any(k in n for k in kws)

# ---------- 4. seasonality ----------
Q4 = {'beanie','glove','scarf','halloween','bat light','candle holder','humidifier',
      'charcuterie topper','heated towel','wrap','shawl'}
SEASON_OTHER = {'bucket hat','pocket fan','seatbelt harness','fan ','power bank'}
def seasonality(name):
    n = name.lower()
    if any(k in n for k in Q4): return 'Q4-seasonal'
    if any(k in n for k in SEASON_OTHER): return 'seasonal-other'
    return 'evergreen'

# ---------- 5. ship weight ----------
BULKY = {'dog bed','mattress topper','mirror','kitchen trolley','under-sink organizer',
         'pillow','mason genie','photo frames kit','cat feeder'}
MEDIUM = {'organizer','storage jars','mandoline','shower head','drill','candle holder',
          'backpack','tote','grain storage','dust collector','frames kit','feeder','planter','vase','basket'}
def ship_tier(name):
    n = name.lower()
    if any(k in n for k in BULKY): return 'bulky'
    if any(k in n for k in MEDIUM): return 'medium'
    return 'light'

# ---------- 2. margin tier (computed from est. sourced cost) ----------
# keyword -> estimated sourced unit cost (high end of typical range), USD
COST_MAP = [
    ('flipper zero', 120), ('geeta', 40), ('bronoir', 30), ('buckley', 50),
    ('fisiorest', 50), ('fire extinguisher', 40), ('mizuro', 25),
    ('bala bangles', 16), ('bala bars', 13),
    ('canonyx', 50), ('axzenia', 25), ('cloudalign', 30), ('mason genie', 30),
    ('orthoflexx', 12), ('photo frames', 15),
    ('cheese chew', 13), ('chew toy', 2.0), ('sisal', 1.5),
    ('tool kit', 1.0), ('lash', 1.0),
    ('hair accessories', 1.0), ('hair styling', 1.0),
    ('ssd', 22), ('cat feeder', 20), ('doorbell', 7),
    ('mirror', 17), ('mattress topper', 20), ('dog bed', 11),
    ('leather belt', 12), ('spa gift basket', 22), ('luna', 7), ('dog tag', 4),
    ('candle holder', 8), ('trolley', 7), ('under-sink', 6),
    ('treats', 5), ('jerky', 5), ('yak', 13),
    ('fragrance', 5), ('parfum', 5), ('edp', 5), ('oud', 5),
    ('serum', 4), ('foundation', 4), ('lip treatment', 4),
    ('cream', 6), ('moisturizer', 6), ('pads', 6), ('deodorizer', 6),
    ('mascara', 1.5), ('brow gel', 1.5), ('lashes', 1.0),
    ('bangle', 0.8), ('bracelet', 0.6), ('necklace', 0.8),
    ('phone case', 3), ('magpack', 4),
    ('posture', 2.5), ('car phone mount', 2.5), ('phone holder', 1.5), ('charger mount', 3),
    ('power bank', 2.5), ('fan', 2.5), ('humidifier', 2.5), ('frother', 2), ('shaver', 2.5),
    ('scrubber', 8), ('grinder', 4), ('lamp', 5),
    ('led', 2), ('night light', 1.5),
    ('trash bag', 0.9), ('poop bag', 8),
    ('toy', 2), ('mice', 4), ('snuffle', 4), ('lick mat', 2.5),
    ('socks', 3), ('beanie', 3), ('gloves', 3), ('hat', 2.5), ('underwear', 4), ('briefs', 10),
    ('scarf', 5), ('tote', 3.5),
    ('brush', 2), ('roller', 2.5),
    ('jars', 4), ('rack', 4), ('mandoline', 5), ('shower head', 7),
    ('insoles', 12), ('brace', 25),
    ('coffee', 0.5), ('rosemary', 3.5),
    ('portrait', 1), ('download', 0.5), ('print', 3),
    ('game', 8), ('vase', 10), ('planter', 10), ('soap', 3.5),
    ('martini picks', 6), ('toppers', 8),
]
def est_cost(name):
    n = name.lower()
    for kw, c in COST_MAP:
        if kw in n: return c
    return 4.0

def margin_tier_calc(price, cost, bulky):
    ratio = price / cost if cost else 99
    if ratio >= 3: t = 'High'
    elif ratio >= 2: t = 'Medium'
    elif ratio >= 1.4: t = 'Low'
    else: t = 'Tight'
    note = ''
    if price < 3 and t == 'High':
        t, note = 'Tight', 'absolute gross too small for paid ads'
    if bulky and t in ('High', 'Medium'):
        t = {'High': 'Medium', 'Medium': 'Low'}[t]
        note = (note + '; ' if note else '') + 'bulky shipping'
    return t, cost, note

# ---------- 3. competition ----------
COMP_HIGH = {'case','jewelry','bracelet','necklace','bangle','mascara','brow','lip','skincare',
    'cream','serum','fragrance','perfume','oud','phone holder','mount','charger','posture',
    'led','trash','poop bag','beauty','hair accessory','hair removal','humidifier','frother',
    'shaver','organizer','hat','fan','light','grooming','lint','dog bed','mirror','feeder'}
COMP_LOW = {'personalized','custom','portrait','handmade','yak','snuffle','deodorizer','seatbelt',
    'martini','dice','flipper','bala','geeta','download','engraved'}
def competition(name):
    n = name.lower()
    if any(k in n for k in COMP_LOW): return 'Low'
    if any(k in n for k in COMP_HIGH): return 'High'
    return 'Medium'

COMPETITION_OVERRIDES = {
    ('main', 43): ('High', 'branded haircare; competes with the brand itself'),
    ('main', 54): ('High', 'branded skincare; dupes everywhere'),
    ('main', 1): ('High', 'fragrance dupes saturated on TikTok Shop'),
    ('main', 7): ('Medium', 'pet toys crowded; flapping niche is differentiated'),
    ('main', 10): ('Medium', 'seasonal accessory; many sellers, design-led'),
    ('main', 19): ('Medium', 'pet treats crowded; 100% chicken niche'),
    ('main', 21): ('Medium', 'totes common; zippered canvas niche'),
    ('main', 22): ('Medium', 'multipack basics; brand-light'),
    ('main', 26): ('Medium', 'cat toys crowded; rabbit-fur niche'),
    ('main', 29): ('Medium', 'shoe care is a quiet niche'),
    ('main', 30): ('Medium', 'seasonal basics; many sellers'),
    ('main', 34): ('Medium', 'basics multipack; brand-light'),
    ('main', 36): ('Medium', 'enrichment niche, growing'),
    ('main', 42): ('Medium', 'treats crowded; duck/cod twist niche'),
    ('main', 46): ('Medium', 'auto feeders competitive; dual-power niche'),
    ('main', 48): ('Medium', 'decor mirrors; arched trend'),
    ('main', 53): ('Medium', 'enrichment niche'),
    ('main', 55): ('Medium', 'home decor; brass trend'),
    ('main', 0): ('High', 'protein coffee is a crowded TikTok category'),
    ('main', 2): ('High', 'hair-growth oils everywhere'),
    ('main', 4): ('High', 'K-beauty sets heavily promoted'),
    ('main', 5): ('High', 'fragrance dupes saturated'),
    ('main', 39): ('Medium', 'tech accessories; magnetic niche'),
    ('main', 44): ('High', 'K-beauty toner pads; many brands'),
    ('main', 45): ('High', 'K-beauty moisturizers; many brands'),
    ('main', 49): ('High', 'moisturizers; crowded'),
    ('leads', 0): ('High', 'viral toy format; many copycats'),
    ('leads', 1): ('Medium', 'photo gifts; moderate competition'),
    ('leads', 2): ('Medium', 'insoles; established brands'),
    ('leads', 3): ('Medium', 'vacuum sealers competitive'),
    ('leads', 4): ('Medium', 'pillows; ergonomic niche'),
    ('leads', 5): ('Medium', 'posture braces; established'),
    ('leads', 6): ('Low', 'novel heated appliance; few sellers'),
    ('leads', 33): ('Medium', 'novelty doorbell; gift niche'),
    ('leads', 35): ('Low', 'novelty multi-tool; gift niche'),
    ('leads', 37): ('Medium', 'seasonal Halloween decor'),
    ('leads', 38): ('Medium', 'branded fitness; competes with brand'),
    ('leads', 40): ('Medium', 'branded fitness; competes with brand'),
    ('leads', 41): ('Low', 'niche DIY tool'),
    ('leads', 42): ('Medium', 'eye masks; beauty niche'),
    ('leads', 45): ('Medium', 'branded socks; competes with brand'),
    ('leads', 47): ('Medium', 'seasonal basics'),
    ('leads', 48): ('Medium', 'handmade leather; craft niche'),
    ('leads', 51): ('Medium', 'seasonal basics'),
    ('leads', 52): ('Medium', 'winter gloves; seasonal'),
    ('leads', 53): ('Low', 'handmade barware niche'),
    ('leads', 57): ('Low', 'handmade game; one-of-a-kind'),
    ('leads', 58): ('Low', 'vintage resale; one-of-a-kind'),
    ('leads', 59): ('Medium', 'art prints; crowded but design-led'),
    ('leads', 60): ('Low', 'handmade decor; craft niche'),
    ('leads', 61): ('Low', 'handmade planter; craft niche'),
    ('leads', 62): ('Medium', 'artisan soap; crowded craft niche'),
}

def competition_basis(name, tier):
    defaults = {'High': 'commodity/saturated category with many sellers (est.)',
                'Medium': 'competitive but differentiated niche exists (est.)',
                'Low': 'niche, custom, or novel product; few direct sellers (est.)'}
    return defaults[tier]

# ---------- 6. creative_availability ----------
CREA_HIGH = {'mascara','brow','lash','serum','cream','foundation','lip','fragrance','perfume','oud',
    'parfum','toy','flapping','posture','mount','ssd','led strip','shaver','frother',
    'snuffle','lick mat','pads','dog bed','hair removal','phone holder','feeder','deodorizer'}
CREA_LOW = {'exhaust pipe','martini picks','print','game','pipe','policy'}
def creative(name):
    n = name.lower()
    if any(k in n for k in CREA_LOW): return 'Low'
    if any(k in n for k in CREA_HIGH): return 'High'
    return 'Medium'

CREATIVE_OVERRIDES = {
    ('main', 15): ('Medium', 'strength/capacity demo possible; not exciting'),
    ('main', 25): ('Medium', 'count/capacity demo; utilitarian'),
    ('main', 56): ('Medium', 'engraving process videos exist'),
    ('main', 57): ('Medium', 'unboxing/gifting videos'),
    ('leads', 53): ('Low', 'static barware; weak demo'),
    ('leads', 54): ('Medium', 'process/customization videos'),
    ('leads', 55): ('Medium', 'process/customization videos'),
    ('leads', 56): ('Medium', 'engraving process videos'),
    ('leads', 59): ('Low', 'static art print; weak demo'),
}
def creative_basis(tier):
    return {'High': '5-sec demo obvious; abundant TikTok-style creative exists (assessed)',
            'Medium': 'demo possible; creative obtainable with effort (assessed)',
            'Low': 'weak visual demo; creative hard to source (assessed)'}[tier]

# ---------- 7. review_authenticity ----------
REVIEW_CHECK = {('main', 50), ('main', 27)}  # Atgdluk anomaly; facial kit ~91% review rate
def review_auth(name, ev, sec, i):
    if (sec, i) in REVIEW_CHECK: return 'check'
    if re.search(r'rating|review|\u2605', ev or '', re.I): return 'ok'
    return 'n/a'

# ---------- apply ----------
# GUARD: index-keyed overrides below assume the ORIGINAL v2 row order.
# Never run this script after score_v2.py has re-sorted Main.
assert d['main'][0]['name'].startswith('Protein Loaded Coffee'), 'Main order changed - overrides would misalign!'
assert d['main'][1]['name'].startswith('Bella Vita'), 'Main order changed - overrides would misalign!'
assert 'business_score' not in d['main'][0], 'Main already scored - restore from pre-enrichment backup first!'
counts = {}
for sec in ['main', 'leads', 'cuts']:
    for i, r in enumerate(d[sec]):
        name, price = r['name'], price_low(r['price'])
        cost = est_cost(name)
        bulky = ship_tier(name) == 'bulky'
        mt, c_used, mnote = margin_tier_calc(price, cost, bulky)
        ov = COMPETITION_OVERRIDES.get((sec, i))
        if ov:
            comp, cbasis = ov
        else:
            comp = competition(name)
            cbasis = competition_basis(name, comp)
        crea = creative(name)
        cb = CREATIVE_OVERRIDES.get((sec, i))
        crea_basis = cb[1] if cb else creative_basis(crea)
        band = price_band_fit(sec, i, price)
        r['replenishable'] = replenishable(name)
        r['margin_tier'] = mt
        r['margin_basis'] = f"est. retail ${price:g} vs typical sourced ~${c_used:g}" + (f" ({mnote})" if mnote else "")
        r['competition'] = comp
        r['competition_basis'] = cbasis
        r['seasonality'] = seasonality(name)
        r['ship_weight_tier'] = ship_tier(name)
        r['ship_note'] = 'bulky: freight eats margin; check dimensional weight' if bulky else ''
        r['creative_availability'] = crea
        r['creative_basis'] = crea_basis
        r['review_authenticity'] = review_auth(name, r.get('buyer_evidence'), sec, i)
        r['price_band_fit'] = band
        for k in ['margin_tier','competition','seasonality','ship_weight_tier',
                  'creative_availability','review_authenticity','price_band_fit']:
            counts.setdefault(k, {}).setdefault(r[k], 0)
            counts[k][r[k]] += 1

json.dump(d, open(DIR + '/products.json', 'w'), indent=1, ensure_ascii=False)
print('written. totals:', d['count'])
for k, v in counts.items():
    print(k, '=', dict(sorted(v.items())))
# main-section splits of interest
m = d['main']
from collections import Counter
print('MAIN price_band_fit:', dict(Counter(r['price_band_fit'] for r in m)))
print('MAIN replenishable:', sum(1 for r in m if r['replenishable']))
print('MAIN margin_tier:', dict(Counter(r['margin_tier'] for r in m)))
print('MAIN review check:', [r['name'] for r in m if r['review_authenticity']=='check'])
