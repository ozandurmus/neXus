package com.securityexpert.nexus.ui2.service.eventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.PendingDeferState;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.ProjectedUnitRow;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.Projection;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.UnitActivity;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.UnitKind;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerOperatorProjection.VendorFleetCoverage;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventTriggerOperatorProjectionTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    private TopologyNamePseudonymizer pseudonymizer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        byte[] testKey = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        pseudonymizer = new TopologyNamePseudonymizer(testKey);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void maskedProjectionPseudonymizesAllUnitKindsAndLeaksZeroPlantedRawStrings() throws Exception {
        String rawDomain = "CORP-EDGE-CMA-PROD";
        String rawDeviceGroup = "DG-RETAIL-BRANCHES";
        String rawFirewall = "fw-perimeter-east-01";
        String rawCluster = "CLS-CORE-PAYMENTS-HA";

        List<UnitActivity> input = List.of(
                new UnitActivity(
                        UnitKind.DOMAIN,
                        rawDomain,
                        "checkpoint",
                        "CONNECTED",
                        NOW.minus(Duration.ofMinutes(15)),
                        NOW.minus(Duration.ofMinutes(14)),
                        NOW.minus(Duration.ofMinutes(10)),
                        PendingDeferState.NONE,
                        null),
                new UnitActivity(
                        UnitKind.DEVICE_GROUP,
                        rawDeviceGroup,
                        "paloalto",
                        "HEARTBEAT_OK",
                        NOW.minus(Duration.ofHours(2)),
                        NOW.minus(Duration.ofHours(2)),
                        NOW.minus(Duration.ofHours(1)),
                        PendingDeferState.DEBOUNCING,
                        NOW.plus(Duration.ofSeconds(90))),
                new UnitActivity(
                        UnitKind.DEVICE,
                        rawFirewall,
                        "fortinet",
                        "DISCONNECTED",
                        NOW.minus(Duration.ofDays(20)),
                        NOW.minus(Duration.ofDays(20)),
                        NOW.minus(Duration.ofHours(6)),
                        PendingDeferState.NONE,
                        null),
                new UnitActivity(
                        UnitKind.CLUSTER,
                        rawCluster,
                        "cisco_asa",
                        "NOT_ENROLLED",
                        null,
                        null,
                        NOW.minus(Duration.ofHours(12)),
                        PendingDeferState.NONE,
                        null));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        assertThat(projection.masked()).isTrue();
        assertThat(projection.units()).hasSize(4);

        ProjectedUnitRow domainRow = projection.units().get(0);
        ProjectedUnitRow dgRow = projection.units().get(1);
        ProjectedUnitRow deviceRow = projection.units().get(2);
        ProjectedUnitRow clusterRow = projection.units().get(3);

        assertThat(domainRow.displayName()).startsWith("DOM-").doesNotStartWith("DMN-");
        assertThat(dgRow.displayName()).startsWith("DOM-").doesNotStartWith("DMN-");
        assertThat(deviceRow.displayName()).startsWith("FW-");
        assertThat(clusterRow.displayName()).startsWith("CLS-");
        assertThat(clusterRow.noChangeEventsFlag()).isFalse();
        assertThat(clusterRow.noChangeEventsCode()).isNull();
        assertThat(clusterRow.noChangeEventsMessage()).isNull();

        String serializedJson = objectMapper.writeValueAsString(projection);
        String asString = projection.toString();
        for (String planted : List.of(rawDomain, rawDeviceGroup, rawFirewall, rawCluster)) {
            assertThat(serializedJson).doesNotContainIgnoringCase(planted);
            assertThat(asString).doesNotContainIgnoringCase(planted);
        }
    }

    @Test
    void maskedProjectionRequiresPseudonymizerAndNeverEchoesIpLiterals() throws Exception {
        String syntheticIp = String.join(".", "198", "51", "100", "42");
        UnitActivity ipNamedDevice = new UnitActivity(
                UnitKind.DEVICE,
                syntheticIp,
                "fortinet",
                "CONNECTED",
                NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(2)),
                PendingDeferState.NONE,
                null);

        assertThatThrownBy(() -> EventTriggerOperatorProjection.project(List.of(ipNamedDevice), NOW, true, null))
                .isInstanceOf(NullPointerException.class);

        Projection projection = EventTriggerOperatorProjection.project(List.of(ipNamedDevice), NOW, true, pseudonymizer);
        assertThat(projection.units().getFirst().displayName()).isEqualTo(TopologyNamePseudonymizer.GAP);
        assertThat(objectMapper.writeValueAsString(projection)).doesNotContain(syntheticIp);
    }

    @Test
    void maskedProjectionMapsPlaceholdersAndIpLiteralsToGapForAllUnitKindsWithoutPoisoningMaskText() throws Exception {
        String syntheticIpv4 = String.join(".", "198", "51", "100", "42");
        String syntheticIpv6 = String.join(":", "2001", "db8", "", "1");

        List<UnitActivity> input = List.of(
                unit(UnitKind.DOMAIN, "Unknown", "checkpoint", "CONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DOMAIN, syntheticIpv4, "checkpoint", "CONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE_GROUP, "N/A", "paloalto", "HEARTBEAT_OK", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE_GROUP, "None", "paloalto", "HEARTBEAT_OK", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.CLUSTER, "null", "cisco_asa", "CONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.CLUSTER, syntheticIpv6, "cisco_asa", "CONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE, "Unknown", "fortinet", "CONNECTED", NOW.minus(Duration.ofHours(1))));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        assertThat(projection.units())
                .extracting(ProjectedUnitRow::displayName)
                .containsOnly(TopologyNamePseudonymizer.GAP);

        String serializedJson = objectMapper.writeValueAsString(projection);
        assertThat(serializedJson).doesNotContain(syntheticIpv4).doesNotContain(syntheticIpv6);

        // Placeholders and IP literals must never be registered in TopologyNamePseudonymizer's text dictionary.
        String sampleText = "Status Unknown, N/A, None, null, host " + syntheticIpv4 + " / " + syntheticIpv6;
        assertThat(pseudonymizer.maskText(sampleText)).isEqualTo(sampleText);
    }

    @Test
    void fleetCoverageCountsHealthyLiveUnitsPerVendor() {
        List<UnitActivity> input = List.of(
                unit(UnitKind.DOMAIN, "CP-DOM-A", "checkpoint", "CONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DOMAIN, "CP-DOM-B", "checkpoint", "HEARTBEAT_OK", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DOMAIN, "CP-DOM-C", "checkpoint", "DISCONNECTED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DOMAIN, "CP-DOM-D", "checkpoint", "HEARTBEAT_STALE", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DOMAIN, "CP-DOM-E", "checkpoint", "DEGRADED", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE_GROUP, "PAN-DG-1", "paloalto", "HEARTBEAT_OK", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE_GROUP, "PAN-DG-2", "paloalto", "PASSIVE_NO_HEARTBEAT", NOW.minus(Duration.ofHours(1))),
                unit(UnitKind.DEVICE, "ASA-UNIT-1", "cisco_asa", "NOT_ENROLLED", null),
                unit(UnitKind.DEVICE, "ASA-UNIT-2", "cisco_asa", "BOGUS_UNEXPECTED", null));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        assertThat(projection.fleetCoverageByVendor())
                .containsEntry("checkpoint", new VendorFleetCoverage("checkpoint", 2, 5))
                .containsEntry("paloalto", new VendorFleetCoverage("paloalto", 1, 2))
                .containsEntry("cisco_asa", new VendorFleetCoverage("cisco_asa", 0, 2));

        // Unknown liveness fails closed to UNKNOWN and never echoes arbitrary strings.
        assertThat(projection.units().get(8).livenessState()).isEqualTo("UNKNOWN");
        assertThat(projection.units().get(8).livenessHealthy()).isFalse();
    }

    @Test
    void projectsAllFourPendingDeferStatesAndIdleState() {
        Instant dueAt = NOW.plus(Duration.ofSeconds(90));
        List<UnitActivity> input = List.of(
                new UnitActivity(UnitKind.DOMAIN, "DOM-IDLE", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(10)), NOW,
                        PendingDeferState.NONE, dueAt),
                new UnitActivity(UnitKind.DOMAIN, "DOM-DEBOUNCE", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofSeconds(20)), NOW.minus(Duration.ofSeconds(20)), NOW.minus(Duration.ofHours(1)),
                        PendingDeferState.DEBOUNCING, dueAt),
                new UnitActivity(UnitKind.DOMAIN, "DOM-BUSY", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofMinutes(2)), NOW.minus(Duration.ofMinutes(2)), NOW.minus(Duration.ofHours(1)),
                        PendingDeferState.DEFERRED_SOURCE_BUSY, dueAt),
                new UnitActivity(UnitKind.DOMAIN, "DOM-BACKUP", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofMinutes(3)), NOW.minus(Duration.ofMinutes(3)), NOW.minus(Duration.ofHours(1)),
                        PendingDeferState.DEFERRED_BACKUP_WINDOW, dueAt),
                new UnitActivity(UnitKind.DOMAIN, "DOM-GUARD", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofMinutes(4)), NOW.minus(Duration.ofMinutes(4)), NOW.minus(Duration.ofHours(1)),
                        PendingDeferState.DEFERRED_WINDOW_GUARD, dueAt));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        ProjectedUnitRow idle = projection.units().get(0);
        assertThat(idle.pendingDeferState()).isEqualTo(PendingDeferState.NONE);
        assertThat(idle.pendingStatusText()).isNull();
        assertThat(idle.triggerDueAt()).isNull();

        ProjectedUnitRow debouncing = projection.units().get(1);
        assertThat(debouncing.pendingDeferState()).isEqualTo(PendingDeferState.DEBOUNCING);
        assertThat(debouncing.pendingStatusText()).contains("debouncing").contains(dueAt.toString());
        assertThat(debouncing.triggerDueAt()).isEqualTo(dueAt);

        ProjectedUnitRow sourceBusy = projection.units().get(2);
        assertThat(sourceBusy.pendingDeferState()).isEqualTo(PendingDeferState.DEFERRED_SOURCE_BUSY);
        assertThat(sourceBusy.pendingStatusText()).contains("DEFERRED_SOURCE_BUSY");

        ProjectedUnitRow backupWindow = projection.units().get(3);
        assertThat(backupWindow.pendingDeferState()).isEqualTo(PendingDeferState.DEFERRED_BACKUP_WINDOW);
        assertThat(backupWindow.pendingStatusText()).contains("DEFERRED_BACKUP_WINDOW").contains("backup window");

        ProjectedUnitRow windowGuard = projection.units().get(4);
        assertThat(windowGuard.pendingDeferState()).isEqualTo(PendingDeferState.DEFERRED_WINDOW_GUARD);
        assertThat(windowGuard.pendingStatusText()).contains("DEFERRED_WINDOW_GUARD");
    }

    @Test
    void noChangeEventsFourteenDaysRuleIsInformationalOnlyAndNeverSaysNoChanges() {
        List<UnitActivity> input = List.of(
                // 13d 23h ago -> below threshold, not flagged
                unit(UnitKind.DOMAIN, "DOM-RECENT", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofDays(14)).plus(Duration.ofHours(1))),
                // Exactly 14 days ago on healthy CONNECTED source -> informational only, never an alarm
                unit(UnitKind.DOMAIN, "DOM-EXACT-14", "checkpoint", "CONNECTED",
                        NOW.minus(Duration.ofDays(14))),
                // 21 days ago on HEARTBEAT_OK -> informational only, never an alarm
                unit(UnitKind.DEVICE_GROUP, "DG-QUIET-21", "paloalto", "HEARTBEAT_OK",
                        NOW.minus(Duration.ofDays(21))),
                // 18 days ago on DISCONNECTED -> wording must never claim "no changes"
                unit(UnitKind.DEVICE, "FW-DISCONN-18", "fortinet", "DISCONNECTED",
                        NOW.minus(Duration.ofDays(18))),
                // 15 days ago on HEARTBEAT_STALE -> wording must never claim "no changes"
                unit(UnitKind.DEVICE, "FW-STALE-15", "fortinet", "HEARTBEAT_STALE",
                        NOW.minus(Duration.ofDays(15))),
                // 30 days ago on PASSIVE_NO_HEARTBEAT -> wording must never claim "no changes"
                unit(UnitKind.DEVICE, "FW-PASSIVE-30", "cisco_asa", "PASSIVE_NO_HEARTBEAT",
                        NOW.minus(Duration.ofDays(30))));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        ProjectedUnitRow recent = projection.units().get(0);
        assertThat(recent.noChangeEventsFlag()).isFalse();
        assertThat(recent.noChangeEventsCode()).isNull();
        assertThat(recent.noChangeEventsMessage()).isNull();
        assertThat(recent.noChangeEventsAlarm()).isFalse();

        ProjectedUnitRow exact14 = projection.units().get(1);
        assertThat(exact14.noChangeEventsFlag()).isTrue();
        assertThat(exact14.noChangeEventsCode()).isEqualTo("NO_CHANGE_EVENTS_N_DAYS");
        assertThat(exact14.noChangeEventsMessage()).isEqualTo("No policy-install events received for 14 days");
        assertThat(exact14.noChangeEventsAlarm()).isFalse();
        assertThat(exact14.livenessHealthy()).isTrue();

        ProjectedUnitRow quiet21 = projection.units().get(2);
        assertThat(quiet21.noChangeEventsFlag()).isTrue();
        assertThat(quiet21.noChangeEventsCode()).isEqualTo("NO_CHANGE_EVENTS_N_DAYS");
        assertThat(quiet21.noChangeEventsMessage()).isEqualTo("No policy-install events received for 21 days");
        assertThat(quiet21.noChangeEventsAlarm()).isFalse();
        assertThat(quiet21.livenessHealthy()).isTrue();

        for (int i = 1; i < projection.units().size(); i++) {
            ProjectedUnitRow row = projection.units().get(i);
            assertThat(row.noChangeEventsFlag()).isTrue();
            assertThat(row.noChangeEventsCode()).isEqualTo("NO_CHANGE_EVENTS_N_DAYS");
            assertThat(row.noChangeEventsMessage())
                    .startsWith("No policy-install events received for ")
                    .endsWith(" days")
                    .doesNotContainIgnoringCase("no changes");
            assertThat(row.noChangeEventsAlarm()).isFalse();
        }
    }

    @Test
    void missingLastReceivedAtAndNotEnrolledUnitsNeverFabricateSilenceDuration() {
        List<UnitActivity> input = List.of(
                unit(UnitKind.DOMAIN, "DOM-NEW-CONNECTED", "checkpoint", "CONNECTED", null),
                unit(UnitKind.DEVICE_GROUP, "DG-NEW-HEARTBEAT", "paloalto", "HEARTBEAT_OK", null),
                unit(UnitKind.DEVICE, "FW-STALE-NULL", "fortinet", "HEARTBEAT_STALE", null),
                unit(UnitKind.DEVICE, "FW-PASSIVE-NULL", "cisco_asa", "PASSIVE_NO_HEARTBEAT", null),
                unit(UnitKind.CLUSTER, "CLS-NOT-ENROLLED-NULL", "cisco_asa", "NOT_ENROLLED", null),
                unit(UnitKind.DOMAIN, "DOM-NOT-ENROLLED-OLD", "checkpoint", "NOT_ENROLLED",
                        NOW.minus(Duration.ofDays(30))),
                unit(UnitKind.DEVICE, "FW-UNKNOWN-NULL", "fortinet", "UNEXPECTED_STATE", null));

        Projection projection = EventTriggerOperatorProjection.project(input, NOW, true, pseudonymizer);

        for (ProjectedUnitRow row : projection.units()) {
            assertThat(row.noChangeEventsFlag()).isFalse();
            assertThat(row.noChangeEventsCode()).isNull();
            assertThat(row.noChangeEventsMessage()).isNull();
            assertThat(row.noChangeEventsAlarm()).isFalse();
        }
    }

    @Test
    void unmaskedProjectionPreservesOperatorUnitNames() {
        UnitActivity activity = unit(UnitKind.DOMAIN, "CORP-OPS-DOMAIN", "checkpoint", "CONNECTED",
                NOW.minus(Duration.ofHours(1)));

        Projection projection = EventTriggerOperatorProjection.project(List.of(activity), NOW, false, null);

        assertThat(projection.masked()).isFalse();
        assertThat(projection.units().getFirst().displayName()).isEqualTo("CORP-OPS-DOMAIN");
    }

    private static UnitActivity unit(
            UnitKind kind,
            String rawName,
            String vendor,
            String liveness,
            Instant lastReceivedAt) {
        return new UnitActivity(
                kind,
                rawName,
                vendor,
                liveness,
                lastReceivedAt,
                lastReceivedAt,
                NOW.minus(Duration.ofHours(2)),
                PendingDeferState.NONE,
                null);
    }
}
