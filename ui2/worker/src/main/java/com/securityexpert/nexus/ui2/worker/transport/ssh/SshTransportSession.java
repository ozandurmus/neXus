package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.util.Optional;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;

/**
 * Wraps one authenticated JSch {@link Session} (contract §5: "one
 * authenticated connection per connect... may reuse one connection across
 * exec steps without ever depending on shell state left by a prior step").
 */
final class SshTransportSession implements TransportSession {

    private final String sessionId;
    private final Session jschSession;

    SshTransportSession(String sessionId, Session jschSession) {
        this.sessionId = sessionId;
        this.jschSession = jschSession;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /**
     * The SSH host-key fingerprint presented at connection
     * ({@code DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md} EC-5's Check Point
     * recorded identity). {@link HostKeyVerifier} already accepted this
     * connection under {@code StrictHostKeyChecking=yes} before this
     * session exists; this method only reads back what was presented, it
     * makes no trust decision of its own.
     */
    @Override
    public Optional<String> presentedIdentity() {
        HostKey hostKey = jschSession.getHostKey();
        if (hostKey == null) {
            return Optional.empty();
        }
        return Optional.of(hostKey.getFingerPrint(new JSch()));
    }

    Session jschSession() {
        return jschSession;
    }
}
