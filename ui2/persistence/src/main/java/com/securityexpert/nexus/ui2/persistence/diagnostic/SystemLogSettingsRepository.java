package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.Instant;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;

/**
 * The write side of the single {@code system_log_settings} row (V160; DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section
 * 14, {@code PUT /api/v2/config/system-logs}). The read of the same row for the collector and the read routes stays
 * {@link SystemLogCollectionRepository#settings()}; this port adds who changed it last and when, and the one audited
 * write under {@link #WRITE_ACTION}.
 */
public interface SystemLogSettingsRepository {
    /** The audit action id of every settings write (contract section 14): {@code app.action_id} of the transaction. */
    String WRITE_ACTION = "system_log_settings_write";

    /** The row as stored: the typed settings, and the last change (null when the row has not been written yet). */
    record Row(Settings settings, Instant updatedAt, String updatedBy) {
    }

    Row read();

    /**
     * Replaces the whole row in one audited transaction ({@code app.actor_fingerprint} = the caller,
     * {@code app.action_id} = {@link #WRITE_ACTION}); {@code updated_at} is the database clock and {@code updated_by} the
     * actor. Returns the row as stored after the write.
     */
    Row write(String actorFingerprint, Settings settings);
}
