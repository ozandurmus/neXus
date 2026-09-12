package com.securityexpert.nexus.ui2.capability;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Spec-time validation that does not require a database (C4 §3 items 4-5,
 * §7-6/§7-9/§7-10; contract §3 items 4-5). Gate resolution itself
 * ({@link GateResolver}) is a separate, registry-backed step; this class
 * only checks the parts of C4 §3/§7 that a spec's own literal content
 * decides.
 */
public final class CapabilitySpecValidator {

    private CapabilitySpecValidator() {
    }

    public static void validate(CapabilitySpec spec) {
        for (CapabilityStep step : spec.allSteps()) {
            validateStep(spec, step);
        }
    }

    private static void validateStep(CapabilitySpec spec, CapabilityStep step) {
        // C4 §2.3 / §7-6: sftp_put is reserved and refused unconditionally,
        // regardless of gate references, sign-off state, or action class.
        if (step.kind() == StepKind.SFTP_PUT) {
            throw new CapabilityValidationException(CapabilityValidationException.SFTP_PUT_RESERVED,
                    "capability " + spec.capabilityId() + " declares sftp_put -- no capability may push bytes "
                            + "to a device at current maturity (C4 §2.3, §7-6)");
        }

        // C4 §2.3 / §3.3 step 7: restore_push is legal only for a
        // controlled-restore-write capability.
        if (step.kind() == StepKind.RESTORE_PUSH) {
            boolean declaredControlledRestore = step.declaredActionClass()
                    .map(ac -> ac == ActionClass.CLASS_1B_CONTROLLED_RESTORE_WRITE)
                    .orElse(false);
            if (!declaredControlledRestore) {
                throw new CapabilityValidationException(
                        CapabilityValidationException.RESTORE_PUSH_ACTION_CLASS_INVALID,
                        "capability " + spec.capabilityId() + " declares restore_push with action_class != "
                                + "controlled-restore-write (C4 §2.3, §3.3 step 7)");
            }
        }

        // C4 §7-10 / contract §5 AC-10: under ssh_exec, connect's
        // expectation is auth+banner only -- a shell-prompt regex fails
        // validation. Under ssh_interactive the same step requires one.
        if (step.kind() == StepKind.CONNECT) {
            if (spec.transportKind() == TransportKind.SSH_EXEC && step.connectExpectationLooksLikeShellPrompt()) {
                throw new CapabilityValidationException(
                        CapabilityValidationException.CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT,
                        "capability " + spec.capabilityId() + "'s connect step carries a shell-prompt regex under "
                                + "ssh_exec, which never opens a persistent shell (C4 §7-10)");
            }
            if (spec.transportKind() == TransportKind.SSH_INTERACTIVE && step.expectRegex().isEmpty()) {
                throw new CapabilityValidationException(
                        CapabilityValidationException.CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT,
                        "capability " + spec.capabilityId() + "'s connect step under ssh_interactive requires an "
                                + "expect.regex (login banner/prompt match, C4 §5.3)");
            }
        }
    }

    /**
     * C4 §5.2 / §7-9: {@code ssh_interactive} requires the extraction
     * spec's own source-pointer evidence of shell-state dependency; absent
     * that proof, {@code ssh_exec} stays the default even when declared
     * otherwise.
     */
    public static void validateTransportEvidence(CapabilitySpec spec) {
        if (spec.transportKind() == TransportKind.SSH_INTERACTIVE && !spec.shellStateDependencyEvidence()) {
            throw new CapabilityValidationException(CapabilityValidationException.STEP_TRANSPORT_EVIDENCE_MISSING,
                    "capability " + spec.capabilityId() + " declares transport.kind=ssh_interactive with no "
                            + "captured shell-state-dependency evidence in its source pointers (C4 §5.2, §7-9)");
        }
    }
}
