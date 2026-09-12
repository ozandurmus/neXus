package com.securityexpert.nexus.ui2.jobs.parsing;

import java.util.Objects;

/**
 * The parser framework's shared boundary (contract §6): runs a capability's
 * {@link StepParser} and guarantees the three-way distinction of {@link
 * ParsedStepResult} holds even when the parser itself misbehaves. An
 * uncaught parser exception becomes {@link ParsedStepResult.Ambiguous}
 * ({@code STEP_PARSE_ERROR}) -- never {@code matched(emptyList())} (contract
 * §6, §8 test 9).
 */
public final class ParserRunner {

    private ParserRunner() {
    }

    public static ParsedStepResult run(StepParser parser, String rawOutput) {
        Objects.requireNonNull(parser, "parser");
        try {
            ParsedStepResult result = parser.parse(rawOutput);
            if (result == null) {
                // A parser that returns null is exactly as much a defect
                // as one that throws -- never silently treated as a
                // correctly-parsed empty result.
                return ParsedStepResult.Ambiguous.parseError(
                        new IllegalStateException("StepParser#parse returned null"));
            }
            return result;
        } catch (RuntimeException e) {
            return ParsedStepResult.Ambiguous.parseError(e);
        }
    }
}
