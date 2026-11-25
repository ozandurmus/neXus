package com.securityexpert.nexus.ui2.integration.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;

/**
 * The synthetic estate of {@code AiviewNonGetRoutesSweepTest} (backlog {@code aiview_masking_leak_audit}): the same
 * shape and the same raw-value classes the GET sweep seeds (devices, a cluster, addresses, serials, an administrator, a
 * policy snapshot with rule history, a discovery run, a failed job, a static-route preview, a Zone Matrix ACTIVE and
 * DRAFT version, an ACI cutover segment with an assessment, a Cisco ASA pair with stored configurations, a stored
 * system-log line), copied here so the two sweeps do not share a class and can change independently. Every value is
 * synthetic and generated per run; the counting helper reports a value <em>class</em> and a number, never a value.
 */
public final class AiviewSweepEstate {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a seeded value is; the only thing about a value this test ever prints. */
    public enum ValueClass { HOSTNAME, CLUSTER_NAME, ADDRESS, SERIAL, PRINCIPAL, RULE_NAME, OBJECT_NAME, POLICY_NAME, ZONE_OR_SEGMENT_NAME }

    private static Ui2PostgresFixture fixture;
    private static ArtefactStore store;

    private AiviewSweepEstate() {
    }

    /** Seeds {@code estate} into the fixture's database; the artefact store carries the encrypted log and configuration texts. */
    public static void seedInto(Ui2PostgresFixture target, ArtefactStore artefacts, Estate estate) throws Exception {
        fixture = target;
        store = artefacts;
        seed(estate);
    }

    // ------------------------------------------------------------------ the synthetic estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A token with at least one letter, so it can never equal a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    private static String digits(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    public record Device(String id, String vendor, String role, String hostname, String serial, String managementAddress,
            String interfaceAddress, String cluster) {
    }

    /** One unit of the Cisco ASA failover pair: an enrolled gateway with its own management address and serial. */
    public record AsaUnit(String id, String hostname, String serial, String managementAddress, String haRole) {
    }

    /**
     * The P1 planes of the estate: a zone model (Zone Matrix), an ACI cutover segment with one assessment, and a Cisco
     * ASA failover pair with stored running configurations. Blocks are written as CIDR text; the network address of
     * each block is what the sweep counts (it occurs in every spelling of the block).
     */
    public record P1(String zoneNameA, String zoneNameB, String zoneDescription, String zoneV4A, String zoneV4B, String zoneV6A,
            String zoneV6B, String cellRationale, String regulationReference, String segmentName, String segmentV4,
            String segmentV6, String peerV4, String flowSource, String flowDestination, String missingRuleSource,
            String flowFirstSeen, String asaCluster, AsaUnit asaA, AsaUnit asaB, String asaNameif, String asaInterfaceAddress,
            String asaObjectName, String asaObjectAddress, String asaAclName, String disabledRuleName) {

        public static P1 generate(String principal, String hostname, int base, Map<String, ValueClass> raw) {
            int zoneA = 16 + 4 * RANDOM.nextInt(8);    // 203.0.113.16 .. .44, /30
            int zoneB = 64 + 4 * RANDOM.nextInt(8);    // 203.0.113.64 .. .92, /30
            int segment = 128 + 8 * RANDOM.nextInt(7); // 203.0.113.128 .. .176, /29
            int peer = 192 + 8 * RANDOM.nextInt(7);    // 203.0.113.192 .. .240, /29
            String v6a = "2001:db8:" + hex16() + ":" + hex16() + "::";
            String v6b = "2001:db8:" + hex16() + ":" + hex16() + "::";
            String v6segment = "2001:db8:" + hex16() + "::";
            AsaUnit a = new AsaUnit(Ui2Rows.opaqueId("dev"), "asa" + tag() + "-edge-a", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 4), "active");
            AsaUnit b = new AsaUnit(Ui2Rows.opaqueId("dev"), "asa" + tag() + "-edge-b", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 5), "standby");
            P1 p = new P1("zn-" + tag() + "-cardholder", "zn-" + tag() + "-corebank",
                    "kept by " + principal + " on " + hostname,
                    "203.0.113." + zoneA, "203.0.113." + zoneB, v6a, v6b,
                    "requested by " + principal + " for " + hostname, "REG-" + tag() + " signed by " + principal,
                    "seg-" + tag() + "-swift", "203.0.113." + segment, v6segment, "203.0.113." + peer,
                    "203.0.113." + (segment + 2), "203.0.113." + (peer + 3), "203.0.113." + (segment + 5),
                    String.format(Locale.ROOT, "2026-11-19T%02d:%02d:%02dZ", RANDOM.nextInt(24), RANDOM.nextInt(60),
                            1 + RANDOM.nextInt(59)),
                    "asa-ha-" + tag() + "-dc", a, b, "nif" + tag() + "-outside", "198.51.100." + (base + 7),
                    "obj-" + tag() + "-paysrv", "198.51.100." + (base + 8), "ACL-" + tag() + "-EXT",
                    "deny-" + tag() + "-legacy");
            raw.put(p.zoneNameA(), ValueClass.ZONE_OR_SEGMENT_NAME);
            raw.put(p.zoneNameB(), ValueClass.ZONE_OR_SEGMENT_NAME);
            raw.put(p.segmentName(), ValueClass.ZONE_OR_SEGMENT_NAME);
            for (String address : List.of(p.zoneV4A(), p.zoneV4B(), p.zoneV6A(), p.zoneV6B(), p.segmentV4(), p.segmentV6(),
                    p.peerV4(), p.flowSource(), p.flowDestination(), p.missingRuleSource(), a.managementAddress(),
                    b.managementAddress(), p.asaInterfaceAddress(), p.asaObjectAddress())) {
                raw.put(address, ValueClass.ADDRESS);
            }
            raw.put(p.asaCluster(), ValueClass.CLUSTER_NAME);
            for (AsaUnit u : List.of(a, b)) {
                raw.put(u.hostname(), ValueClass.HOSTNAME);
                raw.put(u.serial(), ValueClass.SERIAL);
            }
            raw.put(p.asaNameif(), ValueClass.OBJECT_NAME);
            raw.put(p.asaObjectName(), ValueClass.OBJECT_NAME);
            raw.put(p.asaAclName(), ValueClass.RULE_NAME);
            raw.put(p.disabledRuleName(), ValueClass.RULE_NAME);
            return p;
        }

        public List<AsaUnit> asaUnits() {
            return List.of(asaA, asaB);
        }
    }

    private static String hex16() {
        return Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff));
    }

    /** Every raw value the sweep looks for, generated per run. */
    public record Estate(Device cpMemberA, Device cpMemberB, Device panFirewall, Device management, String cluster,
            String ipv6Management, String principal, String ruleName, String sectionName, String objectName,
            String objectAddress, String policyName, String policyId, String domainId, String objectId, String ruleId,
            P1 p1, Map<String, ValueClass> raw) {

        public static Estate generate() {
            String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            int base = 100 + RANDOM.nextInt(90);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "FW-TANGO-" + tag().toUpperCase(Locale.ROOT), "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + base, "198.51.100." + base, cluster);
            Device b = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist" + tag() + "-fw-02", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 1), "198.51.100." + (base + 1), cluster);
            Device pan = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "dc" + tag() + "-edge-gw01", "0" + digits(11),
                    "192.0.2." + (base + 2), "198.51.100." + (base + 2), null);
            Device mgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "core" + tag() + "-smc-a", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 3), null, null);
            String v6 = "2001:db8:" + Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff)) + "::"
                    + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String principal = "adm" + tag() + ".ops";
            String ruleName = "allow-" + tag() + "-payroll";
            String sectionName = "sec-" + tag() + "-dmz";
            String objectName = "h-" + tag() + "-dbsrv";
            String objectAddress = "198.51.100." + (base + 5);
            String policyName = "pkg-" + tag() + "-branch";
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(a, b, pan, mgmt)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.managementAddress(), ValueClass.ADDRESS);
                if (d.interfaceAddress() != null) raw.put(d.interfaceAddress(), ValueClass.ADDRESS);
            }
            raw.put(cluster, ValueClass.CLUSTER_NAME);
            raw.put(v6, ValueClass.ADDRESS);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(ruleName, ValueClass.RULE_NAME);
            raw.put(sectionName, ValueClass.RULE_NAME);
            raw.put(objectName, ValueClass.OBJECT_NAME);
            raw.put(objectAddress, ValueClass.ADDRESS);
            raw.put(policyName, ValueClass.POLICY_NAME);
            // the static-route preview's typed destination and its representative host (documentation range)
            raw.put("203.0.113.0", ValueClass.ADDRESS);
            raw.put("203.0.113.10", ValueClass.ADDRESS);
            P1 p1 = P1.generate(principal, pan.hostname(), base, raw);
            return new Estate(a, b, pan, mgmt, cluster, v6, principal, ruleName, sectionName, objectName, objectAddress,
                    policyName, "policy-" + UUID.randomUUID(), "domain-" + UUID.randomUUID(),
                    "obj-" + UUID.randomUUID(), "rule-" + UUID.randomUUID(), p1, Map.copyOf(raw));
        }

        public List<Device> devices() {
            return List.of(cpMemberA, cpMemberB, panFirewall, management);
        }
    }

    /** Seeding ids the sweep substitutes for path variables. */
    public static final Map<String, String> ids = new TreeMap<>();

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, () -> {
                for (Device d : e.devices()) {
                    exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                            + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                            + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                            + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                            + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', ?, ?, "
                            + "'synthetic-model', 'R81.20', ?, ?, now(), ?, true, ?)",
                            d.id(), d.vendor(), credential, d.role(), d.cluster() == null ? "NONE" : "CORROBORATED",
                            d.hostname(), d.cluster() == null ? null : "active", d.serial(), d.cluster(),
                            d.vendor() + "|" + d.serial());
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                            Ui2Rows.opaqueId("ep"), d.id(), d.managementAddress());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }
                // The management server's second endpoint is IPv6.
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                        + "values (?, ?, 'https', ?, now() + interval '1 second')",
                        Ui2Rows.opaqueId("ep"), e.management().id(), e.ipv6Management());
                exec(c, "insert into device_policy_install(device_id, policy_name, installed_at_text, source_read) "
                        + "values (?, ?, 'synthetic', 'synthetic_seed')", e.cpMemberA().id(), e.policyName());

                // A discovery run on the management server: the candidates carry hostnames, serials and addresses.
                String discoveryJob = job(c, null, "discovery", "COMPLETED", null, "discovery_run", "run-pending");
                String runId = Ui2Rows.opaqueId("disc");
                exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state, job_id) values (?, 'check_point', ?, ?, ?, 'FINISHED', ?)",
                        runId, e.management().managementAddress(), credential, Ui2Rows.ACTOR, discoveryJob);
                exec(c, "update jobs set target_ref = ? where job_id = ?", runId, discoveryJob);
                ids.put("runId:discovery", runId);
                String clusterCandidate = Ui2Rows.opaqueId("cand");
                exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                        + "management_address, importable) values (?, ?, 'check_point', ?, 'cluster', ?, ?, false)",
                        clusterCandidate, runId, e.cluster(), e.cluster(), e.cpMemberA().managementAddress());
                for (Device d : e.devices()) {
                    exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                            + "own_address, management_address, cluster_reference, parent_candidate_id, model, software_version, "
                            + "importable, import_outcome) values (?, ?, ?, ?, 'gateway', ?, ?, ?, ?, ?, 'synthetic-model', "
                            + "'R81.20', true, 'already_imported')",
                            Ui2Rows.opaqueId("cand"), runId, d.vendor(), d.serial(), d.hostname(), d.managementAddress(),
                            e.management().managementAddress(), d.cluster(), d.cluster() == null ? null : clusterCandidate);
                }

                // One inventory run per gateway: interface addresses, zone names.
                for (Device d : List.of(e.cpMemberA(), e.cpMemberB(), e.panFirewall())) {
                    String job = job(c, d.id(), "inventory_collect", "COMPLETED", null, "device", null);
                    String run = Ui2Rows.opaqueId("inv");
                    exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                            + "values (?, ?, ?, now(), 1)", run, d.id(), job);
                    String itf = Ui2Rows.opaqueId("itf");
                    exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state, zone) "
                            + "values (?, ?, 'physical', 'eth1', 'physical', 'up', 'internal')", itf, run);
                    exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                            + "values (?, ?, ?, 'ipv4', 'member')", Ui2Rows.opaqueId("addr"), itf, d.interfaceAddress());
                }

                // A failed job whose free-text reason names a device, its address and the administrator.
                String failed = job(c, e.panFirewall().id(), "inventory_collect", "FAILED",
                        "ssh to " + e.panFirewall().hostname() + " at " + e.panFirewall().managementAddress()
                                + " refused for " + e.principal() + " (serial " + e.panFirewall().serial() + ")",
                        "device", null);
                ids.put("jobId", failed);

                // A configuration backup row of the first member.
                String backup = UUID.randomUUID().toString();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path, deviation_state, backup_type) "
                        + "values (?, ?, 'backup', 'check_point', 'R81.20', ?, ?, 10, ?, 10, 'k1', ?, "
                        + "'{\"level_reached\":\"V2\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?, 'first', 'standard')",
                        backup, e.cpMemberA().id(), sha256(e.cpMemberA().hostname()), "0".repeat(64), "1".repeat(64),
                        new byte[32], backup);
                ids.put("artefactId", backup);

                // A stored system-log chunk whose only line names a hostname, a serial, an address and the administrator.
                String line = "Nov 23 10:00:01 " + e.cpMemberA().hostname() + " fwd[4242]: sync with peer "
                        + e.cpMemberB().hostname() + " serial " + e.cpMemberB().serial() + " from "
                        + e.cpMemberB().managementAddress() + " by " + e.principal() + "\n";
                String logJob = job(c, e.cpMemberA().id(), "system_log_collect", "COMPLETED", null, "device", null);
                var handle = store.open(e.cpMemberA().id(), logJob, "check_point", true);
                byte[] plain = line.getBytes(StandardCharsets.UTF_8);
                handle.sink().write(plain);
                var meta = handle.finish();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, "
                        + "plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, "
                        + "validation, retention_tier, expires_at, recovery_volume_path) values (?, ?, 'system_log', "
                        + "'check_point', ?, ?, ?, ?, ?, ?, ?, "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', now() + interval '30 days', ?)",
                        meta.ref().value(), e.cpMemberA().id(), sha256(e.cpMemberA().hostname()), meta.plaintextSha256(),
                        meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                        meta.wrappedDataKey(), meta.ref().value());
                exec(c, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                        + "reads_issued, duration_ms, detail) values (?, ?, ?, 'cp_messages', 'COLLECTED', ?, ?, 1, 5, '{}'::jsonb)",
                        UUID.randomUUID().toString(), e.cpMemberA().id(), logJob, (long) plain.length, (long) plain.length);
                String chunk = UUID.randomUUID().toString();
                exec(c, "insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, "
                        + "start_offset, end_offset, bytes, lines, redaction_count, flags, artefact_ref, wrapped_data_key, "
                        + "plaintext_hmac, ciphertext_bytes) values (?, ?, 'cp_messages', ?, current_date, ?, 0, ?, ?, 1, 0, "
                        + "'{}'::text[], ?, ?, ?, ?)",
                        chunk, e.cpMemberA().id(), logJob, "2".repeat(64), (long) plain.length, (long) plain.length,
                        meta.ref().value(), meta.wrappedDataKey(), "3".repeat(64), meta.ciphertextBytes());
                ids.put("chunkId", chunk);

                // A Check Point policy of the management server: rule, section, object and package names, the
                // administrator as last modifier, the gateways as install targets.
                var object = new PolicySnapshot.PolicyObject(e.objectId(), e.objectName(), "host", List.of(),
                        List.of(e.objectAddress()), "RESOLVED");
                var rule = new PolicySnapshot.Rule(e.ruleId(), UUID.randomUUID().toString(), 1, e.ruleName(), true,
                        new PolicySnapshot.Cell(List.of(e.objectId()), false), new PolicySnapshot.Cell(List.of(e.objectId()), false),
                        new PolicySnapshot.Cell(List.of(), false), new PolicySnapshot.Cell(List.of(), false), "Accept", "Log",
                        "requested by " + e.principal() + " for " + e.panFirewall().hostname(),
                        Map.of("last-modifier", List.of(e.principal())));
                var metadata = new PolicySnapshot.Metadata(e.policyId(), e.management().id(), e.management().hostname(), "CP",
                        e.domainId(), "SMC User", e.policyName(), "2026-11-20T10:00:00Z", null,
                        List.of(new PolicySnapshot.Target(e.cpMemberA().id(), e.cpMemberA().hostname(), "", "IN_SYNC"),
                                new PolicySnapshot.Target(e.cpMemberB().id(), e.cpMemberB().hostname(), "", "IN_SYNC")));
                // A disabled second rule: a hygiene finding ("disabled") for the hygiene views and their CSV export.
                var disabled = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2,
                        e.p1().disabledRuleName(), false, new PolicySnapshot.Cell(List.of(e.objectId()), false),
                        new PolicySnapshot.Cell(List.of(), false), new PolicySnapshot.Cell(List.of(), false),
                        new PolicySnapshot.Cell(List.of(), false), "Drop", "None", "disabled by " + e.principal(),
                        Map.of("last-modifier", List.of(e.principal())));
                var snapshot = new PolicySnapshot(metadata,
                        List.of(new PolicySnapshot.Section("section-1", e.sectionName(), "CP access layer", null, List.of(rule, disabled))),
                        Map.of(e.objectId(), object), List.of());
                exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) "
                        + "values (?, '2026-11-20T10:00:00Z'::timestamptz, ?::jsonb, ?::jsonb)",
                        e.policyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
                exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                        + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), "
                        + "'2026-11-20T09:00:00Z', ?, ?::jsonb)",
                        e.policyId(), e.ruleId(), e.ruleId(), e.principal(),
                        JSON.writeValueAsString(List.of(Map.of("field", "name", "before", "old-" + tag(), "after", e.ruleName()))));

                // A static-route preview on the cluster (the unit is the cluster), requested by the administrator.
                String preview = "srp-" + UUID.randomUUID();
                exec(c, "insert into static_route_preview(preview_id, idempotency_key, request_payload_sha256, requested_by, "
                        + "unit_id, target_device_id, vendor, routing_context, operation, destination_cidr, nexthop_ip, "
                        + "interface_name, route_name, device_comment, representative_host, state, verdict, pre_checks_json, "
                        + "command_plan_json, inverse_plan_json, completed_at) values (?, ?, ?, ?, ?, ?, 'check_point', "
                        + "'default', 'ADD', '203.0.113.0/24', ?, 'eth1', ?, ?, '203.0.113.10', 'DONE', 'PASS', '[]'::jsonb, "
                        + "'{}'::jsonb, '{}'::jsonb, now())",
                        preview, UUID.randomUUID().toString(), "4".repeat(64), e.principal(), e.cluster(), e.cpMemberA().id(),
                        e.cpMemberB().interfaceAddress(), "to-" + e.panFirewall().hostname(),
                        "for " + e.principal());
                ids.put("previewId", preview);
            });
            seedZoneMatrix(c, e);
            seedCutover(c, e);
            seedAsaPair(c, e, credential);
        }
    }

    /**
     * Zone Matrix (V216/V220/V267): one ACTIVE version and one DRAFT based on it, with their revision rows. The canonical
     * content holds two zones with raw names, IPv4 and IPv6 blocks and a description, and one cell whose rationale,
     * regulation reference and confirmation name the administrator and a hostname. The administrator created,
     * published and edited both versions.
     */
    private static void seedZoneMatrix(Connection c, Estate e) throws Exception {
        P1 p = e.p1();
        String zoneA = UUID.randomUUID().toString();
        String zoneB = UUID.randomUUID().toString();
        var zones = List.of(
                new ZoneMatrixContent.Zone(zoneA, p.zoneNameA(), p.zoneDescription(), "DATABASE",
                        List.of(ZoneBlock.parse(p.zoneV4A() + "/30"), ZoneBlock.parse(p.zoneV6A() + "/64"))),
                new ZoneMatrixContent.Zone(zoneB, p.zoneNameB(), p.zoneDescription(), "APPLICATION",
                        List.of(ZoneBlock.parse(p.zoneV4B() + "/30"), ZoneBlock.parse(p.zoneV6B() + "/64"))));
        var cell = new ZoneMatrixContent.Cell(zoneB, zoneA, "DENY_ALL", List.of(), "HIGH", p.cellRationale(),
                p.regulationReference(), "CONFIRMED", e.principal(), "2026-11-20T10:00:00Z");
        var active = new ZoneMatrixContent(zones, ZoneMatrixContent.Matrix.initial().withCells(List.of(cell)));
        active.validate();
        var draft = new ZoneMatrixContent(List.of(zones.get(0), new ZoneMatrixContent.Zone(zoneB, p.zoneNameB(),
                "moved by " + e.principal(), "APPLICATION", List.of(ZoneBlock.parse(p.zoneV4B() + "/30")))),
                active.matrix());
        draft.validate();
        String activeId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        audited(c, () -> {
            exec(c, "insert into zone_matrix_version(version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) values (?, 1, 'ACTIVE', 2, ?, ?, ?, ?, ?, now())",
                    activeId, active.canonicalJson(), active.sha256(), e.principal(), e.principal(), e.principal());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, zone_id, content_sha256, "
                    + "content, rationale) values (?, 1, ?, 'ZONE_CREATE', ?, ?, ?, ?)",
                    activeId, e.principal(), zoneA, active.sha256(), active.canonicalJson(), p.cellRationale());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, content_sha256, content, "
                    + "rationale) values (?, 2, ?, 'PUBLISH', ?, ?, ?)",
                    activeId, e.principal(), active.sha256(), active.canonicalJson(), p.regulationReference());
            exec(c, "insert into zone_matrix_version(version_id, state, revision, content, content_sha256, "
                    + "based_on_version_id, created_by, updated_by) values (?, 'DRAFT', 1, ?, ?, ?, ?, ?)",
                    draftId, draft.canonicalJson(), draft.sha256(), activeId, e.principal(), e.principal());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, zone_id, content_sha256, "
                    + "content, rationale) values (?, 1, ?, 'ZONE_UPDATE', ?, ?, ?, ?)",
                    draftId, e.principal(), zoneB, draft.sha256(), draft.canonicalJson(), p.zoneDescription());
        });
        ids.put("zoneMatrixActive", activeId);
        ids.put("zoneMatrixDraft", draftId);
        ids.put("zoneA", zoneA);
    }

    /**
     * ACI cutover (V217): one segment (raw name, IPv4 and IPv6 blocks, a peer block, written by the administrator), one
     * operator-override assessment on the first Check Point member citing the seeded policy, and its flow rows: a
     * dropped flow between raw addresses decided by the seeded rule, and a missing-rule group with its raw sources.
     */
    private static void seedCutover(Connection c, Estate e) throws Exception {
        P1 p = e.p1();
        String segment = UUID.randomUUID().toString();
        String assessment = UUID.randomUUID().toString();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", e.ruleId());
        rule.put("number", 1);
        rule.put("name", e.ruleName());
        rule.put("sectionId", "section-1");
        rule.put("sectionName", e.sectionName());
        rule.put("action", "Drop");
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("direction", "SEGMENT_TO_PEER");
        flow.put("flowSource", p.flowSource());
        flow.put("flowDestination", p.flowDestination());
        flow.put("protocol", "tcp");
        flow.put("port", 443);
        flow.put("flowCount", 7);
        flow.put("firstSeen", p.flowFirstSeen());
        flow.put("lastSeen", "2026-11-20T08:00:00Z");
        flow.put("decision", "WOULD_DROP");
        flow.put("decisionReason", "rule match");
        flow.put("decidingRule", rule);
        flow.put("targetSource", "OPERATOR_OVERRIDE");
        flow.put("flags", List.of());
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("direction", "SEGMENT_TO_PEER");
        missing.put("flowDestination", p.flowDestination());
        missing.put("protocol", "tcp");
        missing.put("port", 1433);
        missing.put("sourceCount", 2);
        missing.put("flowSources", List.of(p.flowSource(), p.missingRuleSource()));
        missing.put("flowCount", 3);
        missing.put("firstSeen", p.flowFirstSeen());
        missing.put("lastSeen", "2026-11-20T08:00:00Z");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engineVersion", "synthetic-engine");
        summary.put("status", "COMPLETE");
        summary.put("readiness", "BLOCKERS_FOUND");
        summary.put("headline", "1 of 1 evaluated flows would be dropped by the target policy.");
        summary.put("targetSource", "OPERATOR_OVERRIDE");
        summary.put("counts", Map.of("tuples", 1, "flows", 7, "blockingTuples", 1, "missingRuleGroups", 1));
        summary.put("coverage", List.of(Map.of("code", "LOG_PERIOD")));
        summary.put("uploads", List.of(Map.of("index", 0, "rowCount", 10, "invalidRows", 0, "inScopeFlows", 10,
                "periodStart", p.flowFirstSeen(), "periodEnd", "2026-11-20T08:00:00Z")));
        try {
            c.setAutoCommit(false);
            Ui2Rows.setAuditContext(c, e.principal(), "cutover_assessment_write");
            exec(c, "insert into cutover_segment(segment_id, name, revision, blocks, peer_blocks, created_by, updated_by) "
                    + "values (?, ?, 1, ?::jsonb, ?::jsonb, ?, ?)",
                    segment, p.segmentName(), JSON.writeValueAsString(List.of(p.segmentV4() + "/29", p.segmentV6() + "/48")),
                    JSON.writeValueAsString(List.of(p.peerV4() + "/29")), e.principal(), e.principal());
            exec(c, "insert into cutover_assessment(assessment_id, segment_id, segment_revision, actor, target_source, "
                    + "target_device_id, target_policy_id, target_collected_at, engine_version, status, readiness, summary) "
                    + "values (?, ?, 1, ?, 'OPERATOR_OVERRIDE', ?, ?, '2026-11-20T10:00:00Z', 'synthetic-engine', 'COMPLETE', "
                    + "'BLOCKERS_FOUND', ?::jsonb)",
                    assessment, segment, e.principal(), e.cpMemberA().id(), e.policyId(), JSON.writeValueAsString(summary));
            exec(c, "insert into cutover_assessment_flow(assessment_id, kind, ordinal, decision, flow) "
                    + "values (?, 'FLOW', 0, 'WOULD_DROP', ?::jsonb), (?, 'MISSING_RULE', 0, 'MISSING_RULE', ?::jsonb)",
                    assessment, JSON.writeValueAsString(flow), assessment, JSON.writeValueAsString(missing));
            c.commit();
        } catch (Exception failure) {
            c.rollback();
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
        ids.put("segmentId", segment);
        ids.put("assessmentId", assessment);
    }

    /**
     * A Cisco ASA failover pair: two enrolled gateways sharing a raw cluster reference, the pair's recorded unit serials
     * (MATCH), and one stored running configuration per unit (encrypted in the test store) with a raw interface name
     * and address, a network object and an access list.
     */
    private static void seedAsaPair(Connection c, Estate e, String credential) throws Exception {
        P1 p = e.p1();
        List<String> serials = new ArrayList<>(List.of(p.asaA().serial(), p.asaB().serial()));
        java.util.Collections.sort(serials);
        audited(c, () -> {
            for (AsaUnit u : p.asaUnits()) {
                exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                        + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                        + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                        + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                        + "discovery_match_key) values (?, 'cisco_asa', 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                        + "'CORROBORATED', ?, 'synthetic-model', '9.18(4)22', ?, ?, now(), ?, true, ?)",
                        u.id(), credential, u.hostname(), u.haRole(), u.serial(), p.asaCluster(), "cisco_asa|" + u.serial());
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                        Ui2Rows.opaqueId("ep"), u.id(), u.managementAddress());
                exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                        + "values (?, ?, 'synthetic', 'synthetic_seed')", u.id(), u.serial());
                String config = "ASA Version 9.18(4)22\n"
                        + "hostname " + u.hostname() + "\n"
                        + "interface GigabitEthernet0/0\n nameif " + p.asaNameif() + "\n security-level 0\n"
                        + " ip address " + p.asaInterfaceAddress() + " 255.255.255.0\n"
                        + "interface GigabitEthernet0/1\n nameif inside\n security-level 100\n"
                        + "object network " + p.asaObjectName() + "\n host " + p.asaObjectAddress() + "\n"
                        + "access-list " + p.asaAclName() + " extended permit tcp any object " + p.asaObjectName() + " eq 443\n"
                        + "access-list " + p.asaAclName() + " extended deny ip host " + p.flowSource() + " any\n"
                        + "access-group " + p.asaAclName() + " in interface " + p.asaNameif() + "\n";
                String job = job(c, u.id(), "configuration_collect", "COMPLETED", null, "device", null);
                var handle = store.open(u.id(), job, "cisco_asa", false);
                byte[] plain = config.getBytes(StandardCharsets.UTF_8);
                handle.sink().write(plain);
                var meta = handle.finish();
                String ref = meta.ref().value();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path) values (?, ?, 'configuration', "
                        + "'cisco_asa', '9.18(4)22', ?, ?, ?, ?, ?, ?, ?, "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?)",
                        ref, u.id(), sha256(u.hostname()), meta.plaintextSha256(), meta.plaintextBytes(),
                        meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(), meta.wrappedDataKey(), ref);
                exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                        + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                        + "values (?, ?, ?, 'cisco_asa', ?, ?, ?, ?, 'none', ?)",
                        ref, u.id(), job, meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(),
                        meta.ciphertextBytes(), meta.keyId());
                exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                        + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) "
                        + "values (?, ?, ?, '2026-11-20T09:30:00Z'::timestamptz, 'cisco_asa', 'show_configuration', true, ?, ?, ?, ?, "
                        + "'first_run')",
                        Ui2Rows.opaqueId("cfg"), u.id(), job, meta.plaintextSha256(), meta.plaintextSha256(),
                        meta.plaintextBytes(), ref);
            }
            exec(c, "insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, recorded_by, "
                    + "last_state) values (?, 'cisco_asa', ?, ?, 'synthetic_seed', 'MATCH')",
                    p.asaCluster(), serials.get(0), serials.get(1));
        });
    }

    private static String job(Connection c, String deviceId, String type, String state, String reason, String targetKind,
            String targetRef) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, target_ref, submitted_by_actor_fingerprint, "
                + "state, action_class, terminal_reason, finished_at) values (?, ?, ?, ?, ?, ?, ?, 'read', ?, "
                + (state.equals("REQUESTED") ? "null" : "now()") + ")",
                id, type, targetKind, deviceId, targetRef, Ui2Rows.ACTOR, state, reason);
        return id;
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void audited(Connection c, Work work) throws Exception {
        c.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(c, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            work.run();
            c.commit();
        } catch (Exception failure) {
            c.rollback();
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            st.executeUpdate();
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ leak counting

    /** The search forms of one raw value: exact text, case-insensitively, and the other spellings of an address. */
    public static List<Pattern> forms(String value, ValueClass cls) {
        List<Pattern> out = new ArrayList<>();
        if (cls == ValueClass.ADDRESS && value.contains(":")) {
            Set<String> spellings = new TreeSet<>();
            spellings.add(value);
            try {
                byte[] bytes = java.net.InetAddress.getByName(value).getAddress();
                StringBuilder full = new StringBuilder();
                StringBuilder unpadded = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    int group = ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
                    if (i > 0) { full.append(':'); unpadded.append(':'); }
                    full.append(String.format(Locale.ROOT, "%04x", group));
                    unpadded.append(Integer.toHexString(group));
                }
                spellings.add(full.toString());
                spellings.add(unpadded.toString());
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("seeded IPv6 literal did not parse", e);
            }
            for (String s : spellings) {
                out.add(Pattern.compile("(?<![0-9a-f:])" + Pattern.quote(s) + "(?![0-9a-f:])", Pattern.CASE_INSENSITIVE));
            }
        } else if (cls == ValueClass.ADDRESS) {
            // dotted form, not as the prefix of a longer address (192.0.2.1 inside 192.0.2.10)
            out.add(Pattern.compile("(?<![0-9.])" + Pattern.quote(value) + "(?![0-9])"));
            // URL- or JSON-escaped dots do not occur in JSON bodies; the hyphenated form some name builders use does
            out.add(Pattern.compile("(?<![0-9-])" + Pattern.quote(value.replace('.', '-')) + "(?![0-9])"));
        } else {
            out.add(Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    public static Map<ValueClass, Integer> count(String body, Map<String, ValueClass> raw) {
        Map<ValueClass, Integer> found = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            int n = 0;
            for (Pattern p : forms(entry.getKey(), entry.getValue())) {
                Matcher m = p.matcher(body);
                while (m.find()) n++;
            }
            if (n > 0) found.merge(entry.getValue(), n, Integer::sum);
        }
        return found;
    }
}
