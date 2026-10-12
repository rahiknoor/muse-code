import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit write guard (Rahik, 2026-09-27).
 *
 * <p>Contract: background jobs NEVER write blessed value columns directly. A job that
 * wants a value changed FILES A PROPOSAL. The auditor (Muse) reviews each proposal
 * with a cross-check against primary sources and methodology, then approves or
 * rejects. Every decision lands in the changelog. RebuildAll runs {@code verify}
 * first and fails loudly if any blessed value drifted without an approved proposal,
 * so a silent rewrite can never reach the workbook, CSVs, Atlas, or dashboard.
 *
 * <p>Columns (generic: a new value column plugs in by adding a reader + blessed
 * entry, no rewrite of this mechanism):
 * <ul>
 *   <li>ours       - workbook_data.json companies[].fixed (+ assumptions; a DCF value
 *                    without its assumptions is rejected at file time)</li>
 *   <li>notebooklm - workbook_data.json companies[].notebooklm (grounded:
 *                    filings/history only, neutral instruction)</li>
 *   <li>forward    - workbook_data.json companies[].notebooklm_forward (NotebookLM
 *                    forward tilt: grounded base plus a MODEST forward-looking
 *                    tilt from management guidance / product-cycle momentum —
 *                    a little bit of story, not a narrative valuation. Its own
 *                    column; never blended with the grounded value)</li>
 *   <li>creator    - creator_values.json latest[].value (designated writer: the digest
 *                    aggregation; direct approval writes are refused - creator values
 *                    only ever come from logged tracker calls)</li>
 *   <li>analyst    - analyst_targets.json [ticker].target</li>
 *   <li>nlm_speculation - blessed.json columns.nlm_speculation (NotebookLM's OWN
 *                    speculation price, derived inside the 'DCF Methodology Check'
 *                    notebook from its grounded DCF + outside median + its own
 *                    nudge — NEVER computed by Muse's code. Bless-only column:
 *                    approval writes blessed.json and publishDownstream rebuilds
 *                    speculation_price.json from it via SpeculationPrice.java.
 *                    Its own column; never blended with the grounded value)</li>
 * </ul>
 *
 * <p>Provenance trail (idea #3, approved 2026-09-29): proposals filed with
 * {@code --provenance '<json>'} carry a structured end-to-end trail into
 * blessed.json — executor task id, auditor task id, note URL + title,
 * timestamps — so any number is traceable in ONE lookup instead of digging
 * through run logs. Supported shape:
 * <pre>
 *   {"executor_task_id":"browser-task:...","auditor_task_id":"browser-task:...",
 *    "note_url":"https://notebook.google.com/notebook/<id>",
 *    "note_title":"[T] DCF forward tilt",
 *    "executor_ts":"...","auditor_ts":"...","run_date":"2026-09-29"}
 * </pre>
 * {@code file} validates it parses as a JSON object (rejects otherwise) and
 * stores it on the proposal. {@code blessOne} copies it onto the blessed entry
 * as {@code provenance} together with {@code proposal_id}. Entries blessed
 * before this feature simply have no provenance (backward compatible).
 * The {@code verify} step only compares {@code value} (and assumptions for
 * ours), so the added field cannot introduce drift.
 * {@code Provenance.java --show} prints the one-lookup trail.
 *
 * <p>Designated writers (the only processes allowed to change a column outside the
 * proposal flow) must re-bless after legitimate writes:
 * <ul>
 *   <li>creator  : creator_tracker/BuildCreatorValues.java (digest STEP 5b + RebuildAll)</li>
 *   <li>analyst  : whichever job refreshes analyst_targets.json</li>
 *   <li>notebooklm: the nightly re-ask pipeline, but ONLY for null -&gt; value fills;
 *                    changing an already-verified value requires a proposal</li>
 *   <li>forward  : the nightly re-ask forward-tilt phase, but ONLY for null -&gt;
 *                    value fills; changing an already-verified value requires a proposal</li>
 *   <li>ours     : nobody - only approved proposals or Rahik's explicit instruction</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java Proposals.java init [--by NAME]
 *   java Proposals.java verify
 *   java Proposals.java file --column C --ticker T --new V --rationale R --by WHO [--sources S] [--assumptions JSON] [--provenance JSON]
 *   java Proposals.java list [--status pending|approved|rejected|all]
 *   java Proposals.java show ID
 *   java Proposals.java approve ID --reason R
 *   java Proposals.java approve-batch --reason R   (approve all pending, publish once)
 *   java Proposals.java reject ID --reason R
 *   java Proposals.java rebless --column C [--ticker T] --by WHO
 *   java Proposals.java age [--json] [--mark] [--state PATH]
 * </pre>
 *
 * <p>Regression suite: {@code ProposalsSelfTest.java} (weekly sweep).
 *
 * <p>Proposal aging (idea: proposal-aging, 2026-09-30): a clock on the
 * proposal queue. {@code age} lists pending proposals by age bucket and
 * flags stale ones (thresholds in the AGE_* constants — the single source
 * of truth). Value proposals FLAG at 3 days unaudited and ESCALATE at 7;
 * voice-evidence (dated quotes, not decaying inputs) uses 14/30 and is
 * never approval-blocked. An escalated price-sensitive proposal is marked
 * needs-refresh and the approve path REFUSES it (see guardNotStale) — it
 * can never be blessed on stale inputs; the auditor re-files or rejects.
 */
public class Proposals {

    // ------------------------------------------------------------------
    // Paths. HOME comes from the environment, never the user.home property
    // (which returns /root on this box while $HOME is /home/hatch).
    // ------------------------------------------------------------------
    static String HOME;
    static Path ROOT, BLESSED, PDIR, PENDING, APPROVED, REJECTED, CHANGELOG;
    static Path WORKBOOK_DATA, CREATOR_VALUES, ANALYST_TARGETS, SPECULATION_PRICE;

    static final List<String> COLUMNS = List.of("ours", "notebooklm", "forward", "creator", "analyst", "nlm_speculation");

    /**
     * Master-record field holding each column's value. ours/notebooklm/forward
     * live in workbook_data.json company records; creator/analyst live in
     * their own backends.
     */
    static String masterField(String column) {
        switch (column) {
            case "ours": return "fixed";
            case "notebooklm": return "notebooklm";
            case "forward": return "notebooklm_forward";
            default: throw new IllegalArgumentException("no master field for column: " + column);
        }
    }
    static final double TOL = 0.005;

    // ------------------------------------------------------------------
    // Proposal aging (idea: proposal-aging, 2026-09-30).
    //
    // A clock on the proposal queue. Background jobs file value proposals
    // that wait for audit; nothing watched the waiting room, and a stale
    // proposal could be approved on old market inputs. The thresholds live
    // HERE and ONLY here: the --age reporter and the approve-path guard both
    // read these constants, so the checker and the enforcer can never drift
    // apart.
    //
    // Why these numbers:
    // - FLAG at 3 days: the nightly re-ask + morning review give a daily
    //   audit cadence. A proposal untouched after 3 daily windows is being
    //   ignored, not merely queued — nudge the waiting room (Stock Research).
    // - ESCALATE at 7 days: a full week of market sessions. price_cache
    //   refreshes daily and every value consumer (gap watch, comparison
    //   table, scoreboard) runs at least weekly; a value proposal older than
    //   that risks blessing inputs the market has already moved past.
    //   Price-sensitive proposals at this age are marked needs-refresh and
    //   can NEVER be approved on the stale inputs — the approve path refuses
    //   (see guardNotStale). The auditor re-files with fresh inputs (a new
    //   proposed_at) or rejects.
    // - Voice-evidence is NOT price-sensitive: it carries a dated QUOTE and
    //   the quote IS the datum — it does not decay. It still gets a nudge so
    //   the queue cannot grow silently, on lenient thresholds, and it is
    //   never approval-blocked.
    // ------------------------------------------------------------------
    static final int AGE_FLAG_DAYS = 3;
    static final int AGE_ESCALATE_DAYS = 7;
    static final int AGE_EVIDENCE_FLAG_DAYS = 14;
    static final int AGE_EVIDENCE_ESCALATE_DAYS = 30;
    /** Escalated proposals re-ping weekly while they stay stale. */
    static final long AGE_RENUDGE_DAYS = 7;

    /**
     * Value proposals (a new NUMBER for a blessed column) are market/filing-
     * input dependent — price-sensitive. Voice-evidence carries a dated quote
     * (the quote is the datum) and is exempt from staleness enforcement.
     */
    static boolean priceSensitive(J.O p) {
        return !"voice-evidence".equals(J.str(p.m.get("kind")));
    }

    /**
     * Whole elapsed days since the proposal was filed. Unparseable or missing
     * timestamps fail SAFE: treated as infinitely old, i.e. escalated.
     */
    static long ageDays(J.O p) {
        return ageDaysAt(p, Instant.now());
    }

    static long ageDaysAt(J.O p, Instant now) {
        String at = J.str(p.m.get("proposed_at"));
        if (at == null) return Long.MAX_VALUE;
        try {
            long d = Duration.between(Instant.parse(at), now).toDays();
            return d < 0 ? 0 : d;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** "fresh" | "flag" | "escalate". Voice-evidence uses the lenient thresholds. */
    static String ageBucket(J.O p) {
        return ageBucketAt(p, Instant.now());
    }

    static String ageBucketAt(J.O p, Instant now) {
        long d = ageDaysAt(p, now);
        boolean evidence = !priceSensitive(p);
        int flag = evidence ? AGE_EVIDENCE_FLAG_DAYS : AGE_FLAG_DAYS;
        int esc = evidence ? AGE_EVIDENCE_ESCALATE_DAYS : AGE_ESCALATE_DAYS;
        if (d >= esc) return "escalate";
        if (d >= flag) return "flag";
        return "fresh";
    }

    /**
     * True when this proposal must never be approved on its current inputs:
     * price-sensitive AND past the escalate threshold. Voice-evidence is
     * never stale for approval (dated quotes don't decay).
     */
    static boolean staleForApproval(J.O p) {
        return staleForApprovalAt(p, Instant.now());
    }

    static boolean staleForApprovalAt(J.O p, Instant now) {
        return priceSensitive(p) && "escalate".equals(ageBucketAt(p, now));
    }

    /**
     * Approve-path guard (fail-closed). Called by cmdApprove and
     * cmdApproveBatch BEFORE any store write, backup, or bless. A stale
     * price-sensitive proposal is refused loudly: the attempt is recorded in
     * the changelog (an audit event) and an IllegalArgumentException aborts
     * the run (exit 2). The proposal stays pending; the auditor re-files with
     * fresh inputs or rejects it.
     */
    static void guardNotStale(J.O p, String id) throws IOException {
        if (!staleForApproval(p)) return;
        long d = ageDays(p);
        String msg = "REFUSED: " + id + " is " + d + " days old (escalated) -- price-sensitive proposals "
                + "cannot be approved on stale inputs (aging rule: escalate at "
                + AGE_ESCALATE_DAYS + "d). Re-file with fresh inputs, or reject it.";
        changelog(id + " APPROVE REFUSED by muse-audit\n- stale: " + d + " days unaudited (escalated)\n"
                + "- column/ticker: " + J.str(p.m.get("column")) + "/" + J.str(p.m.get("ticker")) + "\n"
                + "- action required: re-file with fresh inputs, or reject");
        System.out.println(msg);
        throw new IllegalArgumentException("stale proposal " + id + " (" + d + "d unaudited) -- re-file with fresh inputs");
    }

    static void initPaths() {
        HOME = System.getenv("HOME");
        if (HOME == null || HOME.isEmpty()) {
            throw new IllegalStateException("$HOME is not set");
        }
        ROOT = Paths.get(HOME, "workspace", "dcf_csv");
        BLESSED = ROOT.resolve("blessed.json");
        PDIR = ROOT.resolve("proposals");
        PENDING = PDIR.resolve("pending");
        APPROVED = PDIR.resolve("approved");
        REJECTED = PDIR.resolve("rejected");
        CHANGELOG = PDIR.resolve("changelog.md");
        WORKBOOK_DATA = Paths.get(HOME, "workspace", "your_files", "dcf-valuations", ".src", "workbook_data.json");
        CREATOR_VALUES = ROOT.resolve("creator_values.json");
        ANALYST_TARGETS = ROOT.resolve("analyst_targets.json");
        SPECULATION_PRICE = ROOT.resolve("speculation_price.json");
    }

    // ------------------------------------------------------------------
    // Minimal JSON: parser + Python-compatible writer (indent=1).
    // Mirrors the embedded parser in RebuildAll.java; kept self-contained
    // so this file stays readable on its own.
    // ------------------------------------------------------------------
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
            if (p.i != t.length()) {
                throw new IllegalArgumentException("trailing characters at " + p.i);
            }
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
                StringBuilder sb = new StringBuilder(); i++; // opening quote
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
                                int cp = Integer.parseInt(t.substring(i, i + 4), 16); i += 4;
                                if (Character.isHighSurrogate((char) cp) && i + 6 <= t.length()
                                        && t.charAt(i) == '\\' && t.charAt(i + 1) == 'u') {
                                    int lo = Integer.parseInt(t.substring(i + 2, i + 6), 16); i += 6;
                                    sb.appendCodePoint(Character.toCodePoint((char) cp, (char) lo));
                                } else {
                                    sb.append((char) cp);
                                }
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) i++;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try {
                    return new N(Double.parseDouble(lit), lit);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad number: " + lit);
                }
            }
        }

        static String dumps(V v) {
            StringBuilder sb = new StringBuilder();
            dump(v, sb, 0);
            return sb.toString();
        }

        static void dump(V v, StringBuilder sb, int lvl) {
            if (v instanceof Nil) sb.append("null");
            else if (v instanceof B) sb.append(((B) v).b ? "true" : "false");
            else if (v instanceof N) sb.append(numLit((N) v));
            else if (v instanceof S) quote(((S) v).s, sb);
            else if (v instanceof O) {
                O o = (O) v;
                if (o.m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                String pad = " ".repeat(lvl + 1);
                boolean first = true;
                for (Map.Entry<String, V> e : o.m.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append(pad); quote(e.getKey(), sb); sb.append(": ");
                    dump(e.getValue(), sb, lvl + 1);
                }
                sb.append("\n").append(" ".repeat(lvl)).append("}");
            } else if (v instanceof A) {
                A a = (A) v;
                if (a.l.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                String pad = " ".repeat(lvl + 1);
                boolean first = true;
                for (V x : a.l) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append(pad); dump(x, sb, lvl + 1);
                }
                sb.append("\n").append(" ".repeat(lvl)).append("]");
            }
        }

        /** Keep the original literal when it round-trips (7695.0 stays 7695.0). */
        static String numLit(N n) {
            try {
                if (Double.parseDouble(n.lit) == n.d) return n.lit;
            } catch (Exception ignored) { }
            if (n.d == Math.rint(n.d) && Math.abs(n.d) < 1e15) return Long.toString((long) n.d) + ".0";
            return Double.toString(n.d);
        }

        static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        // Raw UTF-8 for everything else (matches Python ensure_ascii=False,
                        // which is how workbook_data.json is written). Only control chars
                        // below 0x20 need escaping.
                        if (cp < 0x20) sb.append(String.format("\\u%04x", cp));
                        else sb.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
            sb.append('"');
        }

        static Double num(V v) { return v instanceof N ? ((N) v).d : null; }
        static String str(V v) { return v instanceof S ? ((S) v).s : null; }
        static O obj(V v) { return v instanceof O ? (O) v : null; }
    }

    // ------------------------------------------------------------------
    // Live store readers: the current value per ticker per column.
    // ------------------------------------------------------------------
    static class LiveVal {
        Double v;      // null = missing/blank
        J.V assumptions; // ours only
    }

    static J.O readJsonFile(Path p) throws IOException {
        return (J.O) J.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    static Map<String, LiveVal> readLive(String column) throws IOException {
        Map<String, LiveVal> m = new LinkedHashMap<>();
        switch (column) {
            case "ours":
            case "notebooklm":
            case "forward": {
                J.O root = readJsonFile(WORKBOOK_DATA);
                J.A companies = (J.A) root.m.get("companies");
                for (J.V c : companies.l) {
                    J.O co = (J.O) c;
                    String t = ((J.S) co.m.get("ticker")).s;
                    LiveVal lv = new LiveVal();
                    lv.v = J.num(co.m.get(masterField(column)));
                    if (column.equals("ours")) lv.assumptions = co.m.get("assumptions");
                    m.put(t, lv);
                }
                break;
            }
            case "creator": {
                J.O root = readJsonFile(CREATOR_VALUES);
                J.O latest = J.obj(root.m.get("latest"));
                if (latest != null) {
                    for (Map.Entry<String, J.V> e : latest.m.entrySet()) {
                        J.O en = J.obj(e.getValue());
                        LiveVal lv = new LiveVal();
                        lv.v = en == null ? null : J.num(en.m.get("value"));
                        m.put(e.getKey(), lv);
                    }
                }
                break;
            }
            case "analyst": {
                J.O root = readJsonFile(ANALYST_TARGETS);
                for (Map.Entry<String, J.V> e : root.m.entrySet()) {
                    J.O en = J.obj(e.getValue());
                    LiveVal lv = new LiveVal();
                    lv.v = en == null ? null : J.num(en.m.get("target"));
                    m.put(e.getKey(), lv);
                }
                break;
            }
            case "nlm_speculation": {
                // Live store is the derived file: SpeculationPrice.java rebuilds
                // speculation_price.json from the blessed nlm_speculation column.
                // verify compares it against blessed to catch a stale/missing rebuild.
                J.O root = readJsonFile(SPECULATION_PRICE);
                J.O tickers = J.obj(root.m.get("tickers"));
                if (tickers != null) {
                    for (Map.Entry<String, J.V> e : tickers.m.entrySet()) {
                        J.O en = J.obj(e.getValue());
                        LiveVal lv = new LiveVal();
                        lv.v = en == null ? null : J.num(en.m.get("nlm_speculation"));
                        m.put(e.getKey(), lv);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("unknown column: " + column);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // blessed.json access.
    // ------------------------------------------------------------------
    static J.O readBlessed() throws IOException {
        if (!Files.exists(BLESSED)) {
            throw new IllegalStateException("blessed.json not found - run: java Proposals.java init");
        }
        return readJsonFile(BLESSED);
    }

    static void writeBlessed(J.O blessed) throws IOException {
        Files.writeString(BLESSED, J.dumps(blessed) + "\n", StandardCharsets.UTF_8);
    }

    /** blessed entry for one ticker; null when never blessed. */
    static J.O blessedEntry(J.O blessed, String column, String ticker) {
        J.O cols = J.obj(blessed.m.get("columns"));
        if (cols == null) return null;
        J.O col = J.obj(cols.m.get(column));
        if (col == null) return null;
        J.O tickers = J.obj(col.m.get("tickers"));
        if (tickers == null) return null;
        return J.obj(tickers.m.get(ticker));
    }

    /**
     * Create the column skeleton + ticker entry when a brand-new column (e.g.
     * forward) is approved for the first time. This is the ONLY sanctioned
     * path for growing blessed.json's column set — background jobs never
     * hand-edit the file; the approval run creates the entry it is about to
     * bless. Existing columns/values are never touched.
     */
    static J.O ensureBlessedEntry(J.O blessed, String column, String ticker) throws IOException {
        J.O cols = J.obj(blessed.m.get("columns"));
        if (cols == null) throw new IllegalStateException("blessed.json has no columns object");
        J.O col = J.obj(cols.m.get(column));
        if (col == null) {
            col = new J.O();
            col.m.put("store", new J.S(storeDesc(column)));
            col.m.put("blessed_at", new J.S(nowIso()));
            col.m.put("tickers", new J.O());
            cols.m.put(column, col);
            changelog("blessed.json: created column skeleton '" + column + "' (" + storeDesc(column) + ")");
        }
        J.O tickers = J.obj(col.m.get("tickers"));
        if (tickers == null) {
            tickers = new J.O();
            col.m.put("tickers", tickers);
        }
        J.O entry = J.obj(tickers.m.get(ticker));
        if (entry == null) {
            entry = new J.O();
            tickers.m.put(ticker, entry);
        }
        return entry;
    }

    static String nowIso() {
        return Instant.now().toString();
    }

    static String todayEt() {
        return LocalDate.now(ZoneId.of("America/New_York")).toString();
    }

    static void ensureDirs() throws IOException {
        Files.createDirectories(PENDING);
        Files.createDirectories(APPROVED);
        Files.createDirectories(REJECTED);
    }

    static void changelog(String entry) throws IOException {
        ensureDirs();
        String line = "\n## " + nowIso() + "\n" + entry + "\n";
        Files.writeString(CHANGELOG, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String fmtVal(Double v) {
        return v == null ? "—" : (v == Math.rint(v) ? Long.toString(v.longValue()) : v.toString());
    }

    static String pctChange(Double oldV, Double newV) {
        if (oldV == null || newV == null || oldV == 0) return "n/a";
        double p = (newV - oldV) / Math.abs(oldV) * 100.0;
        return String.format("%+.1f%%", p);
    }

    // ------------------------------------------------------------------
    // Command: init - seed blessed.json from the live stores.
    // ------------------------------------------------------------------
    static void cmdInit(Map<String, String> args) throws IOException {
        String by = args.getOrDefault("--by", "rahik");
        if (Files.exists(BLESSED)) {
            Path bak = ROOT.resolve("blessed.json.bak-" + todayEt());
            Files.copy(BLESSED, bak, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("backed up existing blessed.json -> " + bak.getFileName());
        }
        J.O blessed = new J.O();
        blessed.m.put("note", new J.S("Blessed (approved) values per value column. Background jobs never write "
                + "these directly - they file proposals via Proposals.java. RebuildAll verifies before every run."));
        J.O columns = new J.O();
        blessed.m.put("columns", columns);
        String at = nowIso();
        for (String col : COLUMNS) {
            Map<String, LiveVal> live = readLive(col);
            J.O colObj = new J.O();
            colObj.m.put("store", new J.S(storeDesc(col)));
            colObj.m.put("blessed_at", new J.S(at));
            J.O tickers = new J.O();
            colObj.m.put("tickers", tickers);
            for (Map.Entry<String, LiveVal> e : live.entrySet()) {
                J.O entry = new J.O();
                entry.m.put("value", e.getValue().v == null ? new J.Nil() : new J.N(e.getValue().v, e.getValue().v.toString()));
                if (col.equals("ours") && e.getValue().assumptions != null) {
                    entry.m.put("assumptions", e.getValue().assumptions);
                }
                entry.m.put("blessed_at", new J.S(at));
                entry.m.put("blessed_by", new J.S(by));
                tickers.m.put(e.getKey(), entry);
            }
            columns.m.put(col, colObj);
            System.out.println("  " + col + ": " + live.size() + " tickers blessed by " + by);
        }
        writeBlessed(blessed);
        changelog("blessed.json initialized by " + by + " (" + COLUMNS.size() + " columns).");
        System.out.println("wrote " + BLESSED);
    }

    static String storeDesc(String column) {
        switch (column) {
            case "ours": return "workbook_data.json companies[].fixed (+assumptions)";
            case "notebooklm": return "workbook_data.json companies[].notebooklm";
            case "forward": return "workbook_data.json companies[].notebooklm_forward";
            case "creator": return "creator_values.json latest[].value";
            case "analyst": return "analyst_targets.json [ticker].target";
            case "nlm_speculation": return "speculation_price.json tickers[].nlm_speculation (derived from blessed by SpeculationPrice.java)";
            default: return "?";
        }
    }

    // ------------------------------------------------------------------
    // Command: verify - fail loudly (exit 1) on any drift vs blessed.
    // This is RebuildAll's pre-gate: a silent rewrite can never publish.
    // ------------------------------------------------------------------
    static void cmdVerify() throws IOException {
        J.O blessed = readBlessed();
        List<String> drifts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String col : COLUMNS) {
            Map<String, LiveVal> live = readLive(col);
            J.O colObj = J.obj(((J.O) blessed.m.get("columns")).m.get(col));
            if (colObj == null) {
                // A brand-new column (e.g. forward) has no blessed section yet.
                // That is only drift when live values already exist unblessed;
                // an empty new column is not a failure.
                boolean anyLive = live.values().stream().anyMatch(lv -> lv.v != null);
                if (anyLive) drifts.add(col + ": live values exist with no blessed section");
                continue;
            }
            J.O tickers = J.obj(colObj.m.get("tickers"));
            for (Map.Entry<String, J.V> e : tickers.m.entrySet()) {
                String t = e.getKey();
                J.O entry = (J.O) e.getValue();
                Double blessedV = J.num(entry.m.get("value"));
                LiveVal lv = live.get(t);
                if (lv == null) {
                    drifts.add(col + "/" + t + ": blessed " + fmtVal(blessedV) + " but missing from live store");
                    continue;
                }
                if (!valEq(blessedV, lv.v)) {
                    drifts.add(col + "/" + t + ": blessed " + fmtVal(blessedV) + " vs live " + fmtVal(lv.v));
                }
                if (col.equals("ours")) {
                    J.V blessedA = entry.m.get("assumptions");
                    if (!assumptionsEq(blessedA, lv.assumptions)) {
                        drifts.add(col + "/" + t + ": ASSUMPTIONS drifted (value may match but inputs changed)");
                    }
                }
            }
            // Tickers live but never blessed: warn, don't fail (rebless picks them up).
            for (String t : live.keySet()) {
                if (!tickers.m.containsKey(t)) {
                    warnings.add(col + "/" + t + ": live but never blessed (run rebless)");
                }
            }
        }
        for (String w : warnings) System.out.println("  [warn] " + w);
        if (!drifts.isEmpty()) {
            System.out.println("BLESSED-GUARD FAIL: " + drifts.size() + " drift(s) without an approved proposal:");
            for (String d : drifts) System.out.println("  [drift] " + d);
            System.out.println("Refusing to continue. Investigate, then either file+approve a proposal or rebless.");
            System.exit(1);
        }
        System.out.println("blessed-guard: all columns match blessed snapshot (" + COLUMNS + ")");
    }

    static boolean valEq(Double a, Double b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Math.abs(a - b) <= TOL;
    }

    /** Field-wise compare of the assumptions blocks that feed a DCF value. */
    static boolean assumptionsEq(J.V a, J.V b) {
        if (a == b) return true;
        if (!(a instanceof J.O) || !(b instanceof J.V)) return false;
        J.O ao = (J.O) a, bo = (J.O) b;
        for (String k : List.of("kind", "base", "g", "w", "t", "shares", "netdebt", "src")) {
            J.V av = ao.m.get(k), bv = bo.m.get(k);
            if (av == null && bv == null) continue;
            if (av == null || bv == null) return false;
            Double an = J.num(av), bn = J.num(bv);
            if (an != null && bn != null) {
                if (Math.abs(an - bn) > 0.5) return false;
            } else {
                String as = J.str(av), bs = J.str(bv);
                if (as == null ? bs != null : !as.equals(bs)) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Command: file - a background job proposes a change. Never writes.
    // ------------------------------------------------------------------
    static void cmdFile(Map<String, String> args) throws IOException {
        String col = req(args, "--column"), ticker = req(args, "--ticker").toUpperCase();
        String by = req(args, "--by"), rationale = req(args, "--rationale");
        String sources = args.getOrDefault("--sources", "");
        if (!COLUMNS.contains(col)) throw new IllegalArgumentException("unknown column: " + col);
        double newV;
        try {
            newV = Double.parseDouble(req(args, "--new"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--new must be numeric");
        }
        J.V assumptions = null;
        if (col.equals("ours")) {
            // A DCF value without its assumptions is exactly the skew that bit us on
            // 2026-09-27 (fixed restored, base left behind). Require both, always.
            String a = args.get("--assumptions");
            if (a == null || a.isBlank()) {
                throw new IllegalArgumentException("--assumptions JSON is required for column 'ours'");
            }
            assumptions = J.parse(a);
        }
        // End-to-end provenance trail (idea #3, 2026-09-29): an optional structured
        // JSON object describing exactly how this number was produced — executor
        // task id, auditor task id, note URL + title, timestamps. Validated as a
        // JSON object here; blessOne copies it into the blessed entry so any
        // number is traceable in one lookup (Provenance.java --show).
        J.V provenance = null;
        String provRaw = args.get("--provenance");
        if (provRaw != null && !provRaw.isBlank()) {
            J.V parsed = J.parse(provRaw);
            if (!(parsed instanceof J.O)) {
                throw new IllegalArgumentException("--provenance must parse as a JSON object");
            }
            provenance = parsed;
        }
        Map<String, LiveVal> live = readLive(col);
        LiveVal lv = live.get(ticker);
        if (lv == null) throw new IllegalArgumentException("ticker " + ticker + " not in " + col + " store");
        J.O blessed = readBlessed();
        J.O entry = blessedEntry(blessed, col, ticker);
        Double oldV = entry == null ? null : J.num(entry.m.get("value"));
        if (oldV != null && Math.abs(oldV - newV) <= TOL) {
            System.out.println("no change vs blessed (" + fmtVal(oldV) + ") - nothing to file");
            return;
        }
        ensureDirs();
        String id = nextId();
        J.O p = new J.O();
        p.m.put("id", new J.S(id));
        p.m.put("column", new J.S(col));
        p.m.put("ticker", new J.S(ticker));
        p.m.put("old_value", oldV == null ? new J.Nil() : new J.N(oldV, Double.toString(oldV)));
        p.m.put("new_value", new J.N(newV, Double.toString(newV)));
        p.m.put("pct_change", new J.S(pctChange(oldV, newV)));
        p.m.put("rationale", new J.S(rationale));
        p.m.put("sources", new J.S(sources));
        p.m.put("assumptions", assumptions == null ? new J.Nil() : assumptions);
        p.m.put("provenance", provenance == null ? new J.Nil() : provenance);
        p.m.put("proposed_by", new J.S(by));
        p.m.put("proposed_at", new J.S(nowIso()));
        p.m.put("status", new J.S("pending"));
        Files.writeString(PENDING.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
        System.out.println("filed " + id + ": " + col + "/" + ticker + " " + fmtVal(oldV) + " -> " + fmtVal(newV)
                + " (" + pctChange(oldV, newV) + ") by " + by);
        System.out.println("status: pending auditor review (Muse cross-checks, then approve/reject)");
    }

    // ------------------------------------------------------------------
    // Command: file-evidence - file an assumption-VOICE evidence proposal.
    //
    // Value proposals (file) carry a new NUMBER for a blessed column. Voice
    // evidence is different: it carries a QUOTE from an independent voice
    // (management guidance, creator assumptions, ...) for the assumption
    // cross-check. It never touches a blessed store on approve - it only
    // moves pending -> approved/rejected as the review record. Missing value
    // stays missing (J.Nil), never a guess.
    //
    // Usage:
    //   java Proposals.java file-evidence --voice guidance --ticker NVDA \
    //       --field growth_rate [--value 12.5] [--unit percent] \
    //       --quote "..." --date 2025-08-27 --source "..." --by earnings-guidance
    // ------------------------------------------------------------------
    static void cmdFileEvidence(Map<String, String> args) throws IOException {
        String voice = req(args, "--voice"), ticker = req(args, "--ticker").toUpperCase();
        String field = req(args, "--field"), quote = req(args, "--quote");
        String date = req(args, "--date"), source = req(args, "--source");
        String by = req(args, "--by");
        String vRaw = args.get("--value"), unit = args.get("--unit");
        J.V value;
        if (vRaw == null || vRaw.isBlank() || vRaw.equalsIgnoreCase("null")) {
            value = new J.Nil();
        } else {
            try {
                value = new J.N(Double.parseDouble(vRaw), vRaw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--value must be numeric or omitted");
            }
        }
        ensureDirs();
        // Dedupe: same voice/ticker/field/value/call already pending -> skip.
        if (Files.exists(PENDING)) {
            try (var stream = Files.list(PENDING)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path f = it.next();
                    if (!f.getFileName().toString().endsWith(".json")) continue;
                    J.O p;
                    try { p = (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8)); }
                    catch (Exception ignored) { continue; }
                    if (!"voice-evidence".equals(J.str(p.m.get("kind")))) continue;
                    if (voice.equals(J.str(p.m.get("voice")))
                            && ticker.equals(J.str(p.m.get("ticker")))
                            && field.equals(J.str(p.m.get("field")))
                            && date.equals(J.str(p.m.get("call_date")))
                            && valEq(J.num(p.m.get("value")), J.num(value))) {
                        System.out.println("already filed as " + J.str(p.m.get("id")) + " - skipping");
                        return;
                    }
                }
            }
        }
        String id = nextId();
        J.O p = new J.O();
        p.m.put("id", new J.S(id));
        p.m.put("kind", new J.S("voice-evidence"));
        p.m.put("voice", new J.S(voice));
        p.m.put("ticker", new J.S(ticker));
        p.m.put("field", new J.S(field));
        p.m.put("value", value);
        p.m.put("unit", unit == null ? new J.Nil() : new J.S(unit));
        p.m.put("quote", new J.S(quote));
        p.m.put("call_date", new J.S(date));
        p.m.put("source", new J.S(source));
        p.m.put("proposed_by", new J.S(by));
        p.m.put("proposed_at", new J.S(nowIso()));
        p.m.put("status", new J.S("pending"));
        Files.writeString(PENDING.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
        System.out.println("filed " + id + ": voice-evidence " + voice + "/" + ticker
                + " " + field + "=" + (value instanceof J.Nil ? "null" : vRaw)
                + " (call " + date + ") by " + by);
        System.out.println("status: pending auditor review (evidence only - no blessed store touched)");
    }

    static String nextId() throws IOException {
        ensureDirs();
        String day = todayEt().replace("-", "");
        // Scan all proposal files for today's max sequence number.
        int max = 0;
        for (Path dir : List.of(PENDING, APPROVED, REJECTED)) {
            if (!Files.exists(dir)) continue;
            try (var stream = Files.list(dir)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    String name = it.next().getFileName().toString();
                    if (name.startsWith("prop-" + day + "-") && name.endsWith(".json")) {
                        try {
                            int k = Integer.parseInt(name.substring(14, 17));
                            if (k > max) max = k;
                        } catch (Exception ignored) { }
                    }
                }
            }
        }
        return String.format("prop-%s-%03d", day, max + 1);
    }

    // ------------------------------------------------------------------
    // Commands: list / show
    // ------------------------------------------------------------------
    static void cmdList(Map<String, String> args) throws IOException {
        String status = args.getOrDefault("--status", "pending");
        List<Path> dirs = new ArrayList<>();
        if (status.equals("all") || status.equals("pending")) dirs.add(PENDING);
        if (status.equals("all") || status.equals("approved")) dirs.add(APPROVED);
        if (status.equals("all") || status.equals("rejected")) dirs.add(REJECTED);
        int n = 0;
        for (Path dir : dirs) {
            if (!Files.exists(dir)) continue;
            try (var stream = Files.list(dir)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path f = it.next();
                    if (!f.getFileName().toString().endsWith(".json")) continue;
                    J.O p = (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8));
                    if ("voice-evidence".equals(J.str(p.m.get("kind")))) {
                        Double v = J.num(p.m.get("value"));
                        String unit = J.str(p.m.get("unit"));
                        String vFmt;
                        if (v == null) vFmt = "null";
                        else if ("percent".equals(unit)) vFmt = v + "%";
                        else vFmt = v + (unit == null ? "" : " " + unit);
                        System.out.println(J.str(p.m.get("id")) + " [" + J.str(p.m.get("status")) + "] "
                                + "voice-evidence " + J.str(p.m.get("voice")) + "/" + J.str(p.m.get("ticker"))
                                + " " + J.str(p.m.get("field")) + "=" + vFmt
                                + " (call " + J.str(p.m.get("call_date")) + ") by "
                                + J.str(p.m.get("proposed_by")));
                        System.out.println("    quote: " + J.str(p.m.get("quote")));
                        n++;
                        continue;
                    }
                    System.out.println(J.str(p.m.get("id")) + " [" + J.str(p.m.get("status")) + "] "
                            + J.str(p.m.get("column")) + "/" + J.str(p.m.get("ticker")) + " "
                            + fmtVal(J.num(p.m.get("old_value"))) + " -> " + fmtVal(J.num(p.m.get("new_value")))
                            + " (" + J.str(p.m.get("pct_change")) + ") by " + J.str(p.m.get("proposed_by")));
                    System.out.println("    rationale: " + J.str(p.m.get("rationale")));
                    n++;
                }
            }
        }
        if (n == 0) System.out.println("(no " + status + " proposals)");
    }

    static void cmdShow(Map<String, String> args) throws IOException {
        String id = req(args, "id");
        J.O p = loadProposal(id);
        System.out.println(J.dumps(p));
    }

    static J.O loadProposal(String id) throws IOException {
        for (Path dir : List.of(PENDING, APPROVED, REJECTED)) {
            Path f = dir.resolve(id + ".json");
            if (Files.exists(f)) return (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("proposal not found: " + id);
    }

    // ------------------------------------------------------------------
    // Commands: approve / reject (auditor only - cross-check BEFORE approving).
    // ------------------------------------------------------------------
    static void cmdApprove(Map<String, String> args) throws Exception {
        String id = req(args, "id");
        String reason = req(args, "--reason");
        Path f = PENDING.resolve(id + ".json");
        if (!Files.exists(f)) throw new IllegalArgumentException("proposal not pending: " + id);
        J.O p = (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8));
        if ("voice-evidence".equals(J.str(p.m.get("kind")))) {
            // Evidence proposals carry quotes for the cross-check, not numbers for
            // a blessed column: approving only records the review decision. The
            // voice store (e.g. earnings_guidance.json) is written by the harvester;
            // approval here means "accepted as a voice entry".
            approveVoiceEvidence(p, f, id, reason);
            return;
        }
        String col = J.str(p.m.get("column"));
        String ticker = J.str(p.m.get("ticker"));
        double newV = J.num(p.m.get("new_value"));

        if (col.equals("creator")) {
            // Creator values only ever come from logged tracker calls. A direct write
            // here would fabricate provenance, so approval refuses: fix the log, then
            // rebless. The proposal stays pending until then.
            System.out.println("REFUSED: creator values flow from tracker logs (idea #47).");
            System.out.println("Add the call to creator_tracker/*_targets.json via the digest pipeline,");
            System.out.println("then run: java Proposals.java rebless --column creator --by <who>");
            return;
        }

        // Aging guard (idea: proposal-aging, 2026-09-30): a stale
        // price-sensitive proposal can NEVER be approved on old inputs.
        // Refuses loudly (exit 2) BEFORE any store write, backup, or bless.
        guardNotStale(p, id);

        blessOne(p, f, id, reason);
        // Hard-wired publish: the newly blessed value must reach every
        // consumer in this same run — no separate "remember to rebuild" step.
        publishDownstream();
    }

    /**
     * Batch approval: bless every pending numeric proposal, then publish
     * downstream exactly once. The hard-wiring is preserved — the publish
     * still happens, once for the whole batch instead of once per proposal
     * (a 31-ticker recalibration must not run 31 full rebuilds). Creator
     * proposals are skipped (they stay pending); voice-evidence proposals
     * are recorded without touching a blessed store.
     */
    static void cmdApproveBatch(Map<String, String> args) throws Exception {
        String reason = req(args, "--reason");
        List<Path> files;
        try (var s = Files.list(PENDING)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        }
        if (files.isEmpty()) {
            System.out.println("no pending proposals; nothing to approve, nothing to publish.");
            return;
        }
        int n = 0;
        for (Path f : files) {
            String id = f.getFileName().toString().replaceFirst("\\.json$", "");
            J.O p = (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8));
            if ("voice-evidence".equals(J.str(p.m.get("kind")))) {
                approveVoiceEvidence(p, f, id, reason);
                n++;
            } else if ("creator".equals(J.str(p.m.get("column")))) {
                System.out.println("SKIP " + id
                        + ": creator values flow from tracker logs (stays pending)");
            } else if (staleForApproval(p)) {
                // Aging guard (idea: proposal-aging, 2026-09-30): one stale
                // proposal must not block the batch — skip it loudly, keep it
                // pending, and let the auditor re-file with fresh inputs.
                System.out.println("SKIP " + id + ": stale (" + ageDays(p)
                        + "d unaudited, escalated) — price-sensitive proposals need "
                        + "re-file with fresh inputs (stays pending)");
            } else {
                blessOne(p, f, id, reason);
                n++;
            }
        }
        if (n == 0) {
            System.out.println("nothing approved (all skipped); downstream publish not needed.");
            return;
        }
        System.out.println("batch approved " + n + " proposal(s); publishing downstream once...");
        // Hard-wired publish (same guarantee as single approve).
        publishDownstream();
    }

    /** Record-only approval for voice-evidence proposals: no blessed store touched. */
    static void approveVoiceEvidence(J.O p, Path f, String id, String reason) throws IOException {
        p.m.put("status", new J.S("approved"));
        p.m.put("decided_by", new J.S("muse-audit"));
        p.m.put("decided_at", new J.S(nowIso()));
        p.m.put("decision_reason", new J.S(reason));
        Files.writeString(APPROVED.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
        Files.delete(f);
        changelog(id + " APPROVED by muse-audit\n- voice-evidence "
                + J.str(p.m.get("voice")) + "/" + J.str(p.m.get("ticker"))
                + " " + J.str(p.m.get("field")) + " (call " + J.str(p.m.get("call_date")) + ")"
                + "\n- decision: " + reason + " (no blessed store touched)");
        System.out.println("approved " + id + ": voice-evidence recorded (no blessed store touched)");
    }

    /**
     * Bless one numeric proposal: update the live store, re-bless the value
     * (+assumptions for ours), move to approved/, write the changelog.
     * Does NOT publish downstream — the caller (approve or approve-batch)
     * owns the publish step so the hard-wiring stays in exactly one place.
     */
    static void blessOne(J.O p, Path f, String id, String reason) throws Exception {
        String col = J.str(p.m.get("column"));
        String ticker = J.str(p.m.get("ticker"));
        double newV = J.num(p.m.get("new_value"));

        // Backup the store we're about to touch.
        Path store = col.equals("analyst") ? ANALYST_TARGETS : WORKBOOK_DATA;
        Path bak = store.resolveSibling(store.getFileName() + ".bak-proposal-" + id);
        Files.copy(store, bak, StandardCopyOption.REPLACE_EXISTING);

        if (col.equals("nlm_speculation")) {
            // Bless-only column: the live store (speculation_price.json) is
            // DERIVED from blessed by SpeculationPrice.java during the
            // publishDownstream rebuild below. Writing it here would be
            // overwritten by that rebuild — so bless, then publish.
        } else if (col.equals("analyst")) {
            J.O root = readJsonFile(ANALYST_TARGETS);
            J.O en = J.obj(root.m.get(ticker));
            if (en == null) { en = new J.O(); root.m.put(ticker, en); }
            en.m.put("target", new J.N(newV, Double.toString(newV)));
            en.m.put("asof", new J.S(todayEt()));
            Files.writeString(ANALYST_TARGETS, J.dumps(root) + "\n", StandardCharsets.UTF_8);
        } else {
            // ours / notebooklm / forward live in workbook_data.json company records.
            J.O root = readJsonFile(WORKBOOK_DATA);
            J.A companies = (J.A) root.m.get("companies");
            boolean found = false;
            for (J.V c : companies.l) {
                J.O co = (J.O) c;
                if (ticker.equals(((J.S) co.m.get("ticker")).s)) {
                    if (col.equals("ours")) {
                        co.m.put("fixed", new J.N(newV, Double.toString(newV)));
                        J.V asm = p.m.get("assumptions");
                        if (asm != null && !(asm instanceof J.Nil)) co.m.put("assumptions", asm);
                    } else {
                        co.m.put(masterField(col), new J.N(newV, Double.toString(newV)));
                    }
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalStateException("ticker vanished from workbook_data.json: " + ticker);
            Files.writeString(WORKBOOK_DATA, J.dumps(root) + "\n", StandardCharsets.UTF_8);
        }

        // Re-bless the new value (creates the column/ticker entry on first approval
        // of a new column — the sanctioned path, never a hand-edit).
        J.O blessed = readBlessed();
        J.O entry = ensureBlessedEntry(blessed, col, ticker);
        entry.m.put("value", new J.N(newV, Double.toString(newV)));
        if (col.equals("ours")) {
            J.V asm = p.m.get("assumptions");
            if (asm != null && !(asm instanceof J.Nil)) entry.m.put("assumptions", asm);
        }
        entry.m.put("blessed_at", new J.S(nowIso()));
        entry.m.put("blessed_by", new J.S("muse-audit"));
        // End-to-end provenance (idea #3): copy the executor/auditor trail onto
        // the blessed entry together with the proposal id that produced it. The
        // verify step only compares value/assumptions, so this field is inert
        // to the blessed-guard — it is pure audit trail.
        J.V prov = p.m.get("provenance");
        if (prov != null && !(prov instanceof J.Nil)) {
            entry.m.put("provenance", prov);
            entry.m.put("proposal_id", new J.S(id));
        }
        writeBlessed(blessed);

        // Move to approved/ with the decision record.
        p.m.put("status", new J.S("approved"));
        p.m.put("decided_by", new J.S("muse-audit"));
        p.m.put("decided_at", new J.S(nowIso()));
        p.m.put("decision_reason", new J.S(reason));
        Files.writeString(APPROVED.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
        Files.delete(f);
        changelog(id + " APPROVED by muse-audit\n- " + col + "/" + ticker + ": "
                + fmtVal(J.num(p.m.get("old_value"))) + " -> " + fmtVal(newV)
                + " (" + J.str(p.m.get("pct_change")) + ")\n- decision: " + reason
                + "\n- proposed by " + J.str(p.m.get("proposed_by")) + ": " + J.str(p.m.get("rationale")));
        System.out.println("approved " + id + ": " + col + "/" + ticker + " -> " + fmtVal(newV)
                + " (store backup: " + bak.getFileName() + ")");
    }

    static void cmdReject(Map<String, String> args) throws IOException {
        String id = req(args, "id");
        String reason = req(args, "--reason");
        Path f = PENDING.resolve(id + ".json");
        if (!Files.exists(f)) throw new IllegalArgumentException("proposal not pending: " + id);
        J.O p = (J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8));
        if ("voice-evidence".equals(J.str(p.m.get("kind")))) {
            p.m.put("status", new J.S("rejected"));
            p.m.put("decided_by", new J.S("muse-audit"));
            p.m.put("decided_at", new J.S(nowIso()));
            p.m.put("decision_reason", new J.S(reason));
            Files.writeString(REJECTED.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
            Files.delete(f);
            changelog(id + " REJECTED by muse-audit\n- voice-evidence "
                    + J.str(p.m.get("voice")) + "/" + J.str(p.m.get("ticker"))
                    + " " + J.str(p.m.get("field")) + " (call " + J.str(p.m.get("call_date")) + ")"
                    + "\n- decision: " + reason);
            System.out.println("rejected " + id);
            return;
        }
        p.m.put("status", new J.S("rejected"));
        p.m.put("decided_by", new J.S("muse-audit"));
        p.m.put("decided_at", new J.S(nowIso()));
        p.m.put("decision_reason", new J.S(reason));
        Files.writeString(REJECTED.resolve(id + ".json"), J.dumps(p) + "\n", StandardCharsets.UTF_8);
        Files.delete(f);
        changelog(id + " REJECTED by muse-audit\n- " + J.str(p.m.get("column")) + "/"
                + J.str(p.m.get("ticker")) + ": " + fmtVal(J.num(p.m.get("old_value"))) + " -> "
                + fmtVal(J.num(p.m.get("new_value"))) + "\n- decision: " + reason);
        System.out.println("rejected " + id);
    }

    // ------------------------------------------------------------------
    // Command: age - the waiting-room clock.
    //
    // Lists pending proposals by age bucket, flags stale ones, and (with
    // --mark) stamps them in place: stale=true, stale_bucket, stale_since.
    // Report-only otherwise: it never approves, rejects, or blesses.
    // The dcf-gap-watch job runs this daily and delivers the "nudge" list
    // to the Stock Research side chat.
    //
    // Dedup (anti-spam, same spirit as the gap watch): the state file maps
    // proposal id -> {bucket, last_nudged_at}. A proposal is nudged when it
    // ENTERS a bucket or is UPGRADED flag->escalate; an escalated proposal
    // re-pings weekly while it stays stale. Fresh proposals are pruned.
    //
    // Usage:
    //   java Proposals.java age [--json] [--mark] [--state PATH]
    // ------------------------------------------------------------------
    static void cmdAge(Map<String, String> args) throws IOException {
        boolean asJson = args.containsKey("--json");
        boolean mark = args.containsKey("--mark");
        Path statePath = args.containsKey("--state")
                ? Paths.get(args.get("--state"))
                : PDIR.resolve("age_state.json");
        ensureDirs();
        Instant now = Instant.now();

        List<J.O> pending = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.exists(PENDING)) {
            try (var stream = Files.list(PENDING)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path f = it.next();
                    if (!f.getFileName().toString().endsWith(".json")) continue;
                    try {
                        pending.add((J.O) J.parse(Files.readString(f, StandardCharsets.UTF_8)));
                        files.add(f);
                    } catch (Exception e) {
                        System.out.println("  [warn] unreadable proposal file: " + f.getFileName());
                    }
                }
            }
        }

        J.O state = readAgeState(statePath);
        List<J.O> nudges = computeNudges(pending, state, now);
        writeAgeState(statePath, state);

        int nFresh = 0, nFlag = 0, nEsc = 0;
        List<String> staleIds = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            J.O p = pending.get(i);
            String bucket = ageBucketAt(p, now);
            if (bucket.equals("fresh")) nFresh++;
            else if (bucket.equals("flag")) nFlag++;
            else nEsc++;
            if (staleForApprovalAt(p, now)) staleIds.add(J.str(p.m.get("id")));
            if (mark && !bucket.equals("fresh")) {
                stampStale(files.get(i), p, bucket, now);
            }
        }

        if (asJson) {
            J.O out = new J.O();
            J.O buckets = new J.O();
            buckets.m.put("fresh", new J.N(nFresh, Integer.toString(nFresh)));
            buckets.m.put("flag", new J.N(nFlag, Integer.toString(nFlag)));
            buckets.m.put("escalate", new J.N(nEsc, Integer.toString(nEsc)));
            out.m.put("buckets", buckets);
            J.A stale = new J.A();
            for (String id : staleIds) stale.l.add(new J.S(id));
            out.m.put("stale_unapprovable", stale);
            J.A nudgeArr = new J.A();
            for (J.O n : nudges) nudgeArr.l.add(n);
            out.m.put("nudge", nudgeArr);
            System.out.println(J.dumps(out));
            return;
        }

        // Human-readable bucket listing, oldest first.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(ageDaysAt(pending.get(b), now), ageDaysAt(pending.get(a), now)));
        for (int i : order) {
            J.O p = pending.get(i);
            String id = J.str(p.m.get("id"));
            String bucket = ageBucketAt(p, now);
            long d = ageDaysAt(p, now);
            String what;
            if ("voice-evidence".equals(J.str(p.m.get("kind")))) {
                what = "voice-evidence " + J.str(p.m.get("voice")) + "/" + J.str(p.m.get("ticker"))
                        + " " + J.str(p.m.get("field"));
            } else {
                what = J.str(p.m.get("column")) + "/" + J.str(p.m.get("ticker")) + " "
                        + fmtVal(J.num(p.m.get("old_value"))) + " -> " + fmtVal(J.num(p.m.get("new_value")));
            }
            System.out.println(id + " [" + bucket + "] " + d + "d " + what + " by " + J.str(p.m.get("proposed_by"))
                    + (staleForApprovalAt(p, now) ? "  <-- STALE: needs re-file, cannot be approved" : ""));
        }
        System.out.println("buckets: fresh=" + nFresh + " flag=" + nFlag + " escalate=" + nEsc
                + "; nudges this run: " + nudges.size());
    }

    /**
     * Deduped nudge list (pure logic over the passed state object, which it
     * mutates). A proposal is nudged when it ENTERS a bucket or is UPGRADED
     * to a worse bucket; an escalated proposal re-pings weekly while stale.
     * Fresh proposals are pruned from the state.
     */
    static List<J.O> computeNudges(List<J.O> pending, J.O state, Instant now) {
        List<J.O> nudges = new ArrayList<>();
        for (J.O p : pending) {
            String id = J.str(p.m.get("id"));
            if (id == null) continue;
            String bucket = ageBucketAt(p, now);
            J.O seen = J.obj(state.m.get(id));
            String seenBucket = seen == null ? null : J.str(seen.m.get("bucket"));
            String lastNudged = seen == null ? null : J.str(seen.m.get("last_nudged_at"));
            String why = null;
            if (bucket.equals("fresh")) {
                if (seen != null) state.m.remove(id);
            } else if (!bucket.equals(seenBucket)) {
                why = (seenBucket == null) ? "entered-" + bucket : "upgraded-to-" + bucket;
            } else if (bucket.equals("escalate") && daysSince(lastNudged, now) >= AGE_RENUDGE_DAYS) {
                why = "still-escalated";
            }
            if (why != null) {
                J.O rec = new J.O();
                rec.m.put("bucket", new J.S(bucket));
                rec.m.put("last_nudged_at", new J.S(now.toString()));
                state.m.put(id, rec);
                nudges.add(nudgeRecord(p, id, bucket, why, now));
            }
        }
        return nudges;
    }

    static J.O nudgeRecord(J.O p, String id, String bucket, String why, Instant now) {
        long d = ageDaysAt(p, now);
        J.O n = new J.O();
        n.m.put("id", new J.S(id));
        String title = J.str(p.m.get("title"));
        n.m.put("title", new J.S(title == null ? id : title));
        String kind = J.str(p.m.get("kind"));
        n.m.put("kind", new J.S(kind == null ? "value" : kind));
        n.m.put("column", p.m.get("column") == null ? new J.Nil() : p.m.get("column"));
        n.m.put("ticker", p.m.get("ticker") == null ? new J.Nil() : p.m.get("ticker"));
        n.m.put("age_days", new J.N(d, Long.toString(d)));
        n.m.put("bucket", new J.S(bucket));
        n.m.put("nudge_why", new J.S(why));
        n.m.put("price_sensitive", new J.B(priceSensitive(p)));
        n.m.put("needs_refile", new J.B(staleForApprovalAt(p, now)));
        n.m.put("old_value", p.m.get("old_value") == null ? new J.Nil() : p.m.get("old_value"));
        n.m.put("new_value", p.m.get("new_value") == null ? new J.Nil() : p.m.get("new_value"));
        n.m.put("proposed_by", p.m.get("proposed_by") == null ? new J.Nil() : p.m.get("proposed_by"));
        return n;
    }

    /**
     * Stamp a stale proposal in place (visibility marker for humans and the
     * waiting-room report). Enforcement does NOT read this stamp — the
     * approve path recomputes staleness from proposed_at, so the stamp can
     * never be a bypass. Never overwrites an existing stale_since.
     */
    static void stampStale(Path file, J.O p, String bucket, Instant now) throws IOException {
        boolean changed = false;
        J.V staleFlag = p.m.get("stale");
        if (!(staleFlag instanceof J.B) || !((J.B) staleFlag).b) {
            p.m.put("stale", new J.B(true));
            changed = true;
        }
        if (!bucket.equals(J.str(p.m.get("stale_bucket")))) {
            p.m.put("stale_bucket", new J.S(bucket));
            changed = true;
        }
        if (J.str(p.m.get("stale_since")) == null) {
            p.m.put("stale_since", new J.S(now.toString()));
            changed = true;
        }
        if (changed) Files.writeString(file, J.dumps(p) + "\n", StandardCharsets.UTF_8);
    }

    static J.O readAgeState(Path statePath) {
        try {
            if (Files.exists(statePath)) {
                J.V v = J.parse(Files.readString(statePath, StandardCharsets.UTF_8));
                if (v instanceof J.O) return (J.O) v;
            }
        } catch (Exception e) {
            System.out.println("  [warn] unreadable age state, starting fresh: " + e.getMessage());
        }
        return new J.O();
    }

    static void writeAgeState(Path statePath, J.O state) throws IOException {
        if (statePath.getParent() != null) Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, J.dumps(state) + "\n", StandardCharsets.UTF_8);
    }

    static long daysSince(String iso, Instant now) {
        if (iso == null) return Long.MAX_VALUE;
        try {
            return Math.max(0, Duration.between(Instant.parse(iso), now).toDays());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------
    // Hard-wired publish (Rahik 2026-09-28: "hard write it on your code").
    //
    // A blessed number is not "done" until it has propagated everywhere.
    // Every approve/rebless that changes a blessed value ends HERE:
    // RebuildAll regenerates every downstream file from the JSON backends
    // and finishes with the propagation gate (fail-closed: a stale consumer
    // fails the run). There is no "remember to rebuild" step anymore — the
    // code does it. If the gate fails, the value change stays blessed and
    // recorded, but this command exits 1: do each pending agent leg, then
    // re-run `java PropagationCheck.java` until it exits 0.
    static void publishDownstream() throws Exception {
        System.out.println("[publish] blessed values changed — running RebuildAll.java ...");
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                HOME + "/workspace/dcf_csv/RebuildAll.java");
        pb.directory(Paths.get(HOME, "workspace", "dcf_csv").toFile());
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.inheritIO();
        int rc = pb.start().waitFor();
        if (rc != 0) {
            System.out.println("PUBLISH INCOMPLETE (RebuildAll exited " + rc + "): the value "
                    + "change is blessed and recorded, but downstream is NOT fully "
                    + "propagated. Do each pending agent leg, then run: java " + HOME
                    + "/workspace/dcf_csv/PropagationCheck.java (until exit 0).");
            System.exit(1);
        }
        System.out.println("[publish] done — every consumer is current");
    }

    // ------------------------------------------------------------------
    // Command: rebless - designated writers re-bless after legitimate writes.
    // ------------------------------------------------------------------
    static void cmdRebless(Map<String, String> args) throws Exception {
        String col = req(args, "--column");
        String by = req(args, "--by");
        String onlyTicker = args.get("--ticker");
        if (!COLUMNS.contains(col)) throw new IllegalArgumentException("unknown column: " + col);
        J.O blessed = readBlessed();
        J.O cols = J.obj(blessed.m.get("columns"));
        J.O colObj = J.obj(cols.m.get(col));
        if (colObj == null) {
            // First touch of a new column: create the skeleton; the loop below
            // then blesses each live value through this same sanctioned path.
            colObj = new J.O();
            colObj.m.put("store", new J.S(storeDesc(col)));
            colObj.m.put("blessed_at", new J.S(nowIso()));
            colObj.m.put("tickers", new J.O());
            cols.m.put(col, colObj);
            changelog("blessed.json: created column skeleton '" + col + "' via rebless");
        }
        J.O tickers = J.obj(colObj.m.get("tickers"));
        Map<String, LiveVal> live = readLive(col);
        String at = nowIso();
        int n = 0;
        for (Map.Entry<String, LiveVal> e : live.entrySet()) {
            if (onlyTicker != null && !onlyTicker.equalsIgnoreCase(e.getKey())) continue;
            J.O entry = J.obj(tickers.m.get(e.getKey()));
            if (entry == null) {
                entry = new J.O();
                tickers.m.put(e.getKey(), entry);
            }
            entry.m.put("value", e.getValue().v == null ? new J.Nil() : new J.N(e.getValue().v, e.getValue().v.toString()));
            if (col.equals("ours") && e.getValue().assumptions != null) {
                entry.m.put("assumptions", e.getValue().assumptions);
            }
            entry.m.put("blessed_at", new J.S(at));
            entry.m.put("blessed_by", new J.S(by));
            n++;
        }
        writeBlessed(blessed);
        changelog("re-blessed column '" + col + "' (" + n + " tickers) by designated writer: " + by
                + (onlyTicker == null ? "" : " [ticker " + onlyTicker + "]"));
        System.out.println("re-blessed " + col + ": " + n + " tickers (by " + by + ")");
        // Hard-wired publish: the re-blessed values must reach every consumer
        // in this same run — no separate "remember to rebuild" step.
        publishDownstream();
    }

    // ------------------------------------------------------------------
    // Args + dispatch.
    // ------------------------------------------------------------------
    static Map<String, String> rest(String[] args, int from) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(a, args[i + 1]);
                i++;
            } else if (a.startsWith("--")) {
                m.put(a, "true");
            } else if (!m.containsKey("id")) {
                m.put("id", a);
            } else {
                throw new IllegalArgumentException("unexpected arg: " + a);
            }
        }
        return m;
    }

    static String req(Map<String, String> args, String key) {
        String v = args.get(key);
        if (v == null) throw new IllegalArgumentException("missing required " + key);
        return v;
    }

    static void usage() {
        System.out.println("Usage:");
        System.out.println("  java Proposals.java init [--by NAME]");
        System.out.println("  java Proposals.java verify");
        System.out.println("  java Proposals.java file --column C --ticker T --new V --rationale R --by WHO [--sources S] [--assumptions JSON] [--provenance JSON]");
        System.out.println("  java Proposals.java file-evidence --voice V --ticker T --field F [--value N] [--unit U] --quote Q --date D --source S --by WHO");
        System.out.println("  java Proposals.java list [--status pending|approved|rejected|all]");
        System.out.println("  java Proposals.java show ID");
        System.out.println("  java Proposals.java approve ID --reason R");
        System.out.println("  java Proposals.java approve-batch --reason R   (approve all pending, publish once)");
        System.out.println("  java Proposals.java reject ID --reason R");
        System.out.println("  java Proposals.java rebless --column C [--ticker T] --by WHO");
        System.out.println("  java Proposals.java age [--json] [--mark] [--state PATH]   (waiting-room clock)");
        System.out.println("  regression suite: ProposalsSelfTest.java (weekly sweep; see its header for the run command)");
        System.out.println("Columns: " + COLUMNS);
    }

    public static void main(String[] args) throws Exception {
        initPaths();
        if (args.length == 0) {
            usage();
            return;
        }
        try {
            switch (args[0]) {
                case "init": cmdInit(rest(args, 1)); break;
                case "verify": cmdVerify(); break;
                case "file": cmdFile(rest(args, 1)); break;
                case "file-evidence": cmdFileEvidence(rest(args, 1)); break;
                case "list": cmdList(rest(args, 1)); break;
                case "show": cmdShow(rest(args, 1)); break;
                case "approve": cmdApprove(rest(args, 1)); break;
                case "approve-batch": cmdApproveBatch(rest(args, 1)); break;
                case "reject": cmdReject(rest(args, 1)); break;
                case "rebless": cmdRebless(rest(args, 1)); break;
                case "age":
                case "--age": cmdAge(rest(args, 1)); break;
                default: usage(); System.exit(2);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }
}
