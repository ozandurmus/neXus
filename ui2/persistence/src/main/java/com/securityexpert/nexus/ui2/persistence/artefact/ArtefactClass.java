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

    private ArtefactClass() {
    }
}
