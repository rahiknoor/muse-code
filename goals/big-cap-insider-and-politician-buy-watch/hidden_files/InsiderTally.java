// InsiderTally.java — running tally of insider + politician transactions.
//
// A small ledger (insider_tally.json) accumulates every transaction from each
// daily insider/politician digest. `--tally` renders a trailing-N-day
// leaderboard section for the digest: buys/sells x insiders/politicians,
// plus multi-buyer / multi-seller highlights (the cluster view Rahik asked
// for on 2026-10-07).
//
// Ledger lives at:
//   $HOME/workspace/goals/big-cap-insider-and-politician-buy-watch/hidden_files/insider_tally.json
// ($HOME from the environment — never the Java user.home property, which is
// /root on this box.)
//
// Usage:
//   java --class-path "$HOME/workspace/lib/json/json-20240303.jar" InsiderTally.java \
//       --add-txn /tmp/batch_2026-10-07.json [--ledger <path>]
//       # batch.json = JSON array of transaction objects (no id; dedupe computed).
//       # Prints added=N skipped=M. Invalid entries fail fast (exit 2).
//   java --class-path "$HOME/workspace/lib/json/json-20240303.jar" InsiderTally.java \
//       --remove-by-id "insider|P|Chu Mona|sell|2026-10-02|2852.0" [--ledger <path>]
//       # Removes the transaction with that dedupe id. Fail-closed: throws on zero matches.
//   java --class-path "$HOME/workspace/lib/json/json-20240303.jar" InsiderTally.java \
//       --tally 2026-10-07 [--days 30] [--ledger <path>]
//       # Prints the markdown tally section for the trailing window.
//   java --class-path "$HOME/workspace/lib/json/json-20240303.jar" InsiderTally.java --self-test
//
// Transaction object fields:
//   digest_date, group (insider|politician), side (buy|sell), ticker, company,
//   person, role, party (politicians, "" otherwise),
//   shares (number|null), price (per-share number|null),
//   value_usd (number; politicians carry the RANGE MIDPOINT),
//   value_range (politicians e.g. "$1,001-$15,000", "" otherwise),
//   value_approx (true for politicians — midpoint, never a stated price),
//   trade_date, filed_date, is_10b51 (true|false|null), source
//
// Dedupe id: group|TICKER|person|side|trade_date|shares-or-range.
// A catch-up re-listing of the same filing dedupes; a genuinely new purchase
// (different trade date) counts separately.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

public final class InsiderTally {

    static Path defaultLedger() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) home = ".";
        return Path.of(home,
            "workspace/goals/big-cap-insider-and-politician-buy-watch/hidden_files/insider_tally.json");
    }

    private InsiderTally() { }

    // ---------- ledger IO ----------

    static JSONObject readLedger(Path p) throws IOException {
        if (!Files.exists(p)) {
            JSONObject o = new JSONObject();
            o.put("updated", "");
            o.put("transactions", new JSONArray());
            return o;
        }
        return new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
    }

    static void writeLedger(Path p, JSONObject ledger) throws IOException {
        ledger.put("updated", LocalDate.now().toString());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, ledger.toString(1), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------- dedupe ----------

    static String dedupeId(JSONObject t) {
        String sharesPart = t.isNull("shares")
            ? t.optString("value_range", "")
            : Double.toString(t.getDouble("shares"));
        return String.join("|",
            t.getString("group"),
            t.getString("ticker").toUpperCase(Locale.ROOT).trim(),
            t.getString("person").trim(),
            t.getString("side"),
            t.optString("trade_date", ""),
            sharesPart);
    }

    static void validate(JSONObject t) {
        for (String k : new String[]{"digest_date","group","side","ticker","company","person","value_usd","source"}) {
            if (!t.has(k) || t.isNull(k) || t.optString(k,"").isBlank() && !k.equals("value_usd"))
                throw new IllegalArgumentException("transaction missing required field: " + k + " :: " + t);
        }
        if (Double.isNaN(t.getDouble("value_usd")) || t.getDouble("value_usd") < 0)
            throw new IllegalArgumentException("value_usd must be >= 0 :: " + t);
        String g = t.getString("group"), s = t.getString("side");
        if (!g.equals("insider") && !g.equals("politician"))
            throw new IllegalArgumentException("group must be insider|politician :: " + t);
        if (!s.equals("buy") && !s.equals("sell"))
            throw new IllegalArgumentException("side must be buy|sell :: " + t);
        LocalDate.parse(t.getString("digest_date")); // fail fast on bad date
    }

    /** Returns int[]{added, skipped}. */
    static int[] addBatch(Path ledgerPath, JSONArray batch) throws IOException {
        for (int i = 0; i < batch.length(); i++) validate(batch.getJSONObject(i));
        JSONObject ledger = readLedger(ledgerPath);
        JSONArray txns = ledger.getJSONArray("transactions");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < txns.length(); i++)
            seen.add(txns.getJSONObject(i).getString("id"));
        int added = 0, skipped = 0;
        for (int i = 0; i < batch.length(); i++) {
            JSONObject t = batch.getJSONObject(i);
            String id = dedupeId(t);
            if (seen.contains(id)) { skipped++; continue; }
            t.put("id", id);
            txns.put(t);
            seen.add(id);
            added++;
        }
        writeLedger(ledgerPath, ledger);
        return new int[]{added, skipped};
    }

    /** Removes the transaction(s) whose dedupe id equals {@code id}.
     *  Fail-closed: zero matches throws instead of writing. */
    static int removeById(Path ledgerPath, String id) throws IOException {
        JSONObject ledger = readLedger(ledgerPath);
        JSONArray txns = ledger.getJSONArray("transactions");
        JSONArray kept = new JSONArray();
        int removed = 0;
        for (int i = 0; i < txns.length(); i++) {
            JSONObject t = txns.getJSONObject(i);
            if (id.equals(t.optString("id", ""))) removed++;
            else kept.put(t);
        }
        if (removed == 0)
            throw new IllegalArgumentException("no transaction with id: " + id);
        ledger.put("transactions", kept);
        writeLedger(ledgerPath, ledger);
        return removed;
    }

    // ---------- tally ----------

    static String money(double v) {
        boolean neg = v < 0; v = Math.abs(v);
        String s;
        if (v >= 1e9) s = String.format(Locale.ROOT, "$%.1fB", v / 1e9);
        else if (v >= 1e6) s = String.format(Locale.ROOT, "$%.1fM", v / 1e6);
        else if (v >= 1e3) s = String.format(Locale.ROOT, "$%.0fK", v / 1e3);
        else s = String.format(Locale.ROOT, "$%.0f", v);
        return neg ? "-" + s : s;
    }

    static class Agg {
        String ticker, company, group, side;
        double total;
        TreeSet<String> persons = new TreeSet<>();
        int n;
    }

    static String renderTally(Path ledgerPath, LocalDate end, int days) throws IOException {
        LocalDate start = end.minusDays(days - 1);
        JSONObject ledger = readLedger(ledgerPath);
        JSONArray txns = ledger.getJSONArray("transactions");
        // group|side|ticker -> Agg, insertion-ordered per section later
        Map<String, Agg> map = new LinkedHashMap<>();
        for (int i = 0; i < txns.length(); i++) {
            JSONObject t = txns.getJSONObject(i);
            LocalDate d;
            try { d = LocalDate.parse(t.getString("digest_date")); }
            catch (Exception e) { continue; }
            if (d.isBefore(start) || d.isAfter(end)) continue;
            String key = t.getString("group") + "|" + t.getString("side") + "|"
                + t.getString("ticker").toUpperCase(Locale.ROOT);
            Agg a = map.get(key);
            if (a == null) {
                a = new Agg();
                a.group = t.getString("group"); a.side = t.getString("side");
                a.ticker = t.getString("ticker").toUpperCase(Locale.ROOT);
                a.company = t.getString("company");
                map.put(key, a);
            }
            a.total += t.getDouble("value_usd");
            a.persons.add(t.getString("person"));
            a.n++;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83D\uDCCA ").append(days).append("-day tally (")
          .append(start.format(fmt)).append(" \u2192 ").append(end.format(fmt)).append(")\n");
        String[][] sections = {
            {"insider", "buy", "Insider buys"},
            {"insider", "sell", "Insider sells"},
            {"politician", "buy", "Politician buys"},
            {"politician", "sell", "Politician sells"},
        };
        boolean any = false;
        List<String> multiBuy = new ArrayList<>(), multiSell = new ArrayList<>();
        for (String[] sec : sections) {
            List<Agg> list = new ArrayList<>();
            for (Agg a : map.values())
                if (a.group.equals(sec[0]) && a.side.equals(sec[1])) list.add(a);
            if (list.isEmpty()) continue;
            any = true;
            list.sort((x, y) -> Double.compare(y.total, x.total));
            boolean approx = sec[0].equals("politician");
            sb.append("**").append(sec[2]);
            if (approx) sb.append("** (~range midpoints)");
            else sb.append(":**");
            if (approx) sb.append(":");
            sb.append(" ");
            int shown = 0;
            for (Agg a : list) {
                if (shown >= 5) break;
                if (shown > 0) sb.append(" \u00B7 ");
                sb.append(a.ticker).append(" ").append(money(a.total))
                  .append(" (").append(a.persons.size())
                  .append(a.persons.size() == 1 ? (a.side.equals("buy") ? " buyer)" : " seller)")
                                               : (a.side.equals("buy") ? " buyers)" : " sellers)"));
                shown++;
            }
            sb.append("\n");
            for (Agg a : list)
                if (a.persons.size() >= 2)
                    (a.side.equals("buy") ? multiBuy : multiSell).add(
                        a.ticker + " (" + String.join(", ", a.persons) + ")");
        }
        if (!any) {
            sb.append("No transactions in this window yet.\n");
            return sb.toString();
        }
        if (!multiBuy.isEmpty())
            sb.append("\uD83D\uDD01 Multi-buyer: ").append(String.join(" \u00B7 ", multiBuy)).append("\n");
        if (!multiSell.isEmpty())
            sb.append("\uD83D\uDD01 Multi-seller: ").append(String.join(" \u00B7 ", multiSell)).append("\n");
        return sb.toString();
    }

    // ---------- self-test ----------

    static int failures = 0;

    static void check(String name, boolean cond) {
        if (cond) System.out.println("  PASS " + name);
        else { System.out.println("  FAIL " + name); failures++; }
    }

    static JSONObject txn(String digestDate, String group, String side, String ticker,
                          String person, double valueUsd) {
        JSONObject t = new JSONObject();
        t.put("digest_date", digestDate); t.put("group", group); t.put("side", side);
        t.put("ticker", ticker); t.put("company", ticker + " Inc.");
        t.put("person", person); t.put("role", "Director"); t.put("party", "");
        t.put("shares", 1000); t.put("price", valueUsd / 1000);
        t.put("value_usd", valueUsd); t.put("value_range", ""); t.put("value_approx", false);
        t.put("trade_date", "2026-10-01"); t.put("filed_date", "2026-10-03");
        t.put("is_10b51", JSONObject.NULL); t.put("source", "https://example.com");
        return t;
    }

    static void selfTest() throws Exception {
        System.out.println("InsiderTally --self-test");
        Path tmp = Files.createTempDirectory("tally-test");
        Path lp = tmp.resolve("tally.json");

        // 1. add + dedupe
        JSONArray b1 = new JSONArray()
            .put(txn("2026-10-07", "insider", "buy", "LEN", "Berkshire Hathaway Inc", 192634433))
            .put(txn("2026-10-07", "insider", "buy", "LEN", "Berkshire Hathaway Inc", 192634433)); // dup
        int[] r = addBatch(lp, b1);
        check("add-twice-dedupes", r[0] == 1 && r[1] == 1);

        // 2. aggregation sums across digests
        JSONArray b2 = new JSONArray()
            .put(txn("2026-10-05", "insider", "buy", "LEN", "Berkshire Hathaway Inc", 136382789));
        // NOTE: different trade_date so it counts separately (see txn(): trade_date is fixed
        // "2026-10-01" for both — dedupe uses trade_date+shares, so bump shares via value):
        JSONObject t2 = txn("2026-10-05", "insider", "buy", "LEN", "Berkshire Hathaway Inc", 136382789);
        t2.put("trade_date", "2026-09-24");
        b2 = new JSONArray().put(t2);
        r = addBatch(lp, b2);
        check("second-tranche-added", r[0] == 1);
        String out = renderTally(lp, LocalDate.parse("2026-10-07"), 30);
        check("tally-sums-len", out.contains("LEN $329.0M"));

        // 3. window exclusion
        JSONArray b3 = new JSONArray()
            .put(txn("2026-08-01", "insider", "sell", "OLD", "Some Person", 999999999));
        addBatch(lp, b3);
        out = renderTally(lp, LocalDate.parse("2026-10-07"), 30);
        check("old-txn-excluded", !out.contains("OLD"));

        // 4. multi-actor highlight
        JSONArray b4 = new JSONArray()
            .put(txn("2026-10-06", "insider", "sell", "CRWD", "Alice", 1000000))
            .put(txn("2026-10-06", "insider", "sell", "CRWD", "Bob", 2000000));
        // distinct trade_dates to avoid dedupe collision
        b4.getJSONObject(1).put("trade_date", "2026-10-02");
        addBatch(lp, b4);
        out = renderTally(lp, LocalDate.parse("2026-10-07"), 30);
        check("multi-seller-line", out.contains("Multi-seller: CRWD (Alice, Bob)"));

        // 5. politician approx labeling
        JSONObject p = txn("2026-10-07", "politician", "buy", "JNJ", "Rep. X", 8000);
        p.put("shares", JSONObject.NULL); p.put("price", JSONObject.NULL);
        p.put("value_range", "$1,001-$15,000"); p.put("value_approx", true); p.put("party", "D");
        addBatch(lp, new JSONArray().put(p));
        out = renderTally(lp, LocalDate.parse("2026-10-07"), 30);
        check("politician-approx-label", out.contains("Politician buys** (~range midpoints)"));

        // 6. invalid batch fails fast
        JSONObject bad = txn("2026-10-07", "insider", "buy", "ZZZ", "X", 100);
        bad.remove("ticker");
        boolean threw = false;
        try { addBatch(lp, new JSONArray().put(bad)); } catch (IllegalArgumentException e) { threw = true; }
        check("invalid-batch-fails-fast", threw);

        // 7. empty window message
        Path lp2 = tmp.resolve("empty.json");
        out = renderTally(lp2, LocalDate.parse("2026-10-07"), 30);
        check("empty-window-message", out.contains("No transactions in this window"));

        // 8. remove-by-id
        JSONObject r8 = txn("2026-10-07", "insider", "sell", "RMV", "Gone Person", 500000);
        r8.put("trade_date", "2026-10-03");
        addBatch(lp, new JSONArray().put(r8));
        String rid = "insider|RMV|Gone Person|sell|2026-10-03|1000.0";
        int nrm = removeById(lp, rid);
        check("remove-by-id-removes", nrm == 1);
        out = renderTally(lp, LocalDate.parse("2026-10-07"), 30);
        check("removed-txn-gone", !out.contains("RMV"));
        boolean threw2 = false;
        try { removeById(lp, rid); } catch (IllegalArgumentException e) { threw2 = true; }
        check("remove-missing-fails-fast", threw2);

        System.out.println(failures == 0 ? "SELF-TEST GREEN (" + 8 + " groups)" : "SELF-TEST RED: " + failures);
        if (failures > 0) System.exit(1);
    }

    // ---------- main ----------

    static void usage() {
        System.out.println("usage: InsiderTally.java --add-txn <batch.json> [--ledger <p>]");
        System.out.println("                         --remove-by-id <dedupe-id> [--ledger <p>]");
        System.out.println("                         --tally <YYYY-MM-DD> [--days N] [--ledger <p>]");
        System.out.println("                         --self-test");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        Path ledger = defaultLedger();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--ledger") && i + 1 < args.length) ledger = Path.of(args[++i]);
            else rest.add(args[i]);
        }
        if (rest.isEmpty()) usage();
        switch (rest.get(0)) {
            case "--self-test" -> selfTest();
            case "--add-txn" -> {
                if (rest.size() < 2) usage();
                JSONArray batch = new JSONArray(
                    Files.readString(Path.of(rest.get(1)), StandardCharsets.UTF_8));
                int[] res = addBatch(ledger, batch);
                System.out.println("added=" + res[0] + " skipped=" + res[1]);
            }
            case "--remove-by-id" -> {
                if (rest.size() < 2) usage();
                int n = removeById(ledger, rest.get(1));
                System.out.println("removed=" + n);
            }
            case "--tally" -> {
                if (rest.size() < 2) usage();
                int days = 30;
                for (int i = 2; i < rest.size(); i++)
                    if (rest.get(i).equals("--days") && i + 1 < rest.size()) days = Integer.parseInt(rest.get(++i));
                System.out.print(renderTally(ledger, LocalDate.parse(rest.get(1)), days));
            }
            default -> usage();
        }
    }
}
