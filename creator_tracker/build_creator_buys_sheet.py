"""Build the one-page Creator Buys log: every personal buy / plan-to-buy from the
three followed creators (Parkev Tatevosian, Jeremy Lefebvre, Couch Investor).

Reads: creator_tracker/{couch_investor,parkev,jeremy}_targets.json + creator_buys/buys.json
Writes: ~/workspace/your_files/creator-buys/Creator Buys.xlsx (one sheet, newest first)
"""
import json
import re
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

WS = Path('/home/hatch/workspace')
OUT = WS / 'your_files/creator-buys/Creator Buys.xlsx'
OUT.parent.mkdir(parents=True, exist_ok=True)

CREATORS = {
    'couch': 'Couch Investor',
    'parkev': 'Parkev Tatevosian, CFA',
    'jeremy': 'Jeremy Lefebvre',
}

NAVY = '17324D'
TEAL = '1B7F79'
LIGHT_BLUE = 'EAF2F8'
BLUE = '2F75B5'
GREEN = '008000'
BLACK = '000000'
GRAY = '667085'
thin = Side(style='thin', color='D0D5DD')
border = Border(left=thin, right=thin, top=thin, bottom=thin)


def load(p):
    p = WS / p
    if not p.exists():
        return []
    d = json.loads(p.read_text())
    if isinstance(d, list):
        return d
    return d.get('entries', d.get('buys', []))


def extract_time(source, notes):
    m = re.search(r'(\d{1,2}:\d{2}\s*ET)', f'{source or ""} {notes or ""}')
    return m.group(1) if m else '—'


def extract_shares(notes):
    m = re.search(r'(\d[\d,]*)\s+shares?', notes or '', re.I)
    return m.group(1) if m else '—'


def short_quote(notes, limit=220):
    q = re.sub(r'\s+', ' ', notes or '').strip()
    # Prefer a verbatim quoted bit if present
    m = re.search(r"'([^']{20,220}?)'", q)
    if m:
        return m.group(1)
    return q[:limit] + ('…' if len(q) > limit else '')


def action_label(entry):
    tt = (entry.get('target_type') or entry.get('price_type') or '').lower()
    if tt in ('buy_price', 'bought_at'):
        return 'Bought'
    if tt == 'adding':
        return 'Adding'
    if tt == 'plans_to_buy':
        return 'Plans to buy'
    if tt == 'called_buying_opportunity':
        return 'Owns it / buying opportunity'
    notes = (entry.get('notes') or '').lower()
    if 'bought' in notes or 'purchas' in notes:
        return 'Bought (earlier)'
    return '—'


def buy_price_of(entry):
    """Only a stated personal buy price — never a fair value."""
    tt = (entry.get('target_type') or entry.get('price_type') or '').lower()
    if tt in ('buy_price', 'bought_at', 'adding', 'plans_to_buy'):
        v = entry.get('target_value', entry.get('buy_price', entry.get('price_at_call')))
        return v if isinstance(v, (int, float)) else '—'
    return '—'


def is_buy(entry):
    """Personal buys only:
    - explicit buy action types,
    - notes stating he personally bought / owns / purchased.
    Excludes fair-value-only calls, 'buying opportunity' opinions with no
    personal purchase, and sell stories."""
    tt = (entry.get('target_type') or entry.get('price_type') or '').lower()
    notes = (entry.get('notes') or '').lower()
    if 'sold' in notes or 'no personal purchase' in notes:
        return False
    if tt in ('buy_price', 'bought_at', 'adding', 'plans_to_buy',
              'called_buying_opportunity'):
        return True
    return 'bought' in notes or 'purchas' in notes or 'i own' in notes


rows = []

# Per-creator target logs
for key, cname in (('couch', 'couch_investor_targets'),
                   ('parkev', 'parkev_targets'),
                   ('jeremy', 'jeremy_targets')):
    for e in load(f'creator_tracker/{cname}.json'):
        if not is_buy(e):
            continue
        rows.append({
            'date': e.get('call_date') or '—',
            'time': extract_time(e.get('source'), e.get('notes')),
            'creator': CREATORS[key],
            'company': e.get('company') or '—',
            'ticker': e.get('ticker') or '—',
            'action': action_label(e),
            'price': buy_price_of(e),
            'shares': extract_shares(e.get('notes')),
            'quote': short_quote(e.get('notes')),
            'video': e.get('video_url') or e.get('video_title') or '—',
        })

# Non-holdings buy tracker — only the three followed creators
for e in load('creator_buys/buys.json'):
    creator = e.get('creator') or ''
    cname = None
    for key, full in CREATORS.items():
        if full.split(',')[0].lower() in creator.lower() or creator.lower() in full.lower():
            cname = full
            break
    if not cname:
        continue
    if not is_buy(e):
        continue
    price = e.get('buy_price')
    rows.append({
        'date': e.get('call_date') or '—',
        'time': extract_time(e.get('source'), e.get('notes')),
        'creator': cname,
        'company': e.get('company') or '—',
        'ticker': e.get('ticker') or '—',
        'action': action_label(e),
        'price': price if isinstance(price, (int, float)) else '—',
        'shares': extract_shares(e.get('notes')),
        'quote': short_quote(e.get('notes')),
        'video': e.get('video_url') or e.get('video_title') or '—',
    })

# De-dupe (same creator+ticker+date+action), newest first
seen = set()
uniq = []
for r in rows:
    k = (r['creator'], r['ticker'], r['date'], r['action'])
    if k in seen:
        continue
    seen.add(k)
    uniq.append(r)
uniq.sort(key=lambda r: (r['date'], r['creator']), reverse=True)

wb = Workbook()
ws = wb.active
ws.title = 'Creator Buys'
ws.sheet_view.showGridLines = False
ws.freeze_panes = 'A3'

ws.merge_cells('A1:J1')
t = ws['A1']
t.value = 'Creator Buys — Parkev Tatevosian, Jeremy Lefebvre, Couch Investor'
t.font = Font(name='Liberation Sans', size=16, bold=True, color='FFFFFF')
t.fill = PatternFill('solid', fgColor=NAVY)
t.alignment = Alignment(horizontal='left', vertical='center')
ws.row_dimensions[1].height = 30

headers = ['Date', 'Time', 'Creator', 'Company', 'Ticker', 'Action',
           'Buy price (USD)', 'Shares', 'What they said', 'Video']
for i, h in enumerate(headers, 1):
    c = ws.cell(2, i, h)
    c.font = Font(name='Liberation Sans', size=11, bold=True, color='FFFFFF')
    c.fill = PatternFill('solid', fgColor=TEAL)
    c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
    c.border = border
ws.row_dimensions[2].height = 28

for ri, r in enumerate(uniq, 3):
    vals = [r['date'], r['time'], r['creator'], r['company'], r['ticker'],
            r['action'], r['price'], r['shares'], r['quote'], r['video']]
    for ci, v in enumerate(vals, 1):
        c = ws.cell(ri, ci, v)
        c.font = Font(name='Liberation Sans', size=10, color=BLACK)
        c.border = border
        c.alignment = Alignment(vertical='center', wrap_text=True,
                                horizontal='center' if ci in (1, 2, 5, 6, 7, 8) else 'left')
        if ci == 7 and isinstance(v, (int, float)):
            c.number_format = '$#,##0.00'
            c.font = Font(name='Liberation Sans', size=11, bold=True, color=GREEN)
        if ci == 6 and v == 'Bought':
            c.font = Font(name='Liberation Sans', size=10, bold=True, color=GREEN)
    if ri % 2 == 0:
        for ci in range(1, 11):
            if ws.cell(ri, ci).fill.fill_type is None:
                ws.cell(ri, ci).fill = PatternFill('solid', fgColor=LIGHT_BLUE)
    ws.row_dimensions[ri].height = 44

widths = [12, 10, 24, 22, 10, 24, 15, 10, 60, 30]
for i, w in enumerate(widths, 1):
    ws.column_dimensions[get_column_letter(i)].width = w
ws.auto_filter.ref = f'A2:J{2 + len(uniq)}'
ws.sheet_properties.pageSetUpPr.fitToPage = True

wb.save(OUT)
print(f'Wrote {OUT} with {len(uniq)} buy entries.')
