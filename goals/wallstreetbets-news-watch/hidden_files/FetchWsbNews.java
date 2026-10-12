import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Fetch the newest r/wallstreetbets posts flaired News via multiple fallback sources.
 *
 * <p>Java port of fetch_wsb_news.py. Tries, in order:
 * <ol>
 *   <li>old.reddit.com search RSS (different infra, often works when www is blocked)
 *   <li>www.reddit.com search RSS
 *   <li>www.reddit.com search JSON
 *   <li>Arctic Shift API (Reddit archive mirror)
 *   <li>PullPush API (Reddit archive mirror)
 * </ol>
 *
 * <p>Prints a JSON list of {@code {"id","title","link","created_utc"}} to stdout.
 * Prints {@code []} when every source fails so the caller can fall back to
 * browser tools. Logs the working source to stderr (does not affect stdout
 * parsing) in the same {@code [wsb-fetch] ...} format as the Python script,
 * and sleeps 1 second after each failed source just like {@code time.sleep(1)}.
 *
 * <p>The User-Agent and Accept headers are passed through unchanged from the
 * Python script. Numbers coming back from the JSON APIs (e.g. created_utc)
 * keep their exact original characters when re-printed, because Java and
 * Python format doubles differently.
 */
public class FetchWsbNews {

    // Same headers the Python script sends (kept verbatim).
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String ACCEPT =
            "application/rss+xml, application/atom+xml, application/json, text/xml, */*";

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    // Same regex the Python script uses to pull the post id out of the permalink.
    private static final Pattern POST_ID_PATTERN = Pattern.compile("/comments/([a-z0-9]+)/");

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

    // ======================================================================
    // Entry point: the fallback chain
    // ======================================================================


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

    public static void main(String[] args) throws Exception {
        // (name, fetch-function) pairs, in the same order as the Python SOURCES list.
        List<Source> sources = List.of(
                new Source("old.reddit RSS", () -> tryRedditRss("old.reddit.com")),
                new Source("www.reddit RSS", () -> tryRedditRss("www.reddit.com")),
                new Source("www.reddit JSON", FetchWsbNews::tryRedditJson),
                new Source("arcticshift", FetchWsbNews::tryArcticshift),
                new Source("pullpush", FetchWsbNews::tryPullpush));

        for (Source source : sources) {
            List<Map<String, Object>> posts;
            try {
                posts = source.fetcher().get();
            } catch (Exception e) {
                // Python prints: [wsb-fetch] <name> failed: <ExceptionType>: <message>
                System.err.println("[wsb-fetch] " + source.name + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                // Human gap between fallback sources (never metronomic) — Polite
                // paces per host-bucket with jitter so retries don't look scripted.
                Polite.paceHuman("wsb-fallback-chain", 1200);
                continue;
            }
            if (!posts.isEmpty()) {
                System.err.println("[wsb-fetch] source OK: " + source.name
                        + " (" + posts.size() + " posts)");
                System.out.print(Json.pretty(posts));
                System.out.println(); // the script's trailing print()
                return;
            }
            System.err.println("[wsb-fetch] " + source.name + ": no posts returned");
        }
        System.out.println("[]");
    }

    /** A named source in the fallback chain. */
    private record Source(String name, Fetcher fetcher) {}

    /** Functional interface so source failures surface as checked exceptions. */
    private interface Fetcher {
        List<Map<String, Object>> get() throws Exception;
    }

    // ======================================================================
    // HTTP helper (mirrors the Python http_get)
    // ======================================================================

    /** Thrown for non-2xx responses; urllib would raise HTTPError here. */
    private static final class HttpStatusException extends IOException {
        HttpStatusException(int status, String url) {
            super("HTTP Error " + status + " for " + url);
        }
    }

    private static byte[] httpGet(String url) throws Exception {
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
            throw new HttpStatusException(status, url);
        }
        return response.body();
    }

    /** Query-string builder equivalent to urllib.parse.urlencode (quote_plus). */
    private static String query(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(keyValues[i], StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(keyValues[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ======================================================================
    // Source 1+2: Reddit search RSS (Atom on www, RSS 2.0 on old)
    // ======================================================================

    private static List<Map<String, Object>> tryRedditRss(String host) throws Exception {
        String q = query("q", "flair:\"News\"", "restrict_sr", "on",
                "sort", "new", "t", "week");
        byte[] raw = httpGet("https://" + host + "/r/wallstreetbets/search.rss?" + q);
        return parseAtomOrRss(raw);
    }

    /**
     * Handle both Atom (www.reddit.com) and RSS 2.0 (old.reddit.com) feeds,
     * mirroring parse_atom_or_rss: Atom entries first; RSS items only if no
     * Atom entries produced any posts.
     */
    private static List<Map<String, Object>> parseAtomOrRss(byte[] raw) throws Exception {
        Element root = parseXml(raw);

        List<Map<String, Object>> posts = new ArrayList<>();
        // root.findall("{ATOM}entry"): direct children in the Atom namespace.
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE
                    || !"entry".equals(node.getLocalName())
                    || !ATOM_NS.equals(node.getNamespaceURI())) {
                continue;
            }
            Element entry = (Element) node;
            Element titleEl = directChild(entry, ATOM_NS, "title");
            Element linkEl = directChild(entry, ATOM_NS, "link");
            Element updatedEl = directChild(entry, ATOM_NS, "updated");

            String titleText = titleEl == null ? null : directText(titleEl);
            String title = (titleText != null && !titleText.isEmpty()) ? titleText.strip() : "";
            String href = linkEl == null ? "" : linkEl.getAttribute("href");
            String updated = updatedEl == null ? "" : directText(updatedEl);
            if (!title.isEmpty() && !href.isEmpty()) {
                posts.add(makePost(postIdFrom(href), title, href, updated));
            }
        }
        if (!posts.isEmpty()) {
            return posts;
        }
        // RSS 2.0 items: root.findall(".//item") — any descendant <item>.
        for (Element item : descendants(root, "item")) {
            String titleText = directText(plainChild(item, "title"));
            String title = (titleText != null && !titleText.isEmpty()) ? titleText.strip() : "";
            String hrefText = directText(plainChild(item, "link"));
            String href = hrefText == null ? "" : hrefText.strip();
            String pub = directText(plainChild(item, "pubDate"));
            if (pub == null) {
                pub = "";
            }
            if (!title.isEmpty() && !href.isEmpty()) {
                posts.add(makePost(postIdFrom(href), title, href, pub));
            }
        }
        return posts;
    }

    // ======================================================================
    // Source 3: Reddit search JSON
    // ======================================================================

    private static List<Map<String, Object>> tryRedditJson() throws Exception {
        String q = query("q", "flair:\"News\"", "restrict_sr", "on",
                "sort", "new", "limit", "25", "t", "week");
        byte[] raw = httpGet("https://www.reddit.com/r/wallstreetbets/search.json?" + q);
        Map<String, Object> data = asObject(Json.parse(new String(raw, StandardCharsets.UTF_8)));

        List<Map<String, Object>> posts = new ArrayList<>();
        Map<String, Object> dataObj = asObject(data.get("data"));
        for (Object child : asList(dataObj == null ? null : dataObj.get("children"))) {
            Map<String, Object> d = asObject(asObject(child).get("data"));
            if (d == null || !"news".equals(str(d.get("link_flair_text")).toLowerCase(Locale.ROOT))) {
                continue;
            }
            posts.add(makePost(
                    str(d.get("id")),
                    str(d.get("title")),
                    "https://www.reddit.com" + str(d.get("permalink")),
                    d.containsKey("created_utc") ? d.get("created_utc") : ""));
        }
        return posts;
    }

    // ======================================================================
    // Source 4: Arctic Shift API
    // ======================================================================

    private static List<Map<String, Object>> tryArcticshift() throws Exception {
        String q = query("subreddit", "wallstreetbets", "limit", "50", "sort", "desc");
        byte[] raw = httpGet("https://arcticshift.io/api/posts/search?" + q);
        Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        // data.get("data", data if isinstance(data, list) else [])
        List<Object> items;
        if (parsed instanceof Map<?, ?> map) {
            items = asList(((Map<?, ?>) map).get("data"));
        } else if (parsed instanceof List) {
            items = asList(parsed);
        } else {
            items = new ArrayList<>();
        }

        List<Map<String, Object>> posts = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> d = asObject(item);
            if (d == null || !"news".equals(str(d.get("link_flair_text")).toLowerCase(Locale.ROOT))) {
                continue;
            }
            String pid = str(d.get("id"));
            String permalink = str(d.get("permalink"));
            String link = permalink.isEmpty()
                    ? "https://www.reddit.com/r/wallstreetbets/comments/" + pid + "/"
                    : "https://www.reddit.com" + permalink;
            posts.add(makePost(pid, str(d.get("title")), link,
                    d.containsKey("created_utc") ? d.get("created_utc") : ""));
        }
        return posts;
    }

    // ======================================================================
    // Source 5: PullPush API
    // ======================================================================

    private static List<Map<String, Object>> tryPullpush() throws Exception {
        String q = query("subreddit", "wallstreetbets", "size", "50",
                "sort", "desc", "sort_type", "created_utc");
        byte[] raw = httpGet("https://api.pullpush.io/reddit/search/submission/?" + q);
        Map<String, Object> data = asObject(Json.parse(new String(raw, StandardCharsets.UTF_8)));

        List<Map<String, Object>> posts = new ArrayList<>();
        for (Object item : asList(data == null ? null : data.get("data"))) {
            Map<String, Object> d = asObject(item);
            if (d == null || !"news".equals(str(d.get("link_flair_text")).toLowerCase(Locale.ROOT))) {
                continue;
            }
            String pid = str(d.get("id"));
            String permalink = str(d.get("permalink"));
            String link = permalink.isEmpty()
                    ? "https://www.reddit.com/r/wallstreetbets/comments/" + pid + "/"
                    : "https://www.reddit.com" + permalink;
            posts.add(makePost(pid, str(d.get("title")), link,
                    d.containsKey("created_utc") ? d.get("created_utc") : ""));
        }
        return posts;
    }

    // ======================================================================
    // Small helpers
    // ======================================================================

    private static Map<String, Object> makePost(String id, String title, String link, Object createdUtc) {
        Map<String, Object> post = new LinkedHashMap<>();
        post.put("id", id);
        post.put("title", title);
        post.put("link", link);
        post.put("created_utc", createdUtc);
        return post;
    }

    private static String postIdFrom(String href) {
        Matcher m = POST_ID_PATTERN.matcher(href == null ? "" : href);
        return m.find() ? m.group(1) : "";
    }


    /**
     * Namespace-aware XML parsing (Python's ElementTree always is). A silent
     * error handler keeps the parser from printing "[Fatal Error]" lines to
     * stderr -- the Python script never prints those either.
     */
    private static Element parseXml(byte[] raw) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override public void warning(org.xml.sax.SAXParseException e) { }
            @Override public void error(org.xml.sax.SAXParseException e) { }
            @Override public void fatalError(org.xml.sax.SAXParseException e) { }
        });
        return builder.parse(new ByteArrayInputStream(raw)).getDocumentElement();
    }

    /** First direct child element with the given namespace + local name (or null). */
    private static Element directChild(Element parent, String namespace, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(node.getLocalName())
                    && namespace.equals(node.getNamespaceURI())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** First direct child element with a plain (namespace-less) tag name, or null. */
    private static Element plainChild(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())
                    && (node.getNamespaceURI() == null || node.getNamespaceURI().isEmpty())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** All descendant elements with a plain (namespace-less) tag name. */
    private static List<Element> descendants(Element root, String tagName) {
        List<Element> found = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) node;
            if (tagName.equals(el.getNodeName())
                    && (el.getNamespaceURI() == null || el.getNamespaceURI().isEmpty())) {
                found.add(el);
            }
            found.addAll(descendants(el, tagName));
        }
        return found;
    }

    /**
     * ElementTree's {@code element.text}: the text before the first child
     * element, or null when the element holds no direct text.
     */
    private static String directText(Element element) {
        if (element == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                break;
            }
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(child.getNodeValue());
                found = true;
            }
        }
        return found ? sb.toString() : null;
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

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    // ======================================================================
    // Minimal JSON support:
    //   * parser that keeps number literals verbatim (JsonNumber), so values
    //     like created_utc print back exactly as the API sent them;
    //   * writer matching Python's json.dump(..., indent=2) including
    //     ensure_ascii=True string escaping.
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

        /** Render a value exactly like {@code json.dumps(value, indent=2)}. */
        static String pretty(Object value) {
            StringBuilder out = new StringBuilder();
            writePretty(value, out, 0);
            return out.toString();
        }

        private static void writePretty(Object value, StringBuilder out, int indent) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String s) {
                writeString(s, out);
            } else if (value instanceof Boolean || value instanceof JsonNumber) {
                out.append(value);
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    out.append("  ".repeat(indent + 1));
                    writePretty(list.get(i), out, indent + 1);
                    if (i < list.size() - 1) {
                        out.append(',');
                    }
                    out.append('\n');
                }
                out.append("  ".repeat(indent)).append(']');
            } else if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.append("  ".repeat(indent + 1));
                    writeString((String) entry.getKey(), out);
                    out.append(": ");
                    writePretty(entry.getValue(), out, indent + 1);
                    if (i < map.size() - 1) {
                        out.append(',');
                    }
                    out.append('\n');
                    i++;
                }
                out.append("  ".repeat(indent)).append('}');
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
