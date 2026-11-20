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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

/**
 * The two real ports of {@link AdvisoryFetchRun} (contract section 3). Outbound: {@code GET} only, no body, through the
 * configured proxy, the runtime's default trust store and host-name check (no custom trust, on any path), redirects
 * never followed, one request per second per host, a size cap. Ingest: direct to the service's internal port, never
 * through the proxy, retrying transient refusals (connection refused/IO error, 429, 503/5xx while draining or not ready)
 * with bounded backoff while failing real refusals (4xx other than 429) at once. No message of an exception is logged or
 * returned: it could name the proxy.
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
    private final List<Duration> ingestBackoff;
    private final Consumer<Duration> ingestSleeper;
    private final Map<String, Long> lastRequestNanos = new HashMap<>();

    public HttpTransport(String proxyHost, int proxyPort, URI ingestBase, String token) {
        this(proxyHost, proxyPort, ingestBase, token, AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, HttpTransport::sleep);
    }

    public HttpTransport(String proxyHost, int proxyPort, URI ingestBase, String token,
            List<Duration> ingestBackoff, Consumer<Duration> ingestSleeper) {
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
        this.ingestBackoff = List.copyOf(ingestBackoff);
        this.ingestSleeper = ingestSleeper;
    }

    @Override
    public AdvisoryFetchRun.Fetched get(AdvisorySources.Source source, URI uri) {
        if (!AdvisorySources.allowed(source, uri)) {
            return AdvisoryFetchRun.Fetched.failed("ADDRESS_NOT_ALLOWED");
        }
        pace(uri.getHost());
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
            return AdvisoryFetchRun.Fetched.failed(tlsCause(e) ? AdvisoryFetchRun.TLS_VALIDATION_FAILED : codeWord("IO_", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AdvisoryFetchRun.Fetched.failed("INTERRUPTED");
        } catch (RuntimeException e) {
            return AdvisoryFetchRun.Fetched.failed(codeWord("ERROR_", e));
        }
    }

    @Override
    public boolean document(String source, String runId, URI readFrom, byte[] body) {
        return handOver(source, runId, readFrom, body).accepted();
    }

    @Override
    public boolean complete(String source, String runId, int fetched, int failed, String reason) {
        return close(source, runId, fetched, failed, reason, true).accepted();
    }

    @Override
    public AdvisoryFetchRun.IngestResult handOver(String source, String runId, URI readFrom, byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(ingestBase.resolve(AdvisorySources.ingestPath(source)))
                .timeout(Duration.ofSeconds(120))
                .header(TOKEN_HEADER, token).header(URL_HEADER, readFrom.toString()).header(RUN_HEADER, runId)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return postWithRetry(request);
    }

    @Override
    public AdvisoryFetchRun.IngestResult close(String source, String runId, int fetched, int failed, String reason) {
        return close(source, runId, fetched, failed, reason, true);
    }

    @Override
    public AdvisoryFetchRun.IngestResult close(String source, String runId, int fetched, int failed, String reason,
            boolean retryTransient) {
        String json = "{\"documents_fetched\":" + fetched + ",\"documents_failed\":" + failed + ",\"reason\":"
                + (reason == null || !reason.matches("[A-Z0-9_]{1,40}") ? "null" : "\"" + reason + "\"") + "}";
        HttpRequest request = HttpRequest.newBuilder(ingestBase.resolve(AdvisorySources.ingestPath(source) + "/complete"))
                .timeout(Duration.ofSeconds(120))
                .header(TOKEN_HEADER, token).header(RUN_HEADER, runId).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return retryTransient ? postWithRetry(request) : postOnce(request, 1);
    }

    private AdvisoryFetchRun.IngestResult postWithRetry(HttpRequest request) {
        int maxAttempts = ingestBackoff.size() + 1;
        AdvisoryFetchRun.IngestResult last = AdvisoryFetchRun.IngestResult.permanentRefusal(1, AdvisoryFetchRun.INGEST_REFUSED);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                ingestSleeper.accept(ingestBackoff.get(attempt - 2));
                if (Thread.currentThread().isInterrupted()) {
                    return AdvisoryFetchRun.IngestResult.transientRefusal(attempt - 1, last.reason());
                }
            }
            last = postOnce(request, attempt);
            if (last.accepted() || !last.retryable() || Thread.currentThread().isInterrupted()) {
                return last;
            }
        }
        return last;
    }

    private AdvisoryFetchRun.IngestResult postOnce(HttpRequest request, int attempt) {
        try {
            int status = direct.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return AdvisoryFetchRun.IngestResult.ok(attempt);
            }
            if (isTransientStatus(status)) {
                return AdvisoryFetchRun.IngestResult.transientRefusal(attempt, AdvisoryFetchRun.INGEST_REFUSED);
            }
            return AdvisoryFetchRun.IngestResult.permanentRefusal(attempt, AdvisoryFetchRun.INGEST_REFUSED);
        } catch (IOException e) {
            return AdvisoryFetchRun.IngestResult.transientRefusal(attempt, AdvisoryFetchRun.INGEST_REFUSED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AdvisoryFetchRun.IngestResult.permanentRefusal(attempt, AdvisoryFetchRun.INGEST_REFUSED);
        } catch (RuntimeException e) {
            return AdvisoryFetchRun.IngestResult.permanentRefusal(attempt, AdvisoryFetchRun.INGEST_REFUSED);
        }
    }

    static boolean isTransientStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private static void sleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A failure as a code word of the form the feed-run record accepts: upper case, digits and underscores, at most 40. */
    static String codeWord(String prefix, Throwable error) {
        String name = (prefix + error.getClass().getSimpleName()).toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
        return name.length() > 40 ? name.substring(0, 40) : name;
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
