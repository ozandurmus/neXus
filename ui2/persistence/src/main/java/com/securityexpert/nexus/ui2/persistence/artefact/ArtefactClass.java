package com.securityexpert.nexus.ui2.persistence.artefact;

/** {@code backup_artefact.artefact_class} closed vocabulary (BK-16 / C7 section 3.2). */
public final class ArtefactClass {

    public static final String CONFIGURATION = "configuration";
    public static final String BACKUP = "backup";
    /** Device system-log chunks and whole-file fetches (phase B, V160). */
    public static final String SYSTEM_LOG = "system_log";
    /**
     * Generated report files (Reports R1, V190): vendor {@link #REPORT_VENDOR}, device {@link #REPORT_DEVICE_ID}, always
     * an {@code expires_at} (30 days); the retention janitor removes them once expired.
     */
    public static final String REPORT = "report";
    public static final String REPORT_VENDOR = "nexus";
    public static final String REPORT_DEVICE_ID = "platform:reports";

    /**
     * Support bundle items (V230, SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md): vendor TAC files, always an
     * {@code expires_at} (14 days); outside the backup storage budget, own fleet quota.
     */
    public static final String SUPPORT_BUNDLE = "support_bundle";

    /**
     * Operator-uploaded script bodies (Script Execution slice 1, V251; PO decision 2026-11-18): every script body
     * (text or binary) is envelope-encrypted at rest and write-only (never returned over any API, CLI or UI route).
     */
    public static final String SCRIPT_BODY = "script_body";
    public static final String SCRIPT_VENDOR = "script_body";
    public static final String SCRIPT_DEVICE_ID = "platform:scripts";

    /**
     * Captured script run output (Script Execution slice 2, V258; SCRIPT_EXECUTION_CONTRACT.md section 3.3 and 4.3):
     * masked stdout and stderr up to 256 KiB each, envelope-encrypted at rest, 30-day default retention.
     */
    public static final String SCRIPT_OUTPUT = "script_output";
    public static final String SCRIPT_OUTPUT_VENDOR = "script_output";

    private ArtefactClass() {
    }
}
