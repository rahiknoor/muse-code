#!/usr/bin/env python3
"""Build Product Shortlist v2 merged dataset. Part 2: cuts, unverified leads, assembly."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_v2_part1 import v1, N, new_tier1, new_tier2

# ---------- NEW cuts ----------
new_cuts = [
N("Polo Wear Men's Boxer Briefs (6/12 pack)", "$29.99", "Polo Wear", "eBay", 1, "Sold counts",
  "1,280 sold; 901 watchers (2026-10-06 scan)", "Bulk underwear value",
  "HIGH (sizing/fit returns on underwear)", "Cut (sizing)", "necessity", ["sizing"],
  "https://i.ebayimg.com/images/g/9AwAAeSwiolovzYW/s-l1600.webp", "https://www.ebay.com/itm/375724396960", section="cuts"),
N("Handheld Mini Portable Fan (1800mAh rechargeable)", "$8.64", "Unbranded", "Shein", 1, "Sold counts",
  "100+ reviews 4.73avg; #2 Bestseller in its category (2026-10-06 scan)", "Summer gadget",
  "Medium (rechargeable battery; prefer-no-batteries rule)", "Cut (battery)", "problem-solver", ["battery"],
  "https://img.ltwebstatic.com/v4/j/spmp/2026/05/07/7f/17781411662c033c9ff427895f8bc93d910a6b08f7_thumbnail_900x.webp",
  "https://us.shein.com/Handheld-Mini-Quiet-Portable-Fan-Fashionable-Full-Diamond-Fan-1800mAh-Rechargeable-Battery-Powered-Suitable-For-Travel-Outdoor-Holiday-Beach-And-Other-Outdoor-Activities-p-502221091.html", section="cuts"),
N("10000mAh Fast-Charging Power Bank (built-in cable, LED)", "$5.92", "QQW-LWM", "Shein", 1, "Sold counts",
  "10k+ sold; 1,000+ reviews 4.67avg; #1 Bestseller in Phone Power Banks (2026-10-06 scan)", "Charging essential",
  "Medium-High (lithium battery; shipping regulations; prefer-no-batteries rule)", "Cut (battery)", "problem-solver", ["battery"],
  "https://img.ltwebstatic.com/v4/j/spmp/2026/02/04/f9/1770214290fe8bb97819356c5304ea752a03cb5fc2_thumbnail_220x293.webp",
  "https://us.shein.com/2026-New-Model-Fast-Charging-Power-Bank-With-Built-In-Cable-10000mAh-Mini-Power-Bank-5W-20W-Fast-Charge-Portable-Mobile-Charger-Slim-And-Portable-Charger-With-LED-Display-Small-External-Battery-Pack-For-Phone-Charging-Comes-With-Charging-Cable-Large-Capacity-Suitable-For-Various-Electronic-Devices-Valentine-s-Day-Gift-p-389316705.html",
  section="cuts"),
N("Lieberwell Touch Cordless Table Lamp (dimmable crystal)", "$17.43", "Lieberwell", "Walmart", 2, "Review/order velocity",
  "4.4avg 113 ratings; bestseller badge; 'In 50+ people's carts' (2026-10-06 scan)", "Decor lamp",
  "Medium (battery-operated; prefer-no-batteries rule)", "Cut (battery)", "problem-solver", ["battery"],
  "https://i5.walmartimages.com/seo/Touch-Cordless-Table-Lamp-Dimmable-Crystal-Lamp-Battery-Operated-Light-Lamp-for-Bedroom-Living-Room-Bathroom-Hotel-Bar_35ccb337-182a-47d3-bc4d-3d30f9214803.5e1caea916661c8538c9c1fd94e9c910.jpeg?odnHeight=573&odnWidth=573&odnBg=FFFFFF",
  "https://www.walmart.com/ip/Touch-Cordless-Table-Lamp-Dimmable-Crystal-Lamp-Battery-Operated-Light-Lamp-for-Bedroom-Living-Room-Bathroom-Hotel-Bar/13392214867", section="cuts"),
N("MagPack for iPhone", "$19.99", "MagPack", "Reddit", 4, "Community interest only",
  "Upvotes/comments in gadget community; no buyer proof (2026-10-06 scan)", "Magnetic phone accessory",
  "Medium (battery product per community)", "Cut (battery)", "problem-solver", ["battery", "interest_only"], section="cuts"),
]

# ---------- NEW unverified leads ----------
new_leads = [
N("LOOPS Weekly Reset Rejuvenating Under Eye Mask (5 pack)", "$22.50", "LOOPS", "Walmart", 4, "No buyer proof",
  "No ratings yet (new trending item); sold by Pro Seller, fulfilled by Walmart (2026-10-06 scan)",
  "New beauty launch; eye-mask trend", "Medium (new item, no social proof)", "Unverified lead", "desire", ["no_ratings"],
  "https://i5.walmartimages.com/seo/Loops-Weekly-Reset-Rejuvenating-Hydrogel-Eye-Mask-Set-of-5_0ddccff1-b7f4-435a-84fc-53b2e19b5d9f.94a898424747fc305215945c6069da9f.jpeg?odnHeight=573&odnWidth=573&odnBg=FFFFFF",
  "https://www.walmart.com/ip/Loops-Weekly-Reset-Rejuvenating-Hydrogel-Eye-Mask-Set-of-5/19645772100", section="leads"),
N("Flipper Zero", "$169.00", "Flipper", "Reddit", 4, "Community interest only",
  "Upvotes/comments in gadget community; no buyer proof; above $50 band (2026-10-06 scan)",
  "Hacker-gadget cult following; pen-test tool", "Low-Medium (tech niche; above core band)",
  "Unverified lead", "problem-solver", ["interest_only", "premium_price"],
  None, None, section="leads"),
N("Geeta Everyday Backpack (indigo/camel)", "$138.00", "Geeta", "Reddit", 4, "Community interest only",
  "Upvotes/comments; no buyer proof; above $50 band (2026-10-06 scan)",
  "Everyday carry community favorite", "Medium (fit/preference subjective; above core band)",
  "Unverified lead", "desire", ["interest_only", "premium_price"],
  None, None, section="leads"),
]

# 8 eBay clothing rows without captured buyer counts -> unverified leads (honest labeling)
missing_counts = [
("6-pack Dickies Dritech Crew Socks (moisture control, size 6-12)", "$16.91", "Dickies",
 "https://i.ebayimg.com/images/g/1IsAAeSwNlRo7pTB/s-l1600.webp", "https://www.ebay.com/itm/277443521036", "necessity"),
("Fruit of the Loom Women's Bikini Underwear Multi-Pack", "$9.99", "Fruit of the Loom",
 "https://i.ebayimg.com/images/g/XEYAAeSwEyNqKAfH/s-l500.webp", "https://www.ebay.com/itm/800149483820", "necessity"),
("12-pair Winter Magic Gloves (stretchy knit, bulk pack)", "$17.99", "Unbranded",
 "https://i.ebayimg.com/images/g/2d4AAOSwl4Rk-Mc2/s-l1600.webp", "https://www.ebay.com/itm/115908827265", "necessity"),
("Men's Genuine Buffalo Leather Belt 1.5\" (handmade)", "$37.49", "Unbranded",
 "https://i.ebayimg.com/images/g/jwgAAeSwVNpqe7I-/s-l1600.webp", "https://www.ebay.com/itm/286790011136", "necessity"),
("Unisex Bucket Hat (fishing/safari, sun brim)", "$8.99", "Unbranded",
 "https://i.ebayimg.com/images/g/xwQAAeSw-95qhRB9/s-l1600.webp", "https://www.ebay.com/itm/284869397332", "necessity"),
("Women's Wide Buckle Elastic Stretch Belt", "$11.59", "Unbranded",
 "https://i.ebayimg.com/images/g/IZAAAOSw4uVkx3Xr/s-l500.webp", "https://www.ebay.com/itm/404412737504", "necessity"),
("2-pack Men's Fleece-Lined Winter Beanie Hats", "$11.99", "Unbranded",
 "https://i.ebayimg.com/images/g/1t4AAeSwCqtqi~Ye/s-l1600.webp", "https://www.ebay.com/itm/227489244539", "necessity"),
("TRENDOUX Winter Gloves (warm, flexible, touchscreen)", "$10.99", "TRENDOUX",
 "https://i.ebayimg.com/images/g/FtIAAeSw2t1qv9IG/s-l1600.webp", "https://www.ebay.com/itm/800748778314", "necessity"),
]
for nm, pr, br, img, url, lane in missing_counts:
    new_leads.append(N(nm, pr, br, "eBay", 4, "No buyer proof captured",
        "Listed in 2026-10-06 eBay basics scan; sold/watcher counts not captured (page fetch 500s) - recheck before sourcing",
        "One-size/low-fit-risk basic; belongs to the necessity lane",
        "Low (one-size items); size-labeled socks/underwear carry fit friction",
        "Unverified lead", lane, ["counts_missing"], img, url, section="leads"))

# 10 remaining Etsy rows -> demand intelligence, no item-level buyer proof
etsy_rest = [
("Venetian Romance Martini Picks (stainless steel cocktail skewers)", "$25.00", "DizzyDiva",
 "https://i.etsystatic.com/5451809/r/il/f29b2b/3385468207/il_794xN.3385468207_cv5x.jpg",
 "https://www.etsy.com/listing/1073046506/venetian-romance-martini-picks-cocktail", "problem-solver"),
("Personalized Family & Pet Portrait (custom framed wall art)", "$24.90", "CatiaCreative",
 "https://i.etsystatic.com/20482270/r/il/5932fb/5178672145/il_794xN.5178672145_lisz.jpg",
 "https://www.etsy.com/listing/920991283/personalized-framed-wall-art-for-family", "desire"),
("Pet Portrait Digital Download (custom dog/cat print)", "$19.31", "GooZooArt",
 "https://i.etsystatic.com/25742404/c/1656/1315/229/68/il/234d47/5776002121/il_680x540.5776002121_35vo.jpg",
 "https://www.etsy.com/listing/893779813/pet-portrait-digital-download-file", "desire"),
("Custom Mountain-View Dog Tag (engraved pet ID)", "$17.35", "Tag4MyPet",
 "https://i.etsystatic.com/24727225/c/2250/1789/365/625/il/32df29/3385776960/il_680x540.3385776960_3x6z.jpg",
 "https://www.etsy.com/listing/1083169596/dog-tag-custom-dog-tag-outdoor-pet-tag", "desire"),
("Handmade Wooden Dice Baseball Game (family tabletop)", "$55.00", "WillowberryWorkshop",
 "https://i.etsystatic.com/27414927/r/il/167adc/7179219526/il_680x540.7179219526_k0sz.jpg",
 "https://www.etsy.com/listing/4365494672/handmade-wooden-dice-baseball-game", "problem-solver"),
("Vintage 1977 Baseball Strategy Game (retro sports decor)", "$22.00", "UniqueHomeShop",
 "https://i.etsystatic.com/10474959/r/il/bc9914/7080537966/il_680x540.7080537966_62x5.jpg",
 "https://www.etsy.com/listing/4346390920/vintage-1977-baseball-strategy-game", "desire"),
("Pilsner Beer Print (German beverage poster)", "$15.89", "TilePatternsStudio",
 "https://i.etsystatic.com/41609690/r/il/58ec9a/7824502913/il_680x540.7824502913_bcga.jpg",
 "https://www.etsy.com/listing/1547466495/pilsner-beer-print-german-beverage", "desire"),
("Handmade Leather-Wrapped Flower Vase (minimalist decor)", "$68.00", "zeynNY",
 "https://i.etsystatic.com/9696294/r/il/47f4e6/7548065657/il_680x540.7548065657_fmrm.jpg",
 "https://www.etsy.com/listing/961449439/handmade-leather-wrapped-flower-vase", "desire"),
("Wood Coiled Planter (mid-century indoor planter)", "$22.00", "MorseStudioCo",
 "https://i.etsystatic.com/23301702/r/il/a87ee7/2580118473/il_680x540.2580118473_fe5k.jpg",
 "https://www.etsy.com/listing/865339439/wood-coiled-planter-matte-finish-mid", "problem-solver"),
("Artisan Soap & Lip Balm Gift Set (eco-friendly)", "$9.00", "MontroseNaturalSoap",
 "https://i.etsystatic.com/25338104/r/il/255x319.1587114468/il_255x319.1587114468_s0vb.jpg",
 "https://www.etsy.com/listing/1587114468/soap-lip-balm-gift-set-artisan-guest", "desire"),
]
for nm, pr, br, img, url, lane in etsy_rest:
    new_leads.append(N(nm, pr, br, "Etsy", 4, "Demand intelligence only",
        "Etsy demand pattern (giftable/personalized); no item-level buyer proof captured - 'Etsy-style, not Etsy-sourced'",
        "Giftable Etsy-style product idea; validate demand with sold counts before sourcing",
        "Low-Medium (7 of 12 Etsy shops accept no returns; personalized items can't be returned)",
        "Unverified lead", lane, ["etsy_intel"], img, url, section="leads"))

# ---------- classify v1 ----------
for r in v1:
    r['image_url'] = None
    r['listing_url'] = None
    if r['verdict'].startswith('Cut'):
        r['section'] = 'cuts'
    elif r['evidence_tier'] in (1, 2):
        r['section'] = 'main'
    else:
        r['section'] = 'leads'

# ---------- assemble & rank ----------
MAIN_ORDER = [
 "Protein Loaded Coffee (single packets, 16 flavors)",
 "Bella Vita Luxury Honey Oud EDP 100ml",
 "Nature x Science Rosemary Water and Rice Water",
 "PHOFAY Bikini Pain-Free Hair Removal Cream",
 "medicube Smooth & Clear Body Care Set",
 "Voyage Nova Extrait De Parfum",
 "EPAIS Essential Eyelash Growth Serum",
 "Potaroma Flapping Sandpiper Cat Toy",
 "Silver-Tone Open Twist Wire Cuff Bangle",
 "SANDAOYX Foldable Fabric Organizer Bin",
 "100% Cashmere Scotland Wool Scarf (oversize shawl wrap)",
 "SHEGLAM Set Me Up Brow Gel",
 "SHEGLAM All-In-One Volume & Length Mascara",
 "4pc Bohemian Multilayer Bracelet Set (heart, tree of life, acrylic beads)",
 "Vintage Bohemian Shell Beaded Double-Layer Necklace",
 "400pc 4-Gallon Small Drawstring Trash Bags",
 "79pc Maillard Hair Accessories Set (claw clips, scrunchies, elastic ties)",
 "Adjustable Posture Corrector",
 "Universal 360° Car Phone Mount",
 "Natural Chicken Jerky Dog Treats (100% chicken)",
 "Foldable Desktop Phone Holder (iPhone/Android)",
 "DALIX 22\" Large Cotton Canvas Zippered Tote",
 "10-pack Bamboo Cotton Ankle Socks (no-show, non-slip)",
 "Self-Cleaning Pet Grooming Brush (shedding comb, knot remover)",
 "PETSIN Cat Sisal Ball (self-entertaining chew/rattle)",
 "1000ct Easy-Tie Dog Poop Bags",
 "20-pack Furry Catnip Mice (real rabbit fur)",
 "3pc Facial Massage Scraping Beauty Tool Kit (roller, stick, ice gel pack)",
 "Pet Safety Seatbelt Harness Lead (2-pack)",
 "Shoe Cleaning Brush Polish Kit (leather case)",
 "12-pack Winter Beanie Hats (knit cuffed skull caps)",
 "Interactive Puzzle Slow Feeder",
 "Large Yak Cheese Chew (5 pcs)",
 "POOPH Litterizer Deodorizer (2-pack)",
 "12-pair Men's Cotton Crew Socks (Size 10-13)",
 "Anderson's Freeze-Dried Pet Treats (2oz)",
 "2-pack Dog Lick Mat (suction cups)",
 "4x Reusable Pet Hair Removal Tool",
 "Juicy Pineapple Snuffle Mat",
 # tier 2: review/order velocity
 "Lazzda Magnetic Anti-Lost Portable SSD",
 "Verdtera Changing Foundation Stick",
 "Ophanie Orthopedic Dog Bed (35x22, egg-crate foam)",
 "Pawmate Chicken Jerky & Duck/Cod Twist Dog Treats (28-29ct)",
 "COLOR WOW Dream Coat Supernatural Spray 6.7oz",
 "Medicube Zero Pore Pad 2.0 (70 pads)",
 "Medicube Collagen Jelly Cream 3.71oz",
 "Oneisall Automatic Cat Feeder 3.5L (dual power, stainless bowl)",
 "TJS Samsung Galaxy A16 5G Phone Case + Tempered Glass",
 "CONCETTA 26x38 Arched Bathroom Mirror (gold metal frame)",
 "Vitamasques Hydrate Milk Glaze Moisturizer 1.69oz",
 "Atgdluk Magnetic Shockproof iPhone 17 Pro Max Case (MagSafe, cherry red)",
 "BENTOBEN iPhone 11 Case 6.1 (translucent matte, magnetic)",
 "Pet Hair Remover Roller (reusable)",
 "Dog Lick Mat (single, silicone)",
 "TULA Rose Glow Glaze Plumping Lip Treatment (pink)",
 "Nuptio 6pc Brass Taper Candle Holders (vintage gold)",
 "Luna Personalized Dog Tag (pet ID, microchip, custom)",
 "Spa Gift Basket (birthday/mom/pamper self-care)",
]

all_rows = v1 + new_tier1 + new_tier2 + new_cuts + new_leads
by_name = {r['name']: r for r in all_rows}
main, leads, cuts = [], [], []
for nm in MAIN_ORDER:
    assert nm in by_name, "missing: " + nm
    r = by_name[nm]; r['rank'] = len(main) + 1; main.append(r)
# sanity: no main-section row left unranked
for r in all_rows:
    if r['section'] == 'main' and 'rank' not in r:
        print("UNRANKED MAIN:", r['name'])
        r['rank'] = len(main) + 1; main.append(r)
cuts = [r for r in all_rows if r['section'] == 'cuts']
for i, r in enumerate(cuts): r['rank'] = i + 1
leads = [r for r in all_rows if r['section'] == 'leads']
for i, r in enumerate(leads): r['rank'] = i + 1

out = {
    'generated': '2026-10-06',
    'version': 2,
    'count': len(main) + len(leads) + len(cuts),
    'sections': {
        'main': {'count': len(main), 'label': 'Main ranked shortlist (buyer-proven only)'},
        'leads': {'count': len(leads), 'label': 'Unverified leads (ad-spend / trend / interest only)'},
        'cuts': {'count': len(cuts), 'label': 'Cuts (fail a filter)'},
    },
    'ranking_rule': 'sold counts first, then review/order velocity, then sustained ad spend, then trend scores (Rahik, locked 2026-10-06). All FastMoss TikTok Shop figures are directional third-party data, not first-party verified.',
    'main': main,
    'leads': leads,
    'cuts': cuts,
}
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'products.json')
json.dump(out, open(path, 'w'), indent=1)
print("sections:", {k: v['count'] for k, v in out['sections'].items()}, "total:", out['count'])
print("main with photos:", sum(1 for r in main if r['image_url']))
print("top5:", [r['name'] for r in main[:5]])
