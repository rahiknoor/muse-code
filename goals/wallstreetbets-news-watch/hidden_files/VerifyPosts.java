import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Check whether given r/wallstreetbets post ids are still live (not removed).
 *
 * <p>Java port of verify_posts.py.
 *
 * <p>Usage: VerifyPosts &lt;id&gt; [&lt;id&gt; ...]
 * Prints JSON: {@code {"<id>": true|false|null}} (null = could not verify),
 * using Python's compact {@code json.dumps} separators ({@code ", "} and
 * {@code ": "}), followed by a newline.
 *
 * <p>A post is live unless the listing JSON shows {@code removed_by_category}
 * set, or its selftext is {@code [removed]} / {@code [deleted]}. Each id is
 * tried on www.reddit.com first, then old.reddit.com; if both fail the value
 * is null. The User-Agent and Accept headers are passed through unchanged.
 */
public class VerifyPosts {

    // Same headers the Python script sends (kept verbatim).
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String ACCEPT = "application/json";

    private static final HttpClient HTTP = withEnvProxy(HttpClient.newBuilder())
            .followRedirects(HttpClient.Redirect.NORMAL) // urllib follows redirects too
            .sslContext(tlsContext())
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * Honor the same proxy environment variables Python's urllib honors
     * (https_proxy / http_proxy, including embedded credentials). Without
     * this, direct TLS connections fail in proxied environments. Credentials
     * are read from the environment at runtime and never stored.
     */
    private static HttpClient.Builder withEnvProxy(HttpClient.Builder builder) {
        String proxyUrl = System.getenv("https_proxy");
        if (proxyUrl == null) proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl == null) proxyUrl = System.getenv("http_proxy");
        if (proxyUrl == null) proxyUrl = System.getenv("HTTP_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return builder; // no proxy configured: connect directly
        }
        URI uri = URI.create(proxyUrl.trim());
        int port = uri.getPort() < 0 ? 3128 : uri.getPort();
        java.net.InetSocketAddress proxyAddress;
        try {
            proxyAddress = new InetSocketAddress(resolveProxyHost(uri.getHost()), port);
        } catch (java.net.UnknownHostException e) {
            proxyAddress = new InetSocketAddress(uri.getHost(), port); // let connect fail naturally
        }
        builder.proxy(ProxySelector.of(proxyAddress));
        String userInfo = uri.getUserInfo(); // "user:password", if present
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            String user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            char[] password = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8).toCharArray()
                    : new char[0];
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, password);
                    }
                    return null;
                }
            });
        }
        return builder;
    }

    /**
     * Resolve the proxy host, preferring an IPv6 address when one exists.
     * (In this sandbox the proxy hostname resolves to both a sandbox-gateway
     * IPv4 address that refuses the connection and the real proxy's IPv6
     * address; the system resolver and curl/python all end up on the IPv6 one.)
     */
    private static java.net.InetAddress resolveProxyHost(String host)
            throws java.net.UnknownHostException {
        java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
        for (java.net.InetAddress address : addresses) {
            if (address instanceof java.net.Inet6Address) {
                return address;
            }
        }
        return addresses[0];
    }

    /**
     * TLS trust that mirrors what Python's requests does in this environment:
     * the default CA set plus the sandbox's egress MITM CA bundle, whose path
     * is published via the SSL_CERT_FILE / REQUESTS_CA_BUNDLE env vars (the
     * JDK does not read those on its own). Falls back to the plain defaults
     * when no bundle is configured.
     */
    private static javax.net.ssl.SSLContext tlsContext() {
        try {
            String caBundle = System.getenv("SSL_CERT_FILE");
            if (caBundle == null) {
                caBundle = System.getenv("REQUESTS_CA_BUNDLE");
            }
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null); // default cacerts
            if (caBundle != null && !caBundle.isBlank()
                    && java.nio.file.Files.isReadable(java.nio.file.Path.of(caBundle.trim()))) {
                java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                java.security.KeyStore ks =
                        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                ks.load(null, null);
                int n = 0;
                for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof javax.net.ssl.X509TrustManager xtm) {
                        for (java.security.cert.X509Certificate c : xtm.getAcceptedIssuers()) {
                            ks.setCertificateEntry("default-" + (n++), c);
                        }
                    }
                }
                try (java.io.InputStream in = java.nio.file.Files
                        .newInputStream(java.nio.file.Path.of(caBundle.trim()))) {
                    for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
                        ks.setCertificateEntry("bundle-" + (n++), c);
                    }
                }
                tmf.init(ks);
            }
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build TLS context", e);
        }
    }


    /**
     * Home directory for workspace paths. In this runtime
     * System.getProperty("user.home") returns /root (the passwd entry) while
     * $HOME is /home/hatch -- and Python's os.path.expanduser("~/...") honors
     * $HOME. So always prefer the HOME environment variable, exactly like
     * Python does. Never call System.getProperty("user.home") directly for
     * workspace paths.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) {
        // {pid: is_live(pid) for pid in sys.argv[1:]} — argv order preserved.
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String pid : args) {
            results.put(pid, isLive(pid));
        }
        // LinkedHashMap with null values: key order kept, nulls print as null.
        System.out.println(Json.compact(results));
    }

    /**
     * True = live, False = removed/deleted, null = verification blocked.
     * Mirrors the Python is_live(): any exception moves on to the next host.
     */
    private static Boolean isLive(String pid) {
        for (String host : List.of("www.reddit.com", "old.reddit.com")) {
            try {
                byte[] raw = fetchJson(
                        "https://" + host + "/r/wallstreetbets/comments/" + pid + "/.json");
                Map<String, Object> data = asObject(Json.parse(
                        new String(raw, StandardCharsets.UTF_8)));
                // data[0]["data"]["children"][0]["data"]
                Map<String, Object> d = asObject(asObject(asList(
                        asObject(data).get("data")).get(0)).get("data"));
                Map<String, Object> post = asObject(asList(
                        asObject(d).get("children")).get(0));
                Map<String, Object> postData = asObject(post.get("data"));

                if (pythonTruthy(postData.get("removed_by_category"))) {
                    return false;
                }
                Object selftext = postData.get("selftext");
                if ("[removed]".equals(selftext) || "[deleted]".equals(selftext)) {
                    return false;
                }
                return true;
            } catch (Exception e) {
                continue; // try the next host, like the Python except: continue
            }
        }
        return null;
    }

    private static byte[] fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .timeout(Duration.ofSeconds(20)) // the Python script's urlopen timeout
                .GET()
                .build();
        HttpResponse<byte[]> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // urllib raises HTTPError here, which the Python code catches and
            // treats as "try the next host".
            throw new java.io.IOException("HTTP Error " + status + " for " + url);
        }
        return response.body();
    }

    /**
     * Python truthiness for JSON values: null/None, false, 0, "" and empty
     * containers are falsy; everything else (including the string
     * "moderator" that Reddit puts in removed_by_category) is truthy.
     */
    private static boolean pythonTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Json.JsonNumber n) {
            String t = n.text();
            return !(t.equals("0") || t.equals("0.0") || t.matches("-?0+(\\.0+)?([eE][+-]?0+)?"));
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static List<Object> asList(Object o) {
        List<Object> list = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object item : l) {
                list.add(item);
            }
        }
        return list;
    }

    // ======================================================================
    // Minimal JSON support: a small parser plus a compact writer matching
    // Python's json.dumps default separators (", " and ": ").
    // ======================================================================

    private static final class Json {

        /** A JSON number with its original characters preserved verbatim. */
        record JsonNumber(String text) {
            @Override public String toString() { return text; }
        }

        static Object parse(String text) {
            Parser parser = new Parser(text);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.pos != text.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON data");
            }
            return value;
        }

        /**
         * Compact rendering like {@code json.dumps(...)}: no extra spaces
         * except after separators — {@code {"abc": true, "def": null}}.
         */
        static String compact(Object value) {
            StringBuilder out = new StringBuilder();
            writeCompact(value, out);
            return out.toString();
        }

        private static void writeCompact(Object value, StringBuilder out) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String s) {
                writeString(s, out);
            } else if (value instanceof Boolean || value instanceof JsonNumber) {
                out.append(value);
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    writeCompact(list.get(i), out);
                }
                out.append(']');
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    writeString((String) entry.getKey(), out);
                    out.append(": ");
                    writeCompact(entry.getValue(), out);
                    i++;
                }
                out.append('}');
            } else {
                throw new IllegalArgumentException("Cannot JSON-encode: " + value);
            }
        }

        /**
         * String quoting with ensure_ascii=True semantics: control characters
         * get short escapes (or U+00XX) and every non-ASCII character becomes
         * U+XXXX (astral characters as UTF-16 surrogate pairs, like Python).
         */
        private static void writeString(String s, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    default -> {
                        if (cp < 0x20 || cp > 0x7E) {
                            if (cp > 0xFFFF) {
                                out.append(String.format("\\u%04x\\u%04x",
                                        (int) Character.highSurrogate(cp),
                                        (int) Character.lowSurrogate(cp)));
                            } else {
                                out.append(String.format("\\u%04x", cp));
                            }
                        } else {
                            out.append((char) cp);
                        }
                    }
                }
                i += Character.charCount(cp);
            }
            out.append('"');
        }

        private static final class Parser {
            private final String s;
            private int pos;

            Parser(String s) { this.s = s; }

            void skipWhitespace() {
                while (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Object parseValue() {
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON");
                }
                return switch (s.charAt(pos)) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> { expect("true"); yield Boolean.TRUE; }
                    case 'f' -> { expect("false"); yield Boolean.FALSE; }
                    case 'n' -> { expect("null"); yield null; }
                    default -> parseNumber();
                };
            }

            private void expect(String literal) {
                if (!s.startsWith(literal, pos)) {
                    throw new IllegalArgumentException("Invalid JSON literal at " + pos);
                }
                pos += literal.length();
            }

            private Map<String, Object> parseObject() {
                pos++; // consume '{'
                Map<String, Object> map = new LinkedHashMap<>();
                skipWhitespace();
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    if (pos >= s.length() || s.charAt(pos) != ':') {
                        throw new IllegalArgumentException("Expected ':' in object");
                    }
                    pos++;
                    map.put(key, parseValue());
                    skipWhitespace();
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated object");
                    }
                    char c = s.charAt(pos++);
                    if (c == '}') {
                        return map;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("Expected ',' or '}' in object");
                    }
                }
            }

            private List<Object> parseArray() {
                pos++; // consume '['
                List<Object> list = new ArrayList<>();
                skipWhitespace();
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWhitespace();
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated array");
                    }
                    char c = s.charAt(pos++);
                    if (c == ']') {
                        return list;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("Expected ',' or ']' in array");
                    }
                }
            }

            private String parseString() {
                pos++; // consume opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated string");
                    }
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (pos >= s.length()) {
                            throw new IllegalArgumentException("Unterminated escape");
                        }
                        char e = s.charAt(pos++);
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
                                if (pos + 4 > s.length()) {
                                    throw new IllegalArgumentException("Bad \\u escape");
                                }
                                int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                                pos += 4;
                                // Combine surrogate pairs, like Python's json does.
                                if (Character.isHighSurrogate((char) code)
                                        && pos + 6 <= s.length()
                                        && s.charAt(pos) == '\\' && s.charAt(pos + 1) == 'u') {
                                    int low = Integer.parseInt(s.substring(pos + 2, pos + 6), 16);
                                    if (Character.isLowSurrogate((char) low)) {
                                        sb.append(Character.toChars(
                                                Character.toCodePoint((char) code, (char) low)));
                                        pos += 6;
                                        break;
                                    }
                                }
                                sb.append((char) code);
                            }
                            default -> throw new IllegalArgumentException("Bad escape: \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            private JsonNumber parseNumber() {
                int start = pos;
                if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                    pos++;
                }
                while (pos < s.length()
                        && (Character.isDigit(s.charAt(pos)) || ".eE+-".indexOf(s.charAt(pos)) >= 0)) {
                    pos++;
                }
                if (start == pos) {
                    throw new IllegalArgumentException("Invalid JSON value at " + pos);
                }
                return new JsonNumber(s.substring(start, pos));
            }
        }
    }
}
