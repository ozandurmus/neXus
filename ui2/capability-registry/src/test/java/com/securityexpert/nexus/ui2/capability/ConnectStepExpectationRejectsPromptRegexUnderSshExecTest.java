package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Contract §8 test 14 / C4 §7 test 10, AC-10: "fails if a shell-prompt
 * connect regex validates under ssh_exec." Under ssh_exec, connect's
 * expectation is authentication plus optional banner match only -- never a
 * shell-prompt regex (C4 §7-10, since no persistent shell is opened).
 */
class ConnectStepExpectationRejectsPromptRegexUnderSshExecTest {

    @Test
    void shellPromptRegexOnConnectFailsValidationUnderSshExec() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.of("^.*[#>]\\s*$"), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec("test_prompt_regex", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect), List.of(), "UNKNOWN",
                List.of(), false);

        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecValidator.validate(spec));
        assertEquals(CapabilityValidationException.CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT, e.code());
    }

    @Test
    void connectWithNoExpectationRegexValidatesUnderSshExec() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec("test_connect_ok", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect), List.of(), "UNKNOWN",
                List.of(), false);

        assertDoesNotThrow(() -> CapabilitySpecValidator.validate(spec));
    }

    @Test
    void connectUnderSshInteractiveRequiresAnExpectationRegex() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec("test_interactive_needs_regex", "check_point", "vsx_context",
                TransportKind.SSH_INTERACTIVE, MaturityState.CAP_OFFLINE, List.of(connect), List.of(), "UNKNOWN",
                List.of(), true);

        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecValidator.validate(spec));
        assertEquals(CapabilityValidationException.CONNECT_EXPECTATION_INVALID_FOR_TRANSPORT, e.code());
    }
}
