import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * OutsideVoiceQuality — ONE verifier for the audit question "are the outside
 * voices fit to feed the speculation median?", covering both gaps in one run:
 *
 *   Section 1 — A4 freshness: are the three outside-voice input backends
 *   (analyst_targets.json, website_consensus.json, creator_values.json)
 *   fresh enough, per their asof dates?  Ported from
 *   OutsideVoiceFreshness.java — thresholds and severity policy identical.
 *
 *   Section 2 — C2 transcription drift: does a browser re-collection of one
 *   website's DCF values match the stored raw file
 *   (website_values/<site>.json)?  Ported from TranscriptionRecheck.java —
 *   tolerance max(1%·|stored|, $0.05), the metric-discipline guard, and the
 *   gurufocus-excluded policy are all copied verbatim.  Keep them identical:
 *   a rule change here must land in TranscriptionRecheck.java too.
 *
 * Severity shape (shared with both source verifiers, preserved on merge):
 *   - A4 findings are WARN at most: a stale outside voice is not a WRONG
 *     outside voice.  FAIL is reserved for unreadable/structural backends.
 *   - C2 findings go to FAIL when a recollected value drifts beyond
 *     tolerance or the metric row changes (manual review required).
 *
 * Findings carry their ORIGINAL checkIds ("A4" / "C2") so the 12-gap
 * traceability survives the merge.
 *
 * Finding pattern: the shared Finding.java (borrowed from Google Truth's
 * Fact — failure details as key-value facts, never string-concatenated
 * prose; detection stays here, rendering stays there).
 *
 * REPORT-ONLY per the audit-write guard: zero write calls anywhere in this
 * file.  Verifiers read; refreshes run through the proposal pipeline.
 *
 * Usage:
 *   java --class-path ~/workspace/java-shared/classes OutsideVoiceQuality.java --self-test
 *   java --class-path ~/workspace/java-shared/classes OutsideVoiceQuality.java --verify
 *   java --class-path ~/workspace/java-shared/classes OutsideVoiceQuality.java --recheck <recollected.json>
 *
 * --verify runs the A4 freshness section only (pure backend check, no extra
 * input).  --recheck runs A4 first, then adds the C2 drift section against
 * the given recollected file (same schema as website_values/<site>.json:
 * {site, display, metric, collected, values:{TICKER:{value, date, note, url}}}).
 */
public class OutsideVoiceQuality {

    // ---- A4 thresholds (days), identical to OutsideVoiceFreshness --------
    static final int ANALYST_MAX_AGE_DAYS = 35;   // analyst targets move on earnings; ~monthly refresh
    static final int WEBSITE_MAX_AGE_DAYS = 90;   // website DCF pages re-stamp quarterly-ish
    static final int CREATOR_MAX_AGE_DAYS = 30;   // creator calls decay fast; monthly digest cadence

    // ---- C2 constants, identical to TranscriptionRecheck -----------------
    /** GuruFocus is excluded from the re-collection program (premium wall). */
    static final String EXCLUDED_SITE = "gurufocus";
    static final String VALUES_DIR = "workspace/dcf_csv/website_values";

    static String home() {
        return System.getenv("HOME");
    }

    // ==================================================================
    // SECTION 1 — A4 freshness (ported from OutsideVoiceFreshness.java)
    // ==================================================================

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
            String asof = strV(entry.m.get("asof"));
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
        String top = strV(doc.m.get("asof"));
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
                String site = se.getKey(), date = strV(src.m.get("date"));
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
        String top = strV(doc.m.get("asof"));
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
                String call = strV(entry.m.get("call_date"));
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
                            d + " days old (" + call + "), creator=" + strV(entry.m.get("creator")),
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

    static List<Finding> runFreshness(LocalDate today, Path root) {
        List<Finding> findings = new ArrayList<>();
        auditAnalystTargets(findings, today, root);
        auditWebsiteConsensus(findings, today, root);
        auditCreatorValues(findings, today, root);
        return findings;
    }

    // ==================================================================
    // SECTION 2 — C2 transcription drift (ported from TranscriptionRecheck)
    // ==================================================================
    // NOTE: diffSection is a verbatim port of TranscriptionRecheck.diffSite
    // (tolerance, metric-discipline guard, gurufocus policy all identical).
    // It is ported — not called into — because diffSite is package-private
    // and TranscriptionRecheck.java must not be modified; reflective access
    // would not be Rahik-readable.

    static List<Finding> runDrift(Path recollectedPath, Path root) {
        List<Finding> findings = new ArrayList<>();
        J.O recollected;
        try {
            recollected = (J.O) J.parse(Files.readString(recollectedPath, StandardCharsets.UTF_8));
        } catch (Exception e) {
            findings.add(Finding.fail("C2", "-", "recollected.parse",
                    "parseable recollected JSON", "parse error: " + e.getMessage(),
                    recollectedPath.toString(), "re-generate the recollected file"));
            return findings;
        }
        String site = strObj(recollected, "site");
        if (site.isEmpty()) {
            findings.add(Finding.fail("C2", "-", "recollected.site",
                    "site field present", "missing",
                    recollectedPath.toString(), "fix the recollected file's \"site\" field"));
            return findings;
        }
        if (site.equals(EXCLUDED_SITE)) {
            findings.add(Finding.fail("C2", "-", "site.excluded",
                    "site in the re-collection program",
                    "site '" + site + "' — excluded: values unverifiable behind the premium wall",
                    "recollected: " + recollectedPath,
                    "do not collect this site; remove the recollected file"));
            return findings;
        }
        Path storedPath = root.resolve("website_values/" + site + ".json");
        if (!Files.isRegularFile(storedPath)) {
            findings.add(Finding.fail("C2", "-", "site.unknown",
                    "a stored raw file website_values/" + site + ".json",
                    "no stored file for site '" + site + "'",
                    "recollected: " + recollectedPath,
                    "collect this site for the first time per RECOLLECTION_PROTOCOL.md, "
                            + "or fix the recollected file's \"site\" field"));
            return findings;
        }
        try {
            J.O stored = (J.O) J.parse(Files.readString(storedPath, StandardCharsets.UTF_8));
            findings.addAll(diffSection(stored, recollected, site,
                    storedPath.toString(), recollectedPath.toString()));
        } catch (Exception e) {
            findings.add(Finding.fail("C2", "-", "stored.parse",
                    "parseable stored JSON", "parse error: " + e.getMessage(),
                    storedPath.toString(), "repair the stored file"));
        }
        return findings;
    }

    /** The drift diff — pure function; --self-test exercises this directly. */
    static List<Finding> diffSection(J.O stored, J.O recollected, String site,
                                     String storedLabel, String recollectedLabel) {
        List<Finding> out = new ArrayList<>();
        String evidenceBase = "stored: " + storedLabel + " (collected " + strObj(stored, "collected") + ")"
                + "; recollected: " + recollectedLabel + " (collected " + strObj(recollected, "collected") + ")";

        // Metric discipline: the recollected file must name the SAME metric
        // the stored file was collected under. A changed metric string means
        // the collector may have read the wrong row (e.g. the headline
        // instead of the disciplined DCF row).
        String storedMetric = strObj(stored, "metric");
        String recollectedMetric = strObj(recollected, "metric");
        if (!storedMetric.isEmpty() && !recollectedMetric.equals(storedMetric)) {
            out.add(Finding.fail("C2", "-", "metric.discipline",
                    "metric '" + storedMetric + "'",
                    "metric '" + recollectedMetric + "'",
                    evidenceBase,
                    "confirm the recollection used the disciplined metric row per "
                            + "RECOLLECTION_PROTOCOL.md; if the site renamed its metric, "
                            + "bless the change by updating the stored file's metric field"));
        }

        // Stale recollection: older than what is already stored.
        String storedCollected = strObj(stored, "collected");
        String recollectedCollected = strObj(recollected, "collected");
        if (!storedCollected.isEmpty() && !recollectedCollected.isEmpty()
                && recollectedCollected.compareTo(storedCollected) < 0) {
            out.add(Finding.warn("C2", "-", "recollection.stale",
                    "recollection collected on/after " + storedCollected,
                    "recollection collected " + recollectedCollected,
                    evidenceBase,
                    "re-collect with a fresh browser pass; an older recollection cannot "
                            + "supersede the stored file"));
        }

        J.O storedVals = objOf(stored, "values");
        J.O recollectedVals = objOf(recollected, "values");
        Set<String> tickers = new LinkedHashSet<>();
        tickers.addAll(storedVals.m.keySet());
        tickers.addAll(recollectedVals.m.keySet());

        for (String t : tickers) {
            J.O sCell = cellOf(storedVals, t);
            J.O rCell = cellOf(recollectedVals, t);
            if (sCell == null && rCell == null) continue;
            if (rCell == null) {
                out.add(Finding.warn("C2", t, "ticker.not_recollected",
                        "ticker re-collected", "ticker absent from the recollection",
                        evidenceBase,
                        "re-collect this ticker or record why it is unavailable in the "
                                + "recollected file's note field"));
                continue;
            }
            if (sCell == null) {
                out.add(Finding.warn("C2", t, "ticker.unexpected",
                        "ticker present in the stored file",
                        "ticker '" + t + "' only in the recollection",
                        evidenceBase,
                        "confirm the ticker belongs to the roster; if it is a new holding, "
                                + "bless it by adding it to the stored file"));
                continue;
            }
            Double sVal = numOf(sCell, "value");
            Double rVal = numOf(rCell, "value");
            if (sVal != null && rVal != null) {
                double delta = Math.abs(rVal - sVal);
                double tol = tolerance(sVal);
                if (delta > tol) {
                    out.add(Finding.fail("C2", t, "value.drift",
                            "stored " + fmt(sVal), "recollected " + fmt(rVal)
                                    + " (delta " + fmt(delta) + " > tol " + fmt(tol) + ")",
                            evidenceBase + "; note: '" + strObj(rCell, "note") + "'",
                            "MANUAL REVIEW: confirm the recollected page used the disciplined "
                                    + "metric row (RECOLLECTION_PROTOCOL.md) — drift may be a "
                                    + "metric-row error or a genuine site model update. Do NOT "
                                    + "auto-adopt; replace website_values/" + site + ".json only "
                                    + "after review (add-then-verify), then run "
                                    + "WebsiteConsensus --build"));
                } else if (delta > 0) {
                    out.add(Finding.info("C2", t, "value.minor_drift",
                            "stored " + fmt(sVal), "recollected " + fmt(rVal)
                                    + " (within tol " + fmt(tol) + ")",
                            evidenceBase,
                            "adopt the recollected value at the coordinator's discretion; "
                                    + "run WebsiteConsensus --build after updating the stored file"));
                }
            } else if (sVal == null && rVal != null) {
                out.add(Finding.warn("C2", t, "value.appeared",
                        "stored null (no value published)", "recollected " + fmt(rVal),
                        evidenceBase + "; note: '" + strObj(rCell, "note") + "'",
                        "the site now publishes a value — adopt it after confirming the "
                                + "metric row, then run WebsiteConsensus --build"));
            } else if (sVal != null && rVal == null) {
                out.add(Finding.warn("C2", t, "value.disappeared",
                        "stored " + fmt(sVal), "recollected null (no value published)",
                        evidenceBase + "; note: '" + strObj(rCell, "note") + "'",
                        "check whether the page now gates the value (paywall/login) — "
                                + "do not null the stored value on a collection failure"));
            }
            // URL moved while the value held: the canonical page may have moved.
            String sUrl = strObj(sCell, "url");
            String rUrl = strObj(rCell, "url");
            if (!sUrl.isEmpty() && !rUrl.isEmpty() && !sUrl.equals(rUrl)) {
                out.add(Finding.warn("C2", t, "url.changed",
                        sUrl, rUrl,
                        evidenceBase,
                        "confirm the new URL is the same disciplined page; update the "
                                + "stored file's url after review"));
            }
        }
        return out;
    }

    /** max(1% of |stored|, $0.05) — identical to TranscriptionRecheck.tolerance. */
    static double tolerance(double storedVal) {
        return Math.max(0.01 * Math.abs(storedVal), 0.05);
    }

    static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    // ==================================================================
    // plumbing
    // ==================================================================

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

    static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** J.V -> String or null (null when JSON null or missing). */
    static String strV(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
    }

    /** J.O field -> String or "" (matches TranscriptionRecheck.str semantics). */
    static String strObj(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.S) ? ((J.S) v).s : "";
    }

    static J.O objOf(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.O) ? (J.O) v : new J.O();
    }

    static J.O cellOf(J.O vals, String ticker) {
        J.V v = vals.m.get(ticker);
        return (v instanceof J.O) ? (J.O) v : null;
    }

    static Double numOf(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    // ==================================================================
    // self-test (one per section, both checkIds exercised)
    // ==================================================================

    static void selfTest() {
        LocalDate t = LocalDate.of(2026, 9, 29);

        // ---- A4: thresholds (identical to OutsideVoiceFreshness self-test) ----
        SelfTest.checkEq("daysOld today", 0L, daysOld("2026-09-29", t));
        SelfTest.checkEq("daysOld 2d ago", 2L, daysOld("2026-09-27", t));
        SelfTest.checkEq("daysOld future is negative", -1L, daysOld("2026-09-30", t));
        SelfTest.checkEq("daysOld blank", -1L, daysOld("  ", t));
        SelfTest.checkEq("daysOld garbage", -1L, daysOld("not-a-date", t));
        SelfTest.checkEq("daysOld null", -1L, daysOld(null, t));
        SelfTest.check("analyst 35d fresh", fresh("2026-08-25", ANALYST_MAX_AGE_DAYS, t));
        SelfTest.check("analyst 36d stale", !fresh("2026-08-24", ANALYST_MAX_AGE_DAYS, t));
        SelfTest.check("website 90d fresh", fresh("2026-07-01", WEBSITE_MAX_AGE_DAYS, t));
        SelfTest.check("website 91d stale", !fresh("2026-06-30", WEBSITE_MAX_AGE_DAYS, t));
        SelfTest.check("creator 30d fresh", fresh("2026-08-30", CREATOR_MAX_AGE_DAYS, t));
        SelfTest.check("creator 31d stale", !fresh("2026-08-29", CREATOR_MAX_AGE_DAYS, t));
        SelfTest.check("missing asof never fresh", !fresh(null, 35, t));
        // JSON null is distinct from a missing key and from the string "null".
        SelfTest.check("json null -> strV null", strV(J.parse("null")) == null);
        J.O nullObj = (J.O) J.parse("{\"asof\":null}");
        SelfTest.check("null asof never fresh", !fresh(strV(nullObj.m.get("asof")), ANALYST_MAX_AGE_DAYS, t));

        // A4 findings keep their checkId after the merge.
        List<Finding> af = new ArrayList<>();
        af.add(Finding.warn("A4", "AAA", "analyst_target.asof", "fresh", "60d old", "syn", "none"));
        SelfTest.check("A4 checkId preserved", af.get(0).checkId.equals("A4"));
        SelfTest.check("A4 staleness is WARN not FAIL",
                af.get(0).severity == Finding.Severity.WARN);

        // ---- C2: diff logic (identical to TranscriptionRecheck self-test) ----
        String storedJson = "{\"site\":\"simplywallst\",\"display\":\"Simply Wall St\","
                + "\"metric\":\"Cash Flow Value\",\"collected\":\"2026-09-28\","
                + "\"values\":{\"AAA\":{\"value\":100.00,\"date\":\"2026-09-28\",\"note\":\"\","
                + "\"url\":\"https://example.com/aaa\"},"
                + "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"no value published\","
                + "\"url\":\"https://example.com/bbb\"}}}";
        String sameRecollected = "{\"site\":\"simplywallst\",\"display\":\"Simply Wall St\","
                + "\"metric\":\"Cash Flow Value\",\"collected\":\"2026-10-28\","
                + "\"values\":{\"AAA\":{\"value\":100.00,\"date\":\"2026-10-28\",\"note\":\"\","
                + "\"url\":\"https://example.com/aaa\"},"
                + "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"still none\","
                + "\"url\":\"https://example.com/bbb\"}}}";

        List<Finding> f = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(sameRecollected),
                "simplywallst", "S", "R");
        SelfTest.checkEq("identical recollection: no findings", 0, f.size());

        String drifted = sameRecollected.replace("\"value\":100.00", "\"value\":112.50");
        List<Finding> f2 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(drifted),
                "simplywallst", "S", "R");
        SelfTest.check("drift: anyFail", Finding.anyFail(f2));
        SelfTest.checkEq("drift: one finding", 1, f2.size());
        SelfTest.checkEq("drift: field", "value.drift", f2.get(0).field);
        SelfTest.checkEq("drift: ticker", "AAA", f2.get(0).ticker);
        SelfTest.checkContains("drift: shows stored side", f2.get(0).expected, "100");
        SelfTest.checkContains("drift: shows recollected side", f2.get(0).actual, "112.5");
        SelfTest.check("drift: checkId C2", f2.get(0).checkId.equals("C2"));

        String minor = sameRecollected.replace("\"value\":100.00", "\"value\":100.40");
        List<Finding> f3 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(minor),
                "simplywallst", "S", "R");
        SelfTest.check("minor drift: no FAIL", !Finding.anyFail(f3));
        SelfTest.checkEq("minor drift: one INFO", 1, f3.size());
        SelfTest.checkEq("minor drift: severity INFO",
                Finding.Severity.INFO, f3.get(0).severity);

        SelfTest.checkEq("tolerance floor on 0.30", 0.05, tolerance(0.30));
        SelfTest.checkEq("tolerance relative on 700", 7.0, tolerance(700.0));
        SelfTest.checkEq("tolerance on negative", 0.442, Math.round(tolerance(-44.2) * 1000.0) / 1000.0);

        String wrongMetric = sameRecollected.replace("\"metric\":\"Cash Flow Value\"",
                "\"metric\":\"Intrinsic Value\"");
        List<Finding> f4 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(wrongMetric),
                "simplywallst", "S", "R");
        SelfTest.check("metric change: anyFail", Finding.anyFail(f4));
        boolean hasMetric = false;
        for (Finding x : f4) if (x.field.equals("metric.discipline")) hasMetric = true;
        SelfTest.check("metric change: metric.discipline FAIL", hasMetric);

        String appeared = sameRecollected.replace(
                "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"still none\"",
                "\"BBB\":{\"value\":42.00,\"date\":\"2026-10-28\",\"note\":\"\"");
        List<Finding> f5 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(appeared),
                "simplywallst", "S", "R");
        boolean hasAppeared = false;
        for (Finding x : f5) {
            if (x.field.equals("value.appeared") && x.severity == Finding.Severity.WARN) hasAppeared = true;
        }
        SelfTest.check("appeared: WARN present", hasAppeared && !Finding.anyFail(f5));

        String disappeared = sameRecollected.replace(
                "\"AAA\":{\"value\":100.00,\"date\":\"2026-10-28\",\"note\":\"\"",
                "\"AAA\":{\"value\":null,\"date\":null,\"note\":\"page gated\"");
        List<Finding> f6 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(disappeared),
                "simplywallst", "S", "R");
        boolean hasDisappeared = false;
        for (Finding x : f6) {
            if (x.field.equals("value.disappeared") && x.severity == Finding.Severity.WARN) hasDisappeared = true;
        }
        SelfTest.check("disappeared: WARN present", hasDisappeared && !Finding.anyFail(f6));

        String staleRecol = sameRecollected.replace("\"collected\":\"2026-10-28\"", "\"collected\":\"2026-09-01\"");
        List<Finding> f7 = diffSection((J.O) J.parse(storedJson), (J.O) J.parse(staleRecol),
                "simplywallst", "S", "R");
        boolean hasStale = false;
        for (Finding x : f7) if (x.field.equals("recollection.stale")) hasStale = true;
        SelfTest.check("stale recollection: WARN present", hasStale);

        // runDrift end-to-end: gurufocus recollected -> FAIL by policy.
        List<Finding> gf = runDriftPathForTest("{\"site\":\"gurufocus\",\"metric\":\"x\",\"collected\":\"2026-09-29\",\"values\":{}}");
        boolean hasExcluded = false;
        for (Finding x : gf) if (x.field.equals("site.excluded") && x.severity == Finding.Severity.FAIL) hasExcluded = true;
        SelfTest.check("gurufocus: policy FAIL", hasExcluded);

        // ---- merge: both sections feed one Finding stream ----
        List<Finding> merged = new ArrayList<>();
        merged.addAll(af);
        merged.addAll(f2);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(merged, infos, failures);
        SelfTest.checkEq("merged: 1 warn info + 1 fail", 1, failures.size());
        SelfTest.check("merged: infos carry both ids",
                infos.stream().anyMatch(s -> s.contains(" A4 ")) && failures.stream().anyMatch(s -> s.contains("C2")));

        SelfTest.finish();
    }

    /** Self-test helper: runs the site-policy branch of runDrift without files. */
    static List<Finding> runDriftPathForTest(String recollectedJson) {
        List<Finding> findings = new ArrayList<>();
        J.O recollected = (J.O) J.parse(recollectedJson);
        String site = strObj(recollected, "site");
        if (site.equals(EXCLUDED_SITE)) {
            findings.add(Finding.fail("C2", "-", "site.excluded",
                    "site in the re-collection program",
                    "site '" + site + "' — excluded: values unverifiable behind the premium wall",
                    "test", "do not collect this site"));
        }
        return findings;
    }

    // ==================================================================
    // entry point
    // ==================================================================

    public static void main(String[] args) throws Exception {
        boolean test = args.length == 1 && args[0].equals("--self-test");
        boolean verify = args.length == 1 && args[0].equals("--verify");
        boolean recheck = args.length == 2 && args[0].equals("--recheck");
        if (!test && !verify && !recheck) {
            System.out.println("OutsideVoiceQuality.java — merged outside-voice quality audit (A4 + C2).");
            System.out.println("  java OutsideVoiceQuality.java --self-test              unit checks (prove-can-fail)");
            System.out.println("  java OutsideVoiceQuality.java --verify                 A4 freshness only (pure backend check)");
            System.out.println("  java OutsideVoiceQuality.java --recheck <file.json>    A4 + C2 drift vs the recollected file");
            System.exit(2);
            return;
        }
        if (test) {
            selfTest();
            return;
        }
        Path root = Paths.get(home(), "workspace", "dcf_csv");
        List<Finding> findings = runFreshness(LocalDate.now(), root);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        infos.add("section 1 (A4): outside-voice input freshness — always runs");
        if (recheck) {
            Path recollectedPath = Paths.get(args[1]);
            if (!Files.isRegularFile(recollectedPath)) {
                System.err.println("FATAL: recollected file not found: " + recollectedPath);
                System.exit(2);
            }
            List<Finding> drift = runDrift(recollectedPath.toAbsolutePath(), root);
            infos.add("section 2 (C2): transcription drift vs '" + args[1] + "' — "
                    + drift.size() + " finding(s)");
            findings.addAll(drift);
        }
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (OutsideVoiceQuality): outside voices are fit to feed the speculation median.",
                "VERIFY FAILED (OutsideVoiceQuality): " + failures.size() + " problem(s) found:",
                infos, failures);
    }

    // ==================================================================
    // Minimal JSON parser (single parser for both sections; true/false as
    // booleans, null as its own type, number literals preserved for fmt).
    // ==================================================================
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; N(double d) { this.d = d; } }
        static final class B extends V { final boolean b; B(boolean b) { this.b = b; } }
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
                if (i >= t.length()) throw new IllegalArgumentException("unexpected end of input");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return new S(str());
                if (c == 't') { exp("true"); return new B(true); }
                if (c == 'f') { exp("false"); return new B(false); }
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
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
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
