package com.securityexpert.nexus.ui2.persistence.device;

import org.jooq.DSLContext;

/**
 * Writes the verdict of one pinned-identity comparison into {@code device_identity_check} (V165). Called inside the
 * transaction the transport already opens for its trust decision; it only records the outcome and never changes it.
 * The verdict is a fixed word, the transport a fixed token: no fingerprint, key or address is written.
 */
public final class DeviceIdentityCheckRecorder {

    public static final String SSH_HOST_KEY = "SSH_HOST_KEY";
    public static final String HTTPS_CERTIFICATE = "HTTPS_CERTIFICATE";
    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    public static final String FIRST_USE = "FIRST_USE";

    private static final String UPSERT_TAIL = " on conflict (device_id) do update set transport = excluded.transport, "
            + "verdict = excluded.verdict, checked_at = now()";

    private DeviceIdentityCheckRecorder() {
    }

    /** One device, by id; an id that is not an enrolled device row is ignored (the SELECT yields nothing). */
    public static void forDevice(DSLContext db, String deviceId, String transport, String verdict) {
        if (deviceId == null) return;
        db.execute("INSERT INTO device_identity_check(device_id, transport, verdict, checked_at) "
                + "SELECT device_id, ?, ?, now() FROM devices WHERE device_id = ?" + UPSERT_TAIL, transport, verdict, deviceId);
    }
}
