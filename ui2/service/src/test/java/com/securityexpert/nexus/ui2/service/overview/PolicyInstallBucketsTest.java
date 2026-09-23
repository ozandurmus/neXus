package com.securityexpert.nexus.ui2.service.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall;

class PolicyInstallBucketsTest {

    private static DevicePolicyInstall at(String id, String when) {
        return new DevicePolicyInstall(id, Optional.of("P"), Optional.of(when), Optional.of(Instant.parse(when)), "cp_cpstat_policy",
                Optional.of(Instant.parse("2026-09-23T20:01:00Z")));
    }

    @Test
    void calendarDaysInIstanbulWithOldestAndUnknown() {
        Instant now = Instant.parse("2026-09-23T20:30:00Z"); // 23:30 in Istanbul
        Map<String, DevicePolicyInstall> installs = Map.of(
                "a", at("a", "2026-09-23T20:05:00Z"),   // today 23:05
                "b", at("b", "2026-09-22T21:30:00Z"),   // 00:30 on the 23rd in Istanbul -> today
                "c", at("c", "2026-09-22T20:00:00Z"),   // yesterday 23:00
                "d", at("d", "2026-09-18T20:00:00Z"),   // 5 days
                "e", at("e", "2026-09-01T20:00:00Z"));  // older
        Map<String, Object> b = OverviewService.policyInstallBuckets(List.of("a", "b", "c", "d", "e", "f"), installs, now);
        assertThat(b).containsEntry("today", 2).containsEntry("yesterday", 1).containsEntry("days_2_7", 1)
                .containsEntry("older", 1).containsEntry("unknown", 1).containsEntry("of", 6)
                .containsEntry("oldest_at", "2026-09-01T20:00:00Z").containsEntry("state", "OK");
    }

    @Test
    void nothingReadIsUnknown() {
        assertThat(OverviewService.policyInstallBuckets(List.of("a"), Map.of(), Instant.now())).containsEntry("state", "UNKNOWN");
    }
}
