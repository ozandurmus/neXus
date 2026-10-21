package com.securityexpert.nexus.ui2.jobs.systemlog;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * The one-off Spike Detective layout review (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3; PO approval 2026-10-19,
 * docs/design/SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md): one {@code cp_spike_layout_review} job on one Check Point
 * Gaia gateway issues exactly two read-only listings on one trusted SSH session, and nothing else.
 *
 * <ul>
 *   <li>C4 {@value #LS_SPIKE_GATE}: the parent listing, byte-identical to its V160 row (shell context
 *       {@value CpSystemLogCollect#COLLECT_SHELL}, as V160 keys it).</li>
 *   <li>C5 {@value #LS_SPIKE_DIR_GATE}: the listing of ONE data directory C4 printed in the same run, the V160 template
 *       with {@code <dir>} replaced by that name; the name must match {@link #SAFE_DIR_NAME} (§8.3), so no quote, space,
 *       slash or shell character can reach the command.</li>
 * </ul>
 *
 * No file is fetched and no content is read. Both rows are resolved again against the live gate before each is sent.
 */
public final class CpSpikeLayoutReview {
    private CpSpikeLayoutReview() {}

    public static final String CAPABILITY = "cp_spike_layout_review";
    /** The {@code system_log_measurement.source} of the review's one summary row (V166). */
    public static final String SOURCE = CAPABILITY;
    public static final String VENDOR = CpSystemLogCollect.VENDOR;
    public static final String PLATFORM_ROLE_SCOPE = CpSystemLogCollect.PLATFORM_ROLE_SCOPE;

    public static final String LS_SPIKE_GATE = "cp_syslog_collect_ls_spike";
    public static final String LS_SPIKE_DIR_GATE = "cp_syslog_collect_ls_spike_dir";
    /** C4 repeats the approved V157 measurement string, so V160 narrows its key with this context. */
    public static final String LS_SPIKE_SHELL = CpSystemLogCollect.COLLECT_SHELL;
    /** C5 is a new command string: its row carries the plain {@code expert} context. */
    public static final String LS_SPIKE_DIR_SHELL = "expert";

    /** §10.1 C4, byte-identical to the V160 row. */
    public static final String LS_SPIKE_COMMAND = "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'";
    /** §10.1 C5 as V160 registers it; {@code <dir>} is the only placeholder. */
    public static final String LS_SPIKE_DIR_TEMPLATE = "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'";
    public static final String DIR_PLACEHOLDER = "<dir>";
    /** §8.3: the data-directory name pattern is not measured, so only this safe character form is ever listed. */
    public static final Pattern SAFE_DIR_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    /**
     * §10.1 "Max bytes" of C4: the transport stops there and the listing is UNKNOWN (never processed partially).
     * 1 MiB since the PO amendment of 2026-10-21 (PO_DECISION_RECORD_2026_10_21_SPIKE_LISTING_BOUND.md, option A; was 256 KiB):
     * 3,638 entries of at most 33 + 255 bytes each is 1,047,744 bytes, below this bound.
     */
    public static final int LS_SPIKE_MAX_BYTES = 1024 * 1024;
    public static final int LS_SPIKE_DIR_MAX_BYTES = 64 * 1024;
    /** §8.3: a data directory is settled when its mtime is at least this much older than the clock. */
    public static final long SETTLE_SECONDS = 600;

    public static CanonicalCommandKey listingKey() {
        return new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, LS_SPIKE_SHELL, "SSH_EXEC", LS_SPIKE_COMMAND);
    }

    public static CanonicalCommandKey directoryKey() {
        return new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, LS_SPIKE_DIR_SHELL, "SSH_EXEC", LS_SPIKE_DIR_TEMPLATE);
    }

    /** The signed-off C4 read gate; empty when it is missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> listingGate(GateRegistryPort registry) {
        return resolve(listingKey(), LS_SPIKE_GATE, registry);
    }

    /** The signed-off C5 read gate; empty when it is missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> directoryGate(GateRegistryPort registry) {
        return resolve(directoryKey(), LS_SPIKE_DIR_GATE, registry);
    }

    /**
     * True when {@code name} may be placed into the C5 template: the §8.3 safe form, and never {@code .} or {@code ..}
     * (which the form admits but which would name the parent itself or leave it).
     */
    public static boolean safeDirectoryName(String name) {
        return name != null && SAFE_DIR_NAME.matcher(name).matches() && !".".equals(name) && !"..".equals(name);
    }

    /** The C5 command for one directory name; empty when the name is outside the safe form. */
    public static Optional<String> directoryCommand(String name) {
        if (!safeDirectoryName(name)) return Optional.empty();
        return Optional.of(LS_SPIKE_DIR_TEMPLATE.replace(DIR_PLACEHOLDER, name));
    }

    private static Optional<GateResolution.Known> resolve(CanonicalCommandKey key, String expectedGate,
            GateRegistryPort registry) {
        if (registry == null) return Optional.empty();
        try {
            var resolved = GateResolver.resolve(key, Optional.of(ActionClass.CLASS_0_READ), registry);
            return resolved instanceof GateResolution.Known known && expectedGate.equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ && known.timeoutS() > 0
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    /** Connect, C4, C5 (the template), disconnect. Execution-eligible only while both rows resolve signed off. */
    public static Capability capability(GateRegistryPort registry) {
        var steps = List.of(
                new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new CapabilityStep(StepKind.EXEC, LS_SPIKE_SHELL, LS_SPIKE_COMMAND, false, Optional.empty(),
                        Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.EXEC, LS_SPIKE_DIR_SHELL, LS_SPIKE_DIR_TEMPLATE, false, Optional.empty(),
                        Optional.empty(), Optional.empty()));
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                Optional.empty(), Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, VENDOR, PLATFORM_ROLE_SCOPE,
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-19", List.of(),
                false));
    }
}
