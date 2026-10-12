import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Resource Validity Auditor — Rahik's auditor fleet, auditor #6.
///
/// ONE TASK (two checks, one pass — combined per Rahik's "combine the task"):
/// are the external resources gathered in the notes still valid?
/// (a) every URL in vault + research notes — does it still resolve?
/// (b) every dated "as of" claim in prose — is it older than 90 days?
///
/// READ-ONLY: gentle HTTP HEADs (sequential, 300ms apart, 8s timeout),
/// never writes. Findings end in SUGGESTED actions; a separate approved
/// pass does any implementing.
///
/// Structured finding format: severity | location | expected vs actual |
/// evidence | suggestion.
public class ResourceValidityAudit {

    static final Pattern URL = Pattern.compile("https?://[^\\s)}\\]\"'<>]+");
    static final Pattern AS_OF = Pattern.compile(
            "(?i)\\bas of\\b[^\\n]{0,40}?(\\d{1,2}/\\d{1,2}/\\d{2,4}|\\d{4}-\\d{2}-\\d{2})");
    static final Pattern PROSE_DATE = Pattern.compile("\\((\\d{1,2}/\\d{1,2}/\\d{2})[^)]*\\)");

    static final int URL_CAP = 150;
    static final int LINK_FINDINGS_CAP = 20;
    static final int STALE_FINDINGS_CAP = 10;
    static final int STALE_DAYS = 90;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        List<Path> roots = List.of(
                home.resolve("workspace/atlas"),
                home.resolve("workspace/research_notes"));
        List<String> findings = audit(roots);
        if (findings.isEmpty()) {
            System.out.println("RESOURCE VALIDITY: CLEAN — links resolve, dated claims are fresh.");
        } else {
            System.out.println("RESOURCE VALIDITY: " + findings.size() + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    /** checkBase overrides the URL host check in tests (points at localhost). */
    static List<String> audit(List<Path> roots) throws Exception {
        return auditWithChecker(roots, ResourceValidityAudit::curlStatus);
    }

    /** Audit variant with an injected URL checker (for tests). */
    static List<String> auditWithChecker(List<Path> roots, UrlChecker checker) throws Exception {
        Map<String, List<String>> urlLocs = new LinkedHashMap<>();
        List<String[]> datedClaims = new ArrayList<>(); // [file, line, dateStr]
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".md") && !f.toString().contains("/.git/")).toList()) {
                    String rel = p.toString();
                    for (String line : Files.readAllLines(p)) {
                        Matcher um = URL.matcher(line);
                        while (um.find()) {
                            String u = trimUrl(um.group());
                            urlLocs.computeIfAbsent(u, k -> new ArrayList<>()).add(rel + ":" + line.strip());
                        }
                        if (!line.strip().startsWith("|")) { // prose only, not tables
                            Matcher am = AS_OF.matcher(line);
                            while (am.find()) datedClaims.add(new String[]{rel, line.strip(), am.group(1)});
                            Matcher pm = PROSE_DATE.matcher(line);
                            while (pm.find()) datedClaims.add(new String[]{rel, line.strip(), pm.group(1)});
                        }
                    }
                }
            }
        }

        List<String> findings = new ArrayList<>();
        findings.addAll(checkLinks(urlLocs, checker));
        findings.addAll(checkStaleClaims(datedClaims));
        return findings;
    }

    static List<String> checkLinks(Map<String, List<String>> urlLocs) {
        return checkLinks(urlLocs, ResourceValidityAudit::curlStatus);
    }

    /** Injectable checker: returns HTTP status, or -1 when unreachable. */
    interface UrlChecker { int check(String url) throws Exception; }

    static List<String> checkLinks(Map<String, List<String>> urlLocs, UrlChecker checker) {
        List<String> out = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<String>> e : urlLocs.entrySet()) {
            if (checked >= URL_CAP) {
                out.add("[LOW] url check truncated | checked " + URL_CAP + " of " + urlLocs.size() + " unique URLs\n"
                        + "  suggestion: raise URL_CAP or split the audit if link rot matters more.");
                break;
            }
            if (isLocalUrl(e.getKey())) continue; // local service, not a web resource
            checked++;
            int code;
            try {
                code = checker.check(e.getKey());
            } catch (Exception ex) {
                code = -1;
            }
            if (code == 429) continue; // rate-limited: not dead, try next run
            if (code < 0) {
                out.add(linkFinding(e.getKey(), e.getValue().get(0), "unreachable (timeout or connection failed)"));
            } else if (code >= 400) {
                out.add(linkFinding(e.getKey(), e.getValue().get(0), code + " " + reason(code)));
            }
            try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (out.size() >= LINK_FINDINGS_CAP) break;
        }
        return out.stream().limit(LINK_FINDINGS_CAP).toList();
    }

    /**
     * Production checker: curl HEAD with redirect-following and a hard timeout.
     * curl honors the runtime egress proxy env vars (direct connections fail
     * in this sandbox), so this stays proxy-aware without embedding credentials.
     */
    static int curlStatus(String url) throws Exception {
        Process p = new ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "--max-time", "8", "-L", "-I",
                "-A", "Mozilla/5.0 (resource-validity-audit; read-only link check)",
                url).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        if (exit != 0) return -1;
        try { return Integer.parseInt(out); }
        catch (NumberFormatException nfe) { return -1; }
    }

    static String linkFinding(String url, String loc, String problem) {
        return "[MEDIUM] dead link | " + url + "\n"
                + "  expected: cited resources resolve\n"
                + "  actual: " + problem + "\n"
                + "  evidence: cited in " + shorten(loc, 120) + "\n"
                + "  suggestion: replace with a live source, or drop the citation if the claim stands without it.";
    }

    static List<String> checkStaleClaims(List<String[]> datedClaims) {
        List<String> out = new ArrayList<>();
        LocalDate cutoff = LocalDate.now().minusDays(STALE_DAYS);
        for (String[] dc : datedClaims) {
            LocalDate d = parseDate(dc[2]);
            if (d == null || !d.isBefore(cutoff)) continue;
            out.add("[LOW] stale dated claim | " + dc[0] + "\n"
                    + "  expected: time-sensitive claims refreshed within " + STALE_DAYS + " days\n"
                    + "  actual: claim dated " + d + " (" + java.time.Period.between(d, LocalDate.now()).getMonths() + "+ months old)\n"
                    + "  evidence: " + shorten(dc[1], 140) + "\n"
                    + "  suggestion: re-verify the claim against a current source, or mark it historical.");
            if (out.size() >= STALE_FINDINGS_CAP) break;
        }
        return out;
    }

    static LocalDate parseDate(String s) {
        for (String fmt : new String[]{"M/d/yy", "M/d/yyyy", "yyyy-MM-dd"}) {
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(fmt)); }
            catch (DateTimeParseException e) { /* try next */ }
        }
        return null;
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static String trimUrl(String u) {
        while (!u.isEmpty() && ".,;:!'\"`*_~".indexOf(u.charAt(u.length() - 1)) >= 0)
            u = u.substring(0, u.length() - 1);
        return u;
    }

    /** Local-service URLs are not web resources; checking them is noise. */
    static boolean isLocalUrl(String u) {
        return u.matches("(?i)https?://(localhost|127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+"
                + "|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"
                + "|100\\.(6[4-9]|[78]\\d|9\\d|1[01]\\d|12[0-7])\\.\\d+\\.\\d+)(:\\d+)?(/.*)?");
    }

    static String reason(int code) {
        return switch (code) {
            case 400 -> "bad request"; case 401 -> "unauthorized"; case 403 -> "forbidden";
            case 404 -> "not found"; case 410 -> "gone"; case 500 -> "server error";
            case 502 -> "bad gateway"; case 503 -> "unavailable"; default -> "http " + code;
        };
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---- self-test: canned checker, no network at all ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("resvalid-test");
        Files.writeString(tmp.resolve("n.md"), """
                # N

                Good: https://example.com/ok and redirect https://example.com/redir.
                Bad: https://example.com/dead is gone.
                As of 01/15/20 the price was $5 (ancient claim).
                As of 10/01/26 the price is $6 (fresh claim).
                """);

        Map<String, Integer> canned = Map.of(
                "https://example.com/ok", 200,
                "https://example.com/redir", 200,
                "https://example.com/dead", 404);
        List<String> findings = auditWithChecker(List.of(tmp), canned::get);

        check(findings.stream().anyMatch(f -> f.contains("example.com/dead") && f.contains("dead link")),
                "must flag the 404 link, got: " + findings);
        check(findings.stream().noneMatch(f -> f.contains("example.com/ok")
                || f.contains("example.com/redir")),
                "200 links must not flag, got: " + findings);
        check(findings.stream().anyMatch(f -> f.contains("stale dated claim") && f.contains("2020-01-15")),
                "must flag the 2020 'as of' claim, got: " + findings);
        check(findings.stream().noneMatch(f -> f.contains("2026-10-01")),
                "fresh 'as of' claim must not flag");
        check(findings.size() == 2, "exactly 2 findings expected, got " + findings.size());

        // Prove the curl path is wired: example.com is reachable through the proxy.
        int live = curlStatus("https://example.com");
        check(live == 200, "curl checker must reach example.com via proxy, got " + live);

        System.out.println("ResourceValidityAudit self-test: ALL GREEN (dead red, stale red, ok/fresh green, live curl proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
