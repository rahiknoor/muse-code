// FeedRss.java
// Scripted YouTube subscription-feed lister (IDEA 6: browser clicks -> direct calls, 2026-09-28).
//
// Replaces the browser.spawn_task feed-listing step in the hourly buy-call alerts
// and the morning digest. YouTube channel RSS feeds
// (https://www.youtube.com/feeds/videos.xml?channel_id=<id>) list each channel's
// ~15 most recent uploads — videos AND Shorts — with videoId, title, and publish
// time. No sign-in, no browser, no cookies. Java's HttpClient is blocked by the
// sandbox proxy policy ("Other TCP connections is turned off"), so all HTTP goes
// through curl subprocesses — the same pattern TranscriptFetch.java uses.
//
// Channel registry: ~/workspace/youtube/subscriptions.json (channelId + name).
// New channels are added with `FeedRss.java resolve "<channel name>"`.
//
// Usage:
//   java --class-path <json.jar>:<java-shared/classes> ~/workspace/youtube/FeedRss.java \
//        fetch --since <ISO-8601> [--registry <path>] [--channels <id1,id2,...>]
//
//   Prints ONE JSON array on stdout (newest first, deduplicated across channels):
//     [{"videoId":"...","title":"...","channel":"...","channelId":"...",
//       "published":"2026-09-27T13:45:32+00:00","url":"https://www.youtube.com/watch?v=..."}]
//   Only videos with published > --since are listed.
//   Exit 0 = every channel fetched OK; exit 2 = one or more channel fetches
//   failed (partial results are still printed; per-channel errors go to stderr).
//
//   FeedRss.java resolve "<channel name>"
//     Innertube search -> top-5 "channelId | channel title" lines on stdout.
//   FeedRss.java --self-test   (real RSS fetches; exit 1 on any failure)
//   FeedRss.java --verify       (registry + watermark + one live probe)

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class FeedRss {

    static final String HOME = homeDir();
    static final String DEFAULT_REGISTRY = HOME + "/workspace/youtube/subscriptions.json";
    static final String WATERMARK = HOME
        + "/workspace/goals/daily-youtube-stock-video-digest/hidden_files/buy_alert_watermark.txt";

    static final String WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    static final String SEARCH_URL =
        "https://www.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";

    static final int MAX_ATTEMPTS = 5;    static final Pattern CHANNEL_ID_RE = Pattern.compile("^UC[a-zA-Z0-9_-]{22}$");

    // ------------------------------------------------------------------ entry

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        if (args.length == 1 && "--verify".equals(args[0])) { verify(); return; }
        if (args.length >= 2 && "resolve".equals(args[0])) {
            for (Channel c : resolveName(join(args, 1)))
                System.out.println(c.id + " | " + c.name);
            return;
        }
        if (args.length < 2 || !"fetch".equals(args[0])) {
            System.err.println("usage: FeedRss.java fetch --since <ISO-8601>"
                + " [--registry <path>] [--channels <id1,id2,...>]");
            System.err.println("       FeedRss.java resolve \"<channel name>\"");
            System.err.println("       FeedRss.java --self-test | --verify");
            System.exit(1);
        }
        String since = null, registry = DEFAULT_REGISTRY, channels = null;
        for (int i = 1; i < args.length; i++) {
            if ("--since".equals(args[i]) && i + 1 < args.length) since = args[++i];
            else if ("--registry".equals(args[i]) && i + 1 < args.length) registry = args[++i];
            else if ("--channels".equals(args[i]) && i + 1 < args.length) channels = args[++i];
        }
        if (since == null) {
            System.err.println("fetch: --since <ISO-8601> is required");
            System.exit(1);
        }
        Instant sinceInstant;
        try {
            sinceInstant = Instant.parse(since);
        } catch (Exception e) {
            System.err.println("fetch: --since is not ISO-8601: " + since);
            System.exit(1);
            return;
        }
        List<Channel> list = channels != null
            ? channelsFromIds(channels)
            : loadRegistry(Path.of(registry));

        List<Video> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Channel ch : list) {
            try {
                Polite.pace("youtube-feeds", 300); // politeness gap between channels; first call is free
                all.addAll(fetchChannel(ch.id));
            } catch (Exception e) {
                errors.add(ch.id + " (" + ch.name + "): " + e.getMessage());
            }
        }
        for (String err : errors) System.err.println("feed-rss: channel failed: " + err);

        List<Video> fresh = filterSince(all, sinceInstant);
        Map<String, Video> dedup = new LinkedHashMap<>();
        for (Video v : fresh) dedup.putIfAbsent(v.videoId, v);
        List<Video> out = new ArrayList<>(dedup.values());
        out.sort((a, b) -> b.publishedInstant.compareTo(a.publishedInstant));

        JSONArray arr = new JSONArray();
        for (Video v : out) {
            arr.put(new JSONObject()
                .put("videoId", v.videoId)
                .put("title", v.title)
                .put("channel", v.channel)
                .put("channelId", v.channelId)
                .put("published", v.published)
                .put("url", "https://www.youtube.com/watch?v=" + v.videoId));
        }
        System.out.println(arr.toString());
        System.err.println("feed-rss: " + list.size() + " channels, "
            + all.size() + " entries, " + out.size() + " newer than " + since
            + (errors.isEmpty() ? "" : ", " + errors.size() + " channel errors"));
        System.exit(errors.isEmpty() ? 0 : 2);
    }

    static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ model

    static final class Channel {
        final String id, name;
        Channel(String id, String name) { this.id = id; this.name = name; }
    }

    static final class Video {
        final String videoId, title, channel, channelId, published;
        final Instant publishedInstant;
        Video(String videoId, String title, String channel, String channelId, String published) {
            this.videoId = videoId; this.title = title; this.channel = channel;
            this.channelId = channelId; this.published = published;
            this.publishedInstant = OffsetDateTime.parse(published).toInstant();
        }
        String url() { return "https://www.youtube.com/watch?v=" + videoId; }
    }

    static List<Channel> loadRegistry(Path p) throws IOException {
        JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
        JSONArray arr = root.getJSONArray("channels");
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            out.add(new Channel(c.getString("channelId"), c.optString("name", "")));
        }
        return out;
    }

    static List<Channel> channelsFromIds(String csv) {
        List<Channel> out = new ArrayList<>();
        for (String id : csv.split(",")) {
            id = id.trim();
            if (!id.isEmpty()) out.add(new Channel(id, ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ fetch

    static List<Video> fetchChannel(String channelId) throws Exception {
        String xml = withBackoff(() ->
            curlGet("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId));
        return parseFeed(xml, channelId);
    }

    static final Pattern ENTRY_RE = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    static final Pattern VIDEO_ID_RE = Pattern.compile("<yt:videoId>([^<]+)</yt:videoId>");
    static final Pattern PUBLISHED_RE = Pattern.compile("<published>([^<]+)</published>");
    static final Pattern TITLE_RE = Pattern.compile("<title>([^<]*)</title>");
    static final Pattern FEED_TITLE_RE = Pattern.compile("<feed[^>]*>.*?<title>([^<]*)</title>", Pattern.DOTALL);

    static List<Video> parseFeed(String xml, String channelId) {
        String channelName = "";
        Matcher fm = FEED_TITLE_RE.matcher(xml);
        if (fm.find()) channelName = unescape(fm.group(1).trim());

        List<Video> out = new ArrayList<>();
        Matcher em = ENTRY_RE.matcher(xml);
        while (em.find()) {
            String entry = em.group(1);
            String vid = first(VIDEO_ID_RE, entry);
            String pub = first(PUBLISHED_RE, entry);
            String title = first(TITLE_RE, entry); // first <title> in the entry is the video title
            if (vid == null || pub == null) continue; // malformed entry: skip, don't fail
            try {
                out.add(new Video(vid, unescape(title == null ? "" : title.trim()),
                    channelName, channelId, pub.trim()));
            } catch (Exception e) {
                // unparseable publish timestamp: skip the entry
            }
        }
        return out;
    }

    static String first(Pattern re, String s) {
        Matcher m = re.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static List<Video> filterSince(List<Video> videos, Instant since) {
        List<Video> out = new ArrayList<>();
        for (Video v : videos) if (v.publishedInstant.isAfter(since)) out.add(v);
        return out;
    }

    // ------------------------------------------------------------------ resolve

    static List<Channel> resolveName(String name) throws Exception {
        JSONObject body = new JSONObject()
            .put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250925.01.00")
                .put("hl", "en").put("gl", "US")))
            .put("query", name);
        Path tmp = Files.createTempFile("feedrss-search", ".json");
        try {
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8);
            String resp = withBackoff(() -> curlPost(SEARCH_URL, tmp));
            JSONObject d = new JSONObject(resp);
            List<Channel> out = new ArrayList<>();
            JSONObject contents = d.optJSONObject("contents");
            JSONObject twoCol = contents == null ? null
                : contents.optJSONObject("twoColumnSearchResultsRenderer");
            JSONObject primary = twoCol == null ? null
                : twoCol.optJSONObject("primaryContents");
            JSONObject section = primary == null ? null
                : primary.optJSONObject("sectionListRenderer");
            JSONArray sections = section == null ? null : section.optJSONArray("contents");
            if (sections != null) {
                for (int i = 0; i < sections.length() && out.size() < 5; i++) {
                    JSONObject itemSec = sections.getJSONObject(i).optJSONObject("itemSectionRenderer");
                    JSONArray items = itemSec == null ? null : itemSec.optJSONArray("contents");
                    if (items == null) continue;
                    for (int j = 0; j < items.length() && out.size() < 5; j++) {
                        JSONObject ch = items.getJSONObject(j).optJSONObject("channelRenderer");
                        if (ch == null) continue;
                        JSONObject title = ch.optJSONObject("title");
                        out.add(new Channel(ch.optString("channelId", ""),
                            title == null ? "" : title.optString("simpleText", "")));
                    }
                }
            }
            return out;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ network (curl: proxy allows curl, blocks raw sockets)

    interface CurlCall { String run() throws Exception; }

    static String withBackoff(CurlCall call) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (CurlHttpException e) {
                last = e;
                if ((e.status == 429 || e.status >= 500) && attempt < MAX_ATTEMPTS) {
                    long wait = Polite.backoffSleep(attempt, e.retryAfterMs);
                    System.err.println("feed-rss: HTTP " + e.status
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                } else throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = Polite.backoffSleep(attempt);
                    System.err.println("feed-rss: " + e.getMessage()
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                } else throw e;
            }
        }
        throw last;
    }

    static final class CurlHttpException extends IOException {
        final int status;
        final long retryAfterMs;
        CurlHttpException(int status, long retryAfterMs) {
            super("HTTP " + status);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }
    }

    static String curlGet(String url) throws Exception {
        return curl("GET", url, null);
    }

    static String curlPost(String url, Path dataFile) throws Exception {
        return curl("POST", url, dataFile);
    }

    static String curl(String method, String url, Path dataFile) throws Exception {
        Path hdrFile = Files.createTempFile("curl-hdr", ".txt");
        try {
            List<String> cmd = new ArrayList<>(List.of(
                "curl", "-sS", "-m", "60",
                "-D", hdrFile.toString(),
                "-A", WEB_UA,
                "-H", "Accept-Language: en-US,en;q=0.9",
                "-w", "\n%{http_code}"));
            if ("POST".equals(method)) {
                cmd.addAll(List.of("-X", "POST", "-H", "Content-Type: application/json",
                    "--data-binary", "@" + dataFile.toString()));
            }
            cmd.add(url);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit != 0) throw new IOException("curl exit=" + exit + ": " + stderr);
            int nl = stdout.lastIndexOf('\n');
            int status = Integer.parseInt(stdout.substring(nl + 1).trim());
            String body = stdout.substring(0, nl);
            if (status == 200) return body;
            throw new CurlHttpException(status, retryAfterMs(hdrFile));
        } finally {
            Files.deleteIfExists(hdrFile);
        }
    }

    /** Parse a Retry-After response header (seconds) into milliseconds; 0 when absent/unparseable. */
    static long retryAfterMs(Path hdrFile) {
        try {
            for (String line : Files.readAllLines(hdrFile, StandardCharsets.UTF_8)) {
                if (line.regionMatches(true, 0, "Retry-After:", 0, 12)) {
                    return Math.max(0, Long.parseLong(line.substring(12).trim())) * 1000;
                }
            }
        } catch (Exception ignored) { /* absent header: no backoff override */ }
        return 0;
    }

    // ------------------------------------------------------------------ self-test (real network)

    static void selfTest() throws Exception {
        // 1. Real RSS fetch returns entries for a known channel.
        List<Video> parkev = fetchChannel("UCwKB_00dPL3x5XmHF9IJCrg");
        SelfTest.check("parkev RSS returns entries", !parkev.isEmpty());

        // 2. Entry fields are all populated.
        boolean fields = true;
        for (int i = 0; i < Math.min(3, parkev.size()); i++) {
            Video v = parkev.get(i);
            fields &= !v.videoId.isEmpty() && !v.title.isEmpty()
                && !v.channel.isEmpty() && !v.published.isEmpty()
                && v.url().startsWith("https://www.youtube.com/watch?v=");
        }
        SelfTest.check("first entries have videoId/title/channel/published/url", fields);
        SelfTest.checkContains("channel name resolved from feed",
            parkev.get(0).channel, "Parkev");

        // 3. Watermark filter: since=now -> nothing; since=epoch -> everything.
        SelfTest.check("since=now filters everything out",
            filterSince(parkev, Instant.now().plusSeconds(60)).isEmpty());
        SelfTest.checkEq("since=epoch keeps everything", parkev.size(),
            filterSince(parkev, Instant.EPOCH).size());

        // 4. Synthetic entry: entity unescape + malformed-entry skip.
        String xml = "<feed><title>C</title><entry><id>yt:video:x</id>"
            + "<yt:videoId>x</yt:videoId><title>A &amp; B &lt;tag&gt;</title>"
            + "<published>2026-01-01T00:00:00+00:00</published></entry>"
            + "<entry><title>no id here</title></entry></feed>";
        List<Video> syn = parseFeed(xml, "UCxxxxxxxxxxxxxxxxxxxxxx");
        SelfTest.checkEq("synthetic: one well-formed entry parsed", 1, syn.size());
        SelfTest.checkEq("synthetic: entities unescaped", "A & B <tag>", syn.get(0).title);

        // 5. Resolve mode finds the known channel first.
        List<Channel> res = resolveName("Parkev Tatevosian, CFA");
        SelfTest.check("resolve returns candidates", !res.isEmpty());
        SelfTest.checkEq("resolve top hit is Parkev's channel",
            "UCwKB_00dPL3x5XmHF9IJCrg", res.isEmpty() ? null : res.get(0).id);

        // 6. Registry loads and every id matches the channel-id shape.
        List<Channel> reg = loadRegistry(Path.of(DEFAULT_REGISTRY));
        SelfTest.check("registry has channels", reg.size() >= 20);
        boolean shapes = true;
        for (Channel c : reg) shapes &= CHANNEL_ID_RE.matcher(c.id).matches();
        SelfTest.check("every registry id matches UC[22] shape", shapes);

        SelfTest.finish();
    }

    // ------------------------------------------------------------------ verify

    static void verify() throws Exception {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        Path reg = Path.of(DEFAULT_REGISTRY);
        if (!Files.isRegularFile(reg)) {
            failures.add("missing registry: " + reg);
        } else {
            try {
                List<Channel> list = loadRegistry(reg);
                infos.add("registry holds " + list.size() + " channels");
                int bad = 0;
                for (Channel c : list) if (!CHANNEL_ID_RE.matcher(c.id).matches()) bad++;
                if (bad > 0) failures.add(bad + " registry ids do not match UC[22] shape");
            } catch (Exception e) {
                failures.add("registry does not parse: " + e.getMessage());
            }
        }

        try {
            String wm = Files.readString(Path.of(WATERMARK), StandardCharsets.UTF_8).trim();
            Instant.parse(wm);
            infos.add("buy-call watermark parses as ISO-8601: " + wm);
        } catch (Exception e) {
            failures.add("buy-call watermark missing or not ISO-8601: " + e.getMessage());
        }

        try {
            List<Video> probe = fetchChannel("UCwKB_00dPL3x5XmHF9IJCrg");
            if (probe.isEmpty()) failures.add("live RSS probe returned zero entries");
            else infos.add("live RSS probe OK: " + probe.size() + " entries from Parkev feed");
        } catch (Exception e) {
            failures.add("live RSS probe failed: " + e.getMessage());
        }

        Verify.report(
            "VERIFY OK (FeedRss): registry, watermark, and live RSS probe all healthy.",
            "VERIFY FAILED (FeedRss): problems found:",
            infos, failures);
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }
}
