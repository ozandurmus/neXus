package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.persistence.device.JooqHaPairIdentityRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 10 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp; post-parsing
 * masking law"): the remaining failover coverage items noted after round 7 -- Check Point VSX chassis
 * ({@code VSX_CHASSIS}) and virtual-system ({@code VIRTUAL_SYSTEM}) units with per-unit readiness runs and a
 * {@code FAILOVER_ALL_VIRTUAL_SYSTEMS} {@code failover_sequence} row, request-bound failover approvals
 * ({@code OPERATION_ADMIN_TWO_PERSON} and {@code ADMIN_SINGLE}, including {@code execution_nonce} withholding), and
 * Cisco ASA / FortiGate corroborated HA pairs ({@code ha_pair_identity = MATCH}).
 *
 * <p>Seeded into PostgreSQL 16 with per-run synthetic tags and {@code 192.0.2.x} / {@code 198.51.100.x} addresses only:
 * <ul>
 *   <li>Check Point VSX cluster ({@code check_point}): two corroborated members with {@code physical} and VSID
 *       {@code "1"}, {@code "2"} inventory contexts and planted raw VS display names, finished readiness runs for the
 *       chassis ({@code vs_id = null}) and both virtual systems, and an admitted {@code failover_sequence}
 *       ({@code FAILOVER_ALL_VIRTUAL_SYSTEMS}) initiated by a catalogued administrator.</li>
 *   <li>Request-bound approvals via {@link JooqCpFailoverRepository#createRequestApproval} and
 *       {@link JooqCpFailoverRepository#approveRequest}: VSX chassis {@code TWO_PERSON}
 *       ({@code FAILOVER_ALL_VIRTUAL_SYSTEMS}) and {@code ADMIN_SINGLE} ({@code FAILOVER}), per-VS pending and approved
 *       {@code TWO_PERSON} approvals, and Palo Alto {@code TWO_PERSON} and {@code ADMIN_SINGLE} approvals.</li>
 *   <li>Cisco ASA and FortiGate corroborated HA pairs with {@code ha_pair_identity} recorded ({@code MATCH}) through
 *       {@link JooqHaPairIdentityRepository#observe} by a catalogued principal.</li>
 * </ul>
 *
 * <p>Live controller behaviour verified under {@code aiview} ({@code role:replay_viewer}):
 * <ul>
 *   <li>{@code GET /api/v2/cp-failover/units} (bare) answers {@code 200 []}; {@code ?clusterRef=<raw>} and
 *       {@code ?memberDeviceId=<id>} answer {@code 200} with one {@code VSX_CHASSIS} unit (carrying {@code sequence})
 *       and two {@code VIRTUAL_SYSTEM} units; {@code ?clusterRef=<masked>} answers {@code 200 []}
 *       ({@code CpFailoverService.lookupUnits} resolves raw {@code cluster_member_ref} or {@code memberDeviceId}).</li>
 *   <li>{@code GET /api/v2/cp-failover/units/{chassisId}} answers {@code 200} with both {@code VSX_CHASSIS} and
 *       {@code VIRTUAL_SYSTEM} unit kinds; {@code GET /api/v2/cp-failover/units/{vsUnitId}} answers {@code 409}
 *       ({@code CLUSTER_NOT_FOUND}) because {@code @GetMapping("/units/{clusterId}")} resolves by {@code clusterId},
 *       with zero planted raw values in the refusal body.</li>
 *   <li>Zero occurrences of every planted raw hostname, member/VS address, serial, cluster reference, VS raw context
 *       name, catalogued principal ({@code initiated_by}, {@code approved_by}, {@code recorded_by}), and
 *       {@code execution_nonce} (and neither {@code "executionNonce"} nor {@code "execution_nonce"} appears).</li>
 * </ul>
 *
 * <p>Output prints only route, HTTP status, call label, and {@link ValueClass} counts -- never raw values.</p>
 */
class AiviewVsxAndRequestBoundFailoverSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Planted raw value categories; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, CLUSTER_NAME, VS_NAME, ADDRESS, SERIAL, PRINCIPAL, EXECUTION_NONCE
    }

    /** Surviving raw values pinned by exact route and value class: empty (every planted identity is masked or withheld). */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of();

    /** Seeded markers each route must expose across its calls so zero leak counts are proven over populated views. */
    static final Map<String, Set<String>> COVERAGE = Map.ofEntries(
            Map.entry("GET /api/v2/cp-failover/units", Set.of(
                    "vsx-chassis-unit", "vsx-virtual-system-unit", "vsx-sequence", "vsx-all-vs-action")),
            Map.entry("GET /api/v2/cp-failover/units/*", Set.of("vsx-chassis-unit", "vsx-virtual-system-unit")),
            Map.entry("GET /api/v2/cp-failover/summary", Set.of(
                    "vsx-chassis-unit", "vsx-virtual-system-unit", "vsx-all-vs-action", "asa-unit", "fgt-unit", "ha-view")),
            Map.entry("GET /api/v2/cp-failover/runs", Set.of(
                    "chassis-readiness-run", "vs1-readiness-run", "vs2-readiness-run", "vs1-sequence-run")),
            Map.entry("GET /api/v2/cp-failover/runs/*", Set.of(
                    "chassis-readiness-run", "vs1-readiness-run", "vs2-readiness-run", "vs1-sequence-run", "check-result")),
            Map.entry("GET /api/v2/cp-failover/approvals", Set.of(
                    "cp-chassis-seq-approval", "cp-chassis-admin-approval",
                    "cp-vs1-pending-approval", "cp-vs2-approved-approval", "two-person-policy")),
            Map.entry("GET /api/v2/pan-failover/approvals", Set.of(
                    "pan-two-person-approval", "pan-admin-approval", "two-person-policy")),
            Map.entry("GET /api/v2/asa-failover/units", Set.of("asa-unit")),
            Map.entry("GET /api/v2/asa-failover/units/*", Set.of("asa-unit")),
            Map.entry("GET /api/v2/fgt-failover/units", Set.of("fgt-unit")),
            Map.entry("GET /api/v2/fgt-failover/units/*", Set.of("fgt-unit")));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Estate estate;

    private static final Map<String, ValueClass> rawValues = new LinkedHashMap<>();
    private static final Map<String, String> markers = new LinkedHashMap<>();
    private static final List<String> cpRunIds = new ArrayList<>();

    // ------------------------------------------------------------------ boot (self-contained, per AiviewFailoverIncidentsSweepTest)

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_vsx_request_bound");
        work = Files.createTempDirectory("ui2-aiview-vsx-request-bound-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        var store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        estate = Estate.generate();
        seed(estate);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> useTestArtefactStore(bf, store)))
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + keyFile("group_ref.key"),
                        "--ui2.credential-store.key-file=" + keyFile("credential.key"),
                        "--ui2.failover.master-key-file=" + work.resolve("failover.key"),
                        "--ui2.packet-analysis.root=" + Files.createDirectory(work.resolve("pa")),
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false");
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory,
            com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore store) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(store));
        registry.removeBeanDefinition("machineConnector");
    }

    @AfterAll
    static void shutDown() throws IOException {
        if (context != null) context.close();
        if (fixture != null) fixture.close();
        if (work != null) {
            try (var paths = Files.walk(work)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static Path keyFile(String name) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        Path file = work.resolve(name);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return file;
    }

    // ------------------------------------------------------------------ synthetic estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A token starting with a letter so it can never equal a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    private static String opaqueUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    record Device(String id, String vendor, String hostname, String serial, String address, String cluster) {
    }

    record Pair(String name, String vendor, String prefix, String cluster, Device a, Device b) {
        String clusterId() {
            return opaqueUuid(cluster);
        }

        String vsUnitId(String vsId) {
            return opaqueUuid(cluster + ":" + vsId);
        }

        Set<String> members() {
            return Set.of(a.id(), b.id());
        }
    }

    record Estate(
            Pair cpVsx,
            Pair pan,
            Pair asa,
            Pair fgt,
            String vs1Name,
            String vs2Name,
            String vs1AddrA,
            String vs1AddrB,
            String vs2AddrA,
            String vs2AddrB,
            String initiator,
            String approver,
            String recorder) {

        static Estate generate() {
            int baseA = 20 + RANDOM.nextInt(120);
            int baseB = 20 + RANDOM.nextInt(120);
            Pair cpVsx = pair("cp-vsx", "check_point", "/api/v2/cp-failover", "CP", "192.0.2.", baseA, 0);
            Pair pan = pair("pan", "palo_alto", "/api/v2/pan-failover", "PA", "192.0.2.", baseA, 2);
            Pair asa = pair("asa", "cisco_asa", "/api/v2/asa-failover", "AS", "198.51.100.", baseB, 0);
            Pair fgt = pair("fgt", "fortinet", "/api/v2/fgt-failover", "FG", "198.51.100.", baseB, 2);
            String vs1Name = "vs-core-" + tag();
            String vs2Name = "vs-edge-" + tag();
            String vs1AddrA = "198.51.100." + (baseB + 4);
            String vs1AddrB = "198.51.100." + (baseB + 5);
            String vs2AddrA = "198.51.100." + (baseB + 6);
            String vs2AddrB = "198.51.100." + (baseB + 7);
            String initiator = "adm" + tag() + ".init";
            String approver = "adm" + tag() + ".appr";
            String recorder = "adm" + tag() + ".rec";
            return new Estate(cpVsx, pan, asa, fgt, vs1Name, vs2Name,
                    vs1AddrA, vs1AddrB, vs2AddrA, vs2AddrB, initiator, approver, recorder);
        }

        List<Pair> pairs() {
            return List.of(cpVsx, pan, asa, fgt);
        }

        List<String> principals() {
            return List.of(initiator, approver, recorder);
        }
    }

    private static Pair pair(String name, String vendor, String prefix, String serialPrefix,
            String subnetPrefix, int base, int offset) {
        String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
        Device a = new Device(Ui2Rows.opaqueId("dev"), vendor, "FW-TANGO-" + tag().toUpperCase(Locale.ROOT),
                serialPrefix + "0" + hex(5).toUpperCase(Locale.ROOT), subnetPrefix + (base + offset), cluster);
        Device b = new Device(Ui2Rows.opaqueId("dev"), vendor, "synth" + tag() + "-fw-02",
                serialPrefix + "1" + hex(5).toUpperCase(Locale.ROOT), subnetPrefix + (base + offset + 1), cluster);
        return new Pair(name, vendor, prefix, cluster, a, b);
    }

    // ------------------------------------------------------------------ seeding

    private static void seed(Estate e) throws Exception {
        rawValues.clear();
        markers.clear();
        cpRunIds.clear();
        for (Pair p : e.pairs()) {
            rawValues.put(p.cluster(), ValueClass.CLUSTER_NAME);
            for (Device d : List.of(p.a(), p.b())) {
                rawValues.put(d.hostname(), ValueClass.HOSTNAME);
                rawValues.put(d.serial(), ValueClass.SERIAL);
                rawValues.put(d.address(), ValueClass.ADDRESS);
            }
        }
        rawValues.put(e.vs1Name(), ValueClass.VS_NAME);
        rawValues.put(e.vs2Name(), ValueClass.VS_NAME);
        rawValues.put(e.vs1AddrA(), ValueClass.ADDRESS);
        rawValues.put(e.vs1AddrB(), ValueClass.ADDRESS);
        rawValues.put(e.vs2AddrA(), ValueClass.ADDRESS);
        rawValues.put(e.vs2AddrB(), ValueClass.ADDRESS);
        for (String principal : e.principals()) {
            rawValues.put(principal, ValueClass.PRINCIPAL);
        }

        String vsSummary = e.vs1Name() + " (VSID 1), " + e.vs2Name() + " (VSID 2)";
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, () -> {
                for (Pair p : e.pairs()) {
                    for (Device d : List.of(p.a(), p.b())) {
                        boolean first = d == p.a();
                        String observedRole = switch (p.vendor()) {
                            case "fortinet" -> first ? "primary" : "secondary";
                            case "palo_alto" -> first ? "active" : "passive";
                            default -> first ? "active" : "standby";
                        };
                        exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                                + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                                + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                                + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                                + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                                + "'CORROBORATED', ?, 'synthetic-model', 'R81.20', ?, ?, now(), ?, true, ?)",
                                d.id(), p.vendor(), credential, d.hostname(), observedRole, d.serial(),
                                d.cluster(), d.vendor() + "|" + d.serial());
                        exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, ?, ?)",
                                Ui2Rows.opaqueId("ep"), d.id(),
                                p.vendor().equals("palo_alto") ? "pan_xml_api" : "ssh_exec", d.address());
                        exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                                + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());

                        String job = Ui2Rows.opaqueId("job");
                        exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                                + "state, action_class, finished_at) values (?, 'inventory_collect', 'device', ?, ?, 'COMPLETED', 'read', now())",
                                job, d.id(), Ui2Rows.ACTOR);
                        String invRun = Ui2Rows.opaqueId("inv");
                        boolean vsx = p == e.cpVsx();
                        exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count, virtual_systems) "
                                + "values (?, ?, ?, now(), ?, ?)",
                                invRun, d.id(), job, vsx ? 3 : 1, vsx ? vsSummary : null);

                        if (vsx) {
                            insertInterface(c, invRun, "physical", "eth0", null);
                            insertInterface(c, invRun, "1", "eth1", first ? e.vs1AddrA() : e.vs1AddrB());
                            insertInterface(c, invRun, "2", "eth2", first ? e.vs2AddrA() : e.vs2AddrB());
                            for (String ctx : List.of("physical", "1", "2")) {
                                exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                                        + "values (?, ?, ?, ?, 'VSLS', 'cp_vsls_table')",
                                        Ui2Rows.opaqueId("ha"), invRun, ctx, first ? "ACTIVE" : "STANDBY");
                            }
                        } else if (p.vendor().equals("palo_alto")) {
                            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                                    + "values (?, ?, 'physical', ?, 'active-passive', 'pan_high_availability_state')",
                                    Ui2Rows.opaqueId("ha"), invRun, first ? "ACTIVE" : "PASSIVE");
                        } else if (p.vendor().equals("cisco_asa")) {
                            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                                    + "values (?, ?, 'physical', ?, 'active-standby', 'asa_show_failover')",
                                    Ui2Rows.opaqueId("ha"), invRun, first ? "active" : "standby");
                        } else if (p.vendor().equals("fortinet")) {
                            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                                    + "values (?, ?, 'physical', ?, 'active-passive', 'fgt_get_system_ha_status')",
                                    Ui2Rows.opaqueId("ha"), invRun, first ? "primary" : "secondary");
                        }
                    }
                }
            });
        }

        seedKnownAdministrators(e);

        var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        var haIdentityRepo = new JooqHaPairIdentityRepository(tx);
        assertEquals(Optional.of(HaPairIdentity.State.MATCH), haIdentityRepo.observe(e.asa().a().id(), e.recorder()),
                "ASA pair identity recorded as MATCH");
        assertEquals(Optional.of(HaPairIdentity.State.MATCH), haIdentityRepo.observe(e.fgt().a().id(), e.recorder()),
                "FortiGate pair identity recorded as MATCH");

        var repo = new JooqCpFailoverRepository(tx, true, JobWindowTestPolicy.PERMISSIVE);

        // Readiness runs for the VSX chassis (vs_id = null), both virtual systems ("1" and "2"), and Palo Alto.
        String chassisReadiness = readinessRun(repo, e, e.cpVsx(), null, "VSLS");
        String vs1Readiness = readinessRun(repo, e, e.cpVsx(), "1", "VSLS");
        String vs2Readiness = readinessRun(repo, e, e.cpVsx(), "2", "VSLS");
        cpRunIds.addAll(List.of(chassisReadiness, vs1Readiness, vs2Readiness));
        markers.put(chassisReadiness, "chassis-readiness-run");
        markers.put(vs1Readiness, "vs1-readiness-run");
        markers.put(vs2Readiness, "vs2-readiness-run");
        readinessRun(repo, e, e.pan(), null, "active-passive");

        String chassisId = e.cpVsx().clusterId();
        String vs1UnitId = e.cpVsx().vsUnitId("1");
        String vs2UnitId = e.cpVsx().vsUnitId("2");

        // 1. VSX chassis TWO_PERSON approval for FAILOVER_ALL_VIRTUAL_SYSTEMS + failover_sequence admission.
        var chassisSeqApproval = repo.createRequestApproval(UUID.randomUUID().toString(), e.cpVsx().cluster(), null,
                chassisId, e.cpVsx().members(), Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600),
                freeText(e, e.cpVsx(), "vsx all-virtual-systems two-person approval"), e.initiator(), "check_point",
                JooqCpFailoverRepository.TWO_PERSON, JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS);
        assertEquals("APPROVED", repo.approveRequest(chassisSeqApproval.approval().id(), chassisSeqApproval.revision(),
                e.approver(), "check_point", e.cpVsx().members()));
        markers.put(chassisSeqApproval.approval().id(), "cp-chassis-seq-approval");

        var seqDecision = repo.requestSequence(e.cpVsx().cluster(), e.initiator(), e.cpVsx().a().id(),
                e.cpVsx().members(), chassisSeqApproval.approval().id(), chassisSeqApproval.revision(),
                chassisSeqApproval.executionNonce(), true, JooqCpFailoverRepository.TWO_PERSON,
                List.of(new JooqCpFailoverRepository.SequenceStep("1", vs1UnitId),
                        new JooqCpFailoverRepository.SequenceStep("2", vs2UnitId)),
                "operation_admin", true);
        assertEquals("ADMITTED", seqDecision.code(), "VSX sequence admission");
        String seqRunId = seqDecision.runId();
        checks(repo, e, e.cpVsx(), "1", seqRunId, "VSLS");
        repo.state(seqRunId, "DONE", "DONE", "SUCCEEDED", null, freeText(e, e.cpVsx(), "vs1 sequence failover finished"));
        completeJobOfRun(seqRunId);
        cpRunIds.add(seqRunId);
        markers.put(seqRunId, "vs1-sequence-run");
        var sequence = repo.latestSequence(e.cpVsx().cluster()).orElseThrow();
        markers.put(sequence.id(), "vsx-sequence");

        // 2. VSX chassis standalone ADMIN_SINGLE approval (OPERATION_FAILOVER).
        var chassisAdminApproval = repo.createRequestApproval(UUID.randomUUID().toString(), e.cpVsx().cluster(), null,
                chassisId, e.cpVsx().members(), Instant.now().minusSeconds(90), Instant.now().plusSeconds(3600),
                freeText(e, e.cpVsx(), "vsx chassis admin-single approval"), e.approver(), "check_point",
                JooqCpFailoverRepository.ADMIN_SINGLE, JooqCpFailoverRepository.OPERATION_FAILOVER);
        markers.put(chassisAdminApproval.approval().id(), "cp-chassis-admin-approval");

        // 3. Per-VS approvals: VS 1 pending TWO_PERSON; VS 2 second-person approved TWO_PERSON.
        var vs1PendingApproval = repo.createRequestApproval(UUID.randomUUID().toString(), e.cpVsx().cluster(), "1",
                vs1UnitId, e.cpVsx().members(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                freeText(e, e.cpVsx(), "vs1 pending two-person approval"), e.initiator(), "check_point",
                JooqCpFailoverRepository.TWO_PERSON, JooqCpFailoverRepository.OPERATION_FAILOVER);
        markers.put(vs1PendingApproval.approval().id(), "cp-vs1-pending-approval");

        var vs2ApprovedApproval = repo.createRequestApproval(UUID.randomUUID().toString(), e.cpVsx().cluster(), "2",
                vs2UnitId, e.cpVsx().members(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                freeText(e, e.cpVsx(), "vs2 approved two-person approval"), e.initiator(), "check_point",
                JooqCpFailoverRepository.TWO_PERSON, JooqCpFailoverRepository.OPERATION_FAILOVER);
        assertEquals("APPROVED", repo.approveRequest(vs2ApprovedApproval.approval().id(), vs2ApprovedApproval.revision(),
                e.approver(), "check_point", e.cpVsx().members()));
        markers.put(vs2ApprovedApproval.approval().id(), "cp-vs2-approved-approval");

        // 4. Palo Alto approvals: second-person approved TWO_PERSON + ADMIN_SINGLE.
        var panTwoPerson = repo.createRequestApproval(UUID.randomUUID().toString(), e.pan().cluster(), null,
                e.pan().clusterId(), e.pan().members(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                freeText(e, e.pan(), "pan two-person approval"), e.initiator(), "palo_alto",
                JooqCpFailoverRepository.TWO_PERSON, JooqCpFailoverRepository.OPERATION_FAILOVER);
        assertEquals("APPROVED", repo.approveRequest(panTwoPerson.approval().id(), panTwoPerson.revision(),
                e.approver(), "palo_alto", e.pan().members()));
        markers.put(panTwoPerson.approval().id(), "pan-two-person-approval");

        var panAdmin = repo.createRequestApproval(UUID.randomUUID().toString(), e.pan().cluster(), null,
                e.pan().clusterId(), e.pan().members(), Instant.now().minusSeconds(30), Instant.now().plusSeconds(3600),
                freeText(e, e.pan(), "pan admin-single approval"), e.approver(), "palo_alto",
                JooqCpFailoverRepository.ADMIN_SINGLE, JooqCpFailoverRepository.OPERATION_FAILOVER);
        markers.put(panAdmin.approval().id(), "pan-admin-approval");

        markers.put(e.asa().clusterId(), "asa-unit");
        markers.put(e.fgt().clusterId(), "fgt-unit");

        // Register every persisted execution_nonce (including the sequence's derived per-VS child approval).
        int nonces = 0;
        try (Connection c = fixture.migrateConnection();
                PreparedStatement st = c.prepareStatement(
                        "select execution_nonce from failover_approval where execution_nonce is not null");
                ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                rawValues.put(rs.getString(1), ValueClass.EXECUTION_NONCE);
                nonces++;
            }
        }
        assertEquals(7, nonces, "six explicit request-bound approvals plus one sequence-derived child approval");
    }

    private static void insertInterface(Connection c, String runId, String context, String name, String address)
            throws SQLException {
        String ifaceId = Ui2Rows.opaqueId("if");
        exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state) "
                + "values (?, ?, ?, ?, 'physical', 'up')", ifaceId, runId, context, name);
        if (address != null) {
            exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                    + "values (?, ?, ?, 'ipv4', 'member')", Ui2Rows.opaqueId("addr"), ifaceId, address);
        }
    }

    /** Seed all catalogued principals into policy_rule_history.changed_by and snapshot last-modifier. */
    private static void seedKnownAdministrators(Estate e) throws Exception {
        String policyId = "policy-" + UUID.randomUUID();
        var any = new PolicySnapshot.Cell(List.of("any-obj"), false);
        List<PolicySnapshot.Rule> rules = new ArrayList<>();
        List<String> principals = e.principals();
        for (int i = 0; i < principals.size(); i++) {
            String p = principals.get(i);
            String ruleId = "rule-" + UUID.randomUUID();
            rules.add(new PolicySnapshot.Rule(ruleId, UUID.randomUUID().toString(), i + 1, "allow-" + tag(), true,
                    any, any, any, any, "Accept", "Log", "changed by " + p,
                    Map.of("from", List.of("any"), "to", List.of("any"), "last-modifier", List.of(p))));
        }
        var metadata = new PolicySnapshot.Metadata(policyId, e.pan().a().id(), "pano-" + tag(), "PAN",
                "domain-" + UUID.randomUUID(), "shared", "pkg-" + tag(), Instant.now().toString(), null, List.of());
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("section-1", "Pre Rules", "Network", null, rules)),
                Map.of("any-obj", new PolicySnapshot.PolicyObject("any-obj", "Any", "any", List.of(), List.of(), "RESOLVED")),
                List.of());
        try (Connection c = fixture.migrateConnection()) {
            audited(c, Ui2Rows.ACTOR, () -> {
                exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                        policyId, JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
                for (int i = 0; i < principals.size(); i++) {
                    String ruleId = rules.get(i).id();
                    exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                            + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), "
                            + "'2026-11-20T09:00:00Z', ?, '[]'::jsonb)",
                            policyId, ruleId, ruleId, principals.get(i));
                }
            });
        }
    }

    private static String freeText(Estate e, Pair p, String what) {
        return what + " on " + p.a().hostname() + " (" + p.a().address() + ", serial " + p.a().serial() + ") and "
                + p.b().hostname() + " (" + p.b().address() + ", serial " + p.b().serial() + ") of " + p.cluster()
                + " initiated by " + e.initiator() + " approved by " + e.approver() + " recorded by " + e.recorder();
    }

    private static void checks(JooqCpFailoverRepository repo, Estate e, Pair p, String vsId, String run, String mode)
            throws Exception {
        for (String phase : List.of("pre", "post")) {
            for (Device d : List.of(p.a(), p.b())) {
                String role = d == p.a() ? "ACTIVE" : (p.vendor().equals("palo_alto") ? "PASSIVE" : "STANDBY");
                repo.check(run, phase, d.id(), vsId, 1, "PASS", JSON.writeValueAsString(Map.of(
                        "role", role, "failoverOn", true, "mode", mode,
                        "detail", freeText(e, p, "check-1 " + phase))));
                repo.check(run, phase, d.id(), vsId, 15, "PASS", JSON.writeValueAsString(Map.of(
                        "recoveryMode", "ACTIVE_UP",
                        "detail", freeText(e, p, "check-15 " + phase))));
            }
        }
    }

    private static String readinessRun(JooqCpFailoverRepository repo, Estate e, Pair p, String vsId, String mode)
            throws Exception {
        var decision = repo.requestReadiness(p.cluster(), vsId, Ui2Rows.ACTOR, p.a().id(), p.vendor());
        assertEquals("ADMITTED", decision.code(), "readiness admission of " + p.name() + " vs=" + vsId);
        checks(repo, e, p, vsId, decision.runId(), mode);
        repo.state(decision.runId(), "DONE", "DONE", "READY", null, freeText(e, p, "readiness finished"));
        completeJobOfRun(decision.runId());
        return decision.runId();
    }

    private static void completeJobOfRun(String runId) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            audited(c, Ui2Rows.ACTOR, () -> exec(c,
                    "update jobs set state='COMPLETED', finished_at=now() "
                            + "where job_id=(select job_id from failover_run where run_id=?)",
                    runId));
        }
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void audited(Connection c, String actor, Work work) throws Exception {
        c.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(c, actor, Ui2Rows.ACTION);
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

    // ------------------------------------------------------------------ leak counting

    static List<Pattern> forms(String value, ValueClass cls) {
        List<Pattern> out = new ArrayList<>();
        if (cls == ValueClass.ADDRESS) {
            out.add(Pattern.compile("(?<![0-9.])" + Pattern.quote(value) + "(?![0-9])"));
            out.add(Pattern.compile("(?<![0-9-])" + Pattern.quote(value.replace('.', '-')) + "(?![0-9])"));
        } else {
            out.add(Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    static Map<ValueClass, Integer> count(String body, Map<String, ValueClass> raw) {
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

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String body) {
    }

    private static final Map<String, Integer> SENT = new TreeMap<>();

    private static Resp get(String target, String cookie) throws Exception {
        SENT.merge("GET", 1, Integer::sum);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String login() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "aiview login");
        for (String cookie : response.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no session cookie on the aiview login");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Call(String key, String path, String detail, int expectedStatus) {
        Call(String key, String path, String detail) {
            this(key, path, detail, 200);
        }
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    void noPlantedRawValueOrNonceSurvivesInVsxRequestBoundAndHaPairFailoverViews() throws Exception {
        SENT.clear();
        String cookie = login();
        Estate e = estate;

        Map<String, Boolean> reachable = new TreeMap<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (ep.method().equals("GET") && ep.pathTemplate().matches("/api/v2/(cp|pan|asa|fgt)-failover/.*")) {
                boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
                reachable.merge(ep.key(), may, (a, b) -> a || b);
            }
        }
        for (String expectedReachable : COVERAGE.keySet()) {
            assertEquals(Boolean.TRUE, reachable.get(expectedReachable),
                    "static route audit says aiview can reach " + expectedReachable);
        }

        String chassisId = e.cpVsx().clusterId();
        String vs1UnitId = e.cpVsx().vsUnitId("1");
        String vs2UnitId = e.cpVsx().vsUnitId("2");
        String panId = e.pan().clusterId();
        String asaId = e.asa().clusterId();
        String fgtId = e.fgt().clusterId();

        // Read approvals and runs first on a cold process before any structured unit/summary read warms name caches.
        List<Call> initialCalls = new ArrayList<>();
        initialCalls.add(new Call("GET /api/v2/cp-failover/approvals",
                "/api/v2/cp-failover/approvals?clusterId=" + chassisId + "&unitId=" + chassisId, "cp-chassis"));
        initialCalls.add(new Call("GET /api/v2/cp-failover/approvals",
                "/api/v2/cp-failover/approvals?clusterId=" + chassisId + "&unitId=" + vs1UnitId, "cp-vs1"));
        initialCalls.add(new Call("GET /api/v2/cp-failover/approvals",
                "/api/v2/cp-failover/approvals?clusterId=" + chassisId + "&unitId=" + vs2UnitId, "cp-vs2"));
        initialCalls.add(new Call("GET /api/v2/pan-failover/approvals",
                "/api/v2/pan-failover/approvals?clusterId=" + panId + "&unitId=" + panId, "pan"));

        initialCalls.add(new Call("GET /api/v2/cp-failover/runs",
                "/api/v2/cp-failover/runs?clusterId=" + chassisId + "&unitId=" + chassisId, "cp-chassis"));
        initialCalls.add(new Call("GET /api/v2/cp-failover/runs",
                "/api/v2/cp-failover/runs?clusterId=" + chassisId + "&unitId=" + vs1UnitId, "cp-vs1"));
        initialCalls.add(new Call("GET /api/v2/cp-failover/runs",
                "/api/v2/cp-failover/runs?clusterId=" + chassisId + "&unitId=" + vs2UnitId, "cp-vs2"));
        for (String runId : cpRunIds) {
            initialCalls.add(new Call("GET /api/v2/cp-failover/runs/*",
                    "/api/v2/cp-failover/runs/" + runId, markers.getOrDefault(runId, "cp-run")));
        }

        initialCalls.add(new Call("GET /api/v2/cp-failover/summary", "/api/v2/cp-failover/summary", "fleet-summary"));

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        Map<String, Integer> byKey = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int[] total = {0};
        String maskedCpClusterRef = null;

        for (Call call : initialCalls) {
            Resp resp = recordCall(call, cookie, leaks, seen, byKey, report, total);
            if ("fleet-summary".equals(call.detail())) {
                maskedCpClusterRef = extractMaskedClusterRef(resp.body(), chassisId, e.cpVsx().cluster());
            }
        }
        assertNotNull(maskedCpClusterRef, "masked cluster pseudonym extracted from summary");

        List<Call> unitCalls = List.of(
                new Call("GET /api/v2/cp-failover/units", "/api/v2/cp-failover/units", "cp-bare"),
                new Call("GET /api/v2/cp-failover/units",
                        "/api/v2/cp-failover/units?clusterRef=" + enc(e.cpVsx().cluster()), "cp-raw-ref"),
                new Call("GET /api/v2/cp-failover/units",
                        "/api/v2/cp-failover/units?clusterRef=" + enc(maskedCpClusterRef), "cp-masked-ref"),
                new Call("GET /api/v2/cp-failover/units",
                        "/api/v2/cp-failover/units?memberDeviceId=" + enc(e.cpVsx().a().id()), "cp-member"),
                new Call("GET /api/v2/cp-failover/units/*",
                        "/api/v2/cp-failover/units/" + chassisId, "cp-chassis-id"),
                // /units/{clusterId} resolves by clusterId (opaque(clusterRef)), so a per-VS unitId answers 409 CLUSTER_NOT_FOUND.
                new Call("GET /api/v2/cp-failover/units/*",
                        "/api/v2/cp-failover/units/" + vs1UnitId, "cp-vs1-id-refused", 409),
                new Call("GET /api/v2/asa-failover/units", "/api/v2/asa-failover/units", "asa-bare"),
                new Call("GET /api/v2/asa-failover/units",
                        "/api/v2/asa-failover/units?clusterRef=" + enc(e.asa().cluster()), "asa-raw-ref"),
                new Call("GET /api/v2/asa-failover/units",
                        "/api/v2/asa-failover/units?memberDeviceId=" + enc(e.asa().a().id()), "asa-member"),
                new Call("GET /api/v2/asa-failover/units/*",
                        "/api/v2/asa-failover/units/" + asaId, "asa-cluster-id"),
                new Call("GET /api/v2/fgt-failover/units", "/api/v2/fgt-failover/units", "fgt-bare"),
                new Call("GET /api/v2/fgt-failover/units",
                        "/api/v2/fgt-failover/units?clusterRef=" + enc(e.fgt().cluster()), "fgt-raw-ref"),
                new Call("GET /api/v2/fgt-failover/units",
                        "/api/v2/fgt-failover/units?memberDeviceId=" + enc(e.fgt().a().id()), "fgt-member"),
                new Call("GET /api/v2/fgt-failover/units/*",
                        "/api/v2/fgt-failover/units/" + fgtId, "fgt-cluster-id"));

        for (Call call : unitCalls) {
            Resp resp = recordCall(call, cookie, leaks, seen, byKey, report, total);
            if (call.detail().endsWith("-bare") || "cp-masked-ref".equals(call.detail())) {
                assertEquals("[]", resp.body().strip(), call.detail() + " returns empty list without leaking");
            } else if ("cp-vs1-id-refused".equals(call.detail())) {
                assertTrue(resp.body().contains("CLUSTER_NOT_FOUND"), "per-VS unitId on /units/{clusterId} answers CLUSTER_NOT_FOUND");
            } else if ("cp-raw-ref".equals(call.detail()) || "cp-member".equals(call.detail())
                    || "cp-chassis-id".equals(call.detail())) {
                assertBothVsxUnitKinds(resp.body(), chassisId, vs1UnitId, vs2UnitId, call.detail());
            }
        }

        int totalCalls = initialCalls.size() + unitCalls.size();
        report.forEach(System.out::println);
        System.out.println("AIVIEW_VSX_REQUEST_BOUND_SUMMARY calls=" + totalCalls + " routes=" + byKey.size()
                + " planted_values=" + rawValues.size() + " surviving_raw_occurrences=" + total[0] + " sent=" + SENT);

        assertEquals(Map.of("GET", totalCalls), SENT, "only GET requests are sent after login");
        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            if (!seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected " + expected + " saw " + seen.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "all seeded VSX, sequence, request-bound approval, and HA-pair markers must appear");
        assertEquals(KNOWN_LEAKS, leaks,
                "zero planted raw values or execution nonces may survive in any aiview response");
    }

    private static Resp recordCall(Call call, String cookie, Map<String, Map<ValueClass, Integer>> leaks,
            Map<String, Set<String>> seen, Map<String, Integer> byKey, List<String> report, int[] total)
            throws Exception {
        Resp resp = get(call.path(), cookie);
        assertEquals(call.expectedStatus(), resp.status(), call.key() + " (" + call.detail() + ") HTTP status");
        assertFalse(resp.body().isBlank(), "response body is non-blank: " + call.key() + " (" + call.detail() + ")");
        assertFalse(resp.body().contains("executionNonce") || resp.body().contains("execution_nonce"),
                "executionNonce JSON key withheld from aiview: " + call.key() + " (" + call.detail() + ")");

        Map<ValueClass, Integer> found = count(resp.body(), rawValues);
        if (!found.isEmpty()) {
            leaks.computeIfAbsent(call.key(), k -> new TreeMap<>());
            found.forEach((cls, n) -> leaks.get(call.key()).merge(cls, n, Integer::sum));
        }
        Set<String> kinds = seen.computeIfAbsent(call.key(), k -> new TreeSet<>());
        markers.forEach((marker, what) -> {
            if (resp.body().contains(marker)) kinds.add(what);
        });
        if (resp.body().contains("\"unitKind\":\"VSX_CHASSIS\"")) kinds.add("vsx-chassis-unit");
        if (resp.body().contains("\"unitKind\":\"VIRTUAL_SYSTEM\"")) kinds.add("vsx-virtual-system-unit");
        if (resp.body().contains("\"FAILOVER_ALL_VIRTUAL_SYSTEMS\"")) kinds.add("vsx-all-vs-action");
        if (resp.body().contains("\"OPERATION_ADMIN_TWO_PERSON\"")) kinds.add("two-person-policy");
        if (resp.body().contains("\"checkNo\"")) kinds.add("check-result");
        if (resp.body().contains("\"haView\"")) kinds.add("ha-view");

        int sum = found.values().stream().mapToInt(Integer::intValue).sum();
        total[0] += sum;
        byKey.merge(call.key(), 1, Integer::sum);
        report.add(String.format(Locale.ROOT, "AIVIEW_VSX_FAILOVER %3d %-42s %-20s raw=%s",
                resp.status(), call.key(), call.detail(), found.isEmpty() ? "0" : found.toString()));
        return resp;
    }

    private static String extractMaskedClusterRef(String summaryBody, String chassisId, String rawCluster)
            throws Exception {
        JsonNode root = JSON.readTree(summaryBody);
        assertTrue(root.isArray(), "summary returns a JSON array");
        for (JsonNode row : root) {
            if (chassisId.equals(row.path("unitId").asText())
                    && "VSX_CHASSIS".equals(row.path("unitKind").asText())) {
                String maskedRef = row.path("cluster_member_ref").asText();
                String maskedName = row.path("maskedName").asText();
                assertTrue(maskedRef.startsWith("CLS-"), "cluster_member_ref is pseudonymised to CLS-*");
                assertEquals(maskedRef, maskedName, "maskedName matches pseudonymised cluster_member_ref on chassis");
                assertNotEquals(rawCluster, maskedRef);
                return maskedRef;
            }
        }
        throw new AssertionError("VSX_CHASSIS unit missing from /api/v2/cp-failover/summary");
    }

    private static void assertBothVsxUnitKinds(String body, String chassisId, String vs1UnitId, String vs2UnitId,
            String detail) throws Exception {
        JsonNode units = JSON.readTree(body);
        assertEquals(3, units.size(), detail + " returns 1 VSX_CHASSIS + 2 VIRTUAL_SYSTEM units");
        assertEquals(chassisId, units.get(0).path("unitId").asText(), detail + " lists chassis first");
        assertEquals("VSX_CHASSIS", units.get(0).path("unitKind").asText(), detail + " unit 0 is VSX_CHASSIS");
        Set<String> vsUnitIds = Set.of(units.get(1).path("unitId").asText(), units.get(2).path("unitId").asText());
        assertEquals(Set.of(vs1UnitId, vs2UnitId), vsUnitIds, detail + " lists both VS unit IDs");
        for (int i = 1; i <= 2; i++) {
            assertEquals("VIRTUAL_SYSTEM", units.get(i).path("unitKind").asText(), detail + " VS unitKind");
            assertTrue(units.get(i).path("virtual_system").asText().startsWith("VS-"),
                    detail + " VS display label pseudonymised to VS-*");
        }
    }

    @Test
    void counterMatchesAddressesNoncesAndNamesWithoutPrefixFalsePositives() {
        String nonce = "4f2a91e8-7c10-4b88-9a01-33d4e5f60718";
        Map<String, ValueClass> raw = Map.of(
                "198.51.100.24", ValueClass.ADDRESS,
                "vs-core-x1a2b3c", ValueClass.VS_NAME,
                nonce, ValueClass.EXECUTION_NONCE);
        assertEquals(Map.of(ValueClass.ADDRESS, 2, ValueClass.VS_NAME, 1, ValueClass.EXECUTION_NONCE, 1),
                count("[\"198.51.100.24\",\"198-51-100-24\",\"198.51.100.240\",\"VS-CORE-X1A2B3C\",\"" + nonce + "\"]", raw));
        assertEquals(Map.of(), count("{\"virtual_system\":\"VS-ROMEO-01\",\"ip\":\"240.18.44.24\"}", raw));
    }
}
