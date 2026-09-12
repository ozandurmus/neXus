package com.securityexpert.nexus.ui2.jobs.parsing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The expectation-gate mechanics common to every capability (contract §6;
 * design §6.3, ported via C4 §2.3): anchored matching, fail-closed on
 * ambiguity. A capability's own {@link StepParser} calls this once its
 * expectation regex is known; a timeout is handled by the caller before
 * this class is ever invoked (contract §6/§5.2: "timeout is never a
 * success," decided at send time, never inferred from output).
 */
public final class ExpectationGate {

    private ExpectationGate() {
    }

    /**
     * Anchored matching: the supplied regex is evaluated with {@link
     * Matcher#find()} but the framework never widens a capability's own
     * regex into a substring search silently succeeding on an
     * unintended partial match -- callers are expected to author
     * anchors ({@code ^}/{@code $}) themselves; this method's contribution
     * is fail-closed handling of a malformed pattern, never treated as
     * "no match" (which would be indistinguishable from a legitimate
     * expectation-unmet) but as {@link ParsedStepResult.Ambiguous}.
     */
    public static ParsedStepResult evaluate(String regex, String output) {
        if (regex == null) {
            return ParsedStepResult.Ambiguous.ambiguousResponse(
                    "no expectation regex supplied -- a step without an expectation is a spec defect (design §6.3)");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, Pattern.MULTILINE);
        } catch (PatternSyntaxException e) {
            return ParsedStepResult.Ambiguous.parseError(e);
        }
        if (output == null) {
            return new ParsedStepResult.ExpectationUnmet("no output received");
        }
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return new ParsedStepResult.Matched(java.util.Map.of());
        }
        return new ParsedStepResult.ExpectationUnmet("output did not match expectation regex: " + regex);
    }
}
