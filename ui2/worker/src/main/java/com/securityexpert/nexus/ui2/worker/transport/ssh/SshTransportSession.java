package com.securityexpert.nexus.ui2.worker.transport.ssh;

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

    Session jschSession() {
        return jschSession;
    }
}
