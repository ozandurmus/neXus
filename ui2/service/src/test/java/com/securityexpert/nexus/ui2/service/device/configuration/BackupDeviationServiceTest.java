package com.securityexpert.nexus.ui2.service.device.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService.DeviceClass;
import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService.NotComparedReason;
import com.securityexpert.nexus.ui2.service.device.configuration.MajorDeviationRules.Category;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Backups "Active major deviations" from stored configuration runs (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md):
 * the per-vendor categorisation, the window, the not-comparable reasons and the masked view. Synthetic names only.
 */
class BackupDeviationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeDeviceConfigurationRepository repository = new FakeDeviceConfigurationRepository();
    private final List<DeviceSummaryRecord> inventory = new ArrayList<>();
    private int sequence;

    private void run(String deviceId, String vendor, Duration ago, String changeState,
            Optional<ConfigurationDeviationSummary> summary) {
        String readKind = "palo_alto".equals(vendor) ? "effective_running" : "show_configuration";
        repository.recordRun(new ConfigurationRun("run-" + (++sequence), deviceId, "job-" + sequence, NOW.minus(ago), vendor,
                readKind, true, "h" + sequence, "r" + sequence, 1L, "art-" + sequence, 0, Optional.empty(), changeState,
                List.of(), List.of(), summary), null, "test", "test");
    }

    private static Optional<ConfigurationDeviationSummary> computed(ConfigurationDeviationEntry... entries) {
        return Optional.of(new ConfigurationDeviationSummary(ConfigurationDeviationSummary.Status.COMPUTED, List.of(entries)));
    }

    private static ConfigurationDeviationEntry entry(String context, String section, DeviationKind kind) {
        return new ConfigurationDeviationEntry(context, section, Optional.empty(), kind, 2, kind == DeviationKind.MODIFIED ? 2 : 3);
    }

    private void device(String deviceId, String vendor, String hostname, String clusterRef) {
        inventory.add(new DeviceSummaryRecord(deviceId, "gateway", vendor, DeviceEnrollmentState.ENROLLED,
                Optional.ofNullable(hostname), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(clusterRef), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
    }

    private BackupDeviationService.Result evaluate() {
        return new BackupDeviationService(repository, () -> inventory, CLOCK).evaluate();
    }

    @Test
    void checkPointInterfaceAndRouteChangesAreMajorAndObjectOrNtpChangesAreMinor() {
        run("cp-a", "check_point", Duration.ofDays(3), ChangeState.CHANGED,
                computed(entry("physical", "interface", DeviationKind.MODIFIED), entry("physical", "ntp", DeviationKind.RECOUNTED)));
        run("cp-a", "check_point", Duration.ofDays(1), ChangeState.CHANGED, computed(entry("physical", "static-route", DeviationKind.RECOUNTED)));
        run("cp-a", "check_point", Duration.ofHours(2), ChangeState.CHANGED, computed(entry("physical", "interface", DeviationKind.RECOUNTED)));
        run("cp-b", "check_point", Duration.ofDays(1), ChangeState.CHANGED, computed(entry("physical", "ntp", DeviationKind.RECOUNTED)));
        // Changed with no entry: every section kept its count (a run recorded before MODIFIED existed) -- minor.
        run("cp-c", "check_point", Duration.ofDays(1), ChangeState.CHANGED, computed());
        run("cp-d", "check_point", Duration.ofDays(1), ChangeState.UNCHANGED, Optional.empty());

        BackupDeviationService.Result result = evaluate();

        assertThat(result.compared()).hasSize(4);
        assertThat(result.count(DeviceClass.MAJOR)).isEqualTo(1);
        assertThat(result.count(DeviceClass.MINOR)).isEqualTo(2);
        assertThat(result.count(DeviceClass.NONE)).isEqualTo(1);
        assertThat(result.activeMajor()).extracting(BackupDeviationService.MajorDeviation::category)
                .containsExactly(Category.INTERFACES, Category.ROUTING);
        BackupDeviationService.MajorDeviation interfaces = result.activeMajor().get(0);
        assertThat(interfaces.deviceId()).isEqualTo("cp-a");
        assertThat(interfaces.count()).isEqualTo(2);
        assertThat(interfaces.changedAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(interfaces.sections()).containsExactly("interface");
    }

    @Test
    void paloAltoCategoriesFollowTheContextAndSharedObjectRecountsAreMinor() {
        run("pan-a", "palo_alto", Duration.ofDays(2), ChangeState.CHANGED, computed(entry("mgt-config", "users", DeviationKind.RECOUNTED)));
        run("pan-b", "palo_alto", Duration.ofDays(2), ChangeState.CHANGED,
                computed(entry("shared", "address", DeviationKind.RECOUNTED), entry("vsys1", "service", DeviationKind.RECOUNTED)));
        run("pan-c", "palo_alto", Duration.ofDays(2), ChangeState.CHANGED,
                computed(entry("network", "virtual-router", DeviationKind.RECOUNTED), entry("vsys2", "zone", DeviationKind.ADDED)));
        // "users" outside mgt-config is not the admin list.
        run("pan-d", "palo_alto", Duration.ofDays(2), ChangeState.CHANGED, computed(entry("vsys1", "users", DeviationKind.RECOUNTED)));

        BackupDeviationService.Result result = evaluate();

        assertThat(result.compared()).hasSize(4);
        assertThat(result.activeMajor()).extracting(m -> m.deviceId() + ":" + m.category().wire())
                .containsExactlyInAnyOrder("pan-a:admin_access", "pan-c:routing", "pan-c:interfaces");
        assertThat(result.compared()).filteredOn(d -> d.deviceId().equals("pan-b")).singleElement()
                .extracting(BackupDeviationService.DeviceDeviation::deviceClass).isEqualTo(DeviceClass.MINOR);
    }

    @Test
    void devicesWithFewerThanTwoSnapshotsOrOutsideTheWindowOrUncategorisedAreNotCompared() {
        run("first-only", "check_point", Duration.ofDays(1), ChangeState.FIRST_RUN, Optional.empty());
        run("stale", "check_point", Duration.ofDays(9), ChangeState.CHANGED, computed(entry("physical", "interface", DeviationKind.RECOUNTED)));
        run("proxy", "bluecoat", Duration.ofDays(1), ChangeState.CHANGED, computed(entry("single", "Networking", DeviationKind.RECOUNTED)));
        run("no-index", "check_point", Duration.ofDays(1), ChangeState.CHANGED,
                Optional.of(ConfigurationDeviationSummary.notComputable()));
        run("ok", "check_point", Duration.ofDays(1), ChangeState.UNCHANGED, Optional.empty());

        BackupDeviationService.Result result = evaluate();

        assertThat(result.devicesKnown()).isEqualTo(5);
        assertThat(result.compared()).extracting(BackupDeviationService.DeviceDeviation::deviceId).containsExactly("ok");
        assertThat(result.notCompared()).containsEntry(NotComparedReason.FIRST_READ_ONLY, 1)
                .containsEntry(NotComparedReason.NO_READ_IN_WINDOW, 1)
                .containsEntry(NotComparedReason.VENDOR_NOT_CATEGORISED, 1)
                .containsEntry(NotComparedReason.NOT_COMPUTABLE, 1);
        assertThat(result.activeMajor()).isEmpty();
    }

    @Test
    void aMajorChangeStaysActiveAfterALaterUnchangedReadWithinTheWindow() {
        run("cp-a", "check_point", Duration.ofDays(4), ChangeState.CHANGED, computed(entry("physical", "cluster", DeviationKind.RECOUNTED)));
        run("cp-a", "check_point", Duration.ofDays(3), ChangeState.UNCHANGED, Optional.empty());
        run("cp-a", "check_point", Duration.ofDays(2), ChangeState.UNCHANGED, Optional.empty());

        BackupDeviationService.Result result = evaluate();

        assertThat(result.activeMajor()).singleElement().satisfies(m -> {
            assertThat(m.category()).isEqualTo(Category.HIGH_AVAILABILITY);
            assertThat(m.changedAt()).isEqualTo(NOW.minus(Duration.ofDays(4)));
        });
        assertThat(result.compared().get(0).lastComparedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    @Test
    void asaAndFortiGateSectionsAreCategorised() {
        assertThat(MajorDeviationRules.categoryOf("cisco_asa", "single", "Access List")).contains(Category.ACCESS_RULES);
        assertThat(MajorDeviationRules.categoryOf("cisco_asa", "single", "Nat")).contains(Category.NAT);
        assertThat(MajorDeviationRules.categoryOf("cisco_asa", "single", "Object Network")).isEmpty();
        assertThat(MajorDeviationRules.categoryOf("fortinet", "vdom-a", "firewall policy")).contains(Category.ACCESS_RULES);
        assertThat(MajorDeviationRules.categoryOf("fortinet", "global", "system ha")).contains(Category.HIGH_AVAILABILITY);
        assertThat(MajorDeviationRules.categoryOf("fortinet", "vdom-a", "firewall address")).isEmpty();
        assertThat(MajorDeviationRules.categoryOf("bluecoat", "single", "Networking")).isEmpty();
        assertThat(MajorDeviationRules.categorises("bluecoat")).isFalse();
    }

    @Test
    void theBodyCountsDevicesAndTheReplayViewerGetsTheDevicesScreenPseudonym() {
        device("cp-a", "check_point", "synthetic-gw-north", "synthetic-cluster-1");
        run("cp-a", "check_point", Duration.ofDays(1), ChangeState.CHANGED, computed(entry("physical", "static-route", DeviationKind.RECOUNTED)));
        run("cp-b", "check_point", Duration.ofDays(1), ChangeState.UNCHANGED, Optional.empty());
        run("cp-c", "check_point", Duration.ofDays(1), ChangeState.FIRST_RUN, Optional.empty());

        Map<String, Object> body = com.securityexpert.nexus.ui2.service.api.BackupDeviationController.toBody(evaluate());

        assertThat(body).containsEntry("total_deviations_checked", 2).containsEntry("devices_known", 3)
                .containsEntry("major_devices", 1L).containsEntry("window_days", 7L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("active_major_deviations");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("device_id", "cp-a").containsEntry("hostname", "synthetic-gw-north")
                    .containsEntry("category", "routing").containsEntry("count", 1)
                    .containsEntry("changed_at", "2026-10-22T06:00:00Z");
            assertThat(row.get("sections")).isEqualTo(List.of("static-route"));
        });

        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(key);
        PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) advice.maskObject(body, null);
        String expected = new TopologyNamePseudonymizer(key).maskDeviceName("synthetic-gw-north", "synthetic-cluster-1", "cp-a");
        @SuppressWarnings("unchecked")
        Map<String, Object> maskedRow = ((List<Map<String, Object>>) masked.get("active_major_deviations")).get(0);
        assertThat(maskedRow).containsEntry("hostname", expected).containsEntry("device_id", "cp-a")
                .containsEntry("category", "routing");
        assertThat(masked.toString()).doesNotContain("synthetic-gw-north", "synthetic-cluster-1");
    }
}
