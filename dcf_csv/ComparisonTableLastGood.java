// ComparisonTable.java — renders the standing "all sources" intrinsic-value table
// Rahik asked for this view often (2026-09-28): one row per holding, every source.
// Reshaped 2026-09-29 (Rahik: combine similar things into one, reduce clutter):
// one row per ticker, a single Buy below on Our DCF (the number the
// gap-watch trades on), no Outside voice column (the outside median lives in
// the backend as the speculation input — it was almost identical to the
// speculation price and did not earn its own column). The two speculation
// prices stay separate (story voice vs evidence voice), both display-only.
// Reworked 2026-09-29 (Rahik: margin below the price, not next to it): the
// Buy below no longer has its own column — each ticker is followed by a
// "buy below" sub-row whose only populated cell sits under Our DCF, so all
// prices scan vertically and the margin price reads right beneath them.
// Extended 2026-09-30 (Rahik: standing chat format, three rows per ticker):
// a third "margin of safety" sub-row follows the buy-below row, with
// MoS% = (value - price) / value under each of the five intrinsic-value
// columns (Our DCF, Muse speculation price, NotebookLM grounded, NotebookLM
// speculation price, Forward tilt). "—" when the value is missing or <= 0.
// Column order: Ticker | Price (market, reference) | Our DCF (one number) |
// Muse speculation price (ours pulled halfway toward the outside median +
// growth-outlook nudge; display-only, no buy-below) | Growth outlook
// (Muse's growth assessment — High/Moderate/Low + one-line thesis;
// "not yet researched" until that ticker's brief lands) | NotebookLM
// (grounded; verified values only) | NotebookLM speculation price (DERIVED
// BY NOTEBOOKLM ITSELF — reserved slot, blank until its re-ask response is
// verified and blessed; never computed here) | Forward tilt (blessed forward
// values only; blank until verified) | Creator (followed creators, holdings
// only) | Projection price (analyst target) | Website consensus (median of
// ValueInvesting.io, Simply Wall St, Finbox, ValueSense DCF values — its own
// column, never blended).
// Missing = "—", never a guess.
// Usage: java ComparisonTable.java --print | --self-test
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ComparisonTable {
    static final String HOME = System.getenv("HOME");
    static final String MISSING = "\u2014"; // em dash
    /** Rahik's three-row layout (2026-09-29/30): no Buy below column — each
     *  ticker is followed by a "buy below" sub-row with the margin price
     *  under Our DCF, then a "margin of safety" sub-row with MoS% under
     *  each of the five intrinsic-value columns. */
    static final String HEADER =
        "| Ticker | Price | Our DCF | Muse speculation price | Growth outlook | NotebookLM | NotebookLM speculation price | Forward tilt | Creator | Projection price (analyst target) | Website consensus |";
    static final String SEP = "|---|---|---|---|---|---|---|---|---|---|---|";

    public static void main(String[] args) throws Exception {
        // Always emit UTF-8 regardless of the shell locale (the "—" missing marker
        // becomes "?" under a POSIX locale otherwise).
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length > 0 && args[0].equals("--self-test")) { selfTest(); return; }
        System.out.print(render());
    }

    static J.O blessed() throws IOException {
        return (J.O) J.parse(read(HOME + "/workspace/dcf_csv/blessed.json"));
    }

    static String render() throws IOException {
        J.O cols = (J.O) ((J.O) blessed()).m.get("columns");
        J.O ours = (J.O) ((J.O) cols.m.get("ours")).m.get("tickers");
        J.O nlm = (J.O) ((J.O) cols.m.get("notebooklm")).m.get("tickers");
        // The forward column does not exist in blessed.json until the first
        // forward proposal is approved — treat a missing column as all-blank,
        // never as an error (the values arrive over the coming nights).
        J.O fwdCol = (J.O) cols.m.get("forward");
        J.O fwd = (fwdCol == null) ? null : (J.O) fwdCol.m.get("tickers");
        J.O creator = (J.O) ((J.O) cols.m.get("creator")).m.get("tickers");
        J.O analyst = (J.O) ((J.O) cols.m.get("analyst")).m.get("tickers");
        J.O webTickers = (J.O) ((J.O) J.parse(read(HOME + "/workspace/dcf_csv/website_consensus.json"))).m.get("tickers");
        J.O goAll = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/growth_outlook.json"));
        J.O goTickers = (J.O) goAll.m.get("tickers");
        J.O spAll = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/speculation_price.json"));
        J.O spTickers = (J.O) spAll.m.get("tickers");
        J.O cache = (J.O) J.parse(read(HOME + "/workspace/stock_watch/price_cache.json"));
        J.O prices = (J.O) cache.m.get("prices");
        String priceAsof = str(cache.m.get("asof"));
        // Rahik's required margin of safety per stock (margin_requirements.json).
        // The margin is NEVER baked into an intrinsic column — the buy-below
        // is its own separate price alongside Our DCF.
        J.O mdoc = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/margin_requirements.json"));
        double mdef = (mdoc.m.get("default") instanceof J.N n) ? n.d : 0.20;
        J.V mtv = mdoc.m.get("tickers");
        J.O mticks = (mtv instanceof J.O o) ? o : null;

        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n').append(SEP).append('\n');
        for (String t : ours.m.keySet()) {
            Double dcf = colVal(ours, t);
            Double nlmV = colVal(nlm, t);
            Double fwdV = colVal(fwd, t);
            Double museSpec = specVal(spTickers, t, "muse_speculation");
            Double nlmSpec = specVal(spTickers, t, "nlm_speculation");
            Double creatorV = colVal(creator, t);
            Double analystV = colVal(analyst, t);
            Double webV = webConsensus(webTickers, t);
            double req = mdef;
            if (mticks != null && mticks.m.get(t) instanceof J.N rn) req = rn.d;
            // ONE buy-below, on Our DCF (Rahik 2026-09-29 reshape): the number
            // the gap-watch trades on. It renders on its own sub-row beneath
            // the ticker (Rahik 2026-09-29: margin below the price, not next
            // to it) so prices scan vertically. All other prices are
            // display-only.
            Double bbOurs = buyBelow(dcf, req);
            Double px = price(prices, t);
            sb.append("| ").append(t).append(" | ")
              .append(fmt(px)).append(" | ")
              .append(fmt(dcf)).append(" | ")
              .append(fmt(museSpec)).append(" | ")
              .append(growthOut(goTickers, t)).append(" | ")
              .append(fmt(nlmV)).append(" | ")
              .append(fmt(nlmSpec)).append(" | ")
              .append(fmt(fwdV)).append(" | ")
              .append(fmt(creatorV)).append(" | ")
              .append(fmt(analystV)).append(" | ")
              .append(fmt(webV)).append(" |\n");
            sb.append("| buy below | | ")
              .append(fmt(bbOurs)).append(" | | | | | | | | |\n");
            // Rahik 2026-09-30 (standing format): a "margin of safety"
            // sub-row under the buy-below row, MoS% = (value - price)/value
            // under each of the five intrinsic-value columns; everything
            // else blank.
            sb.append("| margin of safety | | ")
              .append(mosFmt(dcf, px)).append(" | ")
              .append(mosFmt(museSpec, px)).append(" | | ")
              .append(mosFmt(nlmV, px)).append(" | ")
              .append(mosFmt(nlmSpec, px)).append(" | ")
              .append(mosFmt(fwdV, px)).append(" | | | |\n");
        }
        sb.append('\n');
        sb.append("Prices are from the morning price cache (").append(priceAsof).append(").\n");
        sb.append("Three separate DCF values, never blended: NotebookLM grounded (filings/history only), NotebookLM forward tilt (grounded + a modest forward-looking tilt), our DCF (story-informed). Forward tilt is blank until its values are verified.\n");
        sb.append("Speculation prices (display-only, no buy-below): Muse's pulls our DCF halfway toward the outside median plus the growth-outlook nudge; NotebookLM's is derived by NotebookLM itself — the slot stays blank until its re-ask response is verified and blessed. The outside median behind both lives in the backend (outside_voice.json), not as a column.\n");
        sb.append("Buy below sits on its own row beneath each ticker, under Our DCF = dcf x (1 - required margin); gap-watch alerting stays on the base our DCF. Intrinsic columns are never margin-adjusted.\n");
        sb.append("Margin of safety sits on its own row beneath the buy-below row: (intrinsic - price) / intrinsic under each of the five intrinsic columns (Our DCF, Muse speculation price, NotebookLM grounded, NotebookLM speculation price, Forward tilt); \u2014 when the value is missing or \u2264 0, or the price is missing.\n");
        sb.append("Website consensus is the median of ValueInvesting.io, Simply Wall St, Finbox, and ValueSense DCF values \u2014 its own column, never blended with the others.\n");
        return sb.toString();
    }

    /** Buy-below price for one DCF value: intrinsic x (1 - required margin).
     *  Missing, zero, or negative intrinsic values render as missing. */
    static Double buyBelow(Double dcf, double req) {
        return (dcf != null && dcf > 0) ? dcf * (1.0 - req) : null;
    }

    /** Margin of safety for one intrinsic value against the market price:
     *  (value - price) / value, formatted as a signed one-decimal percent.
     *  Missing, zero, or negative values — or a missing/non-positive price —
     *  render as missing: there is no safety margin to measure. */
    static String mosFmt(Double value, Double price) {
        if (value == null || value <= 0 || price == null || price <= 0) return MISSING;
        return String.format(Locale.US, "%+.1f%%", (value - price) / value * 100.0);
    }

    static Double price(J.O prices, String t) {
        J.V e = prices.m.get(t);
        if (!(e instanceof J.O)) return null;
        J.V p = ((J.O) e).m.get("price");
        return (p instanceof J.N) ? ((J.N) p).d : null;
    }

    static Double colVal(J.O col, String t) {
        if (col == null) return null; // column not blessed yet -> all missing
        J.V e = col.m.get(t);
        if (!(e instanceof J.O)) return null; // ticker absent from column -> missing
        J.V v = ((J.O) e).m.get("value");
        return (v instanceof J.N) ? ((J.N) v).d : null; // null entry -> missing
    }

    static Double webConsensus(J.O webTickers, String t) {
        J.V e = webTickers.m.get(t);
        if (!(e instanceof J.O)) return null;
        J.V c = ((J.O) e).m.get("consensus");
        return (c instanceof J.N) ? ((J.N) c).d : null;
    }

    static String fmt(Double v) {
        if (v == null) return MISSING;
        return String.format(Locale.US, "%.2f", v);
    }

    static String str(J.V v) { return (v instanceof J.S) ? ((J.S) v).s : ""; }

    /** Speculation cell (Rahik's 4-result design, 2026-09-29): reads field
     *  {@code field} ("muse_speculation" or "nlm_speculation") from the
     *  per-ticker record. Missing = null -> "—". */
    static Double specVal(J.O tickers, String t, String field) {
        J.V e = tickers.m.get(t);
        if (!(e instanceof J.O)) return null; // ticker absent or null -> missing
        J.V v = ((J.O) e).m.get(field);
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    /** Growth outlook cell: "High — <thesis>", or "not yet researched" when
     *  the ticker's deep-research brief has not landed. Never a plausible fill. */
    static String growthOut(J.O goTickers, String t) {
        J.V e = goTickers.m.get(t);
        if (!(e instanceof J.O)) return "not yet researched";
        J.V ov = ((J.O) e).m.get("outlook");
        if (!(ov instanceof J.S)) return "not yet researched";
        String outlook = ((J.S) ov).s;
        J.V tv = ((J.O) e).m.get("thesis");
        String thesis = (tv instanceof J.S) ? ((J.S) tv).s : "";
        return thesis.isEmpty() ? outlook : outlook + " \u2014 " + thesis;
    }

    static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    static void selfTest() throws Exception {
        String out = render();
        // Table lines = header + separator + three rows per holding (main
        // row + "buy below" sub-row + "margin of safety" sub-row); holding
        // count derived from the holdings list, never hardcoded.
        int holdings = 0;
        J.V tickersV = ((J.O) ((J.O) ((J.O) J.parse(read(HOME + "/workspace/dcf_csv/blessed.json")))
                .m.get("columns")).m.get("ours")).m.get("tickers");
        J.O oursTickers = (tickersV instanceof J.O o) ? o : null;
        if (oursTickers != null) holdings = oursTickers.m.size();
        // Backend maps reused by the MoS wiring check (full precision).
        J.O blessedCols = (J.O) ((J.O) J.parse(read(HOME + "/workspace/dcf_csv/blessed.json"))).m.get("columns");
        J.O wNlm = (J.O) ((J.O) blessedCols.m.get("notebooklm")).m.get("tickers");
        J.O wFwdCol = (J.O) blessedCols.m.get("forward");
        J.O wFwd = (wFwdCol == null) ? null : (J.O) wFwdCol.m.get("tickers");
        J.O wSpTickers = (J.O) ((J.O) J.parse(read(HOME + "/workspace/dcf_csv/speculation_price.json"))).m.get("tickers");
        J.O wPrices = (J.O) ((J.O) J.parse(read(HOME + "/workspace/stock_watch/price_cache.json"))).m.get("prices");
        List<String> tableLines = out.lines().filter(l -> l.startsWith("|")).toList();
        SelfTest.checkEq(holdings + " holdings, " + (holdings * 3 + 2) + " table lines",
                (long) (holdings * 3 + 2), (long) tableLines.size());
        SelfTest.checkContains("header exact", out, HEADER);
        // Rahik's three-row layout (2026-09-29/30): no Buy below column at
        // all; Our DCF | Muse speculation price | Growth outlook |
        // NotebookLM | NotebookLM speculation price | Forward tilt |
        // Creator | Projection price | Website consensus.
        SelfTest.checkNotContains("no buy-below column", out, "| Buy below |");
        SelfTest.check("column order", out.indexOf("| Our DCF |")
                < out.indexOf("| Muse speculation price |")
                && out.indexOf("| Muse speculation price |") < out.indexOf("| Growth outlook |")
                && out.indexOf("| Growth outlook |") < out.indexOf("| NotebookLM |")
                && out.indexOf("| NotebookLM |") < out.indexOf("| NotebookLM speculation price |")
                && out.indexOf("| NotebookLM speculation price |") < out.indexOf("| Forward tilt |")
                && out.indexOf("| Forward tilt |") < out.indexOf("| Creator |")
                && out.indexOf("| Creator |") < out.indexOf("| Projection price (analyst target) |")
                && out.indexOf("| Projection price (analyst target) |") < out.indexOf("| Website consensus |"));
        // Every ticker main row is immediately followed by its "buy below"
        // sub-row and then its "margin of safety" sub-row: 11 cells each;
        // buy-below has the margin price under Our DCF (cell 2) and blanks
        // elsewhere; margin-of-safety has MoS% under the five intrinsic
        // columns (cells 3, 4, 6, 7, 8) and blanks elsewhere.
        for (int i = 2; i + 2 < tableLines.size(); i += 3) {
            String main = tableLines.get(i), sub = tableLines.get(i + 1), mos = tableLines.get(i + 2);
            String[] mp = main.split("\\|", -1), sp = sub.split("\\|", -1), mop = mos.split("\\|", -1);
            SelfTest.check("main row 11 cells: " + main, mp.length == 13);
            SelfTest.check("buy-below sub-row 11 cells after: " + main, sp.length == 13);
            SelfTest.check("buy-below sub-row label after: " + main, sp[1].trim().equals("buy below"));
            boolean restBlank = sp[2].trim().isEmpty();
            for (int c = 4; c <= 11; c++) restBlank &= sp[c].trim().isEmpty();
            SelfTest.check("buy-below sub-row only value under Our DCF: " + main, restBlank);
            SelfTest.check("mos sub-row 11 cells after: " + main, mop.length == 13);
            SelfTest.check("mos sub-row label after: " + main, mop[1].trim().equals("margin of safety"));
            boolean mosRestBlank = mop[2].trim().isEmpty() && mop[5].trim().isEmpty()
                    && mop[9].trim().isEmpty() && mop[10].trim().isEmpty() && mop[11].trim().isEmpty();
            SelfTest.check("mos sub-row blank outside intrinsic columns: " + main, mosRestBlank);
            for (int c : new int[]{3, 4, 6, 7, 8})
                SelfTest.check("mos cell " + c + " signed pct or missing: " + main,
                        mop[c].trim().equals(MISSING) || mop[c].trim().matches("[+-]\\d+\\.\\d%"));
            checkMosWiring(mp[1].trim(), mop, oursTickers, wNlm, wFwd, wSpTickers, wPrices);
        }
        // Anchored values (our-DCF-derived, stable across price moves):
        // NVDA 109.26 x 0.8 = 87.41 sits under Our DCF on its sub-row;
        // IREN's negative DCF renders "—" there.
        String nvdaSub = subRowAfter(tableLines, "| NVDA |");
        SelfTest.checkEq("NVDA buy-below sub-row", "87.41", nvdaSub.split("\\|", -1)[3].trim());
        String irenSub = subRowAfter(tableLines, "| IREN |");
        SelfTest.checkEq("IREN buy-below blank on sub-row", MISSING, irenSub.split("\\|", -1)[3].trim());
        SelfTest.checkNotContains("no per-price buy-belows", out, "Buy below (");
        SelfTest.checkNotContains("no outside voice column", out, "| Outside voice |");
        // buyBelow unit checks: intrinsic x (1 - req); missing/zero/negative -> null.
        SelfTest.checkEq("buyBelow basic", 80.0, buyBelow(100.0, 0.20));
        SelfTest.checkEq("buyBelow custom margin", 70.0, buyBelow(100.0, 0.30));
        SelfTest.checkEq("buyBelow null", null, buyBelow(null, 0.20));
        SelfTest.checkEq("buyBelow zero", null, buyBelow(0.0, 0.20));
        SelfTest.checkEq("buyBelow negative", null, buyBelow(-14.38, 0.20));
        // mosFmt unit checks: (value - price) / value as a signed
        // one-decimal percent; missing/zero/negative values or a
        // missing/non-positive price render as missing.
        SelfTest.checkEq("mosFmt basic", "+20.0%", mosFmt(100.0, 80.0));
        SelfTest.checkEq("mosFmt negative", "-20.0%", mosFmt(100.0, 120.0));
        SelfTest.checkEq("mosFmt rounding", "+66.7%", mosFmt(3.0, 1.0));
        SelfTest.checkEq("mosFmt null value", MISSING, mosFmt(null, 80.0));
        SelfTest.checkEq("mosFmt zero value", MISSING, mosFmt(0.0, 80.0));
        SelfTest.checkEq("mosFmt negative value", MISSING, mosFmt(-14.38, 41.38));
        SelfTest.checkEq("mosFmt null price", MISSING, mosFmt(100.0, null));
        SelfTest.checkEq("mosFmt zero price", MISSING, mosFmt(100.0, 0.0));
        // QXO has no speculation/forward values: its MoS cells there are
        // missing regardless of price moves.
        String qxoMos = mosRowAfter(tableLines, "| QXO |");
        String[] qp = qxoMos.split("\\|", -1);
        SelfTest.checkEq("QXO mos muse-spec missing", MISSING, qp[4].trim());
        SelfTest.checkEq("QXO mos nlm-spec missing", MISSING, qp[7].trim());
        SelfTest.checkEq("QXO mos forward missing", MISSING, qp[8].trim());
        growthSelfTest(out, oursTickers);
        specSelfTest(out, oursTickers);
        SelfTest.checkNotContains("non-holding creator tickers excluded", out, "TTD");
        SelfTest.checkContains("provenance line 1", out, "Prices are from the morning price cache");
        SelfTest.checkContains("provenance line 2", out, "never blended with the others");
        SelfTest.checkContains("provenance line 3", out, "Intrinsic columns are never margin-adjusted");
        SelfTest.checkContains("mos provenance line", out, "Margin of safety sits on its own row");
        SelfTest.checkContains("three-voices line", out, "Three separate DCF values, never blended");
        SelfTest.checkEq("no duplicate rows", true,
            out.indexOf("| META |") == out.lastIndexOf("| META |"));
        SelfTest.finish();
    }

    /** The "buy below" sub-row directly following a ticker's main row. */
    static String subRowAfter(List<String> tableLines, String mainPrefix) {
        for (int i = 0; i + 1 < tableLines.size(); i++)
            if (tableLines.get(i).startsWith(mainPrefix)) return tableLines.get(i + 1);
        return "";
    }

    /** The "margin of safety" sub-row two lines below a ticker's main row. */
    static String mosRowAfter(List<String> tableLines, String mainPrefix) {
        for (int i = 0; i + 2 < tableLines.size(); i++)
            if (tableLines.get(i).startsWith(mainPrefix)) return tableLines.get(i + 2);
        return "";
    }

    /** Wiring check: every MoS cell equals mosFmt applied to the backend
     *  values at full precision — catches column misalignment. mosFmt
     *  itself is covered by literal unit tests above. */
    static void checkMosWiring(String ticker, String[] mop, J.O ours, J.O nlm, J.O fwd,
                               J.O spTickers, J.O prices) {
        Double px = price(prices, ticker);
        Double[] vals = {colVal(ours, ticker), specVal(spTickers, ticker, "muse_speculation"),
                         colVal(nlm, ticker), specVal(spTickers, ticker, "nlm_speculation"),
                         colVal(fwd, ticker)};
        int[] cells = {3, 4, 6, 7, 8};
        for (int k = 0; k < 5; k++)
            SelfTest.checkEq("mos wiring " + ticker + " col " + cells[k],
                    mosFmt(vals[k], px), mop[cells[k]].trim());
    }

    /** Growth outlook self-tests (Rahik 2026-09-28): the column shows
     *  Muse's one-line assessment next to the Muse DCF. QXO renders its full
     *  thesis; tickers without a brief render "not yet researched", never a
     *  plausible fill. */
    static void growthSelfTest(String out, J.O oursTickers) throws IOException {
        J.O goAll = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/growth_outlook.json"));
        J.O goTickers = (J.O) goAll.m.get("tickers");
        // Every holding has a growth record (holdings-count-agnostic — never
        // a hardcoded count).
        SelfTest.checkEq("growth: covers all holdings", oursTickers.m.size(), goTickers.m.size());
        for (String t : oursTickers.m.keySet())
            SelfTest.check("growth: holding covered: " + t, goTickers.m.containsKey(t));
        // Only High/Moderate/Low or null accepted; non-null records carry
        // a thesis, an as-of date, and a source list.
        for (Map.Entry<String, J.V> e : goTickers.m.entrySet()) {
            J.V v = e.getValue();
            if (v instanceof J.Nil) continue;
            SelfTest.check("growth: " + e.getKey() + " is Obj", v instanceof J.O);
            J.O rec = (J.O) v;
            String outlook = str(rec.m.get("outlook"));
            SelfTest.check("growth: " + e.getKey() + " outlook in rubric",
                    outlook.equals("High") || outlook.equals("Moderate") || outlook.equals("Low"));
            SelfTest.check("growth: " + e.getKey() + " thesis present",
                    !str(rec.m.get("thesis")).isEmpty());
            SelfTest.check("growth: " + e.getKey() + " asof present",
                    !str(rec.m.get("asof")).isEmpty());
            J.V srcs = rec.m.get("sources");
            SelfTest.check("growth: " + e.getKey() + " sources non-empty",
                    srcs instanceof J.A && !((J.A) srcs).l.isEmpty());
        }
        // QXO renders High with the exact two-sided thesis.
        String qxoThesis = "Serial acquirer targeting $50B revenue within the decade and "
                + "\u2265$300M/yr TopBuild synergies by 2030, with data-center end-market "
                + "exposure and live AI pricing tools \u2014 set against ~$9.2B pro-forma "
                + "debt (~4.1x 2025 EBITDA), ~$265M/yr in preferred dividends ahead of "
                + "common, ~1.04B pro-forma shares, and three integrations in eleven months.";
        J.O qxo = (J.O) goTickers.m.get("QXO");
        SelfTest.checkEq("growth: QXO outlook", "High", str(qxo.m.get("outlook")));
        SelfTest.checkEq("growth: QXO thesis exact", qxoThesis, str(qxo.m.get("thesis")));
        SelfTest.checkContains("growth: QXO row shows High", out, "| QXO |");
        SelfTest.checkContains("growth: QXO row thesis", out,
                "High \u2014 " + qxoThesis);
        // The "not yet researched" fallback: absent or null records render
        // the placeholder, never a plausible fill.
        J.O emptyGo = new J.O();
        SelfTest.checkEq("growth: absent ticker fallback", "not yet researched",
                growthOut(emptyGo, "ZZZ"));
        J.O nilGo = new J.O();
        nilGo.m.put("AAA", new J.Nil());
        SelfTest.checkEq("growth: null record fallback", "not yet researched",
                growthOut(nilGo, "AAA"));
    }

    /** Speculation self-tests (Rahik's 4-result design, 2026-09-29, three-row
     *  layout): the buy-below lives on the sub-row under Our DCF,
     *  speculation display-only with no buy-below.
     *  NotebookLM's speculation slot is reserved — always "—" until
     *  NotebookLM derives it itself. QXO has no outside median and renders
     *  "—" in the speculation slot, never a guess. */
    static void specSelfTest(String out, J.O oursTickers) throws IOException {
        J.O spAll = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/speculation_price.json"));
        J.O spTickers = (J.O) spAll.m.get("tickers");
        // Every holding has a speculation record (holdings-count-agnostic).
        SelfTest.checkEq("spec: covers all holdings", oursTickers.m.size(), spTickers.m.size());
        // Mandated relative order: Our DCF | Muse speculation price |
        // Growth outlook | NotebookLM | NotebookLM speculation price |
        // Forward tilt. No Buy below column — it lives on the sub-row.
        SelfTest.checkNotContains("spec: no buy-below column", out, "| Buy below |");
        SelfTest.checkContains("spec: buy-below sub-row label", out, "| buy below |");
        SelfTest.check("spec: muse speculation after Our DCF",
                out.indexOf("| Muse speculation price |") > out.indexOf("| Our DCF |"));
        SelfTest.check("spec: growth outlook after muse speculation",
                out.indexOf("| Growth outlook |") > out.indexOf("| Muse speculation price |"));
        SelfTest.check("spec: forward tilt after nlm speculation",
                out.indexOf("| Forward tilt |") > out.indexOf("| NotebookLM speculation price |"));
        // Render wiring (no stale literals — speculation values move when
        // briefs land): each spot cell matches its backend value formatted.
        String nvdaMain = out.lines().filter(l -> l.startsWith("| NVDA |")).findFirst().orElse("");
        SelfTest.checkEq("spec: NVDA muse cell wired",
                fmt(specVal(spTickers, "NVDA", "muse_speculation")),
                nvdaMain.split("\\|", -1)[4].trim());
        SelfTest.checkEq("spec: NVDA nlm reserved null", null,
                specVal(spTickers, "NVDA", "nlm_speculation"));
        String googMain = out.lines().filter(l -> l.startsWith("| GOOG |")).findFirst().orElse("");
        SelfTest.checkEq("spec: GOOG muse cell wired",
                fmt(specVal(spTickers, "GOOG", "muse_speculation")),
                googMain.split("\\|", -1)[4].trim());
        SelfTest.checkEq("spec: GOOG nlm null", null,
                specVal(spTickers, "GOOG", "nlm_speculation"));
        SelfTest.checkEq("spec: QXO muse null", null,
                specVal(spTickers, "QXO", "muse_speculation"));
        SelfTest.checkEq("spec: QXO nlm null", null,
                specVal(spTickers, "QXO", "nlm_speculation"));
        // Buy-below spot value (20% default margin): NVDA ours 109.26 -> 87.41.
        SelfTest.checkEq("spec: buy-below ours", "87.41",
                fmt(buyBelow(109.25966690921047, 0.20)));
    }

    // ---- minimal JSON (same pattern as Proposals.J / WebsiteConsensus.J) ----
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; final String lit; N(double d, String lit) { this.d = d; this.lit = lit; } }
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
                    if (i >= t.length() || t.charAt(i) != '"') throw new IllegalArgumentException("expected key at " + i);
                    String k = str(); ws();
                    if (i >= t.length() || t.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    o.m.put(k, val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == '}') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
                return o;
            }
            A arr() {
                A a = new A(); i++; ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == ']') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
                return a;
            }
            String str() {
                StringBuilder sb = new StringBuilder(); i++;
                while (true) {
                    if (i >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= t.length()) throw new IllegalArgumentException("bad escape");
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
                                if (i + 4 > t.length()) throw new IllegalArgumentException("bad \\u escape");
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try { return new N(Double.parseDouble(lit), lit); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("bad number at " + s); }
            }
        }
    }
}
