package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import java.net.Socket;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportNotImplementedException;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamHandler;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/**
 * The {@code xml_api_call} adapter (WORKER.md "ADAPTER") -- the only
 * production implementation of {@link DeviceTransport#xmlApiCall} in this
 * codebase. {@code connect}/{@code exec}/{@code fetch} throw {@link
 * TransportNotImplementedException} exactly as {@code SshExecTransport}
 * does for {@code xmlApiCall} (contract §5).
 *
 * <p>Palo Alto certificates are accepted without chain, hostname, or
 * per-device verification after their X.509 validity period is checked.</p>
 */
public final class PanXmlApiTransport implements DeviceTransport {

    private static final String API_PATH = "/api/";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8";

    private final String trustRuleRef;
    private final PanTrustRuleResolver trustRuleResolver;

    public PanXmlApiTransport(String trustRuleRef, PanTrustRuleResolver trustRuleResolver) {
        this.trustRuleRef = Objects.requireNonNull(trustRuleRef, "trustRuleRef");
        this.trustRuleResolver = Objects.requireNonNull(trustRuleResolver, "trustRuleResolver");
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("connect");
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("exec");
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("sftp_get/scp_get");
    }

    private static URI resolveUri(ApiTarget target) {
        String base = target.baseUrl().trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + API_PATH);
    }

    private static Map<String, String> resolveFormParams(XmlApiSpec spec) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (spec.type() != null && !spec.type().isBlank() && !"not_applicable".equalsIgnoreCase(spec.type())) {
            params.put("type", spec.type());
        }
        params.putAll(spec.formParams());
        return params;
    }

    /** T-1/T-2/T-4: one POST to {@code target.baseUrl() + "/api/"}, never any other path or host. */
    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        try {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        HttpClient client;
        URI uri;
        try {
            uri = resolveUri(target);
            client = buildClient(uri);
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.http(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort(), client);
        } catch (RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            return new XmlApiResult.Failed("trust rule could not be resolved into a usable TLS configuration");
        }
        try {
            if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "request", "POST " + uri.getRawPath() + "\n"
                    + JobTranscript.safeForm(formEncode(resolveFormParams(spec))));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(resolveFormParams(spec)), StandardCharsets.UTF_8))
                    .header("Content-Type", FORM_CONTENT_TYPE);
            for (Map.Entry<String, String> header : spec.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "response", "HTTP " + response.statusCode() + "\nContent-Type: "
                    + response.headers().firstValue("Content-Type").orElse("") + "\n"
                    + JobTranscript.safeHeaders(response.headers().map()) + "\n" + safeBody(spec, response.body()));
            return new XmlApiResult.Completed(response.statusCode(), response.body());
        } catch (IllegalArgumentException e) {
            return new XmlApiResult.Failed("invalid api target URI: " + e.getMessage());
        } catch (IOException e) {
            System.getLogger(PanXmlApiTransport.class.getName())
                    .log(System.Logger.Level.WARNING, "xml api call IOException: " + e.getMessage(), e);
            return new XmlApiResult.Failed("xml api call did not complete: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new XmlApiResult.Failed("xml api call was interrupted");
        }

        } finally { com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.closeHttpClients(); }
    }

    /**
     * The streaming form of {@link #xmlApiCall} (14G CG-5): the response
     * body is handed to {@code handler} as an {@link InputStream} via
     * {@link HttpResponse.BodyHandlers#ofInputStream()} -- never {@code
     * ofString}, so this call never materializes the response as one
     * in-memory {@code String} regardless of its size (T-1/T-2/T-4 govern
     * this call exactly as {@link #xmlApiCall} above). The stream is
     * closed before this method returns, whether or not {@code handler}
     * consumed it fully.
     */
    @Override
    public <T> XmlApiStreamOutcome<T> xmlApiCallStreaming(ApiTarget target, XmlApiSpec spec, Duration timeout,
            XmlApiStreamHandler<T> handler) {
        try {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        HttpClient client;
        URI uri;
        try {
            uri = resolveUri(target);
            client = buildClient(uri);
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.http(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort(), client);
        } catch (RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            return new XmlApiStreamOutcome.Failed<>("trust rule could not be resolved into a usable TLS configuration");
        }
        try {
            if (JobTranscriptScope.current() != null) JobTranscriptScope.add("https", "request", "POST " + uri.getRawPath() + "\n"
                    + JobTranscript.safeForm(formEncode(resolveFormParams(spec))));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(resolveFormParams(spec)), StandardCharsets.UTF_8))
                    .header("Content-Type", FORM_CONTENT_TYPE);
            for (Map.Entry<String, String> header : spec.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                java.io.ByteArrayOutputStream captured = JobTranscriptScope.current() == null ? null : new java.io.ByteArrayOutputStream();
                java.security.MessageDigest digest = captured == null ? null : sha256();
                long[] received = {0};
                boolean[] compressed = {false};
                InputStream observed = captured == null ? body : new java.io.FilterInputStream(body) {
                    private boolean checked;
                    private void observe(byte[] b, int off, int len) {
                        digest.update(b, off, len);
                        received[0] += len;
                        if (captured.size() < 2) {
                            int head = Math.min(len, 2 - captured.size());
                            captured.write(b, off, head);
                            off += head;
                            len -= head;
                        }
                        if (!checked && captured.size() >= 2) {
                            compressed[0] = gzip(captured);
                            checked = true;
                        }
                        if (!compressed[0] && len > 0 && captured.size() < 64 * 1024 * 1024)
                            captured.write(b, off, Math.min(len, 64 * 1024 * 1024 - captured.size()));
                    }
                    @Override public int read() throws IOException {
                        int value = super.read();
                        if (value >= 0) observe(new byte[] {(byte) value}, 0, 1);
                        return value;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len);
                        if (n > 0) observe(b, off, n);
                        return n;
                    }
                };
                try {
                    T handled = handler.handle(observed);
                    return new XmlApiStreamOutcome.Completed<>(response.statusCode(), handled);
                } finally {
                    if (captured != null) {
                        String type = response.headers().firstValue("Content-Type").orElse("");
                        String text = captured.toString(StandardCharsets.UTF_8);
                        boolean binary = compressed[0] || (("export".equalsIgnoreCase(spec.type())
                                || type.toLowerCase(java.util.Locale.ROOT).contains("octet-stream"))
                                && !text.stripLeading().startsWith("<"));
                        JobTranscriptScope.add("https", "response", "HTTP " + response.statusCode()
                                + "\nContent-Type: " + type + "\n" + JobTranscript.safeHeaders(response.headers().map())
                                + "\n" + (binary ? "file=" + ("device-state".equals(spec.category()) ? "device-state.tgz" : "export") + " size=" + received[0] + " sha256="
                                        + java.util.HexFormat.of().formatHex(digest.digest()) : safeBody(spec, text)));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return new XmlApiStreamOutcome.Failed<>("invalid api target URI: " + e.getMessage());
        } catch (IOException e) {
            JobTranscriptScope.add("https", "note", "XML API stream failed: " + e.getClass().getSimpleName());
            return new XmlApiStreamOutcome.Failed<>("xml api streaming call did not complete: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new XmlApiStreamOutcome.Failed<>("xml api streaming call was interrupted");
        }

        } finally { com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.closeHttpClients(); }
    }

    @Override
    public void disconnect(TransportSession session) {
        // T-1: this transport holds no session of its own -- xmlApiCall is session-less by
        // the port's own signature. Nothing to close here; the caller's in-memory key
        // disposal (PanoramaEnumerationAdapter) is what T-1's "closed when the run ends" means.
    }

    static String safeBody(XmlApiSpec spec, String body) {
        if (body == null) return "";
        String safe = JobTranscript.withoutSentSecrets(body, resolveFormParams(spec));
        safe = "keygen".equalsIgnoreCase(spec.type())
                ? safe.replaceAll("(?s)(<key>)[^<]*(</key>)", "$1[credential]$2") : safe;
        for (Map.Entry<String, String> header : spec.headers().entrySet()) {
            String name = header.getKey().toLowerCase(java.util.Locale.ROOT);
            if ((name.contains("key") || name.contains("token") || name.contains("auth"))
                    && header.getValue() != null && !header.getValue().isEmpty())
                safe = safe.replace(header.getValue(), "[credential]");
        }
        return safe;
    }

    private static boolean gzip(java.io.ByteArrayOutputStream bytes) {
        if (bytes.size() < 2) return false;
        byte[] head = bytes.toByteArray();
        return (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
    }

    private static java.security.MessageDigest sha256() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private HttpClient buildClient(URI endpoint) {
        resolveOrThrow(endpoint);
        SSLContext sslContext = buildSslContext();
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("");
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .build();
    }

    private TrustResolution resolveOrThrow(URI endpoint) {
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 443;
        TrustResolution resolution = trustRuleResolver.resolveTrust(trustRuleRef, endpoint.getHost(), port);
        if (resolution instanceof TrustResolution.Unresolved) {
            throw new IllegalStateException("trust rule ref did not resolve to a usable trust configuration");
        }
        return resolution;
    }

    private static SSLContext buildSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { paloAltoDeviceTrustManager() }, new SecureRandom());
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not build the Palo Alto TLS configuration", e);
        }
    }

    public static X509ExtendedTrustManager paloAltoDeviceTrustManager() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("pan discovery transport is a client only; it never verifies a client certificate");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                verify(chain);
            }

            private void verify(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("no server certificate presented");
                }
                for (X509Certificate certificate : chain) {
                    if (certificate == null) {
                        throw new CertificateException("invalid server certificate chain");
                    }
                    certificate.checkValidity();
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
