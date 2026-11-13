package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Counts-only shapes (2026-11-13): synthetic entries; the note carries fixed words and numbers, never a name or a path. */
class PanConfigLogShapesTest {
    private static final String DG = "/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='DG-EXAMPLE']";

    private static PanLogPage.Entry entry(String time, String seqno, String admin, String cmd, String result, String path) {
        return new PanLogPage.Entry(time, seqno, Map.of("admin", admin, "cmd", cmd, "result", result, "path", path));
    }

    @Test void changeEntriesAreCountedByPathKindAndCommitsByCommandAndResult() {
        var counts = PanConfigLogShapes.of(List.of(
                entry("2026/11/13 03:00:01", "1", "admin-a", "edit", "Succeeded", DG + "/pre-rulebase/security/rules/entry[@name='R']"),
                entry("2026/11/13 03:00:02", "2", "admin-a", "set", "Succeeded", "/config/shared/post-rulebase/security/rules/entry[@name='R']"),
                entry("2026/11/13 03:00:03", "3", "admin-b", "edit", "Succeeded", DG + "/pre-rulebase/security/rules/entry[@name='R']/source"),
                entry("2026/11/13 03:00:04", "4", "admin-b", "set", "Succeeded", DG + "/address/entry[@name='obj']"),
                entry("2026/11/13 03:00:05", "5", "admin-b", "delete", "Succeeded", ""),
                entry("2026/11/13 03:00:06", "6", "admin-b", "commit-all", "Succeeded", ""),
                entry("2026/11/13 03:00:07", "7", "admin-b", "validate-all", "Succeeded", "")));
        assertEquals(Map.of("RULE_PATH", 1, "SHARED_RULE_PATH", 1, "RULE_FIELD_PATH", 1, "OTHER_OBJECT", 1, "UNMATCHED", 1), counts.paths());
        assertEquals(Map.of("commit-all/Succeeded", 1), counts.commits(), "other commands are not counted as commits");
        assertEquals(0, counts.submittedWithCompletion() + counts.submittedWithoutCompletion());
    }

    @Test void aSubmittedCommitIsMatchedToALaterCompletionOfTheSameAdministratorOnly() {
        var counts = PanConfigLogShapes.of(List.of(
                entry("2026/11/13 03:00:01", "1", "admin-a", "commit", "Submitted", ""),
                entry("2026/11/13 03:00:09", "2", "admin-a", "commit", "Succeeded", ""),
                entry("2026/11/13 03:00:02", "3", "admin-b", "commit", "Submitted", ""),
                entry("2026/11/13 03:00:03", "4", "admin-c", "commit", "Submitted", ""),
                entry("2026/11/13 03:00:08", "5", "admin-a", "commit-all", "Failed", "")));
        assertEquals(1, counts.submittedWithCompletion(), "only the first submit has a later completion by its own administrator");
        assertEquals(2, counts.submittedWithoutCompletion());
        assertEquals(Map.of("commit/Submitted", 3, "commit/Succeeded", 1, "commit-all/Failed", 1), counts.commits());
    }

    @Test void theNoteCarriesOnlyFixedWordsAndNumbers() {
        var note = PanConfigLogShapes.of(List.of(
                entry("2026/11/13 03:00:01", "1", "admin-a", "edit", "Succeeded", DG + "/pre-rulebase/security/rules/entry[@name='Rule-EXAMPLE']"),
                entry("2026/11/13 03:00:02", "2", "admin-a", "commit", "weird result with spaces", ""))).note();
        for (String raw : List.of("admin-a", "DG-EXAMPLE", "Rule-EXAMPLE", "weird", "/config")) assertFalse(note.contains(raw), raw);
        assertTrue(note.contains("commit/other=1"), "a result that is not a plain word is counted as other");
        assertTrue(note.startsWith("shapes paths={OTHER_OBJECT=0, RULE_FIELD_PATH=0, RULE_PATH=1,"));
    }
}
