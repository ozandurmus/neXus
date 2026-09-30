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
import java.security.MessageDigest;
import java.util.HexFormat;

import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;

/**
 * The generic HTTPS client for vendors backed up over HTTPS (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §0.2): basic auth
 * sent pre-emptively, form and JSON POST, streamed download into a sink with a size cap, redirects followed only on
 * the same host, endpoint-scoped certificate pinning. One client per call; no cookie state is kept between runs.
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
    /**
     * {@code peerName}: the name the appliance's own TLS certificate carries (the first DNS subject alternative name,
     * else the subject CN) -- observed on the connection already made, no request of its own; empty when the
     * certificate names nothing usable.
     */
    public record SessionLogin(int status, Optional<String> cookie, Optional<String> peerName) {
        public SessionLogin {
            peerName = peerName == null ? Optional.empty() : peerName;
        }

        public SessionLogin(int status, Optional<String> cookie) {
            this(status, cookie, Optional.empty());
        }
    }

    /** A text answer, bounded: {@code body} is at most {@code maxBytes} long; {@code truncated} says when more came. */
    /** {@code peerName}: the name the appliance's TLS certificate carries (see {@link #peerCertificateName}), if any. */
    public record TextResponse(int status, Optional<String> contentType, String body, boolean truncated, Optional<String> peerName) {
        public TextResponse {
            peerName = peerName == null ? Optional.empty() : peerName;
        }

        public TextResponse(int status, Optional<String> contentType, String body, boolean truncated) {
            this(status, contentType, body, truncated, Optional.empty());
        }

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
    @FunctionalInterface
    public interface CertificateObserver {
        com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision observe(
                Target target, com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Certificate certificate);
    }
    private final CertificateObserver certificateObserver;

    /** Unwired clients fail closed; production supplies the shared endpoint trust repository. */
    public HttpsDeviceClient() {
        this((target, certificate) -> { throw new IllegalStateException("HTTPS certificate trust not configured"); });
    }

    public HttpsDeviceClient(CertificateObserver certificateObserver) {
        this.certificateObserver = java.util.Objects.requireNonNull(certificateObserver);
    }

    CertificatePinningTrustManager trustManager(Target target) {
        var warning = com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateWarnings.current();
        return new CertificatePinningTrustManager(certificate -> {
            var decision = certificateObserver.observe(target, certificate);
            if (decision == null) throw new java.security.cert.CertificateException("HTTPS certificate trust unavailable");
            if (decision == com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision.WARN
                    || decision == com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision.REFUSE) {
                if (warning != null) warning.set(true);
                if (decision == com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision.REFUSE)
                    throw new java.security.cert.CertificateException("certificate changed; strict mode refused");
            }
        });
    }

    private HttpClient client(Target target) {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {trustManager(target)}, new SecureRandom());
            SSLParameters params = new SSLParameters();
            // Appliances are addressed by IP; endpoint identification stays off. The endpoint's leaf pin is trust.
            // X509ExtendedTrustManager handles all handshake overloads without JSSE's hostname-validation wrapper.
            params.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder().sslContext(ssl).sslParameters(params)
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
    public TextResponse putRaw(Target target, String path, String body, Credentials creds, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        return text(target, "PUT", path, "application/json", body, creds, timeout, maxBytes);
    }

    /** One form/GET exchange of a web session: status, the cookies it set ("NAME=value"), its Location, a capped body. */
    public record FormReply(int status, java.util.List<String> setCookies, Optional<String> location, String body) {
    }

    @Override
    public FormReply formRequest(Target target, String path, Map<String, String> form, String cookies, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "request",
                (form == null ? "GET " : "POST ") + JobTranscript.safePath(path)
                + (form == null ? "" : "\n" + JobTranscript.safeForm(form)));
        HttpRequest.Builder b = HttpRequest.newBuilder(target.uri(path)).timeout(timeout);
        if (cookies != null && !cookies.isBlank()) {
            b.header("Cookie", cookies);
        }
        if (form == null) {
            b.GET();
        } else {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(java.net.URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
            }
            b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        HttpResponse<java.io.InputStream> r = client(target).send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] data;
        String captured = null;
        try (java.io.InputStream in = r.body()) {
            data = in.readNBytes(maxBytes);
            if (JobTranscriptScope.current() != null) captured = binary(r)
                    ? binarySummary(in, data) : captureRemaining(in, data);
        }
        java.util.List<String> set = r.headers().allValues("Set-Cookie").stream().map(v -> v.split(";", 2)[0].strip())
                .filter(v -> v.contains("=")).toList();
        if (captured != null) recordResponse(r, withoutCookieSecrets(
                JobTranscript.withoutSentSecrets(captured, form), cookies));
        return new FormReply(r.statusCode(), set, r.headers().firstValue("Location"), new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public SessionLogin login(Target target, String path, String json, Duration timeout) throws IOException, InterruptedException {
        if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "request",
                "POST " + JobTranscript.safePath(path) + "\n" + JobTranscript.safeJson(json));
        HttpRequest request = HttpRequest.newBuilder(target.uri(path)).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<?> r;
        if (JobTranscriptScope.current() == null) {
            r = client(target).send(request, HttpResponse.BodyHandlers.discarding());
        } else {
            HttpResponse<InputStream> response = client(target).send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                recordResponse(response, JobTranscript.withoutSentSecrets(
                        captureRemaining(in, new byte[0]), json, "application/json"));
            }
            r = response;
        }
        Optional<String> cookie = r.headers().allValues("Set-Cookie").stream()
                .map(v -> v.split(";", 2)[0].strip())
                .filter(v -> v.toUpperCase(Locale.ROOT).startsWith("JSESSIONID="))
                .findFirst();
        return new SessionLogin(r.statusCode(), cookie, r.sslSession().flatMap(HttpsDeviceClient::peerCertificateName));
    }

    /** The first DNS subject alternative name of the peer's leaf certificate, else its subject CN; empty on any failure. */
    static Optional<String> peerCertificateName(javax.net.ssl.SSLSession session) {
        try {
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0 || !(chain[0] instanceof java.security.cert.X509Certificate leaf)) {
                return Optional.empty();
            }
            java.util.Collection<java.util.List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans != null) {
                for (java.util.List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String dns && !dns.isBlank()) {
                        return Optional.of(dns.trim());
                    }
                }
            }
            String dn = leaf.getSubjectX500Principal().getName(javax.security.auth.x500.X500Principal.RFC2253);
            for (String part : dn.split(",")) {
                String t = part.trim();
                if (t.regionMatches(true, 0, "CN=", 0, 3) && t.length() > 3) {
                    return Optional.of(t.substring(3).trim());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Streams a GET or form POST answer into {@code sink}, refusing past {@code maxBytes}; never buffered whole. */
    @Override
    public DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            OutputStream sink, long maxBytes, Duration timeout) {
        return download(target, method, path, form, creds, null, sink, maxBytes, timeout);
    }

    @Override
    public DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            String requestContentType, OutputStream sink, long maxBytes, Duration timeout) {
        try {
            String body = form == null ? null : formEncode(form);
            String contentType = body != null ? "application/x-www-form-urlencoded" : requestContentType;
            HttpResponse<InputStream> r = send(target, method, path, contentType, body,
                    creds, timeout, HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = JobTranscriptScope.current() == null ? null : sha256();
            try (InputStream in = r.body()) {
                if (r.statusCode() < 200 || r.statusCode() >= 300) {
                    if (JobTranscriptScope.current() != null) {
                        String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                        String refused = type.contains("text") || type.contains("json") || type.contains("xml")
                                || type.contains("html") ? captureRemaining(in, new byte[0]) : binarySummary(in);
                        recordResponse(r, withoutCookieSecrets(
                                JobTranscript.withoutSentSecrets(refused, body, contentType),
                                new String(creds.password())));
                    }
                    return new DownloadResult.Refused(r.statusCode(), "HTTP " + r.statusCode());
                }
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (digest != null) digest.update(buf, 0, n);
                    if (total > maxBytes) {
                        recordResponse(r, "size=" + total + " sha256="
                                + (digest == null ? "" : HexFormat.of().formatHex(digest.digest())) + " [bound exceeded]");
                        return new DownloadResult.Failed("larger than the " + maxBytes + "-byte bound");
                    }
                    sink.write(buf, 0, n);
                }
                recordResponse(r, "size=" + total + " sha256=" + (digest == null ? "" : HexFormat.of().formatHex(digest.digest())));
                return new DownloadResult.Downloaded(r.statusCode(), total, r.headers().firstValue("Content-Type"));
            }
        } catch (IOException e) {
            JobTranscriptScope.add("https", "note", "download failed: " + e.getClass().getSimpleName());
            return new DownloadResult.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JobTranscriptScope.add("https", "note", "download interrupted");
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
            if (JobTranscriptScope.current() != null) recordResponse(r, binary(r) ? binarySummary(in, bytes)
                    : withoutCookieSecrets(JobTranscript.withoutSentSecrets(captureRemaining(in, bytes), body, contentType),
                            new String(creds.password())));
            return new TextResponse(r.statusCode(), r.headers().firstValue("Content-Type"), s, truncated,
                    r.sslSession().flatMap(HttpsDeviceClient::peerCertificateName));
        }
    }

    private <T> HttpResponse<T> send(Target target, String method, String path, String contentType, String body, Credentials creds,
            Duration timeout, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        // A fresh TLS context prevents connection/session reuse from skipping a changed pin or strict policy.
        HttpClient client = client(target);
        String current = path;
        for (int hop = 0; ; hop++) {
            String sentMethod = hop == 0 ? method : "GET";
            if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "request", sentMethod + " " + JobTranscript.safePath(current)
                    // The account is shown (PO 2026-09-27: "hangi account'u kullandığını göstersin"); the password never.
                    + (creds.isSession() ? "" : "\nAuthorization: Basic (user " + creds.username() + ", password [credential])")
                    + (hop == 0 && body != null ? "\n" + (contentType != null && contentType.startsWith("application/json")
                            ? JobTranscript.safeJson(body) : JobTranscript.safeForm(body)) : ""));
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
            } else if ("PUT".equals(method) && hop == 0) {
                b.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
                if (contentType != null) {
                    b.header("Content-Type", contentType);
                }
            } else {
                b.GET();
                if (contentType != null) {
                    // Infoblox file downloads: the appliance answers 415 unless the GET names application/force-download.
                    b.header("Content-Type", contentType);
                }
            }
            HttpResponse<T> r = client.send(b.build(), handler);
            int s = r.statusCode();
            if ((s == 301 || s == 302 || s == 303 || s == 307 || s == 308) && hop < MAX_REDIRECTS && "GET".equals(method)) {
                Optional<String> next = r.headers().firstValue("Location").flatMap(loc -> loc.startsWith("/")
                        ? Optional.of(loc) : sameHostPath(target, loc));
                if (next.isPresent()) {
                    recordResponse(r, "redirect");
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

    static void recordResponse(HttpResponse<?> response, String body) {
        if (JobTranscriptScope.current() == null) return;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JobTranscriptScope.add("https", "response", "HTTP " + response.statusCode() + "\nContent-Type: "
                + contentType + "\n" + JobTranscript.safeHeaders(response.headers().map()) + "\n"
                + JobTranscript.safeResponseBody(contentType, body));
    }

    private static String captureRemaining(InputStream in, byte[] first) throws IOException {
        int cap = 64 * 1024 * 1024 - 1024;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(first, 0, Math.min(first.length, cap));
        byte[] buf = new byte[8192];
        while (out.size() < cap) {
            int n = in.read(buf, 0, Math.min(buf.length, cap - out.size()));
            if (n < 0) return out.toString(StandardCharsets.UTF_8);
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8) + "\n[response truncated at 64 MB]";
    }

    private static String binarySummary(InputStream in) throws IOException {
        return binarySummary(in, new byte[0]);
    }

    private static String binarySummary(InputStream in, byte[] first) throws IOException {
        MessageDigest digest = sha256();
        digest.update(first);
        byte[] buf = new byte[8192];
        long bytes = first.length;
        int n;
        while (bytes < 64L * 1024 * 1024 && (n = in.read(buf, 0,
                (int) Math.min(buf.length, 64L * 1024 * 1024 - bytes))) >= 0) {
            digest.update(buf, 0, n);
            bytes += n;
        }
        return "size=" + bytes + " sha256=" + HexFormat.of().formatHex(digest.digest());
    }

    private static boolean binary(HttpResponse<?> response) {
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        return type.contains("octet-stream") || type.contains("gzip") || type.contains("zip")
                || type.startsWith("image/");
    }

    private static String withoutCookieSecrets(String body, String cookies) {
        if (cookies == null || cookies.isEmpty()) return body;
        String safe = body.replace(cookies, "[credential]");
        for (String part : cookies.split(";")) {
            int equals = part.indexOf('=');
            if (equals >= 0 && equals + 1 < part.length())
                safe = safe.replace(part.substring(equals + 1).trim(), "[credential]");
        }
        return safe;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
