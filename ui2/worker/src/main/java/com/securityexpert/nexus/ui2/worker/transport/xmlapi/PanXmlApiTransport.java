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
        HttpClient client;
        URI uri;
        try {
            uri = resolveUri(target);
            client = buildClient(uri);
        } catch (RuntimeException e) {
            return new XmlApiResult.Failed("trust rule could not be resolved into a usable TLS configuration");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(resolveFormParams(spec)), StandardCharsets.UTF_8))
                    .header("Content-Type", FORM_CONTENT_TYPE);
            for (Map.Entry<String, String> header : spec.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        HttpClient client;
        URI uri;
        try {
            uri = resolveUri(target);
            client = buildClient(uri);
        } catch (RuntimeException e) {
            return new XmlApiStreamOutcome.Failed<>("trust rule could not be resolved into a usable TLS configuration");
        }
        try {
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
                T handled = handler.handle(body);
                return new XmlApiStreamOutcome.Completed<>(response.statusCode(), handled);
            }
        } catch (IllegalArgumentException e) {
            return new XmlApiStreamOutcome.Failed<>("invalid api target URI: " + e.getMessage());
        } catch (IOException e) {
            return new XmlApiStreamOutcome.Failed<>("xml api streaming call did not complete: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new XmlApiStreamOutcome.Failed<>("xml api streaming call was interrupted");
        }
    }

    @Override
    public void disconnect(TransportSession session) {
        // T-1: this transport holds no session of its own -- xmlApiCall is session-less by
        // the port's own signature. Nothing to close here; the caller's in-memory key
        // disposal (PanoramaEnumerationAdapter) is what T-1's "closed when the run ends" means.
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
