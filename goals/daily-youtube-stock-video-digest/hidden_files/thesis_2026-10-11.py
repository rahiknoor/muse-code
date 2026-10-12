#!/usr/bin/env python3
"""Write thesis JSON files for ThesisLog --set."""
import json, os

D = "/home/hatch/workspace/goals/daily-youtube-stock-video-digest/hidden_files/theses_2026-10-11"
os.makedirs(D, exist_ok=True)

def w(name, obj):
    json.dump(obj, open(f"{D}/{name}.json", "w"), indent=1, ensure_ascii=False)

w("pt-333", {
 "growth_story": "Parkev: exciting business on the right track with a cash-flow inflection and growing government demand, but the space-stock frenzy priced it far beyond his DCF ($5.50 vs $17.50 market) - he'd only consider it around $8.",
 "drivers": [
  {"driver": "Cash-flow inflection: $68M CFO YTD, $21M FCF after capex; $865M cash on hand.",
   "quote": "The company year-to-date has generated roughly $68 million in cash flow from operations and even after the increase in capital expenditures, it still generated 21 million in free cash flow."},
  {"driver": "RPO $753M (+9%); $400M+ revenue recognizable over next four quarters from existing contracts.",
   "quote": "By executing on contracts we already have, we could recognize over 400 million in revenue over the next four quarters, not even including any new business."},
  {"driver": "Analysts expect 47%/30%/28% revenue growth through FY2029; government demand for satellite imagery.",
   "quote": "A lot of demand for the company's products are coming from countries that are looking to secure their regions and their satellite imagery certainly helps with that."}],
 "risks": [
  {"risk": "SpaceX-IPO frenzy took PL $20 -> $50+ then collapsed below pre-frenzy; still 3x his fair value.",
   "quote": "Planet Lab shares were trading at about $20 in February and soared to above $50 per share by June."},
  {"risk": "Forward P/S 11 (was 30+); management diluted opportunistically at $32 avg.",
   "quote": "raising approximately $120 million from stock sales with an average sale price of $32."}],
 "refs": {"commentary": "cc-099", "research": ["cr-046"], "assumptions": []}})

w("pt-334", {
 "growth_story": "Parkev: Brian Niccol's return via the rumored SBUX acquisition plus operational synergies support CMG, but the 7% takeover pop closed the gap to his $32 fair value - no longer a buy.",
 "drivers": [
  {"driver": "Niccol turned CMG around once (2016-2024); his return would likely boost performance from the post-2024 decline.",
   "quote": "there is widespread agreement that Brian Nickel did a fantastic job while the leader of Chipotle turning that company around... turning it into one of the best restaurant companies in the world."},
  {"driver": "Operational synergies: combined purchasing, shared real estate teams, one CEO/board/HR.",
   "quote": "If you're negotiating with, let's say, Coca-Cola as a combined entity, you can attract more favorable terms."}],
 "risks": [
  {"risk": "Data says acquirers typically overpay; deal price and structure unknown; SBUX fell 4% on the news.",
   "quote": "The reason is there's data to support the fact that companies typically overpay to make an acquisition."},
  {"risk": "Consumer-side integration unclear; stock now at fair value, no margin of safety.",
   "quote": "I don't see how those can be integrated selling coffee at Chipotle or selling burritos at Starbucks."}],
 "refs": {"commentary": "cc-097", "research": ["cr-050"], "assumptions": []}})

w("pt-335", {
 "growth_story": "Parkev: SBUX has been overvalued for some time; the potential CMG acquisition doesn't change his $63 fair value, leaving the stock ~30% above it at $89.",
 "drivers": [
  {"driver": "Niccol's leadership; the CMG deal could add operational synergies.",
   "quote": "Starbucks is working with ABS Advisors to potentially acquire Chipotle."}],
 "risks": [
  {"risk": "~30% above his fair value at $89; overpay risk on the CMG deal.",
   "quote": "at $89, it looks to be roughly 30% more than my fair value estimate at 63."}],
 "refs": {"commentary": "cc-097", "research": ["cr-050"], "assumptions": []}})

w("pt-336", {
 "growth_story": "Parkev: one of the most excellent businesses in the world at a fair price - away-from-home dominance, record 32% margins, ROIC 17.9% vs 7.16% WACC; reiterated buy with high conviction.",
 "drivers": [
  {"driver": "Away-from-home channel dominance via exclusive partnerships; out-of-home consumption tailwind.",
   "quote": "Well, because the company has a larger market share in away-from-home channels. You may have noticed when you visit restaurants, you're more likely to see Coca-Cola as the exclusive beverage partner."},
  {"driver": "Record 32% operating margin (~2x PepsiCo); ROIC 17.9% vs 7.16% WACC; $51B TTM revenue.",
   "quote": "Coca-Cola reported record-high operating profit margins over the trailing 12-month period at 32%."}],
 "risks": [
  {"risk": "Forward P/E 24 near multi-year high; premium vs PepsiCo at 13.9.",
   "quote": "Coca-Cola's trading at a forward PE of 24, and that's a premium valuation compared to PepsiCo at 13.9."},
  {"risk": "Macro headwinds for consumer spending.",
   "quote": "the business is still facing macroeconomic headwinds like any other consumer-facing business as people have less money to spend."}],
 "refs": {"commentary": "cc-100", "research": ["cr-047"], "assumptions": ["ca-021"]}})

w("pt-337", {
 "growth_story": "Parkev: agentic AI is driving cybersecurity demand; ZS has 10x'd revenue since 2017 with >80% gross margins and is fairly valued ($203 vs $213) - one of the last cyber stocks he still ranks a buy.",
 "drivers": [
  {"driver": "Agentic AI proliferation driving cybersecurity demand; management targets $8-10B ARR by 2031.",
   "quote": "The rise of AI agents is creating an increasing demand for cybersecurity. Zscaler's management team said that's a big driver of demand for the company's products and services."},
  {"driver": ">80% gross margins; strong cash-flow margins; organic growth. Forward P/E 38 near lower end of history.",
   "quote": "Management is forecasting above 80% gross profit margins, and on a cash flow to sales ratio, the business is earning in the trailing 12-month period, which has improved dramatically since 2019."}],
 "risks": [
  {"risk": "Growth decelerating (18% -> 16.5% -> 16.2% per analysts); management's ARR forecast seen as ambitious.",
   "quote": "analysts on Wall Street... point to that forecast as being ambitious rather than conservative."},
  {"risk": "Cybersecurity stocks soared in 2026 - drove his PANW/FTNT downgrades; ZS only ~5% below market.",
   "quote": "It's one of the last cybersecurity stocks I still have ranked as a buying opportunity because cybersecurity stocks overall have soared in 2026."}],
 "refs": {"commentary": "cc-101", "research": ["cr-048"], "assumptions": []}})

w("oc-017", {
 "growth_story": "Chris Sain: AMD is 'on deck' as one of the next trillion-dollar companies, heading to $2T; momentum is so strong it will double from ~$600 to $1,200.",
 "drivers": [
  {"driver": "Trillion-dollar trajectory; his call history (owned from $52) builds his conviction in the name.",
   "quote": "I'm putting AMD on deck because they are about to be one of the next trillion dollar companies and they are definitely going to be a $2 trillion company."}],
 "risks": [
  {"risk": "Momentum/technical basis, not valuation work; no timeframe or downside discussion.",
   "quote": "I can just about guarantee that this stock will double."}],
 "refs": {"commentary": "cc-102", "research": [], "assumptions": []}})

w("oc-018", {
 "growth_story": "Chris Sain: META's valuation is attractive after the pullback; any dip into $400-$600 is a steal on the way to $1,000.",
 "drivers": [
  {"driver": "Attractive P/E and valuation; Zuckerberg's innovation and trend alignment position Meta to win.",
   "quote": "The PE on this is attractive. The valuation on this is attractive, guys."}],
 "risks": [
  {"risk": "No fundamental framework given; $1,000 target restated from his October 5 call.",
   "quote": "another one that's headed to $1,000 from here."}],
 "refs": {"commentary": "cc-102", "research": [], "assumptions": []}})

w("oc-019", {
 "growth_story": "Patient Investor: QCOM's edge-computing dominance positions it to own physical AI (humanoids, robotics, Meta glasses) plus a new Amazon data-center deal - $18 2029 EPS x 18x PE = $324.",
 "drivers": [
  {"driver": "Edge-computing leader; Snapdragon powers Meta's glasses; Amazon $4B data-center partnership.",
   "quote": "All the stuff that you've seen Meta announced, the VR glasses, Rayband... all of it is powered by Snapdragon which is mainly the Qualcomm chip."},
  {"driver": "Non-mobile chips to reaccelerate ~60% in 2027, replacing the Apple drag; automotive +61%.",
   "quote": "the non-mobile chip revenues expected to reacelerate to about 60% and reaceleration alone will off replace all the Apple lost product revenue in 2026."}],
 "risks": [
  {"risk": "'Trap come' history: flat five years; TTM revenue -4%, EPS -20%; Apple exit drags through mid-2027.",
   "quote": "Like revenues declined 4%, earnings per share declined 20%."}],
 "refs": {"commentary": "cc-103", "research": ["cr-049"], "assumptions": ["ca-022"]}})

print("thesis files written:", len([f for f in __import__('os').listdir(D) if f.endswith('.json')]))
