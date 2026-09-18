package com.securityexpert.nexus.ui2.worker.discovery.cp;

/**
 * T-7/AGENTS.md "Letting a jsch exception message carry the host or the
 * username into a result object": diagnostic detail passed to this type must
 * be a sanitized parser or operation description, never an underlying {@code
 * ExecResult} reason or response text, so a caller cannot accidentally
 * thread a raw or secret-bearing string through it into {@link
 * com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult.Failed}.
 */
final class ManagementPlaneQueryFailedException extends RuntimeException {

    ManagementPlaneQueryFailedException() {
        this(null, null);
    }

    ManagementPlaneQueryFailedException(String detail) {
        this(detail, null);
    }

    ManagementPlaneQueryFailedException(String detail, Throwable cause) {
        super(detail == null ? "management-plane query did not complete"
                : "management-plane query did not complete: " + detail, cause, true, true);
    }
}
