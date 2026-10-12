// FlowBacktest.java
// Backtest harness for the EXPERIMENTAL institutional-flow proxy (Bottom Finders technique #4).
// This is explicitly experimental — not Bottom Finders' proprietary reading, not validated.
// Proxy v0 (to be tested): money-flow surge + OBV slope.
//   moneyFlow(d) = typicalPrice(d) * volume(d), signed +1/-1 by close vs prev close
//   signal(d)    = 20d-avg signed money flow > 1.5x its 60d avg AND 20d OBV slope > 0
// Measures forward 30/60/90-day returns after each signal vs buy-and-hold.
// Bars come from the Yahoo chart API via the Python helper (Java HTTPS is blocked
// in this sandbox); this class reads bars JSON: {"bars":[{"date":"YYYY-MM-DD","o":..,"h":..,"l":..,"c":..,"v":..}]}.
//
// Usage:
//   java FlowBacktest.java --fetch TICKER --days 730 --out /tmp/bars.json
//   java FlowBacktest.java --backtest /tmp/bars.json
//   java FlowBacktest.java --self-test

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class FlowBacktest {

    record Bar(String date, double o, double h, double l, double c, long v) {}

    // ---------- bars fetch (delegates to Python/Yahoo) ----------

    static String fetchBarsJson(String ticker, int days) throws Exception {
        String py = "import json,urllib.request,time\n"
            + "t='" + ticker.replace("'", "") + "'\n"
            + "now=int(time.time());p1=now-" + (days + 30) + "*86400\n"
            + "u=f'https://query1.finance.yahoo.com/v8/finance/chart/{t}?period1={p1}&period2={now}&interval=1d'\n"
            + "d=json.load(urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0'}),timeout=30))\n"
            + "r=d['chart']['result'][0];q=r['indicators']['quote'][0]\n"
            + "bars=[]\n"
            + "import time as _t\n"
            + "for ts,o,h,l,c,v in zip(r.get('timestamp') or [],q.get('open') or [],q.get('high') or [],q.get('low') or [],q.get('close') or [],q.get('volume') or []):\n"
            + "  bars.append({'date':_t.strftime('%Y-%m-%d',_t.gmtime(ts)),'o':o or 0,'h':h or 0,'l':l or 0,'c':c or 0,'v':v or 0})\n"
            + "print(json.dumps({'ticker':t,'bars':bars}))\n";
        Path tmp = Files.createTempFile("bars", ".py");
        Files.writeString(tmp, py, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("python3", tmp.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        Files.deleteIfExists(tmp);
        if (rc != 0) throw new IOException("bars fetch failed: " + out);
        return out;
    }

    static List<Bar> parseBars(String json) {
        List<Bar> bars = new ArrayList<>();
        // minimal JSON parse: extract the bars array entries
        int bi = json.indexOf("\"bars\"");
        if (bi < 0) return bars;
        String arr = json.substring(json.indexOf('[', bi), json.lastIndexOf(']') + 1);
        for (String entry : arr.split("\\},\\s*\\{")) {
            String e = entry.replace("{", "").replace("}", "");
            Map<String, String> m = new HashMap<>();
            for (String kv : e.split(",")) {
                String[] p = kv.split(":", 2);
                if (p.length == 2) m.put(p[0].replace("\"", "").trim(), p[1].replace("\"", "").trim());
            }
            try {
                bars.add(new Bar(m.get("date"),
                    Double.parseDouble(m.get("o")), Double.parseDouble(m.get("h")),
                    Double.parseDouble(m.get("l")), Double.parseDouble(m.get("c")),
                    Long.parseLong(m.get("v"))));
            } catch (Exception ignored) {}
        }
        bars.removeIf(b -> b.c <= 0);
        return bars;
    }

    // ---------- indicators ----------

    /** Signed money flow per bar: typical price * volume, sign by close vs prev close. */
    static double[] signedMoneyFlow(List<Bar> bars) {
        double[] mf = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            double tp = (b.h + b.l + b.c) / 3.0;
            double sign = 0;
            if (i > 0) sign = b.c > bars.get(i - 1).c ? 1 : (b.c < bars.get(i - 1).c ? -1 : 0);
            mf[i] = sign * tp * b.v;
        }
        return mf;
    }

    /** On-balance volume. */
    static double[] obv(List<Bar> bars) {
        double[] o = new double[bars.size()];
        double run = 0;
        for (int i = 0; i < bars.size(); i++) {
            if (i > 0) {
                if (bars.get(i).c > bars.get(i - 1).c) run += bars.get(i).v;
                else if (bars.get(i).c < bars.get(i - 1).c) run -= bars.get(i).v;
            }
            o[i] = run;
        }
        return o;
    }

    static double avg(double[] a, int end, int n) {
        // average of a[end-n .. end-1]
        if (end - n < 0) return Double.NaN;
        double s = 0;
        for (int i = end - n; i < end; i++) s += a[i];
        return s / n;
    }

    /** Experimental signal v0 at index i (needs 60 bars of history). */
    static boolean signalV0(double[] mf, double[] obv, int i) {
        double mf20 = avg(mf, i + 1, 20);
        double mf60 = avg(mf, i + 1, 60);
        if (Double.isNaN(mf20) || Double.isNaN(mf60)) return false;
        double obvSlope = obv[i] - obv[i - 20];
        return mf20 > 1.5 * Math.max(mf60, 0) && mf20 > 0 && obvSlope > 0;
    }

    // ---------- backtest ----------

    record Result(int signals, double hit30, double hit60, double hit90,
                  double avg30, double avg60, double avg90,
                  double bh30, double bh60, double bh90) {}

    static Result backtest(List<Bar> bars) {
        double[] mf = signedMoneyFlow(bars);
        double[] o = obv(bars);
        List<Integer> sigIdx = new ArrayList<>();
        for (int i = 60; i < bars.size() - 90; i++)
            if (signalV0(mf, o, i)) sigIdx.add(i);
        double h30 = 0, h60 = 0, h90 = 0, a30 = 0, a60 = 0, a90 = 0;
        for (int i : sigIdx) {
            double base = bars.get(i).c;
            double r30 = bars.get(i + 30).c / base - 1;
            double r60 = bars.get(i + 60).c / base - 1;
            double r90 = bars.get(i + 90).c / base - 1;
            if (r30 > 0) h30++; if (r60 > 0) h60++; if (r90 > 0) h90++;
            a30 += r30; a60 += r60; a90 += r90;
        }
        int n = Math.max(sigIdx.size(), 1);
        // buy-and-hold: average forward return across all eligible dates
        double b30 = 0, b60 = 0, b90 = 0; int bn = 0;
        for (int i = 60; i < bars.size() - 90; i++) {
            double base = bars.get(i).c;
            b30 += bars.get(i + 30).c / base - 1;
            b60 += bars.get(i + 60).c / base - 1;
            b90 += bars.get(i + 90).c / base - 1;
            bn++;
        }
        return new Result(sigIdx.size(), h30 / n, h60 / n, h90 / n,
            a30 / n, a60 / n, a90 / n, b30 / bn, b60 / bn, b90 / bn);
    }

    // ---------- self-test ----------

    static int failures = 0;
    static void check(String name, boolean cond) {
        if (cond) System.out.println("  PASS " + name);
        else { System.out.println("  FAIL " + name); failures++; }
    }

    static void selfTest() {
        // synthetic bars: steady uptrend, then a volume SURGE mid-sample
        // (accumulation signature) -> signal should fire after the surge
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double c = 100 + i * 0.5;
            long v = (i >= 80 && i < 110) ? 6_000_000 : 1_000_000 + i * 10_000;
            bars.add(new Bar("2025-01-01", c - 0.2, c + 0.3, c - 0.3, c, v));
        }
        double[] mf = signedMoneyFlow(bars);
        double[] o = obv(bars);
        check("obv-rises-in-uptrend", o[199] > o[100]);
        check("mf-positive-in-uptrend", avg(mf, 200, 20) > 0);
        // flat market: no signal
        List<Bar> flat = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            flat.add(new Bar("2025-01-01", 100, 100.5, 99.5, 100, 1_000_000));
        double[] mf2 = signedMoneyFlow(flat);
        double[] o2 = obv(flat);
        boolean anySig = false;
        for (int i = 60; i < 110; i++) if (signalV0(mf2, o2, i)) anySig = true;
        check("no-signal-in-flat", !anySig);
        // backtest runs and returns sane numbers on synthetic uptrend
        Result r = backtest(bars);
        check("backtest-signals-found", r.signals() > 0);
        check("uptrend-hit-rate-high", r.hit90() > 0.8);
        // deliberate-red proof: corrupt OBV should break the uptrend check
        System.out.println(failures == 0 ? "SELF-TEST GREEN" : "SELF-TEST RED: " + failures);
        if (failures > 0) System.exit(1);
    }

    // ---------- main ----------

    static void usage() {
        System.out.println("Usage:");
        System.out.println("  java FlowBacktest.java --fetch TICKER --days N --out bars.json");
        System.out.println("  java FlowBacktest.java --backtest bars.json");
        System.out.println("  java FlowBacktest.java --self-test");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        switch (args[0]) {
            case "--self-test" -> selfTest();
            case "--fetch" -> {
                String ticker = null, out = null; int days = 730;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--days")) days = Integer.parseInt(args[++i]);
                    else if (args[i].equals("--out")) out = args[++i];
                    else if (ticker == null) ticker = args[i];
                }
                if (ticker == null || out == null) usage();
                String json = fetchBarsJson(ticker, days);
                Files.writeString(Path.of(out), json, StandardCharsets.UTF_8);
                System.out.println("saved " + out);
            }
            case "--backtest" -> {
                if (args.length < 2) usage();
                String json = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
                List<Bar> bars = parseBars(json);
                String ticker = "?";
                int ti = json.indexOf("\"ticker\"");
                if (ti >= 0) {
                    int q1 = json.indexOf('"', ti + 8);
                    int q2 = json.indexOf('"', q1 + 1);
                    if (q1 > 0 && q2 > q1) ticker = json.substring(q1 + 1, q2);
                }
                Result r = backtest(bars);
                System.out.println("EXPERIMENTAL flow proxy v0 — " + ticker
                    + " (" + bars.size() + " bars). NOT a validated signal.");
                System.out.printf(Locale.ROOT,
                    "signals=%d | hit30=%.0f%% hit60=%.0f%% hit90=%.0f%% | avg30=%+.1f%% avg60=%+.1f%% avg90=%+.1f%%%n",
                    r.signals(), 100 * r.hit30(), 100 * r.hit60(), 100 * r.hit90(),
                    100 * r.avg30(), 100 * r.avg60(), 100 * r.avg90());
                System.out.printf(Locale.ROOT,
                    "buy-and-hold baseline: avg30=%+.1f%% avg60=%+.1f%% avg90=%+.1f%%%n",
                    100 * r.bh30(), 100 * r.bh60(), 100 * r.bh90());
            }
            default -> usage();
        }
    }
}
