package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportNotImplementedException;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * The {@code ssh_exec} adapter (contract §5) -- the only transport
 * implementation this movement ships, and the only implementation of
 * {@link DeviceTransport} anywhere in the codebase (AC-1). One
 * authenticated connection per {@link #connect}; each {@link #exec} opens
 * its own one-shot {@code exec_command} channel, no persistent shell, no
 * shared mutable state (C4 §5.1, §2.3). Never contacted against a real
 * device or even a real network endpoint by any test in this environment
 * (contract §1) -- every test exercising this class either uses a
 * container-hosted SSH endpoint (disabled placeholder here, no container
 * runtime) or tests {@link HostKeyVerifier}/parsing logic in isolation
 * from any socket.
 */
public final class SshExecTransport implements DeviceTransport {

    private static final System.Logger LOG = System.getLogger(SshExecTransport.class.getName());

    private final SshCredentialResolver credentialResolver;
    private final TrustRuleResolver trustRuleResolver;

    public SshExecTransport(SshCredentialResolver credentialResolver, TrustRuleResolver trustRuleResolver) {
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.trustRuleResolver = Objects.requireNonNull(trustRuleResolver, "trustRuleResolver");
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        if (spec.trustRuleRef() == null || spec.trustRuleRef().isBlank()) {
            LOG.log(System.Logger.Level.WARNING, "SSH connection aborted for {0}:{1}: trust rule ref is missing",
                    target.host(), target.port());
            return new ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        }
        var algorithms = trustRuleResolver.authorizedAlgorithms(spec.trustRuleRef(), target.host(), target.port());
        if (algorithms.isPresent() && algorithms.get().isEmpty()) {
            return new ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        }
        SshCredentialMaterial credential = credentialResolver.resolve(spec.credentialRef());
        // Per-connect state: a rejection is determined by the key hook, never exception substrings.
        String[] trustFailure = {null};
        boolean[] trusted = {false};
        long connectStartMs = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO,
                "[SSH_CONNECT] START target={0}:{1} user={2} timeout={3}ms",
                target.host(), target.port(), credential.username(), timeout.toMillis());

        // Fast-fail socket pre-check: verify port reachability within 10000ms before blocking in JSch handshake
        try (java.net.Socket preSocket = new java.net.Socket()) {
            int socketTimeout = (int) Math.min(timeout.toMillis(), 10000L);
            long preStart = System.currentTimeMillis();
            preSocket.connect(new java.net.InetSocketAddress(target.host(), target.port()), socketTimeout);
            long preElapsed = System.currentTimeMillis() - preStart;
            LOG.log(System.Logger.Level.INFO,
                    "[SSH_CONNECT] TCP pre-connect SUCCESS in {0}ms for {1}:{2}",
                    preElapsed, target.host(), target.port());
        } catch (java.io.IOException networkFailure) {
            long preElapsed = System.currentTimeMillis() - connectStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_CONNECT] TCP pre-connect FAILED after {0}ms for {1}:{2}: {3}",
                    preElapsed, target.host(), target.port(), networkFailure.getMessage());
            return new ConnectResult.TimedOut("tcp_connect_timeout: port " + target.port() + " unreachable (" + networkFailure.getMessage() + ")");
        }

        Session session = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(credential.username(), target.host(), target.port());
            session.setUserInfo(silentUserInfo(credential));
            if (credential.password() != null) {
                session.setPassword(new String(credential.password()));
            }
            // Host-key verification (contract §5, AGENTS.md "Check Point"):
            // production SSH requires a trusted host key -- never accept-any,
            // never trust-on-first-use. HostKeyVerifier is the pure decision;
            // this repository wires it to JSch's own verification hook.
            session.setHostKeyRepository(trustedOnlyRepository(spec.trustRuleRef(), target, trustFailure, trusted));
            session.setConfig("StrictHostKeyChecking", "yes");
            long jschStart = System.currentTimeMillis();
            session.connect((int) timeout.toMillis());
            long totalConnectElapsed = System.currentTimeMillis() - connectStartMs;
            long jschElapsed = System.currentTimeMillis() - jschStart;
            LOG.log(System.Logger.Level.INFO,
                    "[SSH_CONNECT] AUTHENTICATED for {0}:{1} in {2}ms (jsch={3}ms)",
                    target.host(), target.port(), totalConnectElapsed, jschElapsed);

            SshTransportSession wrapped = new SshTransportSession(UUID.randomUUID().toString(), session);
            return new ConnectResult.Authenticated(wrapped);
        } catch (JSchException e) {
            long totalConnectElapsed = System.currentTimeMillis() - connectStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_CONNECT] FAILED for {0}:{1} after {2}ms: {3}",
                    target.host(), target.port(), totalConnectElapsed, e.getMessage());
            if (session != null) {
                session.disconnect();
            }
            if (trustFailure[0] != null) {
                LOG.log(System.Logger.Level.WARNING, "SSH connection aborted for {0}:{1}: {2}",
                        target.host(), target.port(), trustFailure[0]);
                return new ConnectResult.HostKeyRejected(trustFailure[0]);
            }
            if (algorithms.isPresent()) {
                return discoveryFailure(e, trustFailure[0], trusted[0]);
            }
            String message = String.valueOf(e.getMessage());
            String lowerMessage = message.toLowerCase(java.util.Locale.ROOT);
            if (lowerMessage.contains("hostkey") || lowerMessage.contains("reject")) {
                return new ConnectResult.HostKeyRejected(message);
            }
            if (isNetworkFailure(e) || lowerMessage.contains("timeout") || lowerMessage.contains("timed out")
                    || message.contains("Auth cancel")) {
                if (lowerMessage.contains("read timed out")) {
                    return new ConnectResult.TimedOut("ssh_banner_timeout: remote host did not respond to SSH banner exchange");
                }
                return new ConnectResult.TimedOut("ssh_connect_timeout: " + message);
            }
            return new ConnectResult.AuthenticationFailed(message);
        } finally {
            if (session != null && !session.isConnected()) {
                session.disconnect();
            }
        }
    }

    static ConnectResult discoveryFailure(JSchException failure, String trustFailure, boolean trusted) {
        if (trustFailure != null) {
            return new ConnectResult.HostKeyRejected(trustFailure);
        }
        if (trusted && ("Auth fail".equals(failure.getMessage()) || "Auth cancel".equals(failure.getMessage()))) {
            return new ConnectResult.AuthenticationFailed("AUTH_FAILED");
        }
        if (!trusted && isNetworkFailure(failure)) {
            return new ConnectResult.TimedOut();
        }
        return new ConnectResult.AuthenticationFailed("NOT_EVALUABLE");
    }

    private static boolean isNetworkFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.NoRouteToHostException || cause instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        String msg = String.valueOf(failure != null ? failure.getMessage() : "").toLowerCase(java.util.Locale.ROOT);
        return msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection refused")
                || msg.contains("no route");
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new ExecResult.ChannelFailed("not an ssh_exec session");
        }
        long execStartMs = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO,
                "[SSH_EXEC] START cmd=\"{0}\" timeout={1}ms",
                spec.command(), timeout.toMillis());
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) sshSession.jschSession().openChannel("exec");
            channel.setCommand(spec.command());
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect((int) timeout.toMillis());

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            long firstByteMs = -1;
            while (true) {
                while (in.available() > 0) {
                    int read = in.read(chunk, 0, chunk.length);
                    if (read < 0) {
                        break;
                    }
                    if (firstByteMs < 0) {
                        firstByteMs = System.currentTimeMillis() - execStartMs;
                        LOG.log(System.Logger.Level.DEBUG,
                                "[SSH_EXEC] FIRST_DATA cmd=\"{0}\" after {1}ms", spec.command(), firstByteMs);
                    }
                    buffer.write(chunk, 0, read);
                }
                while (err.available() > 0) {
                    if (err.read(chunk, 0, chunk.length) < 0) {
                        break;
                    }
                }
                if (channel.isClosed()) {
                    if (in.available() > 0) {
                        continue;
                    }
                    long durationMs = System.currentTimeMillis() - execStartMs;
                    String outStr = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                    int lines = outStr.split("\r\n|\r|\n").length;
                    LOG.log(System.Logger.Level.INFO,
                            "[SSH_EXEC] COMPLETED cmd=\"{0}\" in {1}ms (exit={2}, bytes={3}, lines={4})",
                            spec.command(), durationMs, channel.getExitStatus(), buffer.size(), lines);
                    return new ExecResult.Completed(outStr, channel.getExitStatus());
                }
                if (System.currentTimeMillis() > deadline) {
                    long durationMs = System.currentTimeMillis() - execStartMs;
                    LOG.log(System.Logger.Level.WARNING,
                            "[SSH_EXEC] TIMEOUT cmd=\"{0}\" HUNG for {1}ms! (timeout={2}ms, bytesReceived={3})",
                            spec.command(), durationMs, timeout.toMillis(), buffer.size());
                    return new ExecResult.TimedOut();
                }
                Thread.sleep(50);
            }
        } catch (JSchException | java.io.IOException | InterruptedException e) {
            long durationMs = System.currentTimeMillis() - execStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_EXEC] FAILED cmd=\"{0}\" after {1}ms: {2}",
                    spec.command(), durationMs, e.getMessage());
            Thread.currentThread().interrupt();
            return new ExecResult.ChannelFailed(String.valueOf(e.getMessage()));
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("sftp_get/scp_get");
    }

    /**
     * 14H BK-3: the archive is transferred by SFTP, streamed directly into
     * the caller's sink (the artefact store's own handle) -- no whole-file
     * buffering. {@code spec.remotePath()} is read verbatim, with no path
     * interpolation beyond the exact archive path the caller already
     * resolved (never a pattern, never a listing); {@code
     * spec.maxBytes()} bounds the transfer so a mis-sized or runaway
     * archive fails the run rather than exhausting memory or disk.
     */
    @Override
    public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout,
            OutputStream sink) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new FetchStreamResult.Failed("not an ssh_exec session");
        }
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) sshSession.jschSession().openChannel("sftp");
            channel.connect((int) timeout.toMillis());
            BoundedCountingOutputStream bounded = new BoundedCountingOutputStream(sink, spec.maxBytes());
            channel.get(spec.remotePath(), bounded);
            return new FetchStreamResult.Fetched(bounded.count());
        } catch (JSchException | SftpException e) {
            return new FetchStreamResult.Failed(String.valueOf(e.getMessage()));
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("xml_api_call");
    }

    @Override
    public void disconnect(TransportSession session) {
        if (session instanceof SshTransportSession sshSession) {
            LOG.log(System.Logger.Level.INFO, "[SSH_DISCONNECT] session disconnected: {0}", session.sessionId());
            sshSession.jschSession().disconnect();
        }
    }

    private HostKeyRepository trustedOnlyRepository(String trustRuleRef, ConnectionTarget target,
            String[] trustFailure, boolean[] trusted) {
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleResolver);
        return new HostKeyRepository() {
            @Override
            public int check(String host, byte[] key) {
                String presented = fingerprintOf(key);
                try {
                    String algorithm = new HostKey(host, key).getType();
                    var decision = verifier.verify(trustRuleRef, target.host(), target.port(), algorithm, presented);
                    trusted[0] = decision == HostKeyVerifier.Decision.MATCH;
                    trustFailure[0] = switch (decision) {
                        case MATCH -> null;
                        case MISSING -> "TRUST_ENTRY_MISSING";
                        case MISMATCH -> "host_key_mismatch: " + presented;
                    };
                    if (decision == HostKeyVerifier.Decision.MISMATCH) {
                        LOG.log(System.Logger.Level.WARNING,
                                "SSH host key mismatch for {0}:{1}: presented fingerprint {2}",
                                target.host(), target.port(), presented);
                    }
                    return trusted[0] ? OK : NOT_INCLUDED;
                } catch (JSchException e) {
                    trustFailure[0] = "TRUST_ENTRY_MISSING";
                    return NOT_INCLUDED;
                }
            }

            @Override
            public void add(HostKey hostkey, UserInfo userinfo) {
                // Never trust-on-first-use: adding an unverified key to the
                // repository is exactly the behavior AGENTS.md's "Check
                // Point" note forbids -- deliberately a no-op.
            }

            @Override
            public void remove(String host, String type) {
            }

            @Override
            public void remove(String host, String type, byte[] key) {
            }

            @Override
            public String getKnownHostsRepositoryID() {
                return "ui2-trust-rule";
            }

            @Override
            public HostKey[] getHostKey() {
                return new HostKey[0];
            }

            @Override
            public HostKey[] getHostKey(String host, String type) {
                return new HostKey[0];
            }
        };
    }

    /** Counts bytes written and refuses to write past {@code maxBytes} -- a runaway or mis-sized archive fails the fetch rather than exhausting memory or disk. */
    private static final class BoundedCountingOutputStream extends FilterOutputStream {

        private final long maxBytes;
        private long count;

        BoundedCountingOutputStream(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            if (count + 1 > maxBytes) {
                throw new IOException("fetch exceeded max_bytes=" + maxBytes);
            }
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (count + len > maxBytes) {
                throw new IOException("fetch exceeded max_bytes=" + maxBytes);
            }
            out.write(b, off, len);
            count += len;
        }
    }

    private static String fingerprintOf(byte[] key) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UserInfo silentUserInfo(SshCredentialMaterial credential) {
        return new UserInfo() {
            @Override
            public String getPassphrase() {
                return null;
            }

            @Override
            public String getPassword() {
                return credential.password() == null ? null : new String(credential.password());
            }

            @Override
            public boolean promptPassword(String message) {
                return true;
            }

            @Override
            public boolean promptPassphrase(String message) {
                return true;
            }

            @Override
            public boolean promptYesNo(String message) {
                // Never a TOFU/accept-any prompt path -- host-key trust is
                // decided exclusively by HostKeyRepository#check above.
                return false;
            }

            @Override
            public void showMessage(String message) {
            }
        };
    }
}
