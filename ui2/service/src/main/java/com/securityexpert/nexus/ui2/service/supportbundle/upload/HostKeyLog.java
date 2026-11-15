package com.securityexpert.nexus.ui2.service.supportbundle.upload;

/** Records the host key a destination presented; the answer says whether it differs from one seen before. Never refuses. */
@FunctionalInterface
public interface HostKeyLog {
    /** @return true when this destination had a different key on record (a change worth a warning) */
    boolean record(String host, int port, String keyType, String fingerprintSha256);
}
