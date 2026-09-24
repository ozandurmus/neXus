package com.securityexpert.nexus.ui2.worker.transport.https;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;

/**
 * The generic HTTPS client for vendors backed up over HTTPS (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §0.2): basic auth
 * sent pre-emptively, form and JSON POST, streamed download into a sink with a size cap, redirects followed only on
 * the same host, TLS as the PAN decision record directs for this internal environment (validity dates checked, chain
 * and host name not). One client per call; no cookie state is kept between runs.
 */
public final class HttpsDeviceClient implements HttpsDeviceCalls {

    public record Target(String host, int port) {
        public URI uri(String pathAndQuery) {
            return URI.create("https://" + host + (port == 443 ? "" : ":" + port) + pathAndQuery);
        }
    }

    public record Credentials(String username, char[] password) {
        String basic() {
            return "Basic " + Base64.getEncoder().encodeToString((username + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
        }

        /** A session obtained by {@link HttpsDeviceCalls#login}: sent as its cookie, never as basic auth. */
        public static Credentials session(String cookie) {
            return new Credentials(null, cookie.toCharArray());
        }

        boolean isSession() {
            return username == null;
        }
    }

    /** {@code cookie} is "NAME=value" of the session cookie the login set (JSESSIONID), never logged. */
    public record SessionLogin(int status, Optional<String> cookie) {
    }

    /** A text answer, bounded: {@code body} is at most {@code maxBytes} long; {@code truncated} says when more came. */
    public record TextResponse(int status, Optional<String> contentType, String body, boolean truncated) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public sealed interface DownloadResult {
        record Downloaded(int status, long bytes, Optional<String> contentType) implements DownloadResult {
        }

        record Refused(int status, String reason) implements DownloadResult {
        }

        record Failed(String reason) implements DownloadResult {
        }
    }

    private static final int MAX_REDIRECTS = 3;
    private final HttpClient client;

    public HttpsDeviceClient() {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanXmlApiTransport
                    .paloAltoDeviceTrustManager()}, new SecureRandom());
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm("");
            this.client = HttpClient.newBuilder().sslContext(ssl).sslParameters(params)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not build the HTTPS device TLS configuration", e);
        }
    }

    @Override
    public TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) throws IOException, InterruptedException {
        return text(target, "GET", path, null, null, creds, timeout, maxBytes);
    }

    public TextResponse postForm(Target target, String path, Map<String, String> form, Credentials creds, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return text(target, "POST", path, "application/x-www-form-urlencoded", b.toString(), creds, timeout, maxBytes);
    }

    @Override
    public TextResponse postJson(Target target, String path, String json, Credentials creds, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        return text(target, "POST", path, "application/json", json, creds, timeout, maxBytes);
    }

    @Override
    public SessionLogin login(Target target, String path, String json, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(target.uri(path)).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> r = client.send(request, HttpResponse.BodyHandlers.discarding());
        Optional<String> cookie = r.headers().allValues("Set-Cookie").stream()
                .map(v -> v.split(";", 2)[0].strip())
                .filter(v -> v.toUpperCase(Locale.ROOT).startsWith("JSESSIONID="))
                .findFirst();
        return new SessionLogin(r.statusCode(), cookie);
    }

    /** Streams a GET or form POST answer into {@code sink}, refusing past {@code maxBytes}; never buffered whole. */
    @Override
    public DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            OutputStream sink, long maxBytes, Duration timeout) {
        try {
            String body = form == null ? null : formEncode(form);
            HttpResponse<InputStream> r = send(target, method, path, body == null ? null : "application/x-www-form-urlencoded", body,
                    creds, timeout, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() < 200 || r.statusCode() >= 300) {
                    return new DownloadResult.Refused(r.statusCode(), "HTTP " + r.statusCode());
                }
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > maxBytes) {
                        return new DownloadResult.Failed("larger than the " + maxBytes + "-byte bound");
                    }
                    sink.write(buf, 0, n);
                }
                return new DownloadResult.Downloaded(r.statusCode(), total, r.headers().firstValue("Content-Type"));
            }
        } catch (IOException e) {
            return new DownloadResult.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DownloadResult.Failed("interrupted");
        }
    }

    /** The path-and-query of an absolute URL the device returned, only if it names the same host (never a redirect off it). */
    public static Optional<String> sameHostPath(Target target, String absoluteUrl) {
        try {
            URI u = URI.create(absoluteUrl.strip());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || !u.getHost().toLowerCase(Locale.ROOT).equals(target.host().toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            int port = u.getPort() > 0 ? u.getPort() : 443;
            if (port != target.port()) {
                return Optional.empty();
            }
            return Optional.of(u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private TextResponse text(Target target, String method, String path, String contentType, String body, Credentials creds,
            Duration timeout, int maxBytes) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = send(target, method, path, contentType, body, creds, timeout, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            boolean truncated = bytes.length > maxBytes;
            String s = new String(bytes, 0, Math.min(bytes.length, maxBytes), StandardCharsets.UTF_8);
            return new TextResponse(r.statusCode(), r.headers().firstValue("Content-Type"), s, truncated);
        }
    }

    private <T> HttpResponse<T> send(Target target, String method, String path, String contentType, String body, Credentials creds,
            Duration timeout, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        String current = path;
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(target.uri(current)).timeout(timeout);
            if (creds.isSession()) {
                b.header("Cookie", new String(creds.password()));
            } else {
                b.header("Authorization", creds.basic());
            }
            if ("POST".equals(method) && hop == 0) {
                b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
                if (contentType != null) {
                    b.header("Content-Type", contentType);
                }
            } else {
                b.GET();
            }
            HttpResponse<T> r = client.send(b.build(), handler);
            int s = r.statusCode();
            if ((s == 301 || s == 302 || s == 303 || s == 307 || s == 308) && hop < MAX_REDIRECTS && "GET".equals(method)) {
                Optional<String> next = r.headers().firstValue("Location").flatMap(loc -> loc.startsWith("/")
                        ? Optional.of(loc) : sameHostPath(target, loc));
                if (next.isPresent()) {
                    if (r.body() instanceof InputStream in) {
                        in.close();
                    }
                    current = next.get();
                    continue;
                }
            }
            return r;
        }
    }

    private static String formEncode(Map<String, String> form) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }
}
