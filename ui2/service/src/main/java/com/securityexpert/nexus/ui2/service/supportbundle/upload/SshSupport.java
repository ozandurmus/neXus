package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/** The JSch session shared by SFTP and SCP: password login, the host key recorded and compared but never enforced. */
final class SshSupport {
    private SshSupport() {}

    static final class Opened {
        final Session session; final String fingerprint; final boolean changed;
        Opened(Session session, String fingerprint, boolean changed) { this.session = session; this.fingerprint = fingerprint; this.changed = changed; }
    }

    static Opened open(UploadTarget target, char[] password, HostKeyLog hostKeys) throws UploadException {
        byte[] secret = utf8(password);
        Session session = null;
        try {
            session = new JSch().getSession(target.user(), target.host(), target.port());
            session.setPassword(secret);
            // PO rule: hardening must not break connections. The key is recorded and a change is warned about, never refused.
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            session.setTimeout((int) UploadTarget.STALL_TIMEOUT.toMillis());
            session.setServerAliveInterval(15_000);
            session.connect((int) UploadTarget.CONNECT_TIMEOUT.toMillis());
            HostKey key = session.getHostKey();
            String fp = fingerprint(key);
            boolean changed = fp != null && hostKeys.record(target.host(), target.port(), key.getType(), fp);
            return new Opened(session, fp, changed);
        } catch (JSchException e) {
            if (session != null) session.disconnect();
            throw classify(e, target);
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    static String fingerprint(HostKey key) {
        if (key == null) return null;
        try {
            byte[] blob = Base64.getDecoder().decode(key.getKey());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (Exception e) {
            return null;
        }
    }

    /** A connection failure says plainly where it stopped; the egress hint is deliberate, the firewall opening is the likely cause. */
    static UploadException classify(Throwable e, UploadTarget target) {
        String where = target.host() + ":" + target.port();
        String hint = " The bank firewall may need an opening for this destination from the Overwatch host.";
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("auth fail") || message.contains("auth cancel") || message.contains("userauth")) {
            return new UploadException("AUTH_FAILED", "The destination " + where + " refused the credential.", false);
        }
        if (cause instanceof UnknownHostException) {
            return new UploadException("HOST_NOT_FOUND", "The name " + target.host() + " does not resolve from the Overwatch host.", true);
        }
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
            return new UploadException("CONNECTION_FAILED", "Could not reach " + where + " (" + cause.getClass().getSimpleName() + ")." + hint, true);
        }
        if (cause instanceof SocketTimeoutException || message.contains("timeout") || message.contains("timed out")) {
            return new UploadException("CONNECTION_TIMEOUT", "No answer from " + where + " in time." + hint, true);
        }
        return new UploadException("CONNECTION_FAILED", "The SSH connection to " + where + " failed (" + cause.getClass().getSimpleName() + ")." + hint, true);
    }

    static byte[] utf8(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) java.util.Arrays.fill(buffer.array(), (byte) 0);
        return out;
    }

    static UploadException writeFailed(IOException e) {
        return new UploadException("WRITE_FAILED", "The destination stopped accepting data (" + e.getClass().getSimpleName() + ").", false);
    }
}
