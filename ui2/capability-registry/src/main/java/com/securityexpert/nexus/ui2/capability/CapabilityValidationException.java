package com.securityexpert.nexus.ui2.capability;

/**
 * A named, closed-vocabulary capability-spec validation failure (C4 §3,
 * §7; contract §3). The code is always one of the constants below -- never
 * a free-form message alone -- so a test can assert on the failure kind,
 * not string-match a prose sentence.
 */
public final class CapabilityValidationException extends RuntimeException {

    public static final String STEP_KIND_NOT_IN_CLOSED_SET = "STEP_KIND_NOT_IN_CLOSED_SET";
    public static final String SFTP_PUT_RESERVED = "SFTP_PUT_RESERVED";
    public static final String RESTORE_PUSH_ACTION_CLASS_INVALID = "RESTORE_PUSH_ACTION_CLASS_INVALID";
    public static final String STEP_TRANSPORT_EVIDENCE_MISSING = "STEP_TRANSPORT_EVIDENCE_MISSING";
    public static final String CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT = "CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT";
    public static final String UNKNOWN_SPEC_FIELD = "UNKNOWN_SPEC_FIELD";
    public static final String MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";

    private final String code;

    public CapabilityValidationException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
