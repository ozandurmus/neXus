package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLException;

/**
 * The two real ports of {@link AdvisoryFetchRun} (contract section 3). Outbound: {@code GET} only, no body, through the
 * configured proxy, the runtime's default trust store and host-name check (no custom trust, on any path), redirects
 * never followed, one request per second per host, a size cap. Ingest: direct to the service's internal port, never
 * through the proxy. No message of an exception is logged or returned: it could name the proxy.
 */
public final class HttpTransport implements AdvisoryFetchRun.Outbound, AdvisoryFetchRun.Ingest {

    public static final String USER_AGENT = "Mozilla/5.0 (compatible; neXus-advisory-fetch/1.0)";
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final Duration HOST_INTERVAL = Duration.ofSeconds(1);
    public static final String TOKEN_HEADER = "X-Nexus-Advisory-Token";
    public static final String URL_HEADER = "X-Advisory-Url";
    public static final String RUN_HEADER = "X-Advisory-Run";

    private final HttpClient outbound;
    private final HttpClient direct;
    private final URI ingestBase;
    private final String token;
    private final Map<String, Long> lastRequestNanos = new HashMap<>();

    public HttpTransport(String proxyHost, int proxyPort, URI ingestBase, String token) {
        this.outbound = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.direct = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.ingestBase = ingestBase;
        this.token = token;
    }

    @Override
    public AdvisoryFetchRun.Fetched get(AdvisorySources.Source source, URI uri) {
        if (!AdvisorySources.allowed(source, uri)) {
            return AdvisoryFetchRun.Fetched.failed("ADDRESS_NOT_ALLOWED");
        }
        pace(source.host());
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").build();
        try {
            HttpResponse<InputStream> response = outbound.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    return AdvisoryFetchRun.Fetched.failed("HTTP_" + response.statusCode());
                }
                byte[] body = readCapped(in);
                return body == null ? AdvisoryFetchRun.Fetched.failed("DOCUMENT_TOO_LARGE") : AdvisoryFetchRun.Fetched.ok(body);
            }
        } catch (SSLException e) {
            return AdvisoryFetchRun.Fetched.failed(AdvisoryFetchRun.TLS_VALIDATION_FAILED);
        } catch (IOException e) {
            return AdvisoryFetchRun.Fetched.failed(tlsCause(e) ? AdvisoryFetchRun.TLS_VALIDATION_FAILED : "IO_" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AdvisoryFetchRun.Fetched.failed("INTERRUPTED");
        } catch (RuntimeException e) {
            return AdvisoryFetchRun.Fetched.failed("ERROR_" + e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean document(String source, String runId, URI readFrom, byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(ingestBase.resolve("/internal/advisories/" + source))
                .timeout(Duration.ofSeconds(120))
                .header(TOKEN_HEADER, token).header(URL_HEADER, readFrom.toString()).header(RUN_HEADER, runId)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return post(request);
    }

    @Override
    public boolean complete(String source, String runId, int fetched, int failed, String reason) {
        String json = "{\"documents_fetched\":" + fetched + ",\"documents_failed\":" + failed + ",\"reason\":"
                + (reason == null || !reason.matches("[A-Za-z0-9_]{1,60}") ? "null" : "\"" + reason + "\"") + "}";
        HttpRequest request = HttpRequest.newBuilder(ingestBase.resolve("/internal/advisories/" + source + "/complete"))
                .timeout(Duration.ofSeconds(120))
                .header(TOKEN_HEADER, token).header(RUN_HEADER, runId).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return post(request);
    }

    private boolean post(HttpRequest request) {
        try {
            int status = direct.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** At most one outbound request per second per host. */
    private void pace(String host) {
        Long last = lastRequestNanos.get(host);
        long now = System.nanoTime();
        if (last != null) {
            long waitNanos = HOST_INTERVAL.toNanos() - (now - last);
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastRequestNanos.put(host, System.nanoTime());
    }

    /** The body, or null when it is larger than the cap. */
    static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_DOCUMENT_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean tlsCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException || cause instanceof java.security.cert.CertificateException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
