package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 7 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp; post-parsing
 * masking law"): the failover planes the earlier rounds left empty -- failover incidents, config-sync runs, runs in an
 * in-flight state (dispatch intents, check results) and the failover execution route. A synthetic estate is seeded into a
 * real PostgreSQL 16 database through the failover repository and plain SQL, the application is booted, the aiview
 * persona ({@code role:replay_viewer}) logs in and GETs every route below, and every planted raw value is counted in every
 * body. Output names route, status and value <em>class</em> with a count -- never a value.
 *
 * <p>Planted per vendor family (Check Point twice, Palo Alto, Cisco ASA, FortiGate): the device host names, member
 * addresses and serials, the cluster name, and a principal named in free text; the free text sits in the incident run's
 * message and failed check, the check-result JSON text of every run, the approval reason, the run message of the in-flight
 * and the readiness runs, and (Palo Alto) the config-sync result text and its follow-up refusal.</p>
 *
 * <p>Seeded states: a Check Point run stopped with an unknown outcome and an open incident; a Palo Alto run stopped the
 * same way; a Check Point run in an in-flight state ({@code FAILING_OVER}, leased, with an unresolved dispatch intent);
 * finished readiness runs for Palo Alto, Cisco ASA and FortiGate; a finished Palo Alto config sync. The execution route
 * reads an in-memory history the test cannot seed (see below).</p>
 *
 * <p>Route table (every route is mapped under the four prefixes {@code /api/v2/cp-failover}, {@code /pan-failover},
 * {@code /asa-failover}, {@code /fgt-failover}; {@code *} is a path variable):</p>
 * <pre>
 * GET {cp,pan}-failover/incidents               COVERED     (incident rows, members, cluster name, masked name)
 * GET {asa,fgt}-failover/incidents              NOT COVERED aiview refused 403 (ACTION_MAPPING_REQUIRED): route unmapped, answers nothing
 * GET pan-failover/config-syncs/*               COVERED     (the seeded Palo Alto config-sync record)
 * GET cp-failover/config-syncs/*                NOT COVERED answers a refusal (CONFIG_SYNC_NOT_SUPPORTED), no record
 * GET {asa,fgt}-failover/config-syncs/*         NOT COVERED aiview refused 403 (unmapped)
 * GET {cp,pan}-failover/runs?...                COVERED     (list of the unit's runs: readiness, stopped, in flight)
 * GET {asa,fgt}-failover/runs?...               NOT COVERED answers 200 [] in this boot because AsaFailoverReadinessSwitch and
 *                                               FgtFailoverReadinessSwitch stay at their default OFF (CpFailoverService#runs:
 *                                               readinessUnavailable); covered with both switches enabled in
 *                                               AiviewRawVersusMaskedSweepTest
 * GET {cp,pan,asa,fgt}-failover/runs/*          COVERED     (run detail with check results, per seeded run)
 * GET {cp,pan}-failover/units?...               COVERED     (seeded unit and member ids, OPEN_INCIDENT refusal; pan also configSync)
 * GET {asa,fgt}-failover/units?...              COVERED     (seeded unit and member ids, FAILOVER_VENDOR_NOT_AVAILABLE refusal)
 * GET {cp,pan,asa,fgt}-failover/units/*         COVERED     (seeded unit and member ids per cluster)
 * GET {cp,pan,asa,fgt}-failover/summary         COVERED     (fleet summary across all five seeded pairs: units, members, pan readiness
 *                                               checks and configSync, cp/pan OPEN_INCIDENT refusal, asa/fgt vendor refusal)
 * GET {cp,pan}-failover/approvals?...           COVERED     (approval reason text)
 * GET {asa,fgt}-failover/approvals              NOT COVERED aiview refused 403 (unmapped)
 * GET /api/v2/failover/*&#47;executions/*          NOT COVERED aiview refused 403: the route has no entry in the action map, so the gate
 *                                               answers before the controller; the execution history is in memory, so no row can be
 *                                               planted either. Only the refusal body is checked, and it carries nothing.
 * non-GET approval / incident / resume / config-sync routes  NOT CALLED (the test client only issues GET; the static audit
 *                                               below asserts that none of them is aiview-reachable except the readiness request)
 * </pre>
 *
 * <p>Result: no host name, member address, serial or cluster name survives anywhere, and the catalogued administrator is
 * masked; one finding is pinned in {@link #KNOWN_LEAKS} (an uncatalogued person named in free text). No production code is
 * changed by this lane.</p>
 *
 * <p>A refusal is "not covered", never "covered": the refusal body is asserted to carry no planted value, which proves
 * nothing about the data behind the route. If a gate entry is ever added for a refused route this test fails on the
 * status and the route must be moved to COVERED with seeded data.</p>
 *
 * <p>The class is self-contained on purpose (the other sweeps are edited by other lanes): the boot, login, row-insert and
 * body-scan helpers are copies of the round 4 sweep's.</p>
 */
class AiviewFailoverIncidentsSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a planted value is; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, CLUSTER_NAME, ADDRESS, SERIAL,
        /** An administrator the estate name catalog knows (a policy change author): the free-text masker can replace it. */
        PRINCIPAL,
        /** A person named in free text who appears in no catalog column: no key of its own, nothing to match it against. */
        UNCATALOGUED_PRINCIPAL
    }

    /**
     * Surviving raw values pinned by exact route and value class: {@code route -> (class -> count)}. Must equal the
     * findings, so a new leak, a larger count, or a fix that shrinks a count without updating this map fails the test.
     *
     * <p><b>Finding (open, left for the lane that owns the shared masking advice):</b> a person named in free text who
     * appears in no estate-catalog column survives in every failover free-text field aiview reads. The free-text masker
     * ({@code FreeTextEstate}) replaces serials and the administrator names the estate name catalog knows (policy change
     * authors); it has no key to recognise anyone else. Here the test plants one such name next to a catalogued one in the
     * run message, the check-result text, the approval reason and the config-sync result and follow-up refusal: the
     * catalogued administrator, every host name, member address, serial and cluster name are replaced everywhere (count 0),
     * the uncatalogued name is returned verbatim on the 14 routes below. Whether production writes such a name into these
     * fields is not established here: the run message and check text are worker-generated, the approval reason is typed by
     * an administrator. The counts are the planted occurrences those routes return (summary routes carry the latest
     * readiness checks; the run detail carries every check of the run).</p>
     */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.ofEntries(
            Map.entry("GET /api/v2/asa-failover/runs/*", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 9)),
            Map.entry("GET /api/v2/asa-failover/summary", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 7)),
            Map.entry("GET /api/v2/cp-failover/approvals", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 2)),
            Map.entry("GET /api/v2/cp-failover/runs", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 1)),
            Map.entry("GET /api/v2/cp-failover/runs/*", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 17)),
            Map.entry("GET /api/v2/cp-failover/summary", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 7)),
            Map.entry("GET /api/v2/fgt-failover/runs/*", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 9)),
            Map.entry("GET /api/v2/fgt-failover/summary", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 7)),
            Map.entry("GET /api/v2/pan-failover/approvals", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 1)),
            Map.entry("GET /api/v2/pan-failover/config-syncs/*", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 2)),
            Map.entry("GET /api/v2/pan-failover/runs", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 2)),
            Map.entry("GET /api/v2/pan-failover/runs/*", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 18)),
            Map.entry("GET /api/v2/pan-failover/summary", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 7)),
            Map.entry("GET /api/v2/pan-failover/units", Map.of(ValueClass.UNCATALOGUED_PRINCIPAL, 2)));

    /** Routes aiview must be refused on (401/403), with nothing in the refusal: NOT COVERED. */
    static final Set<String> MUST_BE_REFUSED = Set.of(
            "GET /api/v2/asa-failover/incidents", "GET /api/v2/fgt-failover/incidents",
            "GET /api/v2/asa-failover/config-syncs/*", "GET /api/v2/fgt-failover/config-syncs/*",
            "GET /api/v2/asa-failover/approvals", "GET /api/v2/fgt-failover/approvals",
            "GET /api/v2/failover/*/executions/*");

    /** Reachable routes that answer a refusal and no record (status other than 200): NOT COVERED. */
    static final Set<String> ANSWERS_A_REFUSAL = Set.of("GET /api/v2/cp-failover/config-syncs/*");

    /**
     * Reachable routes that answer HTTP 200 with an empty list in this test's boot because
     * {@code AsaFailoverReadinessSwitch} and {@code FgtFailoverReadinessSwitch} stay at their default {@code OFF}
     * ({@code CpFailoverService#runs}: {@code readinessUnavailable}): NOT COVERED here; covered with both readiness
     * switches enabled in {@link AiviewRawVersusMaskedSweepTest}.
     */
    static final Set<String> EMPTY_WITH_DEFAULT_READINESS_SWITCH = Set.of(
            "GET /api/v2/asa-failover/runs", "GET /api/v2/fgt-failover/runs");

    /** Markers expected on every prefix's fleet summary ({@code CpFailoverController#summary} lists all five seeded pairs). */
    static final Set<String> SUMMARY_MARKERS = Set.of(
            "unit-cp1", "member-cp1-a", "member-cp1-b",
            "unit-cp2", "member-cp2-a", "member-cp2-b",
            "unit-pan", "member-pan-a", "member-pan-b",
            "unit-asa", "member-asa-a", "member-asa-b",
            "unit-fgt", "member-fgt-a", "member-fgt-b",
            "check-result", "incident-refusal", "config-sync", "vendor-readiness-only");

    /** Views that must show seeded rows (by marker), so a zero count is a count over real data and not an empty page. */
    static final Map<String, Set<String>> COVERAGE = Map.ofEntries(
            Map.entry("GET /api/v2/cp-failover/incidents", Set.of("incident-stopped", "incident-members")),
            Map.entry("GET /api/v2/pan-failover/incidents", Set.of("incident-stopped")),
            Map.entry("GET /api/v2/pan-failover/config-syncs/*", Set.of("config-sync")),
            Map.entry("GET /api/v2/cp-failover/runs", Set.of("run-in-flight", "run-stopped")),
            Map.entry("GET /api/v2/pan-failover/runs", Set.of("run-stopped", "run-readiness")),
            Map.entry("GET /api/v2/cp-failover/runs/*", Set.of("run-in-flight", "run-stopped", "check-result", "incident-ref")),
            Map.entry("GET /api/v2/pan-failover/runs/*", Set.of("run-stopped", "run-readiness", "check-result")),
            Map.entry("GET /api/v2/asa-failover/runs/*", Set.of("run-readiness", "check-result")),
            Map.entry("GET /api/v2/fgt-failover/runs/*", Set.of("run-readiness", "check-result")),
            Map.entry("GET /api/v2/cp-failover/units", Set.of(
                    "unit-cp1", "member-cp1-a", "member-cp1-b",
                    "unit-cp2", "member-cp2-a", "member-cp2-b", "incident-refusal")),
            Map.entry("GET /api/v2/pan-failover/units", Set.of(
                    "unit-pan", "member-pan-a", "member-pan-b", "incident-refusal", "config-sync")),
            Map.entry("GET /api/v2/asa-failover/units", Set.of(
                    "unit-asa", "member-asa-a", "member-asa-b", "vendor-readiness-only")),
            Map.entry("GET /api/v2/fgt-failover/units", Set.of(
                    "unit-fgt", "member-fgt-a", "member-fgt-b", "vendor-readiness-only")),
            Map.entry("GET /api/v2/cp-failover/units/*", Set.of(
                    "unit-cp1", "member-cp1-a", "member-cp1-b",
                    "unit-cp2", "member-cp2-a", "member-cp2-b")),
            Map.entry("GET /api/v2/pan-failover/units/*", Set.of("unit-pan", "member-pan-a", "member-pan-b")),
            Map.entry("GET /api/v2/asa-failover/units/*", Set.of("unit-asa", "member-asa-a", "member-asa-b")),
            Map.entry("GET /api/v2/fgt-failover/units/*", Set.of("unit-fgt", "member-fgt-a", "member-fgt-b")),
            Map.entry("GET /api/v2/cp-failover/summary", SUMMARY_MARKERS),
            Map.entry("GET /api/v2/pan-failover/summary", SUMMARY_MARKERS),
            Map.entry("GET /api/v2/asa-failover/summary", SUMMARY_MARKERS),
            Map.entry("GET /api/v2/fgt-failover/summary", SUMMARY_MARKERS),
            Map.entry("GET /api/v2/cp-failover/approvals", Set.of("approval")),
            Map.entry("GET /api/v2/pan-failover/approvals", Set.of("approval")));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Estate estate;

    // ------------------------------------------------------------------ boot (copied from AiviewUnseededPlanesSweepTest)

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_failover_incidents");
        work = Files.createTempDirectory("ui2-aiview-failover-incidents-");
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

    /** The env-configured (here: absent) artefact store is replaced, and the fixed machine port is not opened. */
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
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
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

    record Device(String id, String vendor, String hostname, String serial, String address, String cluster) {
    }

    /** One failover pair of one vendor family; {@code prefix} is the route prefix that serves it. */
    record Pair(String name, String vendor, String prefix, String cluster, Device a, Device b) {
        String clusterId() {
            return UUID.nameUUIDFromBytes(cluster.getBytes(StandardCharsets.UTF_8)).toString();
        }

        Set<String> members() {
            return Set.of(a.id(), b.id());
        }
    }

    /** Every planted raw value, generated per run. */
    record Estate(Pair cp1, Pair cp2, Pair pan, Pair asa, Pair fgt, String principal, String stranger,
            Map<String, ValueClass> raw) {

        static Estate generate() {
            int base = 20 + RANDOM.nextInt(150);
            Pair cp1 = pair("cp1", "check_point", "/api/v2/cp-failover", base, 0);
            Pair cp2 = pair("cp2", "check_point", "/api/v2/cp-failover", base, 2);
            Pair pan = pair("pan", "palo_alto", "/api/v2/pan-failover", base, 4);
            Pair asa = pair("asa", "cisco_asa", "/api/v2/asa-failover", base, 6);
            Pair fgt = pair("fgt", "fortinet", "/api/v2/fgt-failover", base, 8);
            String principal = "adm" + tag() + ".ops";
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Pair p : List.of(cp1, cp2, pan, asa, fgt)) {
                raw.put(p.cluster(), ValueClass.CLUSTER_NAME);
                for (Device d : List.of(p.a(), p.b())) {
                    raw.put(d.hostname(), ValueClass.HOSTNAME);
                    raw.put(d.serial(), ValueClass.SERIAL);
                    raw.put(d.address(), ValueClass.ADDRESS);
                }
            }
            raw.put(principal, ValueClass.PRINCIPAL);
            String stranger = "rev" + tag() + ".desk";
            raw.put(stranger, ValueClass.UNCATALOGUED_PRINCIPAL);
            return new Estate(cp1, cp2, pan, asa, fgt, principal, stranger, Map.copyOf(raw));
        }

        List<Pair> pairs() {
            return List.of(cp1, cp2, pan, asa, fgt);
        }
    }

    private static Pair pair(String name, String vendor, String prefix, int base, int offset) {
        String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
        Device a = new Device(Ui2Rows.opaqueId("dev"), vendor, "FW-TANGO-" + tag().toUpperCase(Locale.ROOT),
                "FD" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + offset), cluster);
        Device b = new Device(Ui2Rows.opaqueId("dev"), vendor, "ist" + tag() + "-fw-02",
                "FD" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + offset + 1), cluster);
        return new Pair(name, vendor, prefix, cluster, a, b);
    }

    // ------------------------------------------------------------------ seeding

    /** What the seeding produced, by marker: the ids a route must show for a zero count to mean anything. */
    private static final Map<String, String> markers = new LinkedHashMap<>();
    private static final Map<String, List<String>> runIds = new TreeMap<>();
    private static String configSyncId;

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, () -> {
                for (Pair p : e.pairs()) {
                    for (Device d : List.of(p.a(), p.b())) {
                        exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                                + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                                + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                                + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                                + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                                + "'CORROBORATED', ?, 'synthetic-model', 'R81.20', ?, ?, now(), ?, true, ?)",
                                d.id(), d.vendor(), credential, d.hostname(), d == p.a() ? "active" : "standby", d.serial(),
                                d.cluster(), d.vendor() + "|" + d.serial());
                        exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, ?, ?)",
                                Ui2Rows.opaqueId("ep"), d.id(), p.vendor().equals("palo_alto") ? "pan_xml_api" : "ssh_exec", d.address());
                        exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                                + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                        // one inventory run per member: the failover unit needs inventory to exist (and, for Palo Alto, the HA fact)
                        String job = Ui2Rows.opaqueId("job");
                        exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                                + "state, action_class, finished_at) values (?, 'inventory_collect', 'device', ?, ?, 'COMPLETED', 'read', now())",
                                job, d.id(), Ui2Rows.ACTOR);
                        String run = Ui2Rows.opaqueId("inv");
                        exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                                + "values (?, ?, ?, now(), 1)", run, d.id(), job);
                        if (p.vendor().equals("palo_alto")) {
                            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                                    + "values (?, ?, 'physical', ?, 'active-passive', 'pan_high_availability_state')",
                                    Ui2Rows.opaqueId("ha"), run, d == p.a() ? "ACTIVE" : "PASSIVE");
                        }
                    }
                }
            });
        }
        for (Pair p : e.pairs()) {
            markers.put(p.clusterId(), "unit-" + p.name());
            markers.put(p.a().id(), "member-" + p.name() + "-a");
            markers.put(p.b().id(), "member-" + p.name() + "-b");
        }
        seedKnownAdministrator(e);
        var repo = new JooqCpFailoverRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),
                true, JobWindowTestPolicy.PERMISSIVE);
        // Readiness runs first (they do not hold the fleet fence), then the stopped runs (their incidents are per unit), and the
        // in-flight run last: its unresolved dispatch intent blocks every later admission.
        readinessRun(repo, e, e.pan(), 1);
        readinessRun(repo, e, e.asa(), 1);
        readinessRun(repo, e, e.fgt(), 1);
        stoppedRunWithIncident(repo, e, e.cp1());
        stoppedRunWithIncident(repo, e, e.pan());
        seedConfigSync(e);
        inFlightRun(repo, e, e.cp2());
    }

    /**
     * The known administrator: a policy change author. That is where the estate name catalog learns administrators
     * ({@code policy_rule_history.changed_by} and the {@code last-modifier} of a snapshot rule), so the free-text masker
     * can replace this name; the other principal appears in no catalog column.
     */
    private static void seedKnownAdministrator(Estate e) throws Exception {
        String policyId = "policy-" + UUID.randomUUID();
        String ruleId = "rule-" + UUID.randomUUID();
        var any = new PolicySnapshot.Cell(List.of("any-obj"), false);
        var rule = new PolicySnapshot.Rule(ruleId, UUID.randomUUID().toString(), 1, "allow-" + tag(), true, any, any, any, any,
                "Accept", "Log", "changed by " + e.principal(),
                Map.of("from", List.of("any"), "to", List.of("any"), "last-modifier", List.of(e.principal())));
        var metadata = new PolicySnapshot.Metadata(policyId, e.pan().a().id(), "pano-" + tag(), "PAN", "domain-" + UUID.randomUUID(),
                "shared", "pkg-" + tag(), Instant.now().toString(), null, List.of());
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("section-1", "Pre Rules", "Network", null, List.of(rule))),
                Map.of("any-obj", new PolicySnapshot.PolicyObject("any-obj", "Any", "any", List.of(), List.of(), "RESOLVED")),
                List.of());
        try (Connection c = fixture.migrateConnection()) {
            audited(c, Ui2Rows.ACTOR, () -> {
                exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                        policyId, JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
                exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, collected_at, "
                        + "changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), '2026-11-20T09:00:00Z', ?, '[]'::jsonb)",
                        policyId, ruleId, ruleId, e.principal());
            });
        }
    }

    private static String freeText(Estate e, Pair p, String what) {
        return what + " on " + p.a().hostname() + " (" + p.a().address() + ", serial " + p.a().serial() + ") and "
                + p.b().hostname() + " (" + p.b().address() + ", serial " + p.b().serial() + ") of " + p.cluster()
                + " requested by " + e.principal() + " and reviewed by " + e.stranger();
    }

    private static String checkJson(Estate e, Pair p, Device d, String role) throws Exception {
        return JSON.writeValueAsString(Map.of("role", role, "failoverOn", true, "mode", "active-passive",
                "detail", "member " + d.hostname() + " at " + d.address() + " serial " + d.serial() + " of " + p.cluster()
                        + " noted by " + e.principal() + " and " + e.stranger()));
    }

    private static void checks(JooqCpFailoverRepository repo, Estate e, Pair p, String run) throws Exception {
        for (String phase : List.of("pre", "post")) {
            for (Device d : List.of(p.a(), p.b())) {
                repo.check(run, phase, d.id(), null, 1, "PASS", checkJson(e, p, d, d == p.a() ? "ACTIVE" : "STANDBY"));
                repo.check(run, phase, d.id(), null, 2, "UNKNOWN", JSON.writeValueAsString(Map.of(
                        "reason", "NOT_EVALUATED", "detail", freeText(e, p, "check not evaluated"))));
            }
        }
    }

    private static void readinessRun(JooqCpFailoverRepository repo, Estate e, Pair p, int unused) throws Exception {
        var decision = repo.requestReadiness(p.cluster(), null, Ui2Rows.ACTOR, p.a().id(), p.vendor());
        assertEquals("ADMITTED", decision.code(), "readiness admission of " + p.name());
        checks(repo, e, p, decision.runId());
        repo.state(decision.runId(), "DONE", "DONE", "READY", null, freeText(e, p, "readiness finished"));
        runIds.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(decision.runId());
        markers.put(decision.runId(), "run-readiness");
    }

    private static JooqCpFailoverRepository.Decision admitted(JooqCpFailoverRepository repo, Estate e, Pair p) {
        var request = repo.createRequestApproval(UUID.randomUUID().toString(), p.cluster(), null, p.clusterId(), p.members(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), freeText(e, p, "approval"), Ui2Rows.ACTOR,
                p.vendor(), JooqCpFailoverRepository.ADMIN_SINGLE);
        markers.put(request.approval().id(), "approval");
        var decision = repo.requestBound(p.cluster(), null, Instant.now(), Ui2Rows.ACTOR, p.a().id(), true, p.vendor(), p.members(),
                request.approval().id(), request.revision(), request.executionNonce(), true, request.policy());
        assertEquals("ADMITTED", decision.code(), "failover admission of " + p.name());
        return decision;
    }

    /** A run stopped after a possibly sent write: state STOPPED, outcome unknown, one active incident for the unit. */
    private static void stoppedRunWithIncident(JooqCpFailoverRepository repo, Estate e, Pair p) throws Exception {
        var decision = admitted(repo, e, p);
        String run = decision.runId();
        update("update failover_run set mutation_possible=true where run_id=?", run);
        checks(repo, e, p, run);
        repo.state(run, "STOPPED", "POSTCHECK", "OUTCOME_UNKNOWN", "CHECK_2", freeText(e, p, "stopped, outcome unknown"));
        assertTrue(repo.finished(run));
        // keep the lease claim of the later in-flight run away from this job
        update("update jobs set state='OUTCOME_UNKNOWN' where job_id=(select job_id from failover_run where run_id=?)", run);
        runIds.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(run);
        markers.put(run, "run-stopped");
    }

    /** A leased, executing run with an unresolved dispatch intent (the send may have happened, nothing observed). */
    private static void inFlightRun(JooqCpFailoverRepository repo, Estate e, Pair p) throws Exception {
        var decision = admitted(repo, e, p);
        String run = decision.runId();
        update("update module_runtime_control set owner_instance='general-synthetic',owner_heartbeat_at=now() where module='general'");
        var leases = new JooqJobLeaseDao(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),
                JobWindowTestPolicy.PERMISSIVE);
        var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
        assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
        checks(repo, e, p, run);
        assertTrue(repo.workerState(run, job.leaseEpoch(), "FAILING_OVER", "FAILING_OVER", null, null,
                freeText(e, p, "failing over")));
        repo.prepareDispatch(run, job.leaseEpoch(), 0, p.a().id(), "cp_failover_down", "operational-state-change");
        runIds.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(run);
        markers.put(run, "run-in-flight");
    }

    /** A finished Palo Alto config sync: the result text and the follow-up refusal carry raw values. */
    private static void seedConfigSync(Estate e) throws Exception {
        Pair p = e.pan();
        String readiness = runIds.get("pan").get(0);
        configSyncId = "sync-" + UUID.randomUUID();
        try (Connection c = fixture.migrateConnection()) {
            audited(c, Ui2Rows.ACTOR, () -> {
                String job = Ui2Rows.opaqueId("job");
                exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                        + "state, action_class, finished_at) values (?, 'pan_config_sync', 'device', ?, ?, 'COMPLETED', 'read', now())",
                        job, p.a().id(), Ui2Rows.ACTOR);
                exec(c, "insert into pan_config_sync(sync_id, cluster_ref, source_member_ref, target_member_ref, member_set_revision, "
                        + "readiness_run_id, initiated_by, initiator_role, requested_in_business_hours, job_id, state) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'security_admin', false, ?, 'QUEUED')",
                        configSyncId, p.cluster(), p.a().id(), p.b().id(), JooqCpFailoverRepository.memberSetRevision(p.members()),
                        readiness, Ui2Rows.ACTOR, job);
                exec(c, "update pan_config_sync set state='DONE', outcome=?, device_status='success', mutation_possible=true, "
                        + "followup_refusal=?, started_at=now(), finished_at=now() where sync_id=?",
                        freeText(e, p, "config sync finished"), freeText(e, p, "follow-up readiness refused"), configSyncId);
            });
        }
        markers.put(configSyncId, "config-sync");
    }

    private static void update(String sql, Object... args) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            audited(c, Ui2Rows.ACTOR, () -> exec(c, sql, args));
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

    // ------------------------------------------------------------------ leak counting (copied from the round 4 sweep)

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

    // ------------------------------------------------------------------ HTTP (copied from the round 4 sweep)

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

    /** Every request the test sent, by method: the sweep calls GET and nothing else. */
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
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
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

    // ------------------------------------------------------------------ the call list

    /** One GET: {@code key} is the route as the static audit names it ({@code GET /a/*}); {@code path} the concrete request. */
    record Call(String key, String path, String detail) {
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final String ALL = "/api/v2";

    static List<Call> calls(Estate e) {
        List<Call> out = new ArrayList<>();
        for (Pair p : e.pairs()) {
            String id = p.clusterId();
            boolean second = p == e.cp2();
            String prefix = p.prefix();
            // the Check Point prefix serves two seeded units: one call set per unit
            out.add(new Call("GET " + prefix + "/units", prefix + "/units?clusterRef=" + enc(p.cluster()), p.name()));
            out.add(new Call("GET " + prefix + "/units/*", prefix + "/units/" + id, p.name()));
            out.add(new Call("GET " + prefix + "/runs", prefix + "/runs?clusterId=" + id + "&unitId=" + id, p.name()));
            for (String run : runIds.getOrDefault(p.name(), List.of())) {
                out.add(new Call("GET " + prefix + "/runs/*", prefix + "/runs/" + run, p.name() + " " + markers.get(run)));
            }
            out.add(new Call("GET " + prefix + "/approvals", prefix + "/approvals?clusterId=" + id + "&unitId=" + id, p.name()));
            if (!second) {
                out.add(new Call("GET " + prefix + "/summary", prefix + "/summary", p.name()));
                out.add(new Call("GET " + prefix + "/incidents", prefix + "/incidents", p.name()));
                out.add(new Call("GET " + prefix + "/config-syncs/*",
                        prefix + "/config-syncs/" + (p == e.pan() ? configSyncId : "sync-" + UUID.randomUUID()), p.name()));
            }
        }
        out.add(new Call("GET " + ALL + "/failover/*/executions/*", ALL + "/failover/" + enc(e.cp1().cluster()) + "/executions/exec-"
                + UUID.randomUUID(), "execution"));
        return out;
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    void noPlantedRawValueSurvivesInTheFailoverIncidentPlanes() throws Exception {
        String cookie = login();
        Estate e = estate;

        Map<String, Boolean> reachable = new TreeMap<>();
        Set<String> nonGetReachable = new TreeSet<>();
        for (var ep : AiviewReachableRoutes.all()) {
            boolean failover = ep.pathTemplate().matches("/api/v2/(cp|pan|asa|fgt)-failover/.*")
                    || ep.pathTemplate().startsWith("/api/v2/failover/");
            if (!failover) continue;
            boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
            if (ep.method().equals("GET")) reachable.merge(ep.key(), may, (a, b) -> a || b);
            else if (may) nonGetReachable.add(ep.key());
        }
        for (String refused : MUST_BE_REFUSED) {
            assertTrue(reachable.containsKey(refused), "the route exists: " + refused);
            assertFalse(reachable.get(refused), "the static audit says aiview cannot reach " + refused);
        }
        // Mutation routes (approvals, incident release, resume, config-sync request) are never called here; the only
        // non-GET failover route aiview is allowed is the readiness request, which this sweep does not send either.
        assertEquals(Set.of("POST /api/v2/cp-failover/units/*/readiness", "POST /api/v2/pan-failover/units/*/readiness",
                "POST /api/v2/asa-failover/units/*/readiness", "POST /api/v2/fgt-failover/units/*/readiness"),
                nonGetReachable, "non-GET failover routes aiview may call (none is called by this test)");

        List<Call> calls = calls(e);
        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Integer> status = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int total = 0;
        // The incident and run reads go first: free-text masking learns names as responses mask them, so a plane read later
        // in a warm process proves less than the first read after the estate loaded (the cold case).
        List<Call> ordered = new ArrayList<>(calls);
        ordered.sort(java.util.Comparator.comparing(c -> !(c.key().endsWith("/incidents") || c.key().contains("/runs"))));
        Map<String, Integer> byKey = new TreeMap<>();
        for (Call call : ordered) {
            Resp resp = get(call.path(), cookie);
            status.merge(call.key(), resp.status(), (a, b) -> a == 200 || b == 200 ? 200 : a);
            Map<ValueClass, Integer> found = count(resp.body(), e.raw());
            if (!found.isEmpty()) {
                leaks.computeIfAbsent(call.key(), k -> new TreeMap<>());
                found.forEach((cls, n) -> leaks.get(call.key()).merge(cls, n, Integer::sum));
            }
            Set<String> kinds = seen.computeIfAbsent(call.key(), k -> new TreeSet<>());
            markers.forEach((marker, what) -> { if (resp.body().contains(marker)) kinds.add(what); });
            if (resp.body().contains("\"checkNo\"")) kinds.add("check-result");
            if (resp.body().contains("\"incidentRef\"") && resp.body().contains("\"members\"")) kinds.add("incident-members");
            if (resp.body().contains("\"incidentActive\":true")) kinds.add("incident-stopped");
            if (resp.body().contains("\"incidentRef\":\"")) kinds.add("incident-ref");
            if (resp.body().contains("\"refusalReason\":\"OPEN_INCIDENT\"")) kinds.add("incident-refusal");
            if (resp.body().contains("\"failoverRefusal\":\"FAILOVER_VENDOR_NOT_AVAILABLE\"")) kinds.add("vendor-readiness-only");
            if (resp.status() == 200) assertFalse(resp.body().isBlank(), "a masked payload is not empty: " + call.key());
            int sum = found.values().stream().mapToInt(Integer::intValue).sum();
            total += sum;
            byKey.merge(call.key(), 1, Integer::sum);
            report.add(String.format(Locale.ROOT, "AIVIEW_FAILOVER %-10s %3d %-56s %-24s raw=%s seeded_rows_seen=%s",
                    reachable.get(call.key()) ? "reachable" : "gated", resp.status(), call.key(), call.detail(),
                    found.isEmpty() ? "0" : found.toString(), kinds));
            if (!reachable.get(call.key())) {
                int code = resp.status();
                assertTrue(code == 401 || code == 403, call.key() + " must refuse aiview, answered " + code);
            }
        }
        report.forEach(System.out::println);
        System.out.println("AIVIEW_FAILOVER_SUMMARY calls=" + calls.size() + " routes=" + byKey.size() + " planted_values="
                + e.raw().size() + " surviving_raw_occurrences=" + total + " sent=" + SENT);

        assertEquals(Map.of("GET", calls.size()), SENT, "only GET requests are sent (the login is the one POST)");
        Set<String> ledger = new TreeSet<>(COVERAGE.keySet());
        assertTrue(java.util.Collections.disjoint(ledger, EMPTY_WITH_DEFAULT_READINESS_SWITCH));
        ledger.addAll(EMPTY_WITH_DEFAULT_READINESS_SWITCH);
        assertTrue(java.util.Collections.disjoint(ledger, ANSWERS_A_REFUSAL));
        ledger.addAll(ANSWERS_A_REFUSAL);
        assertTrue(java.util.Collections.disjoint(ledger, MUST_BE_REFUSED));
        ledger.addAll(MUST_BE_REFUSED);
        assertEquals(byKey.keySet(), ledger, "every called failover route must be classified in the ledger sets");
        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            if (!Integer.valueOf(200).equals(status.get(route))
                    || !seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected 200+" + expected + " saw " + seen.get(route) + " status " + status.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "seeded rows must reach these views, or a zero count proves nothing");
        for (String emptyRoute : EMPTY_WITH_DEFAULT_READINESS_SWITCH) {
            assertEquals(200, status.get(emptyRoute), emptyRoute + " answers 200 [] while readiness switch is OFF");
            assertEquals(Set.of(), seen.getOrDefault(emptyRoute, Set.of()),
                    emptyRoute + " must see no seeded rows while readiness switch is OFF");
        }
        for (Call call : calls) {
            boolean refusalOnly = MUST_BE_REFUSED.contains(call.key()) || ANSWERS_A_REFUSAL.contains(call.key());
            if (!refusalOnly) {
                assertEquals(200, status.get(call.key()), call.key() + " must answer 200 with seeded data (best status over its calls)");
            }
        }
        assertEquals(KNOWN_LEAKS, leaks,
                "planted raw value counts surviving in aiview GET bodies per (route, value class); KNOWN_LEAKS must match exactly");
    }

    /** The counter itself: an address is not matched as the prefix of a longer address, names match case-insensitively. */
    @Test
    void theCounterSeesSpellingsAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of("192.0.2.15", ValueClass.ADDRESS, "clx-romeo-x0a0b0c", ValueClass.CLUSTER_NAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.CLUSTER_NAME, 1), count("cluster CLX-ROMEO-X0A0B0C", raw));
        assertEquals(Map.of(), count("{\"cluster\":\"CLS-ROMEO-01\"}", raw));
    }
}
