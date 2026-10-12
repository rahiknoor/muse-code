import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.net.UnixDomainSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text-to-image via Cloudflare Workers AI REST API (Java replacement for
 * cf_text_to_image.py — Rahik reads Java, not Python).
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes CfImage.java --discover-account
 *   java --class-path ~/workspace/java-shared/classes CfImage.java \
 *       --account &lt;id&gt; --model @cf/black-forest-labs/flux-2-klein-4b \
 *       --prompt "..." --out out.png [--width 1024 --height 1024 --seed 7]
 * </pre>
 *
 * <p>Auth goes through the authd surrogate exchange (never a raw key in
 * chat/env/files): this tool POSTs {"name":"custom.cloudflare-workers-ai"}
 * to authd's Unix socket, receives an {@code hsurr:*} surrogate plus its
 * placement, and puts the surrogate on the outbound request. Sentinel
 * swaps the surrogate for the real credential on approved egress.
 *
 * <p>Every generation appends one line to
 * {@code ~/workspace/av_free_stack/generation_log.md} (the prompt/version
 * registry): timestamp, model, seed, WxH, prompt-file name + sha1 of the
 * prompt text, output filename.
 */
public final class CfImage {

    private static final String CRED_NAME = "custom.cloudflare-workers-ai";
    private static final String[] ALLOWED_HOSTS = {"api.cloudflare.com"};
    private static final String BASE = "https://api.cloudflare.com/client/v4";
    private static final String DEFAULT_MODEL = "@cf/black-forest-labs/flux-2-klein-4b";
    private static final String LOG_NAME = "generation_log.md";
    private static final ZoneId LOG_ZONE = ZoneId.of("America/New_York");
    private static final int MAX_ERROR_BODY_CHARS = 4000;

    private CfImage() {
        // static-only
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    /** Parsed command-line arguments. */
    static final class Args {
        boolean discoverAccount;
        boolean selfTest;
        boolean verify;
        String account;
        String model = DEFAULT_MODEL;
        String prompt;
        String promptFile;
        String out;
        int width = 1024;
        int height = 1024;
        int seed = 7;
        int steps = 0;
    }

    static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String flag = argv[i];
            switch (flag) {
                case "--discover-account" -> a.discoverAccount = true;
                case "--self-test" -> a.selfTest = true;
                case "--verify" -> a.verify = true;
                case "--account" -> a.account = next(argv, ++i, flag);
                case "--model" -> a.model = next(argv, ++i, flag);
                case "--prompt" -> a.prompt = next(argv, ++i, flag);
                case "--prompt-file" -> a.promptFile = next(argv, ++i, flag);
                case "--out" -> a.out = next(argv, ++i, flag);
                case "--width" -> a.width = parseInt(argv, ++i, flag);
                case "--height" -> a.height = parseInt(argv, ++i, flag);
                case "--seed" -> a.seed = parseInt(argv, ++i, flag);
                case "--steps" -> a.steps = parseInt(argv, ++i, flag);
                default -> throw new IllegalArgumentException("unknown flag: " + flag);
            }
        }
        if (a.selfTest || a.verify || a.discoverAccount) {
            return a;
        }
        if (a.account == null || a.account.isBlank()) {
            throw new IllegalArgumentException("--account is required");
        }
        if ((a.prompt == null || a.prompt.isBlank()) && a.promptFile == null) {
            throw new IllegalArgumentException("--prompt or --prompt-file is required");
        }
        if (a.out == null || a.out.isBlank()) {
            throw new IllegalArgumentException("--out is required");
        }
        if (a.width <= 0 || a.height <= 0) {
            throw new IllegalArgumentException("--width/--height must be positive");
        }
        return a;
    }

    private static String next(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return argv[i];
    }

    private static int parseInt(String[] argv, int i, String flag) {
        String v = next(argv, i, flag);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs an integer, got: " + v);
        }
    }

    /** Resolves the prompt text; --prompt-file wins when both are given. */
    static String resolvePrompt(Args a) throws IOException {
        if (a.promptFile != null) {
            return Files.readString(Paths.get(a.promptFile), StandardCharsets.UTF_8);
        }
        return a.prompt;
    }

    /** Display name for the prompt source in the generation log. */
    static String promptSourceName(Args a) {
        if (a.promptFile != null) {
            return Paths.get(a.promptFile).getFileName().toString();
        }
        return "(inline)";
    }

    // ------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        Args a;
        try {
            a = parseArgs(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if (a.selfTest) {
            runSelfTests();
            SelfTest.finish();
            return;
        }
        if (a.verify) {
            runVerify();
            return;
        }
        if (a.discoverAccount) {
            System.out.println(discoverAccount());
            return;
        }
        String prompt = resolvePrompt(a);
        byte[] img = textToImage(a.account, a.model, prompt, a.width, a.height, a.seed,
                a.steps == 0 ? null : a.steps);
        Path outPath = Paths.get(a.out);
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        Files.write(outPath, img);
        appendGenerationLog(a, prompt);
        System.out.println("saved " + a.out + " (" + img.length + " bytes)");
    }

    private static void printUsage() {
        System.err.println("usage: CfImage --account <id> --model <model> "
                + "--prompt <text> | --prompt-file <path> --out <file>");
        System.err.println("       [--width 1024 --height 1024 --seed 7 --steps 0]");
        System.err.println("       CfImage --discover-account | --self-test | --verify");
    }

    // ------------------------------------------------------------------
    // Auth: authd surrogate exchange over a Unix domain socket.
    // ------------------------------------------------------------------

    /** Where authd listens; $JARVIS_AUTHD_SOCK overrides the default. */
    static String authdSocketPath() {
        String override = System.getenv("JARVIS_AUTHD_SOCK");
        return (override != null && !override.isBlank())
                ? override : "/run/hatch/auth/authd.sock";
    }

    /** A surrogated credential entry: the hsurr:* value plus where it goes. */
    static final class Surrogate {
        final String value;        // "hsurr:..." — never the real secret
        final String placement;    // "bearer_header" or "custom_header:<name>"
        Surrogate(String value, String placement) {
            this.value = value;
            this.placement = placement;
        }
    }

    /**
     * POSTs {"name": credential} to authd's /v1/credentials/surrogate and
     * returns the raw JSON body. Speaks plain HTTP/1.1 over the Unix socket.
     */
    static String postToAuthd(String socketPath, String credentialName) throws IOException {
        byte[] body = ("{\"name\":" + Json.quote(credentialName) + "}").getBytes(StandardCharsets.UTF_8);
        String req = "POST /v1/credentials/surrogate HTTP/1.1\r\n"
                + "Host: authd.local\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] head = req.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel ch = SocketChannel.open(addr)) {
            ch.write(ByteBuffer.wrap(head));
            ch.write(ByteBuffer.wrap(body));
            ByteBuffer buf = ByteBuffer.allocate(65536);
            int n;
            while ((n = ch.read(buf)) != -1) {
                if (buf.remaining() == 0) {
                    // Grow: authd payloads are small, but never truncate silently.
                    ByteBuffer bigger = ByteBuffer.allocate(buf.capacity() * 2);
                    buf.flip();
                    bigger.put(buf);
                    buf = bigger;
                }
            }
            buf.flip();
            byte[] raw = new byte[buf.remaining()];
            buf.get(raw);
            out.write(raw, 0, raw.length);
        }
        byte[] raw = out.toByteArray();
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        if (split < 0) {
            throw new IOException("authd returned a malformed HTTP response");
        }
        String statusLine = new String(raw, 0, split, StandardCharsets.ISO_8859_1)
                .split("\r\n", 2)[0];
        String[] parts = statusLine.split(" ", 3);
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new IOException("authd returned malformed status: " + statusLine);
        }
        String text = new String(raw, split + 4, raw.length - split - 4, StandardCharsets.UTF_8);
        if (status != 200) {
            throw new IOException("authd /v1/credentials/surrogate returned HTTP "
                    + status + ": " + text.strip());
        }
        return text;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Extracts the "access_token" surrogate entry from authd's JSON.
     * Refuses anything that is not an hsurr:* surrogate — the real secret
     * must never flow through this process.
     */
    @SuppressWarnings("unchecked")
    static Surrogate parseSurrogateJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("authd response was not a JSON object");
        }
        Object creds = ((Map<String, Object>) root).get("credentials");
        if (!(creds instanceof List)) {
            throw new IllegalArgumentException("authd response has no credentials list");
        }
        for (Object e : (List<Object>) creds) {
            if (!(e instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) e;
            if (!"access_token".equals(entry.get("name"))) {
                continue;
            }
            Object sv = entry.get("surrogate");
            if (!(sv instanceof String) || !((String) sv).startsWith("hsurr:")) {
                throw new IllegalArgumentException(
                        "authd returned a non-surrogate value for " + CRED_NAME + ":access_token");
            }
            Object placement = entry.get("placement");
            String where;
            if ("bearer_header".equals(placement)) {
                where = "bearer_header";
            } else if (placement instanceof Map) {
                Object custom = ((Map<String, Object>) placement).get("custom_header");
                if (custom instanceof String && !((String) custom).isBlank()) {
                    where = "custom_header:" + custom;
                } else {
                    throw new IllegalArgumentException(
                            "unsupported credential placement: " + placement);
                }
            } else {
                throw new IllegalArgumentException(
                        "unsupported credential placement: " + placement);
            }
            return new Surrogate((String) sv, where);
        }
        throw new IllegalArgumentException(
                "missing access_token surrogate for credential " + CRED_NAME);
    }

    /** Refuses to send the surrogate anywhere except api.cloudflare.com. */
    static void ensureAllowedUrl(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad URL: " + url);
        }
        host = host == null ? "" : host.toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (allowed.equals(host)) {
                return;
            }
        }
        throw new IllegalArgumentException("refusing authenticated request to '" + host
                + "'; allowed hosts: " + String.join(",", ALLOWED_HOSTS));
    }

    /** Applies the surrogate to the request per its placement. */
    static HttpRequest.Builder applySurrogate(HttpRequest.Builder b, Surrogate s) {
        if ("bearer_header".equals(s.placement)) {
            return b.header("Authorization", "Bearer " + s.value);
        }
        if (s.placement.startsWith("custom_header:")) {
            return b.header(s.placement.substring("custom_header:".length()), s.value);
        }
        throw new IllegalArgumentException("unsupported placement: " + s.placement);
    }

    /** Full auth: socket -> surrogate. One place where auth happens. */
    static Surrogate fetchSurrogate() throws IOException {
        return parseSurrogateJson(postToAuthd(authdSocketPath(), CRED_NAME));
    }

    // ------------------------------------------------------------------
    // Cloudflare Workers AI calls
    // ------------------------------------------------------------------

    static String discoverAccount() throws Exception {
        Surrogate s = fetchSurrogate();
        String url = BASE + "/accounts";
        ensureAllowedUrl(url);
        HttpResponse<byte[]> resp = send(url, "GET", null, null, s);
        if (resp.statusCode() / 100 != 2) {
            reportHttpError("GET", url, resp);
            throw new IOException("account discovery failed (HTTP " + resp.statusCode() + ")");
        }
        String firstId = firstAccountId(new String(resp.body(), StandardCharsets.UTF_8));
        if (firstId == null) {
            throw new IOException("no Cloudflare accounts visible to this token");
        }
        return firstId;
    }

    @SuppressWarnings("unchecked")
    static String firstAccountId(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            return null;
        }
        Object result = ((Map<String, Object>) root).get("result");
        if (!(result instanceof List) || ((List<Object>) result).isEmpty()) {
            return null;
        }
        Object first = ((List<Object>) result).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object id = ((Map<String, Object>) first).get("id");
        return id instanceof String ? (String) id : null;
    }

    /**
     * Runs text-to-image and returns the raw image bytes. Handles both
     * Workers AI response shapes: raw image bytes, and JSON with
     * result.image as a base64 string (the FLUX.2 family).
     */
    static byte[] textToImage(String account, String model, String prompt,
                              int width, int height, int seed, Integer steps) throws Exception {
        Surrogate s = fetchSurrogate();
        String url = BASE + "/accounts/" + account + "/ai/run/" + model;
        ensureAllowedUrl(url);
        HttpResponse<byte[]> resp;
        String contentType;
        if (model.contains("flux-2")) {
            // FLUX.2 family requires multipart/form-data, even for prompt-only calls.
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("prompt", prompt);
            fields.put("width", String.valueOf(width));
            fields.put("height", String.valueOf(height));
            fields.put("seed", String.valueOf(seed));
            MultipartBody mp = buildMultipart(fields);
            resp = send(url, "POST", mp.body,
                    "multipart/form-data; boundary=" + mp.boundary, s);
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("prompt", prompt);
            payload.put("width", width);
            payload.put("height", height);
            payload.put("seed", seed);
            if (steps != null) {
                payload.put("num_steps", steps);
            }
            byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
            resp = send(url, "POST", body, "application/json", s);
        }
        if (resp.statusCode() / 100 != 2) {
            reportHttpError("POST", url, resp);
            throw new IOException("Workers AI call failed (HTTP " + resp.statusCode() + ")");
        }
        contentType = resp.headers().firstValue("Content-Type").orElse("");
        byte[] img = extractImage(resp.body(), contentType);
        if (img == null) {
            System.err.println("ERROR: Workers AI did not return an image. Body was:");
            System.err.println(truncate(new String(resp.body(), StandardCharsets.UTF_8),
                    MAX_ERROR_BODY_CHARS));
            throw new IOException("Workers AI did not return an image");
        }
        return img;
    }

    /**
     * Builds a ProxySelector from the standard proxy env vars
     * (HTTPS_PROXY/https_proxy, else HTTP_PROXY/http_proxy, else ALL_PROXY).
     * Java's HttpClient ignores these by default; without this, live calls
     * fail in sandboxes that egress only through a proxy. Honors
     * NO_PROXY/no_proxy. Proxy credentials (userinfo) are wired through an
     * Authenticator. Returns null when no proxy is configured.
     */
    static ProxySelector proxySelector() {
        String raw = firstEnv("HTTPS_PROXY", "https_proxy",
                              "HTTP_PROXY", "http_proxy",
                              "ALL_PROXY", "all_proxy");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final URI proxyUri;
        try {
            proxyUri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("WARN: ignoring malformed proxy URL");
            return null;
        }
        final String pHost = proxyUri.getHost();
        final int pPort = proxyUri.getPort() < 0 ? 8080 : proxyUri.getPort();
        if (pHost == null) {
            return null;
        }
        // Proxy auth from userinfo, installed once.
        final String userInfo = proxyUri.getUserInfo();
        if (userInfo != null && Authenticator.getDefault() == null) {
            final String pUser = userInfo.contains(":")
                    ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
            final String pPass = userInfo.contains(":")
                    ? userInfo.substring(userInfo.indexOf(':') + 1) : "";
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(pUser,
                                pPass.toCharArray());
                    }
                    return null;
                }
            });
        }
        final java.util.List<String> noProxy = noProxyList();
        final Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(pHost, pPort));
        return new ProxySelector() {
            @Override
            public java.util.List<Proxy> select(URI uri) {
                String host = uri.getHost();
                if (host != null) {
                    String h = host.toLowerCase(java.util.Locale.ROOT);
                    for (String n : noProxy) {
                        if (h.equals(n) || h.endsWith("." + n)) {
                            return java.util.List.of(Proxy.NO_PROXY);
                        }
                    }
                }
                return java.util.List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException e) {
                System.err.println("WARN: proxy connect failed for " + uri + ": " + e);
            }
        };
    }

    static String firstEnv(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    static java.util.List<String> noProxyList() {
        String raw = firstEnv("NO_PROXY", "no_proxy");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String p = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (p.startsWith(".")) {
                    p = p.substring(1);
                }
                // bracketed IPv6 literals: strip brackets
                if (p.startsWith("[") && p.endsWith("]")) {
                    p = p.substring(1, p.length() - 1);
                }
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    static HttpResponse<byte[]> send(String url, String method, byte[] body,
                                     String contentType, Surrogate s)
            throws IOException, InterruptedException {
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        ProxySelector ps = proxySelector();
        if (ps != null) {
            cb.proxy(ps);
        }
        HttpClient client = cb.build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300));
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        applySurrogate(b, s);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Prints the full response body on HTTP failure — the real reason
     * (e.g. Cloudflare error 3030 "output has been flagged") lives in the
     * body, never in the bare status code. Never prints the surrogate:
     * only the URL host, status, and body.
     */
    static void reportHttpError(String method, String url, HttpResponse<byte[]> resp) {
        String bodyText = new String(resp.body(), StandardCharsets.UTF_8);
        System.err.println("ERROR: " + method + " " + hostOf(url)
                + " -> HTTP " + resp.statusCode());
        String summary = summarizeCfError(resp.body());
        if (summary != null) {
            System.err.println("Cloudflare: " + summary);
        }
        System.err.println("--- response body ---");
        System.err.println(truncate(bodyText, MAX_ERROR_BODY_CHARS));
        System.err.println("--- end body ---");
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "<bad-url>";
        }
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n... [truncated " + (s.length() - max) + " chars]";
    }

    /**
     * Best-effort one-line summary of a Cloudflare error body:
     * "code 3030: output has been flagged by our content moderation system".
     * Returns null when the body is not a Cloudflare error shape.
     */
    @SuppressWarnings("unchecked")
    static String summarizeCfError(byte[] body) {
        Object root;
        try {
            root = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        if (!(root instanceof Map)) {
            return null;
        }
        Object errors = ((Map<String, Object>) root).get("errors");
        if (!(errors instanceof List) || ((List<Object>) errors).isEmpty()) {
            return null;
        }
        Object first = ((List<Object>) errors).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Map<String, Object> e = (Map<String, Object>) first;
        Object code = e.get("code");
        Object msg = e.get("message");
        if (msg instanceof String) {
            return "code " + code + ": " + msg;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Multipart builder (FLUX.2 requires multipart/form-data)
    // ------------------------------------------------------------------

    static final class MultipartBody {
        final byte[] body;
        final String boundary;
        MultipartBody(byte[] body, String boundary) {
            this.body = body;
            this.boundary = boundary;
        }
    }

    /** Random boundary; the "----MuseForm" prefix mirrors the Python CLI. */
    static String newBoundary() {
        byte[] rnd = new byte[12];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder("----MuseForm");
        for (byte x : rnd) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static MultipartBody buildMultipart(Map<String, String> fields) {
        return buildMultipart(fields, newBoundary());
    }

    static MultipartBody buildMultipart(Map<String, String> fields, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            // Framing is ASCII, but field VALUES (the prompt) are UTF-8 —
            // prompts with non-ASCII text must survive the round trip.
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            out.write(v, 0, v.length);
            out.write(crlf, 0, crlf.length);
        }
        writeAscii(out, "--" + boundary + "--\r\n");
        return new MultipartBody(out.toByteArray(), boundary);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    // ------------------------------------------------------------------
    // Response extraction: raw image bytes OR base64-in-JSON
    // ------------------------------------------------------------------

    /**
     * Returns the image bytes from a Workers AI response, or null when the
     * body holds no image. Detection order mirrors the Python CLI:
     * magic bytes first (JPEG/PNG), then {"result":{"image":"&lt;base64&gt;"}}.
     */
    @SuppressWarnings("unchecked")
    static byte[] extractImage(byte[] raw, String contentType) {
        if (raw == null || raw.length < 4) {
            return null;
        }
        // JPEG: FF D8 FF ...   PNG: 89 50 4E 47 ("PNG")
        if (raw[0] == (byte) 0xFF && raw[1] == (byte) 0xD8 && raw[2] == (byte) 0xFF) {
            return raw;
        }
        if (raw[0] == (byte) 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47) {
            return raw;
        }
        Object root;
        try {
            root = Json.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        if (!(root instanceof Map)) {
            return null;
        }
        Object result = ((Map<String, Object>) root).get("result");
        if (!(result instanceof Map)) {
            return null;
        }
        Object b64 = ((Map<String, Object>) result).get("image");
        if (!(b64 instanceof String) || ((String) b64).isEmpty()) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode((String) b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Generation log: the prompt/version registry.
    // ------------------------------------------------------------------

    static String sha1Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static Path logPath() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("$HOME is not set");
        }
        return Paths.get(home, "workspace", "av_free_stack", LOG_NAME);
    }

    /** One registry line: timestamp | model | seed | WxH | prompt source | sha1 | output. */
    static String formatLogLine(ZonedDateTime when, String model, int seed,
                                int width, int height, String promptSource,
                                String promptSha1, String outFile) {
        return "| " + when.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + " | " + model
                + " | seed " + seed
                + " | " + width + "x" + height
                + " | " + promptSource
                + " | sha1:" + promptSha1
                + " | " + outFile + " |";
    }

    static void appendGenerationLog(Args a, String prompt) throws IOException {
        Path log = logPath();
        boolean fresh = !Files.isRegularFile(log);
        ZonedDateTime now = ZonedDateTime.now(LOG_ZONE);
        String line = formatLogLine(now, a.model, a.seed, a.width, a.height,
                promptSourceName(a), sha1Hex(prompt), a.out);
        if (fresh) {
            String header = "# Generation log — Cloudflare Workers AI image runs\n"
                    + "# One line per generation:\n"
                    + "# | timestamp | model | seed | WxH | prompt source | prompt sha1 | output file |\n";
            Files.writeString(log, header + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } else {
            Files.writeString(log, line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    // ------------------------------------------------------------------
    // --self-test
    // ------------------------------------------------------------------

    /** Runs every check; returns the failure count (no System.exit here). */
    static int runSelfTests() {
        // --- args parsing ---
        Args d = parseArgs(new String[]{"--account", "abc", "--prompt", "hi", "--out", "o.png"});
        SelfTest.checkEq("args defaults: model", DEFAULT_MODEL, d.model);
        SelfTest.checkEq("args defaults: width", 1024, d.width);
        SelfTest.checkEq("args defaults: height", 1024, d.height);
        SelfTest.checkEq("args defaults: seed", 7, d.seed);
        Args full = parseArgs(new String[]{"--account", "abc", "--model", "m",
                "--prompt-file", "p.txt", "--out", "o.png",
                "--width", "512", "--height", "256", "--seed", "3", "--steps", "8"});
        SelfTest.checkEq("args full: model", "m", full.model);
        SelfTest.checkEq("args full: width", 512, full.width);
        SelfTest.checkEq("args full: seed", 3, full.seed);
        SelfTest.checkEq("args full: steps", 8, full.steps);
        SelfTest.checkEq("args full: promptFile", "p.txt", full.promptFile);
        SelfTest.check("args missing account throws",
                throwsIllegal(() -> parseArgs(new String[]{"--prompt", "hi", "--out", "o.png"})));
        SelfTest.check("args missing prompt throws",
                throwsIllegal(() -> parseArgs(new String[]{"--account", "a", "--out", "o.png"})));
        SelfTest.check("args missing out throws",
                throwsIllegal(() -> parseArgs(new String[]{"--account", "a", "--prompt", "hi"})));
        SelfTest.check("args unknown flag throws",
                throwsIllegal(() -> parseArgs(new String[]{"--bogus"})));
        SelfTest.check("args bad width throws",
                throwsIllegal(() -> parseArgs(new String[]{"--account", "a", "--prompt", "hi",
                        "--out", "o", "--width", "nope"})));

        // --- multipart ---
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("prompt", "a cat");
        fields.put("width", "1024");
        fields.put("seed", "7");
        MultipartBody mp = buildMultipart(fields, "----MuseFormTEST");
        String mpText = new String(mp.body, StandardCharsets.US_ASCII);
        SelfTest.checkContains("multipart has field prompt", mpText, "name=\"prompt\"");
        SelfTest.checkContains("multipart has prompt value", mpText, "a cat");
        SelfTest.check("multipart keeps non-ascii prompt bytes utf8",
                new String(buildMultipart(Map.of("prompt", "caf\u00e9 \u2615"),
                        "----MuseFormUTF8").body, StandardCharsets.UTF_8)
                        .contains("caf\u00e9 \u2615"));
        SelfTest.checkContains("multipart has seed", mpText, "name=\"seed\"");
        SelfTest.checkContains("multipart closing boundary", mpText, "------MuseFormTEST--");
        SelfTest.check("multipart boundary shape",
                newBoundary().matches("----MuseForm[0-9a-f]{24}"));
        SelfTest.check("multipart boundaries unique",
                !newBoundary().equals(newBoundary()));

        // --- image extraction ---
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        SelfTest.check("extractImage: jpeg magic", extractImage(jpeg, "image/jpeg") == jpeg);
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9};
        SelfTest.check("extractImage: png magic", extractImage(png, "image/png") == png);
        String b64json = "{\"success\":true,\"result\":{\"image\":\"aGVsbG8=\"}}";
        byte[] fromB64 = extractImage(b64json.getBytes(StandardCharsets.UTF_8),
                "application/json");
        SelfTest.checkEq("extractImage: base64-in-JSON decodes",
                "hello", fromB64 == null ? null : new String(fromB64, StandardCharsets.UTF_8));
        String errJson = "{\"success\":false,\"errors\":[{\"code\":3030,\"message\":\"nope\"}]}";
        SelfTest.check("extractImage: error JSON -> null",
                extractImage(errJson.getBytes(StandardCharsets.UTF_8),
                        "application/json") == null);
        SelfTest.check("extractImage: garbage -> null",
                extractImage("not an image".getBytes(StandardCharsets.UTF_8),
                        "text/plain") == null);
        SelfTest.check("extractImage: short body -> null",
                extractImage(new byte[]{1, 2}, "image/png") == null);

        // --- sha1 (RFC 3174 test vector for "abc") ---
        SelfTest.checkEq("sha1Hex known vector",
                "a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"));

        // --- surrogate parsing ---
        String surrJson = "{\"credentials\":[{\"name\":\"access_token\","
                + "\"surrogate\":\"hsurr:ABC123\","
                + "\"placement\":\"bearer_header\"}]}";
        Surrogate s = parseSurrogateJson(surrJson);
        SelfTest.checkEq("surrogate value", "hsurr:ABC123", s.value);
        SelfTest.checkEq("surrogate placement", "bearer_header", s.placement);
        String customJson = "{\"credentials\":[{\"name\":\"access_token\","
                + "\"surrogate\":\"hsurr:X\","
                + "\"placement\":{\"custom_header\":\"X-Auth\"}}]}";
        SelfTest.checkEq("surrogate custom_header placement",
                "custom_header:X-Auth", parseSurrogateJson(customJson).placement);
        SelfTest.check("surrogate rejects non-hsurr",
                throwsIllegal(() -> parseSurrogateJson(
                        "{\"credentials\":[{\"name\":\"access_token\","
                                + "\"surrogate\":\"raw-secret\","
                                + "\"placement\":\"bearer_header\"}]}")));
        SelfTest.check("surrogate rejects missing entry",
                throwsIllegal(() -> parseSurrogateJson("{\"credentials\":[]}")));
        SelfTest.check("surrogate rejects bad shape",
                throwsIllegal(() -> parseSurrogateJson("[1,2]")));

        // --- host allow-list ---
        try {
            ensureAllowedUrl("https://api.cloudflare.com/client/v4/x");
            SelfTest.check("allow-list accepts api.cloudflare.com", true);
        } catch (IllegalArgumentException e) {
            SelfTest.check("allow-list accepts api.cloudflare.com", false);
        }
        SelfTest.check("allow-list rejects evil host",
                throwsIllegal(() -> ensureAllowedUrl("https://evil.example.com/x")));
        SelfTest.check("allow-list rejects lookalike",
                throwsIllegal(() -> ensureAllowedUrl(
                        "https://api.cloudflare.com.evil.example.com/x")));

        // --- Cloudflare error summary (error 3030 lesson) ---
        String cfErr = "{\"success\":false,\"errors\":[{\"code\":3030,"
                + "\"message\":\"output has been flagged by our content moderation system\"}]}";
        String summary = summarizeCfError(cfErr.getBytes(StandardCharsets.UTF_8));
        SelfTest.checkContains("cf error summary has code",
                summary == null ? "" : summary, "3030");
        SelfTest.checkContains("cf error summary has message",
                summary == null ? "" : summary, "flagged");
        SelfTest.check("cf error summary null on non-error JSON",
                summarizeCfError("{\"ok\":1}".getBytes(StandardCharsets.UTF_8)) == null);

        // --- log line format ---
        ZonedDateTime when = ZonedDateTime.of(2026, 9, 29, 11, 30, 0, 0, LOG_ZONE);
        String line = formatLogLine(when, "m", 7, 1024, 1024, "p.txt", "abc123", "o.png");
        SelfTest.checkOutput("log line carries every registry field", line,
                new String[]{"2026-09-29", "m", "seed 7", "1024x1024", "p.txt",
                        "sha1:abc123", "o.png"},
                new String[]{});
        SelfTest.checkEq("promptSourceName inline", "(inline)", promptSourceName(d));
        SelfTest.checkEq("promptSourceName file", "p.txt", promptSourceName(full));

        // --- tiny JSON parser round-trip ---
        Object parsed = Json.parse("{\"a\":[1,true,\"x\",null],\"b\":{\"c\":2.5}}");
        SelfTest.check("json parses nested",
                parsed instanceof Map && ((Map<?, ?>) parsed).get("a") instanceof List);
        SelfTest.checkEq("json stringify quotes",
                "{\"k\":\"v\"}", Json.stringify(Map.of("k", "v")));

        return 0; // failures are counted inside SelfTest; summary() reports them
    }

    private static boolean throwsIllegal(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // --verify
    // ------------------------------------------------------------------

    static void runVerify() {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        Path self = Paths.get(System.getenv("HOME"), "workspace", "av_free_stack", "CfImage.java");
        if (Files.isRegularFile(self)) {
            infos.add("CfImage.java present at " + self);
        } else {
            failures.add("CfImage.java missing at " + self);
        }
        Path classes = Paths.get(System.getenv("HOME"), "workspace", "java-shared", "classes");
        boolean helpers = Files.isRegularFile(classes.resolve("SelfTest.class"))
                && Files.isRegularFile(classes.resolve("Verify.class"));
        if (helpers) {
            infos.add("java-shared helpers compiled (SelfTest, Verify)");
        } else {
            failures.add("java-shared helpers not compiled — run ~/workspace/java-shared/build.sh");
        }
        Path log = logPath();
        if (Files.isRegularFile(log)) {
            infos.add("generation_log.md initialized at " + log);
        } else {
            infos.add("generation_log.md not yet created — first generation will initialize it");
        }
        String sock = authdSocketPath();
        if (Files.exists(Paths.get(sock))) {
            infos.add("authd socket reachable at " + sock);
        } else {
            failures.add("authd socket missing at " + sock + " — authentication impossible");
        }

        runSelfTests();
        // SelfTest printed its own lines; summary() prints "N passed, M failed"
        // exactly once and returns the failure count for the report below.
        int failed = SelfTest.summary();
        if (failed > 0) {
            failures.add("self-test had " + failed + " failure(s) — see lines above");
        } else {
            infos.add("self-test green");
        }
        Verify.report("VERIFY OK (CfImage): source, helpers, socket and self-test all check out.",
                "VERIFY FAILED (CfImage):", infos, failures);
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser/stringifier (no external deps on the sandbox).
    // Handles the shapes Workers AI and authd return: objects, arrays,
    // strings (with escapes), numbers, true/false/null.
    // ------------------------------------------------------------------

    static final class Json {
        private final String src;
        private int pos;

        private Json(String src) {
            this.src = src;
        }

        static Object parse(String src) {
            Json j = new Json(src);
            Object v = j.value();
            j.ws();
            if (j.pos != j.src.length()) {
                throw new IllegalArgumentException(
                        "trailing characters after JSON value at " + j.pos);
            }
            return v;
        }

        static String stringify(Object v) {
            StringBuilder sb = new StringBuilder();
            write(v, sb);
            return sb.toString();
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object v, StringBuilder sb) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String) {
                sb.append(quote((String) v));
            } else if (v instanceof Boolean || v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(quote(e.getKey()));
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (v instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object e : (List<Object>) v) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(e, sb);
                }
                sb.append(']');
            } else {
                throw new IllegalArgumentException("cannot stringify " + v.getClass());
            }
        }

        private void ws() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return src.charAt(pos);
        }

        private Object value() {
            ws();
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            ws();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw new IllegalArgumentException(
                            "expected string key at " + pos);
                }
                String k = string();
                ws();
                if (peek() != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at " + pos);
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            ws();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return l;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at " + pos);
                }
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException(
                                "bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object literal(String word, Object val) {
            if (src.startsWith(word, pos)) {
                pos += word.length();
                return val;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Number number() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("bad value at " + pos);
            }
            String tok = src.substring(start, pos);
            try {
                if (tok.indexOf('.') < 0 && tok.indexOf('e') < 0 && tok.indexOf('E') < 0) {
                    return Long.parseLong(tok);
                }
                return Double.parseDouble(tok);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number: " + tok);
            }
        }
    }
}
