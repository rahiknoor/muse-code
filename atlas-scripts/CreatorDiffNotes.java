import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the Atlas "Creator vs holdings diff" note from the canonical
 * all-creators diff artifact (Rahik 2026-10-03, option 1: the weekly
 * creator-vs-holdings diff lives permanently in the vault, not just in chat).
 *
 * <p>Reads: ~/workspace/goals/daily-youtube-stock-video-digest/hidden_files/creator_holdings_diff_all.json
 * <p>Writes: ~/workspace/atlas/07 🎙️ Creators/Creator vs holdings diff.md
 *
 * <p>Run with: LC_ALL=C.UTF-8 java --class-path ~/workspace/java-shared/classes
 * ~/workspace/atlas-scripts/CreatorDiffNotes.java
 */
public class CreatorDiffNotes {

    // Minimal JSON parser: enough for the diff artifact (objects, arrays,
    // strings, numbers, booleans, null). Mirrors the one in RefreshValuations.
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        static Object parse(String s) { return new Json(s).value(); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            int j = i;
            while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String lit = s.substring(i, j); i = j;
            if (lit.matches("-?\\d+")) {
                try { return Long.parseLong(lit); } catch (NumberFormatException e) { /* fall through */ }
            }
            return Double.parseDouble(lit);
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws(); i++; // :
                m.put(k, value());
                ws();
                char c = s.charAt(i); i++;
                if (c == '}') break;
            }
            return m;
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i); i++;
                if (c == ']') break;
            }
            return l;
        }
        String str() {
            StringBuilder b = new StringBuilder();
            i++; // "
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    b.append(switch (e) {
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case 'u' -> {
                            String hex = s.substring(i, i + 4); i += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        default -> e;
                    });
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }
    }

    static String money(Object v) {
        if (!(v instanceof Number)) return "—";
        double d = ((Number) v).doubleValue();
        String s = String.format("%,.2f", d);
        return "$" + s;
    }

    @SuppressWarnings("unchecked")
    static void run(Path base) throws Exception {
        Path artifact = base.resolve(
                "workspace/goals/daily-youtube-stock-video-digest/hidden_files/creator_holdings_diff_all.json");
        Path stocksDir = base.resolve("workspace/atlas/01 📈 Stocks");
        Path out = base.resolve("workspace/atlas/07 🎙️ Creators/Creator vs holdings diff.md");

        Map<String, Object> diff = (Map<String, Object>)
                Json.parse(Files.readString(artifact, StandardCharsets.UTF_8));
        String runDate = String.valueOf(diff.getOrDefault("run_date", "n/a"));
        Map<String, Object> creators = (Map<String, Object>) diff.get("creators");
        List<Object> youOnly = (List<Object>) diff.get("you_only");

        // Followed creators first (Rahik's priority order), then the rest alphabetically.
        List<String> priority = List.of("Parkev Tatevosian", "Jeremy Lefebvre",
                "Couch Investor", "Bottom Finders");
        List<String> names = new ArrayList<>(creators.keySet());
        names.sort((a, b) -> {
            int pa = priority.indexOf(a), pb = priority.indexOf(b);
            if (pa >= 0 || pb >= 0) {
                return Integer.compare(pa < 0 ? 99 : pa, pb < 0 ? 99 : pb);
            }
            return a.compareToIgnoreCase(b);
        });

        StringBuilder b = new StringBuilder();
        b.append("# Creator vs holdings diff\n\n");
        b.append("Every tracked YouTuber's latest stated call per ticker (intrinsic value, ")
         .append("fair value, price target, or buy price with a stated number), diffed against ")
         .append("Rahik's holdings. Regenerated by the weekly all-creators diff job ")
         .append("(Saturdays ~11:46 AM ET); run date below.\n\n");
        b.append("- Run date: ").append(runDate).append("\n");
        b.append("- Source: `~/workspace/goals/daily-youtube-stock-video-digest/hidden_files/creator_holdings_diff_all.json`\n");
        b.append("- NEW = call stated within the last 7 days\n\n");

        for (String name : names) {
            Map<String, Object> sec = (Map<String, Object>) creators.get(name);
            b.append("## ").append(name).append("\n\n");
            boolean any = false;
            List<Object> inBoth = (List<Object>) sec.get("in_both");
            if (inBoth != null && !inBoth.isEmpty()) {
                any = true;
                b.append("### In both (you own it, they called it)\n\n");
                b.append("| Ticker | Value | Type | Date |\n|---|---:|---|---|\n");
                for (Object o : inBoth) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    String t = String.valueOf(e.get("ticker"));
                    String link = Files.exists(stocksDir.resolve(t + ".md"))
                            ? "[[" + t + "]]" : t;
                    b.append("| ").append(link).append(" | ")
                     .append(money(e.get("value"))).append(" | ")
                     .append(String.valueOf(e.get("value_type")).replace('_', ' ')).append(" | ")
                     .append(e.get("call_date"))
                     .append(Boolean.TRUE.equals(e.get("new_this_week")) ? " NEW" : "")
                     .append(" |\n");
                }
                b.append("\n");
            }
            List<Object> only = (List<Object>) sec.get("creator_only");
            if (only != null && !only.isEmpty()) {
                any = true;
                b.append("### Creator-only (they called it, you don't own it)\n\n");
                b.append("| Ticker | Company | Value | Type | Date |\n|---|---|---:|---|---|\n");
                for (Object o : only) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    b.append("| ").append(e.get("ticker")).append(" | ")
                     .append(e.getOrDefault("company", "—")).append(" | ")
                     .append(money(e.get("value"))).append(" | ")
                     .append(String.valueOf(e.get("value_type")).replace('_', ' ')).append(" | ")
                     .append(e.get("call_date"))
                     .append(Boolean.TRUE.equals(e.get("new_this_week")) ? " NEW" : "")
                     .append(" |\n");
                }
                b.append("\n");
            }
            List<Object> unsc = (List<Object>) sec.get("not_scoreable");
            if (unsc != null && !unsc.isEmpty()) {
                any = true;
                b.append("### Not scoreable (buy call, no stated price)\n\n");
                for (Object o : unsc) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    b.append("- ").append(e.get("ticker")).append(" (")
                     .append(e.get("call_date")).append("): ")
                     .append(e.getOrDefault("reason", "no stated price")).append("\n");
                }
                b.append("\n");
            }
            if (!any) {
                b.append("_No stated valuation-type calls recorded yet._\n\n");
            }
        }

        b.append("## You-only (no tracked creator has called these)\n\n");
        List<String> linked = new ArrayList<>();
        for (Object o : youOnly) {
            String t = String.valueOf(o);
            linked.add(Files.exists(stocksDir.resolve(t + ".md")) ? "[[" + t + "]]" : t);
        }
        b.append(String.join(" · ", linked)).append("\n");

        Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + names.size() + " creators, "
                + youOnly.size() + " you-only)");
    }

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getenv("HOME"));
        run(base);
    }
}
