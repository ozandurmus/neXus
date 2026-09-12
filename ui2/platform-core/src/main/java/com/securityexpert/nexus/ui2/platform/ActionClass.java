package com.securityexpert.nexus.ui2.platform;

/**
 * {@code utils/action_taxonomy.py}'s five action classes, ported verbatim
 * (C2 §1.3 item 7, C4 §1.3 item 10: "the action classes, ported verbatim").
 * Declared in {@code platform-core} (no project dependencies, {@code DIR-1})
 * so every module -- capability-registry's gate rows, job-engine's job/step
 * records, the worker's retry rules -- shares one closed vocabulary rather
 * than each re-declaring its own.
 *
 * <p>{@link #id()} is the exact, persisted string this repository already
 * uses (Python's {@code ActionClass.id}); it is never re-derived from the
 * Java enum constant's name, so a persisted {@code action_class} column
 * value round-trips through {@link #fromId(String)} unchanged.</p>
 */
public enum ActionClass {
    CLASS_0_READ("read", true),
    CLASS_1_RECOVERY_WRITE("recovery-write", false),
    CLASS_1B_CONTROLLED_RESTORE_WRITE("controlled-restore-write", false),
    CLASS_2_OPERATIONAL_STATE_CHANGE("operational-state-change", false),
    CLASS_3_CONFIGURATION_WRITE("configuration-write", false),
    CLASS_4_POLICY_DEPLOYMENT("policy-deployment", false);

    private final String id;
    private final boolean consoleSubmittable;

    ActionClass(String id, boolean consoleSubmittable) {
        this.id = id;
        this.consoleSubmittable = consoleSubmittable;
    }

    public String id() {
        return id;
    }

    /** Never a job-admission or claim-time gate by itself -- see C2 §5.3/§7.3. */
    public boolean consoleSubmittable() {
        return consoleSubmittable;
    }

    /**
     * Fail-closed lookup (AGENTS.md UNKNOWN/fail-closed law): an
     * unrecognized id is never coerced to a default class.
     */
    public static ActionClass fromId(String id) {
        for (ActionClass value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unrecognized action_class id: " + id);
    }
}
