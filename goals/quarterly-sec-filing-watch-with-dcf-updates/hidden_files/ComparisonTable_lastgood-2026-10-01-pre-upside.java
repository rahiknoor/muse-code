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
// columns (2026-09-30 order: NLM grounded, Our DCF, Forward tilt,
// Muse speculation price, NLM speculation price). "—" when the value is
// missing or <= 0.
// Reshaped 2026-09-30 (Rahik: don't show the growth outlook in the value
// table — show it separately): the Growth outlook column left the main
// table. The value table is now 10 columns; growth outlook renders as its
// own separate block AFTER the table and its provenance lines — a
// "Growth outlook" header line followed by a compact
// | Ticker | Growth outlook | Thesis | table, one row per holding.
// The growth_outlook.json backend and the speculation-nudge computation
// that reads it are untouched: this is a display-only change.
// Reordered 2026-09-30 (Rahik): the five intrinsic-value columns now run
// NLM grounded FIRST — Ticker | Price | NLM grounded | Our DCF |
// Forward tilt | Muse speculation price | NLM speculation price | Creator |
// Projection price (analyst target) | Website consensus. The buy-below
// sub-row's value follows Our DCF to its new (4th) column; the MoS sub-row
// lines up under the five in the same order.
// PAUSED 2026-09-30 (Rahik: "I will add those later") — the buy-below
// and margin-of-safety sub-rows are out of the render for now: ONE row per
// ticker. The paused spec lives in the render() comment and in
// ComparisonTable's PAUSED test block; restore verbatim on his word.
// Column order: Ticker | Price (market, reference) | NLM grounded
// (filings-grounded; verified values only) | Our DCF (one number) |
// Forward tilt (blessed forward values only; blank until verified) |
// Muse speculation price (ours pulled halfway toward the outside median +
// growth-outlook nudge; display-only, no buy-below) | NLM speculation
// price (DERIVED BY NOTEBOOKLM ITSELF — reserved slot, blank until its
// re-ask response is verified and blessed; never computed here) |
// Creator (followed creators, holdings only) | Projection price
// (analyst target) | Website consensus (median of
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
     *  each of the five intrinsic-value columns (2026-09-30 reorder: NLM
     *  grounded, Our DCF, Forward tilt, Muse speculation price,
     *  NLM speculation price). Growth outlook is NOT a
     *  column here (2026-09-30) — it renders as its own block after the
     *  table. */
    static final String HEADER =
        "| Ticker | Price | NLM grounded | Our DCF | Forward tilt | Muse speculation price | NLM speculation price | Creator | Projection price (analyst target) | Website consensus |";
    static final String SEP = "|---|---|---|---|---|---|---|---|---|---|";

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
            double req = reqFor(mdef, mticks, t);
            // PAUSED 2026-09-30 (Rahik: "I will add those later") — the
            // buy-below and margin-of-safety sub-rows are out of the render
            // for now: one row per ticker. The paused spec (restore verbatim
            // on his word): one buy-below on Our DCF = our DCF x (1 - margin),
            // the number the gap-watch trades on, on its own sub-row beneath
            // the ticker; a margin-of-safety sub-row beneath that with
            // MoS% = (value - price)/value under each intrinsic column.
            // Restore the two appends below VERBATIM on his word (they
            // already carry the 2026-09-30 reordered column positions).
            // Helper code stays in this file: buyBelow, mosFmt,
            // subRowAfter, mosRowAfter, checkMosWiring.
            // Double bbOurs = buyBelow(dcf, req);
            // sb.append("| buy below | | | | ")
            //   .append(fmt(bbOurs)).append(" | | | | | | |\n");
            // sb.append("| margin of safety | | ")
            //   .append(mosFmt(nlmV, px)).append(" | ")
            //   .append(mosFmt(dcf, px)).append(" | ")
            //   .append(mosFmt(fwdV, px)).append(" | ")
            //   .append(mosFmt(museSpec, px)).append(" | ")
            //   .append(mosFmt(nlmSpec, px)).append(" | | | |\n");
            Double px = price(prices, t);
            sb.append("| ").append(t).append(" | ")
              .append(fmt(px)).append(" | ")
              .append(fmt(nlmV)).append(" | ")
              .append(fmt(dcf)).append(" | ")
              .append(fmt(fwdV)).append(" | ")
              .append(fmt(museSpec)).append(" | ")
              .append(fmt(nlmSpec)).append(" | ")
              .append(fmt(creatorV)).append(" | ")
              .append(fmt(analystV)).append(" | ")
              .append(fmt(webV)).append(" |\n");
        }
        sb.append('\n');
        sb.append("Prices are from the morning price cache (").append(priceAsof).append(").\n");
        sb.append("Three separate DCF values, never blended: NotebookLM grounded (filings/history only), NotebookLM forward tilt (grounded + a modest forward-looking tilt), our DCF (story-informed). Forward tilt is blank until its values are verified.\n");
        sb.append("Speculation prices (display-only, no buy-below): Muse's pulls our DCF halfway toward the outside median plus the growth-outlook nudge; NotebookLM's is derived by NotebookLM itself — the slot stays blank until its re-ask response is verified and blessed. The outside median behind both lives in the backend (outside_voice.json), not as a column.\n");
        sb.append("Buy below = our DCF x (1 - required margin) per margin_requirements.json, shown per ticker in the Buy zone block below (Rahik 2026-10-01); gap-watch alerting stays on the base our DCF. The buy-below and margin-of-safety SUB-ROWS stay paused (Rahik 2026-09-30). Intrinsic columns are never margin-adjusted.\n");
        sb.append("Margin of safety is paused for now (Rahik 2026-09-30 — he will add it back later).\n");
        sb.append("Website consensus is the median of ValueInvesting.io, Simply Wall St, Finbox, and ValueSense DCF values \u2014 its own column, never blended with the others.\n");
        // Rahik 2026-09-30: growth outlook is shown separately, not as a
        // value-table column. Its own block after the table: a header line
        // followed by a compact per-ticker table. Display-only — the
        // growth_outlook.json backend and the speculation nudge are
        // untouched.
        sb.append('\n');
        sb.append("Growth outlook\n");
        sb.append('\n');
        sb.append("| Ticker | Growth outlook | Thesis |\n");
        sb.append("|---|---|---|\n");
        for (String t : ours.m.keySet()) {
            sb.append("| ").append(t).append(" | ")
              .append(goOutlook(goTickers, t)).append(" | ")
              .append(goThesis(goTickers, t)).append(" |\n");
        }
        // Rahik 2026-10-01: buy-focused brief — buy-zone status gets its own
        // block after the growth-outlook block (same separate-block pattern as
        // growth outlook: never a value-table column). The paused
        // buy-below/margin-of-safety SUB-ROWS stay paused; this block is the
        // new surface, not their return. Buy below = our DCF x (1 - required
        // margin) — the same number the gap-watch trades on.
        sb.append('\n');
        sb.append("Buy zone (buy below = our DCF x (1 - required margin); morning price)\n");
        sb.append('\n');
        sb.append("| Ticker | Buy below | Price | Status |\n");
        sb.append("|---|---|---|---|\n");
        for (String t : ours.m.keySet()) {
            Double dcf = colVal(ours, t);
            Double px = price(prices, t);
            double req = reqFor(mdef, mticks, t);
            Double bb = buyBelow(dcf, req);
            sb.append("| ").append(t).append(" | ")
              .append(fmt(bb)).append(" | ")
              .append(fmt(px)).append(" | ")
              .append(buyZoneStatus(bb, px)).append(" |\n");
        }
        return sb.toString();
    }

    /** Buy-below price for one DCF value: intrinsic x (1 - required margin).
     *  Missing, zero, or negative intrinsic values render as missing. */
    static Double buyBelow(Double dcf, double req) {
        return (dcf != null && dcf > 0) ? dcf * (1.0 - req) : null;
    }

    /** Buy-zone status for the brief block (Rahik 2026-10-01): IN BUY ZONE
     *  when the morning price is at or below buy-below; otherwise how far
     *  above the zone the price sits, one decimal. Missing inputs render
     *  as missing — never a guess. */
    static String buyZoneStatus(Double buyBelow, Double price) {
        if (buyBelow == null || price == null || price <= 0) return MISSING;
        if (price <= buyBelow) return "IN BUY ZONE";
        return String.format(Locale.US, "%.1f%% above", (price - buyBelow) / buyBelow * 100.0);
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

    /** Growth-outlook block (Rahik 2026-09-30): outlook and thesis get their
     *  own cells in the separate block — High/Moderate/Low, never a guess.
     *  The thesis is sanitized for the markdown table (pipes and newlines
     *  would break the row geometry). */
    static String goOutlook(J.O goTickers, String t) {
        J.V e = goTickers.m.get(t);
        if (!(e instanceof J.O)) return "not yet researched";
        String outlook = str(((J.O) e).m.get("outlook"));
        return outlook.isEmpty() ? "not yet researched" : outlook;
    }

    static String goThesis(J.O goTickers, String t) {
        J.V e = goTickers.m.get(t);
        if (!(e instanceof J.O)) return MISSING;
        String thesis = str(((J.O) e).m.get("thesis"))
                .replace("|", " / ").replace("\n", " ").replace("\r", " ").trim();
        return thesis.isEmpty() ? MISSING : thesis;
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
        // The growth-outlook block follows the main table (its rows also
        // start with "|"): scope every main-table assertion to the section
        // before the first blank line.
        String mainOut = out.substring(0, out.indexOf("\n\n"));
        List<String> mainLines = mainOut.lines().filter(l -> l.startsWith("|")).toList();
        // PAUSED 2026-09-30 (Rahik): sub-rows are out for now — one row per
        // ticker. Restore the (holdings * 3 + 2) form when they come back.
        SelfTest.checkEq(holdings + " holdings, " + (holdings + 2) + " main table lines",
                (long) (holdings + 2), (long) mainLines.size());
        SelfTest.checkContains("header exact", out, HEADER);
        // Rahik's three-row layout (2026-09-29/30, growth moved out 2026-09-30,
        // columns reordered 2026-09-30): no Buy below column at all;
        // NLM grounded | Our DCF | Forward tilt | Muse speculation price |
        // NLM speculation price | Creator | Projection price |
        // Website consensus. Growth outlook is NOT a column in the value
        // table — it has its own block after the table.
        SelfTest.checkNotContains("no buy-below column", mainOut, "| Buy below |");
        SelfTest.checkNotContains("no growth-outlook column in value table", mainOut, "Growth outlook");
        SelfTest.check("column order", mainOut.indexOf("| NLM grounded |")
                < mainOut.indexOf("| Our DCF |")
                && mainOut.indexOf("| Our DCF |") < mainOut.indexOf("| Forward tilt |")
                && mainOut.indexOf("| Forward tilt |") < mainOut.indexOf("| Muse speculation price |")
                && mainOut.indexOf("| Muse speculation price |") < mainOut.indexOf("| NLM speculation price |")
                && mainOut.indexOf("| NLM speculation price |") < mainOut.indexOf("| Creator |")
                && mainOut.indexOf("| Creator |") < mainOut.indexOf("| Projection price (analyst target) |")
                && mainOut.indexOf("| Projection price (analyst target) |") < mainOut.indexOf("| Website consensus |"));
        // PAUSED 2026-09-30 (Rahik: sub-rows return later) — one row per
        // ticker for now: every main line has 10 cells and no sub-row
        // labels appear. The three-row assertion block below is kept for
        // verbatim restore on his word.
        for (int i = 2; i < mainLines.size(); i++) {
            String[] mp = mainLines.get(i).split("\\|", -1);
            SelfTest.check("main row 10 cells: " + mainLines.get(i), mp.length == 12);
        }
        SelfTest.checkNotContains("no buy-below sub-rows while paused", mainOut, "| buy below |");
        SelfTest.checkNotContains("no mos sub-rows while paused", mainOut, "| margin of safety |");
        /* PAUSED-SUBROWS-START — restore verbatim on Rahik's word
        // Every ticker main row is immediately followed by its "buy below"
        // sub-row and then its "margin of safety" sub-row: 10 cells each;
        // buy-below has the margin price under Our DCF (cell 4) and blanks
        // elsewhere; margin-of-safety has MoS% under the five intrinsic
        // columns (cells 3, 4, 5, 6, 7) and blanks elsewhere.
        for (int i = 2; i + 2 < mainLines.size(); i += 3) {
            String main = mainLines.get(i), sub = mainLines.get(i + 1), mos = mainLines.get(i + 2);
            String[] mp = main.split("\\|", -1), sp = sub.split("\\|", -1), mop = mos.split("\\|", -1);
            SelfTest.check("main row 10 cells: " + main, mp.length == 12);
            SelfTest.check("buy-below sub-row 10 cells after: " + main, sp.length == 12);
            SelfTest.check("buy-below sub-row label after: " + main, sp[1].trim().equals("buy below"));
            boolean restBlank = sp[2].trim().isEmpty() && sp[3].trim().isEmpty();
            for (int c = 5; c <= 10; c++) restBlank &= sp[c].trim().isEmpty();
            SelfTest.check("buy-below sub-row only value under Our DCF: " + main, restBlank);
            SelfTest.check("buy-below sub-row value under Our DCF (cell 4): " + main,
                    !sp[4].trim().isEmpty());
            SelfTest.check("mos sub-row 10 cells after: " + main, mop.length == 12);
            SelfTest.check("mos sub-row label after: " + main, mop[1].trim().equals("margin of safety"));
            boolean mosRestBlank = mop[2].trim().isEmpty() && mop[8].trim().isEmpty()
                    && mop[9].trim().isEmpty() && mop[10].trim().isEmpty();
            SelfTest.check("mos sub-row blank outside intrinsic columns: " + main, mosRestBlank);
            for (int c : new int[]{3, 4, 5, 6, 7})
                SelfTest.check("mos cell " + c + " signed pct or missing: " + main,
                        mop[c].trim().equals(MISSING) || mop[c].trim().matches("[+-]\\d+\\.\\d%"));
            checkMosWiring(mp[1].trim(), mop, oursTickers, wNlm, wFwd, wSpTickers, wPrices);
        }
        // Anchored values (our-DCF-derived, stable across price moves):
        // NVDA 109.26 x 0.8 = 87.41 sits under Our DCF (cell 4) on its
        // sub-row; IREN's negative DCF renders "—" there.
        String nvdaSub = subRowAfter(mainLines, "| NVDA |");
        SelfTest.checkEq("NVDA buy-below sub-row", "87.41", nvdaSub.split("\\|", -1)[4].trim());
        String irenSub = subRowAfter(mainLines, "| IREN |");
        SelfTest.checkEq("IREN buy-below blank on sub-row", MISSING, irenSub.split("\\|", -1)[4].trim());
        PAUSED-SUBROWS-END */
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
        /* PAUSED-SUBROWS-START — restore verbatim on Rahik's word
        // QXO has no speculation/forward values: its MoS cells there are
        // missing regardless of price moves. Positions after the 2026-09-30
        // reorder: 3 = NLM grounded, 4 = Our DCF, 5 = Forward tilt,
        // 6 = Muse speculation, 7 = NLM speculation.
        String qxoMos = mosRowAfter(mainLines, "| QXO |");
        String[] qp = qxoMos.split("\\|", -1);
        SelfTest.checkEq("QXO mos muse-spec missing", MISSING, qp[6].trim());
        SelfTest.checkEq("QXO mos nlm-spec missing", MISSING, qp[7].trim());
        SelfTest.checkEq("QXO mos forward missing", MISSING, qp[5].trim());
        PAUSED-SUBROWS-END */
        growthSelfTest(out, mainOut, oursTickers);
        specSelfTest(out, mainOut, oursTickers);
        buyZoneSelfTest(out, mainOut, oursTickers);
        SelfTest.checkNotContains("non-holding creator tickers excluded", out, "TTD");
        SelfTest.checkContains("provenance line 1", out, "Prices are from the morning price cache");
        SelfTest.checkContains("provenance line 2", out, "never blended with the others");
        SelfTest.checkContains("provenance line 3", out, "Intrinsic columns are never margin-adjusted");
        SelfTest.checkContains("mos provenance line (paused)", out, "Margin of safety is paused for now");
        SelfTest.checkContains("three-voices line", out, "Three separate DCF values, never blended");
        SelfTest.checkEq("no duplicate main rows", true,
            mainOut.indexOf("| META |") == mainOut.lastIndexOf("| META |"));
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
        // 2026-09-30 display order: NLM grounded, Our DCF, Forward tilt,
        // Muse speculation price, NLM speculation price — cells 3..7.
        Double[] vals = {colVal(nlm, ticker), colVal(ours, ticker),
                         colVal(fwd, ticker), specVal(spTickers, ticker, "muse_speculation"),
                         specVal(spTickers, ticker, "nlm_speculation")};
        int[] cells = {3, 4, 5, 6, 7};
        for (int k = 0; k < 5; k++)
            SelfTest.checkEq("mos wiring " + ticker + " col " + cells[k],
                    mosFmt(vals[k], px), mop[cells[k]].trim());
    }

    /** Growth outlook self-tests (Rahik 2026-09-30): growth outlook is NOT
     *  a column in the value table — it renders as its own block AFTER the
     *  table and its provenance lines: a "Growth outlook" header line
     *  followed by a compact | Ticker | Growth outlook | Thesis | table,
     *  one row per holding. Backend coverage and the "not yet researched"
     *  fallback are still enforced. */
    static void growthSelfTest(String out, String mainOut, J.O oursTickers) throws IOException {
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
        // The block: header line, then the compact table; one row per
        // holding, in the same order as the main table.
        SelfTest.checkContains("growth: block header line", out, "\nGrowth outlook\n");
        SelfTest.checkContains("growth: block table header", out, "| Ticker | Growth outlook | Thesis |");
        // The growth block is scoped to its own section: the Buy zone
        // block (Rahik 2026-10-01) follows it and its rows also start
        // with "|", so an end-of-output sweep would count them as growth
        // rows.
        String goSection = out.substring(out.indexOf("| Ticker | Growth outlook | Thesis |"));
        int bzCut = goSection.indexOf("Buy zone (buy below =");
        if (bzCut > 0) goSection = goSection.substring(0, bzCut);
        List<String> blockLines = goSection.lines().filter(l -> l.startsWith("|")).toList();
        SelfTest.checkEq("growth: block header + sep + one row per holding",
                (long) (oursTickers.m.size() + 2), (long) blockLines.size());
        for (String bl : blockLines) {
            if (bl.contains("---")) continue;
            SelfTest.check("growth: block row 3 cells: " + bl, bl.split("\\|", -1).length == 5);
        }
        String qxoRow = blockLines.stream().filter(l -> l.startsWith("| QXO |")).findFirst().orElse("");
        SelfTest.checkEq("growth: QXO block row exact",
                "| QXO | High | " + qxoThesis + " |", qxoRow);
        for (String t : oursTickers.m.keySet()) {
            long n = blockLines.stream().filter(l -> l.startsWith("| " + t + " |")).count();
            SelfTest.checkEq("growth: block row exactly once: " + t, 1L, n);
        }
        // The value table itself carries no growth column.
        SelfTest.checkNotContains("growth: no growth column in value table", mainOut, "Growth outlook");
        // The "not yet researched" fallback: absent or null records render
        // the placeholder, never a plausible fill.
        J.O emptyGo = new J.O();
        SelfTest.checkEq("growth: absent ticker fallback", "not yet researched",
                growthOut(emptyGo, "ZZZ"));
        J.O nilGo = new J.O();
        nilGo.m.put("AAA", new J.Nil());
        SelfTest.checkEq("growth: null record fallback", "not yet researched",
                growthOut(nilGo, "AAA"));
        SelfTest.checkEq("growth: block absent outlook fallback", "not yet researched",
                goOutlook(emptyGo, "ZZZ"));
        SelfTest.checkEq("growth: block absent thesis fallback", MISSING,
                goThesis(emptyGo, "ZZZ"));
    }

    /** Speculation self-tests (Rahik's 4-result design, 2026-09-29).
     *  PAUSED 2026-09-30 (Rahik): the buy-below and margin-of-safety
     *  sub-rows are out of the render for now — one row per ticker.
     *  Speculation stays display-only with no buy-below.
     *  NotebookLM's speculation slot is reserved — always "—" until
     *  NotebookLM derives it itself. QXO has no outside median and renders
     *  "—" in the speculation slot, never a guess. */
    static void specSelfTest(String out, String mainOut, J.O oursTickers) throws IOException {
        J.O spAll = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/speculation_price.json"));
        J.O spTickers = (J.O) spAll.m.get("tickers");
        // Every holding has a speculation record (holdings-count-agnostic).
        SelfTest.checkEq("spec: covers all holdings", oursTickers.m.size(), spTickers.m.size());
        // Mandated relative order (2026-09-30 reorder): NLM grounded |
        // Our DCF | Forward tilt | Muse speculation price |
        // NLM speculation price. No Buy below column — it lives on the
        // sub-row. No Growth outlook column — it lives in its own block
        // after the table.
        SelfTest.checkNotContains("spec: no buy-below column", mainOut, "| Buy below |");
        SelfTest.checkNotContains("spec: no growth-outlook column", mainOut, "| Growth outlook |");
        // PAUSED 2026-09-30 (Rahik): sub-rows are out for now — restore the
        // "| buy below |" contains-check when they return.
        SelfTest.checkNotContains("spec: no buy-below sub-row while paused", mainOut, "| buy below |");
        SelfTest.check("spec: nlm grounded after Price",
                mainOut.indexOf("| NLM grounded |") > mainOut.indexOf("| Price |"));
        SelfTest.check("spec: our DCF after nlm grounded",
                mainOut.indexOf("| Our DCF |") > mainOut.indexOf("| NLM grounded |"));
        SelfTest.check("spec: forward tilt after our DCF",
                mainOut.indexOf("| Forward tilt |") > mainOut.indexOf("| Our DCF |"));
        SelfTest.check("spec: muse speculation after forward tilt",
                mainOut.indexOf("| Muse speculation price |") > mainOut.indexOf("| Forward tilt |"));
        SelfTest.check("spec: nlm speculation after muse speculation",
                mainOut.indexOf("| NLM speculation price |") > mainOut.indexOf("| Muse speculation price |"));
        // Render wiring (no stale literals — speculation values move when
        // briefs land): each spot cell matches its backend value formatted.
        // Muse speculation price sits in cell 6 after the 2026-09-30 reorder.
        String nvdaMain = out.lines().filter(l -> l.startsWith("| NVDA |")).findFirst().orElse("");
        SelfTest.checkEq("spec: NVDA muse cell wired",
                fmt(specVal(spTickers, "NVDA", "muse_speculation")),
                nvdaMain.split("\\|", -1)[6].trim());
        SelfTest.checkEq("spec: NVDA nlm reserved null", null,
                specVal(spTickers, "NVDA", "nlm_speculation"));
        String googMain = out.lines().filter(l -> l.startsWith("| GOOG |")).findFirst().orElse("");
        SelfTest.checkEq("spec: GOOG muse cell wired",
                fmt(specVal(spTickers, "GOOG", "muse_speculation")),
                googMain.split("\\|", -1)[6].trim());
        SelfTest.checkEq("spec: GOOG nlm null", null,
                specVal(spTickers, "GOOG", "nlm_speculation"));
        // 2026-10-01: QXO's website consensus (57.17) filled the outside
        // median, so muse speculation is now live, not null:
        // 5.03907168105596 + 0.5*(57.17-5.03907168105596) + 5.03907168105596*0.05
        // = 31.356... -> "31.36". The old null-expectation was stale data,
        // not a code bug (formula verified by hand).
        SelfTest.checkEq("spec: QXO muse live", "31.36",
                fmt(specVal(spTickers, "QXO", "muse_speculation")));
        SelfTest.checkEq("spec: QXO nlm null", null,
                specVal(spTickers, "QXO", "nlm_speculation"));
        // Buy-below spot value (20% default margin): NVDA ours 109.26 -> 87.41.
        SelfTest.checkEq("spec: buy-below ours", "87.41",
                fmt(buyBelow(109.25966690921047, 0.20)));
    }

    /** Buy-zone block self-test (Rahik 2026-10-01, buy-focused brief):
     *  unit tests for buyZoneStatus plus wiring of every holding's row
     *  against the backends (no stale literals — prices and DCFs move). */
    static void buyZoneSelfTest(String out, String mainOut, J.O oursTickers) throws IOException {
        SelfTest.checkEq("buyZoneStatus above", "25.0% above", buyZoneStatus(80.0, 100.0));
        SelfTest.checkEq("buyZoneStatus at zone", "IN BUY ZONE", buyZoneStatus(80.0, 80.0));
        SelfTest.checkEq("buyZoneStatus inside zone", "IN BUY ZONE", buyZoneStatus(80.0, 79.99));
        SelfTest.checkEq("buyZoneStatus null buy-below", MISSING, buyZoneStatus(null, 100.0));
        SelfTest.checkEq("buyZoneStatus null price", MISSING, buyZoneStatus(80.0, null));
        SelfTest.checkContains("buy-zone block header", out, "Buy zone (buy below =");
        SelfTest.checkContains("buy-zone column header", out, "| Ticker | Buy below | Price | Status |");
        // The value table itself stays canon: no Status column there.
        SelfTest.checkNotContains("no status column in value table", mainOut, "| Status |");
        // Locate the block: header row, then separator, then one row per holding.
        List<String> lines = out.lines().toList();
        int hz = -1;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).startsWith("| Ticker | Buy below |")) { hz = i; break; }
        SelfTest.check("buy-zone header row found", hz > 0);
        List<String> bzRows = lines.subList(hz + 2, lines.size());
        SelfTest.checkEq("buy-zone covers all holdings", oursTickers.m.size(), bzRows.size());
        J.O ours = (J.O) ((J.O) ((J.O) J.parse(read(HOME + "/workspace/dcf_csv/blessed.json")))
                .m.get("columns")).m.get("ours");
        J.O oursT = (J.O) ours.m.get("tickers");
        J.O prices = (J.O) ((J.O) J.parse(read(HOME + "/workspace/stock_watch/price_cache.json"))).m.get("prices");
        J.O mdoc = (J.O) J.parse(read(HOME + "/workspace/dcf_csv/margin_requirements.json"));
        double mdef = (mdoc.m.get("default") instanceof J.N n) ? n.d : 0.20;
        J.V mtv = mdoc.m.get("tickers");
        J.O mticks = (mtv instanceof J.O o) ? o : null;
        for (String t : oursTickers.m.keySet()) {
            String row = bzRows.stream().filter(l -> l.startsWith("| " + t + " |"))
                    .findFirst().orElse("");
            SelfTest.check("buy-zone row present: " + t, !row.isEmpty());
            String[] cells = row.split("\\|", -1);
            Double dcf = colVal(oursT, t);
            Double px = price(prices, t);
            Double bb = buyBelow(dcf, reqFor(mdef, mticks, t));
            SelfTest.checkEq("buy-zone buy-below wired: " + t, fmt(bb), cells[2].trim());
            SelfTest.checkEq("buy-zone price wired: " + t, fmt(px), cells[3].trim());
            SelfTest.checkEq("buy-zone status wired: " + t, buyZoneStatus(bb, px), cells[4].trim());
        }
    }

    /** Required margin for one ticker: per-ticker override or the default. */
    static double reqFor(double mdef, J.O mticks, String t) {
        if (mticks != null && mticks.m.get(t) instanceof J.N rn) return rn.d;
        return mdef;
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
