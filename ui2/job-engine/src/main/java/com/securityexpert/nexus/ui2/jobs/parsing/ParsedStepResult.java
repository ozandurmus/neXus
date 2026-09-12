package com.securityexpert.nexus.ui2.jobs.parsing;

import java.util.Map;

/**
 * The parser framework's boundary type (contract §6): {@code (step output,
 * capability's parser semantics) -> ParsedStepResult}. Type-distinguishes
 * three outcomes so a parse failure is never silently collapsed into an
 * empty, correctly-parsed result (contract §6, §8 test 9, AC-7):
 *
 * <ul>
 *   <li>{@link Matched} -- expectation met, facts derived, each
 *       independently stated. An empty {@code facts} map is a legitimate,
 *       correctly-parsed outcome (e.g. a fact whose value is genuinely
 *       absent from this device's output) -- distinct in kind, not degree,
 *       from {@link Ambiguous}.</li>
 *   <li>{@link ExpectationUnmet} -- a definite failure, proving a negative,
 *       not nothing. {@code error_class = STEP_EXPECTATION_UNMET}.</li>
 *   <li>{@link Ambiguous} -- the parser cannot tell. {@code error_class =
 *       STEP_AMBIGUOUS_RESPONSE} or, for an uncaught parser exception,
 *       {@code STEP_PARSE_ERROR} -- contributes to {@code OUTCOME_UNKNOWN}
 *       past the mutation boundary, never coerced into {@code
 *       matched(emptyList())}.</li>
 * </ul>
 */
public sealed interface ParsedStepResult {

    record Matched(Map<String, Object> facts) implements ParsedStepResult {

        public Matched {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    record ExpectationUnmet(String detail) implements ParsedStepResult {

        public String errorClass() {
            return "STEP_EXPECTATION_UNMET";
        }
    }

    record Ambiguous(String errorClass, String detail) implements ParsedStepResult {

        public static Ambiguous ambiguousResponse(String detail) {
            return new Ambiguous("STEP_AMBIGUOUS_RESPONSE", detail);
        }

        public static Ambiguous parseError(Throwable cause) {
            return new Ambiguous("STEP_PARSE_ERROR", String.valueOf(cause.getMessage()));
        }
    }
}
