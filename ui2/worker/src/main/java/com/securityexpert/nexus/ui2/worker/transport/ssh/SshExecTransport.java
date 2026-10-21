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
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.util.HexFormat;

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
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.jobs.transport.TransportNotImplementedException;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

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
    private final boolean derivedOnly;
    private final com.securityexpert.nexus.ui2.persistence.discovery.SshHostKeyPolicyStore hostKeyPolicy;

    public SshExecTransport(SshCredentialResolver credentialResolver, TrustRuleResolver trustRuleResolver) {
        this(credentialResolver, trustRuleResolver, false);
    }

    /** Failover commands retain raw output only in memory for immediate parsing. */
    public SshExecTransport(SshCredentialResolver credentialResolver, TrustRuleResolver trustRuleResolver,
            boolean derivedOnly) {
        this(credentialResolver, trustRuleResolver, derivedOnly, null);
    }

    public SshExecTransport(SshCredentialResolver credentialResolver, TrustRuleResolver trustRuleResolver,
            boolean derivedOnly, com.securityexpert.nexus.ui2.persistence.discovery.SshHostKeyPolicyStore hostKeyPolicy) {
        this.hostKeyPolicy = hostKeyPolicy;
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.trustRuleResolver = Objects.requireNonNull(trustRuleResolver, "trustRuleResolver");
        this.derivedOnly = derivedOnly;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (spec.trustRuleRef() == null || spec.trustRuleRef().isBlank()
                || trustRuleResolver.authorizedAlgorithms(spec.trustRuleRef(), target.host(), target.port()).filter(java.util.List::isEmpty).isPresent())
            return new ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        var admission = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.ssh(target.host(), target.port());
        try {
            synchronized (admission) {
                admission.check();
                ConnectResult result = connectRaw(target, spec, timeout, admission);
                if (result instanceof ConnectResult.Authenticated connected) {
                    var owned = (SshTransportSession) connected.session();
                    owned.admission = admission;
                    admission.onLoss(() -> disconnect(owned));
                    admission.check();
                } else admission.closed();
                JobTranscriptScope.add("ssh", "note", "connect outcome: " + result.getClass().getSimpleName());
                return result;
            }
        } catch (RuntimeException e) {
            admission.abort();
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            JobTranscriptScope.add("ssh", "note", "connect outcome: " + e.getClass().getSimpleName());
            throw e;
        }
    }

    private ConnectResult connectRaw(ConnectionTarget target, ConnectSpec spec, Duration timeout, com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.Lease admission) {
        if (spec.trustRuleRef() == null || spec.trustRuleRef().isBlank()) {
            LOG.log(System.Logger.Level.WARNING, "SSH connection aborted for {0}:{1}: trust rule ref is missing",
                    derivedOnly ? "<redacted>" : target.host(), target.port());
            return new ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        }
        var algorithms = trustRuleResolver.authorizedAlgorithms(spec.trustRuleRef(), target.host(), target.port());
        if (algorithms.isPresent() && algorithms.get().isEmpty()) {
            return new ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        }
        SshCredentialMaterial credential = credentialResolver.resolve(spec.credentialRef());
        if (!derivedOnly) JobTranscriptScope.add("ssh", "note", "connect ssh " + target.host() + ":" + target.port() + " as " + credential.username());
        // Per-connect state: a rejection is determined by the key hook, never exception substrings.
        String[] trustFailure = {null};
        boolean[] trusted = {false};
        long connectStartMs = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO,
                "[SSH_CONNECT] START target={0}:{1} user={2} timeout={3}ms",
                derivedOnly ? "<redacted>" : target.host(), target.port(),
                derivedOnly ? "<redacted>" : credential.username(), timeout.toMillis());

        // Fast-fail socket pre-check: verify port reachability within 10000ms before blocking in JSch handshake
        try (java.net.Socket preSocket = new java.net.Socket()) {
            int socketTimeout = (int) Math.min(timeout.toMillis(), 10000L);
            long preStart = System.currentTimeMillis();
            preSocket.connect(new java.net.InetSocketAddress(target.host(), target.port()), socketTimeout);
            long preElapsed = System.currentTimeMillis() - preStart;
            LOG.log(System.Logger.Level.INFO,
                    "[SSH_CONNECT] TCP pre-connect SUCCESS in {0}ms for {1}:{2}",
                    preElapsed, derivedOnly ? "<redacted>" : target.host(), target.port());
        } catch (java.io.IOException networkFailure) {
            long preElapsed = System.currentTimeMillis() - connectStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_CONNECT] TCP pre-connect FAILED after {0}ms for {1}:{2}: {3}",
                    preElapsed, derivedOnly ? "<redacted>" : target.host(), target.port(),
                    derivedOnly ? networkFailure.getClass().getSimpleName() : networkFailure.getMessage());
            return new ConnectResult.TimedOut(derivedOnly ? "TCP_CONNECT_FAILED"
                    : "tcp_connect_timeout: port " + target.port() + " unreachable (" + networkFailure.getMessage() + ")");
        }

        Session session = null;
        boolean authenticated = false;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(credential.username(), target.host(), target.port());
            Session ownedSession = session;
            admission.onLoss(ownedSession::disconnect);
            admission.check();
            session.setUserInfo(silentUserInfo(credential));
            if (credential.password() != null) {
                session.setPassword(new String(credential.password()));
            }
            // The ratified WARN exception is checked and audited in the key hook before authentication.
            session.setHostKeyRepository(trustedOnlyRepository(spec.trustRuleRef(), target, trustFailure, trusted));
            session.setConfig("StrictHostKeyChecking", "yes");
            long jschStart = System.currentTimeMillis();
            session.connect((int) timeout.toMillis());
            long totalConnectElapsed = System.currentTimeMillis() - connectStartMs;
            long jschElapsed = System.currentTimeMillis() - jschStart;
            LOG.log(System.Logger.Level.INFO,
                    "[SSH_CONNECT] AUTHENTICATED for {0}:{1} in {2}ms (jsch={3}ms)",
                    derivedOnly ? "<redacted>" : target.host(), target.port(), totalConnectElapsed, jschElapsed);

            SshTransportSession wrapped = new SshTransportSession(UUID.randomUUID().toString(), session);
            authenticated = true;
            return new ConnectResult.Authenticated(wrapped);
        } catch (JSchException e) {
            long totalConnectElapsed = System.currentTimeMillis() - connectStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_CONNECT] FAILED for {0}:{1} after {2}ms: {3}",
                    derivedOnly ? "<redacted>" : target.host(), target.port(), totalConnectElapsed,
                    derivedOnly ? e.getClass().getSimpleName() : e.getMessage());
            if (session != null) {
                session.disconnect();
            }
            if (trustFailure[0] != null) {
                String safeTrustFailure = derivedOnly && trustFailure[0].startsWith("host_key_mismatch:")
                        ? "HOST_KEY_MISMATCH" : trustFailure[0];
                LOG.log(System.Logger.Level.WARNING, "SSH connection aborted for {0}:{1}: {2}",
                        derivedOnly ? "<redacted>" : target.host(), target.port(), safeTrustFailure);
                return new ConnectResult.HostKeyRejected(safeTrustFailure);
            }
            if (algorithms.isPresent()) {
                return discoveryFailure(e, derivedOnly ? null : trustFailure[0], trusted[0]);
            }
            String message = String.valueOf(e.getMessage());
            String lowerMessage = message.toLowerCase(java.util.Locale.ROOT);
            if (lowerMessage.contains("hostkey") || lowerMessage.contains("reject")) {
                return new ConnectResult.HostKeyRejected(derivedOnly ? "HOST_KEY_REJECTED" : message);
            }
            if (isNetworkFailure(e) || lowerMessage.contains("timeout") || lowerMessage.contains("timed out")
                    || message.contains("Auth cancel")) {
                if (lowerMessage.contains("read timed out")) {
                    return new ConnectResult.TimedOut("ssh_banner_timeout: remote host did not respond to SSH banner exchange");
                }
                return new ConnectResult.TimedOut(derivedOnly ? "SSH_CONNECT_TIMEOUT" : "ssh_connect_timeout: " + message);
            }
            return new ConnectResult.AuthenticationFailed(derivedOnly ? "AUTH_FAILED" : message);
        } finally {
            if (session != null && !authenticated) {
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
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        long started = System.nanoTime();
        ExecResult result = execRaw(session, spec, timeout);
        if (!derivedOnly && spec.outputLimitBytes() == 0) {
            recordAnswer(result);
            recordStatus(result, started);
        }
        return result;
    }

    private ExecResult execRaw(TransportSession session, ExecSpec spec, Duration timeout) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new ExecResult.ChannelFailed("not an ssh_exec session");
        }
        boolean derivedOnly = this.derivedOnly || spec.outputLimitBytes() > 0;
        long execStartMs = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO,
                "[SSH_EXEC] START cmd=\"{0}\" timeout={1}ms",
                derivedOnly ? "<redacted>" : spec.command(), timeout.toMillis());
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) sshSession.jschSession().openChannel("exec");
            channel.setCommand(spec.command());
            channel.setPty(spec.pty());
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect((int) timeout.toMillis());
            if (!derivedOnly) JobTranscriptScope.add("ssh", "command", com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()));

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            long firstByteMs = -1;
            while (true) {
                while (in.available() > 0) {
                    if (spec.outputLimitBytes() > 0 && System.currentTimeMillis() > deadline) return new ExecResult.TimedOut();
                    int read = in.read(chunk, 0, chunk.length);
                    if (read < 0) {
                        break;
                    }
                    if (firstByteMs < 0) {
                        firstByteMs = System.currentTimeMillis() - execStartMs;
                        LOG.log(System.Logger.Level.DEBUG,
                                "[SSH_EXEC] FIRST_DATA cmd=\"{0}\" after {1}ms",
                                derivedOnly ? "<redacted>" : spec.command(), firstByteMs);
                    }
                    if (spec.outputLimitBytes() > 0 && read > spec.outputLimitBytes() - buffer.size() - errors.size())
                        return new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED");
                    buffer.write(chunk, 0, read);
                }
                while (err.available() > 0) {
                    if (spec.outputLimitBytes() > 0 && System.currentTimeMillis() > deadline) return new ExecResult.TimedOut();
                    int read = err.read(chunk, 0, chunk.length);
                    if (read < 0) {
                        break;
                    }
                    if (spec.outputLimitBytes() > 0 && read > spec.outputLimitBytes() - buffer.size() - errors.size())
                        return new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED");
                    if (errors.size() < 64 * 1024 * 1024)
                        errors.write(chunk, 0, Math.min(read, 64 * 1024 * 1024 - errors.size()));
                }
                if (channel.isClosed()) {
                    if (in.available() > 0) {
                        continue;
                    }
                    long durationMs = System.currentTimeMillis() - execStartMs;
                    String outStr = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                    if (!derivedOnly && errors.size() > 0) JobTranscriptScope.add("ssh", "answer", errors.toString(java.nio.charset.StandardCharsets.UTF_8));
                    int lines = outStr.split("\r\n|\r|\n").length;
                    LOG.log(System.Logger.Level.INFO,
                            "[SSH_EXEC] COMPLETED cmd=\"{0}\" in {1}ms (exit={2}, bytes={3}, lines={4})",
                            derivedOnly ? "<redacted>" : spec.command(), durationMs, channel.getExitStatus(), buffer.size(), lines);
                    return new ExecResult.Completed(outStr, channel.getExitStatus());
                }
                if (System.currentTimeMillis() > deadline) {
                    long durationMs = System.currentTimeMillis() - execStartMs;
                    LOG.log(System.Logger.Level.WARNING,
                            "[SSH_EXEC] TIMEOUT cmd=\"{0}\" HUNG for {1}ms! (timeout={2}ms, bytesReceived={3})",
                            derivedOnly ? "<redacted>" : spec.command(), durationMs, timeout.toMillis(), buffer.size());
                    if (!derivedOnly && buffer.size() > 0) JobTranscriptScope.add("ssh", "answer",
                            buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
                    return new ExecResult.TimedOut();
                }
                Thread.sleep(50);
            }
        } catch (JSchException | java.io.IOException | InterruptedException e) {
            long durationMs = System.currentTimeMillis() - execStartMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_EXEC] FAILED cmd=\"{0}\" after {1}ms: {2}",
                    derivedOnly ? "<redacted>" : spec.command(), durationMs,
                    derivedOnly ? e.getClass().getSimpleName() : e.getMessage());
            Thread.currentThread().interrupt();
            return new ExecResult.ChannelFailed(derivedOnly ? "CHANNEL_FAILED" : String.valueOf(e.getMessage()));
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * Gaia Embedded/Quantum Spark support (2026-09-21): runs {@code spec.command()}
     * on this session's one persistent interactive shell (opened lazily, reused
     * across calls -- {@link SshTransportSession#interactiveShell()}), instead of
     * a one-shot {@code exec} channel. Ported from the pre-Java product's own
     * real-fleet-proven {@code InteractiveSshSession} (Python); see {@link
     * InteractiveShellSession} for the read/prompt-matching mechanics.
     */
    @Override
    public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        long started = System.nanoTime();
        ExecResult result = execInteractiveRaw(session, spec, timeout);
        if (result instanceof ExecResult.Completed completed)
            JobTranscriptScope.add("ssh", "answer", com.securityexpert.nexus.ui2.worker.transcript.JobTranscript
                    .safeSshAnswer(spec.command(), completed.output()));
        else recordAnswer(result);
        recordStatus(result, started);
        return result;
    }

    private ExecResult execInteractiveRaw(TransportSession session, ExecSpec spec, Duration timeout) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new ExecResult.ChannelFailed("not an ssh_exec session");
        }
        long startMs = System.currentTimeMillis();
        try {
            InteractiveShellSession shell = sshSession.interactiveShell();
            InteractiveShellSession.Result result = shell.runForResult(spec.command(), (int) timeout.toMillis(), spec.streamingExtensionMs());
            String output = result.text();
            if (result.kind() != InteractiveShellSession.Result.Kind.OUTPUT && output != null && !output.isEmpty())
                JobTranscriptScope.add("ssh", "answer", com.securityexpert.nexus.ui2.worker.transcript.JobTranscript
                        .safeSshAnswer(spec.command(), output));
            long elapsedMs = System.currentTimeMillis() - startMs;
            switch (result.kind()) {
                case TIMED_OUT -> {
                    sshSession.closeInteractiveShell();
                    LOG.log(System.Logger.Level.WARNING, "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" TIMED OUT after {1}ms",
                            com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()), elapsedMs);
                    return new ExecResult.TimedOut(result.streaming());
                }
                case EMPTY -> {
                    // prompt came back with no text: a completed setting, not a timeout
                    LOG.log(System.Logger.Level.INFO, "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" completed with no output in {1}ms",
                            com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()), elapsedMs);
                    return new ExecResult.ChannelFailed("empty output");
                }
                case CLI_ERROR -> {
                    LOG.log(System.Logger.Level.WARNING, "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" answered a CLI error after {1}ms",
                            com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()), elapsedMs);
                    return new ExecResult.ChannelFailed("cli error");
                }
                case NOT_SENT -> {
                    return new ExecResult.ChannelFailed("command not sent");
                }
                case OUTPUT -> {
                    // falls through to Completed below
                }
            }
            LOG.log(System.Logger.Level.INFO,
                    "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" completed in {1}ms (length={2})",
                    com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()), elapsedMs, output.length());
            return new ExecResult.Completed(output, 0);
        } catch (JSchException | IOException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" failed after {1}ms: {2}",
                    com.securityexpert.nexus.ui2.worker.transcript.JobTranscript.safeSshCommand(spec.command()), elapsedMs,
                    e.getClass().getSimpleName());
            sshSession.closeInteractiveShell();
            return new ExecResult.ChannelFailed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * One raw PTY shell channel on the trusted session (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md):
     * {@code vt100}, 4096 columns x 10000 rows as {@link InteractiveShellSession}. No banner drain, no prompt learning:
     * the caller frames every command. Admission and cancellation are checked here and before every line sent. Nothing
     * from the device is logged; failures carry only the exception class.
     */
    @Override
    public com.securityexpert.nexus.ui2.jobs.transport.ShellChannel openShell(TransportSession session, Duration timeout) {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!(session instanceof SshTransportSession sshSession)) {
            throw new IllegalArgumentException("not an ssh_exec session");
        }
        com.jcraft.jsch.ChannelShell channel = null;
        try {
            channel = (com.jcraft.jsch.ChannelShell) sshSession.jschSession().openChannel("shell");
            channel.setPty(true);
            channel.setPtyType("vt100");
            channel.setPtySize(4096, 10000, 0, 0);
            InputStream in = channel.getInputStream();
            java.io.OutputStream out = channel.getOutputStream();
            channel.connect((int) Math.max(1000, timeout.toMillis()));
            LOG.log(System.Logger.Level.INFO, "[SSH_SHELL] opened session={0}", session.sessionId());
            return new SshShellChannel(session, channel, in, out);
        } catch (JSchException | IOException e) {
            if (channel != null) channel.disconnect();
            LOG.log(System.Logger.Level.WARNING, "[SSH_SHELL] open failed: {0}", e.getClass().getSimpleName());
            throw new IllegalStateException("SHELL_OPEN_FAILED");
        }
    }

    private static final class SshShellChannel implements com.securityexpert.nexus.ui2.jobs.transport.ShellChannel {
        private final TransportSession session;
        private final com.jcraft.jsch.ChannelShell channel;
        private final InputStream in;
        private final java.io.OutputStream out;

        SshShellChannel(TransportSession session, com.jcraft.jsch.ChannelShell channel, InputStream in,
                java.io.OutputStream out) {
            this.session = session; this.channel = channel; this.in = in; this.out = out;
        }

        @Override
        public void sendLine(String line) throws IOException {
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
                throw new IllegalArgumentException("SHELL_LINE_INVALID");
            checkAdmission(session);
            com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
            if (!channel.isConnected()) throw new IOException("SHELL_CLOSED");
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void interrupt() throws IOException {
            if (!channel.isConnected()) return;
            out.write(3);
            out.flush();
        }

        @Override
        public int read(byte[] buffer, Duration wait) throws IOException {
            long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
            while (true) {
                if (in.available() > 0) return in.read(buffer, 0, Math.min(buffer.length, in.available()));
                if (channel.isClosed() || !channel.isConnected()) return -1;
                if (System.nanoTime() >= deadline) return 0;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("SHELL_READ_INTERRUPTED");
                }
            }
        }

        @Override
        public void close() {
            channel.disconnect();
        }
    }

    @Override
    public boolean resyncPrompt(TransportSession session, Duration quiet) {
        checkAdmission(session);
        if (!(session instanceof SshTransportSession sshSession)) {
            return false;
        }
        try {
            return sshSession.interactiveShell().relearnPrompt((int) Math.max(100, quiet.toMillis()));
        } catch (JSchException | IOException e) {
            return false;
        }
    }

    /** The account name of "execute ha manage <index> <user>" is not logged (AGENTS.md: no principals in logs). */
    private static String loggable(String command) {
        return command == null ? null : command.replaceAll("^(execute ha manage \\d+)\\s+\\S+.*$", "$1 <user>");
    }

    /** {@link #execInteractive}, answering the command's questions on the way (replies never logged). */
    @Override
    public ExecResult execInteractiveAnswering(TransportSession session, ExecSpec spec,
            java.util.List<com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer> answers, Duration timeout) {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        long started = System.nanoTime();
        ExecResult result = execInteractiveAnsweringRaw(session, spec, answers, timeout);
        recordAnswer(result, answers);
        recordStatus(result, started);
        return result;
    }

    static void recordAnswer(ExecResult result,
            java.util.List<com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer> answers) {
        if (result instanceof ExecResult.Completed completed) {
            String safe = completed.output();
            for (com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer answer : answers) {
                String sent = new String(answer.reply());
                if (!sent.isEmpty()) safe = safe.replace(sent, "[credential]");
            }
            JobTranscriptScope.add("ssh", "answer", safe);
        } else recordAnswer(result);
    }

    private ExecResult execInteractiveAnsweringRaw(TransportSession session, ExecSpec spec,
            java.util.List<com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer> answers, Duration timeout) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new ExecResult.ChannelFailed("not an ssh_exec session");
        }
        long startMs = System.currentTimeMillis();
        try {
            InteractiveShellSession.Result result = sshSession.interactiveShell().runAnswering(spec.command(), answers, (int) timeout.toMillis());
            long elapsedMs = System.currentTimeMillis() - startMs;
            LOG.log(System.Logger.Level.INFO, "[SSH_EXEC_INTERACTIVE] cmd=\"{0}\" answered, ended {1} in {2}ms",
                    loggable(spec.command()), result.kind(), elapsedMs);
            return switch (result.kind()) {
                case TIMED_OUT -> new ExecResult.TimedOut();
                case OUTPUT -> new ExecResult.Completed(result.text(), 0);
                case EMPTY -> new ExecResult.ChannelFailed("empty output");
                case CLI_ERROR -> new ExecResult.Completed(result.text(), 1);
                case NOT_SENT -> new ExecResult.ChannelFailed("command not sent");
            };
        } catch (JSchException | IOException e) {
            sshSession.closeInteractiveShell();
            return new ExecResult.ChannelFailed(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        checkAdmission(session);
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
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (JobTranscriptScope.current() == null) return fetchStreamingRaw(session, spec, timeout, sink);
        long started = System.nanoTime();
        MessageDigest digest = sha256();
        BoundedCountingOutputStream captured = new BoundedCountingOutputStream(new DigestOutputStream(sink, digest), Long.MAX_VALUE);
        JobTranscriptScope.add("sftp", "transfer", "fetch requested file=" + spec.remotePath());
        try {
            FetchStreamResult result = fetchStreamingRaw(session, spec, timeout, captured);
            String status = result instanceof FetchStreamResult.Fetched ? "completed"
                    : "failed: " + ((FetchStreamResult.Failed) result).reason();
            JobTranscriptScope.add("sftp", "transfer", "file=" + spec.remotePath() + " size=" + captured.count()
                    + " sha256=" + HexFormat.of().formatHex(digest.digest()) + " status=" + status
                    + " durationMs=" + (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            JobTranscriptScope.add("sftp", "transfer", "failed file=" + spec.remotePath() + " size=" + captured.count()
                    + " reason=" + e.getClass().getSimpleName());
            throw e;
        }
    }

    private FetchStreamResult fetchStreamingRaw(TransportSession session, FetchSpec spec, Duration timeout,
            OutputStream sink) {
        if (!(session instanceof SshTransportSession sshSession)) {
            return new FetchStreamResult.Failed("not an ssh_exec session");
        }
        ChannelSftp channel = null;
        BoundedCountingOutputStream bounded = null;
        try {
            channel = (ChannelSftp) sshSession.jschSession().openChannel("sftp");
            channel.connect((int) timeout.toMillis());
            JobTranscriptScope.add("ssh", "command", "sftp fetch " + spec.remotePath());
            bounded = new BoundedCountingOutputStream(sink, spec.maxBytes());
            channel.get(spec.remotePath(), bounded);
            return new FetchStreamResult.Fetched(bounded.count());
        } catch (JSchException | SftpException e) {
            // A closed session surfaces as an SftpException with an empty message (measured live
            // 2026-09-22, three runs "sftp fetch failed: "): the class and, for SFTP, the status id
            // are the reason then.
            String detail = e.getMessage() == null || e.getMessage().isBlank() ? "no message" : e.getMessage();
            String id = e instanceof SftpException sftp ? " id=" + sftp.id : "";
            // Diagnostics (2026-09-24, an MDS Gaia backup failed "id=4: no message"): how far the transfer got, the
            // file's own size and mode on the device, and whether the product's size bound was reached -- never the path.
            long received = bounded == null ? -1 : bounded.count();
            String remote = describeRemote(sshSession, spec.remotePath(), timeout);
            boolean boundReached = received >= 0 && received >= spec.maxBytes();
            LOG.log(System.Logger.Level.WARNING, "[SFTP_FETCH_FAILED] {0}{1}: {2} received={3} bound={4} bound_reached={5} remote={6} cause={7}",
                    e.getClass().getSimpleName(), id, detail, received, spec.maxBytes(), boundReached, remote,
                    e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            if (boundReached) {
                return new FetchStreamResult.Failed("archive larger than the product's fetch bound of " + spec.maxBytes() + " bytes");
            }
            return new FetchStreamResult.Failed(e.getClass().getSimpleName() + id + ": " + detail + " (received " + received
                    + " bytes; remote " + remote + ")");
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /** Read buffer of the ranged read: the range is streamed through this, never held whole (phase B §4.3). */
    static final int RANGE_BUFFER_BYTES = 32 * 1024;

    private static final java.util.regex.Pattern RANGE_PATH = java.util.regex.Pattern.compile("/[A-Za-z0-9._/-]{1,510}");

    private static final java.util.concurrent.ScheduledExecutorService RANGE_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "sftp-range-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * C4 §2.3 amendment A-2 / phase B contract §4.3: an SFTP ranged read on the run's existing session. One SFTP
     * subsystem channel is opened on that session (no new connection, no exec channel, no shell), the file is opened
     * read-only, at most {@code spec.maxBytes()} bytes are read from {@code spec.startOffset()} and the file and
     * channel are closed. Nothing is written, renamed, removed or setstat'ed on the device. Bytes go straight to
     * {@code sink} through a {@value #RANGE_BUFFER_BYTES}-byte buffer; bytes the server sends past the requested
     * length (request-ahead) are dropped by the library's close and never reach the sink or the count. The timeout
     * bounds the whole call: on expiry the channel is closed and the result is {@code TIMEOUT}. No path, server
     * message or file content reaches the log or the result; the transcript records the file's base name only.
     */
    @Override
    public RangedFetchResult fetchRange(TransportSession session, RangedFetchSpec spec, Duration timeout,
            OutputStream sink) {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        long started = System.nanoTime();
        RangedFetchResult result = fetchRangeRaw(session, spec, timeout, sink);
        if (JobTranscriptScope.current() != null) {
            JobTranscriptScope.add("sftp", "transfer", "range read file=" + baseName(spec.remotePath())
                    + " start=" + result.startOffset() + " requested=" + result.bytesRequested()
                    + " received=" + result.bytesReceived()
                    + " sizeAtOpen=" + (result.sizeAtOpen().isPresent() ? result.sizeAtOpen().getAsLong() : "-")
                    + " eof=" + result.eofReached() + " status=" + result.status()
                    + (result.reasonCode().isEmpty() ? "" : " reason=" + result.reasonCode())
                    + " durationMs=" + (System.nanoTime() - started) / 1_000_000);
        }
        if (!result.bytesUsable()) {
            LOG.log(System.Logger.Level.WARNING, "[SFTP_RANGE_READ_FAILED] status={0} reason={1} start={2} requested={3} received={4}",
                    result.status(), result.reasonCode(), result.startOffset(), result.bytesRequested(),
                    result.bytesReceived());
        }
        return result;
    }

    private static final java.util.regex.Pattern NAME_CLASS = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /**
     * Phase B contract §10.1 C8: a bounded whole-file fetch. It is {@link #fetchRange}'s read-only SFTP open/read/close
     * from the requested start (0 for a whole file) on the run's session, with the caller's timeout on the whole
     * transfer and at most {@code spec.maxBytes()} bytes. The transcript and the log name only {@code nameClass} (a
     * fixed source key); the remote path and file name never appear (an HCP archive name carries the host name).
     * {@link #fetchStreaming} and its backup callers are unchanged.
     */
    @Override
    public RangedFetchResult fetchBounded(TransportSession session, RangedFetchSpec spec, Duration timeout,
            OutputStream sink, String nameClass) {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        String label = nameClass != null && NAME_CLASS.matcher(nameClass).matches() ? nameClass : "-";
        long started = System.nanoTime();
        RangedFetchResult result = fetchRangeRaw(session, spec, timeout, sink);
        if (JobTranscriptScope.current() != null) {
            JobTranscriptScope.add("sftp", "transfer", "bounded fetch class=" + label
                    + " requested=" + result.bytesRequested() + " received=" + result.bytesReceived()
                    + " eof=" + result.eofReached() + " status=" + result.status()
                    + (result.reasonCode().isEmpty() ? "" : " reason=" + result.reasonCode())
                    + " durationMs=" + (System.nanoTime() - started) / 1_000_000);
        }
        if (!result.bytesUsable()) {
            LOG.log(System.Logger.Level.WARNING, "[SFTP_BOUNDED_FETCH_FAILED] class={0} status={1} reason={2} received={3}",
                    label, result.status(), result.reasonCode(), result.bytesReceived());
        }
        return result;
    }

    private RangedFetchResult fetchRangeRaw(TransportSession session, RangedFetchSpec spec, Duration timeout,
            OutputStream sink) {
        long start = spec.startOffset();
        long requested = spec.maxBytes();
        if (!(session instanceof SshTransportSession sshSession)) {
            return RangedFetchResult.refused(start, "NOT_AN_SSH_SESSION");
        }
        if (!isRangePath(spec.remotePath())) {
            // Second line behind CpSyslogRangedRead: an absolute, glob-free, traversal-free path only, so the
            // library never lists a directory to expand a pattern.
            return RangedFetchResult.refused(start, "PATH_UNSAFE");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return RangedFetchResult.refused(start, "TIMEOUT_INVALID");
        }
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        ChannelSftp channel;
        try {
            channel = (ChannelSftp) sshSession.jschSession().openChannel("sftp");
            channel.connect((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis())));
        } catch (JSchException | RuntimeException e) {
            return rangeResult(RangedFetchStatus.SUBSYSTEM_UNAVAILABLE, start, requested, 0,
                    java.util.OptionalLong.empty(), false, e.getClass().getSimpleName());
        }
        final ChannelSftp openChannel = channel;
        java.util.concurrent.ScheduledFuture<?> watchdog = RANGE_WATCHDOG.schedule(() -> {
            timedOut.set(true);
            openChannel.disconnect();
        }, timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        SizeAtOpenMonitor monitor = new SizeAtOpenMonitor();
        long received = 0;
        boolean eof = false;
        try {
            try (InputStream in = channel.get(spec.remotePath(), monitor, start)) {
                byte[] buffer = new byte[(int) Math.min(RANGE_BUFFER_BYTES, requested)];
                while (received < requested) {
                    int want = (int) Math.min(buffer.length, requested - received);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) {
                        eof = true;
                        break;
                    }
                    try {
                        sink.write(buffer, 0, n);
                    } catch (IOException | RuntimeException sinkFailure) {
                        return rangeResult(RangedFetchStatus.SINK_FAILED, start, requested, received,
                                monitor.size(), false, sinkFailure.getClass().getSimpleName());
                    }
                    received += n;
                }
            }
        } catch (SftpException e) {
            RangedFetchStatus status = timedOut.get() ? RangedFetchStatus.TIMEOUT : switch (e.id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE -> RangedFetchStatus.FILE_MISSING;
                case ChannelSftp.SSH_FX_PERMISSION_DENIED -> RangedFetchStatus.PERMISSION_DENIED;
                case ChannelSftp.SSH_FX_OP_UNSUPPORTED -> RangedFetchStatus.SUBSYSTEM_UNAVAILABLE;
                default -> RangedFetchStatus.TRANSPORT_FAILED;
            };
            // The server's status message is device text: only the numeric status id is kept.
            return rangeResult(status, start, requested, received, monitor.size(), eof, "SFTP_STATUS_" + e.id);
        } catch (IOException | RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            return rangeResult(timedOut.get() ? RangedFetchStatus.TIMEOUT : RangedFetchStatus.TRANSPORT_FAILED,
                    start, requested, received, monitor.size(), eof, e.getClass().getSimpleName());
        } finally {
            watchdog.cancel(false);
            channel.disconnect();
        }
        if (timedOut.get()) {
            return rangeResult(RangedFetchStatus.TIMEOUT, start, requested, received, monitor.size(), eof, "DEADLINE");
        }
        java.util.OptionalLong size = monitor.size();
        if (size.isPresent() && size.getAsLong() < start) {
            return rangeResult(RangedFetchStatus.SIZE_BELOW_OFFSET, start, requested, received, size, eof, "");
        }
        if (!eof) {
            return rangeResult(RangedFetchStatus.CAP_REACHED, start, requested, received, size, false, "");
        }
        if (size.isPresent() && received < Math.min(requested, size.getAsLong() - start)) {
            return rangeResult(RangedFetchStatus.SHORT_READ, start, requested, received, size, true, "");
        }
        return rangeResult(RangedFetchStatus.END_OF_FILE, start, requested, received, size, true, "");
    }

    private static RangedFetchResult rangeResult(RangedFetchStatus status, long start, long requested, long received,
            java.util.OptionalLong size, boolean eof, String reason) {
        return new RangedFetchResult(status, start, requested, received, size, eof, reason);
    }

    private static boolean isRangePath(String path) {
        if (path == null || !RANGE_PATH.matcher(path).matches() || path.endsWith("/") || path.contains("//")) {
            return false;
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String baseName(String path) {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        return name.matches("[A-Za-z0-9._-]{1,64}") ? name : "-";
    }

    /**
     * Captures the size JSch reports for the path: {@code ChannelSftp.get(String, SftpProgressMonitor, long)} stats
     * the path and calls {@link #init} with its size before it sends {@code SSH_FXP_OPEN}. JSch 0.1.55 exposes no
     * handle-level {@code fstat}, so this is the closest observation to the open the library offers.
     */
    private static final class SizeAtOpenMonitor implements com.jcraft.jsch.SftpProgressMonitor {
        private volatile long size = -1;

        @Override
        public void init(int op, String src, String dest, long max) {
            size = max;
        }

        @Override
        public boolean count(long count) {
            return true;
        }

        @Override
        public void end() {
        }

        java.util.OptionalLong size() {
            long observed = size;
            return observed < 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(observed);
        }
    }

    /** Size and permission bits of the remote file, over a fresh SFTP channel -- for a failure's diagnosis only. */
    private static String describeRemote(SshTransportSession session, String path, Duration timeout) {
        ChannelSftp probe = null;
        try {
            probe = (ChannelSftp) session.jschSession().openChannel("sftp");
            probe.connect((int) Math.min(timeout.toMillis(), 30_000));
            com.jcraft.jsch.SftpATTRS a = probe.lstat(path);
            return "size=" + a.getSize() + " mode=" + a.getPermissionsString() + " uid=" + a.getUId();
        } catch (JSchException | SftpException e) {
            return "stat failed (" + e.getClass().getSimpleName()
                    + (e instanceof SftpException x ? " id=" + x.id : "") + ")";
        } finally {
            if (probe != null) {
                probe.disconnect();
            }
        }
    }

    /** Where an SCP source's bytes go: called once with the announced size, before any byte. */
    public interface ScpSink {
        OutputStream open(long size) throws IOException;
    }

    private static final java.util.regex.Pattern SCP_PATH = java.util.regex.Pattern.compile("[A-Za-z0-9:/._-]{1,200}");

    /**
     * Pulls one file with the SCP protocol ({@code scp -f <path>} on an exec channel) -- for devices whose only file
     * service is an SCP server, such as a Cisco ASA with {@code ssh scopy enable} (docs/design/CISCO_ASA_CONTRACT.md).
     * The path is checked against a closed character set; nothing is ever sent to the device. Returns the byte count.
     */
    public long scpFetch(TransportSession session, String remotePath, ScpSink sink, long maxBytes, Duration timeout)
            throws IOException {
        checkAdmission(session);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        long started = System.nanoTime();
        JobTranscriptScope.add("scp", "transfer", "fetch requested file=" + remotePath);
        MessageDigest digest = JobTranscriptScope.current() == null ? null : sha256();
        BoundedCountingOutputStream[] received = {null};
        ScpSink captured = digest == null ? sink : size -> {
            received[0] = new BoundedCountingOutputStream(new DigestOutputStream(sink.open(size), digest), Long.MAX_VALUE);
            return received[0];
        };
        try {
            long bytes = scpFetchRaw(session, remotePath, captured, maxBytes, timeout);
            if (digest != null) JobTranscriptScope.add("scp", "transfer", "file=" + remotePath + " size=" + bytes
                    + " sha256=" + HexFormat.of().formatHex(digest.digest()) + " status=completed durationMs="
                    + (System.nanoTime() - started) / 1_000_000);
            return bytes;
        } catch (IOException e) {
            JobTranscriptScope.add("scp", "transfer", "failed file=" + remotePath + " size=" + (received[0] == null ? 0 : received[0].count())
                    + " sha256=" + (digest == null ? "" : HexFormat.of().formatHex(digest.digest())) + " reason=" + e.getMessage()
                    + " durationMs=" + (System.nanoTime() - started) / 1_000_000);
            throw e;
        }
    }

    private long scpFetchRaw(TransportSession session, String remotePath, ScpSink sink, long maxBytes, Duration timeout)
            throws IOException {
        if (!(session instanceof SshTransportSession sshSession)) {
            throw new IOException("not an ssh session");
        }
        if (!SCP_PATH.matcher(remotePath).matches()) {
            throw new IOException("scp path outside the allowed character set");
        }
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) sshSession.jschSession().openChannel("exec");
            channel.setCommand("scp -f " + remotePath);
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();
            channel.connect((int) Math.min(timeout.toMillis(), 30_000));
            JobTranscriptScope.add("ssh", "command", "scp -f " + remotePath);
            out.write(0);
            out.flush();
            int c = in.read();
            if (c == 1 || c == 2) {
                throw new IOException("scp refused: " + readLine(in));
            }
            if (c != 'C') {
                throw new IOException("scp: unexpected reply " + c);
            }
            String header = readLine(in); // "0644 <size> <name>"
            String[] parts = header.trim().split(" ", 3);
            if (parts.length < 2) {
                throw new IOException("scp: unparseable header");
            }
            long size = Long.parseLong(parts[1]);
            if (size < 0 || size > maxBytes) {
                throw new IOException("scp: file of " + size + " bytes exceeds the limit " + maxBytes);
            }
            out.write(0);
            out.flush();
            long copied = 0;
            byte[] buf = new byte[64 * 1024];
            try (OutputStream target = sink.open(size)) {
                while (copied < size) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("scp: timed out after " + copied + " of " + size + " bytes");
                    }
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - copied));
                    if (n < 0) {
                        throw new IOException("scp: stream ended after " + copied + " of " + size + " bytes");
                    }
                    target.write(buf, 0, n);
                    copied += n;
                }
            }
            int status = in.read();
            if (status != 0) {
                throw new IOException("scp: transfer status " + status);
            }
            out.write(0);
            out.flush();
            return copied;
        } catch (JSchException e) {
            throw new IOException("scp channel: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n' && sb.length() < 1024) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    static void recordStatus(ExecResult result, long started) {
        String status = result instanceof ExecResult.Completed completed ? "exit=" + completed.exitStatus()
                : result instanceof ExecResult.ChannelFailed failed ? "channel failed: " + failed.reason() : "timed out";
        JobTranscriptScope.add("ssh", "note", status + " durationMs=" + (System.nanoTime() - started) / 1_000_000);
    }

    static void recordAnswer(ExecResult result) {
        if (result instanceof ExecResult.Completed completed)
            JobTranscriptScope.add("ssh", "answer", completed.output());
        else JobTranscriptScope.add("ssh", "note", result instanceof ExecResult.ChannelFailed failed
                ? "channel failed: " + failed.reason() : "timed out");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("xml_api_call");
    }

    private static void checkAdmission(TransportSession session) {
        com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.checkCommand();
        if (session instanceof SshTransportSession ssh && ssh.admission != null) ssh.admission.check();
    }

    @Override
    public void disconnect(TransportSession session) {
        if (session instanceof SshTransportSession sshSession) {
            LOG.log(System.Logger.Level.INFO, "[SSH_DISCONNECT] session disconnected: {0}", session.sessionId());
            try {
                sshSession.closeInteractiveShell();
            } finally {
                // Shell cleanup failure must never leave the underlying SSH session alive.
                sshSession.jschSession().disconnect();
                if (sshSession.jschSession().isConnected())
                    throw new IllegalStateException("SSH_SESSION_STILL_CONNECTED");
                if (sshSession.admission != null) sshSession.admission.closed();
            }
        }
    }

    private boolean mismatchAllowed(ConnectionTarget target, String ref, String algorithm, String presented) {
        var owner = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.owner();
        if (hostKeyPolicy == null || owner == null) return false;
        try {
            String expected = trustRuleResolver.resolveExpectedFingerprint(ref, target.host(), target.port(), algorithm).orElseThrow();
            return hostKeyPolicy.observe(owner.jobId(), owner.jobEpoch(), "FAILOVER_EXECUTION".equals(owner.purpose()),
                    target.endpointId(), target.host(), target.port(), algorithm, presented, expected);
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "SSH_HOST_KEY_OBSERVATION_UNAVAILABLE");
            return false;
        }
    }

    /** True when a fingerprint was already pinned for this endpoint before the check (so a match is a comparison, not a first use). */
    private boolean pinnedBefore(String ref, ConnectionTarget target, String algorithm) {
        try {
            return trustRuleResolver.resolveExpectedFingerprint(ref, target.host(), target.port(), algorithm)
                    .filter(fingerprint -> !fingerprint.isBlank()).isPresent();
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    /** The "Identity verified" badge follows this (PO decision 2026-10-18); it records the outcome and never alters it. */
    private void recordPinnedComparison(ConnectionTarget target, boolean pinnedBefore) {
        var owner = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.owner();
        if (hostKeyPolicy == null || owner == null) return;
        hostKeyPolicy.recordPinnedComparison(owner.jobId(), owner.jobEpoch(), target.endpointId(), !pinnedBefore);
    }

    HostKeyRepository trustedOnlyRepository(String trustRuleRef, ConnectionTarget target,
            String[] trustFailure, boolean[] trusted) {
        HostKeyVerifier verifier = new HostKeyVerifier(trustRuleResolver);
        return new HostKeyRepository() {
            @Override
            public int check(String host, byte[] key) {
                String presented = fingerprintOf(key);
                try {
                    String algorithm = new HostKey(host, key).getType();
                    boolean pinnedBefore = pinnedBefore(trustRuleRef, target, algorithm);
                    var decision = verifier.verify(trustRuleRef, target.host(), target.port(), algorithm, presented);
                    trusted[0] = decision == HostKeyVerifier.Decision.MATCH;
                    if (trusted[0]) recordPinnedComparison(target, pinnedBefore);
                    trustFailure[0] = switch (decision) {
                        case MATCH -> null;
                        case MISSING -> "TRUST_ENTRY_MISSING";
                        case MISMATCH -> "host_key_mismatch: " + presented;
                    };
                    if (decision == HostKeyVerifier.Decision.MISMATCH) {
                        LOG.log(System.Logger.Level.WARNING,
                                "SSH host key mismatch for {0}:{1}: presented fingerprint {2}",
                                derivedOnly ? "<redacted>" : target.host(), target.port(),
                                derivedOnly ? "<redacted>" : presented);
                    }
                    if (decision == HostKeyVerifier.Decision.MISMATCH && mismatchAllowed(target, trustRuleRef, algorithm, presented)) {
                        trustFailure[0] = null;
                        JobTranscriptScope.add("ssh", "note", "host_key_mismatch_warned: collected under an unverified identity");
                        return OK;
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
