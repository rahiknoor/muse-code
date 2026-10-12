#!/usr/bin/env python3
"""Build ~/workspace/religion/quran/killing-verses.md from the corpus.
Re-run to rebuild. Verse texts are pulled verbatim from corpus/surahs.json."""
import json, re

BASE = "/home/hatch/workspace/religion/quran"
surahs = json.load(open(f"{BASE}/corpus/surahs.json"))
idx = {}
for s in surahs:
    for v in s["verses"]:
        idx[(s["number"], v["verse"])] = v["text"]

def t(ref):
    ch, vs = ref.split(":")
    return idx[(int(ch), int(vs))]

# Eschatological exclusion count
hell_n = sum(1 for v in idx.values() if re.search(r"\bhell\b", v, re.I))

E = []  # (sortkey, heading, refs, tag, clause, context)
def add(refs, tag, clause, context):
    first = refs[0]
    ch, vs = first.split(":")
    E.append(((int(ch), int(vs)), refs, tag, clause, context))

# ---------------- main list (surah order) ----------------
add(["2:30"], "NARRATIVE", "the angels foresee that humans will shed blood",
    "The primordial dialogue at creation; no occasion of revelation.")
add(["2:54"], "NARRATIVE", "Moses orders his people to kill themselves in repentance after the calf",
    "The calf-worship episode at Sinai; classical tafsir reports they were commanded to slay one another as repentance.")
add(["2:55", "2:56"], "NARRATIVE", "the thunderbolt strikes those who demanded to see God, then they are revived",
    "The Sinai episode; 4:153 retells the same event.")
add(["2:61"], "NARRATIVE", "the recurring charge that the Israelites killed prophets without right",
    "GROUPED — formulaic charge repeated at 2:87, 2:91, 3:21, 3:112, 3:181, 3:183, 4:155, 5:70. Representative verse 2:61 quoted below. No single occasion of revelation.")
add(["2:72", "2:73"], "NARRATIVE", "a murdered man is struck with part of the cow and revived",
    "The 'cow' story; the unsolved murder is the narrative occasion for the cow command.")
add(["2:84"], "NARRATIVE", "the covenant: do not shed one another's blood or evict one another",
    "The covenant with the Children of Israel.")
add(["2:85"], "PRESCRIPTIVE", "rebukes killing one another, evicting their own people, and ransoming captives",
    "A rebuke addressed to the Israelites for violating the covenant of 2:84.")
add(["2:154"], "PRESCRIPTIVE", "forbids calling those killed in God's way 'dead'",
    "Classical tafsir places this after the Battle of Badr (2 AH), concerning its martyrs.")
add(["2:177"], "PRESCRIPTIVE", "names patience 'during battle' among the marks of the righteous",
    "A general definition of righteousness; no firmly established occasion.")
add(["2:178", "2:179"], "PRESCRIPTIVE", "prescribes qisas — legal retribution for the murdered",
    "Classical tafsir links the qisas legislation to pre-Islamic tribal feuds and cycles of vengeance killing.")
add(["2:190"], "PRESCRIPTIVE", "permits fighting those who fight you, but forbids transgression",
    "Classical tafsir associates 2:190-194 with questions about fighting in the sacred months, in the period around the Treaty of Hudaybiyyah (6 AH); reports differ on the precise incident.")
add(["2:191"], "PRESCRIPTIVE", "commands killing attackers wherever overtaken and expelling them as they expelled you",
    "Same context as 2:190.")
add(["2:193"], "PRESCRIPTIVE", "commands fighting until there is no more fitnah",
    "Same context as 2:190.")
add(["2:194"], "PRESCRIPTIVE", "regulates fighting in the sacred month and affirms legal retribution",
    "Same context as 2:190.")
add(["2:205"], "PRESCRIPTIVE", "condemns the corruptor who destroys crops and livestock",
    "Reports name al-Akhnas ibn Shurayq as the occasion; commentators differ.")
add(["2:216"], "PRESCRIPTIVE", "enjoins fighting though it is hateful to you",
    "No firmly established occasion; general encouragement to fighting in God's cause.")
add(["2:217"], "PRESCRIPTIVE", "rules on fighting in the sacred month; fitnah is worse than killing",
    "Classical tafsir places this after the Nakhlah expedition, in which 'Abdullah ibn Jahsh's detachment killed a Qurayshi in the sacred month of Rajab.")
add(["2:218"], "PRESCRIPTIVE", "commends those who believed, emigrated, and fought",
    "General commendation of the fighting emigrants.")
add(["2:239"], "PRESCRIPTIVE", "regulates prayer on foot or riding when fearing an enemy",
    "The 'fear prayer'; general legislation.")
add(["2:243"], "NARRATIVE", "thousands fleeing death are told 'Die,' then restored to life",
    "Commentators identify the thousands variously (e.g., those fleeing plague); reports differ.")
add(["2:244"], "PRESCRIPTIVE", "commands fighting in the cause of Allah",
    "General command; no firmly established occasion.")
add(["2:246"], "NARRATIVE", "the Israelites demand a king so they may fight",
    "The Talut (Saul) narrative.")
add(["2:251"], "NARRATIVE", "David killed Goliath",
    "The Talut–Jalut (Saul–Goliath) narrative.")
add(["2:253"], "NARRATIVE", "after the messengers, later generations fought one another",
    "A summary of post-prophetic conflict; no occasion of revelation.")
add(["2:279"], "PRESCRIPTIVE", "declares a war from Allah and His Messenger against persistent usurers",
    "Classical tafsir links this to outstanding usurious debts owed to the Thaqif tribe after the conquest of Mecca.")
add(["3:13"], "NARRATIVE", "the two armies that met at Badr as a sign",
    "The Battle of Badr (2 AH).")
add(["3:111"], "PRESCRIPTIVE", "promises that if they fight you, they will turn their backs",
    "General assurance regarding the People of the Scripture; no firmly established occasion.")
add(["3:121"], "NARRATIVE", "posting the believers at their stations for battle",
    "The Battle of Uhud (3 AH); classical tafsir places 3:121-175 at Uhud.")
add(["3:123"], "NARRATIVE", "recalls the victory at Badr while few in number",
    "The Battle of Badr (2 AH).")
add(["3:125"], "NARRATIVE", "angelic reinforcement against the attacking enemy",
    "The Battle of Uhud (3 AH).")
add(["3:140"], "NARRATIVE", "battlefield wounds apportioned by days; martyrs taken from among you",
    "The Battle of Uhud (3 AH).")
add(["3:142"], "PRESCRIPTIVE", "Paradise requires that those who fight be made evident",
    "The Uhud context.")
add(["3:144"], "NARRATIVE", "if Muhammad dies or is killed, do not turn back on your heels",
    "Uhud; revealed amid the rumor of the Prophet's death in battle.")
add(["3:146"], "NARRATIVE", "past prophets fought, with scholars alongside them",
    "Recalls earlier communities at war; no occasion of revelation.")
add(["3:152"], "NARRATIVE", "you were killing the enemy by His permission until the disobedience",
    "The Battle of Uhud (3 AH) — the archers leaving their post.")
add(["3:153"], "NARRATIVE", "the flight up the mountain; distress upon distress",
    "The Battle of Uhud (3 AH).")
add(["3:154"], "NARRATIVE", "those decreed to be killed went forth to their places of death",
    "The Battle of Uhud (3 AH).")
add(["3:155"], "NARRATIVE", "Satan caused the slip on the day the two armies met",
    "The Battle of Uhud (3 AH).")
add(["3:156"], "PRESCRIPTIVE", "rebukes saying the slain would have lived had they stayed home",
    "The Uhud context; rebukes defeatist talk.")
add(["3:157", "3:158"], "PRESCRIPTIVE", "promises forgiveness and mercy to those killed in Allah's cause",
    "The Uhud context; general divine promise.")
add(["3:161"], "PRESCRIPTIVE", "declares it impossible for a prophet to misappropriate war booty",
    "General ruling; classical tafsir discusses it in the Badr/Uhud booty context.")
add(["3:165", "3:166"], "NARRATIVE", "the disaster at Uhud as occurring by Allah's permission",
    "The Battle of Uhud (3 AH).")
add(["3:167"], "NARRATIVE", "the hypocrites refuse the call: 'come, fight in the way of Allah'",
    "The Battle of Uhud (3 AH).")
add(["3:168"], "PRESCRIPTIVE", "rebukes 'if they had obeyed us, they would not have been killed'",
    "The Uhud context; addressed to those who stayed behind.")
add(["3:169"], "PRESCRIPTIVE", "forbids thinking those killed in Allah's cause are dead",
    "Classical tafsir places this concerning the martyrs of Uhud.")
add(["3:195"], "PRESCRIPTIVE", "promises reward to those who fought and were killed",
    "General divine promise; no firmly established occasion.")
add(["4:29"], "PRESCRIPTIVE", "forbids killing yourselves",
    "General prohibition; classical tafsir reads 'do not kill yourselves' as including suicide.")
add(["4:66"], "PRESCRIPTIVE", "poses a hypothetical decree — 'kill yourselves or leave your homes' — as a measure of obedience",
    "General illustration; not an actual command.")
add(["4:69"], "PRESCRIPTIVE", "numbers the martyrs among the favored company in Paradise",
    "General statement of ranks; no firmly established occasion.")
add(["4:74"], "PRESCRIPTIVE", "calls to fight those who sell this world for the Hereafter",
    "General encouragement to fighting.")
add(["4:75"], "PRESCRIPTIVE", "asks why you do not fight for the oppressed men, women, and children",
    "General call concerning the persecuted believers.")
add(["4:76"], "PRESCRIPTIVE", "believers fight in Allah's cause; commands fighting the allies of Satan",
    "General instruction.")
add(["4:77"], "PRESCRIPTIVE", "fighting prescribed for those once told to restrain their hands",
    "General; addresses those who had asked for fighting to be enjoined.")
add(["4:84"], "PRESCRIPTIVE", "commands the Prophet to fight and to encourage the believers",
    "Addressed to the Prophet generally; no firmly established occasion.")
add(["4:89"], "PRESCRIPTIVE", "commands seizing and killing treacherous apostates wherever found",
    "Classical tafsir associates 4:89-91 with groups who apostatized and aided the enemy; reports differ on their identity.")
add(["4:90"], "PRESCRIPTIVE", "exempts those with a treaty, or who withdraw from fighting you",
    "Same context as 4:89.")
add(["4:91"], "PRESCRIPTIVE", "commands seizing and killing those who revert to treachery",
    "Same context as 4:89.")
add(["4:92"], "PRESCRIPTIVE", "sets expiation for killing a believer by mistake: freeing a slave and blood money",
    "Homicide legislation; no single firmly established occasion is recorded for the expiation rules.")
add(["4:93"], "PRESCRIPTIVE", "assigns Hell to whoever kills a believer intentionally",
    "General ruling.")
add(["4:94"], "PRESCRIPTIVE", "commands verification when going forth to fight; do not call a peaceful greeter a disbeliever",
    "Classical tafsir, on Ibn 'Abbas's authority, places this after Muslims killed a man who had greeted them with peace, suspecting his profession of Islam was a ruse to protect his flock.")
add(["4:95"], "PRESCRIPTIVE", "prefers the fighters over those who remain behind",
    "General ranking; no firmly established occasion.")
add(["4:101"], "PRESCRIPTIVE", "permits shortening prayer when fearing the disbelievers will attack",
    "The travel/fear prayer; general legislation.")
add(["4:104"], "PRESCRIPTIVE", "forbids weakening in pursuit of the enemy",
    "Classical tafsir places this after Uhud, concerning the pursuit of the Quraysh (the Hamra' al-Asad expedition).")
add(["4:157"], "NARRATIVE", "denies that they killed the Messiah, Jesus son of Mary",
    "The crucifixion narrative; no occasion of revelation.")
add(["5:21"], "NARRATIVE", "commands entering the Holy Land and not turning back from fighting",
    "The Moses narrative.")
add(["5:24"], "NARRATIVE", "'go, you and your Lord, and fight' — the refusal to enter",
    "The Moses narrative.")
add(["5:27", "5:28", "5:30", "5:31"], "NARRATIVE", "the first murder: Cain kills Abel",
    "The Quran's paradigmatic murder story; no occasion of revelation.")
add(["5:32"], "PRESCRIPTIVE", "decrees that killing one soul is like killing all mankind",
    "Decreed 'upon the Children of Israel' immediately after the Cain and Abel account.")
add(["5:33"], "PRESCRIPTIVE", "sets the penalty for waging war against Allah: execution, crucifixion, amputation, or exile",
    "Classical tafsir (in Bukhari and Muslim) places this after the 'Ukl/'Urayna tribesmen murdered the shepherd, stole the camels, and apostatized.")
add(["5:38"], "PRESCRIPTIVE", "commands amputating the hands of male and female thieves",
    "General hadd legislation; no firmly established occasion.")
add(["5:45"], "PRESCRIPTIVE", "ordains life for life, an eye for an eye, a nose for a nose",
    "Ordained 'for them therein' — in the Torah for the Children of Israel.")
add(["5:64"], "NARRATIVE", "every time they kindled the fire of war, Allah extinguished it",
    "Descriptive of contemporaries; no occasion of revelation.")
add(["5:110"], "NARRATIVE", "Allah restrained the Children of Israel from killing Jesus",
    "The Jesus narrative; no occasion of revelation.")
add(["6:137"], "PRESCRIPTIVE", "condemns the killing of children made pleasing by false partners",
    "Classical commentators note this addresses the pre-Islamic Arab practice of killing children, including burying daughters alive.")
add(["6:140"], "PRESCRIPTIVE", "declares lost those who killed their children in foolishness",
    "Same pre-Islamic infanticide context as 6:137.")
add(["6:151"], "PRESCRIPTIVE", "forbids killing children for fear of poverty and killing the soul",
    "The prohibitions list; general.")
add(["7:124"], "NARRATIVE", "Pharaoh threatens to cut off hands and feet and crucify",
    "The magicians' conversion narrative.")
add(["7:127"], "NARRATIVE", "'we will kill their sons and keep their women' — Pharaoh's plan",
    "The Moses–Pharaoh narrative.")
add(["7:129"], "NARRATIVE", "'perhaps your Lord will destroy your enemy' — Moses' reassurance",
    "The Moses–Pharaoh narrative.")
add(["7:141"], "NARRATIVE", "recalls Pharaoh's people slaughtering your sons",
    "The Moses–Pharaoh narrative.")
add(["7:150"], "NARRATIVE", "Aaron: 'the people oppressed me and were about to kill me'",
    "The calf episode; Moses' return.")
add(["7:155"], "NARRATIVE", "the earthquake seizes the seventy chosen men",
    "The Sinai narrative.")
add(["8:1"], "PRESCRIPTIVE", "assigns the spoils of war to Allah and the Messenger",
    "Revealed after the Battle of Badr (2 AH) concerning the distribution of booty.")
add(["8:5"], "NARRATIVE", "the march out of home for the Battle of Badr",
    "The Battle of Badr (2 AH).")
add(["8:12"], "NARRATIVE", "the angels: 'strike them upon the necks and every fingertip'",
    "The Battle of Badr (2 AH).")
add(["8:15", "8:16"], "PRESCRIPTIVE", "forbids turning backs in battle except as tactic",
    "The Badr context.")
add(["8:17"], "NARRATIVE", "'you did not kill them, but Allah killed them' at Badr",
    "The Battle of Badr (2 AH).")
add(["8:19"], "NARRATIVE", "warns the defeated: if you return to hostilities, so shall We",
    "Addressed to the Quraysh after Badr.")
add(["8:30"], "NARRATIVE", "the plot to restrain, kill, or expel Muhammad",
    "The Meccan plot before the emigration.")
add(["8:39"], "PRESCRIPTIVE", "commands fighting until there is no fitnah",
    "The Badr-period context.")
add(["8:41"], "PRESCRIPTIVE", "assigns one-fifth of war booty to Allah, the Messenger, and named shares",
    "Revealed after Badr concerning booty distribution.")
add(["8:43"], "NARRATIVE", "the dream showing the enemy as few before Badr",
    "The Battle of Badr (2 AH).")
add(["8:45"], "PRESCRIPTIVE", "commands firmness when meeting an enemy force",
    "The Badr context.")
add(["8:48"], "NARRATIVE", "Satan's promise at Badr: 'no one can overcome you today'",
    "The Battle of Badr (2 AH).")
add(["8:57"], "PRESCRIPTIVE", "commands dispersing, through captives, those behind them in war",
    "General instruction on deterrence in war.")
add(["8:58"], "PRESCRIPTIVE", "permits renouncing a treaty on fear of betrayal, on equal terms",
    "General treaty law; no firmly established occasion.")
add(["8:60"], "PRESCRIPTIVE", "commands preparing steeds of war to terrify the enemy",
    "General military readiness; no firmly established occasion.")
add(["8:65"], "PRESCRIPTIVE", "urges the Prophet to rouse the believers to battle",
    "The Badr context.")
add(["8:67"], "PRESCRIPTIVE", "forbids a prophet taking captives before inflicting a decisive massacre",
    "Classical tafsir places this after the ransom of the Badr captives, when the Prophet consulted his companions on their fate.")
add(["8:69", "8:70"], "PRESCRIPTIVE", "declares the taken booty lawful; addresses the captives in hand",
    "After Badr; the captives' address follows the ransom decision.")
add(["8:72"], "PRESCRIPTIVE", "commends and defines the alliance of those who believed, emigrated, and fought",
    "GROUPED — the same commendation recurs at 8:74, 8:75, 9:88, and 16:110. Representative verse 8:72 quoted below. The Badr/early-Medinan context.")
add(["9:5"], "PRESCRIPTIVE", "commands killing the treaty-breaking polytheists wherever found, capturing and besieging them",
    "The declaration of disavowal (bara'ah), proclaimed at the 9 AH pilgrimage after the conquest of Mecca, concerning treaty-breaking polytheists.")
add(["9:12"], "PRESCRIPTIVE", "commands fighting the leaders of disbelief if they break oaths",
    "Same bara'ah context as 9:5.")
add(["9:13", "9:14"], "PRESCRIPTIVE", "commands fighting the oath-breakers who expelled the Messenger",
    "Same bara'ah context as 9:5.")
add(["9:25"], "NARRATIVE", "recalls the victory and the flight at Hunayn",
    "The Battle of Hunayn (8 AH).")
add(["9:29"], "PRESCRIPTIVE", "commands fighting those who do not believe until they pay the jizya",
    "Classical tafsir places this before the expedition to Tabuk (9 AH).")
add(["9:36"], "PRESCRIPTIVE", "commands fighting the polytheists collectively as they fight you collectively",
    "Same bara'ah context as 9:5.")
add(["9:38"], "PRESCRIPTIVE", "rebukes clinging heavily to the earth instead of going forth",
    "The expedition to Tabuk (9 AH).")
add(["9:41"], "PRESCRIPTIVE", "commands mobilization, light or heavy, with wealth and lives",
    "The expedition to Tabuk (9 AH).")
add(["9:52"], "PRESCRIPTIVE", "the two best outcomes awaited: victory or martyrdom",
    "Addressed to the hypocrites in the Tabuk context.")
add(["9:73"], "PRESCRIPTIVE", "commands fighting the disbelievers and the hypocrites, harshly",
    "General command; no firmly established occasion.")
add(["9:83"], "PRESCRIPTIVE", "bars the hypocrites from ever going out on expedition again",
    "The Tabuk context; those who stayed behind.")
add(["9:86"], "PRESCRIPTIVE", "the wealthy ask exemption from fighting with the Messenger",
    "The Tabuk context.")
add(["9:111"], "PRESCRIPTIVE", "Allah has purchased the believers' lives: they fight, kill, and are killed, for Paradise",
    "Often associated with the Pledge of 'Aqaba; commentators differ.")
add(["9:120"], "PRESCRIPTIVE", "counts every hardship and every infliction upon the enemy as a righteous deed",
    "The Tabuk context.")
add(["9:122"], "PRESCRIPTIVE", "not all believers go to battle; a party remains to learn the religion",
    "General organization for expeditions.")
add(["9:123"], "PRESCRIPTIVE", "commands fighting the nearby disbelievers and showing harshness",
    "General command; no firmly established occasion.")
add(["12:9", "12:10"], "NARRATIVE", "the brothers plot: 'kill Joseph or cast him out'",
    "The Joseph narrative.")
add(["16:81"], "NARRATIVE", "garments and coats of mail protecting from the enemy in battle",
    "Descriptive of divine favor; no occasion of revelation.")
add(["16:126"], "PRESCRIPTIVE", "permits punishing with the equivalent of the harm suffered",
    "Classical tafsir places this after the mutilation of Hamza at Uhud, when retaliation in kind was vowed.")
add(["17:7"], "NARRATIVE", "enemies sent to sadden faces and destroy what was taken over",
    "Commentators identify the two 'corruptions' with the Babylonian and Roman destructions of Jerusalem; details differ.")
add(["17:31"], "PRESCRIPTIVE", "forbids killing children for fear of poverty",
    "Addresses the pre-Islamic practice of infanticide.")
add(["17:33"], "PRESCRIPTIVE", "forbids killing the soul except by right; grants the heir authority over the killer",
    "General homicide law.")
add(["18:74"], "NARRATIVE", "al-Khidr kills a boy; Moses objects",
    "The Moses–Khidr narrative.")
add(["19:46"], "NARRATIVE", "Abraham's father threatens: 'I will surely stone you'",
    "The Abraham narrative.")
add(["20:40"], "NARRATIVE", "recalls: 'you killed someone' — Moses' killing in Egypt",
    "The Moses narrative.")
add(["20:71"], "NARRATIVE", "Pharaoh threatens to cut off hands and feet and crucify",
    "The magicians' conversion narrative.")
add(["21:68", "21:69", "29:24"], "NARRATIVE", "'Burn him' / 'Kill him or burn him' — Abraham cast into the fire",
    "The Abraham narrative; the fire is made coolness and safety.")
add(["21:80"], "NARRATIVE", "David taught the fashioning of coats of armor for battle",
    "Descriptive of divine favor; no occasion of revelation.")
add(["22:15"], "PRESCRIPTIVE", "rhetorical challenge: let him extend a rope and cut off his breath",
    "Classical commentators read this as a rhetorical challenge — 'let him kill himself in rage'; reports differ on the occasion. Not an instruction to self-harm.")
add(["22:39"], "PRESCRIPTIVE", "grants permission to fight to those who are wronged",
    "Ibn 'Abbas and others identify this as the first verse permitting fighting, revealed after the emigration amid Qurayshi persecution.")
add(["22:58"], "PRESCRIPTIVE", "promises provision to emigrants who were killed or died",
    "General divine promise.")
add(["24:2"], "PRESCRIPTIVE", "commands lashing unmarried offenders of zina with one hundred lashes",
    "General hadd legislation; no firmly established occasion.")
add(["24:4"], "PRESCRIPTIVE", "commands eighty lashes for those who accuse chaste women without four witnesses",
    "General hadd legislation against slander.")
add(["25:68"], "PRESCRIPTIVE", "forbids killing the soul which Allah has forbidden",
    "General description of the believers.")
add(["26:14"], "NARRATIVE", "Moses: 'I fear they will kill me' for the sin upon me",
    "The Moses narrative.")
add(["26:49"], "NARRATIVE", "Pharaoh threatens to cut off hands and feet and crucify",
    "The magicians' conversion narrative.")
add(["27:21"], "NARRATIVE", "Solomon threatens to punish or slaughter the hoopoe",
    "The Solomon narrative.")
add(["27:49"], "NARRATIVE", "the plot to kill Salih and his family by night",
    "The Salih narrative.")
add(["28:9"], "NARRATIVE", "Pharaoh's wife: 'do not kill him'",
    "The Moses infancy narrative.")
add(["28:15", "28:19", "28:20", "28:33"], "NARRATIVE", "Moses kills an Egyptian; the plots and warnings that follow",
    "The Moses narrative in Egypt.")
add(["33:9"], "NARRATIVE", "the armies that came against you at the Trench",
    "The Battle of the Trench (al-Khandaq, 5 AH).")
add(["33:16"], "PRESCRIPTIVE", "flight will not benefit you against death or killing",
    "The Trench context; addresses those fleeing battle.")
add(["33:18"], "NARRATIVE", "the hinderers who say 'come to us' and scarcely come to battle",
    "The Battle of the Trench (5 AH).")
add(["33:20"], "NARRATIVE", "they think the companies have not withdrawn; they wish to flee to the bedouins",
    "The Battle of the Trench (5 AH).")
add(["33:25"], "NARRATIVE", "Allah repelled the disbelievers in their rage at the Trench",
    "The Battle of the Trench (5 AH).")
add(["33:26", "33:27"], "NARRATIVE", "brought down from fortresses: a party killed, a party captured, their land inherited",
    "Classical tafsir identifies these as the Banu Qurayza, after the Battle of the Trench (5 AH).")
add(["33:60", "33:61"], "PRESCRIPTIVE", "threatens agitators: seized and massacred wherever found",
    "Concerning the hypocrites and rumor-mongers of Medina; a general warning.")
add(["36:18"], "NARRATIVE", "the threat to stone the messengers",
    "The 'companions of the city' narrative.")
add(["37:102", "37:103", "37:104", "37:105", "37:106", "37:107"], "NARRATIVE", "Abraham's vision of sacrificing his son; the ransom",
    "The Abraham narrative.")
add(["40:25"], "NARRATIVE", "'kill the sons of those who believed with him' — Pharaoh's command",
    "The Moses–Pharaoh narrative.")
add(["40:26"], "NARRATIVE", "'let me kill Moses' — Pharaoh's vow",
    "The Moses–Pharaoh narrative.")
add(["40:28"], "NARRATIVE", "'do you kill a man merely because he says: my Lord is Allah?'",
    "The believing man of Pharaoh's family.")
add(["42:40", "42:41"], "PRESCRIPTIVE", "permits like-for-like retribution; no blame on defending oneself after injustice",
    "General ethics of requital.")
add(["44:20"], "NARRATIVE", "Moses seeks refuge 'lest you stone me'",
    "The Moses–Pharaoh narrative.")
add(["47:4"], "PRESCRIPTIVE", "commands striking necks in battle; rules for captives: ransom or grace",
    "General battle instruction; no firmly established occasion.")
add(["47:20", "47:21"], "PRESCRIPTIVE", "rebukes those who recoil when fighting is mentioned",
    "Concerning the hypocrites' reluctance toward fighting.")
add(["47:35"], "PRESCRIPTIVE", "forbids calling for peace while in the superior position",
    "General instruction; no firmly established occasion.")
add(["48:15"], "NARRATIVE", "the laggards ask to follow toward the war booty",
    "The Treaty of Hudaybiyyah (6 AH) and the spoils of Khaybar (7 AH).")
add(["48:16"], "PRESCRIPTIVE", "summons the bedouin laggards against a people of great military might",
    "After Hudaybiyyah; commentators differ on which people is meant.")
add(["48:19", "48:20"], "PRESCRIPTIVE", "the promise of much war booty to be taken",
    "The Khaybar context (7 AH).")
add(["48:22"], "NARRATIVE", "had the Makkan disbelievers fought, they would have fled",
    "The Hudaybiyyah context (6 AH).")
add(["48:24"], "NARRATIVE", "He withheld their hands from you and yours from them at Makkah",
    "The Hudaybiyyah context (6 AH) — the averted clash.")
add(["49:9"], "PRESCRIPTIVE", "rules for fighting between two believing factions; fight the transgressor until it returns",
    "Classical tafsir links this to a quarrel between groups of Muslims in Medina; reports differ on the incident.")
add(["57:10"], "PRESCRIPTIVE", "distinguishes those who spent and fought before the conquest",
    "General; 'the conquest' is understood as the conquest of Mecca.")
add(["57:19"], "PRESCRIPTIVE", "the martyrs are with their Lord, with their reward and light",
    "General theological statement.")
add(["59:2"], "NARRATIVE", "the expulsion of Banu Nadir; terror cast into their hearts",
    "The expulsion of the Banu Nadir (4 AH).")
add(["59:5"], "PRESCRIPTIVE", "permits cutting the enemy's palm trees by Allah's permission",
    "The Banu Nadir siege (4 AH).")
add(["59:6", "59:7"], "PRESCRIPTIVE", "distributes the restored spoils (fay') among named shares",
    "The Banu Nadir aftermath (4 AH).")
add(["59:11", "59:12"], "NARRATIVE", "the hypocrites promise Banu Nadir: if you are fought, we will aid you",
    "The Banu Nadir episode (4 AH).")
add(["59:14"], "NARRATIVE", "they fight only from fortified cities; their inner violence is severe",
    "The Banu Nadir/hypocrites episode (4 AH).")
add(["60:8", "60:9"], "PRESCRIPTIVE", "permits kindness to those who do not fight you; forbids alliance only with those who fight and expel you",
    "Classical tafsir places 60:8 after Asma' bint Abi Bakr asked about treating her disbelieving mother kindly.")
add(["60:12"], "PRESCRIPTIVE", "the women's pledge includes: they will not kill their children",
    "The pledge of the believing women after the conquest of Mecca.")
add(["61:4"], "PRESCRIPTIVE", "Allah loves those who fight in His cause in firm ranks",
    "General; no firmly established occasion.")
add(["61:14"], "NARRATIVE", "the believers supported against their enemy and made dominant",
    "The Jesus–disciples narrative.")
add(["73:20"], "PRESCRIPTIVE", "excuses from night prayer those fighting in Allah's cause, among others",
    "General concession; no firmly established occasion.")
add(["81:8", "81:9"], "NARRATIVE", "the girl buried alive is asked for what sin she was killed",
    "An eschatological scene; classical commentators note it addresses the pre-Islamic practice of burying infant daughters alive.")
add(["85:4", "85:5", "85:6", "85:7", "85:8"], "NARRATIVE", "cursed are the makers of the trench who burned the believers",
    "Commentators identify the victims as believers persecuted by a tyrannical king (reports name Dhu Nuwas of Yemen); details differ.")
add(["105:1", "105:2", "105:3", "105:4", "105:5"], "NARRATIVE", "the destruction of the elephant army by birds with stones of clay",
    "The 'Year of the Elephant' (c. 570 CE), when Abraha's Abyssinian army marched on Mecca.")

# ---------------- grouped: divine destruction of past nations ----------------
GROUPS = [
    ("The people of Noah — the flood",
     ["7:64", "10:73", "11:37", "11:38", "11:39", "11:40", "11:41", "11:42", "11:43", "11:44",
      "21:77", "23:27", "23:28", "23:29", "23:30", "25:37", "26:105", "26:106", "26:107", "26:108",
      "26:109", "26:110", "26:111", "26:112", "26:113", "26:114", "26:115", "26:116", "26:117",
      "26:118", "26:119", "26:120", "26:121", "26:122", "29:14", "29:15", "37:75", "37:76",
      "37:77", "37:78", "37:79", "37:80", "37:81", "37:82", "51:46", "54:9", "54:10", "54:11",
      "54:12", "54:13", "54:14", "54:15", "54:16", "69:11", "69:12", "71:25"],
     "54:11", "The flood narrative, retold across the corpus; 26:116 records the people's threat to stone Noah."),
    ("'Ad — the people of Hud — destroyed by wind",
     ["7:65", "7:66", "7:67", "7:68", "7:69", "7:70", "7:71", "7:72", "11:50", "11:51", "11:52",
      "11:53", "11:54", "11:55", "11:56", "11:57", "11:58", "11:59", "11:60", "25:38", "25:39",
      "26:123", "26:124", "26:125", "26:126", "26:127", "26:128", "26:129", "26:130", "26:131",
      "26:132", "26:133", "26:134", "26:135", "26:136", "26:137", "26:138", "26:139", "26:140",
      "41:13", "41:14", "41:15", "41:16", "46:21", "46:22", "46:23", "46:24", "46:25",
      "51:41", "51:42", "53:50", "54:18", "54:19", "54:20", "54:21", "69:6", "69:7", "89:6",
      "89:7", "89:8"],
     "69:6", "Destroyed by 'a screaming, violent wind'; 25:38-39 names 'Ad alongside Thamud."),
    ("Thamud — the people of Salih — destroyed by blast",
     ["7:73", "7:74", "7:75", "7:76", "7:77", "7:78", "7:79", "11:61", "11:62", "11:63", "11:64",
      "11:65", "11:66", "11:67", "11:68", "15:80", "15:81", "15:82", "15:83", "15:84",
      "26:141", "26:142", "26:143", "26:144", "26:145", "26:146", "26:147", "26:148", "26:149",
      "26:150", "26:151", "26:152", "26:153", "26:154", "26:155", "26:156", "26:157", "26:158",
      "26:159", "27:45", "27:46", "27:47", "27:48", "27:49", "27:50", "27:51", "27:52", "27:53",
      "41:13", "41:14", "51:43", "51:44", "51:45", "54:23", "54:24", "54:25", "54:26", "54:27",
      "54:28", "54:29", "54:30", "54:31", "69:4", "69:5", "89:9", "91:11", "91:12", "91:13",
      "91:14"],
     "91:14", "Destroyed by the 'overpowering blast' after hamstringing the she-camel."),
    ("The people of Lot — the overturned cities",
     ["7:80", "7:81", "7:82", "7:83", "7:84", "11:77", "11:78", "11:79", "11:80", "11:81",
      "11:82", "11:83", "15:57", "15:58", "15:59", "15:60", "15:61", "15:62", "15:63", "15:64",
      "15:65", "15:66", "15:67", "15:68", "15:69", "15:70", "15:71", "15:72", "15:73", "15:74",
      "15:75", "15:76", "15:77", "26:160", "26:161", "26:162", "26:163", "26:164", "26:165",
      "26:166", "26:167", "26:168", "26:169", "26:170", "26:171", "26:172", "26:173", "26:174",
      "26:175", "27:54", "27:55", "27:56", "27:57", "27:58", "29:28", "29:29", "29:30", "29:31",
      "29:32", "29:33", "29:34", "29:35", "37:133", "37:134", "37:135", "37:136", "37:137",
      "37:138", "51:31", "51:32", "51:33", "51:34", "51:35", "51:36", "51:37", "54:33", "54:34",
      "54:35", "54:36", "54:37", "54:38", "54:39"],
     "11:82", "The cities turned upside down and rained with stones of hard clay."),
    ("Midian — the people of Shu'ayb — destroyed by earthquake",
     ["7:85", "7:86", "7:87", "7:88", "7:89", "7:90", "7:91", "7:92", "7:93", "11:84", "11:85",
      "11:86", "11:87", "11:88", "11:89", "11:90", "11:91", "11:92", "11:93", "11:94", "11:95",
      "26:176", "26:177", "26:178", "26:179", "26:180", "26:181", "26:182", "26:183", "26:184",
      "26:185", "26:186", "26:187", "26:188", "26:189", "26:190", "26:191", "29:36", "29:37"],
     "7:91", "Seized by earthquake; 11:91 records their threat to stone Shu'ayb."),
    ("Pharaoh and his people — drowned in the sea",
     ["2:50", "7:136", "8:54", "10:90", "10:91", "10:92", "17:103", "20:78", "20:79", "25:36",
      "26:63", "26:64", "26:65", "26:66", "28:40", "28:81", "28:82", "29:39", "29:40",
      "43:55", "43:56", "44:24", "44:25", "44:26", "44:27", "44:28", "44:29", "51:38", "51:39",
      "51:40", "54:41", "54:42"],
     "10:90", "Drowned while pursuing Moses; 29:39-40 also names Qarun (swallowed by the earth, 28:81-82) and Haman."),
    ("Formulaic 'how many cities/generations We destroyed'",
     ["3:137", "3:141", "6:6", "7:4", "10:13", "12:109", "15:4", "16:26", "17:16", "17:17",
      "18:59", "19:74", "19:98", "20:128", "21:6", "21:9", "21:11", "21:95", "22:45",
      "28:43", "28:58", "28:59", "30:9", "30:47", "32:26", "34:45", "35:44", "36:31", "38:3",
      "40:21", "40:82", "43:8", "43:25", "44:37", "46:27", "47:10", "47:13", "50:36", "65:8",
      "69:10", "77:16"],
     "7:4", "Recurring formula — destroyed cities and generations presented as warnings; includes the people of Tubba' (44:37)."),
]

# ---------------- assemble ----------------
lines = []
lines.append("# Verses on Killing, Warfare, Fighting, and Violent Punishment")
lines.append("")
lines.append("Source: the Saheeh International English translation of the Quran (corpus: 114 surahs, 6236 verses), "
             "read programmatically — every verse text below is quoted verbatim from the corpus file.")
lines.append("")
lines.append("**Scope.** This dossier collects every verse touching on killing, warfare, fighting, or violent punishment: "
             "murder and its prohibition, qisas (retaliation), suicide, infanticide, warfare and fighting the enemy, "
             "spoils and captives, martyrdom, worldly violent punishments (flogging, amputation, crucifixion, exile), "
             "and divine destruction of past nations. Purely eschatological verses about the punishment of Hell "
             f"({hell_n} verses mention Hell) are excluded as out of scope — this dossier covers killing and violence "
             "in this world and in recounted history, not the Hereafter.")
lines.append("")
lines.append("**Tags.** NARRATIVE — the verse recounts a past event or story (including divine destruction of past nations). "
             "PRESCRIPTIVE — the verse instructs, permits, forbids, legislates, rebukes, or promises for the audience. "
             "Traditional context is framed as what classical commentators report, never as a verdict.")
lines.append("")

E.sort(key=lambda x: x[0])
for _, refs, tag, clause, context in E:
    head = refs[0] if len(refs) == 1 else f"{refs[0]}–{refs[-1].split(':')[1]}" if refs[0].split(':')[0] == refs[-1].split(':')[0] else f"{refs[0]}–{refs[-1]}"
    # multi-surah refs listed fully
    if len(refs) > 1 and refs[0].split(':')[0] != refs[-1].split(':')[0]:
        head = ", ".join(refs)
    lines.append(f"### {head}")
    for r in refs:
        lines.append(f"> {t(r)}")
        lines.append(">")
    lines.pop()  # remove trailing empty quote line
    lines.append(f"**Tag:** {tag} — {clause}.")
    lines.append(f"**Traditional context:** {context}")
    lines.append("")

lines.append("---")
lines.append("")
lines.append("## Grouped: divine destruction of past nations (all NARRATIVE)")
lines.append("")
lines.append("Repeated formulaic passages are grouped by nation below: all verse references listed, "
             "one representative verse quoted verbatim, tagged NARRATIVE.")
lines.append("")
for title, refs, rep, note in GROUPS:
    lines.append(f"### {title}")
    lines.append(f"> {t(rep)}")
    lines.append(f"**Tag:** NARRATIVE — {note}")
    nm = title.split('—')[0].strip()
    if nm.lower().startswith('the '):
        nm = nm[4:]
    lines.append(f"**Traditional context:** The {nm} narrative as retold across the corpus; no occasion of revelation.")
    lines.append(f"**All references:** {', '.join(refs)}")
    lines.append("")

out = "\n".join(lines)
open(f"{BASE}/killing-verses.md", "w").write(out)

# ---- stats ----
narr = sum(1 for _, _, tag, _, _ in E if tag == "NARRATIVE")
pres = sum(1 for _, _, tag, _, _ in E if tag == "PRESCRIPTIVE")
print("main entries:", len(E), "| NARRATIVE:", narr, "| PRESCRIPTIVE:", pres)
print("group entries:", len(GROUPS), "(all NARRATIVE)")
print("total entries:", len(E) + len(GROUPS))
print("hell-mention verses excluded:", hell_n)
# verify every ref exists
missing = [r for _, refs, _, _, _ in E for r in refs if tuple(map(int, r.split(":"))) not in idx]
missing += [r for _, refs, rep, _ in GROUPS for r in refs + [rep] if tuple(map(int, r.split(":"))) not in idx]
print("missing refs:", missing if missing else "none")
