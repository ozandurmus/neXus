package com.securityexpert.nexus.ui2.worker.discovery.cp;

/**
 * T-7/AGENTS.md "Letting a jsch exception message carry the host or the
 * username into a result object": a fixed, no-argument message only -- this
 * type deliberately has no constructor that accepts the underlying {@code
 * ExecResult} reason or any response text, so a caller cannot accidentally
 * thread a raw or secret-bearing string through it into {@link
 * com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult.Failed}.
 */
final class ManagementPlaneQueryFailedException extends RuntimeException {

    ManagementPlaneQueryFailedException() {
        super("management-plane query did not complete", null, false, false);
    }
}
