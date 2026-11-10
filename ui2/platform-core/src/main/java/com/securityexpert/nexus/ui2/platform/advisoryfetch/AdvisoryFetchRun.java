package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

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

    /** Hands documents and the closing counts to the service. Both return true when the service accepted. */
    public interface Ingest {
        boolean document(String source, String runId, URI readFrom, byte[] body);

        boolean complete(String source, String runId, int fetched, int failed, String reason);
    }

    /** The failure word of a certificate validation failure; it stops the source (PO 2026-11-10, TLS stop rule). */
    public static final String TLS_VALIDATION_FAILED = "TLS_VALIDATION_FAILED";

    /** What one source did in this run. */
    public record SourceResult(String source, int fetched, int failed, int handedOver, int dropped, String reason) {

        public boolean ok() {
            return reason == null && failed == 0;
        }
    }

    private final Outbound outbound;
    private final Ingest ingest;
    private final Consumer<String> log;
    private final Instant ciscoSince;

    public AdvisoryFetchRun(Outbound outbound, Ingest ingest, Consumer<String> log, Instant now, int ciscoWindowDays) {
        this.outbound = outbound;
        this.ingest = ingest;
        this.log = log;
        this.ciscoSince = now.minus(Duration.ofDays(Math.max(1, ciscoWindowDays)));
    }

    public SourceResult run(AdvisorySources.Source source, String runId) {
        Counter counter = new Counter();
        String reason = null;
        Fetched start = outbound.get(source, source.startUri());
        if (!start.succeeded()) {
            counter.failed++;
            reason = start.failure();
        } else {
            counter.fetched++;
            reason = switch (source.key()) {
                case "fortinet" -> followUps(source, runId, FollowUps.fortinet(start.body()), false, counter);
                case "cisco" -> followUps(source, runId, FollowUps.cisco(start.body(), ciscoSince), true, counter);
                default -> handOver(source, runId, source.startUri(), start.body(), counter);
            };
        }
        boolean closed = ingest.complete(source.key(), runId, counter.fetched, counter.failed, reason);
        if (!closed && reason == null) {
            reason = "INGEST_REFUSED";
        }
        SourceResult result = new SourceResult(source.key(), counter.fetched, counter.failed, counter.handedOver, counter.dropped, reason);
        log.accept("[ADVISORY_FETCH] source=" + result.source() + " fetched=" + result.fetched() + " failed=" + result.failed()
                + " handed_over=" + result.handedOver() + " dropped=" + result.dropped() + " reason=" + (reason == null ? "none" : reason));
        return result;
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

    private String handOver(AdvisorySources.Source source, String runId, URI readFrom, byte[] body, Counter counter) {
        if (ingest.document(source.key(), runId, readFrom, body)) {
            counter.handedOver++;
            return null;
        }
        return "INGEST_REFUSED";
    }

    private static final class Counter {
        int fetched;
        int failed;
        int handedOver;
        int dropped;
    }
}
