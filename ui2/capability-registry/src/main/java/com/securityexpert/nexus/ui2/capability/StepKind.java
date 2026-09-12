package com.securityexpert.nexus.ui2.capability;

/**
 * C4 §2.3's closed nine-member step-kind set. Adding a kind is a {@code C4}
 * amendment, never a runtime configuration choice (C4 §2.3) -- there is no
 * "custom" or "unrecognized-but-accepted" variant; {@link #fromSpecValue}
 * fails closed on any other token, which is exactly {@code
 * STEP_KIND_NOT_IN_CLOSED_SET} (contract §3 item 1, C4 §7-1).
 *
 * <p><b>Why nine, not ten.</b> C4 §2.3's table lists {@code sftp_get / scp_get}
 * as one row sharing one set of semantics (fetch a remote path into the
 * evidence store's staging area; same expectation gate; same gate-reference
 * applicability rule). This enum represents that row as the single member
 * {@link #SFTP_GET}; a spec author's {@code scp_get} token is accepted as an
 * alias resolving to the same kind ({@link #fromSpecValue}), never a tenth,
 * distinct closed-set member -- this is what keeps the set at exactly nine,
 * matching contract §3 item 1's "closed nine-member set" and C4's own
 * "closed nine-member set" language over a table that otherwise reads as
 * ten distinct kind names.</p>
 */
public enum StepKind {
    CONNECT(GateApplicability.NOT_APPLICABLE),
    EXEC(GateApplicability.RESOLVED),
    POLL(GateApplicability.RESOLVED),
    /** Spec token {@code sftp_get} or its alias {@code scp_get} (see class javadoc). */
    SFTP_GET(GateApplicability.DEPENDS_ON_PRIOR_STEP),
    /** Reserved: refused at spec-validation time, unconditionally (C4 §2.3, §7-6). */
    SFTP_PUT(GateApplicability.NOT_APPLICABLE),
    RESTORE_PUSH(GateApplicability.RESOLVED),
    XML_API_CALL(GateApplicability.RESOLVED),
    VERIFY(GateApplicability.NOT_APPLICABLE),
    DISCONNECT(GateApplicability.NOT_APPLICABLE);

    /**
     * C4 §2.3's rightmost column. {@code RESOLVED} means the step always
     * names a gate reference; {@code NOT_APPLICABLE} means gate resolution
     * never runs for this kind; {@code DEPENDS_ON_PRIOR_STEP} means the
     * spec itself declares {@code NOT_APPLICABLE} when the fetched path was
     * produced by an already-gated prior step, and resolves normally
     * otherwise -- the spec author's declaration decides, not the kind
     * alone (C4 §2.3's {@code sftp_get}/{@code scp_get} row).
     */
    public enum GateApplicability {
        RESOLVED,
        NOT_APPLICABLE,
        DEPENDS_ON_PRIOR_STEP
    }

    private final GateApplicability gateApplicability;

    StepKind(GateApplicability gateApplicability) {
        this.gateApplicability = gateApplicability;
    }

    public GateApplicability gateApplicability() {
        return gateApplicability;
    }

    /**
     * Fail-closed parse (AGENTS.md UNKNOWN/fail-closed law; C4 §7-1): never
     * logs a warning and continues on an unrecognized token -- throws, so
     * the caller can surface {@code STEP_KIND_NOT_IN_CLOSED_SET}.
     */
    public static StepKind fromSpecValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("STEP_KIND_NOT_IN_CLOSED_SET: step kind must not be null");
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "connect" -> CONNECT;
            case "exec" -> EXEC;
            case "poll" -> POLL;
            case "sftp_get", "scp_get" -> SFTP_GET;
            case "sftp_put" -> SFTP_PUT;
            case "restore_push" -> RESTORE_PUSH;
            case "xml_api_call" -> XML_API_CALL;
            case "verify" -> VERIFY;
            case "disconnect" -> DISCONNECT;
            default -> throw new IllegalArgumentException(
                    "STEP_KIND_NOT_IN_CLOSED_SET: '" + value + "' is not one of C4 §2.3's nine members");
        };
    }
}
