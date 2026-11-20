package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * PO 2026-10-24 (docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md): where a device's newest stored
 * effective-running configuration is, so inventory can derive a PAN-OS 12 firewall's configured interfaces from it
 * instead of sending show interface all. The same join the local policy fallback uses (run -> configuration artefact ->
 * backup manifest for the wrapped data key), excluding an artefact the retention ledger records as removed. Read-only.
 */
public final class LatestEffectiveRunningReader {

    /** The artefact reference, its wrapped data key, whether it is gzip-compressed, and when the run was collected. */
    public record Stored(String artefactRef, byte[] wrappedDataKey, boolean gzip, Instant collectedAt) {
    }

    static final String SQL = "select artefact_ref, wrapped_data_key, compression, collected_at "
            + "from v_latest_effective_running_config "
            + "where device_id = {0} "
            + "order by collected_at desc limit 1";

    private final TransactionBoundary tx;

    public LatestEffectiveRunningReader(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    public Optional<Stored> latest(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return tx.inTransaction(db -> db.fetch(SQL, deviceId).stream().findFirst().map(row -> new Stored(
                row.get("artefact_ref", String.class), row.get("wrapped_data_key", byte[].class),
                "gzip".equals(row.get("compression", String.class)),
                row.get("collected_at", java.sql.Timestamp.class).toInstant())));
    }
}
