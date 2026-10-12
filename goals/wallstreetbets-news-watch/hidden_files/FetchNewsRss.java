import java.io.ByteArrayInputStream;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Fetch the newest r/wallstreetbets posts flaired News via Reddit's public RSS.
 *
 * <p>Java port of fetch_news_rss.py. No arguments; prints a JSON list of
 * {@code {"id","title","link","updated"}} objects to stdout (2-space indent,
 * exactly like Python's {@code json.dump(..., indent=2)}), then a newline.
 *
 * <p>The User-Agent header is passed through unchanged from the Python script.
 * Any failure (network error, HTTP error, unparsable feed) propagates as an
 * uncaught exception — the Python script likewise lets it crash with a
 * traceback and a non-zero exit code.
 */
public class FetchNewsRss {

    // Same UA string the Python script sends (kept verbatim).
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "wsb-news-watch/1.0 (+contact: automated alert script)";

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    // Same regex the Python script uses to pull the post id out of the permalink.
    private static final Pattern POST_ID_PATTERN = Pattern.compile("/comments/([a-z0-9]+)/");


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
        // Build the search URL. urllib.parse.urlencode uses quote_plus, which is
        // what URLEncoder does (space -> '+', '"' -> %22, ':' -> %3A).
        // Parameter order is q, restrict_sr, sort (dict insertion order).
        String query = "q=" + URLEncoder.encode("flair:\"News\"", StandardCharsets.UTF_8)
                + "&restrict_sr=" + URLEncoder.encode("on", StandardCharsets.UTF_8)
                + "&sort=" + URLEncoder.encode("new", StandardCharsets.UTF_8);
        String url = "https://www.reddit.com/r/wallstreetbets/search.rss?" + query;

        HttpClient http = withEnvProxy(HttpClient.newBuilder())
                .followRedirects(HttpClient.Redirect.NORMAL) // urllib follows redirects too
                .sslContext(tlsContext())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30)) // the Python script's urlopen timeout
                .GET()
                .build();
        HttpResponse<byte[]> response =
                http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // urllib raises HTTPError here; an uncaught exception in Java gives
            // the same observable behavior (stderr + non-zero exit).
            throw new java.io.IOException("HTTP Error " + status + " for " + url);
        }

        // ET.fromstring(raw) parses the raw bytes, honoring the XML declaration.
        Element root = parseXml(response.body());

        List<Map<String, Object>> posts = new ArrayList<>();
        // root.findall("{ATOM}entry"): direct children in the Atom namespace only.
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

            // title_el.text.strip() if title_el is not None and title_el.text else ""
            String titleText = titleEl == null ? null : directText(titleEl);
            String title = (titleText != null && !titleText.isEmpty()) ? titleText.strip() : "";
            // link_el.get("href") if link_el is not None else ""
            String href = linkEl == null ? "" : linkEl.getAttribute("href");
            // updated_el.text if updated_el is not None else "" (may stay null,
            // which json prints as null — same as the Python script)
            String updated = updatedEl == null ? "" : directText(updatedEl);

            Matcher m = POST_ID_PATTERN.matcher(href);
            String pid = m.find() ? m.group(1) : "";
            if (!title.isEmpty() && !href.isEmpty()) {
                Map<String, Object> post = new LinkedHashMap<>();
                post.put("id", pid);
                post.put("title", title);
                post.put("link", href);
                post.put("updated", updated);
                posts.add(post);
            }
        }

        System.out.print(Json.pretty(posts));
        System.out.println(); // the script's trailing print()
    }

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

    /**
     * ElementTree's {@code element.text}: the text before the first child
     * element, or null when the element holds no direct text.
     */
    private static String directText(Element element) {
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

    // ======================================================================
    // Minimal JSON writer matching Python's json.dump(..., indent=2)
    // ======================================================================

    private static final class Json {

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
            } else if (value instanceof Boolean || value instanceof Number) {
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
    }
}
