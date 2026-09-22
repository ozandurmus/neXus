package com.securityexpert.nexus.ui2.worker.backup.pan;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;

/**
 * The set-format running configuration of a PAN-OS firewall over its CLI
 * (backlog {@code pan_backup_include_set_format_config}, Product Owner P0,
 * 2026-09-22). Backbox's measured sequence (trail 34411050) is
 * {@code set cli scripting-mode on}, {@code set cli pager off},
 * {@code set cli config-output-format set}, then {@code configure} +
 * {@code show} -- the <em>candidate</em> configuration in configure mode.
 * This reader deliberately takes {@code show config running} in operational
 * mode instead: the running configuration is what the device enforces
 * (a backup, not a work in progress), and staying in operational mode keeps
 * one prompt for the whole session. Four reads, gated as
 * {@code pan_backup_ssh_*} (V43).
 */
public final class PanSetConfigReader {

    public static final List<String> COMMANDS = List.of(
            "set cli scripting-mode on",
            "set cli pager off",
            "set cli config-output-format set",
            "show config running");

    public sealed interface Outcome {
        record Read(String setFormatText) implements Outcome {
        }

        record Unavailable(String reason) implements Outcome {
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SHOW_TIMEOUT = Duration.ofSeconds(180);
    /** A real firewall's set-format configuration runs to hundreds of lines; fewer than this is a refusal or an error page. */
    private static final int MIN_SET_LINES = 5;

    private final DeviceTransport transport;

    public PanSetConfigReader(DeviceTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Outcome read(ConnectionTarget sshTarget, String credentialRef, String trustRuleRef) {
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(sshTarget, new ConnectSpec(credentialRef, trustRuleRef, Optional.empty()),
                    CONNECT_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            return new Outcome.Unavailable("ssh credential: " + credentialUnresolvable.getMessage());
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new Outcome.Unavailable("ssh connect: " + connectResult.getClass().getSimpleName());
        }
        TransportSession session = authenticated.session();
        try {
            for (int i = 0; i < COMMANDS.size() - 1; i++) {
                ExecResult setup = transport.execInteractive(session, new ExecSpec(COMMANDS.get(i)), SETUP_TIMEOUT);
                if (!(setup instanceof ExecResult.Completed)) {
                    // A CLI setting answers with an empty line and the prompt; the interactive shell reports
                    // that as ChannelFailed("empty output") rather than Completed -- only a timeout means trouble.
                    if (setup instanceof ExecResult.TimedOut) {
                        return new Outcome.Unavailable("cli setup timed out at step " + (i + 1));
                    }
                }
            }
            ExecResult show = transport.execInteractive(session, new ExecSpec(COMMANDS.get(COMMANDS.size() - 1)), SHOW_TIMEOUT);
            if (!(show instanceof ExecResult.Completed completed)) {
                return new Outcome.Unavailable("show config running: " + show.getClass().getSimpleName());
            }
            String text = completed.output() == null ? "" : completed.output().strip();
            long setLines = text.lines().filter(line -> line.startsWith("set ")).count();
            if (setLines < MIN_SET_LINES) {
                return new Outcome.Unavailable("show config running answered " + setLines + " set-lines (" + text.length()
                        + " chars); not a configuration");
            }
            return new Outcome.Read(text + "\n");
        } finally {
            transport.disconnect(session);
        }
    }
}
