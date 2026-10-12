import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * ChannelVideos — worker-side innerTube "browse" listing for a channel's Videos tab.
 *
 * FALLBACK for FeedRss.java: the YouTube /feeds/videos.xml RSS endpoint has been
 * returning HTTP 404 for every channel since 2026-09-28 ~22:55 ET (endpoint-level;
 * youtube.com itself is 200). This pulls the same videos via the youtubei/v1/browse
 * API (params=EgZ2aWRlb3MyBgQKAjoA = Videos tab, newest first) and prints the same
 * JSON array shape as FeedRss fetch: [{videoId, title, channel, published, url}].
 *
 * publishedTimeText is relative ("2 hours ago"), so published is approximated as
 * now - offset. The alert job only needs a short recent window, so this is fine.
 *
 * Usage: java --class-path java-shared/classes:json.jar ChannelVideos.java \
 *          --since <ISO-8601> [--registry <subscriptions.json>] [--channels <id1,id2,...>]
 * Exit 0 = listing complete; exit 2 = one or more channels failed (JSON still
 * valid partial). Prints ONE JSON array on stdout, newest first.
 */
public class ChannelVideos {
    static final String BROWSE_URL =
        "https://www.youtube.com/youtubei/v1/browse?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
    static final String VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA";
    static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    static final long PACE_MS = 1500;

    public static void main(String[] args) throws Exception {
        String home = System.getenv("HOME"); if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        String since = null, registry = home + "/workspace/youtube/subscriptions.json", channels = null;
        for (int i = 0; i < args.length; i++) {
            if ("--since".equals(args[i]) && i + 1 < args.length) since = args[++i];
            else if ("--registry".equals(args[i]) && i + 1 < args.length) registry = args[++i];
            else if ("--channels".equals(args[i]) && i + 1 < args.length) channels = args[++i];
            else { System.err.println("usage: ChannelVideos.java --since <ISO-8601> [--registry <path>] [--channels <id1,id2,...>]"); return; }
        }
        if (since == null) { System.err.println("--since required"); System.exit(1); }
        Instant sinceInst = Instant.parse(since);

        List<String[]> chans = new ArrayList<>(); // id, name
        if (channels != null) {
            for (String id : channels.split(",")) chans.add(new String[]{id.trim(), ""});
        } else {
            JSONObject reg = new JSONObject(Files.readString(Path.of(registry), StandardCharsets.UTF_8));
            JSONArray arr = reg.getJSONArray("channels");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                chans.add(new String[]{c.getString("channelId"), c.optString("name", "")});
            }
        }

        List<JSONObject> out = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean first = true;
        for (String[] ch : chans) {
            try {
                if (!first) Polite.pace("youtubei-browse", PACE_MS);
                first = false;
                List<JSONObject> vids = browseChannel(ch[0], ch[1]);
                for (JSONObject v : vids) {
                    try {
                        if (Instant.parse(v.getString("published")).isAfter(sinceInst)) out.add(v);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                failed.add(ch[1].isEmpty() ? ch[0] : ch[1]);
                System.err.println("channelvideos: channel failed: " + ch[0] + " (" + ch[1] + "): " + e.getMessage());
            }
        }
        out.sort((a, b) -> b.getString("published").compareTo(a.getString("published")));
        JSONArray arr = new JSONArray(out);
        System.out.println(arr.toString());
        if (!failed.isEmpty()) {
            System.err.println("channelvideos: failed channels: " + String.join(", ", failed));
            System.exit(2);
        }
    }

    static List<JSONObject> browseChannel(String channelId, String channelName) throws Exception {
        JSONObject body = new JSONObject()
            .put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250925.01.00")
                .put("hl", "en").put("gl", "US")))
            .put("browseId", channelId)
            .put("params", VIDEOS_TAB);
        String resp = post(BROWSE_URL, body.toString());
        JSONObject d = new JSONObject(resp);
        List<JSONObject> out = new ArrayList<>();

        JSONObject contents = d.optJSONObject("contents");
        JSONObject twoCol = contents == null ? null : contents.optJSONObject("twoColumnBrowseResultsRenderer");
        JSONArray tabs = twoCol == null ? null : twoCol.optJSONArray("tabs");
        JSONObject tabContent = null;
        if (tabs != null) {
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.getJSONObject(i).optJSONObject("tabRenderer");
                if (tab != null && tab.optBoolean("selected", false)) { tabContent = tab.optJSONObject("content"); break; }
            }
            if (tabContent == null) { // fall back to first tab with content
                for (int i = 0; i < tabs.length(); i++) {
                    JSONObject tab = tabs.getJSONObject(i).optJSONObject("tabRenderer");
                    if (tab != null && tab.optJSONObject("content") != null) { tabContent = tab.optJSONObject("content"); break; }
                }
            }
        }
        JSONObject grid = tabContent == null ? null : tabContent.optJSONObject("richGridRenderer");
        JSONArray items = grid == null ? null : grid.optJSONArray("contents");
        if (items == null) return out;
        Instant now = Instant.now();
        for (int i = 0; i < items.length(); i++) {
            JSONObject rich = items.getJSONObject(i).optJSONObject("richItemRenderer");
            if (rich == null) continue;
            JSONObject content = rich.optJSONObject("content");
            if (content == null) continue;
            JSONObject vr = content.optJSONObject("videoRenderer");
            String vid = null, title = null, pubText = null;
            if (vr != null) {
                vid = vr.optString("videoId", null);
                JSONObject t = vr.optJSONObject("title");
                title = runsText(t);
                JSONObject pt = vr.optJSONObject("publishedTimeText");
                pubText = pt == null ? null : pt.optString("simpleText", null);
            } else {
                JSONObject lockup = content.optJSONObject("lockupViewModel");
                if (lockup != null) {
                    vid = lockup.optString("contentId", null);
                    JSONObject meta = lockup.optJSONObject("metadata");
                    JSONObject lmeta = meta == null ? null : meta.optJSONObject("lockupMetadataViewModel");
                    if (lmeta != null) {
                        JSONObject t = lmeta.optJSONObject("title");
                        title = t == null ? null : t.optString("content", null);
                        pubText = lockupPublishedText(lmeta);
                    }
                }
            }
            if (vid == null || pubText == null) continue;
            Instant pub = parseRelative(pubText, now);
            if (pub == null) continue;
            JSONObject v = new JSONObject();
            v.put("videoId", vid);
            v.put("title", title == null ? "" : title);
            v.put("channel", channelName);
            v.put("published", DateTimeFormatter.ISO_INSTANT.format(pub));
            v.put("url", "https://www.youtube.com/watch?v=" + vid);
            out.add(v);
        }
        return out;
    }

    static String runsText(JSONObject t) {
        if (t == null) return null;
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null || runs.length() == 0) return t.optString("simpleText", null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) sb.append(runs.getJSONObject(i).optString("text", ""));
        return sb.toString();
    }

    static String extractLockupVideoId(JSONObject lockup) {
        try {
            JSONObject meta = lockup.optJSONObject("metadata");
            JSONObject lmeta = meta == null ? null : meta.optJSONObject("lockupMetadataViewModel");
            if (lmeta == null) return null;
            String id = lmeta.optString("rendererContext", null);
            // rendererContext.commandContext.onTap.innertubeCommand.watchEndpoint.videoId
            JSONObject ctx = lmeta.optJSONObject("rendererContext");
            if (ctx == null) return null;
            JSONObject cc = ctx.optJSONObject("commandContext");
            JSONObject tap = cc == null ? null : cc.optJSONObject("onTap");
            JSONObject cmd = tap == null ? null : tap.optJSONObject("innertubeCommand");
            JSONObject we = cmd == null ? null : cmd.optJSONObject("watchEndpoint");
            return we == null ? null : we.optString("videoId", null);
        } catch (Exception e) { return null; }
    }

    /** First metadataParts text matching "N unit(s) ago" in the lockup metadata. */
    static String lockupPublishedText(JSONObject lmeta) {
        JSONObject meta = lmeta.optJSONObject("metadata");
        JSONObject cm = meta == null ? null : meta.optJSONObject("contentMetadataViewModel");
        JSONArray rows = cm == null ? null : cm.optJSONArray("metadataRows");
        if (rows == null) return null;
        Pattern p = Pattern.compile("(?i)(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago");
        for (int i = 0; i < rows.length(); i++) {
            JSONArray parts = rows.getJSONObject(i).optJSONArray("metadataParts");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject txt = parts.getJSONObject(j).optJSONObject("text");
                if (txt == null) continue;
                String c = txt.optString("content", "");
                if (p.matcher(c).find()) return c;
            }
        }
        return null;
    }

    static Instant parseRelative(String text, Instant now) {
        Pattern p = Pattern.compile("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase()) {
            case "second" -> now.minusSeconds(n);
            case "minute" -> now.minus(Duration.ofMinutes(n));
            case "hour" -> now.minus(Duration.ofHours(n));
            case "day" -> now.minus(Duration.ofDays(n));
            case "week" -> now.minus(Duration.ofDays(7 * n));
            case "month" -> now.minus(Duration.ofDays(30 * n));
            case "year" -> now.minus(Duration.ofDays(365 * n));
            default -> null;
        };
    }

    /** POST JSON via curl subprocess — the sandbox proxy allows curl but blocks
     *  Java's raw sockets (same pattern FeedRss.java / TranscriptFetch.java use). */
    static String post(String url, String body) throws Exception {
        Path tmp = Files.createTempFile("chanvideos", ".json");
        try {
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sS", "-m", "60",
                "-A", WEB_UA,
                "-H", "Accept-Language: en-US,en;q=0.9",
                "-H", "Content-Type: application/json",
                "-X", "POST", "--data-binary", "@" + tmp,
                "-w", "\n%{http_code}", url);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit != 0) throw new RuntimeException("curl exit=" + exit + ": " + stderr);
            int nl = stdout.lastIndexOf('\n');
            int status = Integer.parseInt(stdout.substring(nl + 1).trim());
            String resp = stdout.substring(0, nl);
            if (status != 200) throw new RuntimeException("HTTP " + status);
            return resp;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
