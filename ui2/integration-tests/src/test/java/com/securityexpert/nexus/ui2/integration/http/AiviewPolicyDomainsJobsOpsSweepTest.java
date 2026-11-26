package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.logdetect.JooqLogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.policy.HaPolicyPairing;
import com.securityexpert.nexus.ui2.service.policy.PolicyHygieneFleetSummary;
import com.securityexpert.nexus.ui2.service.policy.PolicyPrivacy;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 9 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp; post-parsing
 * masking law" and "Sensitive identity reporting law"): the policy domain and object planes ({@code PolicyController},
 * {@code PolicyCollectionController}) and the Jobs and operations aggregates. A synthetic estate with runtime-generated
 * raw values is seeded into a real PostgreSQL 16 database and encrypted temporary artefact store, the application boots
 * with scheduling disabled (with {@link PolicyHygieneFleetSummary#refreshNext()} ticked to completion before HTTP reads),
 * the {@code aiview} persona ({@code role:replay_viewer}) logs in and issues {@code GET} requests only, and every
 * response body is scanned by {@link ValueClass}. Output reports route keys, HTTP status codes and counts per value
 * class -- never a raw value.
 *
 * <p><b>Re-derivation of the uncovered route set from {@code tools/security/aiview_leak_audit/routes.json} (177 GET
 * routes) against existing {@code Aiview*SweepTest} classes:</b></p>
 * <ul>
 *   <li>Rounds 1-3 ({@code AiviewRawVersusMaskedSweepTest}): core device/cluster/backup/inventory/discovery/overview/
 *       topology views, policy package rules/history/changes/hygiene/devices/ha-pairs, Zone Matrix versions/draft/
 *       policies/summary, cutover segments/assessments/flows, scripts &amp; runs, log-findings list/detail/chips/
 *       coverage, Packet Analysis top-level cases/runs/report, system-log chunks/text, jobs list &amp; CSV, failover
 *       baseline runs/units/approvals.</li>
 *   <li>Round 4 ({@code AiviewUnseededPlanesSweepTest} and {@code AiviewPacketCaseViewsSweepTest}): lifecycle &amp;
 *       catalog, SNMP system-logs/health, notifications, backup deviations, policy-install status, and the 21 Packet
 *       Analysis DuckDB case-detail routes. Note: {@code AiviewUnseededPlanesSweepTest} fired
 *       {@code GET /api/v2/policy/domains/{id}/object-usage} in its generic loop over a single PAN snapshot without a
 *       {@code COVERAGE} entry or Check Point/ASA/FortiGate domain seeding; round 9 seeds and covers it explicitly
 *       across all four vendor families.</li>
 *   <li>Round 5 ({@code AiviewReportsAndComplianceSweepTest}): Reports R1 catalogue/runs/downloads, Compliance overview/
 *       controls/device compliance, HTTPS certificate &amp; SSH trust, software history, change feed, global search.</li>
 *   <li>Round 6 ({@code AiviewConfigBundlesDiagnosticsSweepTest}): Configuration index/overrides/member-diff/backup
 *       compare/management-tree, Support bundles &amp; uploads, Diagnostics targets/ports/history/detail/measurements,
 *       Lifecycle advisories &amp; sources.</li>
 *   <li>Round 7 ({@code AiviewFailoverIncidentsSweepTest}): Failover incidents, config-syncs, in-flight runs and
 *       failover execution refusal across {@code cp/pan/asa/fgt-failover}.</li>
 *   <li>Round 8 (lane G711 {@code AiviewInsightsOfflineTopologySweepTest}, excluded here): policy insights/insight,
 *       offline-analyses, path-analysis/validate, topology/overrides, sanitize/jobs, and system-logs hcp/sources/
 *       spike-data.</li>
 *   <li>Excluded bootstrap/probe/alias routes carrying no domain rows: {@code /livez}, {@code /readyz},
 *       {@code /session/status}, {@code /devices/} (slash alias of {@code /devices}),
 *       {@code /api/v2/diagnostics/preview} (static capability catalog), and
 *       {@code /api/v2/operations/static-route/preview/*} (paired with round 1's static-route preview).</li>
 * </ul>
 *
 * <p><b>Final round 9 route ledger (13 Plane-1 routes + 16 reachable Plane-2 routes + 2 companion refused Plane-2 GET
 * routes = 31 routes; enforced bi-directionally by {@link #routeLedgerMatchesJavadocAndStaticAudit()}):</b></p>
 * <pre>
 * GET /api/v2/policy/tree                           | PLANE_1 | COVERED                         | Root source nodes, source container nodes, container policies and device-filtered policies across CP, PAN, ASA and FGT
 * GET /api/v2/policy/sources                        | PLANE_1 | COVERED                         | Enrolled CP and PAN management sources with latest COMPLETED and FAILED collection summaries
 * GET /api/v2/policy/hygiene-summary                | PLANE_1 | COVERED                         | Fleet hygiene summary ticked to READY across CP, PAN, ASA and FGT containers with SAME HA pair dedup
 * GET /api/v2/policy/collections/{id}               | PLANE_1 | COVERED                         | Completed CP collection job with domain progress and failed PAN collection job with masked failure reason
 * GET /api/v2/policy/devices/{id}                   | PLANE_1 | COVERED                         | Assigned policy page and metadata per enrolled gateway across CP, PAN, ASA and FGT
 * GET /api/v2/policy/objects/{id}                   | PLANE_1 | COVERED                         | Object detail across CP group (with DOMAIN_INVENTORY member resolution), CP host, PAN host, ASA and FGT objects
 * GET /api/v2/policy/domains/{id}/gateways          | PLANE_1 | COVERED                         | Check Point gateways-and-servers inventory items with gateway addresses and package installation states
 * GET /api/v2/policy/domains/{id}/hits              | PLANE_1 | COVERED                         | Stored CP and PAN domain rule hit counters with per-firewall hit breakdown and context masking
 * GET /api/v2/policy/domains/{id}/installation      | PLANE_1 | COVERED                         | Domain installation matrix across CP, PAN, ASA and FGT linking gateways, targets and package names
 * GET /api/v2/policy/domains/{id}/objects           | PLANE_1 | COVERED                         | Streamed domain object index across CP inventory+snapshot, PAN snapshot, ASA and FGT local snapshots
 * GET /api/v2/policy/domains/{id}/objects/duplicates| PLANE_1 | COVERED                         | Value-equivalent duplicate host/address groups across CP, PAN, ASA and FGT domains
 * GET /api/v2/policy/domains/{id}/unused            | PLANE_1 | COVERED                         | Check Point unused-objects management inventory projection
 * GET /api/v2/policy/domains/{id}/object-usage      | PLANE_1 | COVERED                         | Object where-used rules and groups across CP (inventory+snapshot alias join), PAN, ASA and FGT
 * GET /api/v2/backups/store-quotas                  | PLANE_2 | COVERED                         | Seeded artefact_store_quota rows including a store naming a raw gateway host; updated_by omitted, store masked
 * GET /api/v2/config/scheduled-jobs                 | PLANE_2 | COVERED                         | Six V203 families with catalogued and uncatalogued changed_by projected to USR- tokens and raw reason withheld
 * GET /api/v2/system/storage                        | PLANE_2 | COVERED                         | Backup/config manifest totals and top_devices joined to devices with masked FW- and CLS- labels
 * GET /api/v2/system-logs/crash-files               | PLANE_2 | COVERED                         | Seeded device_crash_entry and device_crash_alert rows with host-bearing process masked by advice; name_hmac omitted
 * GET /api/v2/system-logs/gaps                      | PLANE_2 | COVERED                         | Seeded system_log_gap ROTATED_AWAY row for enrolled gateway with byte counters and opaque ids
 * GET /api/v2/security/scan-summary/latest          | PLANE_2 | COVERED                         | Mail delivery booleans over seeded SMTP/recipient settings plus masked notification_routes.last_error
 * GET /api/v2/estate/as-of                          | PLANE_2 | COVERED                         | Historical rewind snapshot over seeded READINESS failover run, backup + size_anomaly and CRITICAL FAIL compliance
 * GET /api/v2/jobs/facets                           | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | JobLogQueryService.Facets: closed jobs.state and jobs.job_type tokens only over seeded target/reason jobs
 * GET /api/v2/jobs/stats                            | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | JobLogQueryService.Stats: long counters and by_state counts only over seeded jobs
 * GET /api/v2/jobs/{id}/transcript/health           | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | JobTranscriptController.TranscriptHealth: booleans, entry count, format and errorCode over encrypted raw transcript
 * GET /api/v2/job-window                            | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | JobWindowController#status: JobWindowPolicy and BusinessHours booleans, minutes, zone id and ISO timestamps
 * GET /api/v2/backups/policies                      | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | BackupPolicyRepository.BackupPolicy via BackupPolicyController#toBody: fixed default id, cron, integers and timestamps
 * GET /api/v2/log-findings/budget                   | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | LogFindingReadService#budget: booleans, window_days, percentile/count integers and PROVISIONAL constants over seeded findings
 * GET /api/v2/system/pods                           | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | SystemStatusService#pods: DiskHeadroomService counters and quarantined_by_role counts over seeded endpoint_admission
 * GET /api/v2/zone-matrix/template                  | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | ZoneMatrixService#template: ZoneMatrixTemplate static constants and role capability booleans
 * GET /project-plan                                 | PLANE_2 | NO_IDENTITY_FIELDS_REVIEWED     | ProjectPlanReader#read: static repository governance JSON projection with zero runtime device or user rows
 * GET /jobs/{id}/transcript                         | PLANE_2 | NOT COVERED (refused by design) | JobTranscriptController#transcript refuses role:replay_viewer with 403 over the seeded raw job transcript
 * GET /api/v2/config/log-detection                  | PLANE_2 | NOT COVERED (refused by design) | GateChainInterceptor refuses aiview with 403 (log_detection_settings_read is security_admin only)
 * </pre>
 */
class AiviewPolicyDomainsJobsOpsSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AS_OF_QUERY = "2026-11-25T12:00:00Z";

    /** Planted value category; the only thing about a value this test ever prints or asserts on. */
    enum ValueClass {
        HOSTNAME,
        CLUSTER_NAME,
        ADDRESS,
        SERIAL,
        /** Catalogued policy change author ({@code policy_rule_history.changed_by} / {@code last-modifier}). */
        PRINCIPAL,
        /** Person appearing only in free text / non-catalogued columns, never in policy change history. */
        UNCATALOGUED_PRINCIPAL,
        DOMAIN_OR_CONTEXT,
        POLICY_NAME,
        RULE_NAME,
        OBJECT_OR_GROUP_NAME,
        ZONE_OR_INTERFACE_NAME,
        FILE_OR_CRASH_NAME,
        MAIL_ADDRESS
    }

    /**
     * Surviving raw values pinned by exact route and value class: {@code route -> (class -> count)}.
     * Empty is the goal; any surviving value class must match this map exactly.
     */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of();

    /** Plane-1 routes (PolicyController and PolicyCollectionController domain, collection, device, object and fleet views). */
    static final Set<String> PLANE_1_ROUTES = Set.of(
            "GET /api/v2/policy/tree",
            "GET /api/v2/policy/sources",
            "GET /api/v2/policy/hygiene-summary",
            "GET /api/v2/policy/collections/*",
            "GET /api/v2/policy/devices/*",
            "GET /api/v2/policy/objects/*",
            "GET /api/v2/policy/domains/*/gateways",
            "GET /api/v2/policy/domains/*/hits",
            "GET /api/v2/policy/domains/*/installation",
            "GET /api/v2/policy/domains/*/objects",
            "GET /api/v2/policy/domains/*/objects/duplicates",
            "GET /api/v2/policy/domains/*/unused",
            "GET /api/v2/policy/domains/*/object-usage");

    /** Plane-2 routes (Jobs and operations aggregates plus the two companion refused GET routes on the same controllers). */
    static final Set<String> PLANE_2_ROUTES = Set.of(
            "GET /api/v2/backups/store-quotas",
            "GET /api/v2/config/scheduled-jobs",
            "GET /api/v2/system/storage",
            "GET /api/v2/system-logs/crash-files",
            "GET /api/v2/system-logs/gaps",
            "GET /api/v2/security/scan-summary/latest",
            "GET /api/v2/estate/as-of",
            "GET /api/v2/jobs/facets",
            "GET /api/v2/jobs/stats",
            "GET /api/v2/jobs/*/transcript/health",
            "GET /api/v2/job-window",
            "GET /api/v2/backups/policies",
            "GET /api/v2/log-findings/budget",
            "GET /api/v2/system/pods",
            "GET /api/v2/zone-matrix/template",
            "GET /project-plan",
            "GET /jobs/*/transcript",
            "GET /api/v2/config/log-detection");

    /**
     * Routes that project or mask identity-bearing rows: each route must answer HTTP 200 and contain every expected
     * seeded-row marker so a zero leak count proves masking over real data rather than an empty payload.
     */
    static final Map<String, Set<String>> COVERAGE = Map.ofEntries(
            // Plane 1 (all 13 routes)
            Map.entry("GET /api/v2/policy/tree", Set.of("cp-source", "pan-source", "asa-source", "fgt-source",
                    "cp-policy", "pan-policy", "asa-policy", "fgt-policy")),
            Map.entry("GET /api/v2/policy/sources", Set.of("cp-source", "pan-source", "cp-collect-job", "pan-collect-job")),
            Map.entry("GET /api/v2/policy/hygiene-summary", Set.of("hygiene-ready", "hygiene-ha-counted-once",
                    "cp-domain", "pan-domain", "asa-source", "fgt-source")),
            Map.entry("GET /api/v2/policy/collections/*", Set.of("cp-collect-job", "pan-collect-job", "cp-domain", "has-transcript")),
            Map.entry("GET /api/v2/policy/devices/*", Set.of("cp-policy", "pan-policy", "asa-policy", "fgt-policy", "cp-rule")),
            Map.entry("GET /api/v2/policy/objects/*", Set.of("cp-group-obj", "domain-inventory-resolution",
                    "pan-dup-obj", "asa-dup-obj", "fgt-dup-obj")),
            Map.entry("GET /api/v2/policy/domains/*/gateways", Set.of("cp-domain", "cp-gw-inv", "cp-cls-inv")),
            Map.entry("GET /api/v2/policy/domains/*/hits", Set.of("cp-rule", "pan-rule", "cp-gw-a", "pan-fw")),
            Map.entry("GET /api/v2/policy/domains/*/installation", Set.of("cp-policy", "pan-policy", "asa-policy", "fgt-policy", "cp-gw-a")),
            Map.entry("GET /api/v2/policy/domains/*/objects", Set.of("cp-host-uid", "pan-dup-obj", "asa-dup-obj", "fgt-dup-obj")),
            Map.entry("GET /api/v2/policy/domains/*/objects/duplicates", Set.of("cp-host-uid", "pan-dup-obj", "asa-dup-obj", "fgt-dup-obj")),
            Map.entry("GET /api/v2/policy/domains/*/unused", Set.of("cp-domain", "cp-unused-uid")),
            Map.entry("GET /api/v2/policy/domains/*/object-usage", Set.of("cp-policy", "cp-group-uid", "pan-policy", "asa-policy", "fgt-policy")),
            // Plane 2 (7 identity-bearing / masked aggregate routes)
            Map.entry("GET /api/v2/backups/store-quotas", Set.of("store-quota-cp", "store-quota-masked-host")),
            Map.entry("GET /api/v2/config/scheduled-jobs", Set.of("scheduled-family-backup", "scheduled-masked-flag", "scheduled-usr-token", "scheduled-last-run")),
            Map.entry("GET /api/v2/system/storage", Set.of("storage-top-device", "storage-masked-fw", "storage-masked-cls")),
            Map.entry("GET /api/v2/system-logs/crash-files", Set.of("crash-entry", "crash-alert", "crash-masked-process")),
            Map.entry("GET /api/v2/system-logs/gaps", Set.of("syslog-gap", "cp-gw-a")),
            Map.entry("GET /api/v2/security/scan-summary/latest", Set.of("scan-mail-delivering", "scan-masked-error")),
            Map.entry("GET /api/v2/estate/as-of", Set.of("cp-gw-a", "as-of-readiness", "as-of-size-anomaly", "as-of-compliance-evidenced")));

    /**
     * Routes whose response record/map provably carries no free-text or device/user identity field, mapped to the exact
     * Java response type/method reviewed. Every route here is still called via HTTP GET against seeded rows, asserted
     * HTTP 200 with expected structural markers, and scanned for zero planted values.
     */
    static final Map<String, String> NO_IDENTITY_FIELDS_REVIEWED = Map.ofEntries(
            Map.entry("GET /api/v2/jobs/facets",
                    "com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.Facets (closed jobs.state and jobs.job_type enums)"),
            Map.entry("GET /api/v2/jobs/stats",
                    "com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.Stats (long totals and by_state counts)"),
            Map.entry("GET /api/v2/jobs/*/transcript/health",
                    "com.securityexpert.nexus.ui2.service.api.JobTranscriptController.TranscriptHealth (present, decrypts, entries, format, errorCode)"),
            Map.entry("GET /api/v2/job-window",
                    "com.securityexpert.nexus.ui2.service.api.JobWindowController#status (JobWindowPolicy + BusinessHours booleans, minutes, zone id, ISO instants)"),
            Map.entry("GET /api/v2/backups/policies",
                    "com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy via BackupPolicyController#toBody (singleton 'default' policy_id, cron, integers, ISO instants)"),
            Map.entry("GET /api/v2/log-findings/budget",
                    "com.securityexpert.nexus.ui2.service.logdetect.LogFindingReadService#budget (booleans, window_days, percentile/count integers, PROVISIONAL budget constants)"),
            Map.entry("GET /api/v2/system/pods",
                    "com.securityexpert.nexus.ui2.service.system.SystemStatusService#pods (DiskHeadroomService counters, quarantined_by_role module counts, non-K8s unavailable reason)"),
            Map.entry("GET /api/v2/zone-matrix/template",
                    "com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService#template (ZoneMatrixTemplate static constants and role capability booleans)"),
            Map.entry("GET /project-plan",
                    "com.securityexpert.nexus.ui2.service.projectplan.ProjectPlanReader#read (static repository governance JSON projection)"));

    /**
     * Companion GET routes on the Plane-2 controllers that refuse {@code aiview} (401/403) by design: never counted as
     * covered, asserted to refuse and to carry zero planted values in the refusal body.
     */
    static final Map<String, String> REFUSED_BY_DESIGN = Map.of(
            "GET /jobs/*/transcript",
            "JobTranscriptController#transcript refuses role:replay_viewer with 403 FORBIDDEN",
            "GET /api/v2/config/log-detection",
            "GateChainInterceptor refuses aiview on log_detection_settings_read (security_admin only)");

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static Estate estate;

    /** Seeded opaque IDs and structural markers verified in {@link #COVERAGE}. */
    private static final Map<String, String> ids = new TreeMap<>();
    private static final Map<String, String> markers = new LinkedHashMap<>();

    // ------------------------------------------------------------------ boot (self-contained copy of round 7 pattern)

    private static synchronized void ensureBooted() throws Exception {
        if (context != null) return;
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_sweep_r9");
        work = Files.createTempDirectory("ui2-aiview-sweep-r9-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
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

        // Discover the mapped ASA and FortiGate snapshot and object IDs derived on read from the encrypted configs.
        PolicyQueryService query = context.getBean(PolicyQueryService.class);
        String asaPolicyId = PolicySnapshot.ref("asa", estate.asaA().id(), "single");
        String fgtPolicyId = PolicySnapshot.ref("fgt", estate.fgtA().id(), "root");
        PolicySnapshot asaSnap = query.find(asaPolicyId).orElseThrow(() -> new IllegalStateException("ASA snapshot missing"));
        PolicySnapshot fgtSnap = query.find(fgtPolicyId).orElseThrow(() -> new IllegalStateException("FGT snapshot missing"));
        String asaObjId = asaSnap.objects().values().stream()
                .filter(o -> estate.asaHostAName().equals(o.name()))
                .map(PolicySnapshot.PolicyObject::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ASA duplicate host object missing"));
        String fgtObjId = fgtSnap.objects().values().stream()
                .filter(o -> estate.fgtHostAName().equals(o.name()))
                .map(PolicySnapshot.PolicyObject::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FGT duplicate host object missing"));
        ids.put("asaPolicyId", asaPolicyId);
        ids.put("asaContainerId", asaSnap.metadata().containerId());
        ids.put("asaObjId", asaObjId);
        ids.put("fgtPolicyId", fgtPolicyId);
        ids.put("fgtContainerId", fgtSnap.metadata().containerId());
        ids.put("fgtObjId", fgtObjId);
        markers.put(asaPolicyId, "asa-policy");
        markers.put(asaObjId, "asa-dup-obj");
        markers.put(fgtPolicyId, "fgt-policy");
        markers.put(fgtObjId, "fgt-dup-obj");

        // Scheduling is disabled in test boot: run the background fleet-hygiene summary ticks synchronously so every
        // seeded container (CP, PAN, ASA HA pair, FGT HA pair) transitions from PENDING to READY before HTTP GETs.
        PolicyHygieneFleetSummary fleetSummary = context.getBean(PolicyHygieneFleetSummary.class);
        int guard = 0;
        while (fleetSummary.refreshNext()) {
            if (++guard > 32) throw new IllegalStateException("PolicyHygieneFleetSummary did not converge");
        }
    }

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory, ArtefactStore testStore) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(testStore));
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

    // ------------------------------------------------------------------ synthetic runtime-generated estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Token starting with a letter so it never collides with a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    private static String digits(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    private static String hex16() {
        return Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff));
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String address, String cluster,
            String softwareVersion) {
    }

    record Estate(
            Device cpMgmt, Device cpGwA, Device cpGwB, String cpCluster, String cpGwAUid, String cpGwBUid,
            Device panorama, Device panFw,
            Device asaA, Device asaB, String asaCluster,
            Device fgtA, Device fgtB, String fgtCluster,
            String ipv6Mgmt,
            String principal, String stranger,
            String cpDomainId, String cpDomainName, String cpPolicyId, String cpPolicyName,
            String cpSectionName, String cpRuleId, String cpRuleName, String cpDisabledRuleName,
            String cpHostAUid, String cpHostAName, String cpHostBUid, String cpHostBName, String cpDupIp,
            String cpInvMemberUid, String cpInvMemberName, String cpInvMemberIp,
            String cpUnusedUid, String cpUnusedName, String cpUnusedIp,
            String cpGroupUid, String cpGroupName, String cpZoneUid, String cpZoneName, String cpVsContext,
            String cpClusterVip,
            String panDomainId, String panDeviceGroup, String panPolicyId, String panPolicyName,
            String panRuleId, String panRuleName, String panShadowRuleName,
            String panObjAId, String panObjAName, String panObjBId, String panObjBName, String panDupIp,
            String panGroupId, String panGroupName, String panZoneA, String panZoneB, String panVsys,
            String asaOutsideIf, String asaInsideIf, String asaIfIp, String asaPeerIp,
            String asaHostAName, String asaHostBName, String asaDupIp, String asaGroupName, String asaAclName,
            String fgtPortA, String fgtPortB, String fgtIfIpA, String fgtIfIpB,
            String fgtHostAName, String fgtHostBName, String fgtDupIp, String fgtGroupName,
            String fgtRuleName, String fgtShadowRuleName,
            String podHost, String crashFileName, String smtpHost, String syslogHost,
            String smtpFrom, String smtpTo, String scanRecipients,
            Map<String, ValueClass> raw) {

        static Estate generate() {
            int base = 20 + RANDOM.nextInt(140);
            String cpCluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            String asaCluster = "ASA-HA-" + tag().toUpperCase(Locale.ROOT);
            String fgtCluster = "FGT-HA-" + tag().toUpperCase(Locale.ROOT);

            Device cpMgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "mds-" + tag() + "-core01", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + base, null, "R81.20");
            Device cpGwA = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "FW-TANGO-" + tag().toUpperCase(Locale.ROOT), "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 1), cpCluster, "R81.20");
            Device cpGwB = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist-" + tag() + "-gw02", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 2), cpCluster, "R81.20");
            String cpGwAUid = "uid-cpgwa-" + UUID.randomUUID();
            String cpGwBUid = "uid-cpgwb-" + UUID.randomUUID();

            Device panorama = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "management_server",
                    "pano-" + tag() + "-mgr01", "0" + digits(11),
                    "192.0.2." + (base + 3), null, "11.1.2");
            Device panFw = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "pan-" + tag() + "-edge01", "0" + digits(11),
                    "192.0.2." + (base + 4), null, "11.1.2");

            Device asaA = new Device(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway",
                    "asa-" + tag() + "-pri01", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 5), asaCluster, "9.18(4)22");
            Device asaB = new Device(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway",
                    "asa-" + tag() + "-sec02", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 6), asaCluster, "9.18(4)22");

            Device fgtA = new Device(Ui2Rows.opaqueId("dev"), "fortinet", "gateway",
                    "fgt-" + tag() + "-pri01", "FGT" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 7), fgtCluster, "7.4.8");
            Device fgtB = new Device(Ui2Rows.opaqueId("dev"), "fortinet", "gateway",
                    "fgt-" + tag() + "-sec02", "FGT" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 8), fgtCluster, "7.4.8");

            String ipv6Mgmt = "2001:db8:" + hex16() + ":" + hex16() + "::" + hex16();
            String principal = "adm" + tag() + ".ops";
            String stranger = "sec" + tag() + ".desk";

            String cpDomainId = "dom-cp-" + UUID.randomUUID();
            String cpDomainName = "dom-" + tag() + "-payments";
            String cpPolicyId = "pol-cp-" + UUID.randomUUID();
            String cpPolicyName = "pkg-" + tag() + "-perimeter";
            String cpSectionName = "sec-" + tag() + "-core";
            String cpRuleId = "rule-cp-" + UUID.randomUUID();
            String cpRuleName = "allow-" + tag() + "-settlement";
            String cpDisabledRuleName = "drop-" + tag() + "-retired";
            String cpHostAUid = "uid-cpa-" + UUID.randomUUID();
            String cpHostAName = "obj-" + tag() + "-paydb01";
            String cpHostBUid = "uid-cpb-" + UUID.randomUUID();
            String cpHostBName = "obj-" + tag() + "-paydb01-dup";
            String cpDupIp = "198.51.100." + (10 + RANDOM.nextInt(40));
            String cpInvMemberUid = "uid-cpinv-" + UUID.randomUUID();
            String cpInvMemberName = "obj-" + tag() + "-invonly";
            String cpInvMemberIp = "198.51.100." + (55 + RANDOM.nextInt(30));
            String cpUnusedUid = "uid-cpun-" + UUID.randomUUID();
            String cpUnusedName = "obj-" + tag() + "-orphan";
            String cpUnusedIp = "198.51.100." + (90 + RANDOM.nextInt(30));
            String cpGroupUid = "uid-cpgrp-" + UUID.randomUUID();
            String cpGroupName = "grp-" + tag() + "-tier1";
            String cpZoneUid = "uid-cpzn-" + UUID.randomUUID();
            String cpZoneName = "zn-" + tag() + "-cpdmz";
            String cpVsContext = "vs-" + tag() + "-ctx01";
            String cpClusterVip = "198.51.100." + (125 + RANDOM.nextInt(20));

            String panDomainId = "dom-pan-" + UUID.randomUUID();
            String panDeviceGroup = "dg-" + tag() + "-branch";
            String panPolicyId = "pol-pan-" + UUID.randomUUID();
            String panPolicyName = "pkg-" + tag() + "-panbranch";
            String panRuleId = "rule-pan-" + UUID.randomUUID();
            String panRuleName = "allow-" + tag() + "-panapp";
            String panShadowRuleName = "allow-" + tag() + "-panshadow";
            String panObjAId = "obj-pana-" + UUID.randomUUID();
            String panObjAName = "panh-" + tag() + "-ledger01";
            String panObjBId = "obj-panb-" + UUID.randomUUID();
            String panObjBName = "panh-" + tag() + "-ledger01-dup";
            String panDupIp = "198.51.100." + (150 + RANDOM.nextInt(25));
            String panGroupId = "obj-pangrp-" + UUID.randomUUID();
            String panGroupName = "pangrp-" + tag() + "-ledgers";
            String panZoneA = "zn-" + tag() + "-pantrust";
            String panZoneB = "zn-" + tag() + "-panuntrust";
            String panVsys = "vsys-" + tag() + "-prod";

            String asaOutsideIf = "nif-" + tag() + "-out";
            String asaInsideIf = "nif-" + tag() + "-in";
            String asaIfIp = "203.0.113." + (10 + RANDOM.nextInt(30));
            String asaPeerIp = "203.0.113." + (45 + RANDOM.nextInt(30));
            String asaHostAName = "asah-" + tag() + "-vault01";
            String asaHostBName = "asah-" + tag() + "-vault01-dup";
            String asaDupIp = "203.0.113." + (80 + RANDOM.nextInt(30));
            String asaGroupName = "asagrp-" + tag() + "-vaults";
            String asaAclName = "ACL-" + tag().toUpperCase(Locale.ROOT) + "-OUT";

            String fgtPortA = "if-" + tag() + "-wan";
            String fgtPortB = "if-" + tag() + "-lan";
            String fgtIfIpA = "203.0.113." + (120 + RANDOM.nextInt(25));
            String fgtIfIpB = "203.0.113." + (150 + RANDOM.nextInt(25));
            String fgtHostAName = "fgth-" + tag() + "-core01";
            String fgtHostBName = "fgth-" + tag() + "-core01-dup";
            String fgtDupIp = "203.0.113." + (180 + RANDOM.nextInt(25));
            String fgtGroupName = "fgtgrp-" + tag() + "-cores";
            String fgtRuleName = "fgtpol-" + tag() + "-accept";
            String fgtShadowRuleName = "fgtpol-" + tag() + "-shadow";

            String podHost = "node-" + tag() + "-k8s01";
            String crashFileName = "crash-" + tag() + "-dump.core";
            String mailTag = tag();
            String smtpHost = "smtp-" + mailTag + "-relay.example.test";
            String syslogHost = "syslog-" + mailTag + "-ingest.example.test";
            String smtpFrom = "alerts-" + mailTag + "@example.test";
            String smtpTo = "soc-" + mailTag + "@example.test";
            String scanRecipients = "secscan-" + mailTag + "@example.test";

            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(cpMgmt, cpGwA, cpGwB, panorama, panFw, asaA, asaB, fgtA, fgtB)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.address(), ValueClass.ADDRESS);
            }
            for (String cls : List.of(cpCluster, asaCluster, fgtCluster)) {
                raw.put(cls, ValueClass.CLUSTER_NAME);
            }
            for (String addr : List.of(ipv6Mgmt, cpDupIp, cpInvMemberIp, cpUnusedIp, cpClusterVip, panDupIp,
                    asaIfIp, asaPeerIp, asaDupIp, fgtIfIpA, fgtIfIpB, fgtDupIp)) {
                raw.put(addr, ValueClass.ADDRESS);
            }
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(stranger, ValueClass.UNCATALOGUED_PRINCIPAL);
            for (String dom : List.of(cpDomainName, cpVsContext, panDeviceGroup, panVsys)) {
                raw.put(dom, ValueClass.DOMAIN_OR_CONTEXT);
            }
            for (String pol : List.of(cpPolicyName, panPolicyName, asaAclName)) {
                raw.put(pol, ValueClass.POLICY_NAME);
            }
            for (String r : List.of(cpSectionName, cpRuleName, cpDisabledRuleName, panRuleName, panShadowRuleName,
                    fgtRuleName, fgtShadowRuleName)) {
                raw.put(r, ValueClass.RULE_NAME);
            }
            for (String obj : List.of(cpHostAName, cpHostBName, cpInvMemberName, cpUnusedName, cpGroupName,
                    panObjAName, panObjBName, panGroupName, asaHostAName, asaHostBName, asaGroupName,
                    fgtHostAName, fgtHostBName, fgtGroupName)) {
                raw.put(obj, ValueClass.OBJECT_OR_GROUP_NAME);
            }
            for (String zn : List.of(cpZoneName, panZoneA, panZoneB, asaOutsideIf, asaInsideIf, fgtPortA, fgtPortB)) {
                raw.put(zn, ValueClass.ZONE_OR_INTERFACE_NAME);
            }
            raw.put(podHost, ValueClass.HOSTNAME);
            raw.put(smtpHost, ValueClass.HOSTNAME);
            raw.put(syslogHost, ValueClass.HOSTNAME);
            raw.put(crashFileName, ValueClass.FILE_OR_CRASH_NAME);
            for (String mail : List.of(smtpFrom, smtpTo, scanRecipients)) {
                raw.put(mail, ValueClass.MAIL_ADDRESS);
            }

            return new Estate(
                    cpMgmt, cpGwA, cpGwB, cpCluster, cpGwAUid, cpGwBUid,
                    panorama, panFw,
                    asaA, asaB, asaCluster,
                    fgtA, fgtB, fgtCluster,
                    ipv6Mgmt,
                    principal, stranger,
                    cpDomainId, cpDomainName, cpPolicyId, cpPolicyName,
                    cpSectionName, cpRuleId, cpRuleName, cpDisabledRuleName,
                    cpHostAUid, cpHostAName, cpHostBUid, cpHostBName, cpDupIp,
                    cpInvMemberUid, cpInvMemberName, cpInvMemberIp,
                    cpUnusedUid, cpUnusedName, cpUnusedIp,
                    cpGroupUid, cpGroupName, cpZoneUid, cpZoneName, cpVsContext,
                    cpClusterVip,
                    panDomainId, panDeviceGroup, panPolicyId, panPolicyName,
                    panRuleId, panRuleName, panShadowRuleName,
                    panObjAId, panObjAName, panObjBId, panObjBName, panDupIp,
                    panGroupId, panGroupName, panZoneA, panZoneB, panVsys,
                    asaOutsideIf, asaInsideIf, asaIfIp, asaPeerIp,
                    asaHostAName, asaHostBName, asaDupIp, asaGroupName, asaAclName,
                    fgtPortA, fgtPortB, fgtIfIpA, fgtIfIpB,
                    fgtHostAName, fgtHostBName, fgtDupIp, fgtGroupName,
                    fgtRuleName, fgtShadowRuleName,
                    podHost, crashFileName, smtpHost, syslogHost,
                    smtpFrom, smtpTo, scanRecipients,
                    Map.copyOf(raw));
        }

        List<Device> allDevices() {
            return List.of(cpMgmt, cpGwA, cpGwB, panorama, panFw, asaA, asaB, fgtA, fgtB);
        }
    }

    // ------------------------------------------------------------------ config text builders (shared with local parser check)

    static String buildAsaConfig(Estate e, Device unit) {
        return "ASA Version 9.18(4)22\n"
                + "hostname " + unit.hostname() + "\n"
                + "interface GigabitEthernet0/0\n"
                + " nameif " + e.asaOutsideIf() + "\n"
                + " security-level 0\n"
                + " ip address " + e.asaIfIp() + " 255.255.255.0\n"
                + "interface GigabitEthernet0/1\n"
                + " nameif " + e.asaInsideIf() + "\n"
                + " security-level 100\n"
                + "object network " + e.asaHostAName() + "\n"
                + " host " + e.asaDupIp() + "\n"
                + "object network " + e.asaHostBName() + "\n"
                + " host " + e.asaDupIp() + "\n"
                + "object-group network " + e.asaGroupName() + "\n"
                + " network-object object " + e.asaHostAName() + "\n"
                + "access-list " + e.asaAclName() + " remark managed by " + e.principal() + " and " + e.stranger() + "\n"
                + "access-list " + e.asaAclName() + " extended permit tcp object-group " + e.asaGroupName()
                + " host " + e.asaPeerIp() + " eq 443\n"
                + "access-list " + e.asaAclName() + " extended permit tcp object " + e.asaHostAName()
                + " host " + e.asaPeerIp() + " eq 443\n"
                + "access-group " + e.asaAclName() + " in interface " + e.asaOutsideIf() + "\n";
    }

    static String buildFgtConfig(Estate e, Device unit) {
        return "#config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin\n"
                + "config system global\n"
                + "    set hostname \"" + unit.hostname() + "\"\n"
                + "end\n"
                + "config system interface\n"
                + "    edit \"" + e.fgtPortA() + "\"\n"
                + "        set vdom \"root\"\n"
                + "        set ip " + e.fgtIfIpA() + " 255.255.255.0\n"
                + "    next\n"
                + "    edit \"" + e.fgtPortB() + "\"\n"
                + "        set vdom \"root\"\n"
                + "        set ip " + e.fgtIfIpB() + " 255.255.255.0\n"
                + "    next\n"
                + "end\n"
                + "config firewall address\n"
                + "    edit \"all\"\n"
                + "    next\n"
                + "    edit \"" + e.fgtHostAName() + "\"\n"
                + "        set subnet " + e.fgtDupIp() + " 255.255.255.255\n"
                + "    next\n"
                + "    edit \"" + e.fgtHostBName() + "\"\n"
                + "        set subnet " + e.fgtDupIp() + " 255.255.255.255\n"
                + "    next\n"
                + "end\n"
                + "config firewall addrgrp\n"
                + "    edit \"" + e.fgtGroupName() + "\"\n"
                + "        set member \"" + e.fgtHostAName() + "\"\n"
                + "    next\n"
                + "end\n"
                + "config firewall service custom\n"
                + "    edit \"HTTPS\"\n"
                + "        set tcp-portrange 443\n"
                + "    next\n"
                + "end\n"
                + "config firewall policy\n"
                + "    edit 1\n"
                + "        set name \"" + e.fgtRuleName() + "\"\n"
                + "        set srcintf \"" + e.fgtPortA() + "\"\n"
                + "        set dstintf \"" + e.fgtPortB() + "\"\n"
                + "        set srcaddr \"" + e.fgtGroupName() + "\"\n"
                + "        set dstaddr \"" + e.fgtHostBName() + "\"\n"
                + "        set action accept\n"
                + "        set schedule \"always\"\n"
                + "        set service \"HTTPS\"\n"
                + "        set logtraffic all\n"
                + "        set comments \"reviewed by " + e.principal() + " and " + e.stranger() + "\"\n"
                + "    next\n"
                + "    edit 2\n"
                + "        set name \"" + e.fgtShadowRuleName() + "\"\n"
                + "        set srcintf \"" + e.fgtPortA() + "\"\n"
                + "        set dstintf \"" + e.fgtPortB() + "\"\n"
                + "        set srcaddr \"" + e.fgtHostAName() + "\"\n"
                + "        set dstaddr \"" + e.fgtHostBName() + "\"\n"
                + "        set action accept\n"
                + "        set schedule \"always\"\n"
                + "        set service \"HTTPS\"\n"
                + "        set logtraffic all\n"
                + "    next\n"
                + "end\n";
    }

    // ------------------------------------------------------------------ seeding

    private static void seed(Estate e) throws Exception {
        markers.put(e.cpMgmt().id(), "cp-source");
        markers.put(e.panorama().id(), "pan-source");
        markers.put(e.asaA().id(), "asa-source");
        markers.put(e.asaB().id(), "asa-source");
        markers.put(e.fgtA().id(), "fgt-source");
        markers.put(e.fgtB().id(), "fgt-source");
        markers.put(e.cpGwA().id(), "cp-gw-a");
        markers.put(e.panFw().id(), "pan-fw");
        markers.put(e.cpDomainId(), "cp-domain");
        markers.put(e.panDomainId(), "pan-domain");
        markers.put(e.cpPolicyId(), "cp-policy");
        markers.put(e.panPolicyId(), "pan-policy");
        markers.put(e.cpRuleId(), "cp-rule");
        markers.put(e.panRuleId(), "pan-rule");
        markers.put(e.cpHostAUid(), "cp-host-uid");
        markers.put(e.cpUnusedUid(), "cp-unused-uid");
        markers.put(e.cpGroupUid(), "cp-group-uid");
        markers.put(e.panObjAId(), "pan-dup-obj");

        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            seedDevicesAndDiscovery(c, e, credential);
            seedCpSnapshotAndCollections(c, e);
            seedPanSnapshotAndCollection(c, e);
            seedAsaAndFgtLocalSnapshots(c, e);
            seedJobsAndOperationsPlane(c, e);
        }

        seedCpInventories(e);
        seedLogDetectionPlane(e);
    }

    private static void seedDevicesAndDiscovery(Connection c, Estate e, String credential) throws Exception {
        audited(c, Ui2Rows.ACTOR, () -> {
            for (Device d : e.allDevices()) {
                String matchKey = d == e.cpGwA()
                        ? "check_point|" + e.cpDomainName() + "|" + e.cpGwAUid()
                        : d == e.cpGwB()
                                ? "check_point|" + e.cpDomainName() + "|" + e.cpGwBUid()
                                : d.vendor() + "|" + d.serial();
                String haRole = d.cluster() == null ? null
                        : (d == e.cpGwA() || d == e.asaA() || d == e.fgtA() ? "active" : "standby");
                exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                        + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                        + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                        + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                        + "discovery_match_key, created_at) values (?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', ?, ?, "
                        + "'synthetic-model', ?, ?, ?, '2026-11-20T08:00:00Z'::timestamptz, ?, true, ?, "
                        + "'2026-11-20T08:00:00Z'::timestamptz)",
                        d.id(), d.vendor(), credential, d.role(), d.cluster() == null ? "NONE" : "CORROBORATED",
                        d.hostname(), d.softwareVersion(), haRole, d.serial(), d.cluster(), matchKey);
                String transport = d.vendor().equals("palo_alto") ? "pan_xml_api" : "ssh_exec";
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, ?, ?)",
                        Ui2Rows.opaqueId("ep"), d.id(), transport, d.address());
                exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                        + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
            }
            // IPv6 management endpoint on Check Point management server
            exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                    + "values (?, ?, 'https', ?, '2026-11-20T08:01:00Z'::timestamptz)",
                    Ui2Rows.opaqueId("ep"), e.cpMgmt().id(), e.ipv6Mgmt());

            // Finished Check Point discovery run + gateway candidates using management object UIDs so
            // PolicyCollectionRepository.sources() and PolicySnapshotRepository.inventoryLinks() resolve cleanly.
            String cpDiscJob = insertJob(c, null, "cp_discovery_enumerate", "cp_discovery_enumerate", "COMPLETED", null,
                    "discovery_run", "pending", Ui2Rows.ACTOR, "2026-11-24T09:00:00Z");
            String cpDiscRun = Ui2Rows.opaqueId("disc");
            exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                    + "requested_by_actor_fingerprint, state, job_id, finished_at) "
                    + "values (?, 'check_point', ?, ?, ?, 'FINISHED', ?, '2026-11-24T09:05:00Z'::timestamptz)",
                    cpDiscRun, e.cpMgmt().address(), credential, Ui2Rows.ACTOR, cpDiscJob);
            exec(c, "update jobs set target_ref = ? where job_id = ?", cpDiscRun, cpDiscJob);
            ids.put("cpDiscRun", cpDiscRun);
            for (Device gw : List.of(e.cpGwA(), e.cpGwB())) {
                String gwUid = gw == e.cpGwA() ? e.cpGwAUid() : e.cpGwBUid();
                exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                        + "own_address, management_address, cluster_reference, owning_domain, model, software_version, "
                        + "importable, import_outcome) values (?, ?, 'check_point', ?, 'gateway', ?, ?, ?, ?, ?, "
                        + "'synthetic-model', 'R81.20', true, 'already_imported')",
                        Ui2Rows.opaqueId("cand"), cpDiscRun, gwUid, gw.hostname(), gw.address(),
                        e.cpMgmt().address(), e.cpCluster(), e.cpDomainName());
            }

            // Finished Panorama discovery run + firewall candidate so PolicyCollectionRepository.sources() lists Panorama too.
            String panDiscJob = insertJob(c, null, "pan_discovery_enumerate", "pan_discovery_enumerate", "COMPLETED", null,
                    "discovery_run", "pending", Ui2Rows.ACTOR, "2026-11-24T09:10:00Z");
            String panDiscRun = Ui2Rows.opaqueId("disc");
            exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                    + "requested_by_actor_fingerprint, state, job_id, finished_at) "
                    + "values (?, 'palo_alto', ?, ?, ?, 'FINISHED', ?, '2026-11-24T09:15:00Z'::timestamptz)",
                    panDiscRun, e.panorama().address(), credential, Ui2Rows.ACTOR, panDiscJob);
            exec(c, "update jobs set target_ref = ? where job_id = ?", panDiscRun, panDiscJob);
            ids.put("panDiscRun", panDiscRun);
            exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                    + "own_address, management_address, model, software_version, importable, import_outcome) "
                    + "values (?, ?, 'palo_alto', ?, 'gateway', ?, ?, ?, 'synthetic-model', '11.1.2', true, 'already_imported')",
                    Ui2Rows.opaqueId("cand"), panDiscRun, e.panFw().serial(), e.panFw().hostname(),
                    e.panFw().address(), e.panorama().address());
        });
    }

    private static void seedCpSnapshotAndCollections(Connection c, Estate e) throws Exception {
        String snapHostAId = PolicySnapshot.ref(e.cpPolicyId(), "object", e.cpHostAUid());
        String snapInvMissId = PolicySnapshot.ref(e.cpPolicyId(), "object", e.cpInvMemberUid());
        String snapGroupId = PolicySnapshot.ref(e.cpPolicyId(), "object", e.cpGroupUid());
        ids.put("cpGroupSnapId", snapGroupId);
        ids.put("cpHostASnapId", snapHostAId);
        markers.put(snapGroupId, "cp-group-obj");

        var hostA = new PolicySnapshot.PolicyObject(snapHostAId, e.cpHostAName(), "host",
                List.of(), List.of("ipv4-address: " + e.cpDupIp()), "RESOLVED");
        // Group references both snapHostAId (in snapshot) and snapInvMissId (absent from snapshot dictionary, resolved via
        // DOMAIN_INVENTORY in PolicyQueryService.object and PolicyDomainIndex).
        var group = new PolicySnapshot.PolicyObject(snapGroupId, e.cpGroupName(), "group",
                List.of(snapHostAId, snapInvMissId), List.of(), "RESOLVED");
        var anyObj = new PolicySnapshot.PolicyObject("cp-any", "Any", "any", List.of(), List.of(), "RESOLVED");

        var fwHits = List.of(
                new PolicySnapshot.FirewallHits(e.cpGwA().id(), e.cpVsContext(), 42L,
                        "2026-11-21T10:00:00Z", "2026-11-24T11:00:00Z", null, null, "2026-11-24T12:00:00Z"),
                new PolicySnapshot.FirewallHits(e.cpGwB().id(), "", 7L,
                        "2026-11-22T10:00:00Z", "2026-11-24T11:30:00Z", null, null, "2026-11-24T12:00:00Z"));
        var hits = new PolicySnapshot.HitCounts(49L, "2026-11-21T10:00:00Z", "2026-11-24T11:30:00Z",
                "mds", "2026-11-24T12:00:00Z", "medium", fwHits);

        var rule1 = new PolicySnapshot.Rule(
                e.cpRuleId(), UUID.randomUUID().toString(), 1, e.cpRuleName(), true,
                new PolicySnapshot.Cell(List.of(snapGroupId), false),
                new PolicySnapshot.Cell(List.of(snapHostAId), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                "Accept", "Log",
                "approved by " + e.principal() + " and " + e.stranger() + " on " + e.cpGwA().hostname(),
                Map.of("last-modifier", List.of(e.principal()), "layer-name", List.of(e.cpSectionName())),
                hits);
        var rule2 = new PolicySnapshot.Rule(
                "rule-cp-dis-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2, e.cpDisabledRuleName(), false,
                new PolicySnapshot.Cell(List.of(snapHostAId), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                "Drop", "None",
                "disabled by " + e.principal(),
                Map.of("last-modifier", List.of(e.principal())));

        var metadata = new PolicySnapshot.Metadata(
                e.cpPolicyId(), e.cpMgmt().id(), e.cpMgmt().hostname(), "CP",
                e.cpDomainId(), e.cpDomainName(), e.cpPolicyName(), "2026-11-24T12:00:00Z", null,
                List.of(
                        new PolicySnapshot.Target(e.cpGwA().id(), e.cpGwA().hostname(), e.cpVsContext(), "IN_SYNC"),
                        new PolicySnapshot.Target(e.cpGwB().id(), e.cpGwB().hostname(), "", "IN_SYNC")),
                new PolicySnapshot.InventoryProvenance(1, "2026-11-24T11:55:00Z"));
        var snapshot = new PolicySnapshot(
                metadata,
                List.of(new PolicySnapshot.Section("cp-sec-1", e.cpSectionName(), "CP access layer", null, List.of(rule1, rule2))),
                Map.of(snapHostAId, hostA, snapGroupId, group, "cp-any", anyObj),
                List.of());

        // Encrypt a 2-entry JSON-array transcript containing planted raw values for cpCollectJobId (also used to test
        // /api/v2/jobs/{id}/transcript/health and the 403 refusal on /jobs/{id}/transcript).
        String transcriptJson = JSON.writeValueAsString(List.of(
                Map.of("step", 1, "host", e.cpMgmt().hostname(), "address", e.cpMgmt().address(),
                        "serial", e.cpMgmt().serial(), "actor", e.principal()),
                Map.of("step", 2, "gateway", e.cpGwA().hostname(), "peer", e.cpGwB().address(),
                        "reviewer", e.stranger(), "domain", e.cpDomainName())));
        var handle = store.open(e.cpMgmt().id(), "transcript-" + UUID.randomUUID(), "check_point", false);
        handle.sink().write(transcriptJson.getBytes(StandardCharsets.UTF_8));
        var transcriptMeta = handle.finish();

        audited(c, Ui2Rows.ACTOR, () -> {
            exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) "
                    + "values (?, '2026-11-24T12:00:00Z'::timestamptz, ?::jsonb, ?::jsonb)",
                    e.cpPolicyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
            exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                    + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', "
                    + "'2026-11-24T12:00:00Z'::timestamptz, '2026-11-24T09:00:00Z', ?, ?::jsonb)",
                    e.cpPolicyId(), e.cpRuleId(), e.cpRuleId(), e.principal(),
                    JSON.writeValueAsString(List.of(Map.of("field", "name", "before", "legacy-rule", "after", e.cpRuleName()))));

            String cpCollectJob = Ui2Rows.opaqueId("job");
            exec(c, "insert into jobs(job_id, job_type, capability_id, target_kind, target_ref, submitted_by_actor_fingerprint, "
                    + "state, action_class, lease_epoch, submitted_at, finished_at, transcript_artefact_ref, transcript_artefact_key) "
                    + "values (?, 'cp_policy_collect', 'cp_policy_collect', 'discovery_run', ?, ?, 'COMPLETED', 'read', 1, "
                    + "'2026-11-24T11:50:00Z'::timestamptz, '2026-11-24T12:00:00Z'::timestamptz, ?, ?)",
                    cpCollectJob, ids.get("cpDiscRun"), Ui2Rows.ACTOR, transcriptMeta.ref().value(), transcriptMeta.wrappedDataKey());
            exec(c, "insert into policy_collection_request(job_id, source_id, domain_ref, automatic, mode) "
                    + "values (?, ?, '', false, 'FULL')",
                    cpCollectJob, e.cpMgmt().id());
            exec(c, "insert into cp_policy_domain_run(job_id, source_id, domain_ref, status, complete, signal, snapshots, "
                    + "rules_count, hits_collected_at) values (?, ?, ?, 'COLLECTED', true, ?::jsonb, '[]'::jsonb, 2, "
                    + "'2026-11-24T12:00:00Z'::timestamptz)",
                    cpCollectJob, e.cpMgmt().id(), e.cpDomainId(),
                    JSON.writeValueAsString(Map.of("publishTime", "2026-11-24T11:45:00Z")));
            exec(c, "insert into job_step_attempt(attempt_id, job_id, lease_epoch, step_index, step_kind, action_class, outcome) "
                    + "values (?, ?, 1, 1, 'CP_POLICY_READ', 'read', 'OK'), "
                    + "(?, ?, 1, 2, 'POLICY_PROGRESS_4', 'read', 'OK')",
                    Ui2Rows.opaqueId("att"), cpCollectJob, Ui2Rows.opaqueId("att"), cpCollectJob);

            ids.put("cpCollectJob", cpCollectJob);
            ids.put("transcriptJob", cpCollectJob);
            markers.put(cpCollectJob, "cp-collect-job");
        });
    }

    private static void seedCpInventories(Estate e) throws Exception {
        var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        var repo = new PolicyCollectionRepository(tx);
        String at = "2026-11-24T11:55:00Z";

        var hosts = new CpObjectInventory(e.cpMgmt().id(), e.cpDomainId(), "hosts", at, "RESOLVED", "", List.of(
                new CpObjectInventory.Item(e.cpHostAUid(), e.cpHostAUid(), e.cpHostAName(), "host",
                        List.of(), List.of("ipv4-address: " + e.cpDupIp()), null, List.of()),
                new CpObjectInventory.Item(e.cpHostBUid(), e.cpHostBUid(), e.cpHostBName(), "host",
                        List.of(), List.of("ipv4-address: " + e.cpDupIp()), null, List.of()),
                new CpObjectInventory.Item(e.cpInvMemberUid(), e.cpInvMemberUid(), e.cpInvMemberName(), "host",
                        List.of(), List.of("ipv4-address: " + e.cpInvMemberIp()), null, List.of()),
                new CpObjectInventory.Item(e.cpUnusedUid(), e.cpUnusedUid(), e.cpUnusedName(), "host",
                        List.of(), List.of("ipv4-address: " + e.cpUnusedIp()), null, List.of())),
                1, 0.4);
        var groups = new CpObjectInventory(e.cpMgmt().id(), e.cpDomainId(), "groups", at, "RESOLVED", "", List.of(
                new CpObjectInventory.Item(e.cpGroupUid(), e.cpGroupUid(), e.cpGroupName(), "group",
                        List.of(e.cpHostAUid(), e.cpInvMemberUid()), List.of(), null, List.of())),
                1, 0.2);
        var zones = new CpObjectInventory(e.cpMgmt().id(), e.cpDomainId(), "security-zones", at, "RESOLVED", "", List.of(
                new CpObjectInventory.Item(e.cpZoneUid(), e.cpZoneUid(), e.cpZoneName(), "security-zone",
                        List.of(), List.of(), null, List.of())),
                1, 0.1);
        var unused = new CpObjectInventory(e.cpMgmt().id(), e.cpDomainId(), "unused-objects", at, "RESOLVED", "", List.of(
                new CpObjectInventory.Item(e.cpUnusedUid(), e.cpUnusedUid(), e.cpUnusedName(), "host",
                        List.of(), List.of("ipv4-address: " + e.cpUnusedIp()), null, List.of())),
                1, 0.2);

        String gwItemId = "gw-item-" + UUID.randomUUID();
        String clsItemId = "cls-item-" + UUID.randomUUID();
        markers.put(gwItemId, "cp-gw-inv");
        markers.put(clsItemId, "cp-cls-inv");
        var gateways = new CpObjectInventory(e.cpMgmt().id(), e.cpDomainId(), "gateways-and-servers", at, "RESOLVED", "", List.of(
                new CpObjectInventory.Item(gwItemId, e.cpGwAUid(), e.cpGwA().hostname(), "simple-gateway",
                        List.of(), List.of("ipv4-address: " + e.cpGwA().address(), "gateway-addresses: complete"), null,
                        List.of(new CpObjectInventory.Installation(e.cpPolicyName(), true)), null,
                        new CpObjectInventory.GatewayAddresses(List.of(e.cpGwA().address()), true)),
                new CpObjectInventory.Item(clsItemId, "uid-cls-" + UUID.randomUUID(), e.cpCluster(), "CpmiGatewayCluster",
                        List.of(), List.of("ipv4-address: " + e.cpClusterVip(), "gateway-addresses: complete"), null,
                        List.of(new CpObjectInventory.Installation(e.cpPolicyName(), true)), null,
                        new CpObjectInventory.GatewayAddresses(List.of(e.cpClusterVip(), e.cpGwA().address(), e.cpGwB().address()), true))),
                1, 0.3);

        for (CpObjectInventory inv : List.of(hosts, groups, zones, unused, gateways)) {
            repo.saveInventory(e.cpMgmt().id(), e.cpDomainId(), inv.type(), at, JSON.writeValueAsString(inv), Ui2Rows.ACTOR);
        }
    }

    private static void seedPanSnapshotAndCollection(Connection c, Estate e) throws Exception {
        var objA = new PolicySnapshot.PolicyObject(e.panObjAId(), e.panObjAName(), "host",
                List.of(), List.of("ip-netmask: " + e.panDupIp() + "/32"), "RESOLVED");
        var objB = new PolicySnapshot.PolicyObject(e.panObjBId(), e.panObjBName(), "host",
                List.of(), List.of("ip-netmask: " + e.panDupIp() + "/32"), "RESOLVED");
        var grp = new PolicySnapshot.PolicyObject(e.panGroupId(), e.panGroupName(), "address-group",
                List.of(e.panObjAId()), List.of(), "RESOLVED");
        var anyObj = new PolicySnapshot.PolicyObject("pan-any", "Any", "any", List.of(), List.of(), "RESOLVED");

        var panHits = new PolicySnapshot.HitCounts(18L, "2026-11-22T08:00:00Z", "2026-11-24T10:30:00Z",
                "device", "2026-11-24T12:00:00Z", null, List.of(
                        new PolicySnapshot.FirewallHits(e.panFw().id(), e.panVsys(), 18L,
                                "2026-11-22T08:00:00Z", "2026-11-24T10:30:00Z",
                                "2026-11-01T00:00:00Z", "2026-11-20T00:00:00Z", "2026-11-24T12:00:00Z")));

        var first = new PolicySnapshot.Rule(
                e.panRuleId(), UUID.randomUUID().toString(), 1, e.panRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.panGroupId()), false),
                new PolicySnapshot.Cell(List.of(e.panObjBId()), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                "allow", "end",
                "created by " + e.principal() + " for " + e.panFw().hostname(),
                Map.of("from", List.of(e.panZoneA(), e.panZoneB()),
                        "to", List.of(e.panZoneA(), e.panZoneB()),
                        "last-modifier", List.of(e.principal()),
                        "target-selectors", List.of(e.panFw().serial())),
                panHits);
        var shadowed = new PolicySnapshot.Rule(
                "rule-pan-sh-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2, e.panShadowRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.panObjAId()), false),
                new PolicySnapshot.Cell(List.of(e.panObjBId()), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                "allow", "end",
                "shadowed rule by " + e.stranger(),
                Map.of("from", List.of("any"), "to", List.of("any"), "last-modifier", List.of(e.principal())));

        var metadata = new PolicySnapshot.Metadata(
                e.panPolicyId(), e.panorama().id(), e.panorama().hostname(), "PAN",
                e.panDomainId(), e.panDeviceGroup(), e.panPolicyName(), "2026-11-24T12:05:00Z", null,
                List.of(new PolicySnapshot.Target(e.panFw().id(), e.panFw().hostname(), e.panVsys(), "IN_SYNC")));
        var snapshot = new PolicySnapshot(
                metadata,
                List.of(new PolicySnapshot.Section("pan-sec-1", "Pre rules", "Shared", null, List.of(first, shadowed))),
                Map.of(e.panObjAId(), objA, e.panObjBId(), objB, e.panGroupId(), grp, "pan-any", anyObj),
                List.of());

        audited(c, Ui2Rows.ACTOR, () -> {
            exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) "
                    + "values (?, '2026-11-24T12:05:00Z'::timestamptz, ?::jsonb, ?::jsonb)",
                    e.panPolicyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
            exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                    + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', "
                    + "'2026-11-24T12:05:00Z'::timestamptz, '2026-11-24T10:00:00Z', ?, ?::jsonb)",
                    e.panPolicyId(), e.panRuleId(), e.panRuleId(), e.principal(),
                    JSON.writeValueAsString(List.of(Map.of("field", "from", "before", e.panZoneA(), "after", e.panZoneB()))));
            exec(c, "insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) "
                    + "values (?, ?, ?, '2026-11-24T12:00:00Z'::timestamptz)",
                    e.panFw().id(), e.panVsys(), c.createArrayOf("text", new String[] {e.panZoneA(), e.panZoneB()}));

            // Failed Panorama policy collection job whose raw terminal_reason carries Panorama hostname, IP, serial and principal.
            String panCollectJob = insertJob(c, null, "pan_policy_collect", "pan_policy_collect", "FAILED",
                    "xml api to " + e.panorama().hostname() + " (" + e.panorama().address() + ", serial "
                            + e.panorama().serial() + ") refused for " + e.principal() + " and " + e.stranger(),
                    "discovery_run", ids.get("panDiscRun"), Ui2Rows.ACTOR, "2026-11-24T12:10:00Z");
            exec(c, "insert into policy_collection_request(job_id, source_id, domain_ref, automatic, mode) "
                    + "values (?, ?, '', false, 'CHANGED_ONLY')",
                    panCollectJob, e.panorama().id());
            ids.put("panCollectJob", panCollectJob);
            markers.put(panCollectJob, "pan-collect-job");
        });
    }

    private static void seedAsaAndFgtLocalSnapshots(Connection c, Estate e) throws Exception {
        audited(c, Ui2Rows.ACTOR, () -> {
            List<String> asaSerials = new ArrayList<>(List.of(e.asaA().serial(), e.asaB().serial()));
            Collections.sort(asaSerials);
            for (Device u : List.of(e.asaA(), e.asaB())) {
                writeStoredShowConfiguration(c, u, "cisco_asa", buildAsaConfig(e, u), "2026-11-24T10:00:00Z");
            }
            exec(c, "insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, recorded_by, "
                    + "last_state) values (?, 'cisco_asa', ?, ?, 'synthetic_seed', 'MATCH')",
                    e.asaCluster(), asaSerials.get(0), asaSerials.get(1));

            List<String> fgtSerials = new ArrayList<>(List.of(e.fgtA().serial(), e.fgtB().serial()));
            Collections.sort(fgtSerials);
            for (Device u : List.of(e.fgtA(), e.fgtB())) {
                writeStoredShowConfiguration(c, u, "fortinet", buildFgtConfig(e, u), "2026-11-24T10:15:00Z");
            }
            exec(c, "insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, recorded_by, "
                    + "last_state) values (?, 'fortinet', ?, ?, 'synthetic_seed', 'MATCH')",
                    e.fgtCluster(), fgtSerials.get(0), fgtSerials.get(1));
        });
    }

    private static void writeStoredShowConfiguration(Connection c, Device unit, String vendor, String configText, String collectedAt)
            throws Exception {
        String cap = vendor.equals("cisco_asa") ? "asa_configuration_collect" : "fgt_configuration_collect";
        String job = insertJob(c, unit.id(), cap, cap, "COMPLETED", null, "device", null, Ui2Rows.ACTOR, collectedAt);
        var handle = store.open(unit.id(), job, vendor, false);
        byte[] plain = configText.getBytes(StandardCharsets.UTF_8);
        handle.sink().write(plain);
        var meta = handle.finish();
        String ref = meta.ref().value();
        exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                + "wrapped_data_key, validation, retention_tier, recovery_volume_path, created_at) "
                + "values (?, ?, 'configuration', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                + "'standard', ?, ?::timestamptz)",
                ref, unit.id(), vendor, unit.softwareVersion(), sha256(unit.hostname()),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(),
                meta.keyId(), meta.wrappedDataKey(), ref, collectedAt);
        exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, 'none', ?)",
                ref, unit.id(), job, vendor, meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId());
        exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) "
                + "values (?, ?, ?, ?::timestamptz, ?, 'show_configuration', true, ?, ?, ?, ?, 'first_run')",
                Ui2Rows.opaqueId("cfgrun"), unit.id(), job, collectedAt, vendor,
                meta.plaintextSha256(), meta.plaintextSha256(), meta.plaintextBytes(), ref);
    }

    private static void seedJobsAndOperationsPlane(Connection c, Estate e) throws Exception {
        audited(c, e.principal(), () -> {
            // 1. Jobs with device targets, scheduled-backup system submitter, and raw failure reasons (for /jobs/facets,
            //    /jobs/stats and /config/scheduled-jobs last_run_at).
            insertJob(c, e.cpGwA().id(), "cp_gateway_backup", "cp_gateway_backup", "COMPLETED", null,
                    "device", null, "system:scheduled-backup", "2026-11-25T02:15:00Z");
            insertJob(c, e.panFw().id(), "pan_inventory_collect", "pan_inventory_collect", "FAILED",
                    "ssh to " + e.panFw().hostname() + " at " + e.panFw().address() + " serial " + e.panFw().serial()
                            + " failed for " + e.principal() + " and " + e.stranger(),
                    "device", null, e.principal(), "2026-11-25T03:00:00Z");

            // 2. Scheduled job switches (V203): catalogued principal on scheduled_backup, uncatalogued stranger on
            //    nightly_inventory, both with raw host/IP/serial/principal/stranger in reason.
            exec(c, "update scheduled_job_family set enabled = true, reason = ?, changed_by = ?, "
                    + "changed_at = '2026-11-25T01:00:00Z'::timestamptz where family_key = 'scheduled_backup'",
                    "enabled for " + e.cpGwA().hostname() + " (" + e.cpGwA().address() + ", serial " + e.cpGwA().serial()
                            + ") by " + e.principal() + " and " + e.stranger(),
                    e.principal());
            exec(c, "update scheduled_job_family set enabled = false, reason = ?, changed_by = ?, "
                    + "changed_at = '2026-11-25T01:05:00Z'::timestamptz where family_key = 'nightly_inventory'",
                    "paused on " + e.panFw().hostname() + " (" + e.panFw().address() + ") by " + e.stranger(),
                    e.stranger());

            // 3. Backup policy singleton update (V30/V44) + artefact store quotas (V225), including a store naming cpGwA.hostname().
            exec(c, "update backup_policy set schedule_enabled = true, daily_backup_cron = '15 2 * * *', "
                    + "updated_at = '2026-11-25T01:10:00Z'::timestamptz where policy_id = 'default'");
            exec(c, "insert into artefact_store_quota(store_name, quota_bytes, updated_by) values ('check_point', ?, ?), (?, ?, ?)",
                    10L * 1024 * 1024 * 1024, e.principal(),
                    "grp-" + e.cpGwA().hostname(), 5L * 1024 * 1024 * 1024, e.stranger());

            // 4. Backup artefacts + open size_anomaly deviation + primary config + compliance_evaluation + failover READINESS
            //    before AS_OF_QUERY (for /system/storage and /estate/as-of).
            String prevBackup = UUID.randomUUID().toString();
            String currBackup = UUID.randomUUID().toString();
            for (String bId : List.of(prevBackup, currBackup)) {
                long bytes = bId.equals(currBackup) ? 18_000L : 6_000L;
                String devState = bId.equals(currBackup) ? "changed" : "first";
                String ts = bId.equals(currBackup) ? "2026-11-25T06:00:00Z" : "2026-11-24T06:00:00Z";
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path, deviation_state, backup_type, created_at) "
                        + "values (?, ?, 'backup', 'check_point', 'R81.20', ?, ?, ?, ?, ?, 'k1', ?, "
                        + "'{\"level_reached\":\"V2\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?, ?, 'standard', ?::timestamptz)",
                        bId, e.cpGwA().id(), sha256(e.cpGwA().hostname()), "a".repeat(64), bytes, "b".repeat(64), bytes,
                        new byte[32], bId, devState, ts);
            }
            exec(c, "insert into backup_deviation_record(deviation_id, artefact_id, previous_artefact_id, device_id, "
                    + "deviation_class, summary, details, detected_at) values (?, ?, ?, ?, 'size_anomaly', ?, "
                    + "'{\"direction\":\"growth\",\"plaintext_bytes\":18000,\"median_bytes\":6000,\"ratio\":3.0}'::jsonb, "
                    + "'2026-11-25T06:05:00Z'::timestamptz)",
                    UUID.randomUUID().toString(), currBackup, prevBackup, e.cpGwA().id(),
                    "backup of " + e.cpGwA().hostname() + " (" + e.cpGwA().address() + ", serial " + e.cpGwA().serial()
                            + ") grew for " + e.principal() + " and " + e.stranger());

            String cpCfgHash = sha256("cp-canonical-" + e.cpGwA().id());
            String cpCfgJob = insertJob(c, e.cpGwA().id(), "cp_configuration_collect", "cp_configuration_collect",
                    "COMPLETED", null, "device", null, Ui2Rows.ACTOR, "2026-11-25T07:00:00Z");
            String cpCfgArt = Ui2Rows.opaqueId("cfgart");
            exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                    + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                    + "values (?, ?, ?, 'check_point', ?, 128, ?, 128, 'none', 'k1')",
                    cpCfgArt, e.cpGwA().id(), cpCfgJob, cpCfgHash, sha256("cipher-" + cpCfgArt));
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) "
                    + "values (?, ?, ?, '2026-11-25T07:00:00Z'::timestamptz, 'check_point', 'show_configuration', true, "
                    + "?, ?, 128, ?, 'first_run')",
                    Ui2Rows.opaqueId("cfgrun"), e.cpGwA().id(), cpCfgJob, cpCfgHash, cpCfgHash, cpCfgArt);
            String complianceResult = JSON.writeValueAsString(Map.of(
                    "items", List.of(
                            Map.of("controlId", "CP-NTP-01", "displayStatus", "FAIL", "severity", "CRITICAL",
                                    "observedValue", e.cpGwA().hostname() + " " + e.cpGwA().address()),
                            Map.of("controlId", "CP-DNS-02", "displayStatus", "PASS", "severity", "HIGH"))));
            exec(c, "insert into compliance_evaluation(device_id, canonical_hash, config_hash, rules_fingerprint, evaluated_at, result) "
                    + "values (?, ?, ?, 'rules-v1', '2026-11-25T07:05:00Z'::timestamptz, ?::jsonb)",
                    e.cpGwA().id(), cpCfgHash, cpCfgHash, complianceResult);

            String foJob = insertJob(c, e.cpGwA().id(), "cp_failover_readiness", "cp_failover_readiness",
                    "COMPLETED", null, "device", null, Ui2Rows.ACTOR, "2026-11-25T08:00:00Z");
            String foRun = "fo-r9-" + UUID.randomUUID();
            exec(c, "insert into failover_run(run_id, cluster_ref, vs_id, approval_id, requested_by, scheduled_for, job_id, "
                    + "state, step, vendor, run_kind, started_at, finished_at, outcome, message) "
                    + "values (?, ?, null, null, ?, '2026-11-25T07:58:00Z'::timestamptz, ?, "
                    + "'DONE', 'DONE', 'check_point', 'READINESS', '2026-11-25T07:59:00Z'::timestamptz, "
                    + "'2026-11-25T08:00:00Z'::timestamptz, 'READY', ?)",
                    foRun, e.cpCluster(), e.principal(), foJob,
                    "readiness on " + e.cpGwA().hostname() + " (" + e.cpGwA().address() + ") of " + e.cpCluster()
                            + " checked by " + e.principal() + " and " + e.stranger());

            // 5. Quarantined endpoint_admission row embedding raw device address and pod hostname (for /system/pods).
            exec(c, "insert into endpoint_admission(request_id, endpoint_ref, purpose_class, operation_ref, operation_epoch, "
                    + "session_ref, owner_role, owner_instance, owner_generation, lease_token, state) "
                    + "values (?, ?, 'POLICY', ?, 1, ?, 'policy', ?, 1, ?, 'QUARANTINED')",
                    "req-" + UUID.randomUUID(), e.cpGwA().address(), "op-" + UUID.randomUUID(),
                    "sess-" + UUID.randomUUID(), "policy-" + e.podHost(), "tok-" + UUID.randomUUID());

            // 6. System-log crash entry + crash alert + gap (V160, for /system-logs/crash-files and /system-logs/gaps).
            String sysLogJob = insertJob(c, e.cpGwA().id(), "cp_system_log_collect", "cp_system_log_collect",
                    "COMPLETED", null, "device", null, Ui2Rows.ACTOR, "2026-11-25T09:00:00Z");
            String crashEntryId = "crash-ent-" + UUID.randomUUID();
            String crashAlertId = "crash-alt-" + UUID.randomUUID();
            String gapId = "gap-r9-" + UUID.randomUUID();
            String crashProcess = "core." + e.cpGwA().hostname();
            exec(c, "insert into device_crash_entry(entry_id, device_id, directory, name_hmac, process, size, mtime, "
                    + "baseline, first_seen_job, last_seen_job) values (?, ?, 'dump_usermode', ?, ?, 4096, 1790000100, "
                    + "false, ?, ?)",
                    crashEntryId, e.cpGwA().id(), sha256(e.crashFileName()), crashProcess, sysLogJob, sysLogJob);
            exec(c, "insert into device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id) "
                    + "values (?, ?, ?, 1, 1790000100, ?)",
                    crashAlertId, e.cpGwA().id(), crashProcess, sysLogJob);
            exec(c, "insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes, pending_bytes, "
                    + "extent_unknown, window_start_epoch, window_end_epoch) "
                    + "values (?, ?, 'cp_messages', ?, 'ROTATED_AWAY', 2048, 0, false, 1790000000, 1790000060)",
                    gapId, e.cpGwA().id(), sysLogJob);
            markers.put(crashEntryId, "crash-entry");
            markers.put(crashAlertId, "crash-alert");
            markers.put(gapId, "syslog-gap");

            // 7. Notification settings + security_scan route with raw SMTP/recipient addresses and collector failure text in
            //    last_error naming enrolled gateway host, IP, serial and catalogued principal (NotificationMailRouter shape).
            exec(c, "update notification_settings set syslog_enabled = true, syslog_host = ?, smtp_enabled = true, "
                    + "smtp_host = ?, smtp_from = ?, smtp_to = ?, updated_at = now(), updated_by = ? "
                    + "where settings_id = 'default'",
                    e.syslogHost(), e.smtpHost(), e.smtpFrom(), e.smtpTo(), e.principal());
            exec(c, "update notification_routes set enabled = true, recipients = ?, last_sent_at = '2026-11-25T02:35:00Z'::timestamptz, "
                    + "last_error = ?, updated_at = now(), updated_by = ? where type = 'security_scan'",
                    e.scanRecipients(),
                    "event collection failed: " + e.cpGwA().hostname() + " (" + e.cpGwA().address()
                            + ", serial " + e.cpGwA().serial() + ") for " + e.principal(),
                    e.principal());
        });
    }

    private static void seedLogDetectionPlane(Estate e) {
        var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        var repo = new JooqLogDetectionRepository(tx);
        repo.writeSettings(e.principal(), true, null);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Istanbul"));
        String logJob = "job-logdet-" + UUID.randomUUID();
        repo.upsertCoverage(new LogDetectionRepository.CoverageRow(
                e.cpGwA().id(), "cp_messages", logJob, today, "EVALUATED", "COMPLETE", 14));
        repo.upsertFinding(new LogDetectionRepository.FindingRow(
                "fnd-r9-" + UUID.randomUUID(), e.cpGwA().id(), "cp_messages",
                "R-BURST-01", "v1", "RATE_ANOMALY", "cp_auth_fail", e.cpGwA().serial(),
                today, LocalDateTime.of(2026, 11, 25, 8, 0), LocalDateTime.of(2026, 11, 25, 9, 0),
                "CRITICAL", "R-CRIT-01", 9.5, "ENGINE", 25L, 12, 14, 14, 2, 4, 1,
                false, false, List.of("auth"), "grp-1", "EVALUATED", List.of(),
                "logmask-v4", "markers-v1", "cfg-v1", "OPEN", e.principal(), Instant.now(), Instant.now(), Instant.now()));
    }

    private static String insertJob(Connection c, String deviceId, String jobType, String capabilityId, String state,
            String reason, String targetKind, String targetRef, String submitter, String finishedAtIso) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(c, "insert into jobs(job_id, job_type, capability_id, target_kind, target_device_id, target_ref, "
                + "submitted_by_actor_fingerprint, state, action_class, terminal_reason, submitted_at, finished_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, 'read', ?, ?::timestamptz, ?::timestamptz)",
                id, jobType, capabilityId, targetKind, deviceId, targetRef, submitter, state, reason,
                finishedAtIso, finishedAtIso);
        return id;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    private static void audited(Connection c, String actor, SqlWork work) throws Exception {
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

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ body scan helpers (copied from rounds 1-7)

    static List<Pattern> forms(String value, ValueClass cls) {
        List<Pattern> out = new ArrayList<>();
        if (cls == ValueClass.ADDRESS && value.contains(":")) {
            Set<String> spellings = new TreeSet<>();
            spellings.add(value);
            try {
                byte[] bytes = InetAddress.getByName(value).getAddress();
                StringBuilder full = new StringBuilder();
                StringBuilder unpadded = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    int group = ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
                    if (i > 0) {
                        full.append(':');
                        unpadded.append(':');
                    }
                    full.append(String.format(Locale.ROOT, "%04x", group));
                    unpadded.append(Integer.toHexString(group));
                }
                spellings.add(full.toString());
                spellings.add(unpadded.toString());
            } catch (UnknownHostException e) {
                throw new IllegalStateException("seeded IPv6 literal did not parse", e);
            }
            for (String s : spellings) {
                out.add(Pattern.compile("(?<![0-9a-f:])" + Pattern.quote(s) + "(?![0-9a-f:])", Pattern.CASE_INSENSITIVE));
            }
        } else if (cls == ValueClass.ADDRESS) {
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

    // ------------------------------------------------------------------ HTTP helpers (GET-only sweep)

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

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
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String login() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
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

    // ------------------------------------------------------------------ concrete GET calls

    record Call(String key, String path, String detail) {
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static List<Call> calls(Estate e) {
        List<Call> out = new ArrayList<>();
        // Plane 2 free-text / advice-masked routes run first on a cold process before other reads warm caches.
        out.add(new Call("GET /api/v2/security/scan-summary/latest", "/api/v2/security/scan-summary/latest", "scan-summary"));
        out.add(new Call("GET /api/v2/system-logs/crash-files",
                "/api/v2/system-logs/crash-files?deviceId=" + enc(e.cpGwA().id()), "crash-files-cp"));
        out.add(new Call("GET /api/v2/system-logs/gaps",
                "/api/v2/system-logs/gaps?deviceId=" + enc(e.cpGwA().id()), "gaps-cp"));
        out.add(new Call("GET /api/v2/backups/store-quotas", "/api/v2/backups/store-quotas", "store-quotas"));
        out.add(new Call("GET /api/v2/config/scheduled-jobs", "/api/v2/config/scheduled-jobs", "scheduled-jobs"));
        out.add(new Call("GET /api/v2/system/storage", "/api/v2/system/storage", "system-storage"));
        out.add(new Call("GET /api/v2/estate/as-of",
                "/api/v2/estate/as-of?at=" + enc(AS_OF_QUERY), "estate-as-of"));

        // Plane 2 reviewed no-identity-field routes
        out.add(new Call("GET /api/v2/jobs/facets", "/api/v2/jobs/facets", "jobs-facets"));
        out.add(new Call("GET /api/v2/jobs/stats", "/api/v2/jobs/stats", "jobs-stats"));
        out.add(new Call("GET /api/v2/jobs/*/transcript/health",
                "/api/v2/jobs/" + enc(ids.get("transcriptJob")) + "/transcript/health", "transcript-health"));
        out.add(new Call("GET /api/v2/job-window", "/api/v2/job-window", "job-window"));
        out.add(new Call("GET /api/v2/backups/policies", "/api/v2/backups/policies", "backup-policies"));
        out.add(new Call("GET /api/v2/log-findings/budget", "/api/v2/log-findings/budget", "log-findings-budget"));
        out.add(new Call("GET /api/v2/system/pods", "/api/v2/system/pods", "system-pods"));
        out.add(new Call("GET /api/v2/zone-matrix/template", "/api/v2/zone-matrix/template", "zone-matrix-template"));
        out.add(new Call("GET /project-plan", "/project-plan", "project-plan"));

        // Plane 2 companion refused routes
        out.add(new Call("GET /jobs/*/transcript",
                "/jobs/" + enc(ids.get("transcriptJob")) + "/transcript", "transcript-raw-refused"));
        out.add(new Call("GET /api/v2/config/log-detection", "/api/v2/config/log-detection", "log-detection-config-refused"));

        // Plane 1: policy tree, sources, hygiene-summary, collections, devices, objects, and all 7 domain sub-routes
        out.add(new Call("GET /api/v2/policy/tree", "/api/v2/policy/tree", "tree-root"));
        out.add(new Call("GET /api/v2/policy/tree", "/api/v2/policy/tree?source=" + enc(e.cpMgmt().id()), "tree-cp-source"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?source=" + enc(e.cpMgmt().id()) + "&container=" + enc(e.cpDomainId()), "tree-cp-container"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?source=" + enc(e.panorama().id()) + "&container=" + enc(e.panDomainId()), "tree-pan-container"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?source=" + enc(e.asaA().id()) + "&container=" + enc(ids.get("asaContainerId")),
                "tree-asa-container"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?source=" + enc(e.fgtA().id()) + "&container=" + enc(ids.get("fgtContainerId")),
                "tree-fgt-container"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?device=" + enc(e.asaA().id()), "tree-asa-device"));
        out.add(new Call("GET /api/v2/policy/tree",
                "/api/v2/policy/tree?device=" + enc(e.fgtA().id()), "tree-fgt-device"));

        out.add(new Call("GET /api/v2/policy/sources", "/api/v2/policy/sources", "policy-sources"));
        out.add(new Call("GET /api/v2/policy/hygiene-summary", "/api/v2/policy/hygiene-summary", "policy-hygiene-summary"));

        out.add(new Call("GET /api/v2/policy/collections/*",
                "/api/v2/policy/collections/" + enc(ids.get("cpCollectJob")), "collection-cp-completed"));
        out.add(new Call("GET /api/v2/policy/collections/*",
                "/api/v2/policy/collections/" + enc(ids.get("panCollectJob")), "collection-pan-failed"));

        out.add(new Call("GET /api/v2/policy/devices/*",
                "/api/v2/policy/devices/" + enc(e.cpGwA().id()), "policy-device-cp"));
        out.add(new Call("GET /api/v2/policy/devices/*",
                "/api/v2/policy/devices/" + enc(e.panFw().id()), "policy-device-pan"));
        out.add(new Call("GET /api/v2/policy/devices/*",
                "/api/v2/policy/devices/" + enc(e.asaA().id()), "policy-device-asa"));
        out.add(new Call("GET /api/v2/policy/devices/*",
                "/api/v2/policy/devices/" + enc(e.fgtA().id()), "policy-device-fgt"));

        out.add(new Call("GET /api/v2/policy/objects/*",
                "/api/v2/policy/objects/" + enc(ids.get("cpGroupSnapId"))
                        + "?device=" + enc(e.cpGwA().id()) + "&policy=" + enc(e.cpPolicyId()),
                "policy-object-cp-group"));
        out.add(new Call("GET /api/v2/policy/objects/*",
                "/api/v2/policy/objects/" + enc(ids.get("cpHostASnapId"))
                        + "?device=" + enc(e.cpGwA().id()) + "&policy=" + enc(e.cpPolicyId()),
                "policy-object-cp-host"));
        out.add(new Call("GET /api/v2/policy/objects/*",
                "/api/v2/policy/objects/" + enc(e.panObjAId())
                        + "?device=" + enc(e.panFw().id()) + "&policy=" + enc(e.panPolicyId()),
                "policy-object-pan-host"));
        out.add(new Call("GET /api/v2/policy/objects/*",
                "/api/v2/policy/objects/" + enc(ids.get("asaObjId"))
                        + "?device=" + enc(e.asaA().id()) + "&policy=" + enc(ids.get("asaPolicyId")),
                "policy-object-asa-host"));
        out.add(new Call("GET /api/v2/policy/objects/*",
                "/api/v2/policy/objects/" + enc(ids.get("fgtObjId"))
                        + "?device=" + enc(e.fgtA().id()) + "&policy=" + enc(ids.get("fgtPolicyId")),
                "policy-object-fgt-host"));

        out.add(new Call("GET /api/v2/policy/domains/*/gateways",
                "/api/v2/policy/domains/" + enc(e.cpDomainId()) + "/gateways?source=" + enc(e.cpMgmt().id()),
                "domain-gateways-cp"));
        out.add(new Call("GET /api/v2/policy/domains/*/unused",
                "/api/v2/policy/domains/" + enc(e.cpDomainId()) + "/unused?source=" + enc(e.cpMgmt().id()),
                "domain-unused-cp"));

        out.add(new Call("GET /api/v2/policy/domains/*/hits",
                "/api/v2/policy/domains/" + enc(e.cpDomainId()) + "/hits?source=" + enc(e.cpMgmt().id()),
                "domain-hits-cp"));
        out.add(new Call("GET /api/v2/policy/domains/*/hits",
                "/api/v2/policy/domains/" + enc(e.panDomainId()) + "/hits?source=" + enc(e.panorama().id()),
                "domain-hits-pan"));

        String[][] domains = {
                {"cp", e.cpMgmt().id(), e.cpDomainId(), e.cpHostAUid()},
                {"pan", e.panorama().id(), e.panDomainId(), e.panObjAId()},
                {"asa", e.asaA().id(), ids.get("asaContainerId"), ids.get("asaObjId")},
                {"fgt", e.fgtA().id(), ids.get("fgtContainerId"), ids.get("fgtObjId")}
        };
        for (String[] d : domains) {
            String vendor = d[0], src = d[1], dom = d[2], uid = d[3];
            out.add(new Call("GET /api/v2/policy/domains/*/objects",
                    "/api/v2/policy/domains/" + enc(dom) + "/objects?source=" + enc(src),
                    "domain-objects-" + vendor));
            out.add(new Call("GET /api/v2/policy/domains/*/objects/duplicates",
                    "/api/v2/policy/domains/" + enc(dom) + "/objects/duplicates?source=" + enc(src),
                    "domain-duplicates-" + vendor));
            out.add(new Call("GET /api/v2/policy/domains/*/installation",
                    "/api/v2/policy/domains/" + enc(dom) + "/installation?source=" + enc(src),
                    "domain-installation-" + vendor));
            out.add(new Call("GET /api/v2/policy/domains/*/object-usage",
                    "/api/v2/policy/domains/" + enc(dom) + "/object-usage?source=" + enc(src) + "&uid=" + enc(uid),
                    "domain-usage-" + vendor));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    /**
     * Route-ledger invariant: {@link #COVERAGE}, {@link #NO_IDENTITY_FIELDS_REVIEWED} and {@link #REFUSED_BY_DESIGN}
     * must be pairwise disjoint, their union must equal {@code PLANE_1_ROUTES U PLANE_2_ROUTES} and must match the
     * Javadoc table of this class row for row, every route must exist in {@link AiviewReachableRoutes#all()}, and the
     * seeded local-snapshot configurations must parse, map and mask cleanly with zero surviving raw values.
     */
    @Test
    void routeLedgerMatchesJavadocAndStaticAudit() throws Exception {
        Set<String> covered = COVERAGE.keySet();
        Set<String> noIdentity = NO_IDENTITY_FIELDS_REVIEWED.keySet();
        Set<String> refused = REFUSED_BY_DESIGN.keySet();

        assertTrue(Collections.disjoint(covered, noIdentity), "COVERAGE and NO_IDENTITY_FIELDS_REVIEWED must be disjoint");
        assertTrue(Collections.disjoint(covered, refused), "COVERAGE and REFUSED_BY_DESIGN must be disjoint");
        assertTrue(Collections.disjoint(noIdentity, refused), "NO_IDENTITY_FIELDS_REVIEWED and REFUSED_BY_DESIGN must be disjoint");
        assertTrue(Collections.disjoint(PLANE_1_ROUTES, PLANE_2_ROUTES), "PLANE_1_ROUTES and PLANE_2_ROUTES must be disjoint");

        Set<String> ledgerUnion = new TreeSet<>(covered);
        ledgerUnion.addAll(noIdentity);
        ledgerUnion.addAll(refused);

        Set<String> planesUnion = new TreeSet<>(PLANE_1_ROUTES);
        planesUnion.addAll(PLANE_2_ROUTES);
        assertEquals(planesUnion, ledgerUnion,
                "covered + NO_IDENTITY_FIELDS_REVIEWED + refused must equal PLANE_1_ROUTES U PLANE_2_ROUTES");

        // Parse the Javadoc route table directly from this source file so adding or dropping a row in either place fails.
        Path source = findOwnSourceFile();
        String sourceText = Files.readString(source, StandardCharsets.UTF_8);
        Pattern rowPattern = Pattern.compile(
                "^ \\* (GET [^|]+?)\\s*\\|\\s*(PLANE_[12])\\s*\\|\\s*(COVERED|NO_IDENTITY_FIELDS_REVIEWED|NOT COVERED \\(refused by design\\))\\s*\\|\\s*(.+)$",
                Pattern.MULTILINE);
        Map<String, String> javadocPlane = new TreeMap<>();
        Map<String, String> javadocDisposition = new TreeMap<>();
        Matcher matcher = rowPattern.matcher(sourceText);
        while (matcher.find()) {
            String route = matcher.group(1).strip().replaceAll("\\{[^}]+}", "*");
            String plane = matcher.group(2).strip();
            String disposition = matcher.group(3).strip();
            String why = matcher.group(4).strip();
            assertFalse(why.isEmpty(), "Javadoc reason must not be blank for " + route);
            assertEquals(null, javadocDisposition.put(route, disposition), "duplicate Javadoc row for " + route);
            javadocPlane.put(route, plane);
        }
        assertEquals(planesUnion, javadocDisposition.keySet(),
                "Javadoc table routes must equal PLANE_1_ROUTES U PLANE_2_ROUTES exactly");
        for (String r : PLANE_1_ROUTES) {
            assertEquals("PLANE_1", javadocPlane.get(r), "Plane mismatch in Javadoc for " + r);
        }
        for (String r : PLANE_2_ROUTES) {
            assertEquals("PLANE_2", javadocPlane.get(r), "Plane mismatch in Javadoc for " + r);
        }
        for (String r : covered) {
            assertEquals("COVERED", javadocDisposition.get(r), "Disposition mismatch in Javadoc for " + r);
        }
        for (String r : noIdentity) {
            assertEquals("NO_IDENTITY_FIELDS_REVIEWED", javadocDisposition.get(r), "Disposition mismatch in Javadoc for " + r);
            assertFalse(NO_IDENTITY_FIELDS_REVIEWED.get(r).isBlank(), "Reviewed response class must be named for " + r);
        }
        for (String r : refused) {
            assertEquals("NOT COVERED (refused by design)", javadocDisposition.get(r), "Disposition mismatch in Javadoc for " + r);
        }

        // Cross-check against AiviewReachableRoutes.all()
        Map<String, Boolean> staticReachable = new TreeMap<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (!"GET".equals(ep.method())) continue;
            boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
            staticReachable.merge(ep.key(), may, (a, b) -> a || b);
        }
        for (String route : ledgerUnion) {
            assertTrue(staticReachable.containsKey(route), "Route missing from AiviewReachableRoutes.all(): " + route);
            if (!refused.contains(route)) {
                assertTrue(staticReachable.get(route), "Expected aiview-reachable in static audit: " + route);
            }
        }

        // Verify the synthetic ASA and FortiGate configurations parse, map, form SAME HA pairs, carry normalized
        // duplicate values, and mask cleanly with zero surviving raw values.
        Estate sample = Estate.generate();
        var asaParsedA = AsaConfigPolicyParser.parse(buildAsaConfig(sample, sample.asaA()));
        var asaParsedB = AsaConfigPolicyParser.parse(buildAsaConfig(sample, sample.asaB()));
        PolicySnapshot asaSnap = AsaPolicyMapper.map(sample.asaA().id(), sample.asaA().hostname(),
                "2026-11-24T10:00:00Z", "art-asa-a", asaParsedA);
        PolicySnapshot asaSnapB = AsaPolicyMapper.map(sample.asaB().id(), sample.asaB().hostname(),
                "2026-11-24T10:00:00Z", "art-asa-b", asaParsedB);
        assertTrue(asaSnap.failures().isEmpty(), () -> "ASA mapper failures: " + asaSnap.failures());
        assertEquals(HaPolicyPairing.canonicalHash(asaSnap), HaPolicyPairing.canonicalHash(asaSnapB),
                "ASA HA pair canonical policy hash must be SAME");
        var asaHostA = asaSnap.objects().values().stream().filter(o -> sample.asaHostAName().equals(o.name())).findFirst().orElseThrow();
        var asaHostB = asaSnap.objects().values().stream().filter(o -> sample.asaHostBName().equals(o.name())).findFirst().orElseThrow();
        assertEquals(asaHostA.values(), asaHostB.values(), "ASA duplicate host objects must carry identical normalized values");
        assertTrue(asaHostA.values().stream().anyMatch(v -> v.startsWith("ip-netmask: ") || v.startsWith("ipv4-address: ")),
                () -> "ASA host values must be recognized by PolicyDomainIndex.normalized: " + asaHostA.values());

        var fgtParsedA = FortiGateConfigPolicyParser.parse(buildFgtConfig(sample, sample.fgtA()), "7.4.8");
        var fgtParsedB = FortiGateConfigPolicyParser.parse(buildFgtConfig(sample, sample.fgtB()), "7.4.8");
        List<PolicySnapshot> fgtSnaps = FortiGatePolicyMapper.map(sample.fgtA().id(), sample.fgtA().hostname(),
                "2026-11-24T10:15:00Z", "art-fgt-a", fgtParsedA);
        List<PolicySnapshot> fgtSnapsB = FortiGatePolicyMapper.map(sample.fgtB().id(), sample.fgtB().hostname(),
                "2026-11-24T10:15:00Z", "art-fgt-b", fgtParsedB);
        assertEquals(1, fgtSnaps.size());
        PolicySnapshot fgtSnap = fgtSnaps.get(0);
        assertTrue(fgtSnap.failures().isEmpty(), () -> "FGT mapper failures: " + fgtSnap.failures());
        assertEquals(HaPolicyPairing.canonicalHash(fgtSnap), HaPolicyPairing.canonicalHash(fgtSnapsB.get(0)),
                "FGT HA pair canonical policy hash must be SAME");
        var fgtHostA = fgtSnap.objects().values().stream().filter(o -> sample.fgtHostAName().equals(o.name())).findFirst().orElseThrow();
        var fgtHostB = fgtSnap.objects().values().stream().filter(o -> sample.fgtHostBName().equals(o.name())).findFirst().orElseThrow();
        assertEquals(fgtHostA.values(), fgtHostB.values(), "FGT duplicate host objects must carry identical normalized values");
        assertTrue(fgtHostA.values().stream().anyMatch(v -> v.startsWith("ip-netmask: ") || v.startsWith("ipv4-address: ")),
                () -> "FGT host values must be recognized by PolicyDomainIndex.normalized: " + fgtHostA.values());

        var names = new TopologyNamePseudonymizer(new byte[32]);
        var ips = new SubnetPreservingIpMasker(new byte[32]);
        for (PolicySnapshot snap : List.of(asaSnap, fgtSnap)) {
            String maskedJson = JSON.writeValueAsString(
                    PolicyPrivacy.mask(JSON.convertValue(snap, Map.class), "", names, ips));
            assertEquals(Map.of(), count(maskedJson, sample.raw()),
                    "PolicyPrivacy.mask must leave zero surviving raw values on mapped local snapshot " + snap.metadata().vendor());
        }
    }

    @Test
    void noPlantedRawValueSurvivesInPolicyDomainsAndJobsOperationsPlanes() throws Exception {
        ensureBooted();
        SENT.clear();
        String cookie = login();
        Estate e = estate;

        List<Call> calls = calls(e);
        Set<String> calledRoutes = new TreeSet<>();
        calls.forEach(c -> calledRoutes.add(c.key()));
        Set<String> expectedRoutes = new TreeSet<>(PLANE_1_ROUTES);
        expectedRoutes.addAll(PLANE_2_ROUTES);
        assertEquals(expectedRoutes, calledRoutes, "every Plane-1 and Plane-2 route in the ledger must be called");

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, List<Integer>> statuses = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        Map<String, String> bodiesForNoIdentityCheck = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int totalSurviving = 0;

        for (Call call : calls) {
            Resp resp = get(call.path(), cookie);
            statuses.computeIfAbsent(call.key(), k -> new ArrayList<>()).add(resp.status());
            Map<ValueClass, Integer> found = count(resp.body(), e.raw());
            if (!found.isEmpty()) {
                leaks.computeIfAbsent(call.key(), k -> new TreeMap<>());
                found.forEach((cls, n) -> leaks.get(call.key()).merge(cls, n, Integer::sum));
            }
            Set<String> kinds = seen.computeIfAbsent(call.key(), k -> new TreeSet<>());
            recordMarkers(call.key(), resp.body(), e, kinds);
            if (NO_IDENTITY_FIELDS_REVIEWED.containsKey(call.key())) {
                bodiesForNoIdentityCheck.put(call.key(), resp.body());
            }
            int sum = found.values().stream().mapToInt(Integer::intValue).sum();
            totalSurviving += sum;
            String disposition = REFUSED_BY_DESIGN.containsKey(call.key()) ? "refused"
                    : NO_IDENTITY_FIELDS_REVIEWED.containsKey(call.key()) ? "no-id" : "covered";
            report.add(String.format(Locale.ROOT, "AIVIEW_ROUND9 %-8s %3d %-48s %-28s raw=%s",
                    disposition, resp.status(), call.key(), call.detail(), found.isEmpty() ? "0" : found.toString()));
        }
        report.forEach(System.out::println);
        System.out.println("AIVIEW_ROUND9_SUMMARY calls=" + calls.size()
                + " routes=" + calledRoutes.size()
                + " covered=" + COVERAGE.size()
                + " reviewed_no_identity=" + NO_IDENTITY_FIELDS_REVIEWED.size()
                + " refused=" + REFUSED_BY_DESIGN.size()
                + " planted_values=" + e.raw().size()
                + " surviving_raw_occurrences=" + totalSurviving
                + " sent=" + SENT);

        assertEquals(Map.of("GET", calls.size()), SENT, "only GET requests may be sent during the sweep (login is the sole POST)");

        // Refused routes: must answer 401 or 403
        for (String refused : REFUSED_BY_DESIGN.keySet()) {
            for (int code : statuses.getOrDefault(refused, List.of())) {
                assertTrue(code == 401 || code == 403, refused + " must refuse aiview with 401/403, got " + code);
            }
        }

        // Covered and reviewed-no-identity routes: every call must answer 200 with a non-blank body
        for (Call call : calls) {
            if (REFUSED_BY_DESIGN.containsKey(call.key())) continue;
            List<Integer> routeStatuses = statuses.getOrDefault(call.key(), List.of());
            assertTrue(routeStatuses.stream().allMatch(s -> s == 200),
                    call.key() + " (" + call.detail() + ") must answer 200 on every call, got " + routeStatuses);
        }

        // COVERAGE markers on all 20 identity-bearing routes
        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            Set<String> actual = seen.getOrDefault(route, Set.of());
            if (!actual.containsAll(expected)) {
                uncovered.add(route + " expected " + expected + " saw " + actual + " statuses " + statuses.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "every COVERED route must return all expected seeded-row markers");

        // Structural non-empty assertions on all 9 NO_IDENTITY_FIELDS_REVIEWED routes
        verifyNoIdentityPayloads(bodiesForNoIdentityCheck);

        // Exact leak assertion
        assertEquals(KNOWN_LEAKS, leaks,
                "surviving planted raw value counts per (route, ValueClass) must match KNOWN_LEAKS exactly");
    }

    private static void recordMarkers(String routeKey, String body, Estate e, Set<String> kinds) {
        if (!body.isBlank()) {
            markers.forEach((token, label) -> {
                if (body.contains(token)) kinds.add(label);
            });
        }
        if ("GET /api/v2/policy/hygiene-summary".equals(routeKey)) {
            if (body.contains("\"state\":\"READY\"")) kinds.add("hygiene-ready");
            if (body.contains("\"haPairsCountedOnce\":2")) kinds.add("hygiene-ha-counted-once");
        }
        if ("GET /api/v2/policy/collections/*".equals(routeKey) && body.contains("\"hasTranscript\":true")) {
            kinds.add("has-transcript");
        }
        if ("GET /api/v2/policy/objects/*".equals(routeKey) && body.contains("\"resolution\":\"DOMAIN_INVENTORY\"")) {
            kinds.add("domain-inventory-resolution");
        }
        if ("GET /api/v2/backups/store-quotas".equals(routeKey)) {
            if (body.contains("\"store\":\"check_point\"")) kinds.add("store-quota-cp");
            if (body.contains("\"store\":\"grp-FW-") && !body.contains(e.cpGwA().hostname())) kinds.add("store-quota-masked-host");
        }
        if ("GET /api/v2/config/scheduled-jobs".equals(routeKey)) {
            if (body.contains("\"family\":\"scheduled_backup\"")) kinds.add("scheduled-family-backup");
            if (body.contains("\"masked\":true") && body.contains("\"reason\":null")) kinds.add("scheduled-masked-flag");
            if (body.contains("\"changed_by\":\"USR-")) kinds.add("scheduled-usr-token");
            if (body.contains("\"last_run_at\":\"2026-11-25T02:15:00Z\"")) kinds.add("scheduled-last-run");
        }
        if ("GET /api/v2/system/storage".equals(routeKey)) {
            if (body.contains(e.cpGwA().id())) kinds.add("storage-top-device");
            if (body.contains("\"hostname\":\"FW-")) kinds.add("storage-masked-fw");
            if (body.contains("\"cluster_member_ref\":\"CLS-")) kinds.add("storage-masked-cls");
        }
        if ("GET /api/v2/system-logs/crash-files".equals(routeKey)
                && body.contains("\"process\":\"core.FW-") && !body.contains(e.cpGwA().hostname())) {
            kinds.add("crash-masked-process");
        }
        if ("GET /api/v2/security/scan-summary/latest".equals(routeKey)) {
            if (body.contains("\"delivering\":true")) kinds.add("scan-mail-delivering");
            if (body.contains("FW-") && !body.contains(e.cpGwA().hostname())) kinds.add("scan-masked-error");
        }
        if ("GET /api/v2/estate/as-of".equals(routeKey)) {
            if (body.contains("\"ready\":2") && body.contains("\"2026-11-25T08:00:00Z\"")) {
                kinds.add("as-of-readiness");
            }
            if (body.contains("\"open_size_anomaly\":1") || body.contains("\"open_size_anomaly\":true")) {
                kinds.add("as-of-size-anomaly");
            }
            if (body.contains("\"evidenced_units\":1") && body.contains("\"critical_failing_units\":1")) {
                kinds.add("as-of-compliance-evidenced");
            }
        }
    }

    private static void verifyNoIdentityPayloads(Map<String, String> bodies) throws Exception {
        JsonNode facets = JSON.readTree(bodies.get("GET /api/v2/jobs/facets"));
        assertTrue(facets.path("states").isArray() && facets.path("states").size() >= 2, "jobs/facets states non-empty");
        assertTrue(facets.path("job_types").isArray() && facets.path("job_types").size() >= 2, "jobs/facets job_types non-empty");

        JsonNode stats = JSON.readTree(bodies.get("GET /api/v2/jobs/stats"));
        assertTrue(stats.path("total").asLong() >= 4, "jobs/stats total reflects seeded jobs");
        assertTrue(stats.path("failed").asLong() >= 2, "jobs/stats failed reflects seeded failed jobs");

        JsonNode th = JSON.readTree(bodies.get("GET /api/v2/jobs/*/transcript/health"));
        assertTrue(th.path("present").asBoolean(), "transcript/health present");
        assertTrue(th.path("decrypts").asBoolean(), "transcript/health decrypts");
        assertEquals(2, th.path("entries").asInt(), "transcript/health entries count");
        assertEquals("JSON_ARRAY", th.path("format").asText(), "transcript/health format");

        JsonNode jw = JSON.readTree(bodies.get("GET /api/v2/job-window"));
        assertEquals("Europe/Istanbul", jw.path("zone").asText(), "job-window zone");
        assertTrue(jw.has("open") && jw.has("window_minutes"), "job-window fields");

        JsonNode bp = JSON.readTree(bodies.get("GET /api/v2/backups/policies"));
        assertEquals("default", bp.path("policy_id").asText(), "backups/policies singleton row");
        assertTrue(bp.path("schedule_enabled").asBoolean(), "backups/policies schedule_enabled");
        assertEquals("15 2 * * *", bp.path("daily_backup_cron").asText(), "backups/policies daily_backup_cron");

        JsonNode budget = JSON.readTree(bodies.get("GET /api/v2/log-findings/budget"));
        assertTrue(budget.path("evaluable").asBoolean(), "log-findings/budget evaluable over seeded coverage");
        assertTrue(budget.path("critical_total").asInt() >= 1, "log-findings/budget counts seeded CRITICAL finding");

        JsonNode pods = JSON.readTree(bodies.get("GET /api/v2/system/pods"));
        assertTrue(pods.path("disk").isObject(), "system/pods disk payload");
        assertTrue(pods.path("quarantined_by_role").isArray() && pods.path("quarantined_by_role").size() >= 1,
                "system/pods quarantined_by_role reflects seeded endpoint_admission row");

        JsonNode zm = JSON.readTree(bodies.get("GET /api/v2/zone-matrix/template"));
        assertNotNull(zm.get("can"), "zone-matrix/template capability map");

        JsonNode pp = JSON.readTree(bodies.get("GET /project-plan"));
        assertEquals("java_ui2", pp.path("product_scope").asText(), "project-plan product_scope");
    }

    private static Path findOwnSourceFile() {
        String rel = "src/test/java/com/securityexpert/nexus/ui2/integration/http/AiviewPolicyDomainsJobsOpsSweepTest.java";
        for (Path candidate : List.of(Path.of(rel), Path.of("ui2/integration-tests").resolve(rel))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not locate AiviewPolicyDomainsJobsOpsSweepTest.java from " + Path.of("").toAbsolutePath());
    }

    /** Counter self-test: IPv4 dotted/hyphenated without prefix false-positive, IPv6 spellings, case-insensitive names. */
    @Test
    void theCounterSeesSpellingsAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of(
                "192.0.2.15", ValueClass.ADDRESS,
                "2001:db8:1234:5678::abcd", ValueClass.ADDRESS,
                "dom-x0a0b0c-payments", ValueClass.DOMAIN_OR_CONTEXT);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 2),
                count("[\"2001:db8:1234:5678::abcd\",\"2001:0db8:1234:5678:0000:0000:0000:abcd\"]", raw));
        assertEquals(Map.of(ValueClass.DOMAIN_OR_CONTEXT, 1), count("domain DOM-X0A0B0C-PAYMENTS", raw));
        assertEquals(Map.of(), count("{\"domain\":\"DOM-ROMEO-01\"}", raw));
    }
}
