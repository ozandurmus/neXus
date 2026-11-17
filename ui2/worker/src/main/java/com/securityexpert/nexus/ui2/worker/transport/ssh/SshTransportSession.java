package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.util.Optional;

import com.jcraft.jsch.HostKey;
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
    com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.Lease admission;
    private InteractiveShellSession interactiveShell;

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
        return Optional.of(md5ColonHex(java.util.Base64.getDecoder().decode(hostKey.getKey())));
    }

    /**
     * The recorded-identity form: MD5 of the host-key blob as lowercase hex pairs joined by {@code ':'}, which is what
     * {@code com.jcraft:jsch 0.1.55} {@code HostKey.getFingerPrint} returned and what every Check Point
     * {@code recorded_identity_primary} was recorded in. Computed here, not through {@code getFingerPrint}, because the
     * maintained fork defaults to {@code SHA256:<base64>} (2026-11-15 upgrade broke every Check Point identity comparison).
     * An identity label only: trust is decided by {@link HostKeyVerifier} on its own SHA-256.
     */
    static String md5ColonHex(byte[] keyBlob) {
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("MD5").digest(keyBlob);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
        StringBuilder out = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format("%02x", digest[i] & 0xff));
        }
        return out.toString();
    }

    @Override
    public Optional<String> interactivePrompt() {
        return interactiveShell == null ? Optional.empty() : Optional.ofNullable(interactiveShell.prompt());
    }

    Session jschSession() {
        return jschSession;
    }

    /** One persistent interactive shell, lazily opened and reused across every
     * {@code execInteractive} call on this session (Gaia Embedded/Quantum Spark support). */
    InteractiveShellSession interactiveShell() throws com.jcraft.jsch.JSchException, java.io.IOException {
        if (interactiveShell == null || !interactiveShell.isConnected()) {
            interactiveShell = new InteractiveShellSession(jschSession, 15000);
        }
        return interactiveShell;
    }

    void closeInteractiveShell() {
        if (interactiveShell != null) {
            interactiveShell.close();
            interactiveShell = null;
        }
    }
}
