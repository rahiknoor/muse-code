import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Map Parkev video titles -> candidate tickers, newest-first worklist.
 *
 * <p>Usage: {@code java ScanTitles.java} (no arguments; reads files in the
 * working directory, like the Python original).
 *
 * <p>Steps (mirrors scan_titles.py):
 * <ol>
 *   <li>Read uploads_list.tsv (vid, title). NB: this file separates fields
 *       with a <em>literal backslash-t</em> ("\\t" as two characters), not a
 *       real tab &mdash; hence the odd-looking split.</li>
 *   <li>For each title, find candidate tickers:
 *       <ul>
 *         <li>{@code XXXX Stock} mentions (1-5 uppercase letters), minus a
 *             stop-word list;</li>
 *         <li>company-name mentions via word-boundary regexes built from the
 *             COMPANY_TO_TICKER table below, in table order (first match per
 *             ticker wins; "Dow Jones" is not Dow Inc.).</li>
 *       </ul></li>
 *   <li>Write worklist.tsv (vid, real tab, title, real tab, comma-joined
 *       candidates) and print stats to stderr.</li>
 * </ol>
 *
 * <p>Table notes, kept identical to the Python dict on purpose: duplicate
 * names keep the <em>last</em> ticker at the <em>first</em> position (e.g.
 * "penn" &rarr; PEI, "l'oreal" &rarr; null so it never matches); names with a
 * null ticker are skipped; quirky keys like " synchrony" (leading space) and
 * "eQT" (uppercase, so it can never match the lowercased title) behave
 * exactly as in Python.
 */
public class ScanTitles {

    /** Company name &rarr; ticker. Insertion order = Python dict order. */
    static final Map<String, String> COMPANY_TO_TICKER = buildMap();

    static Map<String, String> buildMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("apple", "AAPL");
        map.put("microsoft", "MSFT");
        map.put("alphabet", "GOOGL");
        map.put("google", "GOOGL");
        map.put("amazon", "AMZN");
        map.put("nvidia", "NVDA");
        map.put("meta platforms", "META");
        map.put("facebook", "META");
        map.put("tesla", "TSLA");
        map.put("broadcom", "AVGO");
        map.put("jpmorgan", "JPM");
        map.put("visa", "V");
        map.put("mastercard", "MA");
        map.put("exxon", "XOM");
        map.put("exxonmobil", "XOM");
        map.put("unitedhealth", "UNH");
        map.put("johnson & johnson", "JNJ");
        map.put("johnson and johnson", "JNJ");
        map.put("walmart", "WMT");
        map.put("costco", "COST");
        map.put("netflix", "NFLX");
        map.put("amd", "AMD");
        map.put("salesforce", "CRM");
        map.put("oracle", "ORCL");
        map.put("abbvie", "ABBV");
        map.put("eli lilly", "LLY");
        map.put("lilly", "LLY");
        map.put("merck", "MRK");
        map.put("pfizer", "PFE");
        map.put("coca-cola", "KO");
        map.put("coca cola", "KO");
        map.put("pepsico", "PEP");
        map.put("pepsi", "PEP");
        map.put("disney", "DIS");
        map.put("walt disney", "DIS");
        map.put("intel", "INTC");
        map.put("qualcomm", "QCOM");
        map.put("texas instruments", "TXN");
        map.put("applied materials", "AMAT");
        map.put("micron", "MU");
        map.put("shopify", "SHOP");
        map.put("block", "SQ");
        map.put("square", "SQ");
        map.put("paypal", "PYPL");
        map.put("sofi", "SOFI");
        map.put("palantir", "PLTR");
        map.put("roblox", "RBLX");
        map.put("uber", "UBER");
        map.put("airbnb", "ABNB");
        map.put("doordash", "DASH");
        map.put("mercadolibre", "MELI");
        map.put("sea limited", "SE");
        map.put("grab", "GRAB");
        map.put("moderna", "MRNA");
        map.put("berkshire", "BRK.B");
        map.put("berkshire hathaway", "BRK.B");
        map.put("chevron", "CVX");
        map.put("conocophillips", "COP");
        map.put("target", "TGT");
        map.put("novo nordisk", "NVO");
        map.put("duolingo", "DUOL");
        map.put("starbucks", "SBUX");
        map.put("dutch bros", "BROS");
        map.put("take-two", "TTWO");
        map.put("take two", "TTWO");
        map.put("at&t", "T");
        map.put("verizon", "VZ");
        map.put("trade desk", "TTD");
        map.put("servicenow", "NOW");
        map.put("service now", "NOW");
        map.put("3m", "MMM");
        map.put("mcdonald", "MCD");
        map.put("nike", "NKE");
        map.put("home depot", "HD");
        map.put("adobe", "ADBE");
        map.put("coinbase", "COIN");
        map.put("microstrategy", "MSTR");
        map.put("strategy", "MSTR");
        map.put("crowdstrike", "CRWD");
        map.put("palo alto", "PANW");
        map.put("datadog", "DDOG");
        map.put("snowflake", "SNOW");
        map.put("mongodb", "MDB");
        map.put("cloudflare", "NET");
        map.put("arm holdings", "ARM");
        map.put("taiwan semiconductor", "TSM");
        map.put("asml", "ASML");
        map.put("lululemon", "LULU");
        map.put("chipotle", "CMG");
        map.put("booking", "BKNG");
        map.put("delta", "DAL");
        map.put("southwest", "LUV");
        map.put("ford", "F");
        map.put("general motors", "GM");
        map.put("nio", "NIO");
        map.put("rivian", "RIVN");
        map.put("lucid", "LCID");
        map.put("enphase", "ENPH");
        map.put("first solar", "FSLR");
        map.put("realty income", "O");
        map.put("t-mobile", "TMUS");
        map.put("comcast", "CMCSA");
        map.put("procter & gamble", "PG");
        map.put("procter and gamble", "PG");
        map.put("waste management", "WM");
        map.put("union pacific", "UNP");
        map.put("caterpillar", "CAT");
        map.put("deere", "DE");
        map.put("lockheed", "LMT");
        map.put("rtx", "RTX");
        map.put("boeing", "BA");
        map.put("general electric", "GE");
        map.put("honeywell", "HON");
        map.put("sherwin", "SHW");
        map.put("thermo fisher", "TMO");
        map.put("danaher", "DHR");
        map.put("abbott", "ABT");
        map.put("intuitive surgical", "ISRG");
        map.put("stryker", "SYK");
        map.put("zoetis", "ZTS");
        map.put("amgen", "AMGN");
        map.put("gilead", "GILD");
        map.put("vertex", "VRTX");
        map.put("regeneron", "REGN");
        map.put("bristol", "BMY");
        map.put("cvs", "CVS");
        map.put("walgreens", "WBA");
        map.put("humana", "HUM");
        map.put("cigna", "CI");
        map.put("elevance", "ELV");
        map.put("chubb", "CB");
        map.put("aig", "AIG");
        map.put("goldman", "GS");
        map.put("morgan stanley", "MS");
        map.put("bank of america", "BAC");
        map.put("wells fargo", "WFC");
        map.put("citigroup", "C");
        map.put("american express", "AXP");
        map.put("blackrock", "BLK");
        map.put("charles schwab", "SCHW");
        map.put("s&p", "SPGI");
        map.put("moody", "MCO");
        map.put("cme", "CME");
        map.put("intercontinental exchange", "ICE");
        map.put("fidelity", null);
        map.put("vanguard", null);
        map.put("hershey", "HSY");
        map.put("mondelez", "MDLZ");
        map.put("kraft", "KHC");
        map.put("general mills", "GIS");
        map.put("clorox", "CLX");
        map.put("colgate", "CL");
        map.put("estee lauder", "EL");
        map.put("l'oreal", null);
        map.put("lvmh", "MC.PA");
        map.put("nestle", "NESN.SW");
        map.put("toyota", "TM");
        map.put("sony", "SONY");
        map.put("samsung", null);
        map.put("alibaba", "BABA");
        map.put("tencent", "TCEHY");
        map.put("jd.com", "JD");
        map.put("pinduoduo", "PDD");
        map.put("baidu", "BIDU");
        map.put("novo", "NVO");
        map.put("astrazeneca", "AZN");
        map.put("novartis", "NVS");
        map.put("roche", null);
        map.put("sanofi", "SNY");
        map.put("gsk", "GSK");
        map.put("dollar general", "DG");
        map.put("dollar tree", "DLTR");
        map.put("best buy", "BBY");
        map.put("lowe's", "LOW");
        map.put("lowes", "LOW");
        map.put("tractor supply", "TSCO");
        map.put("o'reilly", "ORLY");
        map.put("autozone", "AZO");
        map.put("ross stores", "ROST");
        map.put("tjx", "TJX");
        map.put("marriott", "MAR");
        map.put("hilton", "HLT");
        map.put("royal caribbean", "RCL");
        map.put("carnival", "CCL");
        map.put("wynn", "WYNN");
        map.put("mgm", "MGM");
        map.put("draftkings", "DKNG");
        map.put("penn", "PEI");
        map.put("fubo", "FUBO");
        map.put("roku", "ROKU");
        map.put("pinterest", "PINS");
        map.put("snap", "SNAP");
        map.put("reddit", "RDDT");
        map.put("coupang", "CPNG");
        map.put("warner bros", "WBD");
        map.put("paramount", "PARA");
        map.put("spotify", "SPOT");
        map.put("zoom", "ZM");
        map.put("docusign", "DOCU");
        map.put("workday", "WDAY");
        map.put("hubspot", "HUBS");
        map.put("twilio", "TWLO");
        map.put("okta", "OKTA");
        map.put("zscaler", "ZS");
        map.put("fortinet", "FTNT");
        map.put("check point", "CHKP");
        map.put("gen digital", "GEN");
        map.put("norton", "GEN");
        map.put("cisco", "CSCO");
        map.put("ibm", "IBM");
        map.put("hp", "HPQ");
        map.put("dell", "DELL");
        map.put("hewlett packard", "HPE");
        map.put("juniper", "JNPR");
        map.put("netapp", "NTAP");
        map.put("seagate", "STX");
        map.put("western digital", "WDC");
        map.put("corning", "GLW");
        map.put("analog devices", "ADI");
        map.put("microchip", "MCHP");
        map.put("nxp", "NXPI");
        map.put("marvell", "MRVL");
        map.put("monolithic power", "MPWR");
        map.put("kkr", "KKR");
        map.put("apollo", "APO");
        map.put("blackstone", "BX");
        map.put("carlyle", "CG");
        map.put("brookfield", "BN");
        map.put("aercap", "AER");
        map.put(" synchrony", "SYF");
        map.put("discover", "DFS");
        map.put("capital one", "COF");
        map.put("ally", "ALLY");
        map.put("us bancorp", "USB");
        map.put("pnc", "PNC");
        map.put("truist", "TFC");
        map.put("keycorp", "KEY");
        map.put("fifth third", "FITB");
        map.put("regions", "RF");
        map.put("huntington", "HBAN");
        map.put("zions", "ZION");
        map.put("webster", "WBS");
        map.put("lincoln national", "LNC");
        map.put("metlife", "MET");
        map.put("prudential", "PRU");
        map.put("aflac", "AFL");
        map.put("principal", "PFG");
        map.put("t rowe", "TROW");
        map.put("franklin resources", "BEN");
        map.put("invesco", "IVZ");
        map.put("janus", "JHG");
        map.put("allstate", "ALL");
        map.put("progressive", "PGR");
        map.put("travelers", "TRV");
        map.put("hartford", "HIG");
        map.put("assurant", "AIZ");
        map.put("unm", "UNM");
        map.put("arch capital", "ACGL");
        map.put("everest", "EG");
        map.put("renaissancere", "RNR");
        map.put("brown & brown", "BRO");
        map.put("aj gallagher", "AJG");
        map.put("marsh mclennan", "MMC");
        map.put("aon", "AON");
        map.put("willis towers", "WTW");
        map.put("dominion", "D");
        map.put("duke energy", "DUK");
        map.put("southern company", "SO");
        map.put("southern co", "SO");
        map.put("nextera", "NEE");
        map.put("aep", "AEP");
        map.put("exelon", "EXC");
        map.put("pg&e", "PCG");
        map.put("edison international", "EIX");
        map.put("consolidated edison", "ED");
        map.put("xcel", "XEL");
        map.put("wec energy", "WEC");
        map.put("dte", "DTE");
        map.put("ameran", "AEE");
        map.put("ppl", "PPL");
        map.put("eversource", "ES");
        map.put("firstenergy", "FE");
        map.put("enbridge", "ENB");
        map.put("tc energy", "TRP");
        map.put("kinder morgan", "KMI");
        map.put("williams", "WMB");
        map.put("oneok", "OKE");
        map.put("enterprise products", "EPD");
        map.put("energy transfer", "ET");
        map.put("mplx", "MPLX");
        map.put("phillips 66", "PSX");
        map.put("marathon petroleum", "MPC");
        map.put("valero", "VLO");
        map.put("marathon oil", "MRO");
        map.put("diamondback", "FANG");
        map.put("eog resources", "EOG");
        map.put("pioneer", "PXD");
        map.put("occidental", "OXY");
        map.put("devon", "DVN");
        map.put("apa corp", "APA");
        map.put("hess", "HES");
        map.put("coterra", "CTRA");
        map.put("chesapeake", "CHK");
        map.put("expand energy", "EXE");
        map.put("eQT", "EQT");
        map.put("anquiet", null);
        map.put("range resources", "RRC");
        map.put("freeport", "FCX");
        map.put("southern copper", "SCCO");
        map.put("newmont", "NEM");
        map.put("barrick", "GOLD");
        map.put("agnico", "AEM");
        map.put("nucor", "NUE");
        map.put("steel dynamics", "STLD");
        map.put("cleveland-cliffs", "CLF");
        map.put("alcoa", "AA");
        map.put("mosaic", "MOS");
        map.put("cf industries", "CF");
        map.put("fmc", "FMC");
        map.put("ecolab", "ECL");
        map.put("sherwin-williams", "SHW");
        map.put("ppg", "PPG");
        map.put("dupont", "DD");
        map.put("dow", "DOW");
        map.put("lyb", "LYB");
        map.put("westlake", "WLK");
        map.put("international paper", "IP");
        map.put("packaging corp", "PKG");
        map.put("ball", "BALL");
        map.put("crown holdings", "CCK");
        map.put("amcor", "AMCR");
        map.put("sealed air", "SEE");
        map.put("smurfit", "SW");
        map.put("graphic packaging", "GPK");
        map.put("cintas", "CTAS");
        map.put("copart", "CPRT");
        map.put("rollins", "ROL");
        map.put("waste connections", "WCN");
        map.put("republic services", "RSG");
        map.put("stericycle", "SRCL");
        map.put("us ecology", null);
        map.put("clean harbors", "CLH");
        map.put("tetra tech", "TTEK");
        map.put("jacobs", "J");
        map.put("aecom", "ACM");
        map.put("quanta", "PWR");
        map.put("emcor", "EME");
        map.put("mastec", "MTZ");
        map.put("fluor", "FLR");
        map.put("kbr", "KBR");
        map.put("parsons", "PSN");
        map.put("leidos", "LDOS");
        map.put("caci", "CACI");
        map.put("saic", "SAIC");
        map.put("booz allen", "BAH");
        map.put("manTech", "MANT");
        map.put("general dynamics", "GD");
        map.put("northrop", "NOC");
        map.put("l3harris", "LHX");
        map.put("hew", null);
        map.put("textron", "TXT");
        map.put("spirit aerosystems", "SPR");
        map.put("howmet", "HWM");
        map.put("transdigm", "TDG");
        map.put("heico", "HEI");
        map.put("curtiss-wright", "CW");
        map.put("woodward", "WWD");
        map.put("parker hannifin", "PH");
        map.put("donald", null);
        map.put("donaldson", "DCI");
        map.put("pentair", "PNR");
        map.put("xylem", "XYL");
        map.put("ingersoll", "IR");
        map.put("dover", "DOV");
        map.put("fortive", "FTV");
        map.put("ametek", "AME");
        map.put("keysight", "KEYS");
        map.put("trimble", "TRMB");
        map.put("jabil", "JBL");
        map.put("flex", "FLEX");
        map.put("celestica", "CLS");
        map.put("sanmina", "SANM");
        map.put("benchmark", "BHE");
        map.put("ttm", "TTMI");
        map.put("avnet", "AVT");
        map.put("arrow", "ARW");
        map.put("insight", "NSIT");
        map.put("cdw", "CDW");
        map.put("td synnex", "SNX");
        map.put("accenture", "ACN");
        map.put("cognizant", "CTSH");
        map.put("infosys", "INFY");
        map.put("wipro", "WIT");
        map.put("dxc", "DXC");
        map.put("kyndryl", "KD");
        map.put("guru", null);
        map.put("epam", "EPAM");
        map.put("globant", "GLOB");
        map.put("endava", "DAVA");
        map.put("thoughtworks", "TWKS");
        map.put("ci&t", "CINT");
        map.put("fiverr", "FVRR");
        map.put("upwork", "UPWK");
        map.put("taskus", "TASK");
        map.put("concentrix", "CNXC");
        map.put("teleperformance", null);
        map.put("genpact", "G");
        map.put("exlservice", "EXLS");
        map.put("wex", "WEX");
        map.put("fleetcor", "CPAY");
        map.put("corpay", "CPAY");
        map.put("global payments", "GPN");
        map.put("fis", "FIS");
        map.put("fiserv", "FI");
        map.put("jack henry", "JKHY");
        map.put("toast", "TOST");
        map.put("shift4", "FOUR");
        map.put("adyen", "ADYEY");
        map.put("dlocal", "DLO");
        map.put("nuvei", "NVEI");
        map.put("pagseguro", "PAGS");
        map.put("stone", "STNE");
        map.put("ebury", null);
        map.put("wise", "WISE");
        map.put("remitly", "RELY");
        map.put("moneylion", "ML");
        map.put("dave", "DAVE");
        map.put("lendingclub", "LC");
        map.put("upstart", "UPST");
        map.put("enova", "ENVA");
        map.put("credit acceptance", "CACC");
        map.put("synchrony", "SYF");
        map.put("bread", "BFH");
        map.put("nelnet", "NNI");
        map.put("navient", "NAVI");
        map.put("sallie mae", "SLM");
        map.put("mr cooper", "COOP");
        map.put("loan depot", "LDI");
        map.put("guild", "GHLD");
        map.put("uwmc", "UWMC");
        map.put("rocket", "RKT");
        map.put("better", "BETR");
        map.put("opendoor", "OPEN");
        map.put("zillow", "ZG");
        map.put("redfin", "RDFN");
        map.put("compass", "COMP");
        map.put("anywhere", "HOUS");
        map.put("re/max", "RMAX");
        map.put("cbre", "CBRE");
        map.put("jll", "JLL");
        map.put("colliers", "CIGI");
        map.put("simon property", "SPG");
        map.put("prologis", "PLD");
        map.put("equinix", "EQIX");
        map.put("digital realty", "DLR");
        map.put("american tower", "AMT");
        map.put("crown castle", "CCI");
        map.put("sba communications", "SBAC");
        map.put("public storage", "PSA");
        map.put("extra space", "EXR");
        map.put("cube smart", "CUBE");
        map.put("weyerhaeuser", "WY");
        map.put("rayonier", "RYN");
        map.put("potlatchdeltic", "PCH");
        map.put("vonovia", null);
        map.put("segro", null);
        map.put("avalonbay", "AVB");
        map.put("equity residential", "EQR");
        map.put("essex", "ESS");
        map.put("udr", "UDR");
        map.put("mid-america", "MAA");
        map.put("camden", "CPT");
        map.put("invitation homes", "INVH");
        map.put("american homes", "AMH");
        map.put("sun communities", "SUI");
        map.put("equity lifestyle", "ELS");
        map.put("ventas", "VTR");
        map.put("welltower", "WELL");
        map.put("healthpeak", "DOC");
        map.put("omega healthcare", "OHI");
        map.put("medical properties", "MPW");
        map.put("sabine", "SBR");
        map.put("boston properties", "BXP");
        map.put("sl green", "SLG");
        map.put("vornado", "VNO");
        map.put("kilroy", "KRC");
        map.put("douglas emmett", "DEI");
        map.put("hudson pacific", "HPP");
        map.put("brandywine", "BDN");
        map.put("highwoods", "HIW");
        map.put("cousins", "CUZ");
        map.put("piedmont", "PDM");
        map.put("city office", "CIO");
        map.put("franklin street", "FSP");
        map.put("government properties", "OPI");
        map.put("easterly", "DEA");
        map.put("four corners", "FCPT");
        map.put("spirit realty", "SRC");
        map.put("national retail", "NNN");
        map.put("agree realty", "ADC");
        map.put("essential", "EPRT");
        map.put("getty realty", "GTY");
        map.put("netstreit", "NTST");
        map.put("broadstone", "BNST");
        map.put("wpc", "WPC");
        map.put("caretrust", "CTRE");
        map.put("store capital", null);
        map.put("vici", "VICI");
        map.put("gaming and leisure", "GLPI");
        map.put("mgm growth", "MGP");
        map.put("epr", "EPR");
        map.put("alexander", "ALX");
        map.put("federal realty", "FRT");
        map.put("kimco", "KIM");
        map.put("regency", "REG");
        map.put("site centers", "SITC");
        map.put("brx", "BRX");
        map.put("acadia", "AKR");
        map.put("tanger", "SKT");
        map.put("macerich", "MAC");
        map.put("cbl", "CBL");
        map.put("washington prime", null);
        map.put("urban edge", "UE");
        map.put("kite", "KRG");
        map.put("curbline", "CURB");
        map.put("invenTrust", "IVT");
        map.put("phillips edison", "PECO");
        map.put("retail opportunity", "ROIC");
        map.put("first industrial", "FR");
        map.put("rexford", "REXR");
        map.put("eastgroup", "EGP");
        map.put("stagg", "STAG");
        map.put("terreno", "TRNO");
        map.put("americold", "COLD");
        map.put("lineage", "LINE");
        map.put("ryman", "RHP");
        map.put("host hotels", "HST");
        map.put("park hotels", "PK");
        map.put("pebblebrook", "PEB");
        map.put("sunstone", "SHO");
        map.put("xenia", "XHR");
        map.put("rlj", "RLJ");
        map.put("apple hospitality", "APLE");
        map.put("chatham", "CLDT");
        map.put("hersha", "HT");
        map.put("braemar", "BHR");
        map.put("ashford", "AINC");
        map.put("condor", "CDOR");
        map.put("summit", "INN");
        map.put("diamondrock", "DRH");
        map.put("outfront", "OUT");
        map.put("lamar", "LAMR");
        map.put("clear channel", "CCO");
        map.put("iron mountain", "IRM");
        map.put("data centers", null);
        map.put("innovative industrial", "IIPR");
        map.put("power reit", "PW");
        map.put("farmland partners", "FPI");
        map.put("gladstone land", "LAND");
        map.put("limoneira", "LMNR");
        map.put("adecco", null);
        return map;
    }

    /** {@code XXXX Stock} mentions, e.g. "NVDA Stock". */
    static final Pattern TICKER_RE = Pattern.compile("\\b([A-Z]{1,5})\\s+Stock\\b");
    static final Set<String> TICKER_EXCLUDE = Set.of(
            "THE", "AND", "FOR", "ARE", "YOU", "ITS", "NEW", "TOP",
            "BUY", "SELL", "AI", "US", "UK");

    /**
     * (pattern, ticker) pairs in table order, skipping null tickers &mdash;
     * mirrors Python's _NAME_PATTERNS. Pattern.quote is the equivalent of
     * Python's re.escape here (both mean "match literally").
     */
    static final List<Map.Entry<Pattern, String>> NAME_PATTERNS = buildPatterns();

    static List<Map.Entry<Pattern, String>> buildPatterns() {
        List<Map.Entry<Pattern, String>> list = new ArrayList<>();
        for (var e : COMPANY_TO_TICKER.entrySet()) {
            if (e.getValue() == null) continue;
            list.add(Map.entry(
                    Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b"),
                    e.getValue()));
        }
        return list;
    }

    /** Candidate tickers for one video title (mirrors candidates()). */
    static List<String> candidates(String title) {
        List<String> out = new ArrayList<>();
        Matcher m = TICKER_RE.matcher(title);
        while (m.find()) {
            String t = m.group(1);
            if (!TICKER_EXCLUDE.contains(t)) out.add(t);
        }
        // Python matches the lowercase-name patterns against title.lower().
        // Locale.ROOT keeps the case mapping locale-independent, like Python.
        String low = title.toLowerCase(Locale.ROOT);
        for (var pe : NAME_PATTERNS) {
            String tick = pe.getValue();
            if (out.contains(tick)) continue;
            if (!pe.getKey().matcher(low).find()) continue;
            if (tick.equals("DOW") && low.contains("jones")) continue; // Dow Jones index, not Dow Inc.
            out.add(tick);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        List<String[]> rows = new ArrayList<>(); // each: {vid, title}
        for (String line : textLines(Path.of("uploads_list.tsv"))) {
            // Literal backslash-t separator: regex "\\t" (backslash + 't').
            String[] parts = line.split("\\\\t", -1);
            if (parts.length < 2) continue;
            rows.add(new String[]{parts[0], parts[1]});
        }
        System.err.println("total videos: " + rows.size());

        try (var w = Files.newBufferedWriter(Path.of("worklist.tsv"), StandardCharsets.UTF_8)) {
            for (String[] r : rows) {
                w.write(r[0] + "\t" + r[1] + "\t" + String.join(",", candidates(r[1])));
                w.newLine();
            }
        }

        int withTick = 0;
        for (String[] r : rows)
            if (!candidates(r[1]).isEmpty()) withTick++;
        System.err.println("videos with candidate ticker: " + withTick);
    }

    /**
     * Read a text file the way Python's {@code for line in open(...)} does:
     * universal newlines, each line rstripped of "\n" (a trailing newline does
     * not produce an extra empty line).
     */
    static List<String> textLines(Path path) throws Exception {
        String c = Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(Arrays.asList(c.split("\n", -1)));
        if (c.endsWith("\n")) lines.remove(lines.size() - 1);
        return lines;
    }
}
