package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.StreamedInteractiveResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * The session half of the Cisco ASA and FortiGate support bundle jobs: one trusted SSH session per run, short gated reads
 * on its interactive shell, and the streamed session captures ({@code show tech-support}, {@code execute tac report}) that
 * go line by line through the redactor into the vault. A command is sent once and never repeated.
 */
abstract class SshBundleExecutor extends VendorBundleExecutor {
    static final Duration CONNECT = Duration.ofSeconds(30);

    protected final DeviceTransport ssh;
    protected final SupportBundleTextRedactor redactor = new SupportBundleTextRedactor();
    protected TransportSession session;

    SshBundleExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            com.securityexpert.nexus.ui2.persistence.device.DeviceRepository devices, DeviceTransport ssh,
            GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts, HostnameFingerprint fingerprint,
            Clock clock) {
        super(leases, attempts, devices, gates, store, artefacts, fingerprint, clock);
        this.ssh = ssh;
    }

    @Override
    final Optional<String> open(Run run, String host, int port, String credentialRef, Optional<EndpointRecord> endpoint) {
        if (host == null) return Optional.of("NO_ADDRESS");
        var connected = ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(), host, port),
                new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                CONNECT);
        if (connected instanceof ConnectResult.Authenticated authenticated) {
            session = authenticated.session();
            return Optional.empty();
        }
        return Optional.of(connected instanceof ConnectResult.AuthenticationFailed ? "AUTHENTICATION_FAILED"
                : connected instanceof ConnectResult.HostKeyRejected ? "HOST_KEY_REJECTED" : "CONNECTION_FAILED");
    }

    @Override
    final void close(Run run) {
        if (session != null) {
            ssh.disconnect(session);
            session = null;
        }
    }

    /** What a short read answered. {@code EMPTY} is a prompt with no text (a context move); {@code REFUSED} a CLI error. */
    enum Answer { OK, EMPTY, REFUSED }

    record Read(Answer answer, String text) {
    }

    /**
     * One gated, ledgered read on the shell. A timeout or a lost channel closes the shell (the transport does) and ends the
     * run for the items after it ({@link ShellClosed}); a CLI refusal is an answer, not an error.
     */
    final Read shortRead(Run run, GateResolution.Known gate, String command, Duration timeout, int maxChars) {
        String attempt = beginStep(run, gate, "read");
        ExecResult result = ssh.execInteractive(session, new ExecSpec(command, true), timeout);
        if (result instanceof ExecResult.Completed completed) {
            String text = completed.output() == null ? "" : completed.output();
            boolean ok = text.length() <= maxChars;
            endStep(run, attempt, ok, "OUTPUT_LIMIT_EXCEEDED", text.length());
            if (!ok) throw new Unknown("OUTPUT_LIMIT_EXCEEDED");
            return new Read(Answer.OK, text);
        }
        if (result instanceof ExecResult.ChannelFailed failed && "empty output".equals(failed.reason())) {
            endStep(run, attempt, true, null, 0);
            return new Read(Answer.EMPTY, "");
        }
        if (result instanceof ExecResult.ChannelFailed failed && "cli error".equals(failed.reason())) {
            endStep(run, attempt, false, "CLI_REFUSED", 0);
            return new Read(Answer.REFUSED, "");
        }
        endStep(run, attempt, false, result instanceof ExecResult.TimedOut ? "TIMEOUT" : "CHANNEL_FAILED", 0);
        sessionEnded = true;
        throw new ShellClosed(result instanceof ExecResult.TimedOut ? "READ_TIMED_OUT" : "READ_CHANNEL_FAILED");
    }

    /**
     * One streamed capture into the vault, redacted line by line. Recorded outcomes: COMPLETED (stored), a capture cut short
     * after at least {@code partialMinBytes} (PARTIAL, stored), CLI refusal (UNSUPPORTED), TIMED_OUT, TOO_LARGE,
     * REDACTION_FAILED and FAILED; nothing is stored for any of the last five. A capture that did not end with the prompt
     * leaves the shell in an unknown state, so the run ends after it.
     */
    final String capture(Run run, ItemRow item, GateResolution.Known gate, String command, String pagerMarker, long maxBytes,
            long partialMinBytes, String entryName) {
        String attempt = beginStep(run, gate, "read");
        StreamedInteractiveResult result;
        TextCapture capture;
        ArtefactStore.ArtefactMetadata meta = null;
        boolean store = false, partial = false;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, vendor(), false)) {
            BufferedOutputStream out = new BufferedOutputStream(handle.sink(), 64 * 1024);
            capture = new TextCapture(out, redactor.lines(), maxBytes);
            result = ssh.execInteractiveStreaming(session, new ExecSpec(command, true), timeoutOf(gate),
                    pagerMarker, capture);
            partial = result.kind() == StreamedInteractiveResult.Kind.SESSION_LOST && capture.written() >= partialMinBytes;
            store = result.kind() == StreamedInteractiveResult.Kind.COMPLETED || partial;
            if (store) {
                out.flush();
                meta = handle.finish();
            }
        } catch (ArtefactStore.QuotaExceededException quota) {
            endStep(run, attempt, false, "ARTEFACT_QUOTA", 0);
            sessionEnded = true;
            throw new Unknown("ARTEFACT_QUOTA");
        } catch (IOException e) {
            endStep(run, attempt, false, "ARTEFACT_WRITE_FAILED", 0);
            sessionEnded = true;
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
        endStep(run, attempt, result.kind() == StreamedInteractiveResult.Kind.COMPLETED, result.kind().name(), result.bytes());
        List<String> gateIds = List.copyOf(run.gateIds);
        switch (result.kind()) {
            case COMPLETED -> {
                this.store.completeItem(item.id(), manifest(run, meta), "COMPLETED", "COMPLETED", entryName,
                        SupportBundleTextRedactor.RULESET, gateIds);
                return "STORED";
            }
            case CLI_ERROR -> {
                this.store.finishItem(item.id(), "UNSUPPORTED", "CLI_REFUSED", null, gateIds);
                return "UNSUPPORTED";
            }
            case TIMED_OUT -> {
                sessionEnded = true;
                this.store.finishItem(item.id(), "TIMED_OUT", "TIMED_OUT", "no prompt after " + timeoutOf(gate).toSeconds() + " s",
                        gateIds);
                return "TIMED_OUT";
            }
            case SESSION_LOST -> {
                sessionEnded = true;
                if (partial) {
                    this.store.completeItem(item.id(), manifest(run, meta), "PARTIAL", "SESSION_LOST", entryName,
                            SupportBundleTextRedactor.RULESET, gateIds);
                    return "STORED_PARTIAL";
                }
                this.store.finishItem(item.id(), "FAILED", "SESSION_LOST", null, gateIds);
                return "FAILED";
            }
            case SINK_ABORTED -> {
                sessionEnded = true;
                if (capture.tooLarge) {
                    this.store.finishItem(item.id(), "TOO_LARGE", "TOO_LARGE", "over " + maxBytes + " bytes", gateIds);
                    return "TOO_LARGE";
                }
                if (capture.redactionFailed) {
                    this.store.finishItem(item.id(), "REDACTION_FAILED", "REDACTION_FAILED", null, gateIds);
                    return "REDACTION_FAILED";
                }
                this.store.finishItem(item.id(), "FAILED", "CAPTURE_ABORTED", null, gateIds);
                return "FAILED";
            }
            default -> {
                sessionEnded = true;
                this.store.finishItem(item.id(), "FAILED", "COMMAND_NOT_SENT", null, gateIds);
                return "FAILED";
            }
        }
    }

    private static Duration timeoutOf(GateResolution.Known gate) {
        return Duration.ofSeconds(gate.timeoutS());
    }

    /** The largest percentage found as {@code NN%} after the word, or null when none. */
    static Integer maxPercent(String text, String linePattern) {
        Integer max = null;
        for (String line : text.split("\\R")) {
            if (!line.matches("(?is).*" + linePattern + ".*")) continue;
            var m = java.util.regex.Pattern.compile("([0-9]{1,3})\\s*%").matcher(line);
            while (m.find()) {
                int value = Integer.parseInt(m.group(1));
                if (value <= 100) max = max == null ? value : Math.max(max, value);
            }
        }
        return max;
    }
}
