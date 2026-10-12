import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Net — one shared HTTP GET for the workspace Java tools.
 *
 * <p>Rahik's rule (2026-09-27): repeated steps become a method. The fetch
 * boilerplate used to be copy-pasted in every tool that hits a URL
 * (FetchWsbNews, AnalystActionScan, CrowdPulse, ...). This is the single
 * copy now; new tools use {@link #get(String)}.
 *
 * <p>Transport note (2026-09-28): Java's own HttpClient is blocked by the
 * sandbox proxy policy ("Other TCP connections is turned off") — that is
 * what killed the 2026-09-28 morning digest's analyst-action and crowd-pulse
 * fetches with "direct TCP access denied". All HTTP therefore goes through
 * curl subprocesses, the same pattern FeedRss.java and TranscriptFetch.java
 * already use in production. curl honors https_proxy / http_proxy / NO_PROXY
 * from the environment on its own, so no proxy code is needed here.
 *
 * <p>Accuracy note: {@link #get} throws on any non-200 status — callers must
 * treat that as "fetch failed, skip", never as empty data.
 */
public final class Net {

    private Net() {}

    static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /**
     * GET the URL, return the body as a string. Throws on non-200 or IO error.
     */
    public static String get(String url) throws Exception {
        return get(url, 25);
    }

    /**
     * GET the URL with a custom timeout (seconds). Throws on non-200 or IO error.
     */
    public static String get(String url, int timeoutSecs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            "curl", "-sS", "-L", "--max-redirs", "5",
            "-m", String.valueOf(Math.max(1, timeoutSecs)),
            "-A", UA,
            "-H", "Accept-Language: en-US,en;q=0.9",
            "-w", "\n%{http_code}"));
        cmd.add(url);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = p.waitFor();
        if (exit != 0)
            throw new IOException("curl exit=" + exit + " for " + url + ": " + stderr);
        int nl = stdout.lastIndexOf('\n');
        if (nl < 0)
            throw new IOException("curl produced no status line for " + url);
        final int status;
        try {
            status = Integer.parseInt(stdout.substring(nl + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("curl produced bad status for " + url);
        }
        String body = stdout.substring(0, nl);
        if (status != 200)
            throw new RuntimeException("HTTP " + status + " for " + url);
        return body;
    }
}
