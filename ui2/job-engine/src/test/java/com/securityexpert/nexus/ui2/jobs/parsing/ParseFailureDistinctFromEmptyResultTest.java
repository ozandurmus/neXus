package com.securityexpert.nexus.ui2.jobs.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Contract §8 test 9 (AC-7): "expectationUnmet, matched(emptyList()), an
 * uncaught exception; fails if any two share an outcome/error_class." All
 * three are constructed here and their (outcome-kind, error_class) pairs
 * must be pairwise distinct -- a parse failure is never silently an empty
 * result (contract §6).
 */
class ParseFailureDistinctFromEmptyResultTest {

    @Test
    void allThreeOutcomesAreTypeAndErrorClassDistinct() {
        ParsedStepResult matchedEmpty = new ParsedStepResult.Matched(Map.of());
        ParsedStepResult expectationUnmet = new ParsedStepResult.ExpectationUnmet("no match found");
        ParsedStepResult uncaughtException = ParserRunner.run(
                output -> {
                    throw new RuntimeException("simulated parser defect");
                }, "any output");

        assertTrue(matchedEmpty instanceof ParsedStepResult.Matched);
        assertTrue(expectationUnmet instanceof ParsedStepResult.ExpectationUnmet);
        assertTrue(uncaughtException instanceof ParsedStepResult.Ambiguous);

        String matchedKey = keyOf(matchedEmpty);
        String unmetKey = keyOf(expectationUnmet);
        String ambiguousKey = keyOf(uncaughtException);

        Set<String> keys = new HashSet<>(Set.of(matchedKey, unmetKey, ambiguousKey));
        assertEquals(3, keys.size(), "all three (outcome-kind, error_class) pairs must be pairwise distinct: "
                + matchedKey + ", " + unmetKey + ", " + ambiguousKey);

        assertNotEquals(unmetKey, ambiguousKey, "a definite failure must never share an error_class with an "
                + "ambiguous/parse-error outcome");
    }

    @Test
    void anUncaughtParserExceptionIsNeverCoercedIntoMatchedEmptyList() {
        ParsedStepResult result = ParserRunner.run(output -> {
            throw new IllegalStateException("boom");
        }, "output");

        assertTrue(result instanceof ParsedStepResult.Ambiguous, "an uncaught parser exception must surface as "
                + "Ambiguous(STEP_PARSE_ERROR), never as matched(emptyList())");
        assertEquals("STEP_PARSE_ERROR", ((ParsedStepResult.Ambiguous) result).errorClass());
    }

    @Test
    void aNullReturnFromAParserIsAlsoNeverTreatedAsAnEmptyMatch() {
        ParsedStepResult result = ParserRunner.run(output -> null, "output");
        assertTrue(result instanceof ParsedStepResult.Ambiguous,
                "a parser returning null is exactly as much a defect as one that throws");
    }

    /** {@code (outcome-kind, error_class)} -- the pair contract §8 test 9 requires to be pairwise distinct. */
    private static String keyOf(ParsedStepResult result) {
        if (result instanceof ParsedStepResult.Matched) {
            return "MATCHED:none";
        }
        if (result instanceof ParsedStepResult.ExpectationUnmet unmet) {
            return "EXPECTATION_UNMET:" + unmet.errorClass();
        }
        if (result instanceof ParsedStepResult.Ambiguous ambiguous) {
            return "AMBIGUOUS:" + ambiguous.errorClass();
        }
        throw new IllegalStateException("unreachable");
    }
}
