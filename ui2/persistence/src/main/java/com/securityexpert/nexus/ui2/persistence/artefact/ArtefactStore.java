package com.securityexpert.nexus.ui2.persistence.artefact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The configuration-collection artefact store (14G CG-9, 13F CF-2, C7
 * section 4 minimum viable form -- see {@link
 * com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher}'s own class
 * comment for what "minimum" leaves out). Every raw configuration read is
 * written here, encrypted at rest, gzip-compressed when the caller asks for
 * it, and identified afterward only by the opaque {@link ArtefactRef} this
 * store returns -- never a database row for the plaintext bytes themselves
 * (AGENTS.md raw-evidence law; C7 section 1.4: "a backup artefact... never
 * a database row").
 *
 * <p>{@link #open} streams: the caller writes plaintext bytes to {@link
 * ArtefactHandle#sink()} as it reads them from the device (14G CG-5: never
 * held whole in memory), and calls {@link ArtefactHandle#finish()} once
 * every byte has been written. This lets one XML-streaming pass (Palo
 * Alto's {@code effective-running}) both parse the document and pipe its
 * raw bytes through this store in a single read of the transport's
 * response.</p>
 */
public interface ArtefactStore {

    /**
     * Stable, machine-readable reason a write refused by the store's byte quota carries: the message of every
     * {@link QuotaExceededException} starts with it, and a job failed for that cause names it as its terminal reason.
     */
    String QUOTA_EXCEEDED_REASON = "artefact_store_quota_exceeded";

    /**
     * Verifies that the store is below its enforced byte quota before a new artefact write begins -- a backup
     * executor calls it before it opens any device session, so a full store costs no device work. The default is
     * a no-op for stores without a quota (test doubles).
     *
     * @throws QuotaExceededException when the store has reached its quota
     * @throws IOException if the store usage cannot be measured
     */
    default void checkQuota() throws IOException {
    }

    /**
     * Like {@link #checkQuota()}, and also verifies the per-store quota (backlog backup_screen_backbox_model) of
     * {@code store} -- the vendor segment the artefact will be written under. The default ignores the store.
     */
    default void checkQuota(String store) throws IOException {
        checkQuota();
    }

    ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException;

    /**
     * Decrypts (and, if {@code gzip} was true at write time, decompresses)
     * the artefact back into a stream. {@code wrappedDataKey} is the value
     * {@link ArtefactMetadata#wrappedDataKey()} returned at write time --
     * the store itself holds only the master key, never a per-artefact
     * data key, so a caller must supply the wrapped form it persisted on
     * its own manifest row (BK-15: "no artefact is readable with the
     * master key alone").
     */
    InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException;

    /**
     * Removes an expired artefact file from the store (retention pruning). Returns {@code true} if a file existed and
     * was removed, {@code false} if already absent.
     */
    default boolean delete(ArtefactRef ref) throws IOException {
        return false;
    }

    interface ArtefactHandle extends Closeable {

        /** Write plaintext bytes here, in any number of calls. */
        OutputStream sink();

        /** Closes {@link #sink()} and returns the recorded metadata. Call exactly once, after every byte is written. */
        ArtefactMetadata finish() throws IOException;
    }

    /**
     * A write refused by the enforced byte quota (backlog {@code vault_has_no_enforced_quota_on_a_shared_host}). The
     * message always begins with {@link #QUOTA_EXCEEDED_REASON}; no partial file is left behind and no existing
     * artefact is ever deleted to make room.
     */
    final class QuotaExceededException extends IOException {

        private final long usedBytes;
        private final long quotaBytes;
        private final long attemptedBytes;
        private final String store;

        public QuotaExceededException(long usedBytes, long quotaBytes, long attemptedBytes) {
            this(null, usedBytes, quotaBytes, attemptedBytes);
        }

        /** {@code store} names the per-store quota that refused the write; null for the global quota. */
        public QuotaExceededException(String store, long usedBytes, long quotaBytes, long attemptedBytes) {
            super(QUOTA_EXCEEDED_REASON + (store == null ? "" : ": store=" + store) + (store == null ? ": " : ", ")
                    + "used=" + usedBytes + " bytes"
                    + (attemptedBytes > 0 ? ", attempted=" + attemptedBytes + " bytes" : "")
                    + ", quota=" + quotaBytes + " bytes; write refused, existing backups kept");
            this.usedBytes = usedBytes;
            this.quotaBytes = quotaBytes;
            this.attemptedBytes = attemptedBytes;
            this.store = store;
        }

        /** The store whose per-store quota refused the write, or null when the global quota did. */
        public String store() {
            return store;
        }

        public long usedBytes() {
            return usedBytes;
        }

        public long quotaBytes() {
            return quotaBytes;
        }

        public long attemptedBytes() {
            return attemptedBytes;
        }

        /**
         * The quota refusal carried somewhere in {@code failure}'s cause chain or message, if any: a transport
         * that streams into an artefact sink may wrap the exception or flatten it into a reason string.
         */
        public static java.util.Optional<String> reasonIn(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof QuotaExceededException q) {
                    return java.util.Optional.of(q.getMessage());
                }
            }
            return failure == null ? java.util.Optional.empty() : reasonIn(failure.getMessage());
        }

        /** The quota refusal embedded in a flattened failure reason, from {@link #QUOTA_EXCEEDED_REASON} on. */
        public static java.util.Optional<String> reasonIn(String reason) {
            int at = reason == null ? -1 : reason.indexOf(QUOTA_EXCEEDED_REASON);
            return at < 0 ? java.util.Optional.empty() : java.util.Optional.of(reason.substring(at));
        }
    }

    /** @param wrappedDataKey this artefact's fresh, random data key (BK-15), wrapped under the master key -- never the raw data key. */
    record ArtefactMetadata(
            ArtefactRef ref,
            String plaintextSha256,
            long plaintextBytes,
            String ciphertextSha256,
            long ciphertextBytes,
            String compression,
            String keyId,
            byte[] wrappedDataKey) {
    }
}
