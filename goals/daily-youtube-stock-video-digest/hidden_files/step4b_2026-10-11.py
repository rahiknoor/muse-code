#!/usr/bin/env python3
"""STEP 4b: research, targets, buys appends. Appends only; backups first."""
import json, shutil, datetime

TR = "/home/hatch/workspace/creator_tracker"
TS = datetime.datetime.now().strftime("%Y%m%d%H%M")

def load(name):
    p = f"{TR}/{name}"
    shutil.copy(p, p + f".prebak-{TS}")
    return json.load(open(p))

def save(name, d):
    json.dump(d, open(f"{TR}/{name}", "w"), indent=1, ensure_ascii=False)

def R(kind, text, quote):
    return {"kind": kind, "text": text, "quote": quote}

cr = load("creator_research.json")["entries"]
cr += [
 {"id": "cr-046", "date": "2026-10-11", "creator": "parkev-tatevosian", "ticker": "PL",
  "video_id": "7kTPgbUy5eA",
  "video_url": "https://www.youtube.com/watch?v=7kTPgbUy5eA",
  "video_title": "Is Planet Labs an Undervalued Space Stock to Buy Right Now in October? | PL Stock Analysis",
  "research": [
   R("thesis_driver", "Cash-flow inflection: $68M CFO YTD, still $21M FCF after higher capex; $865M cash/short-term investments.",
     "The company year-to-date has generated roughly $68 million in cash flow from operations and even after the increase in capital expenditures, it still generated 21 million in free cash flow."),
   R("thesis_driver", "RPO $753M (+9%); management could recognize $400M+ revenue over the next four quarters from existing contracts alone.",
     "as of the end of the second quarter, their remaining performance obligations were 753 million. That was up approximately 9%."),
   R("thesis_driver", "Analysts expect revenue growth 47% (FY ending Jan 2027), 30% (FY2028), 28% (FY2029); growth likely to continue a decade+.",
     "they're estimating the company's revenue grows by 47% in the current fiscal year, which ends in January of 2027, and then another 30% growth in the fiscal year that ends in January of 2028, followed by 28% growth in the fiscal year that ends January of 2029."),
   R("thesis_driver", "Government demand for satellite imagery (regional security); AI analytics adding premium margin.",
     "A lot of demand for the company's products are coming from countries that are looking to secure their regions and their satellite imagery certainly helps with that."),
   R("risk", "Space-stock frenzy risk: SpaceX IPO euphoria took PL $20 -> $50+, then collapsed below pre-frenzy levels; FV $5.50 vs $17.50 market.",
     "Planet Lab shares were trading at about $20 in February and soared to above $50 per share by June."),
   R("risk", "Opportunistic dilution: raised ~$120M equity at avg $32 during the frenzy; forward P/S 11 (was 30+).",
     "raising approximately $120 million from stock sales with an average sale price of $32.")]},
 {"id": "cr-047", "date": "2026-10-11", "creator": "parkev-tatevosian", "ticker": "KO",
  "video_id": "qk5nGs1k1IA",
  "video_url": "https://www.youtube.com/watch?v=qk5nGs1k1IA",
  "video_title": "Is Coca Cola an Excellent Dividend Stock to Buy Right Now in October? | KO Stock Analysis",
  "research": [
   R("thesis_driver", "Away-from-home channel dominance (exclusive partnerships like McDonald's, Disneyland); post-pandemic out-of-home consumption.",
     "Well, because the company has a larger market share in away-from-home channels. You may have noticed when you visit restaurants, you're more likely to see Coca-Cola as the exclusive beverage partner."),
   R("thesis_driver", "TTM revenue $51B; record 32% operating margin (~2x PepsiCo); ROIC 17.9% (up from ~10% in 2021) vs WACC ~7.16.",
     "Coca-Cola reported record-high operating profit margins over the trailing 12-month period at 32%."),
   R("risk", "Forward P/E 24 near multi-year high; premium vs PepsiCo at 13.9.",
     "Coca-Cola's trading at a forward PE of 24, and that's a premium valuation compared to PepsiCo at 13.9."),
   R("risk", "Macro headwinds: consumers have less to spend; countered with affordable options in developing markets and premium in developed.",
     "the business is still facing macroeconomic headwinds like any other consumer-facing business as people have less money to spend.")]},
 {"id": "cr-048", "date": "2026-10-11", "creator": "parkev-tatevosian", "ticker": "ZS",
  "video_id": "OF-sVsizTFI",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "research": [
   R("thesis_driver", "Agentic AI driving cybersecurity demand; TTM revenue $3.3B (10x since 2017); management targets $8-10B ARR by 2031.",
     "The rise of AI agents is creating an increasing demand for cybersecurity. Zscaler's management team said that's a big driver of demand for the company's products and services."),
   R("thesis_driver", ">80% gross margins; strong cash flow to sales vs under 10% in 2019; organic demand, not discount-driven.",
     "Management is forecasting above 80% gross profit margins, and on a cash flow to sales ratio, the business is earning in the trailing 12-month period, which has improved dramatically since 2019, where it earned a cash flow to sales ratio of under 10%."),
   R("risk", "Decelerating growth: analysts see 18% -> 16.5% -> 16.2% through FY2029; mgmt's $10B ARR forecast seen as ambitious.",
     "analysts on Wall Street... point to that forecast as being ambitious rather than conservative."),
   R("risk", "Cybersecurity stocks soared in 2026 - sector-wide valuation risk drove his PANW/FTNT downgrades; ZS FV $203 vs $213 market is within margin of safety but not cheap.",
     "It's one of the last cybersecurity stocks I still have ranked as a buying opportunity because cybersecurity stocks overall have soared in 2026.")]},
 {"id": "cr-049", "date": "2026-10-11", "creator": "patient-investor", "ticker": "QCOM",
  "video_id": "SUGDjk3EBlM",
  "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
  "video_title": "2 Stocks That Could 10X From Here!",
  "research": [
   R("thesis_driver", "Edge-computing leader -> physical AI (humanoids, robotics, drones); Meta partnership - Snapdragon powers Meta's VR/Ray-Ban glasses.",
     "All the stuff that you've seen Meta announced, the VR glasses, Rayband, the other kind of glasses... all of it is powered by Snapdragon which is mainly the Qualcomm chip."),
   R("thesis_driver", "Amazon invested $4B in Qualcomm; custom silicon data-center deal - QCOM now a data-center player competing with AVGO/Marvell.",
     "They signed a partnership with Amazon. They're going to be making custom silicon chips for Amazon."),
   R("thesis_driver", "Patent royalties: Apple still pays royalties yearly despite moving away; owns 5G/6G IP.",
     "even though they're moving away from Qualcomm, they still have to pay Qualcomm royalties every single year."),
   R("thesis_driver", "Automotive growing 61%; non-mobile chip revenues to reaccelerate ~60% in 2027, replacing Apple drag by mid-2027.",
     "the non-mobile chip revenues expected to reacelerate to about 60% and reaceleration alone will off replace all the Apple lost product revenue in 2026."),
   R("risk", "'Trap come' history: flat 5 years, old shareholders sell rallies; TTM revenue -4%, EPS -20% on handset decline.",
     "If you look at the financials, you don't you don't even want to invest in it because uh it just looks awful. Like revenues declined 4%, earnings per share declined 20%."),
   R("risk", "Apple exit drag runs through mid-2027 before internal growth replaces it.",
     "This will end in 2027 and they don't have to do any acquisitions to replace this.")]},
 {"id": "cr-050", "date": "2026-10-11", "creator": "parkev-tatevosian", "ticker": "CMG",
  "video_id": "cTvk5J3F9XQ",
  "video_url": "https://www.youtube.com/watch?v=cTvk5J3F9XQ",
  "video_title": "Massive News for Chipotle and Starbucks Investors | CMG Stock SBUX Stock",
  "research": [
   R("thesis_driver", "Brian Niccol (turned CMG around 2016-2024) returning as acquirer CEO would likely boost CMG performance from its post-2024 decline.",
     "there is widespread agreement that Brian Nickel did a fantastic job while the leader of Chipotle turning that company around... turning it into one of the best restaurant companies in the world."),
   R("thesis_driver", "Operational synergies: combined purchasing (paper bags, cups, beverages), shared real estate development teams, one CEO/board/HR.",
     "If you're negotiating with, let's say, Coca-Cola as a combined entity, you can attract more favorable terms."),
   R("risk", "Data says acquirers typically overpay; deal price and structure unknown; SBUX shares fell 4% on the news.",
     "the acquired company, the shares rising, the company making the acquisitions, the shares fall. The reason is there's data to support the fact that companies typically overpay to make an acquisition."),
   R("risk", "Consumer-side integration unclear (coffee at Chipotle? burritos at Starbucks?); SBUX FV $63 vs $89 (~30% overvalued) stands.",
     "I don't see how those can be integrated selling coffee at Chipotle or selling burritos at Starbucks.")]}
]
d = load("creator_research.json"); d["entries"] = cr; save("creator_research.json", d)
print("research:", len(cr))

pt = load("parkev_targets.json")["entries"]
def pt_entry(pid, ticker, val, vtype, call_date, vid, title, quote, notes):
    return {"id": pid, "ticker": ticker, "company": None, "target_type": vtype,
     "target_value": val, "currency": "USD", "call_date": call_date,
     "video_title": title, "video_url": f"https://www.youtube.com/watch?v={vid}",
     "quote": quote, "notes": notes, "source": "daily-youtube-stock-video-digest 2026-10-11",
     "seeded": False, "backfilled": False, "checks": [], "status": "transcript-verified"}
pt += [
 pt_entry("pt-333", "PL", 5.50, "fair_value", "2026-10-11", "7kTPgbUy5eA",
  "Is Planet Labs an Undervalued Space Stock to Buy Right Now in October? | PL Stock Analysis",
  "I calculated a fair value of $5.50 for Planet Labs. Current market price is $17.50.",
  "Not a buy: would consider ~$8 after a further 50%+ drop. Forward P/S 11 (was 30+ during SpaceX-IPO frenzy). PL is not a Rahik holding."),
 pt_entry("pt-334", "CMG", 32, "fair_value", "2026-10-10", "cTvk5J3F9XQ",
  "Massive News for Chipotle and Starbucks Investors | CMG Stock SBUX Stock",
  "According to my calculation, I value the business at $32 per share. The current market price now after today's increase is about 33. So, it's a fairly priced business according to my estimates.",
  "Buy -> fairly valued on the SBUX-takeover news (+7%); he'd ranked CMG a buying opportunity for months. SBUX reportedly working with ABS Advisors on a potential CMG acquisition. CMG is not a Rahik holding."),
 pt_entry("pt-335", "SBUX", 63, "fair_value", "2026-10-10", "cTvk5J3F9XQ",
  "Massive News for Chipotle and Starbucks Investors | CMG Stock SBUX Stock",
  "at $89, it looks to be roughly 30% more than my fair value estimate at 63.",
  "Revision of pt-305 SBUX $66 (2026-09-27) -> $63 (-4.5%). Overvalued; he'd felt SBUX overvalued for some time. SBUX is not a Rahik holding."),
 pt_entry("pt-336", "KO", 86.60, "fair_value", "2026-10-10", "qk5nGs1k1IA",
  "Is Coca Cola an Excellent Dividend Stock to Buy Right Now in October? | KO Stock Analysis",
  "The conclusion, I calculated a fair value at $86.60, very close to its market price",
  "Reiterated buy with high conviction (ranked a buy since ~$40). Caption garbles the market price figure (~$88.05). KO is not a Rahik holding."),
 pt_entry("pt-337", "ZS", 203, "fair_value", "2026-10-10", "OF-sVsizTFI",
  "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "to arrive at a fair value, which for Zscaler arrived at $203 per share.",
  "Reiterated buy (first ranked buy at ~$160-170 on 2026-08-11; now ~$212). FV within ~5% of market, inside margin of safety. ZS is not a Rahik holding."),
]
d = load("parkev_targets.json"); d["entries"] = pt; save("parkev_targets.json", d)
print("parkev:", len(pt))

oc = load("other_creators_targets.json")["entries"]
oc += [
 {"id": "oc-017", "creator_channel": "Chris Sain", "ticker": "AMD", "company": "Advanced Micro Devices",
  "target_type": "price_target", "target_value": 1200, "price_at_call": 600, "currency": "USD",
  "call_date": "2026-10-10", "video_title": "NVIDIA Made Me Rich. I'm Buying This Stock Next.",
  "video_url": "https://www.youtube.com/watch?v=Nj3gEPvlOxk",
  "quote": "I'm putting AMD on deck because they are about to be one of the next trillion dollar companies and they are definitely going to be a $2 trillion company. I can just about guarantee that this stock will double... whether that's $600 to $1,200... it's going to 1,200 from here at least.",
  "notes": "Revision of oc-007 AMD $1,000 PT (2026-10-05) -> $1,200 (+20%). Momentum/technical basis; buy pullbacks. Rahik OWNS AMD.",
  "source": "daily-youtube-stock-video-digest 2026-10-11", "seeded": False, "checks": [], "status": "transcript-verified"},
 {"id": "oc-018", "creator_channel": "Chris Sain", "ticker": "META", "company": "Meta Platforms",
  "target_type": "price_target", "target_value": 1000, "price_at_call": 717.60, "currency": "USD",
  "call_date": "2026-10-10", "video_title": "NVIDIA Made Me Rich. I'm Buying This Stock Next.",
  "video_url": "https://www.youtube.com/watch?v=Nj3gEPvlOxk",
  "quote": "anywhere from $400 to around $600. This is a absolute steal. ... another one that's headed to $1,000 from here.",
  "notes": "Restatement of oc-008 META $1,000 PT (2026-10-05); adds $400-$600 buy-the-dip range. Transcript renders ticker as 'MA' - speech slip for META. Rahik OWNS META.",
  "source": "daily-youtube-stock-video-digest 2026-10-11", "seeded": False, "checks": [], "status": "transcript-verified"},
 {"id": "oc-019", "creator_channel": "The Patient Investor", "ticker": "QCOM", "company": "Qualcomm",
  "target_type": "price_target", "target_value": 324, "price_at_call": 170, "currency": "USD",
  "call_date": "2026-10-10", "video_title": "2 Stocks That Could 10X From Here!",
  "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
  "quote": "I could take the $18 of the company's guidance by 2029 and I type it by 18P, which is the current PE. I'm getting $324 per share by 2029, which is about 3 years from now.",
  "notes": "$18 EPS 2029 company guidance x 18x current P/E; ~90% upside from ~$170; +2.5% dividend. 'High conviction bet'. Rahik OWNS QCOM.",
  "source": "daily-youtube-stock-video-digest 2026-10-11", "seeded": False, "checks": [], "status": "transcript-verified"},
]
d = load("other_creators_targets.json"); d["entries"] = oc; save("other_creators_targets.json", d)
print("oc:", len(oc))

BUYS = "/home/hatch/workspace/creator_buys/buys.json"
b = json.load(open(BUYS))
if isinstance(b, dict):
    buys = b.get("buys", [])
else:
    buys = b
buys += [
 {"id": None, "creator": "Chris Sain", "company": "Advanced Micro Devices", "ticker": "AMD",
  "price_type": "plans_to_buy", "buy_price": None, "currency": "USD",
  "call_date": "2026-10-10", "video_title": "NVIDIA Made Me Rich. I'm Buying This Stock Next.",
  "video_url": "https://www.youtube.com/watch?v=Nj3gEPvlOxk", "current_price": None,
  "price_asof": None, "vs_buy_pct": None, "market_cap_usd_b": None,
  "notes": "Hourly job alerted 2026-10-11 02:55 ET (Price Trackers). 'I'm putting AMD on deck because they are about to be one of the next trillion dollar companies' / 'I can just about guarantee that this stock will double... $600 to $1,200'. PT $1,200 (~5yr). Not yet bought - buy_price null. Rahik OWNS AMD. source: daily-youtube-stock-video-digest | 2026-10-11 | 08:46 ET"},
 {"id": None, "creator": "Chris Sain", "company": "Meta Platforms", "ticker": "META",
  "price_type": "plans_to_buy", "buy_price": None, "currency": "USD",
  "call_date": "2026-10-10", "video_title": "NVIDIA Made Me Rich. I'm Buying This Stock Next.",
  "video_url": "https://www.youtube.com/watch?v=Nj3gEPvlOxk", "current_price": None,
  "price_asof": None, "vs_buy_pct": None, "market_cap_usd_b": None,
  "notes": "Hourly job alerted 2026-10-11 02:55 ET (Price Trackers). 'Next is Meta... anywhere from $400 to around $600. This is a absolute steal.' / 'another one that's headed to $1,000 from here.' PT $1,000. Not yet bought - buy_price null. Rahik OWNS META. source: daily-youtube-stock-video-digest | 2026-10-11 | 08:46 ET"},
]
json.dump(buys, open(BUYS, "w"), indent=1, ensure_ascii=False)
print("buys:", len(buys))
