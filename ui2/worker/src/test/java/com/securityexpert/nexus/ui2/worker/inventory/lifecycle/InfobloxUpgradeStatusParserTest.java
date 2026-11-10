package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class InfobloxUpgradeStatusParserTest {

    @Test
    void readsTheCurrentVersionOfTheGridEntryAndNothingElse() {
        // The answer of GET /wapi/v<ver>/upgradestatus?type=GRID&_return_fields=current_version: the object reference and the field.
        assertEquals(Optional.of("9.0.4-50240-c8be6dfb1ee5"), InfobloxUpgradeStatusParser.currentVersion(
                "[{\"_ref\":\"upgradestatus/b25lLnZpcnR1YWxfbm9kZSQw:gm.corp.example\",\"current_version\":\"9.0.4-50240-c8be6dfb1ee5\"}]"));
        assertEquals(Optional.of("8.6.3"), InfobloxUpgradeStatusParser.currentVersion("{\"result\":[{\"current_version\":\" 8.6.3 \"}]}"));
        assertEquals(Optional.of("9.0.1"), InfobloxUpgradeStatusParser.currentVersion("{\"current_version\":\"9.0.1\"}"));
        assertEquals(Optional.of("9.0.2"), InfobloxUpgradeStatusParser.currentVersion("[{\"type\":\"GRID\"},{\"current_version\":\"9.0.2\"}]"));
    }

    @Test
    void anythingThatIsNotAPlainVersionGivesNothing() {
        for (String raw : new String[] {null, "", "  ", "not json", "[]", "{}", "[{\"type\":\"GRID\"}]", "[{\"current_version\":9}]",
                "[{\"current_version\":\"\"}]", "[{\"current_version\":\"gm.corp.example\"}]", "[{\"current_version\":\"9.0 beta build\"}]",
                "[{\"current_version\":null}]", "[[[[[[[[[[[[[[[[[[[[", "\"9.0.4\""}) {
            assertEquals(Optional.empty(), InfobloxUpgradeStatusParser.currentVersion(raw), String.valueOf(raw));
        }
        assertEquals(Optional.empty(), InfobloxUpgradeStatusParser.currentVersion("[{\"current_version\":\"9." + "1".repeat(80) + "\"}]"));
    }

    @Test
    void currentVersionsAreTheDistinctValuesOfAllEntriesInFirstSeenOrderAtMostEight() {
        assertEquals(List.of("9.0.4", "9.0.3"), InfobloxUpgradeStatusParser.currentVersions(
                "[{\"_ref\":\"upgradestatus/a:gm.corp.example\",\"current_version\":\"9.0.4\"},{\"current_version\":\"9.0.3\"},"
                        + "{\"current_version\":\"9.0.4\"},{\"type\":\"VNODE\"},{\"current_version\":\"not a version\"}]"));
        assertEquals(List.of("8.6.3"), InfobloxUpgradeStatusParser.currentVersions("{\"result\":[{\"current_version\":\" 8.6.3 \"}]}"));
        assertEquals(List.of("9.0.1"), InfobloxUpgradeStatusParser.currentVersions("{\"current_version\":\"9.0.1\"}"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 1; i <= 12; i++) {
            many.append(i > 1 ? "," : "").append("{\"current_version\":\"9.").append(i).append("\"}");
        }
        assertEquals(8, InfobloxUpgradeStatusParser.currentVersions(many.append("]").toString()).size());
        for (String raw : new String[] {null, "", "  ", "not json", "[]", "{}", "[{\"type\":\"VNODE\"}]", "[[[[[[[[[[[[[[[[[[[[", "\"9.0.4\""}) {
            assertEquals(List.of(), InfobloxUpgradeStatusParser.currentVersions(raw), String.valueOf(raw));
        }
    }
}
