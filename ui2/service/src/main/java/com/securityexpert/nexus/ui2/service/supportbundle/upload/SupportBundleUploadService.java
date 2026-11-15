package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.UploadRow;
import com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleDownloadService;

/**
 * Vendor upload of a stored support bundle (design S8; PO 2026-11-15/16: no four-eyes, the same security_admin may request,
 * download and upload; Cisco CXD, Fortinet, Palo Alto and a generic SFTP server). The upload runs in a service thread because
 * the credential must live in memory only: there is no channel to hand a never-stored secret to another process. The vault
 * is read twice and streamed both times, nothing is held whole in memory: pass one measures the exact length and SHA-256 of
 * the deterministic archive, pass two sends it and must produce the same digest.
 *
 * <p>Credentials: held in a {@code char[]} for the job and zeroed in {@code finally}; never in a row, log line, audit row or
 * error text. Host key: recorded, a change warned about on the row, never a refusal. Retry: once, only for a connection that
 * failed before a single byte went out, and never after an authentication refusal (a token can lock).</p>
 */
public final class SupportBundleUploadService {

    private static final Logger LOG = Logger.getLogger(SupportBundleUploadService.class.getName());
    public static final int MIN_REASON_LENGTH = 8;
    public static final int MAX_REASON_LENGTH = 500;
    static final Pattern CASE_REF = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    static final Pattern USER = Pattern.compile("[A-Za-z0-9._@+\\\\-]{1,128}");
    static final long STALE_AFTER_SECONDS = 120;
    static final Duration RETRY_DELAY = Duration.ofSeconds(3);

    public sealed interface Outcome {
        record Started(UUID uploadId) implements Outcome { }
        record Refused(int status, String code, String reason) implements Outcome { }
    }

    /** What the administrator submitted. {@code password} is zeroed by this service once the job has ended (or was refused). */
    public record Spec(String destination, String host, Integer port, String user, char[] password, List<String> itemKinds,
            String caseRef, String reason, boolean confirmed) {
    }

    private final SupportBundleDownloadService downloads;
    private final JooqSupportBundleStore bundles;
    private final JooqSupportBundleUploadStore uploads;
    private final Map<UploadDestination.Method, UploadTransfer> transfers;
    private final Sleeper sleeper;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "support-bundle-upload");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService beat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "support-bundle-upload-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, AtomicLong> active = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    public SupportBundleUploadService(SupportBundleDownloadService downloads, JooqSupportBundleStore bundles,
            JooqSupportBundleUploadStore uploads) {
        this(downloads, bundles, uploads, Map.of(UploadDestination.Method.SFTP, new SftpTransfer(),
                UploadDestination.Method.SCP, new ScpTransfer(), UploadDestination.Method.HTTPS_PUT, new HttpsPutTransfer()),
                d -> Thread.sleep(d.toMillis()));
    }

    SupportBundleUploadService(SupportBundleDownloadService downloads, JooqSupportBundleStore bundles,
            JooqSupportBundleUploadStore uploads, Map<UploadDestination.Method, UploadTransfer> transfers, Sleeper sleeper) {
        this.downloads = downloads; this.bundles = bundles; this.uploads = uploads; this.transfers = transfers; this.sleeper = sleeper;
        beat.scheduleWithFixedDelay(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        try {
            active.forEach((id, sent) -> uploads.progress(id, sent.get(), null, null, 0));
            uploads.reapStale(STALE_AFTER_SECONDS);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "support bundle upload heartbeat failed: " + e.getClass().getSimpleName());
        }
    }

    /** Marks the uploads of a process that is gone; called at start-up. */
    public void reapAtStartup() {
        try {
            uploads.reapStale(STALE_AFTER_SECONDS);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "support bundle upload reap failed: " + e.getClass().getSimpleName());
        }
    }

    public Outcome start(UUID requestId, String actor, Spec spec) {
        try {
            return startChecked(requestId, actor, spec);
        } finally {
            // A refused spec leaves nothing behind; an accepted one has handed the array to its job, which zeroes it.
        }
    }

    private Outcome startChecked(UUID requestId, String actor, Spec spec) {
        char[] password = spec.password();
        Outcome refusal = validate(spec);
        if (refusal != null) { wipe(password); return refusal; }
        UploadDestination destination = UploadDestination.fromId(spec.destination()).orElseThrow();
        Optional<RequestRow> request = bundles.find(requestId);
        if (request.isEmpty()) { wipe(password); return new Outcome.Refused(404, "NOT_FOUND", "No support bundle with that id"); }
        RequestRow req = request.get();
        if (destination.vendor() != null && !destination.vendor().equals(req.vendor())) {
            wipe(password);
            return new Outcome.Refused(409, "DESTINATION_VENDOR_MISMATCH",
                    destination.label() + " takes " + destination.vendor() + " bundles only; use the generic SFTP server for this one");
        }
        Optional<SupportBundleDownloadService.UploadPlan> plan = downloads.uploadPlan(requestId, spec.itemKinds());
        if (plan.isEmpty()) {
            wipe(password);
            return new Outcome.Refused(409, "NOTHING_TO_UPLOAD", "The bundle holds nothing stored for the chosen items (or the vault is not mounted)");
        }
        String host = destination.generic() ? spec.host().strip() : destination.fixedHost();
        int port = destination.generic() ? (spec.port() == null ? destination.defaultPort() : spec.port()) : destination.defaultPort();
        UUID id = UUID.randomUUID();
        UploadRow row = new UploadRow(id, requestId, plan.get().items().stream().map(JooqSupportBundleStore.StoredItem::itemId).toList(),
                destination.id(), host, port, spec.user().strip(), plan.get().fileName(),
                spec.caseRef() == null || spec.caseRef().isBlank() ? null : spec.caseRef().strip(), spec.reason().strip(), actor,
                null, null, null, null, "RUNNING", 0, null, 0, null, null, null, null, null, false);
        try {
            uploads.start(row);   // audited start, committed before any vault byte is read
        } catch (RuntimeException e) {
            wipe(password);
            if (String.valueOf(e.getMessage()).contains("uq_support_bundle_upload_running")
                    || String.valueOf(e.getCause()).contains("uq_support_bundle_upload_running")) {
                return new Outcome.Refused(409, "UPLOAD_ALREADY_RUNNING", "An upload of this bundle is already running");
            }
            LOG.log(Level.SEVERE, "support bundle upload audit could not be written; upload refused: " + e.getClass().getSimpleName());
            return new Outcome.Refused(500, "AUDIT_REFUSED", "The upload could not be audited; nothing was sent");
        }
        UploadTarget target = new UploadTarget(destination, host, port, spec.user().strip(), plan.get().fileName());
        try {
            pool.execute(() -> run(id, actor, target, password, plan.get()));
        } catch (RejectedExecutionException busy) {
            wipe(password);
            uploads.finish(id, actor, "FAILED", 0, null, null, null, "UPLOAD_BUSY", "Two uploads are already running; try again shortly",
                    null, false, 0);
            return new Outcome.Refused(429, "UPLOAD_BUSY", "Two uploads are already running; try again shortly");
        }
        return new Outcome.Started(id);
    }

    static Outcome validate(Spec spec) {
        if (!spec.confirmed()) return new Outcome.Refused(400, "CONFIRMATION_REQUIRED",
                "Confirm that this bundle may be sent to an external destination");
        if (spec.reason() == null || spec.reason().strip().length() < MIN_REASON_LENGTH || spec.reason().strip().length() > MAX_REASON_LENGTH)
            return new Outcome.Refused(400, "REASON_REQUIRED", "A reason of 8 to 500 characters is required");
        Optional<UploadDestination> destination = UploadDestination.fromId(spec.destination());
        if (destination.isEmpty()) return new Outcome.Refused(400, "INVALID_DESTINATION", "Unknown upload destination");
        if (spec.user() == null || !USER.matcher(spec.user().strip()).matches())
            return new Outcome.Refused(400, "INVALID_USER", "The remote user may use letters, digits and . _ @ + - \\ (128 at most)");
        char[] password = spec.password();
        if (password == null || password.length == 0 || password.length > 512)
            return new Outcome.Refused(400, "PASSWORD_REQUIRED", "The upload token or password is required (512 characters at most)");
        for (char c : password) if (Character.isISOControl(c)) return new Outcome.Refused(400, "INVALID_PASSWORD", "The password holds a control character");
        if (spec.caseRef() != null && !spec.caseRef().isBlank() && !CASE_REF.matcher(spec.caseRef().strip()).matches())
            return new Outcome.Refused(400, "INVALID_CASE_REF", "The case reference may use letters, digits, - and _ (40 at most)");
        if (destination.get().generic()) {
            if (!UploadDestination.validHost(spec.host() == null ? null : spec.host().strip()))
                return new Outcome.Refused(400, "INVALID_HOST", "The host must be a host name or an IP address");
            if (spec.port() != null && !UploadDestination.validPort(spec.port()))
                return new Outcome.Refused(400, "INVALID_PORT", "The port must be 1 to 65535");
        } else if ((spec.host() != null && !spec.host().isBlank()) || spec.port() != null) {
            return new Outcome.Refused(400, "DESTINATION_FIXED", "This destination has a fixed host and port; leave them out");
        }
        return null;
    }

    // ------------------------------------------------------------------ the job

    private void run(UUID id, String actor, UploadTarget target, char[] password, SupportBundleDownloadService.UploadPlan plan) {
        AtomicLong sent = new AtomicLong();
        active.put(id, sent);
        int attempts = 0;
        Long total = null;
        String sha = null;
        try {
            // Pass one: exact length and digest, nothing sent, nothing held.
            Measure measure = new Measure();
            try {
                plan.body().writeTo(measure);
            } catch (IOException e) {
                end(id, actor, "FAILED", 0, null, null, null, "SOURCE_FAILED", "The stored bundle could not be read from the vault", null, false, 0);
                return;
            }
            total = measure.count; sha = measure.hex();
            uploads.progress(id, 0, total, sha, 0);
            UploadTransfer transfer = transfers.get(target.destination().method());
            UploadException last = null;
            while (attempts < 2) {
                attempts++;
                sent.set(0);
                Body body = new Body(plan, total, sent, sha);
                try {
                    TransferResult result = transfer.send(target, password, body, uploads::recordHostKey);
                    if (body.count != total) {
                        end(id, actor, "FAILED", body.count, total, sha, result.remoteStatus(), "LENGTH_MISMATCH",
                                "The archive length changed between the two vault passes", result.hostKeyFingerprint(), result.hostKeyChanged(), attempts);
                        return;
                    }
                    if (!sha.equals(body.hex())) {
                        end(id, actor, "FAILED", body.count, total, sha, result.remoteStatus(), "DIGEST_CHANGED",
                                "The archive digest changed between the two vault passes; the remote copy must not be used",
                                result.hostKeyFingerprint(), result.hostKeyChanged(), attempts);
                        return;
                    }
                    String status = result.remoteStatus() + (result.hostKeyChanged() ? "; WARNING: the destination host key differs from one seen before" : "");
                    end(id, actor, "COMPLETED", body.count, total, sha, status, null, null, result.hostKeyFingerprint(), result.hostKeyChanged(), attempts);
                    return;
                } catch (UploadException e) {
                    last = e;
                    boolean noByteSent = sent.get() == 0;
                    if (attempts < 2 && e.retryable() && noByteSent) {
                        try { sleeper.sleep(RETRY_DELAY); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    break;
                }
            }
            UploadException e = last == null ? new UploadException("INTERRUPTED", "The upload was interrupted", false) : last;
            end(id, actor, "FAILED", sent.get(), total, sha, null, e.code(), e.getMessage(), null, false, attempts);
        } catch (RuntimeException unexpected) {
            LOG.log(Level.SEVERE, "support bundle upload " + id + " failed unexpectedly: " + unexpected.getClass().getSimpleName());
            end(id, actor, "FAILED", sent.get(), total, sha, null, "UPLOAD_ERROR", "Unexpected error: " + unexpected.getClass().getSimpleName(),
                    null, false, attempts);
        } finally {
            active.remove(id);
            wipe(password);
        }
    }

    private void end(UUID id, String actor, String state, long bytes, Long total, String sha, String remoteStatus, String code,
            String detail, String hostKey, boolean changed, int attempts) {
        try {
            uploads.finish(id, actor, state, bytes, total, sha, remoteStatus, code, detail, hostKey, changed, attempts);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "support bundle upload " + id + " end could not be recorded: " + e.getClass().getSimpleName());
        }
    }

    static void wipe(char[] secret) {
        if (secret != null) java.util.Arrays.fill(secret, '\0');
    }

    public Optional<UploadRow> find(UUID id) { return uploads.find(id); }

    public List<UploadRow> list(UUID requestId) { return new ArrayList<>(uploads.list(requestId)); }

    // ------------------------------------------------------------------ streams

    /** Counts and digests the bytes of pass one. */
    private static final class Measure extends OutputStream {
        final MessageDigest digest = newDigest();
        long count;
        @Override public void write(int b) { digest.update((byte) b); count++; }
        @Override public void write(byte[] b, int off, int len) { digest.update(b, off, len); count += len; }
        String hex() { return HexFormat.of().formatHex(digest.digest()); }
    }

    /** Pass two: the same archive, counted and digested on its way to the destination. */
    private static final class Body implements UploadBody {
        private final SupportBundleDownloadService.UploadPlan plan;
        private final long length; private final AtomicLong sent; private final String expected;
        private MessageDigest digest = newDigest();
        long count;
        private String hex;

        Body(SupportBundleDownloadService.UploadPlan plan, long length, AtomicLong sent, String expected) {
            this.plan = plan; this.length = length; this.sent = sent; this.expected = expected;
        }

        @Override public long length() { return length; }

        String hex() { if (hex == null) hex = HexFormat.of().formatHex(digest.digest()); return hex; }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            RemoteSide remote = new RemoteSide(out);
            try {
                plan.body().writeTo(remote);
            } catch (IOException e) {
                if (remote.failed) throw e;
                throw new SftpTransfer.SourceReadException(e);
            }
        }

        private final class RemoteSide extends OutputStream {
            private final OutputStream out;
            boolean failed;
            RemoteSide(OutputStream out) { this.out = out; }
            @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                try {
                    out.write(b, off, len);
                } catch (IOException e) {
                    failed = true;
                    throw e;
                }
                digest.update(b, off, len);
                count += len;
                sent.set(count);
            }
            @Override public void flush() throws IOException {
                try { out.flush(); } catch (IOException e) { failed = true; throw e; }
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
