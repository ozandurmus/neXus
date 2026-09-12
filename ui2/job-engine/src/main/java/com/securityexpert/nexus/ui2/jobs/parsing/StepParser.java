package com.securityexpert.nexus.ui2.jobs.parsing;

/**
 * A capability's own regex/field-extraction semantics (contract §6: "a
 * capability's own parser supplies only its regex/field-extraction
 * semantics"). The expectation-gate mechanics common to every capability
 * (anchored matching, fail-closed on ambiguity, timeout-never-success,
 * captured-variable validation) are {@link ParserRunner}'s job, applied
 * once around every implementation of this interface -- an implementation
 * never has to re-implement them.
 */
public interface StepParser {

    /**
     * @param rawOutput the transport's raw, unbounded step output. This
     *                  method may throw for any reason (a regex engine
     *                  fault, an unexpected shape); {@link ParserRunner}
     *                  catches it and reports {@code STEP_PARSE_ERROR},
     *                  never lets it escape as {@code matched(emptyList())}.
     */
    ParsedStepResult parse(String rawOutput);
}
