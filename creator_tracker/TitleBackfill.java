import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OPTION 2 fallback for the Parkev backfill (YouTube timedtext HTTP 429).
 *
 * <p>Walks worklist.tsv newest-first and assigns each candidate ticker its
 * newest video. Tickers already in captured.txt are skipped (they carry real,
 * digest-verified values). No values are derived from titles: the scan on
 * 2026-09-27 showed no title states one of Parkev's values numerically
 * (dollar amounts in titles are market prices, revenues, or backlogs), so
 * every entry keeps target_value=null — coverage only, never an invented
 * number.
 *
 * <p>Writes title_entries.json for {@code java AppendEntries.java
 * title_entries.json --no-capture}. Entries carry source="title-derived ..."
 * and status="title-derived" (kept out of hit-rate/diff/creator-values
 * consumers until a transcript-verified pass upgrades them in place).
 * Tickers are deliberately left OUT of captured.txt so the future
 * transcript pass can still upgrade them.
 *
 * <p>Run with the working directory set to ~/workspace/creator_tracker.
 */
public class TitleBackfill {

    /** Same alias the value consumers use (BuildCreatorValues, diff pipeline). */
    static String alias(String t) {
        return "GOOGL".equals(t) ? "GOOG" : t;
    }

    static String norm(String t) {
        return alias(t.strip().toUpperCase(java.util.Locale.ROOT));
    }

    /** JSON string escaping matching MiniJson's ensure_ascii style. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (cp < 0x20 || cp > 0x7E) {
                        if (cp > 0xFFFF) {
                            sb.append(String.format("\\u%04x\\u%04x",
                                    Character.highSurrogate(cp), Character.lowSurrogate(cp)));
                        } else {
                            sb.append(String.format("\\u%04x", cp));
                        }
                    } else {
                        sb.append((char) cp);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        Set<String> captured = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Path.of("captured.txt"), StandardCharsets.UTF_8)) {
            String t = norm(line);
            if (!t.isEmpty()) captured.add(t);
        }

        List<String> rows = Files.readAllLines(Path.of("worklist.tsv"), StandardCharsets.UTF_8);
        // ticker -> {vid, title, rowNo}
        Map<String, String[]> newest = new LinkedHashMap<>();
        int rowNo = 0;
        for (String row : rows) {
            rowNo++;
            if (row.startsWith("﻿")) row = row.substring(1); // BOM, first row only
            String[] cols = row.split("\t", -1);
            if (cols.length < 3) continue;
            String vid = cols[0].strip();
            String title = cols[1];
            for (String c : cols[2].split(",")) {
                String t = norm(c);
                if (t.isEmpty() || captured.contains(t) || newest.containsKey(t)) continue;
                newest.put(t, new String[]{vid, title, String.valueOf(rowNo)});
            }
        }

        int total = rows.size();
        StringBuilder out = new StringBuilder("[\n");
        boolean first = true;
        for (var en : newest.entrySet()) {
            String t = en.getKey();
            String vid = en.getValue()[0];
            String title = en.getValue()[1];
            String pos = en.getValue()[2];
            String notes = "OPTION 2 title-derived placeholder (YouTube timedtext HTTP 429, "
                    + "retry 2026-09-27). Newest video mentioning " + t + " in the 2026-09-27 "
                    + "channel listing (position " + pos + " of " + total + ", newest-first). "
                    + "The title states no value \u2014 coverage only, NOT a creator-stated "
                    + "value. To be replaced by a transcript-verified entry when throttling lifts.";
            if (!first) out.append(",\n");
            first = false;
            out.append("  {\"ticker\": ").append(esc(t))
               .append(", \"call_date\": null")
               .append(", \"video_title\": ").append(esc(title))
               .append(", \"video_url\": ").append(esc("https://www.youtube.com/watch?v=" + vid))
               .append(", \"target_type\": null")
               .append(", \"target_value\": null")
               .append(", \"price_at_call\": null")
               .append(", \"currency\": \"USD\"")
               .append(", \"notes\": ").append(esc(notes))
               .append(", \"source\": \"title-derived (OPTION 2 fallback, 2026-09-27 retry)\"")
               .append(", \"status\": \"title-derived\"}");
        }
        out.append("\n]\n");
        Files.writeString(Path.of("title_entries.json"), out.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote title_entries.json: " + newest.size()
                + " tickers, " + captured.size() + " already captured, "
                + total + " videos scanned");
    }
}
