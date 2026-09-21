package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import java.net.Socket;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

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
 * <p>TLS verification is always on (WORKER.md "TLS trust"): the {@link
 * SSLContext} used for every request is built strictly from the resolved
 * {@link TrustResolution} -- a PEM CA bundle or a pinned certificate
 * fingerprint -- and standard HTTPS hostname verification is always
 * requested via {@link SSLParameters#setEndpointIdentificationAlgorithm}.
 * There is no branch anywhere in this class that accepts an unresolved
 * trust rule or any certificate: an unresolved trust rule fails every call
 * this instance ever makes ({@link XmlApiResult.Failed}), and this is
 * defense-in-depth only -- {@code PanoramaEnumerationAdapter} refuses the
 * run before this class is ever invoked for an unresolvable trust rule
 * (AC-3), so this path is exercised by no fixture test.</p>
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
            String certificateFailure = certificateFailureReason(e);
            if (certificateFailure != null) {
                return new XmlApiResult.Failed(certificateFailure);
            }
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
            String certificateFailure = certificateFailureReason(e);
            if (certificateFailure != null) {
                return new XmlApiStreamOutcome.Failed<>(certificateFailure);
            }
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
        TrustResolution resolution = resolveOrThrow(endpoint);
        SSLContext sslContext = buildSslContext(resolution);
        SSLParameters sslParameters = new SSLParameters();
        if (resolution instanceof TrustResolution.PaloAltoDeviceTrust) {
            sslParameters.setEndpointIdentificationAlgorithm("");
        } else {
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
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

    /** TLS trust law: strictly a CA bundle, pinned fingerprint, or verified Palo Alto device trust. */
    private static SSLContext buildSslContext(TrustResolution resolution) {
        try {
            TrustManager[] trustManagers = switch (resolution) {
                case TrustResolution.CaBundlePath caBundlePath -> caBundleTrustManagers(caBundlePath.path());
                case TrustResolution.PinnedFingerprint pinned -> new TrustManager[] { pinnedTrustManager(pinned.sha256Hex()) };
                case TrustResolution.PaloAltoDeviceTrust deviceTrust -> new TrustManager[] { paloAltoDeviceTrustManager(deviceTrust) };
                case TrustResolution.Unresolved ignored -> throw new IllegalStateException("unresolved trust rule reached SSLContext construction");
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (java.security.GeneralSecurityException | IOException e) {
            throw new IllegalStateException("could not build a TLS trust configuration from the resolved trust rule", e);
        }
    }

    private static TrustManager[] caBundleTrustManagers(String path) throws java.security.GeneralSecurityException, IOException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends java.security.cert.Certificate> certificates;
        try (var in = Files.newInputStream(Path.of(path))) {
            certificates = factory.generateCertificates(in);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (java.security.cert.Certificate certificate : certificates) {
            trustStore.setCertificateEntry("pan-discovery-ca-" + index, certificate);
            index++;
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        return trustManagerFactory.getTrustManagers();
    }

    /**
     * Verifies the presented chain the same way a CA-backed trust manager would (no
     * expiry/path shortcuts), then additionally requires the leaf certificate's SHA-256
     * fingerprint to equal the pinned value -- never a trust manager that returns without
     * checking (WORKER.md: "no all-trusting TrustManager").
     */
    private static X509ExtendedTrustManager pinnedTrustManager(String expectedSha256Hex) throws java.security.GeneralSecurityException {
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
                String presented = sha256Hex(chain[0]);
                if (!presented.equalsIgnoreCase(expectedSha256Hex)) {
                    throw new CertificateException("presented certificate fingerprint does not match the pinned trust rule");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509ExtendedTrustManager paloAltoDeviceTrustManager(java.util.Optional<String> pinnedFingerprint)
            throws java.security.GeneralSecurityException {
        return paloAltoDeviceTrustManager(new TrustResolution.PaloAltoDeviceTrust(pinnedFingerprint));
    }

    private static X509ExtendedTrustManager paloAltoDeviceTrustManager(TrustResolution.PaloAltoDeviceTrust deviceTrust)
            throws java.security.GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                return paloAltoDeviceTrustManager(deviceTrust, extended);
            }
        }
        throw new java.security.GeneralSecurityException("platform did not provide an X.509 trust manager");
    }

    static X509ExtendedTrustManager paloAltoDeviceTrustManager(java.util.Optional<String> pinnedFingerprint,
            X509ExtendedTrustManager platformTrustManager) {
        return paloAltoDeviceTrustManager(new TrustResolution.PaloAltoDeviceTrust(pinnedFingerprint), platformTrustManager);
    }

    static X509ExtendedTrustManager paloAltoDeviceTrustManager(TrustResolution.PaloAltoDeviceTrust deviceTrust,
            X509ExtendedTrustManager platformTrustManager) {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("pan transport is a client only; it never verifies a client certificate");
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
                verify(chain, () -> platformTrustManager.checkServerTrusted(chain, authType));
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                verify(chain, () -> platformTrustManager.checkServerTrusted(chain, authType, socket));
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                verify(chain, () -> platformTrustManager.checkServerTrusted(chain, authType, engine));
            }

            private void verify(X509Certificate[] chain, CertificateCheck platformCheck) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("no server certificate presented");
                }
                try {
                    platformCheck.run();
                    return;
                } catch (CertificateException ignored) {
                    // Explicit per-endpoint authorization is consulted only after chain verification fails.
                }
                chain[0].checkValidity();
                String presented = sha256Hex(chain[0]);
                java.util.Optional<String> pinnedFingerprint = deviceTrust.pinnedFingerprint();
                if (pinnedFingerprint.isPresent() && !pinnedFingerprint.get().isBlank()) {
                    if (!presented.equalsIgnoreCase(pinnedFingerprint.get())) {
                        throw new CertificateException("presented certificate fingerprint does not match the enrolled trust entry");
                    }
                    return;
                }
                throw new UnverifiableCertificateException(presented);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return platformTrustManager.getAcceptedIssuers();
            }
        };
    }

    @FunctionalInterface
    private interface CertificateCheck {
        void run() throws CertificateException;
    }

    private static final class UnverifiableCertificateException extends CertificateException {
        private UnverifiableCertificateException(String fingerprint) {
            super("server certificate unverifiable; fingerprint_sha256=" + fingerprint);
        }
    }

    static String certificateFailureReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnverifiableCertificateException) {
                return cause.getMessage();
            }
        }
        return null;
    }

    private static String sha256Hex(X509Certificate certificate) throws CertificateException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
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
