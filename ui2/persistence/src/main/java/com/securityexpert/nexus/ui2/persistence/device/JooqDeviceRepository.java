package com.securityexpert.nexus.ui2.persistence.device;
// 14I MS-1

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link DeviceRepository}. Every mutation carries the audit
 * context (F3) through {@link AuditedTransactionBoundary}: {@code devices}
 * and {@code endpoints} are both audited on every INSERT/UPDATE/DELETE
 * (V1's {@code trg_audit_devices}/{@code trg_audit_endpoints}).
 */
public final class JooqDeviceRepository implements DeviceRepository {

    private static final String DEVICE_COLUMNS = "device_id, role, vendor_hint, registration_source, created_at, "
            + "is_test_target, enrollment_state, disabled, credential_reference_id, backup_target";
    private static final String ENDPOINT_COLUMNS = "endpoint_id, device_id, transport_kind, address_ref, created_at";
    private static final String CONFIRM_FACT_COLUMNS = "observed_hostname, observed_model, observed_software_version, "
            + "observed_ha_role, recorded_identity_primary, recorded_identity_secondary, identity_mismatch_state, "
            + "identity_mismatch_presented_primary, identity_mismatch_presented_secondary, cluster_member_ref, "
            + "virtual_system_ref, peer_follow_outcome, peer_follow_reason";
    private static final String DEVICE_SUMMARY_SELECT =
            "select d.device_id, d.role, d.vendor_hint, d.enrollment_state, d.backup_target, "
            + "coalesce(d.observed_hostname, dc.display_name) as observed_hostname, "
            + "coalesce(d.observed_model, dc.model, c_parent.model) as observed_model, "
            + "coalesce(d.observed_software_version, dc.software_version, c_parent.software_version) as observed_software_version, "
            + "coalesce(d.observed_ha_role, inv_ha.role) as observed_ha_role, "
            + "coalesce(c_parent.display_name, d.cluster_member_ref, dc.cluster_reference) as cluster_member_ref, "
            + "coalesce(vs_info.virtual_systems, inv_run.virtual_systems) as virtual_systems, "
            + "latest_job.state as latest_job_state, "
            + "latest_job.job_type as latest_job_type, "
            + "latest_job.terminal_reason as latest_job_terminal_reason, "
            + "ep.address_ref as management_ip, "
            + "if_ips.interface_ips as ip_addresses "
            + "from devices d "
            + "left join lateral ( "
            + "    select display_name, model, software_version, cluster_reference, parent_candidate_id, candidate_id from discovery_candidate c "
            + "    where ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d.discovery_match_key "
            + "       or (c.vendor || '|' || c.stable_identifier) = d.discovery_match_key "
            + "       or c.stable_identifier = d.recorded_identity_primary) "
            + "    order by (c.parent_candidate_id is null) desc "
            + "    limit 1 "
            + ") dc on true "
            + "left join lateral ( "
            + "    select display_name, model, software_version from discovery_candidate c2 "
            + "    where c2.candidate_id = dc.parent_candidate_id "
            + "       or c2.stable_identifier = coalesce(d.cluster_member_ref, dc.cluster_reference) "
            + "    limit 1 "
            + ") c_parent on true "
            + "left join lateral ( "
            + "    select string_agg(distinct substring(c_vs.display_name from length(dc.display_name) + 2), ', ' order by substring(c_vs.display_name from length(dc.display_name) + 2)) as virtual_systems "
            + "    from discovery_candidate c_vs "
            + "    where dc.display_name is not null and c_vs.display_name like dc.display_name || '_%' "
            + ") vs_info on true "
            + "left join lateral ( "
            + "    select virtual_systems from device_inventory_run dir where dir.device_id = d.device_id order by collected_at desc limit 1 "
            + ") inv_run on true "
            // The HA role the last inventory run observed on the physical context (cphaprob stat / show
            // high-availability state): the first-contact read often has none, the inventory always does.
            + "left join lateral ( "
            + "    select h.role from device_inventory_run r2 join device_inventory_ha h on h.run_id = r2.run_id "
            + "    where r2.device_id = d.device_id and h.context = 'physical' order by r2.collected_at desc limit 1 "
            + ") inv_ha on true "
            + "left join lateral ( "
            + "    select address_ref from endpoints ep where ep.device_id = d.device_id order by ep.created_at asc limit 1 "
            + ") ep on true "
            + "left join lateral ( "
            + "    select state, job_type, terminal_reason from jobs j where j.target_device_id = d.device_id order by j.submitted_at desc limit 1 "
            + ") latest_job on true "
            + "left join lateral ( "
            + "    select string_agg(distinct dia.address, ' ') as interface_ips "
            + "    from (select run_id from device_inventory_run r where r.device_id = d.device_id order by r.collected_at desc limit 1) latest_r "
            + "    join device_interface di on di.run_id = latest_r.run_id "
            + "    join device_interface_address dia on dia.interface_id = di.interface_id "
            + ") if_ips on true ";

    private static final String DEVICE_SUMMARY_QUERY = DEVICE_SUMMARY_SELECT + "order by d.created_at desc, d.device_id";

    private static final String FIND_MEMBERS_BY_CLUSTER_QUERY = DEVICE_SUMMARY_SELECT
            + "where coalesce(c_parent.display_name, d.cluster_member_ref, dc.cluster_reference) = {0} "
            + "order by d.created_at desc, d.device_id";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqDeviceRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<DeviceRecord> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + DEVICE_COLUMNS + " from devices where device_id = {0}",
                    deviceId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toDeviceRecord);
        });
    }

    @Override
    public Optional<EndpointRecord> findEndpoint(String endpointId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + ENDPOINT_COLUMNS + " from endpoints where endpoint_id = {0}",
                    endpointId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toEndpointRecord);
        });
    }

    @Override
    public Optional<String> findDeviceIdByEndpointAddress(String addressRef) {
        if (addressRef == null || addressRef.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select device_id from endpoints where lower(trim(address_ref)) = lower({0}) order by created_at asc limit 1",
                        addressRef.strip())
                .stream().findFirst().map(row -> row.get("device_id", String.class)));
    }

    @Override
    public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select " + ENDPOINT_COLUMNS + " from endpoints where device_id = {0} order by created_at asc limit 1",
                    deviceId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toEndpointRecord);
        });
    }

    @Override
    public boolean refreshObservedFacts(String deviceId, Optional<String> hostname, Optional<String> model,
            Optional<String> softwareVersion, String actorFingerprint, String actionId) {
        if (hostname.isEmpty() && model.isEmpty() && softwareVersion.isEmpty()) {
            return false;
        }
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> dsl.execute(
                "update devices set observed_hostname = coalesce({1}, observed_hostname), "
                        + "observed_model = coalesce({2}, observed_model), "
                        + "observed_software_version = coalesce({3}, observed_software_version) "
                        + "where device_id = {0} and (observed_hostname is distinct from coalesce({1}, observed_hostname) "
                        + "or observed_model is distinct from coalesce({2}, observed_model) "
                        + "or observed_software_version is distinct from coalesce({3}, observed_software_version))",
                deviceId, hostname.orElse(null), model.orElse(null), softwareVersion.orElse(null))) == 1;
    }

    @Override
    public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> {
            Timestamp now = Timestamp.from(Instant.now());
            dsl.execute("insert into devices(device_id, role, vendor_hint, registration_source, created_at, "
                    + "is_test_target, enrollment_state, disabled, credential_reference_id, "
                    + "cluster_member_ref, virtual_system_ref, discovery_match_key, "
                    + "identity_mismatch_state, peer_follow_outcome) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, 'DRAFT', false, {6}, {7}, {8}, {9}, 'NONE', 'NONE')",
                    draft.deviceId(), draft.role(), draft.vendorHint(), draft.registrationSource(), now, draft.isTestTarget(),
                    draft.credentialReferenceId(), draft.clusterMemberRef().orElse(null),
                    draft.virtualSystemRef().orElse(null), draft.discoveryMatchKey().orElse(null));
            dsl.execute("insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                    + "values ({0}, {1}, {2}, {3}, {4})",
                    draft.endpointId(), draft.deviceId(), draft.transportKind(), draft.addressRef(), now);
            return draft.deviceId();
        });
    }

    @Override
    public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
            DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set enrollment_state = {0} where device_id = {1} and enrollment_state = {2}",
                toState.name(), deviceId, fromState.name()));
        return updated == 1;
    }

    @Override
    public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
        int deleted = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "delete from devices where device_id = {0} and enrollment_state = 'DRAFT'", deviceId));
        return deleted == 1;
    }

    @Override
    public DeleteResult deleteDevice(String deviceId, BackupDisposition backupDisposition,
            String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            long backupArtefactCount = ((Number) dsl.fetchValue(
                    "select count(*) from backup_artefact where device_id = {0}", deviceId)).longValue();
            if (backupArtefactCount > 0 && backupDisposition == null) {
                return new DeleteResult(false, backupArtefactCount, true);
            }

            dsl.execute("update jobs set reconciliation_ref = null where target_device_id = {0}", deviceId);
            dsl.execute("delete from job_step_attempt where job_id in (select job_id from jobs where target_device_id = {0})", deviceId);
            dsl.execute("delete from job_steps where job_id in (select job_id from jobs where target_device_id = {0})", deviceId);
            dsl.execute("delete from job_reconciliation where job_id in (select job_id from jobs where target_device_id = {0})", deviceId);

            dsl.execute("delete from device_interface_address where interface_id in ("
                    + "select interface_id from device_interface where run_id in ("
                    + "select run_id from device_inventory_run where device_id = {0}))", deviceId);
            dsl.execute("delete from device_interface where run_id in ("
                    + "select run_id from device_inventory_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_route where run_id in ("
                    + "select run_id from device_inventory_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_inventory_ha where run_id in ("
                    + "select run_id from device_inventory_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_inventory_run where device_id = {0}", deviceId);

            dsl.execute("delete from configuration_run_deviation_entry where summary_id in ("
                    + "select summary_id from configuration_run_deviation_summary where run_id in ("
                    + "select run_id from device_configuration_run where device_id = {0}))", deviceId);
            dsl.execute("delete from configuration_run_deviation_summary where run_id in ("
                    + "select run_id from device_configuration_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_configuration_index where run_id in ("
                    + "select run_id from device_configuration_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_configuration_override where run_id in ("
                    + "select run_id from device_configuration_run where device_id = {0})", deviceId);
            dsl.execute("delete from configuration_notification where device_id = {0} or run_id in ("
                    + "select run_id from device_configuration_run where device_id = {0})", deviceId);
            dsl.execute("delete from device_configuration_run where device_id = {0}", deviceId);

            dsl.execute("delete from configuration_artefact where device_id = {0}", deviceId);

            if (backupDisposition == BackupDisposition.REMOVE) {
                dsl.execute("delete from backup_artefact where device_id = {0}", deviceId);
            }
            dsl.execute("delete from backup_endpoint_ineligibility where device_id = {0}", deviceId);

            dsl.execute("delete from cp_inventory_projection where device_id = {0}", deviceId);
            dsl.execute("delete from jobs where target_device_id = {0}", deviceId);
            dsl.execute("delete from endpoints where device_id = {0}", deviceId);
            boolean deleted = dsl.execute("delete from devices where device_id = {0}", deviceId) == 1;
            return new DeleteResult(deleted, backupArtefactCount, false);
        });
    }

    @Override
    public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set disabled = {0} where device_id = {1} and disabled <> {0}", disabled, deviceId));
        return updated == 1;
    }

    @Override
    public boolean setCredentialReference(String deviceId, String credentialReferenceId, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set credential_reference_id = {0} where device_id = {1} and credential_reference_id <> {0}",
                credentialReferenceId, deviceId));
        return updated == 1;
    }

    @Override
    public boolean setBackupTarget(String deviceId, boolean backupTarget, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set backup_target = {0} where device_id = {1} and backup_target <> {0}",
                backupTarget, deviceId));
        return updated == 1;
    }

    @Override
    public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
            String actionId) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set "
                        + "enrollment_state = 'ENROLLED', "
                        + "observed_hostname = {0}, observed_model = {1}, observed_software_version = {2}, "
                        + "observed_ha_role = {3}, "
                        + "recorded_identity_primary = {4}, recorded_identity_secondary = {5}, "
                        + "recorded_identity_recorded_at = {6}, "
                        + "identity_mismatch_state = {7}, identity_mismatch_presented_primary = {8}, "
                        + "identity_mismatch_presented_secondary = {9}, "
                        + "identity_mismatch_detected_at = case when {7} = 'OPEN' then {6} else null end, "
                        + "cluster_member_ref = {10}, virtual_system_ref = {11}, "
                        + "peer_follow_outcome = {12}, peer_follow_reason = {13} "
                        + "where device_id = {14} and enrollment_state = 'DRAFT'",
                facts.observedHostname().orElse(null), facts.observedModel().orElse(null),
                facts.observedSoftwareVersion().orElse(null), facts.observedHaRole().orElse(null),
                facts.recordedIdentityPrimary().orElse(null), facts.recordedIdentitySecondary().orElse(null), now,
                facts.identityMismatchState(), facts.identityMismatchPresentedPrimary().orElse(null),
                facts.identityMismatchPresentedSecondary().orElse(null), facts.clusterMemberRef().orElse(null),
                facts.virtualSystemRef().orElse(null), facts.peerFollowOutcome(), facts.peerFollowReason().orElse(null),
                deviceId));
        return updated == 1;
    }

    @Override
    public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + CONFIRM_FACT_COLUMNS + " from devices where device_id = {0}",
                    deviceId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toConfirmFacts);
        });
    }

    @Override
    public List<DeviceSummaryRecord> listAll() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(DEVICE_SUMMARY_QUERY)
                .stream()
                .map(JooqDeviceRepository::toSummaryRecord)
                .toList());
    }

    @Override
    public List<DeviceSummaryRecord> findMembersByClusterRef(String clusterMemberRef) {
        if (clusterMemberRef == null || clusterMemberRef.isBlank()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(FIND_MEMBERS_BY_CLUSTER_QUERY, clusterMemberRef)
                .stream()
                .map(JooqDeviceRepository::toSummaryRecord)
                .toList());
    }

    private static boolean backupTargetOf(Record row) {
        return row.field("backup_target") != null && Boolean.TRUE.equals(row.get("backup_target", Boolean.class));
    }

    private static DeviceRecord toDeviceRecord(Record row) {
        return new DeviceRecord(
                row.get("device_id", String.class),
                row.get("role", String.class),
                row.get("vendor_hint", String.class),
                row.get("registration_source", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("is_test_target", Boolean.class),
                DeviceEnrollmentState.fromColumnValue(row.get("enrollment_state", String.class)),
                row.get("disabled", Boolean.class),
                row.get("credential_reference_id", String.class),
                backupTargetOf(row));
    }

    private static DeviceConfirmFacts toConfirmFacts(Record row) {
        return new DeviceConfirmFacts(
                Optional.ofNullable(row.get("observed_hostname", String.class)),
                Optional.ofNullable(row.get("observed_model", String.class)),
                Optional.ofNullable(row.get("observed_software_version", String.class)),
                Optional.ofNullable(row.get("observed_ha_role", String.class)),
                Optional.ofNullable(row.get("recorded_identity_primary", String.class)),
                Optional.ofNullable(row.get("recorded_identity_secondary", String.class)),
                row.get("identity_mismatch_state", String.class),
                Optional.ofNullable(row.get("identity_mismatch_presented_primary", String.class)),
                Optional.ofNullable(row.get("identity_mismatch_presented_secondary", String.class)),
                Optional.ofNullable(row.get("cluster_member_ref", String.class)),
                Optional.ofNullable(row.get("virtual_system_ref", String.class)),
                row.get("peer_follow_outcome", String.class),
                Optional.ofNullable(row.get("peer_follow_reason", String.class)));
    }

    private static DeviceSummaryRecord toSummaryRecord(Record row) {
        return new DeviceSummaryRecord(
                row.get("device_id", String.class),
                row.get("role", String.class),
                row.get("vendor_hint", String.class),
                DeviceEnrollmentState.fromColumnValue(row.get("enrollment_state", String.class)),
                Optional.ofNullable(row.get("observed_hostname", String.class)),
                Optional.ofNullable(row.get("observed_model", String.class)),
                Optional.ofNullable(row.get("observed_software_version", String.class)),
                Optional.ofNullable(row.get("observed_ha_role", String.class)),
                Optional.ofNullable(row.get("cluster_member_ref", String.class)),
                Optional.ofNullable(row.get("latest_job_state", String.class)),
                Optional.ofNullable(row.get("latest_job_type", String.class)),
                Optional.ofNullable(row.get("latest_job_terminal_reason", String.class)),
                Optional.ofNullable(row.get("virtual_systems", String.class)),
                Optional.ofNullable(row.get("management_ip", String.class)),
                Optional.ofNullable(row.get("ip_addresses", String.class)),
                backupTargetOf(row));
    }

    private static EndpointRecord toEndpointRecord(Record row) {
        return new EndpointRecord(
                row.get("endpoint_id", String.class),
                row.get("device_id", String.class),
                row.get("transport_kind", String.class),
                row.get("address_ref", String.class),
                row.get("created_at", Timestamp.class).toInstant());
    }
}
