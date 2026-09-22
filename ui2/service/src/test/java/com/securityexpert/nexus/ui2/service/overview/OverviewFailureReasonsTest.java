package com.securityexpert.nexus.ui2.service.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Failed-job reasons grouped for the Overview (amendment A-2026-09-23 to OVERVIEW_EXCEPTION_SCREEN_CONTRACT). */
class OverviewFailureReasonsTest {

    private static Timestamp at(String iso) {
        return Timestamp.from(Instant.parse(iso));
    }

    @Test
    void reasonKeyKeepsTheCodeAndTheStableHeadOfTheDetail() {
        assertThat(OverviewService.reasonKey("connect_failed: palo alto key generation did not return a usable key (after 8127ms)"))
                .isEqualTo("connect_failed: palo alto key generation did not return a usable key");
        assertThat(OverviewService.reasonKey("artefact_store_failed: sftp fetch failed: TRANSPORT_NOT_IMPLEMENTED: x"))
                .isEqualTo("artefact_store_failed: sftp fetch failed");
        assertThat(OverviewService.reasonKey("unhandled_exception: Java heap space")).isEqualTo("unhandled_exception: Java heap space");
        assertThat(OverviewService.reasonKey("timeout")).isEqualTo("timeout");
        assertThat(OverviewService.reasonKey(null)).isEqualTo("no reason recorded");
        assertThat(OverviewService.reasonKey("  ")).isEqualTo("no reason recorded");
    }

    @Test
    void reasonKeyDropsATargetTailAndFoldsNumbers() {
        assertThat(OverviewService.reasonKey("submit_refused: show backup status reported a failed backup for EXAMPLE-FW-01"))
                .isEqualTo("submit_refused: show backup status reported a failed backup");
        assertThat(OverviewService.reasonKey("precheck_failed: 3 of 4 checks failed"))
                .isEqualTo("precheck_failed: N of N checks failed");
    }

    @Test
    void groupsLargestFirstWithDistinctDevicesTypesAndLatestTime() {
        List<OverviewService.FailedJob> failed = List.of(
                new OverviewService.FailedJob("pan_inventory_collect", "d1", "connect_failed: palo alto key generation did not return a usable key (after 8127ms)", at("2026-09-22T19:54:00Z")),
                new OverviewService.FailedJob("pan_inventory_collect", "d2", "connect_failed: palo alto key generation did not return a usable key (after 8020ms)", at("2026-09-22T19:55:00Z")),
                new OverviewService.FailedJob("pan_inventory_collect", "d2", "connect_failed: palo alto key generation did not return a usable key (after 7914ms)", at("2026-09-22T19:50:00Z")),
                new OverviewService.FailedJob("cp_gateway_backup", "d3", "unhandled_exception: Java heap space", at("2026-09-22T11:54:00Z")),
                new OverviewService.FailedJob("pan_configuration_collect", "d4", "unhandled_exception: Java heap space", at("2026-09-22T17:36:00Z")));
        List<Map<String, Object>> reasons = OverviewService.failureReasons(failed);
        assertThat(reasons).hasSize(2);
        assertThat(reasons.get(0)).containsEntry("reason", "connect_failed: palo alto key generation did not return a usable key")
                .containsEntry("count", 3).containsEntry("devices", 2L)
                .containsEntry("job_types", List.of("pan_inventory_collect"))
                .containsEntry("last_at", "2026-09-22T19:55:00Z");
        assertThat(reasons.get(1)).containsEntry("count", 2)
                .containsEntry("job_types", List.of("cp_gateway_backup", "pan_configuration_collect"));
    }

    @Test
    void atMostSixGroups() {
        List<OverviewService.FailedJob> failed = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            failed.add(new OverviewService.FailedJob("t", "d" + i, "code_" + (char) ('a' + i), at("2026-09-22T10:00:00Z")));
        }
        assertThat(OverviewService.failureReasons(failed)).hasSize(6);
    }
}
