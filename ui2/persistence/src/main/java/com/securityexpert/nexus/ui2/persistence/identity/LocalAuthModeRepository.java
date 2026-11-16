package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;

/** The single local-account scope setting (migration plan Step 3, V236); one audited write. */
public interface LocalAuthModeRepository {
    /** The audit action id of every mode write. */
    String WRITE_ACTION = "local_auth_mode_write";

    String ALL_LOCAL = "ALL_LOCAL";
    String BREAK_GLASS_ONLY = "BREAK_GLASS_ONLY";

    /** {@code updatedAt}/{@code updatedBy} are null before the row was ever written. */
    record Row(String mode, Instant updatedAt, String updatedBy) {
    }

    Row read();

    Row write(String actorFingerprint, String mode);
}
