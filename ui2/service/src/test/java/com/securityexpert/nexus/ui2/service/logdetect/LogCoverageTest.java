package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.RunFacts;
import com.securityexpert.nexus.ui2.service.logdetect.LogCoverage.State;

/** Section 7.4: every window that cannot be evaluated says why, and none is ever "healthy". */
class LogCoverageTest {
    private static final RunFacts OK = new RunFacts("COLLECTED", List.of(), 1);

    private static State of(RunFacts run, Map<String, Integer> statuses, long lines, long unparsed, int history) {
        return LogCoverage.of(true, run, statuses, lines, unparsed, history);
    }

    @Test
    void historyDecidesBetweenNotEvaluableLearningAndEvaluated() {
        assertEquals("NOT_EVALUABLE", of(OK, Map.of("INDEXED", 1), 100, 0, 6).state());
        assertEquals("BASELINE_LEARNING", of(OK, Map.of("INDEXED", 1), 100, 0, 7).state());
        assertEquals("BASELINE_LEARNING", of(OK, Map.of("INDEXED", 1), 100, 0, 13).state());
        assertEquals("EVALUATED", of(OK, Map.of("INDEXED", 1), 100, 0, 14).state());
    }

    @Test
    void aGapRecordOrABacklogOutcomeIsAGapNeverEvaluated() {
        assertEquals("GAP", of(new RunFacts("COLLECTED", List.of("ROTATED_AWAY"), 1), Map.of("INDEXED", 1), 100, 0, 30).state());
        assertEquals("GAP", of(new RunFacts("BACKLOG", List.of(), 1), Map.of("INDEXED", 1), 100, 0, 30).state());
    }

    @Test
    void aRunThatDidNotCollectIsNotCollected() {
        assertEquals("NOT_COLLECTED", of(new RunFacts("UNKNOWN", List.of(), 0), Map.of(), 0, 0, 30).state());
        assertEquals("NOT_COLLECTED", of(new RunFacts(null, List.of(), 0), Map.of(), 0, 0, 30).state());
    }

    @Test
    void maskingUnavailableAndUnreadableChunksAreNamed() {
        assertEquals("MASKING_UNAVAILABLE", of(OK, Map.of("MASKING_UNAVAILABLE", 1), 0, 0, 30).state());
        State unreadable = of(OK, Map.of("UNREADABLE", 1), 0, 0, 30);
        assertEquals("UNPARSED_HIGH", unreadable.state());
        assertEquals("CHUNK_UNREADABLE", unreadable.reason());
    }

    @Test
    void moreThanFivePercentUnparsedIsUnparsedHigh() {
        assertEquals("UNPARSED_HIGH", of(OK, Map.of("INDEXED", 1), 100, 6, 30).state());
        assertEquals("EVALUATED", of(OK, Map.of("INDEXED", 1), 100, 5, 30).state());
    }

    @Test
    void aSourceThatIsNotAnalysedIsUnsupported() {
        State s = LogCoverage.of(false, OK, Map.of("UNSUPPORTED", 1), 0, 0, 30);
        assertEquals("UNSUPPORTED", s.state());
    }

    @Test
    void onlyThreeStatesCountAsEvaluated() {
        assertTrue(LogCoverage.evaluated("EVALUATED"));
        assertTrue(LogCoverage.evaluated("BASELINE_LEARNING"));
        assertTrue(LogCoverage.evaluated("NOT_EVALUABLE"));
        for (String s : List.of("GAP", "NOT_COLLECTED", "MASKING_UNAVAILABLE", "UNPARSED_HIGH", "UNSUPPORTED")) {
            assertFalse(LogCoverage.evaluated(s), s);
        }
    }
}
