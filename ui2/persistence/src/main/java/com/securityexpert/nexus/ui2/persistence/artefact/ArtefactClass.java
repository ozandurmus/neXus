package com.securityexpert.nexus.ui2.persistence.artefact;

/** {@code backup_artefact.artefact_class} closed vocabulary (BK-16 / C7 section 3.2). */
public final class ArtefactClass {

    public static final String CONFIGURATION = "configuration";
    public static final String BACKUP = "backup";
    /** Device system-log chunks and whole-file fetches (phase B, V160). */
    public static final String SYSTEM_LOG = "system_log";

    private ArtefactClass() {
    }
}
