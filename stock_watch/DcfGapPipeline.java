import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-command pipeline for the "dcf-gap-watch" cron job (weekdays 8:46 AM ET).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — market check (quiet exit when closed) → read the DCF
 *       backend + the price cache (READ-ONLY, never live prices) → compute
 *       margin of safety (dcf-price)/dcf per ticker, skipping zero/negative
 *       DCF values → dedup against dcf_gap_state.json → print newly-qualifying
 *       tickers as JSON. The agent delivers the alert message and THEN writes
 *       the returned {@code state_update} map to the state file.</li>
 *   <li>{@code --dry-run} — same but skips the market gate and writes nothing.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 * </ul>
 *
 * <p>Testing: set PIPELINE_STATE_DIR to a /tmp copy of the state files and
 * --run will read/write state there instead of the real goal hidden_files.
 * The DCF backend and price cache are always read from their real paths
 * (read-only inputs).
 */
public class DcfGapPipeline {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String SW = HOME + "/workspace/stock_watch";
    // The job def names ~/workspace/dcf_values.json; the live backend is
    // ~/workspace/dcf_csv/our_dcf.json. Prefer the def's path when present.
    static final String DCF_PRIMARY = HOME + "/workspace/dcf_values.json";
    static final String DCF_FALLBACK = HOME + "/workspace/dcf_csv/our_dcf.json";
    static final String CACHE = SW + "/price_cache.json";
    static final String JOB_ID = "dcf-gap-watch";
    static final String MARGIN_REQ = HOME + "/workspace/dcf_csv/margin_requirements.json";
    static final double WIDEN_TRIGGER = 0.10;   // re-alert if MoS widened 10pp+
    static final double WIDEN_EPS = 1e-9;       // float epsilon on the widening test
    static final double EXIT_HYSTERESIS = 0.02; // clear state only when mos < req - 0.02
    static final double MOS_SANITY_CEILING = 0.60; // MoS above this is "verify inputs", not a clean signal
    static final long STALE_PRICE_MILLIS = 36L * 3600_000; // banner prices older than ~1 trading day

    // Rahik's required margin of safety per ticker (margin_requirements.json).
    // Buy zone: (dcf - price) / dcf >= required. Absent file -> 0.20 for all.
    static final class MarginReq {
        final double def;
        final Map<String, Double> per;
        MarginReq(double def, Map<String, Double> per) { this.def = def; this.per = per; }
        double required(String t) { Double v = per.get(t); return v == null ? def : v; }
    }

    static MarginReq readMarginReq() {
        double def = 0.20;
        Map<String, Double> per = new LinkedHashMap<>();
        Path p = Paths.get(MARGIN_REQ);
        if (!Files.exists(p)) {
            // Missing file: warn loudly but keep the documented 0.20 default.
            System.err.println("WARNING (" + JOB_ID + "): margin file missing at " + MARGIN_REQ
                    + " — using default 0.20 for all tickers");
            return new MarginReq(def, per);
        }
        // Fail CLOSED (audit-13 finding 4): a corrupt margin file must never
        // silently reset custom margins to 0.20 — exit non-zero, loudly.
        Map<String, Object> doc;
        try {
            doc = asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            fail("margin file unreadable at " + MARGIN_REQ + ": " + e.getMessage());
            return null; // unreachable — fail() exits
        }
        if (doc == null) fail("margin file unreadable at " + MARGIN_REQ + ": not a JSON object");
        Object d = doc.get("default");
        if (d != null) {
            if (!(d instanceof Number n)) fail("margin file unreadable: 'default' is not a number");
            def = ((Number) d).doubleValue();
            if (!(def > 0 && def < 1))
                fail("margin file invalid: default " + def + " not in (0, 1) — expected a fraction like 0.20");
        }
        Object tickersObj = doc.get("tickers");
        if (tickersObj != null) {
            Map<String, Object> ts = asMap(tickersObj);
            if (ts == null) fail("margin file unreadable: 'tickers' is not a JSON object");
            for (Map.Entry<String, Object> e : ts.entrySet()) {
                if (!(e.getValue() instanceof Number))
                    fail("margin file unreadable: ticker '" + e.getKey() + "' margin is not a number");
                double v = ((Number) e.getValue()).doubleValue();
                if (!(v > 0 && v < 1))
                    fail("margin file invalid: ticker '" + e.getKey() + "' margin " + v
                            + " not in (0, 1) — expected a fraction like 0.30");
                per.put(e.getKey(), v);
            }
        }
        return new MarginReq(def, per);
    }

    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return HOME + "/workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files";
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) return jh + "/bin/java";
        // Fall back to the known Temurin install (TOOLS.md) instead of bare
        // "java": cron bodies that launch this pipeline by full path do not
        // always export JAVA_HOME, and a bare-"java" child failure was
        // misread as "market closed" (false quiet exit, 2026-09-30).
        return homeDir() + "/workspace/java/jdk-21/bin/java";
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--run": run(false); break;
            case "--dry-run": run(true); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java DcfGapPipeline.java --run | --dry-run | --verify");
                System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    static void run(boolean dryRun) throws Exception {
        long runStart = System.currentTimeMillis();

        // 1. Market check — quiet exit when closed (dry-run skips the gate).
        if (!dryRun) {
            ExecResult cal = exec(List.of(javaBin(), SW + "/MarketCalendar.java", "--is-open"), 60_000);
            if (cal.exit != 0) {
                writeLastRun(runStart, "quiet_exit", "market closed (" + firstLine(cal.stdout + cal.stderr) + ")",
                        null, null, Map.of(), List.of(), Map.of());
                System.out.println(Json.dumps(Map.of("status", "quiet_exit", "job", JOB_ID,
                        "reason", "market closed")));
                return;
            }
        }

        // 2. Read the DCF backend (fresh each run). Both shapes are accepted:
        //    {"values": {T: {"value": v}}} and {"values": {T: v}}.
        DcfBackend dcf = readDcfBackend();
        if (dcf.values.isEmpty()) fail("no DCF values found in " + dcf.file);

        // 2b. Rahik's required margin of safety per ticker (default 20%).
        MarginReq mreq = readMarginReq();

        // 3. Read market prices from the cache ONLY, via the PriceCache helper
        //    (read-only). This job must never fetch live prices.
        List<String> tickers = new ArrayList<>(dcf.values.keySet());
        PriceCacheOut pc = readPriceCache(tickers);

        // 4. Compute margin of safety per ticker: (dcf - price) / dcf.
        //    Skip zero/negative DCF (a gap is meaningless there) and tickers
        //    with no cached price (never invent one).
        Map<String, Map<String, Object>> computed = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> zoneStatus = new LinkedHashMap<>(); // three-state display: YES / no / n-a (reason)
        List<String> warnings = new ArrayList<>();
        // Unknown ticker keys in the margin file: warn loudly — a typo would
        // otherwise leave the ticker at the default without anyone knowing.
        for (String k : mreq.per.keySet())
            if (!dcf.values.containsKey(k))
                warnings.add("margin_requirements.json has unknown ticker '" + k + "' — ignored");
        for (String t : tickers) {
            double d = dcf.values.get(t);
            double req = mreq.required(t);
            if (d <= 0) { skipped.add(t + " (dcf<=0)"); zoneStatus.put(t, "n/a (dcf<=0)"); continue; }
            CachePrice cp = pc.prices.get(t);
            if (cp == null) { skipped.add(t + " (no cached price)"); zoneStatus.put(t, "n/a (no price)"); continue; }
            if (!(cp.price > 0)) { skipped.add(t + " (bad price<=0)"); zoneStatus.put(t, "n/a (bad price)"); continue; }
            double mos = (d - cp.price) / d;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", t);
            row.put("price", canon(cp.price));          // digits copied exactly
            row.put("price_source", cp.source);
            row.put("price_fetched_at", cp.fetchedAt);
            row.put("dcf", canon(d));                   // digits copied exactly
            row.put("dcf_source", dcf.note);
            row.put("mos", fmt4(mos));                  // exact 4-decimal form (display only)
            row.put("mos_raw", mos);                    // raw double — comparisons use this, never the string
            row.put("buy_below", canon(d * (1 - req)));  // pipeline-emitted; --verify recomputes it
            row.put("required_mos", fmt4(req));
            if (mos > MOS_SANITY_CEILING)
                warnings.add(t + ": MoS " + fmt4(mos * 100) + "% exceeds sanity ceiling "
                        + fmt4(MOS_SANITY_CEILING * 100) + "% — verify inputs, not a clean buy signal");
            long priceAge = priceAgeMillis(cp.fetchedAt);
            if (priceAge >= 0 && priceAge > STALE_PRICE_MILLIS)
                warnings.add(t + ": cached price is stale (fetched_at " + cp.fetchedAt
                        + ") — bannered, never silently re-fetched");
            zoneStatus.put(t, mos >= req ? "YES" : "no");
            computed.put(t, row);
        }

        // 5. Dedup: alert only on a fresh required-margin crossing, or a
        //    10pp+ widening since the last alert for that ticker.
        Map<String, Double> state = readGapState();
        List<Map<String, Object>> newly = new ArrayList<>();
        Map<String, Object> stateUpdate = new LinkedHashMap<>();
        // Numbers, not strings: readGapState() only picks up JSON numbers, so
        // string values would be invisible to the next run's dedup (causing
        // duplicate alerts). Round to 4 decimals for stable digits.
        for (Map.Entry<String, Double> e : state.entrySet())
            stateUpdate.put(e.getKey(), Math.round(e.getValue() * 10000.0) / 10000.0);
        List<String> stateCleared = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : computed.entrySet()) {
            // Raw-double comparisons (audit-13): the 4-decimal display string is
            // for output only — parsing it back introduced a ±0.005pp window at
            // the boundary. mos_raw is the exact computed value.
            double mos = (Double) e.getValue().get("mos_raw");
            double req = mreq.required(e.getKey());
            if (mos < req - EXIT_HYSTERESIS) {
                // Ticker left the zone (with hysteresis against boundary
                // flapping): DELETE its state entry so a later re-entry counts
                // as a fresh crossing. The old `continue` kept stale state and
                // silently suppressed re-entry alerts (audit-13 finding 1).
                if (stateUpdate.remove(e.getKey()) != null)
                    stateCleared.add(e.getKey());
                continue;
            }
            if (mos < req) continue; // hysteresis band: no alert, keep state
            Double last = state.get(e.getKey());
            boolean freshCross = last == null || last < req;
            boolean widened = last != null && (mos - last) >= WIDEN_TRIGGER - WIDEN_EPS;
            if (freshCross || widened) {
                Map<String, Object> nq = new LinkedHashMap<>(e.getValue());
                nq.put("mos_pct", fmt4(mos * 100));
                nq.put("required_mos_pct", fmt4(req * 100));
                newly.add(nq);
                stateUpdate.put(e.getKey(), Math.round(mos * 10000.0) / 10000.0);
            }
        }
        // Provenance for the numeric state: which DCF backend and which price
        // cache session these MoS values were computed from. readGapState()
        // ignores non-numeric entries, so this never affects dedup.
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("dcf_backend", dcf.file);
        prov.put("dcf_note", dcf.note);
        prov.put("margin_requirements", MARGIN_REQ);
        prov.put("price_cache_asof", pc.asof);
        prov.put("computed_et", etNow());
        stateUpdate.put("_provenance", prov);

        // Dry-run never writes: no state changes at all.
        if (!dryRun)
            writeLastRun(runStart, "ok", null, dcf.file, pc.asof, computed, newly, state);

        // Coverage line + heartbeat (audit-13 NOW #5): every run states what it
        // monitored and what it skipped; quiet runs still heartbeat so "no
        // opportunities" is distinguishable from a dead feed.
        String coverage = "Monitored " + computed.size() + "/" + tickers.size()
                + ". Skipped: " + (skipped.isEmpty() ? "none" : String.join("; ", skipped));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run" : "ok");
        out.put("job", JOB_ID);
        out.put("dcf_backend", dcf.file);
        out.put("cache_asof", pc.asof);   // label stale prices "as of <asof>", never re-fetch
        out.put("tickers_computed", computed.size());
        out.put("skipped", skipped);
        out.put("coverage", coverage);
        out.put("zone_status", zoneStatus); // YES / no / n-a (reason) per ticker
        out.put("warnings", warnings);
        out.put("state_cleared", stateCleared); // tickers whose zone exit cleared dedup state
        out.put("newly_qualifying", newly);
        boolean heartbeat = newly.isEmpty();
        out.put("heartbeat", heartbeat);
        if (heartbeat)
            out.put("heartbeat_msg", "dcf-gap-watch heartbeat: no new qualifiers this run. " + coverage);
        // The agent delivers the alert message FIRST, then writes this map to
        // dcf_gap_state.json — so an alert is never recorded without delivery.
        out.put("state_update", stateUpdate);
        System.out.println(Json.dumps(out));
    }

    static class DcfBackend {
        String file = "";
        String note = "";
        Map<String, Double> values = new LinkedHashMap<>();
    }

    static DcfBackend readDcfBackend() throws IOException {
        DcfBackend d = new DcfBackend();
        String path = Files.exists(Paths.get(DCF_PRIMARY)) ? DCF_PRIMARY : DCF_FALLBACK;
        d.file = path;
        Map<String, Object> doc = asMap(Json.parse(readFile(path)));
        d.note = str(doc.get("note"));
        if (d.note.isBlank()) d.note = "dcf backend " + str(doc.get("asof"));
        Map<String, Object> values = asMap(doc.get("values"));
        if (values == null) values = doc; // tolerate a flat {T: {...}} shape
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            Object v = e.getValue();
            Double num = null;
            if (v instanceof Number n) num = n.doubleValue();
            else {
                Map<String, Object> m = asMap(v);
                if (m != null && m.get("value") instanceof Number n2) num = n2.doubleValue();
            }
            if (num != null) d.values.put(e.getKey(), num);
        }
        return d;
    }

    static class CachePrice {
        double price;
        String fetchedAt;
        String source;
    }

    static class PriceCacheOut {
        String asof = "";
        Map<String, CachePrice> prices = new LinkedHashMap<>();
    }

    /** Read-only price read through the PriceCache helper: TICKER|price|fetched_at|source. */
    static PriceCacheOut readPriceCache(List<String> tickers) {
        PriceCacheOut out = new PriceCacheOut();
        ExecResult r = exec(List.of(javaBin(), SW + "/PriceCache.java", "--get", String.join(",", tickers)), 60_000);
        if (r.exit != 0) fail("PriceCache --get failed: " + firstLine(r.stderr));
        for (String line : r.stdout.split("\n")) {
            line = line.strip();
            if (line.startsWith("# asof:")) {
                // "# asof: 2026-09-26 21:50 EDT | written_date: 2026-09-26 | tickers: 31"
                out.asof = line.substring("# asof:".length()).split("\\|")[0].strip();
            } else if (line.contains("|") && !line.startsWith("#")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 2 && !"MISSING".equals(parts[1])) {
                    try {
                        CachePrice cp = new CachePrice();
                        cp.price = Double.parseDouble(parts[1]);
                        cp.fetchedAt = parts.length > 2 ? parts[2] : "";
                        cp.source = parts.length > 3 ? parts[3] : "";
                        out.prices.put(parts[0], cp);
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return out;
    }

    /** ticker -> last alerted margin-of-safety (fraction). Missing file = no alerts yet. */
    static Map<String, Double> readGapState() {
        Map<String, Double> state = new LinkedHashMap<>();
        Path p = Paths.get(stateDir(), "dcf_gap_state.json");
        if (!Files.exists(p)) return state;
        try {
            Map<String, Object> doc = asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
            if (doc == null) return state;
            for (Map.Entry<String, Object> e : doc.entrySet())
                if (e.getValue() instanceof Number n) state.put(e.getKey(), n.doubleValue());
        } catch (Exception ignored) { }
        return state;
    }

    static void writeLastRun(long runStart, String status, String reason, String dcfFile, String cacheAsof,
                             Map<String, Map<String, Object>> computed,
                             List<Map<String, Object>> newly, Map<String, Double> state) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("job", JOB_ID);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", etNow());
        rec.put("status", status);
        if (reason != null) rec.put("reason", reason);
        if (dcfFile != null) rec.put("dcf_backend", dcfFile);
        if (cacheAsof != null) rec.put("cache_asof", cacheAsof);
        rec.put("computed", computed);   // ticker -> {price, dcf, mos, ...} in exact digit form
        rec.put("newly_qualifying", newly);
        rec.put("cache_mtime_ms", fileMtime(CACHE));
        atomicWrite(Paths.get(stateDir(), "dcf_gap_last_run.json").toString(), Json.dumps(rec));
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        // Shared last-run check (see Verify.checkLastRun): without a record
        // there is nothing to recompute against — NOT_INITIALIZED is INFO
        // and passes; REGRESSION fails loud.
        Verify.LastRunState lrs = Verify.checkLastRun(JOB_ID,
                Paths.get(stateDir(), "dcf_gap_last_run.json"),
                HOME + "/workspace/scheduler_state/completed_runs.json", infos, problems);
        if (lrs != Verify.LastRunState.PRESENT) {
            if (!problems.isEmpty())
                failVerify(JOB_ID, problems, null);
            for (String info : infos) System.out.println("INFO: " + info);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "ok");
            ok.put("job", JOB_ID);
            ok.put("detail", "not yet initialized — first scheduled run pending");
            System.out.println(Json.dumps(ok));
            return;
        }
        Map<String, Object> rec = readLastRun();
        if (rec == null) {
            problems.add("last-run record at " + stateDir() + "/dcf_gap_last_run.json"
                    + " exists but does not parse — --run did not write a valid record");
            failVerify(JOB_ID, problems, null);
        }
        boolean quiet = "quiet_exit".equals(str(rec.get("status")));
        Map<String, Object> computed = null;

        if (!quiet) {
        // 1. Recompute MoS independently from the raw backends and compare
        //    digits EXACTLY against the last run's record (numbers-copied rule).
        DcfBackend dcf = readDcfBackend();
        List<String> tickers = new ArrayList<>(dcf.values.keySet());
        PriceCacheOut pc = readPriceCache(tickers);
        MarginReq vmreq = readMarginReq(); // fail-closed too — buy_below needs the margin
        computed = asMap(rec.get("computed"));
        if (computed == null) {
            problems.add("last-run record has no computed map");
        } else {
            for (Map.Entry<String, Object> e : computed.entrySet()) {
                String t = e.getKey();
                Map<String, Object> row = asMap(e.getValue());
                Double dv = dcf.values.get(t);
                CachePrice cp = pc.prices.get(t);
                if (dv == null || dv <= 0 || cp == null || row == null) {
                    problems.add("cannot recompute " + t + " (backend/cache changed since the run?)");
                    continue;
                }
                String expectPrice = canon(cp.price);
                String expectDcf = canon(dv);
                String expectMos = fmt4((dv - cp.price) / dv);
                if (!expectPrice.equals(str(row.get("price"))))
                    problems.add(t + ": price digits differ — record '" + row.get("price")
                            + "' vs recomputed '" + expectPrice + "'");
                if (!expectDcf.equals(str(row.get("dcf"))))
                    problems.add(t + ": dcf digits differ — record '" + row.get("dcf")
                            + "' vs recomputed '" + expectDcf + "'");
                if (!expectMos.equals(str(row.get("mos"))))
                    problems.add(t + ": mos digits differ — record '" + row.get("mos")
                            + "' vs recomputed '" + expectMos + "'");
                // buy_below: pipeline-emitted, recomputed here independently from
                // the raw backends (audit-13 NOW #2). Uses the run's recorded
                // required_mos so a later margin change can't false-positive.
                double vreq = vmreq.required(t);
                Object rmo = row.get("required_mos");
                if (rmo != null) {
                    try { vreq = Double.parseDouble(str(rmo)); }
                    catch (NumberFormatException ignored) { }
                }
                String expectBuyBelow = canon(dv * (1 - vreq));
                Object bb = row.get("buy_below");
                if (bb == null)
                    problems.add(t + ": no buy_below in record (predates pipeline emission"
                            + " — rerun --run to refresh)");
                else if (!expectBuyBelow.equals(str(bb)))
                    problems.add(t + ": buy_below digits differ — record '" + bb
                            + "' vs recomputed '" + expectBuyBelow + "'");
                // Provenance on every figure used.
                if (str(row.get("price_source")).isBlank() || str(row.get("price_fetched_at")).isBlank())
                    problems.add(t + ": price missing source/fetched_at provenance");
            }
        }
        } // end if (!quiet)

        // 2. State file must be valid JSON (an object). When the last run
        //    newly qualified tickers, the agent must have written the
        //    pipeline's state_update map AFTER delivering the alert — verify
        //    each alerted ticker is present with the alerted MoS digits, and
        //    that _provenance names the DCF backend and price-cache session.
        Path statePath = Paths.get(stateDir(), "dcf_gap_state.json");
        if (Files.exists(statePath)) {
            try {
                Object s = Json.parse(Files.readString(statePath, StandardCharsets.UTF_8));
                if (!(s instanceof Map)) {
                    problems.add("dcf_gap_state.json is not a JSON object");
                } else {
                    Map<String, Object> stateDoc = asMap(s);
                    List<?> newly = rec.get("newly_qualifying") instanceof List
                            ? (List<?>) rec.get("newly_qualifying") : List.of();
                    for (Object o : newly) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> nq = asMap(o);
                        String t = str(nq.get("ticker"));
                        Object sv = stateDoc.get(t);
                        if (!(sv instanceof Number))
                            problems.add("dcf_gap_state.json missing alerted ticker " + t
                                    + " — the agent did not write state_update after delivery");
                        else {
                            double expect = Double.parseDouble(str(nq.get("mos")));
                            if (Math.abs(((Number) sv).doubleValue() - expect) > 0.00005)
                                problems.add(t + ": state MoS " + sv
                                        + " differs from alerted MoS " + nq.get("mos"));
                        }
                    }
                    boolean hasTickers = stateDoc.keySet().stream()
                            .anyMatch(k -> !k.startsWith("_"));
                    Object prov = stateDoc.get("_provenance");
                    if (hasTickers) {
                        if (!(prov instanceof Map)) {
                            problems.add("dcf_gap_state.json has ticker entries but no"
                                    + " _provenance object");
                        } else {
                            Map<String, Object> pm = asMap(prov);
                            if (str(pm.get("dcf_backend")).isBlank()
                                    || str(pm.get("price_cache_asof")).isBlank())
                                problems.add("dcf_gap_state.json _provenance is missing"
                                        + " dcf_backend or price_cache_asof");
                        }
                    }
                }
            } catch (Exception ex) {
                problems.add("dcf_gap_state.json is not valid JSON: " + ex.getMessage());
            }
        } else if (!quiet) {
            List<?> newly = rec.get("newly_qualifying") instanceof List
                    ? (List<?>) rec.get("newly_qualifying") : List.of();
            if (!newly.isEmpty())
                problems.add("last run newly qualified " + newly.size()
                        + " ticker(s) but dcf_gap_state.json was never written —"
                        + " the agent did not write state_update after delivery");
        }

        // 3. The cache must NOT have been refreshed by this run — its mtime
        //    must predate the run start (stale caches are labeled "as of",
        //    never silently re-fetched). Applies to quiet exits too.
        long runStart = num(rec.get("run_start_epoch_ms"));
        long cacheMtime = fileMtime(CACHE);
        if (cacheMtime >= runStart)
            problems.add("price_cache.json was rewritten at/after this run's start —"
                    + " this job must never refresh the cache");

        if (!problems.isEmpty())
            failVerify(JOB_ID, problems, str(rec.get("run_start_et")));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("job", JOB_ID);
        if (quiet) ok.put("note", "quiet exit: " + str(rec.get("reason")));
        ok.put("tickers_recomputed", quiet ? 0 : (computed == null ? 0 : computed.size()));
        ok.put("digits_match", true);
        System.out.println(Json.dumps(ok));
    }

    static Map<String, Object> readLastRun() {
        try {
            return asMap(Json.parse(readFile(Paths.get(stateDir(), "dcf_gap_last_run.json").toString())));
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Small utilities
    // =====================================================================

    /** Canonical digit form of a double, so recompute comparisons are exact. */
    static String canon(double d) {
        return Double.toString(d);
    }

    static String fmt4(double d) {
        return String.format(Locale.US, "%.4f", d);
    }

    static class ExecResult {
        int exit = -1;
        String stdout = "";
        String stderr = "";
        boolean timedOut = false;
    }

    static ExecResult exec(List<String> cmd, long timeoutMs) {
        ExecResult r = new ExecResult();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> { try { p.getInputStream().transferTo(out); } catch (IOException ignored) { } });
            t1.start();
            Thread t2 = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (IOException ignored) { } });
            t2.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            t1.join(5000);
            t2.join(5000);
            if (!done) {
                p.destroyForcibly();
                r.timedOut = true;
                return r;
            }
            r.exit = p.exitValue();
            r.stdout = out.toString(StandardCharsets.UTF_8);
            r.stderr = err.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.stderr = e.toString();
        }
        return r;
    }

    static String readFile(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    static void atomicWrite(String path, String content) throws IOException {
        Path target = Paths.get(path);
        Files.createDirectories(target.getParent());
        Path tmp = Paths.get(path + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static long fileMtime(String path) {
        try {
            return Files.getLastModifiedTime(Paths.get(path)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    static String etNow() {
        return ZonedDateTime.now(ET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Millis since a price-cache fetched_at timestamp ("yyyy-MM-dd HH:mm ..."
     * prefix, e.g. "2026-10-09 07:30 EDT"). Returns -1 when unparseable, in
     * which case the caller treats the price age as unknown (no banner).
     */
    static long priceAgeMillis(String fetchedAt) {
        if (fetchedAt == null) return -1;
        String s = fetchedAt.strip();
        if (s.length() < 16) return -1;
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(s.substring(0, 16),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return System.currentTimeMillis() - ldt.atZone(ET).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static void fail(String msg) {
        System.err.println("PIPELINE FAILED (" + JOB_ID + "): " + msg);
        System.out.println(Json.dumps(Map.of("status", "failed", "job", JOB_ID, "error", msg)));
        System.exit(1);
    }

    /**
     * --verify failure path: fail LOUDLY and record the failure in the shared
     * outcome log (the task requires verification failures to be loud AND
     * recorded). PIPELINE_OUTCOME_DIR overrides the log directory in tests;
     * production uses ~/workspace/scheduler_state/job_outcomes. (Never Java
     * user.home — it resolves to /root in this sandbox; $HOME is the real home.)
     */
    static void failVerify(String jobId, List<String> problems, String runStartEt) {
        String dir = System.getenv("PIPELINE_OUTCOME_DIR");
        if (dir == null || dir.isEmpty())
            dir = System.getenv("HOME") + "/workspace/scheduler_state/job_outcomes";
        try {
            Files.createDirectories(Paths.get(dir));
            String stamp;
            if (runStartEt != null && runStartEt.length() >= 16) {
                stamp = runStartEt.substring(0, 10) + " " + runStartEt.substring(11, 16) + " ET";
            } else {
                stamp = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " ET";
            }
            String line = stamp + " | " + jobId + " | " + stamp.substring(0, 10) + " | "
                    + stamp.substring(11, 16) + " ET"
                    + " | outcome=failed | delivered=no | notes=verify failed: " + problems.get(0) + "\n";
            Files.writeString(Paths.get(dir, jobId + ".log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("WARNING: could not append to outcome log: " + e.getMessage());
        }
        System.err.println("VERIFY FAILED (" + jobId + "):");
        for (String p : problems) System.err.println("  - " + p);
        System.exit(1);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : List.of();
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o)); } catch (NumberFormatException e) { return -1; }
    }

    /* =====================================================================
     * Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java DcfGapPipeline.java` (single-file launch cannot link other
     * files — the same pattern BatchQuotes.java and PriceCache.java use).
     * ===================================================================== */
    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            if (j.pos != j.s.length()) throw new IllegalArgumentException("trailing characters after JSON document");
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected '" + lit + "'");
            pos += lit.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string");
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: b.append(e); break;
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at position " + start);
            }
        }

        static String dumps(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(String.valueOf(e.getKey()))).append(":").append(dumps(e.getValue()));
                }
                return b.append("}").toString();
            }
            if (v instanceof List<?> l) {
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                for (Object e : l) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(e));
                }
                return b.append("]").toString();
            }
            return dumps(String.valueOf(v));
        }
    }
}
