// AlertGuards.java
// Split/dividend guard + volatility-adjusted alert thresholds for the
// drop-alert pipeline. Standalone single-file launch, like the other
// stock_watch tools (DropAlertPipeline shells out to it).
//
// Modes:
//   --refresh-thresholds
//       For every watchlist ticker, fetch ~45 calendar days of daily
//       closes (Yahoo chart API via batch_quotes.py.bak --history-events)
//       and compute the per-ticker drop-alert threshold from 20-day
//       realized volatility. Writes alert_thresholds.json atomically.
//   --check TICKER --session-date=YYYY-MM-DD --drop-pct=-8.2
//       Corporate-action guard: was this drop mechanically caused by a
//       stock split or ex-dividend date? Prints
//       {"suppressed":bool,"kind":"split"|"dividend"|null,"detail":"..."}.
//       Only suppresses when the observed drop matches the expected
//       mechanical drop; a genuine crash on an ex-div day still alerts.
//   --backtest --days=60
//       Walk-forward simulation over the last N trading days: flat -6%
//       rule vs volatility-adjusted thresholds + corporate-action guard.
//       Prints old/new alert counts and every day the two rules disagree.
//
// The volatility formula (documented for Rahik in
// goals/meta-nflx-sofi-5-drop-alerts/files/alert-thresholds.md):
//   sigma  = sample std-dev of daily log returns over the last 20 trading
//            days (split-adjusted closes, so splits don't inflate it)
//   threshold = clamp(6.0 * (sigma / 1.5), 4.0, 12.0), in percent
// 1.5% daily vol is the anchor: a typical large-cap day. 6% is ~4 sigma.
// Volatile names (sigma 3%) get 12% (fewer false alarms); calm names
// (sigma 0.75%) get 4% (a 5% drop on a calm stock is genuinely unusual).
// Fewer than 15 usable closes -> fall back to flat 6% and record it.

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class AlertGuards {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String SW = HOME + "/workspace/stock_watch";
    static final String WATCHLIST = SW + "/watchlist.json";
    static final String THRESHOLDS = SW + "/alert_thresholds.json";
    static final String PY = SW + "/batch_quotes.py.bak";

    // ---- Volatility formula constants (the documented formula) ----
    static final double BASELINE_PCT = 6.0;   // anchor: the old flat threshold
    static final double ANCHOR_DAILY_VOL_PCT = 1.5; // typical large-cap daily vol
    static final double MIN_THRESHOLD_PCT = 4.0;
    static final double MAX_THRESHOLD_PCT = 12.0;
    static final int VOL_LOOKBACK_TRADING_DAYS = 20;
    static final int MIN_CLOSES_FOR_VOL = 15;
    static final int HISTORY_CALENDAR_DAYS = 45; // comfortably covers 20 trading days

    public static void main(String[] args) throws Exception {
        String mode = null, ticker = null, sessionDate = null, dropPct = null;
        int days = 60;
        for (String a : args) {
            if (a.equals("--refresh-thresholds") || a.equals("--check") || a.equals("--backtest")) mode = a;
            else if (a.startsWith("--session-date=")) sessionDate = a.substring(15);
            else if (a.startsWith("--drop-pct=")) dropPct = a.substring(11);
            else if (a.startsWith("--days=")) days = Integer.parseInt(a.substring(7));
            else if (!a.startsWith("--")) ticker = a;
        }
        switch (mode == null ? "" : mode) {
            case "--refresh-thresholds":
                System.out.println(Json.dumps(refreshThresholds()));
                break;
            case "--check":
                if (ticker == null || sessionDate == null || dropPct == null) {
                    System.err.println("Usage: java AlertGuards.java --check TICKER --session-date=YYYY-MM-DD --drop-pct=-8.2");
                    System.exit(2);
                }
                System.out.println(Json.dumps(checkCorporateAction(ticker,
                        normalizeDate(sessionDate), Double.parseDouble(dropPct))));
                break;
            case "--backtest":
                System.out.println(Json.dumps(backtest(days)));
                break;
            default:
                System.err.println("Usage: java AlertGuards.java --refresh-thresholds | --check TICKER --session-date=YYYY-MM-DD --drop-pct=PCT | --backtest [--days=N]");
                System.exit(2);
        }
    }

    // =====================================================================
    // --refresh-thresholds
    // =====================================================================

    static Map<String, Object> refreshThresholds() throws Exception {
        List<String> tickers = readTickers();
        Map<String, Object> perTicker = new LinkedHashMap<>();
        List<String> fallbacks = new ArrayList<>();
        for (String t : tickers) {
            Map<String, Object> rec = new LinkedHashMap<>();
            try {
                History h = fetchHistory(t, HISTORY_CALENDAR_DAYS);
                List<Double> closes = ascendingAdjCloses(h, VOL_LOOKBACK_TRADING_DAYS);
                if (closes.size() >= MIN_CLOSES_FOR_VOL) {
                    double sigma = sampleStd(logReturns(closes)) * 100.0; // in percent
                    double thr = clamp(BASELINE_PCT * (sigma / ANCHOR_DAILY_VOL_PCT),
                            MIN_THRESHOLD_PCT, MAX_THRESHOLD_PCT);
                    thr = Math.round(thr * 100.0) / 100.0;
                    rec.put("threshold_pct", thr);
                    rec.put("daily_vol_pct", Math.round(sigma * 100.0) / 100.0);
                    rec.put("closes_used", closes.size());
                } else {
                    rec.put("threshold_pct", BASELINE_PCT);
                    rec.put("daily_vol_pct", null);
                    rec.put("closes_used", closes.size());
                    rec.put("fallback", "fewer than " + MIN_CLOSES_FOR_VOL + " closes");
                    fallbacks.add(t);
                }
            } catch (Exception e) {
                rec.put("threshold_pct", BASELINE_PCT);
                rec.put("fallback", "history fetch failed: " + firstLine(e.getMessage()));
                fallbacks.add(t);
            }
            perTicker.put(t, rec);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("asof", LocalDate.now(ET).format(DateTimeFormatter.ISO_DATE));
        doc.put("computed_at_et", nowEt());
        doc.put("baseline_pct", BASELINE_PCT);
        doc.put("anchor_daily_vol_pct", ANCHOR_DAILY_VOL_PCT);
        doc.put("min_pct", MIN_THRESHOLD_PCT);
        doc.put("max_pct", MAX_THRESHOLD_PCT);
        doc.put("lookback_trading_days", VOL_LOOKBACK_TRADING_DAYS);
        doc.put("formula", "threshold = clamp(6.0 * (sigma_20d / 1.5), 4.0, 12.0)");
        doc.put("thresholds", perTicker);
        doc.put("fallbacks", fallbacks);
        atomicWrite(THRESHOLDS, Json.dumps(doc));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("file", THRESHOLDS);
        out.put("tickers", tickers.size());
        out.put("fallbacks", fallbacks);
        return out;
    }

    /** Read the thresholds file; missing entries -> flat 6.0 baseline. */
    static Map<String, Double> loadThresholds() {
        Map<String, Double> m = new LinkedHashMap<>();
        try {
            Map<String, Object> doc = asMap(Json.parse(readFile(THRESHOLDS)));
            Map<String, Object> th = asMap(doc.get("thresholds"));
            if (th == null) return m;
            for (Map.Entry<String, Object> e : th.entrySet()) {
                Map<String, Object> rec = asMap(e.getValue());
                if (rec != null && rec.get("threshold_pct") instanceof Number)
                    m.put(e.getKey(), ((Number) rec.get("threshold_pct")).doubleValue());
            }
        } catch (Exception ignored) { }
        return m;
    }

    static String thresholdsAsof() {
        try {
            return String.valueOf(asMap(Json.parse(readFile(THRESHOLDS))).get("asof"));
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    // --check : split/dividend guard
    // =====================================================================

    /**
     * Returns {suppressed, kind, detail}. Suppresses only when the observed
     * drop matches the expected mechanical drop from a split or dividend
     * whose event date is within 3 calendar days before/at the session date.
     * dropPct is negative (e.g. -8.2).
     */
    static Map<String, Object> checkCorporateAction(String ticker, String sessionDateIso, double dropPct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suppressed", false);
        out.put("kind", null);
        out.put("detail", "");
        double dropMag = -dropPct; // positive magnitude
        try {
            // Anchor the history window on the session date: a corporate
            // action only explains the drop if its event is near that date.
            History h = fetchHistory(ticker, LocalDate.parse(sessionDateIso), 12);
            LocalDate session = LocalDate.parse(sessionDateIso);
            // --- splits ---
            for (Map<String, Object> s : h.splits) {
                LocalDate ev = LocalDate.parse(str(s.get("date")));
                if (ev.isAfter(session) || ev.isBefore(session.minusDays(3))) continue;
                double num = num(s.get("numerator")), den = num(s.get("denominator"));
                if (num <= 0 || den <= 0) continue;
                double expected = (1.0 - den / num) * 100.0; // e.g. 2:1 -> 50%
                if (Math.abs(dropMag - expected) <= 5.0) {
                    out.put("suppressed", true);
                    out.put("kind", "split");
                    out.put("detail", ticker + " " + str(s.get("ratio")) + " split on " + ev
                            + ": observed " + fmtPct(dropPct) + " matches expected mechanical drop of "
                            + fmtPct(-expected));
                    return out;
                }
            }
            // --- dividends ---
            for (Map<String, Object> d : h.dividends) {
                LocalDate ev = LocalDate.parse(str(d.get("date")));
                if (ev.isAfter(session) || ev.isBefore(session.minusDays(3))) continue;
                double amount = num(d.get("amount"));
                Double prevClose = closeBefore(h.close, ev);
                if (amount <= 0 || prevClose == null || prevClose <= 0) continue;
                double expected = amount / prevClose * 100.0;
                if (Math.abs(dropMag - expected) <= 1.0) {
                    out.put("suppressed", true);
                    out.put("kind", "dividend");
                    out.put("detail", ticker + " ex-dividend $" + amount + " on " + ev
                            + ": observed " + fmtPct(dropPct) + " matches expected mechanical drop of "
                            + fmtPct(-expected));
                    return out;
                }
            }
            out.put("detail", "no split/dividend event explains " + fmtPct(dropPct) + " on " + sessionDateIso);
        } catch (Exception e) {
            // Guard must never block a real alert when data is unavailable:
            // fail open (do not suppress), and say so.
            out.put("detail", "corporate-action check unavailable (" + firstLine(e.getMessage())
                    + ") — not suppressed");
        }
        return out;
    }

    /** Offline variant used by --backtest (reuses one prefetched History). */
    static Map<String, Object> checkCorporateActionOffline(History h, String sessionDateIso, double dropPct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suppressed", false);
        out.put("kind", null);
        out.put("detail", "");
        double dropMag = -dropPct;
        LocalDate session = LocalDate.parse(sessionDateIso);
        for (Map<String, Object> s : h.splits) {
            LocalDate ev = LocalDate.parse(str(s.get("date")));
            if (ev.isAfter(session) || ev.isBefore(session.minusDays(3))) continue;
            double num = num(s.get("numerator")), den = num(s.get("denominator"));
            if (num <= 0 || den <= 0) continue;
            double expected = (1.0 - den / num) * 100.0;
            if (Math.abs(dropMag - expected) <= 5.0) {
                out.put("suppressed", true);
                out.put("kind", "split");
                out.put("detail", str(s.get("ratio")) + " split on " + ev);
                return out;
            }
        }
        for (Map<String, Object> d : h.dividends) {
            LocalDate ev = LocalDate.parse(str(d.get("date")));
            if (ev.isAfter(session) || ev.isBefore(session.minusDays(3))) continue;
            double amount = num(d.get("amount"));
            Double prevClose = closeBefore(h.close, ev);
            if (amount <= 0 || prevClose == null || prevClose <= 0) continue;
            double expected = amount / prevClose * 100.0;
            if (Math.abs(dropMag - expected) <= 1.0) {
                out.put("suppressed", true);
                out.put("kind", "dividend");
                out.put("detail", "ex-div $" + amount + " on " + ev);
                return out;
            }
        }
        return out;
    }

    /** Latest close strictly before the given date. */
    static Double closeBefore(Map<String, Double> closes, LocalDate date) {
        LocalDate best = null;
        for (String k : closes.keySet()) {
            LocalDate d = LocalDate.parse(k);
            if (d.isBefore(date) && (best == null || d.isAfter(best))) best = d;
        }
        return best == null ? null : closes.get(best.format(DateTimeFormatter.ISO_DATE));
    }

    // =====================================================================
    // --backtest : walk-forward simulation, old rule vs new rule
    // =====================================================================

    static Map<String, Object> backtest(int days) throws Exception {
        List<String> tickers = readTickers();
        int oldAlerts = 0, newAlerts = 0, suppressedSplit = 0, suppressedDiv = 0;
        List<Map<String, Object>> disagreements = new ArrayList<>();
        Map<String, Object> perTicker = new LinkedHashMap<>();
        for (String t : tickers) {
            History h;
            try {
                h = fetchHistory(t, days + 60);
            } catch (Exception e) {
                perTicker.put(t, Map.of("skipped", "history fetch failed"));
                continue;
            }
            List<String> tradingDays = new ArrayList<>(h.close.keySet());
            Collections.sort(tradingDays);
            int tOld = 0, tNew = 0, tSupS = 0, tSupD = 0;
            // Walk forward over the last `days` trading days that have a
            // previous day (for the daily change) and 20 prior days (for vol).
            for (int i = Math.max(1, tradingDays.size() - days); i < tradingDays.size(); i++) {
                String d = tradingDays.get(i);
                String prev = tradingDays.get(i - 1);
                double c = h.close.get(d), p = h.close.get(prev);
                if (p <= 0) continue;
                double dayChange = (c - p) / p * 100.0;
                boolean oldHit = dayChange <= -BASELINE_PCT;
                // Walk-forward vol: 20 trading days strictly before d,
                // split-adjusted closes so splits don't inflate sigma.
                List<Double> win = new ArrayList<>();
                for (int j = i - VOL_LOOKBACK_TRADING_DAYS; j < i && j >= 0; j++) {
                    Double px = h.adjclose.get(tradingDays.get(j));
                    if (px != null && px > 0) win.add(px);
                }
                double thr = BASELINE_PCT;
                if (win.size() >= MIN_CLOSES_FOR_VOL) {
                    double sigma = sampleStd(logReturns(win)) * 100.0;
                    thr = Math.round(clamp(BASELINE_PCT * (sigma / ANCHOR_DAILY_VOL_PCT),
                            MIN_THRESHOLD_PCT, MAX_THRESHOLD_PCT) * 100.0) / 100.0;
                }
                boolean newHit = dayChange <= -thr;
                String suppressedKind = null;
                if (newHit) {
                    Map<String, Object> chk = checkCorporateActionOffline(h, d, dayChange);
                    if (Boolean.TRUE.equals(chk.get("suppressed"))) {
                        suppressedKind = str(chk.get("kind"));
                        newHit = false;
                        if ("split".equals(suppressedKind)) { suppressedSplit++; tSupS++; }
                        else { suppressedDiv++; tSupD++; }
                    }
                }
                if (oldHit) { oldAlerts++; tOld++; }
                if (newHit) { newAlerts++; tNew++; }
                if (oldHit != newHit) {
                    Map<String, Object> dg = new LinkedHashMap<>();
                    dg.put("ticker", t);
                    dg.put("date", d);
                    dg.put("day_change_pct", Math.round(dayChange * 100.0) / 100.0);
                    dg.put("old_rule_alert", oldHit);
                    dg.put("new_rule_alert", newHit);
                    dg.put("threshold_pct", thr);
                    if (suppressedKind != null) dg.put("suppressed_as", suppressedKind);
                    disagreements.add(dg);
                }
            }
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("old_rule_alerts", tOld);
            tr.put("new_rule_alerts", tNew);
            tr.put("suppressed_split", tSupS);
            tr.put("suppressed_dividend", tSupD);
            perTicker.put(t, tr);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("trading_days_simulated", days);
        out.put("note", "walk-forward: each day's threshold uses only prior 20 trading days; "
                + "daily change is close-to-close (production uses intraday change_pct vs prior close)");
        out.put("old_rule_total_alerts", oldAlerts);
        out.put("new_rule_total_alerts", newAlerts);
        out.put("suppressed_as_split", suppressedSplit);
        out.put("suppressed_as_dividend", suppressedDiv);
        out.put("disagreements", disagreements);
        out.put("per_ticker", perTicker);
        return out;
    }

    // =====================================================================
    // History fetch (delegated to Python: Java HTTPS is unreliable here,
    // same reason HistoricalPrice.java delegates to batch_quotes.py.bak)
    // =====================================================================

    static class History {
        Map<String, Double> close = new TreeMap<>();    // raw daily closes
        Map<String, Double> adjclose = new TreeMap<>(); // split-adjusted closes
        List<Map<String, Object>> splits = new ArrayList<>();
        List<Map<String, Object>> dividends = new ArrayList<>();
    }

    static History fetchHistory(String ticker, int calendarDays) throws Exception {
        return fetchHistory(ticker, LocalDate.now(ET), calendarDays);
    }

    static History fetchHistory(String ticker, LocalDate end, int calendarDays) throws Exception {
        LocalDate to = end;
        LocalDate from = to.minusDays(calendarDays);
        DateTimeFormatter compact = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<String> cmd = List.of("python3", PY,
                "--history-events=" + ticker + "," + from.format(compact) + "," + to.format(compact));
        ExecResult r = exec(cmd, 60_000);
        if (r.timedOut) throw new IOException("history fetch timed out for " + ticker);
        if (r.exit != 0) throw new IOException("history fetch exit=" + r.exit + " for " + ticker);
        Map<String, Object> doc = asMap(Json.parse(r.stdout));
        if (doc == null || !Boolean.TRUE.equals(doc.get("ok")))
            throw new IOException("history fetch not ok for " + ticker);
        History h = new History();
        for (Map.Entry<String, Object> e : asMap(doc.get("close")).entrySet())
            if (e.getValue() instanceof Number) h.close.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        Map<String, Object> adj = asMap(doc.get("adjclose"));
        if (adj != null)
            for (Map.Entry<String, Object> e : adj.entrySet())
                if (e.getValue() instanceof Number) h.adjclose.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        if (h.adjclose.isEmpty()) h.adjclose.putAll(h.close); // fall back to raw
        for (Object s : asList(doc.get("splits"))) { Map<String, Object> m = asMap(s); if (m != null) h.splits.add(m); }
        for (Object d : asList(doc.get("dividends"))) { Map<String, Object> m = asMap(d); if (m != null) h.dividends.add(m); }
        if (h.close.isEmpty()) throw new IOException("no closes returned for " + ticker);
        return h;
    }

    /** Last N trading days of split-adjusted closes, oldest first. */
    static List<Double> ascendingAdjCloses(History h, int n) {
        List<String> days = new ArrayList<>(h.adjclose.keySet());
        Collections.sort(days);
        List<Double> out = new ArrayList<>();
        for (int i = Math.max(0, days.size() - n); i < days.size(); i++) out.add(h.adjclose.get(days.get(i)));
        return out;
    }

    // =====================================================================
    // Math + small utilities
    // =====================================================================

    static List<Double> logReturns(List<Double> closes) {
        List<Double> r = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double p = closes.get(i - 1), c = closes.get(i);
            if (p > 0 && c > 0) r.add(Math.log(c / p));
        }
        return r;
    }

    static double sampleStd(List<Double> xs) {
        if (xs.size() < 2) return 0.0;
        double mean = 0;
        for (double x : xs) mean += x;
        mean /= xs.size();
        double s2 = 0;
        for (double x : xs) s2 += (x - mean) * (x - mean);
        return Math.sqrt(s2 / (xs.size() - 1));
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Accept ISO (2026-09-25) or the quote's MM/dd/yy form (09/25/26 ...). */
    static String normalizeDate(String s) {
        s = s.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2})/(\\d{1,2})/(\\d{2})").matcher(s);
        if (m.find()) {
            return "20" + m.group(3) + "-" + String.format("%02d", Integer.parseInt(m.group(1)))
                    + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
        }
        return LocalDate.now(ET).format(DateTimeFormatter.ISO_DATE); // fail open to today
    }

    static String fmtPct(double pct) {
        return String.format("%.2f%%", pct);
    }

    static List<String> readTickers() throws IOException {
        Map<String, Object> wl = asMap(Json.parse(readFile(WATCHLIST)));
        List<String> tickers = new ArrayList<>();
        for (Object t : asList(wl.get("tickers"))) tickers.add(String.valueOf(t));
        if (tickers.isEmpty()) throw new IllegalStateException("watchlist.json has no tickers");
        return tickers;
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

    static String nowEt() {
        return java.time.ZonedDateTime.now(ET)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
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
            if (!done) { p.destroyForcibly(); r.timedOut = true; return r; }
            r.exit = p.exitValue();
            r.stdout = out.toString(StandardCharsets.UTF_8);
            r.stderr = err.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.stderr = e.toString();
        }
        return r;
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

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(str(o)); } catch (NumberFormatException e) { return 0; }
    }

    /* Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java AlertGuards.java` (single-file launch cannot link other files —
     * the same pattern BatchQuotes.java and DropAlertPipeline.java use). */
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
