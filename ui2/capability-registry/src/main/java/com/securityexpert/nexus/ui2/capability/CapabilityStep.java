package com.securityexpert.nexus.ui2.capability;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * One step of a capability's {@code steps[]}/{@code finally_steps[]} (C4
 * §2.2 field 3, §2.3). A step's own literal fields only -- gate resolution
 * (against the enclosing capability's {@code vendor}/{@code
 * platform_role_scope} and {@code transport.kind}) is {@link GateResolver}'s
 * job, not this record's.
 *
 * @param kind                  one of {@link StepKind}'s nine members
 * @param shellContext          e.g. {@code "expert_via_clish_c"} (C4 §3.2's
 *                              canonical-key component); {@code
 *                              "not_applicable"} for a kind that never sends
 *                              a command
 * @param commandTemplate       the exact, literal authored command/call
 *                              string (C4 §3.3 step 2); {@code null} for a
 *                              kind with no command ({@code connect},
 *                              {@code verify}, {@code disconnect})
 * @param gateNotApplicable     the spec author's own explicit declaration
 *                              that this step's gate is {@code
 *                              NOT_APPLICABLE} (C4 §2.3's {@code sftp_get}
 *                              row: "a fetch of a path already produced by
 *                              a gated prior step") -- distinct from a kind
 *                              whose {@link StepKind#gateApplicability()}
 *                              is intrinsically {@code NOT_APPLICABLE}
 * @param declaredActionClass   present for readability/review only, never
 *                              for resolution (C4 §3.3 step 5)
 * @param expectRegex           {@code expect.regex}; required for every
 *                              step with an expectation gate except {@code
 *                              connect} under {@code ssh_exec} (auth+banner
 *                              only, C4 §7-10)
 * @param timeoutS              declared timeout; mirrored from the
 *                              resolved gate's own {@code timeout_s} once
 *                              {@code KNOWN} (C4 §6) rather than trusted
 *                              from the spec alone for a resolved step
 */
public record CapabilityStep(
        StepKind kind,
        String shellContext,
        String commandTemplate,
        boolean gateNotApplicable,
        Optional<ActionClass> declaredActionClass,
        Optional<String> expectRegex,
        Optional<Integer> timeoutS) {

    public CapabilityStep {
        Objects.requireNonNull(kind, "kind");
        shellContext = shellContext == null ? "not_applicable" : shellContext;
        declaredActionClass = declaredActionClass == null ? Optional.empty() : declaredActionClass;
        expectRegex = expectRegex == null ? Optional.empty() : expectRegex;
        timeoutS = timeoutS == null ? Optional.empty() : timeoutS;
    }

    /**
     * C4 §3.3 step 1: kinds with intrinsic {@code NOT_APPLICABLE} gate
     * applicability skip resolution regardless of {@link #gateNotApplicable};
     * a {@code DEPENDS_ON_PRIOR_STEP} kind (only {@code sftp_get} today)
     * skips resolution only when the spec explicitly declares it.
     */
    public boolean skipsGateResolution() {
        return kind.gateApplicability() == StepKind.GateApplicability.NOT_APPLICABLE
                || (kind.gateApplicability() == StepKind.GateApplicability.DEPENDS_ON_PRIOR_STEP && gateNotApplicable);
    }

    public CanonicalCommandKey canonicalKey(String vendor, String platformRoleScope, TransportKind transportKind) {
        if (skipsGateResolution()) {
            return null;
        }
        String command = commandTemplate == null ? "" : commandTemplate;
        return new CanonicalCommandKey(vendor, platformRoleScope, shellContext, transportKind.name(), command);
    }

    /**
     * C4 §7-10 / contract §5, AC-10: under {@code ssh_exec} a {@code
     * connect} step's expectation is authentication plus an optional
     * banner match only -- never a shell-prompt regex, because no
     * persistent shell is opened. A conservative heuristic for "looks like
     * a shell-prompt regex" is enough here: this document defines the
     * refusal rule, not a full shell-grammar parser.
     */
    private static final Pattern SHELL_PROMPT_LOOKING_REGEX = Pattern.compile(
            "[#>$]|prompt", Pattern.CASE_INSENSITIVE);

    public boolean connectExpectationLooksLikeShellPrompt() {
        return kind == StepKind.CONNECT && expectRegex.isPresent()
                && SHELL_PROMPT_LOOKING_REGEX.matcher(expectRegex.get()).find();
    }
}
