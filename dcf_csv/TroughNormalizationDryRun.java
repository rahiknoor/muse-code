import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TroughNormalizationDryRun — dry-run for proposal 010 (Claude audit-02
 * round-2 re-audit trough trigger).
 *
 * <p>For each ticker in workbook_data.json:
 * <ol>
 *   <li>Pulls 5-year FCF margin history from SEC companyfacts (via
 *       DcfHistory's Maps/Series machinery).
 *   <li>Calls DcfModel.troughNormalizedBase(revenue, margins, ttmMargin).
 *   <li>If non-null, recomputes the intrinsic value with the normalized base.
 * </ol>
 *
 * <p>DRY-RUN ONLY: reports which tickers WOULD be normalized, old base vs
 * new base, and valuation impact. Writes nothing.
 *
 * <p>Usage: java TroughNormalizationDryRun [--ticker T]
 */
public class TroughNormalizationDryRun {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return Path.of(e != null && !e.isEmpty() ? e : homeDir() + "/workspace");
    }

    static String readFile(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String onlyTicker = null;
        for (int i = 0; i < args.length; i++) {
            if ("--ticker".equals(args[i]) && i + 1 < args.length) onlyTicker = args[++i].toUpperCase();
        }

        Path root = workspaceRoot();
        DcfModel.Json.Obj wd = (DcfModel.Json.Obj) DcfModel.Json.parse(
                readFile(root.resolve("your_files/dcf-valuations/.src/workbook_data.json")));
        DcfHistory.Json.Obj inputs = new DcfHistory.Json.Obj();
        try {
            inputs = (DcfHistory.Json.Obj) ((DcfHistory.Json.Obj) DcfHistory.Json.parse(
                    readFile(root.resolve("sec_filings/dcf_inputs_all.json")))).get("companies");
        } catch (Exception e) { /* no CIK map */ }

        System.out.println("=== Trough Normalization Dry-Run (proposal 010) ===");
        System.out.println("Claude spec: 5yr history, median>0.005, TTM<=80% median,");
        System.out.println("  last-3 not strictly declining. DRY RUN - no writes.\n");

        int wouldNormalize = 0, total = 0, noData = 0;
        List<String[]> report = new ArrayList<>();

        for (DcfModel.Json.Val cv : wd.getList("companies")) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            String t = c.getStr("ticker");
            if (onlyTicker != null && !onlyTicker.equals(t)) continue;
            total++;

            DcfModel.Json.Obj a = c.getObj("assumptions");
            if (a == null || !a.has("base")) {
                noData++;
                continue;
            }

            String kind = a.has("kind") && a.get("kind") instanceof DcfModel.Json.Str
                    ? ((DcfModel.Json.Str) a.get("kind")).v : "FCFF";
            // Trough normalization only applies to FCF-based models, not REV or bank FCFE
            if (!"FCFF".equals(kind)) {
                continue;
            }

            double baseM = a.getNum("base"); // $M
            double g = a.getNum("g");
            double w = a.getNum("w");
            double tgr = a.getNum("t");
            double shares = a.getNum("shares");
            double netdebt = a.getNum("netdebt");
            Double startMargin = a.has("start_margin") ? Double.valueOf(a.getNum("start_margin")) : null;
            Double endMargin = a.has("end_margin") ? Double.valueOf(a.getNum("end_margin")) : null;

            // Pull 5-year history from companyfacts
            String cik = DcfHistory.cikFor(t, inputs);
            if (cik == null) { noData++; continue; }
            Path cf = root.resolve("dcf_csv/backtest/companyfacts/" + cik + ".json");
            if (!Files.exists(cf)) { noData++; continue; }

            List<XbrlFacts.Fact> facts = XbrlFacts.load(cf);
            DcfHistory.Maps maps = DcfHistory.pullMaps(facts);
            DcfHistory.Series rev = DcfHistory.lastN(maps.rev);
            Map<Integer, Double> fcfMap = new LinkedHashMap<>();
            for (int fy : maps.ocf.keySet()) {
                if (maps.cap.containsKey(fy)) {
                    fcfMap.put(fy, maps.ocf.get(fy) - maps.cap.get(fy));
                }
            }
            DcfHistory.Series fcf = DcfHistory.lastN(fcfMap);

            if (rev.v.length < 5 || fcf.v.length < 5) { noData++; continue; }

            // Build 5-year FCF margin history (oldest to newest)
            int n = 5;
            double[] margins = new double[n];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int idx = fcf.v.length - n + i;
                double r = DcfHistory.revAt(rev, fcf.fy[idx]);
                if (Double.isNaN(r) || r <= 0) { ok = false; break; }
                margins[i] = fcf.v[idx] / r;
            }
            if (!ok) { noData++; continue; }

            // Base-year revenue = most recent FY revenue ($M)
            double baseRevM = rev.v[rev.v.length - 1] / 1e6;
            // TTM margin = base FCF / base-year revenue
            double ttmMargin = baseM / baseRevM;

            Double normalizedBase = DcfModel.troughNormalizedBase(baseRevM, margins, ttmMargin);

            if (normalizedBase != null) {
                wouldNormalize++;
                // Recompute IV with normalized base
                double normBase = normalizedBase.doubleValue();
                double oldIv = DcfModel.intrinsicValue(
                        kind, baseM, g, w, tgr, shares, netdebt, startMargin, endMargin);
                double newIv = DcfModel.intrinsicValue(
                        kind, normBase, g, w, tgr, shares, netdebt, startMargin, endMargin);
                double pctChg = oldIv != 0 ? (newIv - oldIv) / Math.abs(oldIv) * 100 : Double.NaN;

                // Median for reporting
                double[] sorted = margins.clone();
                java.util.Arrays.sort(sorted);
                double median = sorted[2];

                report.add(new String[]{
                    t,
                    String.format("%,.0f", baseM),
                    String.format("%,.0f", normalizedBase),
                    String.format("%.1f%%", ttmMargin * 100),
                    String.format("%.1f%%", median * 100),
                    String.format("$%.2f", oldIv),
                    String.format("$%.2f", newIv),
                    String.format("%+.1f%%", pctChg)
                });
            }
        }

        System.out.println("Tickers analyzed: " + total);
        System.out.println("Would normalize: " + wouldNormalize);
        System.out.println("Insufficient data: " + noData);
        System.out.println();

        if (!report.isEmpty()) {
            System.out.printf("%-6s %12s %12s %10s %10s %12s %12s %10s%n",
                    "Ticker", "Old base$M", "New base$M", "TTM marg", "5y median",
                    "Old IV", "New IV", "IV change");
            System.out.println("-".repeat(96));
            for (String[] r : report) {
                System.out.printf("%-6s %12s %12s %10s %10s %12s %12s %10s%n",
                        (Object[]) r);
            }
        } else {
            System.out.println("No tickers trigger normalization under Claude's spec.");
        }
    }
}
