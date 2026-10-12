// ThirteenFWatch.java — quarterly 13F institutional watch for the Big Three.
//
// Tracks 13F-HR holdings for:
//   Vanguard  -> VANGUARD CAPITAL MANAGEMENT LLC (0002100119)
//             + VANGUARD PORTFOLIO MANAGEMENT LLC (0002100121)
//             (parent VANGUARD GROUP INC files 13F-NT since the Jan 2026
//              realignment; holdings are reported by these sub-managers)
//   BlackRock -> BlackRock, Inc. (0002012383)
//             (named as the reporting manager in the 13F-NT filings of the
//              BlackRock trust/adviser entities)
//   State Street -> STATE STREET CORP (0000093751)
//
// Commands:
//   --fetch <cik> [--period YYYY-MM-DD] --out <snapshot.json> [--ua <user-agent>]
//       Download the 13F-HR holdings XML for the given CIK and save a snapshot.
//       Without --period, takes the latest filing. Snapshot holdings are keyed
//       by CUSIP: {name, value_usd, shares}.
//       NOTE (verified 2026-10-07 on Q2 2026 filings): the <value> element in
//       these 13F-HR XMLs is in DOLLARS, not thousands — three independent
//       filers all imply the identical per-share price only under dollars
//       (e.g. QXO 82846H405 -> $17.28 for Vanguard/BlackRock/State Street).
//       The tool stores value_usd as reported, with NO x1000.
//   --merge <snapA> <snapB> --manager <name> --out <merged.json>
//       Combine two snapshots (e.g. Vanguard's two sub-managers) into one.
//   --compare <old.json> <new.json> [--top N] [--min-usd X] [--cache <cusip_cache.json>]
//       New/exited positions + top adds/cuts by dollar change. CUSIPs resolved
//       to tickers via OpenFIGI with a local cache.
//   --rank <snap...> --tickers T1,T2 --meta <ticker_meta.json> [--cache ...]
//       Big Three sticky-supply ranking: per ticker, each manager's % of shares
//       outstanding plus the combined %. ticker_meta.json: {TICKER: {shares_out, company}}.
//   --self-test
//
// 13F value is reported in thousands of dollars; value_usd multiplies by 1000.
// Tiers (combined %): >=25 locked, >=15 heavy, >=8 moderate, else light.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class ThirteenFWatch {

    static final String DEFAULT_UA = "Muse personal-research contact@example.com";

    private ThirteenFWatch() { }

    // HTTP via curl subprocess (workspace standard since 2026-09-28: Java's
    // HttpClient does not honor the egress proxy env vars; curl does).
    static String curl(String[] args, String stdin) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");
        cmd.add("--fail");
        cmd.add("--max-time");
        cmd.add("90");
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IOException("curl failed (" + code + "): " + err.trim());
        return out;
    }

    static String get(String url, String ua) throws Exception {
        return curl(new String[]{"-A", ua, "--compressed", url}, null);
    }

    static String post(String url, String ua, String body) throws Exception {
        return curl(new String[]{"-A", ua, "-H", "Content-Type: text/json",
            "--data-binary", "@-", url}, body);
    }

    static String padCik(String cik) {
        String digits = cik.replaceAll("\\D", "");
        return "0".repeat(Math.max(0, 10 - digits.length())) + digits;
    }

    // ---------- fetch ----------

    /** Find the accession number of the 13F-HR filing for the period (or latest).
     *  Prefers the original 13F-HR over 13F-HR/A amendments: amendments are
     *  often partial corrections (verified 2026-10-07: Vanguard sub-managers'
     *  Q1 2026 amendments held ~80 positions vs ~4000 in the original). */
    static String[] findFiling(String cik, String period, String ua) throws Exception {
        String url = "https://data.sec.gov/submissions/CIK" + padCik(cik) + ".json";
        JSONObject sub = new JSONObject(get(url, ua));
        JSONObject recent = sub.getJSONObject("filings").getJSONObject("recent");
        JSONArray forms = recent.getJSONArray("form");
        JSONArray dates = recent.getJSONArray("filingDate");
        JSONArray accs = recent.getJSONArray("accessionNumber");
        JSONArray reports = recent.getJSONArray("reportDate");
        String bestAcc = null, bestFiled = null, bestForm = null;
        for (int round = 0; round < 2 && bestAcc == null; round++) {
            // round 0: original 13F-HR only; round 1: amendments as fallback
            for (int i = 0; i < forms.length(); i++) {
                String f = forms.getString(i);
                boolean want = round == 0 ? f.equals("13F-HR")
                    : (f.equals("13F-HR") || f.equals("13F-HR/A"));
                if (!want) continue;
                String rep = reports.isNull(i) ? "" : reports.getString(i);
                if (period != null && !period.equals(rep)) continue;
                String filed = dates.getString(i);
                if (bestFiled == null || filed.compareTo(bestFiled) > 0) {
                    bestFiled = filed;
                    bestAcc = accs.getString(i);
                    bestForm = f;
                }
            }
        }
        if (bestAcc == null)
            throw new IOException("no 13F-HR filing found for CIK " + cik + (period == null ? "" : " period " + period));
        return new String[]{bestAcc, bestFiled, bestForm};
    }

    static String findInfoTable(String cik, String accession, String ua) throws Exception {
        String nodash = accession.replace("-", "");
        String url = "https://www.sec.gov/Archives/edgar/data/" + cik.replaceAll("^0+", "")
            + "/" + nodash + "/index.json";
        JSONObject idx = new JSONObject(get(url, ua));
        JSONArray items = idx.getJSONObject("directory").getJSONArray("item");
        String best = null;
        long bestSize = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String name = it.getString("name");
            if (!name.endsWith(".xml") || name.equals("primary_doc.xml")) continue;
            long size = it.optLong("size", 0);
            if (size > bestSize) { bestSize = size; best = name; }
        }
        if (best == null) throw new IOException("no information table found in " + url);
        return "https://www.sec.gov/Archives/edgar/data/" + cik.replaceAll("^0+", "")
            + "/" + nodash + "/" + best;
    }

    static String textOf(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }

    /** Parse a 13F information-table XML into CUSIP -> {name, value_usd, shares}. */
    static Map<String, JSONObject> parseInfoTable(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Harden against XXE in a filing we download from EDGAR.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = dbf.newDocumentBuilder().parse(
            new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        Map<String, JSONObject> out = new LinkedHashMap<>();
        NodeList tables = doc.getElementsByTagNameNS("*", "infoTable");
        for (int i = 0; i < tables.getLength(); i++) {
            Element t = (Element) tables.item(i);
            String cusip = textOf(t, "cusip").toUpperCase(Locale.ROOT);
            if (cusip.isEmpty()) continue;
            String name = textOf(t, "nameOfIssuer");
            long valueUsd; // stored as reported (verified: dollars, not $000s, 2026-10-07)
            try { valueUsd = Long.parseLong(textOf(t, "value")); }
            catch (NumberFormatException e) { continue; }
            long shares = 0;
            NodeList amts = t.getElementsByTagNameNS("*", "shrsOrPrnAmt");
            if (amts.getLength() > 0) {
                NodeList qtys = ((Element) amts.item(0)).getElementsByTagNameNS("*", "sshPrnamt");
                if (qtys.getLength() > 0) {
                    try { shares = Long.parseLong(qtys.item(0).getTextContent().trim()); }
                    catch (NumberFormatException e) { /* leave 0 */ }
                }
            }
            JSONObject prev = out.get(cusip);
            if (prev == null) {
                JSONObject h = new JSONObject();
                h.put("name", name);
                h.put("value_usd", valueUsd);
                h.put("shares", shares);
                out.put(cusip, h);
            } else {
                // Same CUSIP listed twice (e.g. voting-authority splits): aggregate.
                prev.put("value_usd", prev.getLong("value_usd") + valueUsd);
                prev.put("shares", prev.getLong("shares") + shares);
            }
        }
        return out;
    }

    static JSONObject fetchSnapshot(String cik, String period, String managerName, String ua) throws Exception {
        String[] found = findFiling(cik, period, ua);
        String accession = found[0], filed = found[1], form = found[2];
        String tableUrl = findInfoTable(cik, accession, ua);
        Map<String, JSONObject> holdings = parseInfoTable(get(tableUrl, ua));
        // Derive the report period from the filing when not given.
        String repPeriod = period;
        if (repPeriod == null) {
            JSONObject sub = new JSONObject(get(
                "https://data.sec.gov/submissions/CIK" + padCik(cik) + ".json", ua));
            JSONObject recent = sub.getJSONObject("filings").getJSONObject("recent");
            JSONArray forms = recent.getJSONArray("form");
            JSONArray accs = recent.getJSONArray("accessionNumber");
            JSONArray reports = recent.getJSONArray("reportDate");
            for (int i = 0; i < forms.length(); i++)
                if (accs.getString(i).equals(accession)) { repPeriod = reports.getString(i); break; }
        }
        JSONObject snap = new JSONObject();
        snap.put("manager", managerName != null ? managerName : cik);
        snap.put("cik", cik);
        snap.put("period", repPeriod);
        snap.put("filed", filed);
        snap.put("form", form);
        snap.put("accession", accession);
        JSONObject h = new JSONObject();
        for (Map.Entry<String, JSONObject> e : holdings.entrySet()) h.put(e.getKey(), e.getValue());
        snap.put("holdings", h);
        return snap;
    }

    static void writeJson(Path p, JSONObject o) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, o.toString(1), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------- CUSIP -> ticker ----------

    static Map<String, String> loadCache(Path p) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        if (Files.exists(p)) {
            JSONObject o = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
            for (String k : o.keySet()) m.put(k, o.getString(k));
        }
        return m;
    }

    static void saveCache(Path p, Map<String, String> m) throws IOException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        writeJson(p, o);
    }

    /** Resolve CUSIPs to tickers via OpenFIGI, using and updating the local cache.
     *  Only clean US-style tickers are accepted (1-5 letters, optional class
     *  suffix); FIGI often returns foreign/placeholder tickers (HONGBP, 1XOMM)
     *  which are rejected and left unresolved. */
    static final java.util.regex.Pattern CLEAN_TICKER =
        java.util.regex.Pattern.compile("^[A-Z]{1,5}([./][A-Z])?$");

    static boolean cleanTicker(String t) {
        return t != null && CLEAN_TICKER.matcher(t).matches();
    }

    static Map<String, String> resolveTickers(List<String> cusips, Path cachePath, String ua) throws Exception {
        Map<String, String> cache = loadCache(cachePath);
        List<String> missing = new ArrayList<>();
        for (String c : cusips) if (!cache.containsKey(c)) missing.add(c);
        if (!missing.isEmpty()) {
            // OpenFIGI rejects large batches (HTTP 413): send in chunks of 10.
            for (int from = 0; from < missing.size(); from += 10) {
                List<String> chunk = missing.subList(from, Math.min(from + 10, missing.size()));
                JSONArray req = new JSONArray();
                for (String c : chunk) {
                    JSONObject job = new JSONObject();
                    job.put("idType", "ID_CUSIP");
                    job.put("idValue", c);
                    req.put(job);
                }
                JSONArray res = new JSONArray(post("https://api.openfigi.com/v3/mapping", ua, req.toString()));
                for (int i = 0; i < res.length(); i++) {
                    JSONObject r = res.getJSONObject(i);
                    String ticker = "";
                    if (r.has("data")) {
                        JSONArray data = r.getJSONArray("data");
                        // FIGI returns many listings per CUSIP, often with
                        // foreign/placeholder tickers (HONGBP, EXMOC, SPCX).
                        // Take the consensus ticker (most frequent); tie-break
                        // toward the US listing, then the shortest ticker.
                        Map<String, Integer> votes = new LinkedHashMap<>();
                        Map<String, Boolean> isUs = new LinkedHashMap<>();
                        for (int j = 0; j < data.length(); j++) {
                            JSONObject d = data.getJSONObject(j);
                            String t = d.optString("ticker", "").trim();
                            if (t.isEmpty()) continue;
                            votes.put(t, votes.getOrDefault(t, 0) + 1);
                            if ("US".equals(d.optString("exchCode", ""))) isUs.put(t, true);
                        }
                        int best = -1;
                        for (Map.Entry<String, Integer> e : votes.entrySet()) {
                            String t = e.getKey();
                            if (!cleanTicker(t)) continue;
                            int score = e.getValue() * 100
                                + (isUs.getOrDefault(t, false) ? 50 : 0)
                                - Math.min(t.length(), 20);
                            if (score > best) { best = score; ticker = t; }
                        }
                    }
                    cache.put(chunk.get(i), ticker); // empty = unresolved, cached to avoid re-query
                }
            }
            saveCache(cachePath, cache);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : cusips) out.put(c, cache.getOrDefault(c, ""));
        return out;
    }

    // ---------- compare ----------

    static String money(long v) {
        boolean neg = v < 0; long a = Math.abs(v);
        String s = a >= 1_000_000_000L ? String.format(Locale.ROOT, "$%.1fB", a / 1e9)
            : a >= 1_000_000L ? String.format(Locale.ROOT, "$%.1fM", a / 1e6)
            : a >= 1_000L ? String.format(Locale.ROOT, "$%.0fK", a / 1e3)
            : "$" + a;
        return neg ? "-" + s : s;
    }

    static class Delta {
        String cusip, name;
        long oldV, newV, oldS, newS;
        long dV() { return newV - oldV; }
    }

    static String renderCompare(JSONObject oldS, JSONObject newS, int top, long minUsd,
                                Path cachePath, String ua, Map<String, String> verified) throws Exception {
        JSONObject oldH = oldS.getJSONObject("holdings"), newH = newS.getJSONObject("holdings");
        List<Delta> deltas = new ArrayList<>();
        java.util.HashSet<String> all = new java.util.HashSet<>();
        for (String k : oldH.keySet()) all.add(k);
        for (String k : newH.keySet()) all.add(k);
        for (String c : all) {
            Delta d = new Delta();
            d.cusip = c;
            JSONObject o = oldH.optJSONObject(c), n = newH.optJSONObject(c);
            d.name = n != null ? n.optString("name", "") : o.optString("name", "");
            d.oldV = o == null ? 0 : o.optLong("value_usd", 0);
            d.newV = n == null ? 0 : n.optLong("value_usd", 0);
            d.oldS = o == null ? 0 : o.optLong("shares", 0);
            d.newS = n == null ? 0 : n.optLong("shares", 0);
            deltas.add(d);
        }
        List<Delta> fresh = new ArrayList<>(), exited = new ArrayList<>(),
            adds = new ArrayList<>(), cuts = new ArrayList<>();
        for (Delta d : deltas) {
            if (d.oldV == 0 && d.newV > 0) fresh.add(d);
            else if (d.newV == 0 && d.oldV > 0) exited.add(d);
            else if (d.dV() > 0) adds.add(d);
            else if (d.dV() < 0) cuts.add(d);
        }
        fresh.sort((a, b) -> Long.compare(b.newV, a.newV));
        exited.sort((a, b) -> Long.compare(b.oldV, a.oldV));
        adds.sort((a, b) -> Long.compare(b.dV(), a.dV()));
        cuts.sort((a, b) -> Long.compare(a.dV(), b.dV()));
        // Resolve tickers for everything we will print.
        java.util.LinkedHashSet<String> need = new java.util.LinkedHashSet<>();
        for (List<Delta> l : List.of(fresh, exited, adds, cuts))
            for (int i = 0; i < Math.min(top, l.size()); i++) need.add(l.get(i).cusip);
        Map<String, String> tickers = resolveTickers(new ArrayList<>(need), cachePath, ua);
        // Verified map wins over FIGI guesses.
        if (verified != null)
            for (String c : need)
                if (verified.containsKey(c)) tickers.put(c, verified.get(c));

        String mgr = newS.optString("manager", "");
        StringBuilder sb = new StringBuilder();
        sb.append("## 13F changes — ").append(mgr)
          .append(" (").append(oldS.optString("period", "?"))
          .append(" → ").append(newS.optString("period", "?")).append(")\n");
        java.util.function.Function<Delta, String> label = d -> {
            String t = tickers.getOrDefault(d.cusip, "");
            return t.isEmpty() ? d.name + " [" + d.cusip + "]" : t + " (" + d.name + ")";
        };
        sb.append("**New positions:** ");
        if (fresh.isEmpty()) sb.append("none\n");
        else {
            sb.append("\n");
            for (int i = 0; i < Math.min(top, fresh.size()); i++) {
                Delta d = fresh.get(i);
                if (d.newV < minUsd) break;
                sb.append("- ").append(label.apply(d)).append(" — ").append(money(d.newV)).append("\n");
            }
        }
        sb.append("**Exited:** ");
        if (exited.isEmpty()) sb.append("none\n");
        else {
            sb.append("\n");
            for (int i = 0; i < Math.min(top, exited.size()); i++) {
                Delta d = exited.get(i);
                if (d.oldV < minUsd) break;
                sb.append("- ").append(label.apply(d)).append(" — was ").append(money(d.oldV)).append("\n");
            }
        }
        sb.append("**Top adds:**\n");
        int shown = 0;
        for (Delta d : adds) {
            if (shown >= top || d.dV() < minUsd) break;
            double pct = d.oldV == 0 ? 0 : 100.0 * d.dV() / d.oldV;
            sb.append("- ").append(label.apply(d)).append(" +").append(money(d.dV()))
              .append(String.format(Locale.ROOT, " (+%.0f%%)", pct)).append("\n");
            shown++;
        }
        if (shown == 0) sb.append("none above threshold\n");
        sb.append("**Top cuts:**\n");
        shown = 0;
        for (Delta d : cuts) {
            if (shown >= top || -d.dV() < minUsd) break;
            double pct = d.oldV == 0 ? 0 : 100.0 * -d.dV() / d.oldV;
            sb.append("- ").append(label.apply(d)).append(" −").append(money(-d.dV()))
              .append(String.format(Locale.ROOT, " (−%.0f%%)", pct)).append("\n");
            shown++;
        }
        if (shown == 0) sb.append("none above threshold\n");
        return sb.toString();
    }

    // ---------- merge ----------

    static JSONObject mergeSnapshots(JSONObject a, JSONObject b, String manager) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (String key : new String[]{"a", "b"}) {
            JSONObject h = (key.equals("a") ? a : b).getJSONObject("holdings");
            for (String cusip : h.keySet()) {
                JSONObject src = h.getJSONObject(cusip);
                JSONObject dst = m.get(cusip);
                if (dst == null) {
                    JSONObject c = new JSONObject();
                    c.put("name", src.optString("name", ""));
                    c.put("value_usd", src.optLong("value_usd", 0));
                    c.put("shares", src.optLong("shares", 0));
                    m.put(cusip, c);
                } else {
                    dst.put("value_usd", dst.getLong("value_usd") + src.optLong("value_usd", 0));
                    dst.put("shares", dst.getLong("shares") + src.optLong("shares", 0));
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("manager", manager);
        out.put("cik", a.optString("cik", "") + "+" + b.optString("cik", ""));
        out.put("period", a.optString("period", ""));
        out.put("filed", a.optString("filed", ""));
        out.put("merged_from", new JSONArray()
            .put(a.optString("manager", "")).put(b.optString("manager", "")));
        JSONObject h = new JSONObject();
        for (Map.Entry<String, JSONObject> e : m.entrySet()) h.put(e.getKey(), e.getValue());
        out.put("holdings", h);
        return out;
    }

    // ---------- rank ----------

    static String tier(double combinedPct) {
        if (combinedPct >= 25) return "\uD83D\uDD12 locked";
        if (combinedPct >= 15) return "heavy";
        if (combinedPct >= 8) return "moderate";
        return "light";
    }

    /** Rank tickers by combined Big Three ownership % of shares outstanding.
     *  cusipMap: ticker -> list of CUSIPs (verified common-stock CUSIPs). */
    static String renderRank(List<JSONObject> snaps, List<String> tickers, JSONObject meta,
                             JSONObject cusipMap) {
        Map<String, List<String>> tickerToCusips = new LinkedHashMap<>();
        for (String t : tickers) {
            JSONArray arr = cusipMap.optJSONArray(t);
            List<String> l = new ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) l.add(arr.getString(i));
            tickerToCusips.put(t, l);
        }

        StringBuilder sb = new StringBuilder();
        String period = snaps.isEmpty() ? "?" : snaps.get(0).optString("period", "?");
        sb.append("## Big Three sticky-supply ranking (").append(period).append(")\n");
        sb.append("Combined Vanguard + BlackRock + State Street % of shares outstanding. ")
          .append("Tiers: \uD83D\uDD12 locked ≥25% · heavy ≥15% · moderate ≥8% · light <8%.\n");
        List<String[]> rows = new ArrayList<>();
        for (String t : tickers) {
            List<String> cusips = tickerToCusips.get(t);
            JSONObject tm = meta.optJSONObject(t);
            long sharesOut = tm == null ? 0 : tm.optLong("shares_out", 0);
            String company = tm == null ? t : tm.optString("company", t);
            double[] pcts = new double[snaps.size()];
            double combined = 0;
            if (sharesOut > 0) {
                for (int i = 0; i < snaps.size(); i++) {
                    JSONObject holdings = snaps.get(i).getJSONObject("holdings");
                    long sh = 0;
                    for (String cusip : cusips) {
                        JSONObject h = holdings.optJSONObject(cusip);
                        if (h != null) sh += h.optLong("shares", 0);
                    }
                    pcts[i] = 100.0 * sh / sharesOut;
                    combined += pcts[i];
                }
            }
            String[] row = new String[snaps.size() + 4];
            row[0] = t;
            row[1] = company;
            for (int i = 0; i < snaps.size(); i++)
                row[2 + i] = String.format(Locale.ROOT, "%.1f%%", pcts[i]);
            row[2 + snaps.size()] = String.format(Locale.ROOT, "%.1f%%", combined);
            row[3 + snaps.size()] = tier(combined);
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare(
            Double.parseDouble(b[2 + snaps.size()].replace("%", "")),
            Double.parseDouble(a[2 + snaps.size()].replace("%", ""))));
        // Bottom Finders double-filing guard (technique #3): summed 13F
        // ownership can exceed 100% because lent shares get filed twice.
        boolean guardTripped = false;
        for (String[] r : rows) {
            double combined = Double.parseDouble(r[2 + snaps.size()].replace("%", ""));
            if (combined > 95) { guardTripped = true; break; }
        }
        // Header
        sb.append("| # | Ticker | ");
        for (JSONObject s : snaps) sb.append(s.optString("manager", "")).append(" | ");
        sb.append("Combined | Tier |\n|---|--------|");
        for (int i = 0; i < snaps.size(); i++) sb.append("---------|");
        sb.append("----------|------|\n");
        int n = 1;
        for (String[] r : rows) {
            sb.append("| ").append(n++).append(" | **").append(r[0]).append("** | ");
            for (int i = 0; i < snaps.size(); i++) sb.append(r[2 + i]).append(" | ");
            sb.append("**").append(r[2 + snaps.size()]).append("** | ")
              .append(r[3 + snaps.size()]).append(" |\n");
        }
        if (guardTripped)
            sb.append("\n_Note: a combined figure above 95% likely includes lent-share double filing — treat as an upper bound, not exact ownership._\n");
        return sb.toString();
    }

    // ---------- anchor ----------

    /** Anchor-holder table (Bottom Finders technique #1): for each ticker, the
     *  single largest 13F holder across all given snapshots. >8% is flagged as
     *  a large tracked holder (2026-10-08: Bottom Finders' market-maker label is
     *  only an interpretation, never a stated fact). */
    static String renderAnchor(List<JSONObject> snaps, List<String> tickers, JSONObject meta,
                               JSONObject cusipMap) {
        StringBuilder sb = new StringBuilder();
        String period = snaps.isEmpty() ? "?" : snaps.get(0).optString("period", "?");
        sb.append("## Anchor holders (").append(period).append(")\n");
        sb.append("Largest single 13F holder per ticker. >8% flagged as a large tracked holder (Bottom Finders' market-maker label is only an interpretation).\n");
        sb.append("| Ticker | Anchor holder | % of shares out | Flag |\n");
        sb.append("|--------|----------------|-----------------|------|\n");
        for (String t : tickers) {
            JSONArray arr = cusipMap.optJSONArray(t);
            List<String> cusips = new ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) cusips.add(arr.getString(i));
            JSONObject tm = meta.optJSONObject(t);
            long sharesOut = tm == null ? 0 : tm.optLong("shares_out", 0);
            String bestName = "—";
            double bestPct = 0;
            for (JSONObject s : snaps) {
                JSONObject holdings = s.getJSONObject("holdings");
                long sh = 0;
                for (String c : cusips) {
                    JSONObject h = holdings.optJSONObject(c);
                    if (h != null) sh += h.optLong("shares", 0);
                }
                if (sharesOut > 0) {
                    double pct = 100.0 * sh / sharesOut;
                    if (pct > bestPct) {
                        bestPct = pct;
                        bestName = s.optString("manager", "?");
                    }
                }
            }
            String flag = bestPct > 8 ? "largest tracked holder" : "—";
            String displayName = bestName;
            if (displayName.startsWith("active_"))
                displayName = displayName.substring("active_".length());
            if (!displayName.isEmpty())
                displayName = Character.toUpperCase(displayName.charAt(0)) + displayName.substring(1);
            sb.append("| **").append(t).append("** | ").append(displayName).append(" | ")
              .append(String.format(Locale.ROOT, "%.1f%%", bestPct)).append(" | ")
              .append(flag).append(" |\n");
        }
        return sb.toString();
    }

    // ---------- self-test ----------

    static int failures = 0;

    static void check(String name, boolean cond) {
        if (cond) System.out.println("  PASS " + name);
        else { System.out.println("  FAIL " + name); failures++; }
    }

    static final String FIXTURE_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<informationTable xmlns=\"http://www.sec.gov/edgar/document/thirteenf/informationtable\">" +
        "<infoTable><nameOfIssuer>ACME CORP</nameOfIssuer><titleOfClass>COM</titleOfClass>" +
        "<cusip>000001234</cusip><value>1500</value>" +
        "<shrsOrPrnAmt><sshPrnamt>10000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt></infoTable>" +
        "<infoTable><nameOfIssuer>ACME CORP</nameOfIssuer><titleOfClass>COM</titleOfClass>" +
        "<cusip>000001234</cusip><value>500</value>" +
        "<shrsOrPrnAmt><sshPrnamt>4000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt></infoTable>" +
        "<infoTable><nameOfIssuer>BETA INC</nameOfIssuer><titleOfClass>COM</titleOfClass>" +
        "<cusip>000009999</cusip><value>2000</value>" +
        "<shrsOrPrnAmt><sshPrnamt>5000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt></infoTable>" +
        "</informationTable>";

    static void selfTest() throws Exception {
        System.out.println("ThirteenFWatch --self-test");
        // 1. parse aggregates duplicate CUSIPs and converts $000s
        Map<String, JSONObject> h = parseInfoTable(FIXTURE_XML);
        check("parse-count", h.size() == 2);
        check("parse-aggregates-dup-cusip",
            h.get("000001234").getLong("value_usd") == 2000L
            && h.get("000001234").getLong("shares") == 14000L);
        check("parse-single", h.get("000009999").getLong("shares") == 5000L);

        // 2. merge combines holdings
        JSONObject a = new JSONObject().put("manager", "A").put("cik", "1")
            .put("period", "2026-06-30").put("filed", "2026-08-01")
            .put("holdings", new JSONObject().put("C1",
                new JSONObject().put("name", "X").put("value_usd", 1000).put("shares", 10)));
        JSONObject b = new JSONObject().put("manager", "B").put("cik", "2")
            .put("period", "2026-06-30").put("filed", "2026-08-01")
            .put("holdings", new JSONObject().put("C1",
                new JSONObject().put("name", "X").put("value_usd", 2000).put("shares", 20)));
        JSONObject m = mergeSnapshots(a, b, "AB");
        check("merge-sums", m.getJSONObject("holdings").getJSONObject("C1").getLong("shares") == 30L
            && m.getString("manager").equals("AB"));

        // 3. tier boundaries
        check("tier-locked", tier(25.0).contains("locked"));
        check("tier-heavy", tier(15.0).equals("heavy"));
        check("tier-moderate", tier(8.0).equals("moderate"));
        check("tier-light", tier(7.9).equals("light"));

        // 4. money formatting
        check("money-b", money(1_500_000_000L).equals("$1.5B"));
        check("money-m", money(2_400_000L).equals("$2.4M"));
        check("money-neg", money(-750_000L).equals("-$750K"));

        // 5. cache round-trip (no network)
        Path tmp = Files.createTempDirectory("t13f-test");
        Path cp = tmp.resolve("cache.json");
        Map<String, String> c0 = new LinkedHashMap<>();
        c0.put("ABC", "T1");
        saveCache(cp, c0);
        check("cache-roundtrip", loadCache(cp).get("ABC").equals("T1"));

        // 6. compare detects new/exited/adds (tickers pre-seeded in cache -> no network)
        Map<String, String> c1 = new LinkedHashMap<>();
        c1.put("000001234", "ACME");
        c1.put("000009999", "BETA");
        saveCache(cp, c1);
        JSONObject oldS = new JSONObject().put("manager", "M").put("period", "2026-03-31")
            .put("holdings", new JSONObject()
                .put("000009999", new JSONObject().put("name", "BETA INC").put("value_usd", 2_000_000L).put("shares", 5000)));
        JSONObject newS = new JSONObject().put("manager", "M").put("period", "2026-06-30")
            .put("holdings", new JSONObject()
                .put("000001234", new JSONObject().put("name", "ACME CORP").put("value_usd", 2_000_000L).put("shares", 14000)));
        String out = renderCompare(oldS, newS, 10, 0, cp, DEFAULT_UA, null);
        check("compare-new", out.contains("ACME") && out.contains("New positions"));
        check("compare-exited", out.contains("BETA") && out.contains("Exited"));

        // 7. anchor flags >8% holders; double-filing guard trips >95%
        JSONObject snapA = new JSONObject().put("manager", "Big").put("period", "2026-06-30")
            .put("holdings", new JSONObject().put("C1",
                new JSONObject().put("name", "X").put("value_usd", 100).put("shares", 900)));
        JSONObject snapB = new JSONObject().put("manager", "Small").put("period", "2026-06-30")
            .put("holdings", new JSONObject().put("C1",
                new JSONObject().put("name", "X").put("value_usd", 10).put("shares", 50)));
        JSONObject metaT = new JSONObject().put("T1",
            new JSONObject().put("shares_out", 1000).put("company", "T1 Co"));
        JSONObject mapT = new JSONObject().put("T1", new JSONArray().put("C1"));
        String anchorOut = renderAnchor(List.of(snapA, snapB), List.of("T1"), metaT, mapT);
        check("anchor-picks-largest", anchorOut.contains("Big") && anchorOut.contains("90.0%"));
        check("anchor-flags-large-holder", anchorOut.contains("largest tracked holder") && !anchorOut.contains("probable market maker"));
        String rankOut = renderRank(List.of(snapA, snapB), List.of("T1"), metaT, mapT);
        check("guard-quiet-at-95", !rankOut.contains("double filing"));

        // 8. guard trips above 95%
        JSONObject snapC = new JSONObject().put("manager", "Big").put("period", "2026-06-30")
            .put("holdings", new JSONObject().put("C1",
                new JSONObject().put("name", "X").put("value_usd", 100).put("shares", 960)));
        String rankOut2 = renderRank(List.of(snapC), List.of("T1"), metaT, mapT);
        check("guard-trips-above-95", rankOut2.contains("double filing"));
        System.out.println(failures == 0 ? "SELF-TEST GREEN" : "SELF-TEST RED: " + failures);
        if (failures > 0) System.exit(1);
    }

    // ---------- main ----------

    static void usage() {
        System.out.println("usage: ThirteenFWatch.java --fetch <cik> [--period YYYY-MM-DD] --out <snap.json> [--ua <ua>]");
        System.out.println("                           --merge <a.json> <b.json> --manager <name> --out <merged.json>");
        System.out.println("                           --compare <old.json> <new.json> [--top N] [--min-usd X] [--cache <p>] [--ua <ua>]");
        System.out.println("                           --rank <snap...> --tickers T1,T2 --meta <meta.json> --cusip-map <map.json>");
        System.out.println("                           --anchor <snap...> --tickers T1,T2 --meta <meta.json> --cusip-map <map.json>");
        System.out.println("                           --self-test");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        String ua = DEFAULT_UA, cache = null, out = null, period = null, manager = null, meta = null, tickers = null,
            cusipMap = null;
        int top = 10;
        long minUsd = 0;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ua" -> ua = args[++i];
                case "--cache" -> cache = args[++i];
                case "--out" -> out = args[++i];
                case "--period" -> period = args[++i];
                case "--manager" -> manager = args[++i];
                case "--meta" -> meta = args[++i];
                case "--tickers" -> tickers = args[++i];
                case "--cusip-map" -> cusipMap = args[++i];
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--min-usd" -> minUsd = Long.parseLong(args[++i]);
                default -> rest.add(args[i]);
            }
        }
        if (rest.isEmpty()) usage();
        Path cachePath = cache == null
            ? Path.of(System.getenv("HOME") == null ? "." : System.getenv("HOME"),
                "workspace/goals/big-cap-insider-and-politician-buy-watch/hidden_files/thirteenf/cusip_cache.json")
            : Path.of(cache);
        switch (rest.get(0)) {
            case "--self-test" -> selfTest();
            case "--fetch" -> {
                if (rest.size() < 2 || out == null) usage();
                JSONObject snap = fetchSnapshot(rest.get(1), period, manager, ua);
                writeJson(Path.of(out), snap);
                System.out.println("saved " + out + " holdings=" + snap.getJSONObject("holdings").length()
                    + " period=" + snap.optString("period"));
            }
            case "--merge" -> {
                if (rest.size() < 3 || out == null || manager == null) usage();
                JSONObject a = new JSONObject(Files.readString(Path.of(rest.get(1)), StandardCharsets.UTF_8));
                JSONObject b = new JSONObject(Files.readString(Path.of(rest.get(2)), StandardCharsets.UTF_8));
                writeJson(Path.of(out), mergeSnapshots(a, b, manager));
                System.out.println("saved " + out);
            }
            case "--compare" -> {
                if (rest.size() < 3) usage();
                JSONObject o = new JSONObject(Files.readString(Path.of(rest.get(1)), StandardCharsets.UTF_8));
                JSONObject n = new JSONObject(Files.readString(Path.of(rest.get(2)), StandardCharsets.UTF_8));
                Map<String, String> verified = null;
                if (cusipMap != null) {
                    verified = new LinkedHashMap<>();
                    JSONObject mapO = new JSONObject(Files.readString(Path.of(cusipMap), StandardCharsets.UTF_8));
                    for (String t : mapO.keySet()) {
                        JSONArray arr = mapO.optJSONArray(t);
                        if (arr == null) continue;
                        for (int i = 0; i < arr.length(); i++) verified.put(arr.getString(i), t);
                    }
                }
                System.out.print(renderCompare(o, n, top, minUsd, cachePath, ua, verified));
            }
            case "--rank" -> {
                if (rest.size() < 2 || tickers == null || meta == null || cusipMap == null) usage();
                List<JSONObject> snaps = new ArrayList<>();
                for (int i = 1; i < rest.size(); i++)
                    snaps.add(new JSONObject(Files.readString(Path.of(rest.get(i)), StandardCharsets.UTF_8)));
                JSONObject metaO = new JSONObject(Files.readString(Path.of(meta), StandardCharsets.UTF_8));
                JSONObject mapO = new JSONObject(Files.readString(Path.of(cusipMap), StandardCharsets.UTF_8));
                System.out.print(renderRank(snaps, List.of(tickers.split(",")), metaO, mapO));
            }
            case "--anchor" -> {
                if (rest.size() < 2 || tickers == null || meta == null || cusipMap == null) usage();
                List<JSONObject> snaps = new ArrayList<>();
                for (int i = 1; i < rest.size(); i++)
                    snaps.add(new JSONObject(Files.readString(Path.of(rest.get(i)), StandardCharsets.UTF_8)));
                JSONObject metaO = new JSONObject(Files.readString(Path.of(meta), StandardCharsets.UTF_8));
                JSONObject mapO = new JSONObject(Files.readString(Path.of(cusipMap), StandardCharsets.UTF_8));
                System.out.print(renderAnchor(snaps, List.of(tickers.split(",")), metaO, mapO));
            }
            default -> usage();
        }
    }
}
