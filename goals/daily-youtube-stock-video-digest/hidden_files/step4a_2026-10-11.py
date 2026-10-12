#!/usr/bin/env python3
"""STEP 4a: assumptions, guidance, commentary appends. Appends only; backups first."""
import json, shutil, datetime

TR = "/home/hatch/workspace/creator_tracker"
TS = datetime.datetime.now().strftime("%Y%m%d%H%M")

def load(name):
    p = f"{TR}/{name}"
    shutil.copy(p, p + f".prebak-{TS}")
    return json.load(open(p))

def save(name, d):
    json.dump(d, open(f"{TR}/{name}", "w"), indent=1, ensure_ascii=False)

ca = load("creator_assumptions.json")["assumptions"]
ca.append({
    "id": "ca-021", "ticker": "KO", "creator": "parkev", "date": "2026-10-10",
    "video_id": "qk5nGs1k1IA",
    "video_title": "Is Coca Cola an Excellent Dividend Stock to Buy Right Now in October? | KO Stock Analysis",
    "video_url": "https://www.youtube.com/watch?v=qk5nGs1k1IA",
    "discount_wacc": 7.16,
    "discount_wacc_quote": "discount those cash flows using a weighted average cost of capital, which for Coca-Cola I calculated at 7.16, less than half of its return on invested capital.",
    "notes": "DCF input WACC 7.16%. In the ROIC context he separately framed WACC as 'closer to 10%' (ROIC 17.9% at record highs vs ~10% in 2021) - the 7.16 is the number his DCF uses."
})
ca.append({
    "id": "ca-022", "ticker": "QCOM", "creator": "patient-investor", "date": "2026-10-10",
    "video_id": "SUGDjk3EBlM",
    "video_title": "2 Stocks That Could 10X From Here!",
    "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
    "pe_multiple": 18,
    "pe_multiple_quote": "I could take the $18 of the company's guidance by 2029 and I type it by 18P, which is the current PE.",
    "notes": "Valuation assumption: 18x P/E (current PE) applied to the company's $18 2029 EPS guidance -> $324 by 2029."
})
ca.append({
    "id": "ca-023", "ticker": "OUST", "creator": "patient-investor", "date": "2026-10-10",
    "video_id": "SUGDjk3EBlM",
    "video_title": "2 Stocks That Could 10X From Here!",
    "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
    "growth_rate": 35,
    "growth_rate_quote": "I put in their current revenues of 220 million for the next 10 years at 35% annual growth.",
    "notes": "10-yr model: $220M revenue at 35% CAGR -> $4.4B revenue; 10x EV/revenue -> ~$44B EV vs $2.6B current (~16.7x); at 5x sales still ~8.7x."
})
d = load("creator_assumptions.json"); d["assumptions"] = ca; save("creator_assumptions.json", d)
print("assumptions:", len(ca))

eg = load("earnings_guidance.json")["entries"]
eg += [
 {"id": "eg-015", "date": "2026-10-10", "ticker": "ZS", "company": "Zscaler",
  "creator": "parkev",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "guidance": "Management forecasts at least $8B in annual recurring revenue by 2031; in an upside scenario where agentic AI proliferation continues, as much as $10B ARR by 2031 (vs ~$3.3B TTM revenue).",
  "quote": "Management is optimistic about its prospects, forecasting at least $8 in annual recurring revenue by 2031... in an upside scenario where the proliferation of agentic AI continues, they could generate as much as $10 billion in annual recurring revenue by 2031."},
 {"id": "eg-016", "date": "2026-10-10", "ticker": "ZS", "company": "Zscaler",
  "creator": "parkev",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "guidance": "Wall Street analysts forecast revenue growth 18% in FY ending July 2027, 16.5% in FY2028, 16.2% in FY2029; TTM revenue $5.3B by July 2029.",
  "quote": "the analysts on Wall Street that are following Zscaler are forecasting its revenue growth rate to slow down considerably to 18% in the current fiscal year, which will end in July of 2027, and then slow further to 16.5% in the fiscal year that will end in 2028. And then slow further to a growth rate of 16.2% in the fiscal year that will end in July of 2029, at which point the analyst on Wall Street are forecasting trailing 12-month revenues of 5.3 billion"},
 {"id": "eg-017", "date": "2026-10-11", "ticker": "PL", "company": "Planet Labs",
  "creator": "parkev",
  "video_url": "https://www.youtube.com/watch?v=7kTPgbUy5eA",
  "video_title": "Is Planet Labs an Undervalued Space Stock to Buy Right Now in October? | PL Stock Analysis",
  "guidance": "Management: executing on contracts already held could recognize over $400M in revenue over the next four quarters, not even including any new business. RPO $753M (+9%) at end of Q2.",
  "quote": "By executing on contracts we already have, we could recognize over 400 million in revenue over the next four quarters, not even including any new business."},
 {"id": "eg-018", "date": "2026-10-10", "ticker": "QCOM", "company": "Qualcomm",
  "creator": "patient-investor",
  "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
  "video_title": "2 Stocks That Could 10X From Here!",
  "guidance": "Company believes it can reach $18 EPS by 2029; CFO said it could be conservative. Non-mobile chip revenues expected to reaccelerate to ~60% in 2027. Amazon invested $4B in Qualcomm (custom silicon partnership). 2.5% dividend.",
  "quote": "the company believes they can get to $18 by 2029. Very recently, the CFO said that it could potentially be conservative. like he pretty much said there's no reason why we can't exceed this number."},
 {"id": "eg-019", "date": "2026-10-10", "ticker": "OUST", "company": "Ouster",
  "creator": "patient-investor",
  "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
  "video_title": "2 Stocks That Could 10X From Here!",
  "guidance": "Company guiding 30-50% annual revenue growth for the next 5-10 years; expects profitability by mid-to-late 2027. 49% gross margins.",
  "quote": "The company is guiding 30 to 50% annual revenue growth for the next pretty much 5 to 10 years for the very long term."},
]
d = load("earnings_guidance.json"); d["entries"] = eg; save("earnings_guidance.json", d)
print("guidance:", len(eg))

cc = load("creator_commentary.json")["entries"]
cc += [
 {"id": "cc-095", "ticker": "PANW", "creator": "parkev-tatevosian", "conviction": "downgrade",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "summary": "Parkev downgraded Palo Alto Networks from buy on valuation concerns, after ranking it a buying opportunity for many years; cybersecurity stocks overall soared in 2026. Zscaler remains one of the few cyber buys.",
  "quotes": ["I've downgraded a few of them due to valuation concerns including Palo Alto Networks and Fortinet which I've had ranked as buying opportunities for many years, but in recent weeks I downgraded them due to valuation concerns."]},
 {"id": "cc-096", "ticker": "FTNT", "creator": "parkev-tatevosian", "conviction": "downgrade",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "summary": "Parkev downgraded Fortinet from buy on valuation concerns, after ranking it a buying opportunity for many years. Zscaler remains one of the few cyber buys.",
  "quotes": ["I've downgraded a few of them due to valuation concerns including Palo Alto Networks and Fortinet which I've had ranked as buying opportunities for many years, but in recent weeks I downgraded them due to valuation concerns."]},
 {"id": "cc-097", "ticker": "CMG", "creator": "parkev-tatevosian", "conviction": "downgrade-to-fair",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Massive News for Chipotle and Starbucks Investors | CMG Stock SBUX Stock",
  "video_url": "https://www.youtube.com/watch?v=cTvk5J3F9XQ",
  "summary": "CMG moved from buy (his ranking for several months) to fairly valued after the SBUX-takeover news sent shares +7% to ~$33 vs his $32 FV. Starbucks reportedly working with ABS Advisors on a potential Chipotle acquisition; Brian Niccol (ex-CMG CEO) returning would likely boost CMG performance; sees operational synergies, less consumer-side overlap.",
  "quotes": ["According to my calculation, I value the business at $32 per share. The current market price now after today's increase is about 33. So, it's a fairly priced business according to my estimates.", "Starbucks is working with ABS Advisors to potentially acquire Chipotle."]},
 {"id": "cc-098", "ticker": "LCID", "creator": "parkev-tatevosian", "conviction": "negative",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Lucid Reports Quarterly Production and Delivery Numbers",
  "video_url": "https://www.youtube.com/watch?v=-sntisUbsZs",
  "summary": "Parkev stays negative on Lucid: down >90-95% off highs but still not a buying opportunity; forward P/S 0.457 is a record low. Q3: delivered 3,806 vs produced ~2,906 (drawing down inventory); Gravity demand regaining momentum; ~$800M cash after burning from $6B+; deeply negative operating margins (improved from -500%). Captioning garbled his fair value number, so no value logged (STORY-GAP).",
  "quotes": ["it still doesn't look like an attractive buying opportunity to me. There's a lot more work to be done.", "If I was interested in Lucid stock, I would be waiting for a better buying opportunity to jump in."]},
 {"id": "cc-099", "ticker": "PL", "creator": "parkev-tatevosian", "conviction": "negative",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-11",
  "video_title": "Is Planet Labs an Undervalued Space Stock to Buy Right Now in October? | PL Stock Analysis",
  "video_url": "https://www.youtube.com/watch?v=7kTPgbUy5eA",
  "summary": "Parkev: exciting business on the right track, but NOT a buy at $17.50 vs his $5.50 FV; would start considering ~$8 after another 50%+ drop. Warns against space-stock frenzy repeat (SpaceX IPO euphoria took PL from $20 to $50+); raised $120M equity at avg $32 during the frenzy.",
  "quotes": ["I calculated a fair value of $5.50 for Planet Labs. Current market price is $17.50.", "I would like to see at least another 50% drop from here, where the stock is around $8 to start to meaningfully consider buying this stock. I wouldn't buy this stock as all else being equal at anywhere near these levels."]},
 {"id": "cc-100", "ticker": "KO", "creator": "parkev-tatevosian", "conviction": "high",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Is Coca Cola an Excellent Dividend Stock to Buy Right Now in October? | KO Stock Analysis",
  "video_url": "https://www.youtube.com/watch?v=qk5nGs1k1IA",
  "summary": "Parkev reiterated his KO buy (ranked a buying opportunity since ~$40) with high conviction at fair value; DCF FV $86.60 near market price. TTM revenue $51B; record 32% operating margin (~2x PEP); ROIC 17.9%; forward P/E 24 near multi-year high (PEP 13.9). Away-from-home channel dominance is the core thesis.",
  "quotes": ["So, I will reiterate that buy recommendation for Coca-Cola stock, and I have a relatively high confidence level, a relatively high conviction level that this is a good investment at current market prices when considering the risks and the reward potential.", "The conclusion, I calculated a fair value at $86.60, very close to its market price"]},
 {"id": "cc-101", "ticker": "ZS", "creator": "parkev-tatevosian", "conviction": "high",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "Is Zscaler an Undervalued Cybersecurity Stock to Buy Right Now in October? | ZS Stock Analysis",
  "video_url": "https://www.youtube.com/watch?v=OF-sVsizTFI",
  "summary": "Parkev reiterated ZS buy (first ranked buy at ~$160-170 on 2026-08-11; now ~$212). DCF FV $203 vs $213 market (~5% gap, within margin of safety). Forward P/E 38 near lower end of its historical range; decelerating growth drove the de-rating. TTM revenue $3.3B (10x since 2017); >80% gross margins. One of the last cyber stocks he still ranks as a buy.",
  "quotes": ["Today, I will reiterate that buy ranking for Zscaler stock. It's one of the last cybersecurity stocks I still have ranked as a buying opportunity because cybersecurity stocks overall have soared in 2026.", "to arrive at a fair value, which for Zscaler arrived at $203 per share."]},
 {"id": "cc-102", "ticker": "AMD", "creator": "chris-sain", "conviction": "high",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "NVIDIA Made Me Rich. I'm Buying This Stock Next.",
  "video_url": "https://www.youtube.com/watch?v=Nj3gEPvlOxk",
  "summary": "Chris Sain pounded the table on AMD ('on deck', will double, next trillion-dollar company, heading to $2T) with a $1,200 target (~$600 now). On META: buy-the-dip $400-$600 is 'a absolute steal', target $1,000. NVDA in the title was a hook only - no NVDA call (says he'll circle back). Palantir teaser: crossed $200, video coming. Rahik OWNS AMD and META. Hourly job alerted 2026-10-11 02:55 ET (Price Trackers).",
  "quotes": ["I'm putting AMD on deck because they are about to be one of the next trillion dollar companies and they are definitely going to be a $2 trillion company.", "I can just about guarantee that this stock will double. Okay? So whether that's $600 to $1,200, whether that's $500 to a,000, no matter the price point, this is going to double in route to this becoming a $2 trillion company.", "Next is Meta, ticker symbol MA. This one is currently sitting at $717.60... anywhere from $400 to around $600. This is a absolute steal.", "another one that's headed to $1,000 from here."]},
 {"id": "cc-103", "ticker": "QCOM", "creator": "patient-investor", "conviction": "high",
  "verified": True, "entry_date": "2026-10-11", "video_date": "2026-10-10",
  "video_title": "2 Stocks That Could 10X From Here!",
  "video_url": "https://www.youtube.com/watch?v=SUGDjk3EBlM",
  "summary": "Patient Investor: QCOM is a 'high conviction bet' - simple model $18 2029 EPS guidance x 18x PE = $324 by 2029 (~90% upside from ~$170, +2.5% dividend). Physical-AI edge-computing thesis (Meta partnership, Snapdragon in Meta glasses), Amazon $4B data-center deal. Also covered Ouster (OUST): $220M rev at 35% CAGR -> $4.4B in 10yrs, 10x EV/rev -> ~$44B EV vs $2.6B now (~16.7x); guiding 30-50% rev growth; profitable by mid/late 2027. Rahik OWNS QCOM.",
  "quotes": ["So, Qualcomm is a high conviction bet for me. I don't care how long it's going to take. I have at least a 5year horizon.", "I could take the $18 of the company's guidance by 2029 and I type it by 18P, which is the current PE. I'm getting $324 per share by 2029, which is about 3 years from now.", "I like the stock in the low30s, 30, 31, $32. Right now, it's a little bit extended, but you know, if you're a very long-term investor, it could still potentially make sense."]},
]
d = load("creator_commentary.json"); d["entries"] = cc; save("creator_commentary.json", d)
print("commentary:", len(cc))
