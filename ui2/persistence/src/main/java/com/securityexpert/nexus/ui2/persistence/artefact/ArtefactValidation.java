package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;

/**
 * {@code backup_artefact.validation} JSONB shape (BK-16 / C7 section 3.4):
 * the level a run actually reached, section 3.4's V3 (restore-attempted)
 * status, and whether a restore was actually proven.
 *
 * <p>This movement never attempts a restore (WORKER.md scope: "no restore
 * in any form"), so {@link #reachedWithoutRestore(String)} is the only way
 * to construct one here -- it always records {@link #NOT_APPLICABLE} for
 * V3 and {@code false} for {@code restoreProven}, since there is no proof
 * record type in this codebase yet that could honestly claim otherwise.</p>
 */
public record ArtefactValidation(String levelReached, String v3Status, boolean restoreProven) {

    public static final String V1 = "V1";
    public static final String V2 = "V2";
    public static final String V3 = "V3";
    public static final String V4 = "V4";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    public ArtefactValidation {
        Objects.requireNonNull(levelReached, "levelReached");
        Objects.requireNonNull(v3Status, "v3Status");
        if (restoreProven && !PASS.equals(v3Status)) {
            throw new IllegalArgumentException("restore_proven can only be true alongside a V3 status of PASS "
                    + "(section 3.4: restore_proven is false without a proof record)");
        }
    }

    /** The only constructor this movement uses -- see class Javadoc. */
    public static ArtefactValidation reachedWithoutRestore(String levelReached) {
        return new ArtefactValidation(levelReached, NOT_APPLICABLE, false);
    }

    /** The JSONB literal persisted on the manifest row. */
    String toJson() {
        return "{\"level_reached\":\"" + escape(levelReached) + "\",\"v3_status\":\"" + escape(v3Status)
                + "\",\"restore_proven\":" + restoreProven + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
