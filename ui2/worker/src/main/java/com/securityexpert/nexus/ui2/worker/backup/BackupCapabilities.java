package com.securityexpert.nexus.ui2.worker.backup;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;

/**
 * Builds the one {@code cp_gateway_backup} {@link Capability} registry
 * entry in Java directly -- mirrors {@code worker.configuration.
 * ConfigurationCapabilities} exactly. The real device contact runs through
 * {@link BackupJobExecutor}/{@link BackupCapabilityExecutor} over the
 * closed {@link BackupReadPlan}, never through {@code StepExecutor}; these
 * steps exist only so {@link Capability#executionEligible()} reflects
 * whether {@code docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md}'s entries
 * are actually gated {@code SIGNED_OFF}.
 *
 * <p>Entry 5 (the SFTP fetch) declares {@code gateNotApplicable = true}: the
 * path fetched is exactly the one the submit step's own output named (C4
 * section 2.3's {@code sftp_get}/prior-step rule) -- see the gate doc's own
 * "Entry 5's gate is NOT_APPLICABLE" section.</p>
 */
public final class BackupCapabilities {

    private BackupCapabilities() {
    }

    public static Capability checkPoint(GateRegistryPort gateRegistry) {
        List<CapabilityStep> steps = List.of(
                connectStep(),
                execStep(BackupReadPlan.CP_SHOW_DISKSPACE),
                execStep(BackupReadPlan.CP_DF_VAR_LOG),
                execStep(BackupReadPlan.CP_ADD_BACKUP_LOCAL),
                pollStep(BackupReadPlan.CP_SHOW_BACKUP_STATUS),
                sftpGetStepNotApplicable(),
                execStep("sha256sum <name>"),
                execStep("clish -c \"delete backup <name>\""));
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, steps,
                List.of(disconnectStep()), "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    /** The two XML API reads {@code worker.backup.pan.PaloAltoBackupExecutor} issues, gated as
     * pan_backup_config_show / pan_backup_export_device_state (V40). Until this existed the capability
     * id was known to admission but never registered, so every PAN backup was refused as unknown. */
    public static Capability paloAlto(GateRegistryPort gateRegistry) {
        List<CapabilityStep> steps = List.of(
                connectStep(),
                xmlApiCallStep("type=config&action=show"),
                xmlApiCallStep("type=export&category=device-state"));
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, steps,
                List.of(disconnectStep()), "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    /**
     * The CLI half of the PAN bundle (V43 gate rows): four interactive reads in operational mode,
     * run by {@code PaloAltoBackupExecutor} inside every {@code pan_device_state_backup} run --
     * never a job of its own. Registered so the gate alignment holds for its literals.
     */
    public static Capability paloAltoSetConfig(GateRegistryPort gateRegistry) {
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connectStep());
        for (String command : com.securityexpert.nexus.ui2.worker.backup.pan.PanSetConfigReader.COMMANDS) {
            steps.add(new CapabilityStep(StepKind.EXEC, "operational", command, false, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.PAN_SET_CONFIG_READ, "palo_alto",
                "pan_firewall", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.copyOf(steps),
                List.of(disconnectStep()), "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    public static List<Capability> all(GateRegistryPort gateRegistry) {
        return List.of(checkPoint(gateRegistry), paloAlto(gateRegistry), paloAltoSetConfig(gateRegistry));
    }

    private static CapabilityStep xmlApiCallStep(String send) {
        return new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", send, false, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static CapabilityStep connectStep() {
        return new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep disconnectStep() {
        return new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static CapabilityStep execStep(String send) {
        return new CapabilityStep(StepKind.EXEC, "expert", send, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep pollStep(String send) {
        return new CapabilityStep(StepKind.POLL, "expert", send, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep sftpGetStepNotApplicable() {
        return new CapabilityStep(StepKind.SFTP_GET, "not_applicable", null, true, Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
