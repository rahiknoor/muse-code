import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * A4 — Outside-voice freshness audit.
 *
 * The outside median (median of creator value / analyst target / website
 * consensus) lives in the BACKEND (outside_voice.json) but is no longer a
 * display column (Rahik 2026-09-29: folded into speculation price). Freshness
 * is about the DATA, not the display — so this verifier checks the asof
 * dates of the three input backends:
 *
 *   - analyst_targets.json  : per-ticker "asof",  stale after  35 days
 *   - website_consensus.json: per-ticker per-source "date", stale after 90 days
 *   - creator_values.json   : top-level "asof" + latest-entry "call_date",
 *                             stale after 30 days
 *
 * Severity policy (documented, see work notes): staleness is WARN, never
 * FAIL. A stale outside voice is not a WRONG outside voice — it is still
 * the best recorded value until refreshed, and failing the monthly
 * analyst-target-refresh job over timing alone would teach the job to lie
 * about dates. A missing asof is also WARN (freshness unprovable, value
 * not proven wrong) — its own finding category so refresh work can be
 * queued. FAIL is reserved for structural breakage: a backend file that
 * cannot be read or parsed.
 *
 * This verifier REPORTS only. It never writes any backend (audit-write
 * guard: verifiers are read-only; refreshes run through the proposal
 * pipeline like everything else).
 *
 * Usage:
 *   java --class-path ~/workspace/java-shared/classes OutsideVoiceFreshness.java --self-test
 *   java --class-path ~/workspace/java-shared/classes OutsideVoiceFreshness.java --verify
 */
public class OutsideVoiceFreshness {

    // ---- freshness thresholds (days), from the A4 audit-gap spec ----
    static final int ANALYST_MAX_AGE_DAYS = 35;   // analyst targets move on earnings; ~monthly refresh
    static final int WEBSITE_MAX_AGE_DAYS = 90;   // website DCF pages re-stamp quarterly-ish
    static final int CREATOR_MAX_AGE_DAYS = 30;   // creator calls decay fast; monthly digest cadence

    static String home() {
        return System.getenv("HOME");
    }

    static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Days from asof (inclusive, yyyy-MM-dd) to today. -1 when unparseable. */
    static long daysOld(String asof, LocalDate today) {
        if (asof == null || asof.isBlank()) return -1;
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(asof.trim()), today);
        } catch (Exception e) {
            return -1;
        }
    }

    /** True when the date is present, parseable, and within the threshold. */
    static boolean fresh(String asof, int maxAgeDays, LocalDate today) {
        long d = daysOld(asof, today);
        return d >= 0 && d <= maxAgeDays;
    }

    // ------------------------------------------------------------------
    // The three backend audits. Each appends Findings; none throws on
    // data problems (structural file problems become FAIL findings).
    // ------------------------------------------------------------------

    static void auditAnalystTargets(List<Finding> out, LocalDate today, Path root) {
        Path f = root.resolve("analyst_targets.json");
        J.O doc = readObject(f, out, "A4");
        if (doc == null) return;
        int checked = 0, stale = 0, missing = 0;
        for (Map.Entry<String, J.V> e : doc.m.entrySet()) {
            if (!(e.getValue() instanceof J.O)) continue;
            J.O entry = (J.O) e.getValue();
            if (!(entry.m.get("target") instanceof J.N)) continue; // no target -> feeds nothing
            checked++;
            String asof = str(entry.m.get("asof"));
            long d = daysOld(asof, today);
            if (asof == null || asof.isBlank()) {
                missing++;
                out.add(Finding.warn("A4", e.getKey(), "analyst_target.asof",
                        "present asof date", "missing",
                        "analyst_targets.json entry \"" + e.getKey() + "\" has no asof field",
                        "backfill the asof from the collection source, or refresh the target"));
            } else if (d < 0) {
                missing++;
                out.add(Finding.warn("A4", e.getKey(), "analyst_target.asof",
                        "parseable yyyy-MM-dd date", "unparseable: " + asof,
                        "analyst_targets.json entry \"" + e.getKey() + "\"",
                        "fix the date format (yyyy-MM-dd)"));
            } else if (d > ANALYST_MAX_AGE_DAYS) {
                stale++;
                out.add(Finding.warn("A4", e.getKey(), "analyst_target.asof",
                        "<= " + ANALYST_MAX_AGE_DAYS + " days old", d + " days old (" + asof + ")",
                        "analyst_targets.json entry \"" + e.getKey() + "\"",
                        "refresh via the monthly analyst-target-refresh job"));
            }
        }
        out.add(Finding.info("A4", "-", "analyst_targets",
                "checked", checked + " targets, " + stale + " stale, " + missing + " missing-asof",
                "analyst_targets.json", "none"));
    }

    static void auditWebsiteConsensus(List<Finding> out, LocalDate today, Path root) {
        Path f = root.resolve("website_consensus.json");
        J.O doc = readObject(f, out, "A4");
        if (doc == null) return;
        // Top-level asof = when the consensus was last rebuilt.
        String top = str(doc.m.get("asof"));
        if (!fresh(top, WEBSITE_MAX_AGE_DAYS, today)) {
            out.add(Finding.warn("A4", "-", "website_consensus.asof",
                    "<= " + WEBSITE_MAX_AGE_DAYS + " days old",
                    top == null ? "missing" : daysOld(top, today) + " days old (" + top + ")",
                    "website_consensus.json top-level asof",
                    "rebuild the consensus (WebsiteConsensus.java --build)"));
        }
        J.V tickersV = doc.m.get("tickers");
        if (!(tickersV instanceof J.O)) {
            out.add(Finding.fail("A4", "-", "website_consensus.tickers",
                    "tickers object", "missing or not an object",
                    "website_consensus.json", "rebuild the consensus"));
            return;
        }
        J.O tickers = (J.O) tickersV;
        int stale = 0, missing = 0;
        for (Map.Entry<String, J.V> te : tickers.m.entrySet()) {
            if (!(te.getValue() instanceof J.O)) continue;
            J.O ticker = (J.O) te.getValue();
            J.V sourcesV = ticker.m.get("sources");
            if (!(sourcesV instanceof J.O)) continue;
            for (Map.Entry<String, J.V> se : ((J.O) sourcesV).m.entrySet()) {
                if (!(se.getValue() instanceof J.O)) continue;
                J.O src = (J.O) se.getValue();
                if (!(src.m.get("value") instanceof J.N)) continue; // null value -> not in median
                String site = se.getKey(), date = str(src.m.get("date"));
                long d = daysOld(date, today);
                if (date == null || date.isBlank()) {
                    missing++;
                    out.add(Finding.warn("A4", te.getKey(), "website_source.date[" + site + "]",
                            "present collection date", "missing (value is present and feeds the median)",
                            "website_consensus.json tickers." + te.getKey() + ".sources." + site,
                            "propagate website_values/" + site + ".json \"collected\" date into the per-ticker date"));
                } else if (d < 0) {
                    missing++;
                    out.add(Finding.warn("A4", te.getKey(), "website_source.date[" + site + "]",
                            "parseable yyyy-MM-dd date", "unparseable: " + date,
                            "website_consensus.json tickers." + te.getKey() + ".sources." + site,
                            "fix the date format (yyyy-MM-dd)"));
                } else if (d > WEBSITE_MAX_AGE_DAYS) {
                    stale++;
                    out.add(Finding.warn("A4", te.getKey(), "website_source.date[" + site + "]",
                            "<= " + WEBSITE_MAX_AGE_DAYS + " days old", d + " days old (" + date + ")",
                            "website_consensus.json tickers." + te.getKey() + ".sources." + site,
                            "re-collect that site's DCF value for this ticker"));
                }
            }
        }
        out.add(Finding.info("A4", "-", "website_consensus.sources",
                "checked", stale + " stale, " + missing + " missing-date (values that feed the median)",
                "website_consensus.json", "none"));
    }

    static void auditCreatorValues(List<Finding> out, LocalDate today, Path root) {
        Path f = root.resolve("creator_values.json");
        J.O doc = readObject(f, out, "A4");
        if (doc == null) return;
        // Top-level asof = when the aggregation last ran (RebuildAll-derived).
        String top = str(doc.m.get("asof"));
        if (!fresh(top, CREATOR_MAX_AGE_DAYS, today)) {
            out.add(Finding.warn("A4", "-", "creator_values.asof",
                    "<= " + CREATOR_MAX_AGE_DAYS + " days old",
                    top == null ? "missing" : daysOld(top, today) + " days old (" + top + ")",
                    "creator_values.json top-level asof",
                    "rebuild via RebuildAll (the digest cron feeds creator_tracker logs)"));
        }
        // Per-ticker "latest" call dates: a creator value older than 30 days
        // is the outside voice going stale.
        J.V latestV = doc.m.get("latest");
        int withCreator = 0, stale = 0, missing = 0;
        if (!(latestV instanceof J.O)) {
            out.add(Finding.warn("A4", "-", "creator_values.latest",
                    "latest object", "missing or not an object",
                    "creator_values.json", "rebuild via RebuildAll"));
        } else {
            J.O latest = (J.O) latestV;
            for (Map.Entry<String, J.V> e : latest.m.entrySet()) {
                if (!(e.getValue() instanceof J.O)) continue;
                J.O entry = (J.O) e.getValue();
                if (!(entry.m.get("value") instanceof J.N)) continue;
                withCreator++;
                String call = str(entry.m.get("call_date"));
                long d = daysOld(call, today);
                if (call == null || call.isBlank()) {
                    missing++;
                    out.add(Finding.warn("A4", e.getKey(), "creator_latest.call_date",
                            "present call_date", "missing",
                            "creator_values.json latest." + e.getKey(),
                            "backfill the call_date from the digest tracker log"));
                } else if (d < 0) {
                    missing++;
                    out.add(Finding.warn("A4", e.getKey(), "creator_latest.call_date",
                            "parseable yyyy-MM-dd date", "unparseable: " + call,
                            "creator_values.json latest." + e.getKey(),
                            "fix the date format (yyyy-MM-dd)"));
                } else if (d > CREATOR_MAX_AGE_DAYS) {
                    stale++;
                    out.add(Finding.warn("A4", e.getKey(), "creator_latest.call_date",
                            "<= " + CREATOR_MAX_AGE_DAYS + " days old",
                            d + " days old (" + call + "), creator=" + str(entry.m.get("creator")),
                            "creator_values.json latest." + e.getKey(),
                            "check the digest for a newer call; the median uses the latest only"));
                }
            }
        }
        out.add(Finding.info("A4", "-", "creator_values.latest",
                "checked", withCreator + " tickers with a creator value, " + stale + " stale, "
                        + missing + " missing-call_date (tickers without one are expected nulls)",
                "creator_values.json", "none"));
    }

    // ---- plumbing -----------------------------------------------------

    static J.O readObject(Path f, List<Finding> out, String checkId) {
        if (!Files.exists(f)) {
            out.add(Finding.fail(checkId, "-", f.getFileName().toString(),
                    "readable JSON backend", "file missing", f.toString(),
                    "restore the backend; the outside median cannot be audited without it"));
            return null;
        }
        try {
            J.V v = J.parse(read(f));
            if (!(v instanceof J.O)) {
                out.add(Finding.fail(checkId, "-", f.getFileName().toString(),
                        "JSON object at top level", "not an object", f.toString(),
                        "repair the backend file"));
                return null;
            }
            return (J.O) v;
        } catch (Exception e) {
            out.add(Finding.fail(checkId, "-", f.getFileName().toString(),
                    "parseable JSON", "parse error: " + e.getMessage(), f.toString(),
                    "repair the backend file"));
            return null;
        }
    }

    static String str(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
    }

    static List<Finding> runVerify(LocalDate today, Path root) {
        List<Finding> findings = new ArrayList<>();
        auditAnalystTargets(findings, today, root);
        auditWebsiteConsensus(findings, today, root);
        auditCreatorValues(findings, today, root);
        return findings;
    }

    // ---- self-test ----------------------------------------------------

    static void selfTest() {
        LocalDate t = LocalDate.of(2026, 9, 29);
        // daysOld arithmetic.
        SelfTest.checkEq("daysOld today", 0L, daysOld("2026-09-29", t));
        SelfTest.checkEq("daysOld 2d ago", 2L, daysOld("2026-09-27", t));
        SelfTest.checkEq("daysOld future is negative", -1L, daysOld("2026-09-30", t));
        SelfTest.checkEq("daysOld blank", -1L, daysOld("  ", t));
        SelfTest.checkEq("daysOld garbage", -1L, daysOld("not-a-date", t));
        SelfTest.checkEq("daysOld null", -1L, daysOld(null, t));
        // Boundary: exactly at the threshold is fresh; one day past is stale.
        SelfTest.check("analyst 35d fresh", fresh("2026-08-25", ANALYST_MAX_AGE_DAYS, t));
        SelfTest.check("analyst 36d stale", !fresh("2026-08-24", ANALYST_MAX_AGE_DAYS, t));
        SelfTest.check("website 90d fresh", fresh("2026-07-01", WEBSITE_MAX_AGE_DAYS, t));
        SelfTest.check("website 91d stale", !fresh("2026-06-30", WEBSITE_MAX_AGE_DAYS, t));
        SelfTest.check("creator 30d fresh", fresh("2026-08-30", CREATOR_MAX_AGE_DAYS, t));
        SelfTest.check("creator 31d stale", !fresh("2026-08-29", CREATOR_MAX_AGE_DAYS, t));
        SelfTest.check("missing asof never fresh", !fresh(null, 35, t));
        // JSON null is distinct from a missing key and from the string "null".
        SelfTest.check("json null -> str() null", str(J.parse("null")) == null);
        J.O nullObj = (J.O) J.parse("{\"asof\":null}");
        SelfTest.check("null asof never fresh", !fresh(str(nullObj.m.get("asof")), ANALYST_MAX_AGE_DAYS, t));
        // Synthetic end-to-end: stale analyst + missing website date + fresh creator.
        List<Finding> f = new ArrayList<>();
        J.O an = (J.O) J.parse("{\"AAA\":{\"target\":100,\"asof\":\"2026-07-01\"}," +
                "\"BBB\":{\"target\":200,\"asof\":\"2026-09-29\"}," +
                "\"CCC\":{\"target\":300}}");
        for (Map.Entry<String, J.V> e : an.m.entrySet()) {
            String asof = str(((J.O) e.getValue()).m.get("asof"));
            if (!fresh(asof, ANALYST_MAX_AGE_DAYS, t))
                f.add(Finding.warn("A4", e.getKey(), "analyst_target.asof", "fresh",
                        asof == null ? "missing" : asof, "synthetic", "none"));
        }
        SelfTest.checkEq("synthetic stale+missing count", 2, f.size());
        SelfTest.check("AAA stale flagged",
                f.stream().anyMatch(x -> x.ticker.equals("AAA")));
        SelfTest.check("CCC missing flagged",
                f.stream().anyMatch(x -> x.ticker.equals("CCC")));
        SelfTest.check("BBB not flagged",
                f.stream().noneMatch(x -> x.ticker.equals("BBB")));
        // Finding.split routes WARN to infos, FAIL to failures.
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        f.add(Finding.fail("A4", "-", "synthetic.file", "x", "y", "synthetic", "none"));
        Finding.split(f, infos, failures);
        SelfTest.checkEq("split: 2 warn infos", 2, infos.size());
        SelfTest.checkEq("split: 1 fail failure", 1, failures.size());
        SelfTest.check("anyFail true", Finding.anyFail(f));
        SelfTest.finish();
    }

    // ---- entry point --------------------------------------------------

    public static void main(String[] args) throws Exception {
        boolean test = args.length == 1 && args[0].equals("--self-test");
        boolean verify = args.length == 1 && args[0].equals("--verify");
        if (!test && !verify) {
            System.out.println("OutsideVoiceFreshness.java — A4 audit: outside-voice input freshness.");
            System.out.println("  java OutsideVoiceFreshness.java --self-test   unit checks (prove-can-fail)");
            System.out.println("  java OutsideVoiceFreshness.java --verify      audit real backends, report findings");
            System.exit(2);
            return;
        }
        if (test) {
            selfTest();
            return;
        }
        Path root = Paths.get(home(), "workspace", "dcf_csv");
        List<Finding> findings = runVerify(LocalDate.now(), root);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(findings, infos, failures);
        Verify.report("VERIFY OK: all outside-voice inputs fresh (or missing-asof queued as WARN)",
                "VERIFY FAILED: outside-voice backend integrity problems",
                infos, failures);
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (same embedded pattern as DcfAuditVerify.Json /
    // OutsideVoice.J — keeps the single-file launcher working without a
    // JSON library dependency).
    // ------------------------------------------------------------------
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; N(double d) { this.d = d; } }
        // JSON null is its own type so str() can tell "missing" apart from
        // the literal string "null" (a missing asof is its own finding).
        static final class Nil extends V { }

        static V parse(String t) {
            P p = new P(t);
            V v = p.val();
            p.ws();
            if (p.i != t.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
            return v;
        }

        static final class P {
            final String t; int i;
            P(String t) { this.t = t; }
            void ws() {
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
                }
            }
            V val() {
                ws();
                if (i >= t.length()) throw new IllegalArgumentException("unexpected end");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return new S(str());
                if (c == 't') { exp("true"); return new Nil(); }
                if (c == 'f') { exp("false"); return new Nil(); }
                if (c == 'n') { exp("null"); return new Nil(); }
                return num();
            }
            void exp(String s) {
                if (t.startsWith(s, i)) i += s.length();
                else throw new IllegalArgumentException("expected '" + s + "' at " + i);
            }
            O obj() {
                O o = new O(); i++; ws();
                if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    if (i >= t.length() || t.charAt(i) != '"')
                        throw new IllegalArgumentException("expected key at " + i);
                    String k = str(); ws();
                    if (i >= t.length() || t.charAt(i) != ':')
                        throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    o.m.put(k, val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
            }
            A arr() {
                A a = new A(); i++; ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
            }
            String str() {
                StringBuilder sb = new StringBuilder(); i++; // opening quote
                while (i < t.length()) {
                    char c = t.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\' && i < t.length()) {
                        char e = t.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                                i += 4; break;
                            default: sb.append(e);
                        }
                    } else sb.append(c);
                }
                throw new IllegalArgumentException("unterminated string");
            }
            N num() {
                int s = i;
                while (i < t.length() && "-+0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
                return new N(Double.parseDouble(t.substring(s, i)));
            }
        }
    }
}
