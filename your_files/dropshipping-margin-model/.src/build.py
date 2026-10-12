#!/usr/bin/env python3
"""Dropshipping Margin Model — generator. Landed-cost gate + break-even CPA."""
import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.formatting.rule import CellIsRule, FormulaRule
from openpyxl.utils import get_column_letter
import os

OUT = os.path.expanduser("~/workspace/your_files/dropshipping-margin-model/Dropshipping Margin Model.xlsx")

wb = openpyxl.Workbook()
wb.properties.title = "Dropshipping Margin Model"
wb.properties.creator = "Muse"
wb.properties.description = "Stress-test dropshipping products: landed cost, contribution margin, break-even CPA and margin-gate flag. Tariff, affiliate, CPC and CVR are adjustable parameters."

# Styles
TITLE_F = Font(name="Calibri", size=16, bold=True, color="1F3864")
SUB_F = Font(name="Calibri", size=10, italic=True, color="595959")
HDR_F = Font(name="Calibri", size=10, bold=True, color="FFFFFF")
HDR_FILL = PatternFill("solid", fgColor="1F3864")
SUBHDR_FILL = PatternFill("solid", fgColor="D9E2F3")
INPUT_FILL = PatternFill("solid", fgColor="FFF2CC")
INPUT_F = Font(name="Calibri", size=11, color="0000FF")
LINK_F = Font(name="Calibri", size=11, color="548235")
FORMULA_F = Font(name="Calibri", size=11, color="000000")
LABEL_F = Font(name="Calibri", size=11)
LABEL_B = Font(name="Calibri", size=11, bold=True)
NOTE_F = Font(name="Calibri", size=9, italic=True, color="595959")
THIN = Side(style="thin", color="B4C6E7")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
USD = '$#,##0.00;($#,##0.00);-'
USD0 = '$#,##0;($#,##0);-'
PCT1 = '0.0%'
MULT = '0.00"x"'

def style_input(c):
    c.fill = INPUT_FILL; c.font = INPUT_F; c.border = BORDER; c.alignment = Alignment(vertical="center")
def style_formula(c, fmt=None):
    c.font = FORMULA_F; c.border = BORDER; c.alignment = Alignment(vertical="center", horizontal="right")
    if fmt: c.number_format = fmt
def style_link(c, fmt=None):
    c.font = LINK_F; c.border = BORDER; c.alignment = Alignment(vertical="center", horizontal="right")
    if fmt: c.number_format = fmt

# ============ Sheet: How to Use ============
ws0 = wb.active
ws0.title = "How to Use"
ws0.sheet_properties.pageSetUpPr.fitToPage = True
ws0.page_setup.orientation = "portrait"; ws0.page_setup.fitToWidth = 1; ws0.page_setup.fitToHeight = 1
ws0.column_dimensions["A"].width = 22
ws0.column_dimensions["B"].width = 95
ws0["A1"] = "Dropshipping Margin Model"; ws0["A1"].font = TITLE_F
ws0["A2"] = "Stress-test candidate products before you score them. Margin is a gate, not a weighted score."
ws0["A2"].font = SUB_F
rows = [
    ("Purpose", "Compute fully landed cost (supplier + shipping + duties + platform fees + returns), contribution after affiliate, break-even CPA, and a PASS/FAIL gate at your expected CPC/CVR. Built from the Claude audit fix: measure markup on landed cost, not supplier cost."),
    ("How to use", "1) Set the yellow Global Parameters on 'Margin Calculator' (tariff, platform fee, returns, affiliate, CPC, CVR).  2) Enter one product per row: name, sell price, supplier cost, ship/pick-pack, declared value.  3) Read Contribution $, Break-even CPA, Expected CPA and the Gate.  4) Stress-test by changing tariff / affiliate / CPC / CVR — every row recalculates."),
    ("Legend", "Yellow fill + blue text = type here (inputs).  Green text = linked to Global Parameters (type over it to override one product).  Black text = formula — do not type over.  Change the Global Parameters to move the whole sheet; override a green cell to test one product only."),
    ("The gate", "PASS means Expected CPA (CPC ÷ CVR) is at or below Break-even CPA (what is left after all landed costs and affiliate). FAIL means ads at those settings lose money before any profit. A product can look like '4x' on supplier cost and still FAIL once duties, fees and affiliate are loaded — that is the point of the model."),
    ("Key definitions", "Landed Cost = supplier + ship/pick-pack + duties (declared value × tariff) + platform fee (sell price × fee %) + returns allowance (sell price × returns %).  Fully Loaded Cost = Landed Cost + affiliate (sell price × affiliate %).  Contribution $ = Sell Price − Fully Loaded Cost.  Break-even CPA = Contribution $.  Expected CPA = CPC ÷ CVR."),
    ("Assumptions", "Defaults are illustrative starting points, not October-2026 verified rates: tariff 35% on declared value, TikTok-style referral 6%, returns 5%, affiliate 15%, CPC $0.50, CVR 1.5% (the audit's worked example). Duties use Declared Value; leave it blank and the model uses Supplier Cost. Verify current tariff, platform-fee and shipping-policy figures before ordering — rates shift and the model should be re-run when they do."),
    ("Watch-outs", "• $15–24 single items rarely clear fixed ship/pick-pack + fees — test them as bundles.  • AliExpress prices are validation prices; confirm the agent quote and MOQ.  • Set kill rules in dollars (CPA vs break-even), not rank.  • High sold counts can mean saturation, not opportunity — keep a separate competition check."),
    ("Example rows", "Rows 15–17 on the calculator are worked examples (one gadget, one low-price item, one bundle). Edit or delete them; rows 18+ are blank templates with formulas ready."),
]
r = 4
for label, text in rows:
    ws0.cell(row=r, column=1, value=label).font = LABEL_B
    c = ws0.cell(row=r, column=2, value=text); c.font = LABEL_F; c.alignment = Alignment(wrap_text=True, vertical="top")
    ws0.row_dimensions[r].height = 52
    r += 1
ws0["A13"] = "Sources / provenance: model structure follows the Claude dropshipping audit (landed-cost contribution gate; sold counts down-weighted; margin/compliance/saturation as gates). All rates are user-adjustable parameters — no rate is hard-coded inside a formula."
ws0["A13"].font = NOTE_F; ws0.merge_cells("A13:B13"); ws0["A13"].alignment = Alignment(wrap_text=True, vertical="top")
ws0.row_dimensions[13].height = 36
ws0.sheet_properties.pageSetUpPr.fitToPage = True
ws0.print_title_rows = '1:2'

# ============ Sheet: Margin Calculator ============
ws = wb.create_sheet("Margin Calculator")
ws.sheet_properties.pageSetUpPr.fitToPage = True
ws.page_setup.orientation = "landscape"; ws.page_setup.paperSize = ws.PAPERSIZE_A3
ws.page_setup.fitToWidth = 1; ws.page_setup.fitToHeight = 0
ws.print_title_rows = '1:14'
ws.freeze_panes = "A15"

ws.merge_cells("A1:Z1"); ws["A1"] = "Dropshipping Margin Model — Landed-Cost Gate & Break-even CPA Calculator"; ws["A1"].font = TITLE_F
ws.merge_cells("A2:Z2"); ws["A2"] = "Edit the yellow parameters, then one product per row. Markup is measured on landed cost. Gate = Expected CPA vs Break-even CPA."
ws["A2"].font = SUB_F

# Global parameters in B5:B11, labels in A, notes in C
ws["A4"] = "GLOBAL PARAMETERS — edit yellow cells to stress-test every product"; ws["A4"].font = LABEL_B; ws["A4"].fill = SUBHDR_FILL
params = [
    ("Default tariff rate (on declared value)", 0.35, PCT1, "Duties = declared value × tariff. Re-run when tariff policy changes."),
    ("Default platform / referral fee rate", 0.06, PCT1, "Example uses a TikTok-Shop-style 6%. Verify the current schedule."),
    ("Default returns & refunds allowance", 0.05, PCT1, "Share of sell price reserved for returns/refunds."),
    ("Default affiliate commission rate", 0.15, PCT1, "Affiliate is a real cost — contribution is shown after affiliate."),
    ("Default CPC ($ per click)", 0.50, USD, "Cost per click for Expected CPA = CPC ÷ CVR."),
    ("Default CVR (click → order)", 0.015, PCT1, "Conversion rate. 1.5% at $0.50 CPC ⇒ $33.33 expected CPA."),
    ("Reference: contribution-margin target", 0.30, PCT1, "Reference only — a thin contribution rarely survives ad volatility."),
]
for i, (label, val, fmt, note) in enumerate(params):
    rr = 5 + i
    ws.cell(row=rr, column=1, value=label).font = LABEL_F
    c = ws.cell(row=rr, column=2, value=val); style_input(c); c.number_format = fmt; c.alignment = Alignment(horizontal="right", vertical="center")
    ws.cell(row=rr, column=3, value=note).font = NOTE_F
ws.column_dimensions["A"].width = 44
ws.column_dimensions["B"].width = 14
ws.column_dimensions["C"].width = 62

HDR_ROW = 14
headers = [
    ("A", "Product", 26), ("B", "Sell Price", 12), ("C", "Supplier Cost", 13),
    ("D", "Ship & Pick/Pack", 14), ("E", "Declared Value*", 13), ("F", "Tariff Rate", 11),
    ("G", "Duties & Fees", 12), ("H", "Platform Rate", 12), ("I", "Platform Fee", 12),
    ("J", "Returns Rate", 11), ("K", "Returns Allow.", 12), ("L", "Affiliate Rate", 12),
    ("M", "Affiliate $", 11), ("N", "Landed Cost", 12), ("O", "Fully Loaded Cost", 14),
    ("P", "Contribution $", 13), ("Q", "Contribution Margin", 13), ("R", "Markup on Supplier", 13),
    ("S", "Markup on Landed", 13), ("T", "Break-even CPA", 13), ("U", "CPC", 10),
    ("V", "CVR", 10), ("W", "Expected CPA", 12), ("X", "CPA Headroom", 12),
    ("Y", "GATE @ Expected Ads", 16), ("Z", "Required CVR @ CPC", 14),
]
for letter, title, w in headers:
    c = ws[f"{letter}{HDR_ROW}"]; c.value = title; c.font = HDR_F; c.fill = HDR_FILL; c.border = BORDER
    c.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    ws.column_dimensions[letter].width = w
# A already set wider above for params; keep product col wide
ws.column_dimensions["A"].width = 26
ws.row_dimensions[HDR_ROW].height = 34
ws["A13"] = "* Declared Value blank ⇒ duties use Supplier Cost.  Green cells link to Global Parameters — type over one to override that product only.  Yellow = type here.  Black = formula."
ws["A13"].font = NOTE_F; ws.merge_cells("A13:Z13")

FIRST = 15; LAST = 32
examples = [
    ("Example — Wow gadget (audit example)", 29.99, 7.00, 3.50, None),
    ("Example — Low-price impulse item", 19.99, 4.00, 3.00, None),
    ("Example — Bundle / multipack", 39.99, 9.00, 4.50, None),
]
for idx in range(FIRST, LAST + 1):
    ex = examples[idx - FIRST] if idx - FIRST < len(examples) else None
    # A product name (input)
    c = ws.cell(row=idx, column=1, value=(ex[0] if ex else None)); style_input(c)
    # B sell, C supplier, D ship, E declared (inputs)
    for col, val in ((2, ex[1] if ex else None), (3, ex[2] if ex else None), (4, ex[3] if ex else None), (5, ex[4] if ex else None)):
        c = ws.cell(row=idx, column=col, value=val); style_input(c); c.number_format = USD; c.alignment = Alignment(horizontal="right", vertical="center")
    # F,H,J,L,U,V links to globals B5,B6,B7,B8,B9,B10
    for col, ref, fmt in ((6, "$B$5", PCT1), (8, "$B$6", PCT1), (10, "$B$7", PCT1), (12, "$B$8", PCT1), (21, "$B$9", USD), (22, "$B$10", PCT1)):
        c = ws.cell(row=idx, column=col, value=f"={ref}"); style_link(c, fmt)
    R = idx
    formulas = {
        7:  f'=IF($B{R}="","",IF($E{R}="",$C{R},$E{R})*$F{R})',                    # G duties
        9:  f'=IF($B{R}="","",$B{R}*$H{R})',                                    # I platform fee
        11: f'=IF($B{R}="","",$B{R}*$J{R})',                                    # K returns
        13: f'=IF($B{R}="","",$B{R}*$L{R})',                                    # M affiliate
        14: f'=IF($B{R}="","",$C{R}+$D{R}+$G{R}+$I{R}+$K{R})',                  # N landed
        15: f'=IF($B{R}="","",$N{R}+$M{R})',                                    # O fully loaded
        16: f'=IF($B{R}="","",$B{R}-$O{R})',                                    # P contribution
        17: f'=IF(OR($B{R}="",$B{R}=0),"",$P{R}/$B{R})',                        # Q margin %
        18: f'=IF(OR($B{R}="",$C{R}="",$C{R}=0),"",$B{R}/$C{R})',               # R markup supplier
        19: f'=IF(OR($B{R}="",$N{R}="",$N{R}=0),"",$B{R}/$N{R})',               # S markup landed
        20: f'=IF($B{R}="","",$P{R})',                                          # T break-even CPA
        23: f'=IF(OR($B{R}="",$U{R}="",$V{R}="",$V{R}=0),"",$U{R}/$V{R})',      # W expected CPA
        24: f'=IF(OR($T{R}="",$W{R}=""),"",$T{R}-$W{R})',                       # X headroom
        25: f'=IF(OR($T{R}="",$W{R}=""),"",IF($W{R}<=$T{R},"✓ PASS","✗ FAIL"))', # Y gate
        26: f'=IF(OR($B{R}="",$U{R}="",$T{R}="",$T{R}<=0),"",$U{R}/$T{R})',     # Z required CVR
    }
    fmts = {7: USD, 9: USD, 11: USD, 13: USD, 14: USD, 15: USD, 16: USD, 17: PCT1, 18: MULT, 19: MULT, 20: USD, 23: USD, 24: USD, 26: PCT1}
    for col, f in formulas.items():
        c = ws.cell(row=idx, column=col, value=f); style_formula(c, fmts.get(col))
    ws.cell(row=idx, column=25).alignment = Alignment(horizontal="center", vertical="center")
    ws.cell(row=idx, column=25).font = Font(name="Calibri", size=11, bold=True)
    ws.cell(row=idx, column=1).alignment = Alignment(horizontal="left", vertical="center")

# Gate conditional formatting
ws.conditional_formatting.add(f"Y{FIRST}:Y{LAST}", FormulaRule(formula=[f'$Y{FIRST}="✓ PASS"'], fill=PatternFill("solid", fgColor="C6EFCE"), font=Font(color="006100", bold=True)))
ws.conditional_formatting.add(f"Y{FIRST}:Y{LAST}", FormulaRule(formula=[f'$Y{FIRST}="✗ FAIL"'], fill=PatternFill("solid", fgColor="FFC7CE"), font=Font(color="9C0006", bold=True)))
ws.conditional_formatting.add(f"Q{FIRST}:Q{LAST}", CellIsRule(operator="lessThan", formula=["0.2"], fill=PatternFill("solid", fgColor="FFC7CE")))
ws.conditional_formatting.add(f"X{FIRST}:X{LAST}", CellIsRule(operator="lessThan", formula=["0"], fill=PatternFill("solid", fgColor="FFC7CE")))

# ============ Sheet: Scenario Grid ============
# Break-even CPA sensitivity for the first example product, varying tariff (rows) x affiliate (cols).
sg = wb.create_sheet("Scenario Grid")
sg.sheet_properties.pageSetUpPr.fitToPage = True
sg.page_setup.orientation = "landscape"; sg.page_setup.fitToWidth = 1; sg.page_setup.fitToHeight = 1
sg.column_dimensions["A"].width = 30
for col in "BCDEFG":
    sg.column_dimensions[col].width = 15
sg.merge_cells("A1:G1"); sg["A1"] = "Scenario Grid — Break-even CPA sensitivity (first product on Margin Calculator)"; sg["A1"].font = TITLE_F
sg.merge_cells("A2:G2"); sg["A2"] = "Change tariff (rows) or affiliate (columns) and read the Break-even CPA. Compare each cell to your Expected CPA: break-even must be higher to PASS."
sg["A2"].font = SUB_F
sg["A4"] = "Base product (linked)"; sg["A4"].font = LABEL_B; sg["A4"].fill = SUBHDR_FILL
base = [
    ("Sell Price", "='Margin Calculator'!B15", USD), ("Supplier Cost", "='Margin Calculator'!C15", USD),
    ("Ship & Pick/Pack", "='Margin Calculator'!D15", USD), ("Declared Value (blank→supplier)", "='Margin Calculator'!E15", USD),
    ("Platform Rate", "='Margin Calculator'!H15", PCT1), ("Returns Rate", "='Margin Calculator'!J15", PCT1),
    ("Expected CPA (CPC÷CVR, linked)", "='Margin Calculator'!W15", USD),
]
for i, (label, ref, fmt) in enumerate(base):
    rr = 5 + i
    sg.cell(row=rr, column=1, value=label).font = LABEL_F
    c = sg.cell(row=rr, column=2, value=ref); style_link(c, fmt)
sg["A13"] = "How to read: find the cell where Break-even CPA ≥ Expected CPA — only those tariff/affiliate combos keep the product viable at your current ad performance."
sg["A13"].font = NOTE_F; sg.merge_cells("A13:G13")

GRID_TOP = 15
sg.cell(row=GRID_TOP, column=1, value="Tariff ↓  /  Affiliate →").font = HDR_F
sg.cell(row=GRID_TOP, column=1).fill = HDR_FILL; sg.cell(row=GRID_TOP, column=1).border = BORDER
affiliates = [0, 0.10, 0.15, 0.20, 0.25, 0.30]
for j, a in enumerate(affiliates):
    c = sg.cell(row=GRID_TOP, column=2 + j, value=a); c.font = HDR_F; c.fill = HDR_FILL; c.border = BORDER; c.number_format = PCT1; c.alignment = Alignment(horizontal="center")
tariffs = [0, 0.15, 0.25, 0.35, 0.50, 0.65]
for i, t in enumerate(tariffs):
    rr = GRID_TOP + 1 + i
    c = sg.cell(row=rr, column=1, value=t); style_input(c); c.number_format = PCT1
    for j in range(len(affiliates)):
        col = get_column_letter(2 + j)
        # Contribution = Sell - [Supplier+Ship + Declared*Tariff + Sell*Platform + Sell*Returns + Sell*Affiliate]
        f = (f"=$B$5-($B$6+$B$7+IF($B$8=\"\",$B$6,$B$8)*$A{rr}"
             f"+$B$5*$B$9+$B$5*$B$10+$B$5*{col}${GRID_TOP})")
        cc = sg.cell(row=rr, column=2 + j, value=f); style_formula(cc, USD)
sg.cell(row=GRID_TOP + 8, column=1, value="Verdict grid — PASS if Break-even CPA ≥ Expected CPA").font = LABEL_B
sg.cell(row=GRID_TOP + 8, column=1).fill = SUBHDR_FILL
for j in range(len(affiliates)):
    c = sg.cell(row=GRID_TOP + 8, column=2 + j, value=affiliates[j]); c.number_format = PCT1; c.font = LABEL_B; c.border = BORDER
for i in range(len(tariffs)):
    rr = GRID_TOP + 9 + i; src = GRID_TOP + 1 + i
    sg.cell(row=rr, column=1, value=tariffs[i]).number_format = PCT1
    sg.cell(row=rr, column=1).font = LABEL_F; sg.cell(row=rr, column=1).border = BORDER
    for j in range(len(affiliates)):
        col = get_column_letter(2 + j)
        c = sg.cell(row=rr, column=2 + j, value=f'=IF({col}{src}>=$B$11,"✓ PASS","✗ FAIL")')
        c.font = Font(name="Calibri", size=11, bold=True); c.border = BORDER; c.alignment = Alignment(horizontal="center")
sg.conditional_formatting.add(f"B{GRID_TOP+9}:G{GRID_TOP+14}", FormulaRule(formula=[f'B{GRID_TOP+9}="✓ PASS"'], fill=PatternFill("solid", fgColor="C6EFCE"), font=Font(color="006100", bold=True)))
sg.conditional_formatting.add(f"B{GRID_TOP+9}:G{GRID_TOP+14}", FormulaRule(formula=[f'B{GRID_TOP+9}="✗ FAIL"'], fill=PatternFill("solid", fgColor="FFC7CE"), font=Font(color="9C0006", bold=True)))

# Order sheets, print settings
wb.active = wb["Margin Calculator"]
for s in (wb["How to Use"], wb["Margin Calculator"], sg):
    s.sheet_properties.pageSetUpPr.fitToPage = True
os.makedirs(os.path.dirname(OUT), exist_ok=True)
wb.save(OUT)
print("saved:", OUT)
