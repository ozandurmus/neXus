package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Counts;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Coverage;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.NewRun;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 6b-2 test fixture: the real {@link ZoneMatrixService} and {@link ZoneMatrixRunService} over the in-memory
 * version and run stores of this package, with one active version (two zones with blocks, two cells, one of them with
 * a planted formula reference) and one current, complete policy whose run stores an open VIOLATION, a VIOLATION
 * resolved inside the default report period and one resolved long before it. Public so the Reports R1 wiring test can
 * drive the stored read models. Synthetic names and documentation ranges only.
 */
public final class ZoneMatrixEvidenceFixture implements AutoCloseable {
    public static final String ZONE_A_NAME = "Z-PLANTED-CARDHOLDER";
    public static final String ZONE_B_NAME = "Z-PLANTED-BRANCH";
    public static final String RULE_NAME = "planted-rule-fw-tango-04-db";
    public static final String POLICY_NAME = "planted-policy-standard";
    public static final String SOURCE_NAME = "fw-tango-04.planted.example";
    public static final String CONTAINER_NAME = "planted-domain-juliet";
    public static final String SET_NAME = "planted-set-web";
    public static final String CONFIRMER = "planted-compliance-officer";
    public static final String PUBLISHER = "planted-publisher";
    public static final String EDITOR = "planted-editor";
    /** A regulation reference a spreadsheet would read as a formula (ZM-28). */
    public static final String FORMULA_REFERENCE = "=cmd planted-reference-12";
    public static final String CONFIRMED_REFERENCE = "BDDK-planted-article-12";
    public static final String BLOCK_A = "192.0.2.0/25";
    public static final String BLOCK_B = "198.51.100.0/24";
    public static final String POLICY_ID = "P-CUR";
    /** Rule numbers of the stored findings: open, resolved inside the default period, resolved before it. */
    public static final int OPEN_RULE = 7;
    public static final int RESOLVED_IN_PERIOD_RULE = 11;
    public static final int RESOLVED_BEFORE_PERIOD_RULE = 14;

    /** Every planted name and reference a masked report must not contain. */
    public static final List<String> PLANTED_NAMES = List.of(ZONE_A_NAME, ZONE_B_NAME, RULE_NAME, POLICY_NAME,
            SOURCE_NAME, CONTAINER_NAME, SET_NAME, CONFIRMER, PUBLISHER, EDITOR, "planted-reference-12",
            CONFIRMED_REFERENCE);
    /** Address prefixes of the blocks and witnesses; no masked report and no report without the appendix carries one. */
    public static final List<String> ADDRESS_MARKERS = List.of("192.0.2.", "198.51.100.");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WITNESS_SOURCE = "192.0.2.17/32";
    private static final String WITNESS_DESTINATION = "198.51.100.23/32";
    private static final String NAT_VIEW = "translated to 198.51.100.99";

    private final Instant now;
    private final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
    private final ZoneMatrixRunServiceTest.FakeRuns runs = new ZoneMatrixRunServiceTest.FakeRuns();
    private final List<Map<String, Object>> catalog = new ArrayList<>();
    private final ZoneMatrixService matrix;
    private final ZoneMatrixRunService runService;
    private final String zoneA = UUID.randomUUID().toString();
    private final String zoneB = UUID.randomUUID().toString();
    private final String activeId = UUID.randomUUID().toString();

    public ZoneMatrixEvidenceFixture(Instant now) {
        this.now = now;
        this.matrix = new ZoneMatrixService(versions, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return false; }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(now, ZoneOffset.UTC));
        PolicyCatalog policies = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(catalog); }
            @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.empty(); }
        };
        this.runService = new ZoneMatrixRunService(runs, versions, policies, MAPPER, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        activeVersion();
        policy();
        completeRun(List.of(
                finding("u-open", OPEN_RULE, null, null),
                finding("u-changed", RESOLVED_IN_PERIOD_RULE, now.minus(Duration.ofDays(1)), "RULE_CHANGED"),
                finding("u-before", RESOLVED_BEFORE_PERIOD_RULE, now.minus(Duration.ofDays(30)), "RULE_CHANGED")));
    }

    public ZoneMatrixService matrixService() { return matrix; }

    public ZoneMatrixRunService runService() { return runService; }

    public ZoneMatrixRepository versions() { return versions; }

    public String activeVersionId() { return activeId; }

    /** Kinds of the raw-view audit rows written so far ({@code BLOCKS_VIEW}, {@code LOOKUP}, ...). */
    public List<String> accessKinds() { return List.copyOf(versions.access); }

    @Override public void close() { runService.close(); }

    private void activeVersion() {
        var zones = List.of(
                new ZoneMatrixContent.Zone(zoneA, ZONE_A_NAME, "planted description", "DATABASE",
                        List.of(ZoneBlock.parse(BLOCK_A))),
                new ZoneMatrixContent.Zone(zoneB, ZONE_B_NAME, "planted description", "USER",
                        List.of(ZoneBlock.parse(BLOCK_B))));
        var cells = List.of(
                new ZoneMatrixContent.Cell(zoneA, zoneB, "DENY_ALL", List.of(), "CRITICAL", null, FORMULA_REFERENCE,
                        "PROPOSED", null, null),
                new ZoneMatrixContent.Cell(zoneB, zoneA, "ALLOW_LISTED", List.of("tcp/443", "set:" + SET_NAME), "HIGH",
                        null, CONFIRMED_REFERENCE, "CONFIRMED", CONFIRMER, "2026-11-20T10:00:00Z"));
        var content = new ZoneMatrixContent(zones, new ZoneMatrixContent.Matrix("DENY_ALL", "MEDIUM", null,
                List.of(new ZoneMatrixContent.ServiceSet(SET_NAME, List.of("tcp/8443"))), cells));
        versions.rows.put(activeId, new Version(activeId, 1, "ACTIVE", 2, content.canonicalJson(), content.sha256(), null,
                EDITOR, now, EDITOR, now, PUBLISHER, now.minus(Duration.ofDays(1))));
        versions.revisions.add(new String[]{activeId, EDITOR, "ZONE_ADDED", "1"});
        versions.revisions.add(new String[]{activeId, PUBLISHER, "PUBLISH", "2"});
    }

    private void policy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", POLICY_ID);
        m.put("vendor", "CP");
        m.put("name", POLICY_NAME);
        m.put("sourceId", "src-" + POLICY_ID);
        m.put("sourceName", SOURCE_NAME);
        m.put("containerId", "ctr-" + POLICY_ID);
        m.put("containerName", CONTAINER_NAME);
        m.put("collectedAt", now.minus(Duration.ofHours(1)).toString());
        m.put("ruleCount", 12);
        catalog.add(m);
    }

    private void completeRun(List<Finding> findings) {
        String runId = UUID.randomUUID().toString();
        runs.start(new NewRun(runId, activeId, 1, versions.rows.get(activeId).contentSha256(), POLICY_ID, "test",
                now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofHours(1))));
        if (!runs.complete(runId, now.minus(Duration.ofMinutes(5)), 100,
                new Counts(findings.size(), 3, 0, 0, 0, findings.size()),
                new Coverage("COMPLETE", List.of(), 0, true, "CP", now.minus(Duration.ofHours(1)), 12), findings)) {
            throw new IllegalStateException("the fixture run did not complete");
        }
    }

    private Finding finding(String uid, int number, Instant resolvedAt, String reason) {
        Instant anchor = resolvedAt == null ? now : resolvedAt;
        List<Cell> cells = List.of(new Cell(zoneB, zoneA, "ALLOW_LISTED", "HIGH", List.of("tcp/22"), NAT_VIEW,
                new Witness(WITNESS_SOURCE, WITNESS_DESTINATION, "tcp/22", 3221225489L, 3325256727L)));
        return new Finding(POLICY_ID, uid, List.of(), List.of(zoneB + "->" + zoneA), "rule-id-" + uid, number, RULE_NAME,
                "sec-1", "Pre rules", List.of("dev-" + POLICY_ID), "VIOLATION", "statement ALLOW_LISTED", "HIGH",
                List.of(), null, cells, List.of(), NAT_VIEW, anchor.minus(Duration.ofDays(2)),
                anchor.minus(Duration.ofDays(3)), anchor.minus(Duration.ofMinutes(5)), resolvedAt, reason);
    }
}
