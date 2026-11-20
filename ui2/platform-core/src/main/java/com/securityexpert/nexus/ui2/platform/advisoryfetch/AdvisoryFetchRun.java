package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One fetch run over the chosen sources (contract section 3). Pure orchestration over two ports, so it is tested without
 * a network: {@link Outbound} fetches one allowed public document, {@link Ingest} hands a document to the service.
 * A source that fails certificate validation is stopped at once and nothing more is requested from its host.
 */
public final class AdvisoryFetchRun {

    /** The outcome of one outbound request. {@code failure} is a fixed code word, never an address or a message. */
    public record Fetched(byte[] body, String failure) {

        public static Fetched ok(byte[] body) {
            return new Fetched(body, null);
        }

        public static Fetched failed(String codeWord) {
            return new Fetched(null, codeWord);
        }

        public boolean succeeded() {
            return body != null;
        }
    }

    /** Fetches one document. Implementations must refuse any address that is not {@link AdvisorySources#allowed}. */
    public interface Outbound {
        Fetched get(AdvisorySources.Source source, URI uri);
    }

    /** The failure word of a certificate validation failure; it stops the source (PO 2026-11-10, TLS stop rule). */
    public static final String TLS_VALIDATION_FAILED = "TLS_VALIDATION_FAILED";
    /** The failure word when the ingest endpoint refuses or remains unreachable after bounded retries. */
    public static final String INGEST_REFUSED = "INGEST_REFUSED";
    /** Default bounded ingest retry schedule: 5 tries over 5 minutes (20s, 40s, 80s, 160s = 300s total wait). */
    public static final int DEFAULT_INGEST_MAX_ATTEMPTS = 5;
    public static final List<Duration> DEFAULT_INGEST_BACKOFF = List.of(
            Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(80), Duration.ofSeconds(160));

    /**
     * The outcome of one ingest operation. {@code accepted} is true on 2xx; {@code retryable} is true when the refusal is
     * transient (connection refused, IO failure, 429, 503 or other 5xx while the service is draining or not ready);
     * {@code attempts} is how many tries were made; {@code reason} is the final failure code word when not accepted.
     */
    public record IngestResult(boolean accepted, boolean retryable, int attempts, String reason) {

        public IngestResult {
            attempts = Math.max(1, attempts);
            reason = accepted ? null : (reason == null ? INGEST_REFUSED : reason);
        }

        public static IngestResult ok() {
            return new IngestResult(true, false, 1, null);
        }

        public static IngestResult ok(int attempts) {
            return new IngestResult(true, false, attempts, null);
        }

        public static IngestResult transientRefusal() {
            return new IngestResult(false, true, 1, INGEST_REFUSED);
        }

        public static IngestResult transientRefusal(String reason) {
            return new IngestResult(false, true, 1, reason);
        }

        public static IngestResult transientRefusal(int attempts, String reason) {
            return new IngestResult(false, true, attempts, reason);
        }

        public static IngestResult permanentRefusal() {
            return new IngestResult(false, false, 1, INGEST_REFUSED);
        }

        public static IngestResult permanentRefusal(String reason) {
            return new IngestResult(false, false, 1, reason);
        }

        public static IngestResult permanentRefusal(int attempts, String reason) {
            return new IngestResult(false, false, attempts, reason);
        }
    }

    /** Hands documents and the closing counts to the service. Both return true when the service accepted. */
    public interface Ingest {
        boolean document(String source, String runId, URI readFrom, byte[] body);

        boolean complete(String source, String runId, int fetched, int failed, String reason);

        default IngestResult handOver(String source, String runId, URI readFrom, byte[] body) {
            return document(source, runId, readFrom, body) ? IngestResult.ok() : IngestResult.permanentRefusal(INGEST_REFUSED);
        }

        default IngestResult close(String source, String runId, int fetched, int failed, String reason) {
            return complete(source, runId, fetched, failed, reason) ? IngestResult.ok() : IngestResult.permanentRefusal(INGEST_REFUSED);
        }

        default IngestResult close(String source, String runId, int fetched, int failed, String reason, boolean retryTransient) {
            return close(source, runId, fetched, failed, reason);
        }
    }

    /** What one source did in this run. */
    public record SourceResult(String source, int fetched, int failed, int handedOver, int dropped, String reason, int attempts) {

        public SourceResult(String source, int fetched, int failed, int handedOver, int dropped, String reason) {
            this(source, fetched, failed, handedOver, dropped, reason, 1);
        }

        public int ingestAttempts() {
            return attempts;
        }

        public boolean ok() {
            return reason == null && failed == 0;
        }
    }

    private final Outbound outbound;
    private final Ingest ingest;
    private final Consumer<String> log;
    private final Instant ciscoSince;
    private final List<Duration> ingestBackoff;
    private final Consumer<Duration> ingestSleeper;

    public AdvisoryFetchRun(Outbound outbound, Ingest ingest, Consumer<String> log, Instant now, int ciscoWindowDays) {
        this(outbound, ingest, log, now, ciscoWindowDays, DEFAULT_INGEST_BACKOFF, AdvisoryFetchRun::defaultSleep);
    }

    public AdvisoryFetchRun(Outbound outbound, Ingest ingest, Consumer<String> log, Instant now, int ciscoWindowDays,
            List<Duration> ingestBackoff, Consumer<Duration> ingestSleeper) {
        this.outbound = outbound;
        this.ingest = ingest;
        this.log = log;
        this.ciscoSince = now.minus(Duration.ofDays(Math.max(1, ciscoWindowDays)));
        this.ingestBackoff = List.copyOf(ingestBackoff);
        this.ingestSleeper = ingestSleeper;
    }

    public SourceResult run(AdvisorySources.Source source, String runId) {
        Counter counter = new Counter();
        String reason = null;
        Fetched start = "checkpoint".equals(source.key()) ? null : outbound.get(source, source.startUri());
        if (start == null) {
            reason = checkpoint(source, runId, counter);
        } else if (!start.succeeded()) {
            counter.failed++;
            reason = start.failure();
        } else {
            counter.fetched++;
            reason = switch (source.key()) {
                case "fortinet" -> followUps(source, runId, FollowUps.fortinet(start.body()), false, counter);
                case "cisco" -> followUps(source, runId, FollowUps.cisco(start.body(), ciscoSince), true, counter);
                case "cisco_eol" -> ciscoEol(source, runId, start.body(), counter);
                case "paloalto_eol" -> paloAltoEol(source, runId, start.body(), counter);
                default -> handOver(source, runId, source.startUri(), start.body(), counter);
            };
        }
        String closeReason = reason;
        IngestResult closed = counter.ingestExhausted
                ? ingest.close(source.key(), runId, counter.fetched, counter.failed, closeReason, false)
                : retryIngest(() -> ingest.close(source.key(), runId, counter.fetched, counter.failed, closeReason, true));
        if (!counter.ingestFailed) {
            counter.ingestRetries += closed.attempts() - 1;
        }
        if (!closed.accepted() && reason == null) {
            reason = closed.reason();
        }
        int attempts = 1 + counter.ingestRetries;
        SourceResult result = new SourceResult(source.key(), counter.fetched, counter.failed, counter.handedOver, counter.dropped,
                reason, attempts);
        log.accept("[ADVISORY_FETCH] source=" + result.source() + " fetched=" + result.fetched() + " failed=" + result.failed()
                + " handed_over=" + result.handedOver() + " dropped=" + result.dropped() + " reason=" + (reason == null ? "none" : reason)
                + " attempts=" + result.attempts());
        return result;
    }

    /**
     * The lifecycle bulletins (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 3.1): the second listing page, then every
     * bulletin either listing links. A listing that fails stops nothing but is counted; no bulletin named at all is a
     * reason (the listing layout changed), so the source does not look fresh with nothing behind it.
     */
    private String ciscoEol(AdvisorySources.Source source, String runId, byte[] asaListing, Counter counter) {
        URI second = URI.create("https://" + source.host() + AdvisorySources.CISCO_EOL_FIREPOWER_LISTING);
        Fetched firepower = outbound.get(source, second);
        if (firepower.succeeded()) {
            counter.fetched++;
        } else {
            counter.failed++;
            if (TLS_VALIDATION_FAILED.equals(firepower.failure())) return TLS_VALIDATION_FAILED;
        }
        List<URI> bulletins = FollowUps.ciscoEol(java.util.Arrays.asList(asaListing, firepower.body()));
        if (bulletins.isEmpty()) return "NO_DOCUMENTS_NAMED";
        return followUps(source, runId, bulletins, false, counter);
    }

    /** The Palo Alto software page, then the hardware page; each is handed on as fetched (contract section 3.1). */
    private String paloAltoEol(AdvisorySources.Source source, String runId, byte[] softwarePage, Counter counter) {
        String refused = handOver(source, runId, source.startUri(), softwarePage, counter);
        if (refused != null) return refused;
        return followUps(source, runId, List.of(URI.create("https://" + source.host() + AdvisorySources.PALOALTO_EOL_HARDWARE)), false, counter);
    }

    /** Fetches each named document and hands it on; returns the reason that stopped the source, or null. */
    private String followUps(AdvisorySources.Source source, String runId, List<URI> addresses, boolean ciscoAsaOnly, Counter counter) {
        if (addresses.isEmpty()) {
            return source.key().equals("cisco") ? null : "NO_DOCUMENTS_NAMED";
        }
        for (URI address : addresses) {
            Fetched fetched = outbound.get(source, address);
            if (!fetched.succeeded()) {
                counter.failed++;
                if (TLS_VALIDATION_FAILED.equals(fetched.failure())) {
                    return TLS_VALIDATION_FAILED;
                }
                continue;
            }
            counter.fetched++;
            if (ciscoAsaOnly && !FollowUps.namesCiscoAsa(fetched.body())) {
                counter.dropped++;
                continue;
            }
            String refused = handOver(source, runId, address, fetched.body(), counter);
            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    /**
     * Check Point (amendment A-2026-11-13): every resolved-issues page is handed over first, so the ingest holds the
     * Jumbo statements of this run before the CVE records that are joined with them arrive; then the CVE record of every
     * CVE id the pages name. A page that fails is counted and the others go on; a certificate failure stops the source.
     */
    private String checkpoint(AdvisorySources.Source source, String runId, Counter counter) {
        List<byte[]> pages = new java.util.ArrayList<>();
        for (String path : AdvisorySources.CHECKPOINT_JUMBO_PAGES) {
            URI address = URI.create("https://" + source.host() + path);
            Fetched fetched = outbound.get(source, address);
            if (!fetched.succeeded()) {
                counter.failed++;
                if (TLS_VALIDATION_FAILED.equals(fetched.failure())) {
                    return TLS_VALIDATION_FAILED;
                }
                continue;
            }
            counter.fetched++;
            byte[] body = fetched.body();
            String refused = handOver(source, runId, address, body, counter);
            if (refused != null) {
                return refused;
            }
            pages.add(body);
        }
        if (pages.isEmpty()) {
            return "NO_PAGE_READ";
        }
        List<URI> records = FollowUps.checkpointRecords(pages);
        return records.isEmpty() ? "NO_DOCUMENTS_NAMED" : followUps(source, runId, records, false, counter);
    }

    private String handOver(AdvisorySources.Source source, String runId, URI readFrom, byte[] body, Counter counter) {
        IngestResult delivered = retryIngest(() -> ingest.handOver(source.key(), runId, readFrom, body));
        counter.ingestRetries += delivered.attempts() - 1;
        if (delivered.accepted()) {
            counter.handedOver++;
            return null;
        }
        counter.ingestFailed = true;
        counter.ingestExhausted = delivered.retryable();
        return delivered.reason();
    }

    private IngestResult retryIngest(Supplier<IngestResult> call) {
        int maxAttempts = ingestBackoff.size() + 1;
        IngestResult last = IngestResult.permanentRefusal(1, INGEST_REFUSED);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                ingestSleeper.accept(ingestBackoff.get(attempt - 2));
                if (Thread.currentThread().isInterrupted()) {
                    return IngestResult.transientRefusal(attempt - 1, last.reason());
                }
            }
            IngestResult result = call.get();
            if (result.attempts() > 1) {
                return result;
            }
            if (result.accepted()) {
                return IngestResult.ok(attempt);
            }
            last = result;
            if (!result.retryable() || Thread.currentThread().isInterrupted()) {
                return IngestResult.permanentRefusal(attempt, result.reason());
            }
        }
        return IngestResult.transientRefusal(maxAttempts, last.reason());
    }

    private static void defaultSleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Counter {
        int fetched;
        int failed;
        int handedOver;
        int dropped;
        int ingestRetries;
        boolean ingestFailed;
        boolean ingestExhausted;
    }
}
