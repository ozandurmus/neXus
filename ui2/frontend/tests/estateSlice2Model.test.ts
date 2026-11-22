import { describe, expect, it } from "vitest";
import type {
  BackupArtefact,
  BackupDeviations,
  ChangeFeedPage,
  ComplianceControlItem,
  ComplianceOverview,
  CpFailoverSummary,
  DeviceSummary,
  JobEventView,
  OverviewView,
  PolicyChangesPage,
} from "../src/auth/adminApi";
import { buildEstateModel } from "../src/estate/estateModel";
import {
  buildBackupsLensModel,
  buildComplianceLensModel,
  buildEstateChangesModel,
  buildFailoverBoardModel,
  evaluateCheckMatrixCell,
  groupChangeSetsByPerson,
  pairFeedDiffLines,
  type EstateChangeCategory,
} from "../src/estate/estateSlice2Model";

const NOW = new Date("2026-11-19T12:00:00Z");

const SYNTHETIC_DEVICES: readonly DeviceSummary[] = [
  {
    device_id: "dev-tango-01",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-01",
    model: "26000",
    software_version: "R81.20",
    ha_role: "ACTIVE",
    cluster_member_ref: "CLS-ROMEO-01",
    backup_target: true,
    inventory_collected_at: "2026-11-19T10:00:00Z",
  },
  {
    device_id: "dev-tango-02",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-02",
    model: "26000",
    software_version: "R81.20",
    ha_role: "STANDBY",
    cluster_member_ref: "CLS-ROMEO-01",
    backup_target: true,
    inventory_collected_at: "2026-11-19T10:00:00Z",
  },
  {
    device_id: "dev-sierra-01",
    vendor_hint: "palo_alto",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-SIERRA-01",
    model: "PA-5420",
    software_version: "11.1.10-h7",
    ha_role: "ACTIVE",
    cluster_member_ref: "CLS-SIERRA-02",
    backup_target: true,
    inventory_collected_at: "2026-11-19T09:00:00Z",
  },
  {
    device_id: "dev-sierra-02",
    vendor_hint: "palo_alto",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-SIERRA-02",
    model: "PA-5420",
    software_version: "11.1.10-h7",
    ha_role: "PASSIVE",
    cluster_member_ref: "CLS-SIERRA-02",
    backup_target: true,
    inventory_collected_at: "2026-11-19T09:00:00Z",
  },
  {
    device_id: "dev-juliet-06",
    vendor_hint: "cisco_asa",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-JULIET-06",
    model: "FPR-2130",
    software_version: "9.20(3)",
    ha_role: null,
    cluster_member_ref: null,
    backup_target: true,
    inventory_collected_at: "2026-11-19T08:00:00Z",
  },
  {
    device_id: "dev-bravo-02",
    vendor_hint: "infoblox",
    role: "appliance",
    enrollment_state: "ENROLLED",
    hostname: "DEV-BRAVO-02",
    model: "IB-2225",
    software_version: "9.0.5",
    ha_role: null,
    cluster_member_ref: null,
    backup_target: false,
    inventory_collected_at: null,
  },
];

const SYNTHETIC_OVERVIEW: OverviewView = {
  generated_at: "2026-11-19T11:55:00Z",
  masked: true,
  denominators: { active_devices: 6, enrolled_devices: 6, gateways: 5, clusters: 2, backup_targets: 5 },
  evidence: {
    inventory: { at: "2026-11-19T10:00:00Z", state: "OK" },
    configuration: { at: "2026-11-19T09:00:00Z", state: "OK" },
    compliance: { at: "2026-11-19T08:00:00Z", state: "OK" },
    backup: { at: "2026-11-19T02:00:00Z", state: "OK" },
    jobs: { at: "2026-11-19T11:30:00Z", state: "OK" },
    platform_facts: { at: "2026-11-19T10:00:00Z", state: "OK" },
  },
  attention: {
    failed_jobs_24h: { count: 0, terminal_24h: 60, state: "OK" },
    stale_inventory: { count: 1, of: 6, state: "OK" },
    cluster_diff: { count: 0, of: 2, state: "OK" },
    config_changed: { count: 1, of: 5, state: "OK" },
    backup_missing: { count: 1, of: 5, state: "OK" },
  },
  exceptions: {
    failed_jobs: { total: 0, rows: [] },
    config_changes: { total: 0, rows: [] },
    cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] },
  },
  inventory_age: { lt24h: 5, h24_72: 0, gt72h: 0, never: 1, of: 6, state: "OK" },
  compliance: {
    state: "OK",
    evaluated: 4,
    of_firewalls: 5,
    coverage_pct: 75,
    critical_deficiencies: 2,
    data_gaps: 4,
    frameworks: [{ name: "CIS Benchmark", pass: 14, fail: 4, unavailable: 6, total: 24, score_pct: 77 }],
  },
  platform: { devices: 6, check_point: 2, palo_alto: 2, clusters: 2, hotfix_levels: [], evidence_at: "2026-11-19T10:00:00Z" },
  policy_install: { state: "OK", of: 5, today: 4, yesterday: 0, days_2_7: 0, older: 0, unknown: 1, last_read_at: "2026-11-19T08:00:00Z" },
  nexus: { completed_24h: 60, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: null, palo_alto: null } },
  estate: {
    compliance_state: "OK",
    no_archive: 1,
    counts: { critical: 1, ageing: 0, partly_assessed: 1, not_assessed: 2, clear: 2 },
    facts: { critical: { k: 1, n: 5 }, backups: { a: 4, b: 5 }, evidence: { r: 5, d: 6, never: 1 } },
    devices: [
      { device_id: "dev-tango-01", hostname: "FW-TANGO-01", vendor: "check_point", model: "26000", condition: "critical", no_archive: false, critical_fail: 2, last_read_at: "2026-11-19T10:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-tango-02", hostname: "FW-TANGO-02", vendor: "check_point", model: "26000", condition: "clear", no_archive: false, critical_fail: 0, last_read_at: "2026-11-19T10:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-sierra-01", hostname: "FW-SIERRA-01", vendor: "palo_alto", model: "PA-5420", condition: "clear", no_archive: false, critical_fail: 0, last_read_at: "2026-11-19T09:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-sierra-02", hostname: "FW-SIERRA-02", vendor: "palo_alto", model: "PA-5420", condition: "partly_assessed", no_archive: false, critical_fail: 0, last_read_at: "2026-11-19T09:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-juliet-06", hostname: "FW-JULIET-06", vendor: "cisco_asa", model: "FPR-2130", condition: "not_assessed", no_archive: true, critical_fail: null, last_read_at: "2026-11-19T08:00:00Z", backup_target: true, last_backup_at: null },
      { device_id: "dev-bravo-02", hostname: "DEV-BRAVO-02", vendor: "infoblox", model: "IB-2225", condition: "not_assessed", condition_reason: "no_critical_control", no_archive: false, critical_fail: null, last_read_at: null, backup_target: false, last_backup_at: null },
    ],
  },
};

describe("buildFailoverBoardModel & evaluateCheckMatrixCell", () => {
  it("evaluates HA pairs, VSX chassis, and child Virtual System units separately and maps matrix cells honestly", () => {
    const failoverRows: readonly CpFailoverSummary[] = [
      {
        unitId: "cls-romeo-01",
        clusterId: "cls-romeo-01",
        cluster_member_ref: "CLS-ROMEO-01",
        vendor: "check_point",
        unitKind: "VSX_CHASSIS",
        failoverRefusal: "VSX_CHASSIS_NOT_FAILOVER_UNIT",
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "SUCCEEDED",
        lastRunAt: "2026-11-19T10:00:00Z",
        canRunReadiness: true,
        mode: "LOAD_SHARING",
        readiness: {
          status: "READY",
          observedAt: "2026-11-19T10:00:00Z",
          failedCheck: "",
          checks: [
            { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
            { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", status: "PASS", summary: "Standby", blocking: true, derived: {} },
            { checkNo: 10, title: "Traffic and ARP", member: "Member 1", result: "PASS", status: "PASS", summary: "ARP resolved", blocking: false, derived: {} },
            { checkNo: 10, title: "Traffic and ARP", member: "Member 2", result: "NOT_EVALUATED", status: "NOT_EVALUATED", summary: "Read on active only", blocking: false, derived: {} },
          ],
        },
      },
      {
        unitId: "cls-romeo-01:vs-1",
        clusterId: "cls-romeo-01",
        cluster_member_ref: "CLS-ROMEO-01",
        vendor: "check_point",
        unitKind: "VIRTUAL_SYSTEM",
        virtual_system: "VS-ROMEO-01-01",
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "STOPPED",
        lastRunAt: "2026-11-19T10:00:00Z",
        canRunReadiness: true,
        mode: "ACTIVE_STANDBY",
        readiness: {
          status: "NOT_READY",
          observedAt: "2026-11-19T10:00:00Z",
          failedCheck: "Policy parity failed",
          checks: [
            { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
            { checkNo: 3, title: "Policy parity", member: "Member 1", result: "FAIL", status: "FAIL", summary: "POL-A vs POL-B", blocking: true, derived: {} },
          ],
        },
      },
      {
        unitId: "cls-romeo-01:vs-2",
        clusterId: "cls-romeo-01",
        cluster_member_ref: "CLS-ROMEO-01",
        vendor: "check_point",
        unitKind: "VIRTUAL_SYSTEM",
        virtual_system: "VS-ROMEO-01-02",
        activeWindow: true,
        lastRunState: null,
        lastRunOutcome: null,
        lastRunAt: null,
        canRunReadiness: true,
        readiness: null, // Never evaluated -> UNKNOWN
      },
      {
        unitId: "cls-sierra-02",
        clusterId: "cls-sierra-02",
        cluster_member_ref: "CLS-SIERRA-02",
        vendor: "palo_alto",
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "SUCCEEDED",
        lastRunAt: "2026-11-17T06:00:00Z", // > 24 h -> ready_stale
        canRunReadiness: true,
        mode: "ACTIVE_PASSIVE",
        readiness: {
          status: "READY",
          observedAt: "2026-11-17T06:00:00Z",
          failedCheck: "",
          checks: [
            { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
            { checkNo: 4, title: "Configuration sync", member: "Member 1", result: "PASS", status: "PASS", summary: "In sync", blocking: true, derived: {} },
          ],
        },
      },
    ];

    const estateModel = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "ok", value: failoverRows },
      backups: { state: "ok", value: { backups: [] } },
      lifecycle: { state: "skipped" },
    });

    const board = buildFailoverBoardModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      failover: { state: "ok", value: failoverRows },
      model: estateModel,
    });

    expect(board.clusterTotals.vsUnits).toBe(2);
    expect(board.notReady.map((u) => u.unitId)).toEqual(["cls-romeo-01:vs-1"]);
    expect(board.unknown.map((u) => u.unitId)).toEqual(["cls-romeo-01:vs-2"]);
    expect(board.readyStale.map((u) => u.unitId)).toEqual(["cls-sierra-02"]);
    expect(board.readyFresh.map((u) => u.unitId)).toEqual(["cls-romeo-01"]);
    expect(board.readyFresh[0].unitKind).toBe("VSX_CHASSIS");

    // Matrix check cells:
    // 1. VS-ROMEO-01-02 has 0 checks -> every column is UNKNOWN ('unk'), never 'na' or 'ok'
    const vs2Cells = board.cellsByUnit.get("CLS-ROMEO-01::vs::cls-romeo-01:vs-2")!;
    expect(vs2Cells.get("cluster state")?.state).toBe("unk");
    expect(vs2Cells.get("policy parity")?.state).toBe("unk");

    // 2. Chassis has Check 10 PASS on Member 1 and NOT_EVALUATED on Member 2 -> 'ok'; absent Policy parity -> 'na'
    const chassisCells = board.cellsByUnit.get("CLS-ROMEO-01::base")!;
    expect(chassisCells.get("traffic and arp")?.state).toBe("ok");
    expect(chassisCells.get("policy parity")?.state).toBe("na");

    // 3. VS-ROMEO-01-01 has blocking FAIL on Policy parity -> 'act'
    const vs1Cells = board.cellsByUnit.get("CLS-ROMEO-01::vs::cls-romeo-01:vs-1")!;
    expect(vs1Cells.get("policy parity")?.state).toBe("act");

    // 4. One-sided PASS + peer UNKNOWN -> 'unk'
    const oneSided = evaluateCheckMatrixCell(
      [
        { checkNo: 1, title: "Peer check", member: "Member 1", result: "PASS", summary: "OK", blocking: true },
        { checkNo: 1, title: "Peer check", member: "Member 2", result: "UNKNOWN", summary: "Peer unreachable", blocking: true },
      ],
      "peer check",
    );
    expect(oneSided.state).toBe("unk");
  });
});

describe("buildEstateChangesModel", () => {
  it("buckets changes per day, groups by person keeping UNKNOWN separate, deduplicates rule history, and pairs before/after diffs", () => {
    const policyPage: PolicyChangesPage = {
      rows: [
        {
          revision: "rev-1",
          policyId: "pol-1",
          policyName: "POL-CORE-01",
          targets: [{ deviceId: "dev-tango-01", name: "FW-TANGO-01", context: "", syncStatus: "OK" }],
          ruleId: "r-10",
          number: 10,
          name: "WEB-ALLOW",
          changedOn: "2026-11-19T09:30:00Z",
          collectedAt: "2026-11-19T10:00:00Z",
          observedAtCollection: false,
          changedBy: "USR-BIRCH-01",
          changeType: "modified",
          changedFields: ["service"],
          changes: [{ field: "service", before: ["tcp/443"], after: ["tcp/443", "tcp/8443"] }],
        },
        {
          revision: "rev-2",
          policyId: "pol-2",
          policyName: "POL-EDGE-02",
          targets: [{ deviceId: "dev-sierra-01", name: "FW-SIERRA-01", context: "", syncStatus: "OK" }],
          ruleId: "r-22",
          number: 22,
          name: "LEGACY-DROP",
          changedOn: "2026-11-18T14:00:00Z",
          collectedAt: "2026-11-18T14:00:00Z",
          observedAtCollection: true,
          changedBy: "UNKNOWN (observed by collection)",
          changeType: "modified",
          changedFields: ["action"],
          changes: [{ field: "action", before: "accept", after: "drop" }],
        },
      ],
      summary: { total: 2, actors: 1, rules: 2, counts: { modified: 2 }, initialCaptures: 5 },
      nextCursor: "",
      rangeStart: "2026-11-12T12:00:00Z",
      rangeEnd: "2026-11-19T12:00:00Z",
    };

    const feedPage: ChangeFeedPage = {
      events: [
        // Duplicate rule event from feed — must be deduplicated when policyChanges succeeded
        {
          key: "policy:rev-1",
          kind: "USER",
          source: "policy_rule_history",
          time: "2026-11-19T09:30:00Z",
          time_basis: "UTC",
          device_time: null,
          vendor: "check_point",
          device_id: "dev-tango-01",
          device_name: "FW-TANGO-01",
          actor: "USR-BIRCH-01",
          what: "modified rule 10",
          detail: null,
          tone: "NEUTRAL",
          diff: [],
          links: { device: null, policy_id: "pol-1", rule_id: "r-10", job_id: null, job_type: null },
        },
        // Configuration change with -/+ diff lines
        {
          key: "cfg:1",
          kind: "USER",
          source: "pan_config",
          time: "2026-11-19T08:15:00Z",
          time_basis: "DEVICE_LOCAL",
          device_time: "2026/11/19 11:15:00",
          vendor: "palo_alto",
          device_id: "dev-sierra-01",
          device_name: "FW-SIERRA-01",
          actor: "USR-BIRCH-01",
          what: "edit system ntp-servers",
          detail: "client Web",
          tone: "NEUTRAL",
          diff: [
            { op: "-", text: "ntp-server 198.51.100.5" },
            { op: "+", text: "ntp-server 203.0.113.5" },
          ],
          links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: null },
        },
      ],
      next_cursor: "",
      range_start: "2026-11-12T12:00:00Z",
      range_end: "2026-11-19T12:00:00Z",
      kinds: ["USER", "DEVICE_EVENT", "NEXUS_JOB"],
      vendor: null,
      device_id: null,
      masked: true,
      ordering_note: "Ordered by collection and device time.",
      coverage: [{ source: "cp_cpwd_elg", kind: "DEVICE_EVENT", state: "UNAVAILABLE", note: "log reader failed", events: 0 }],
    };

    const m = buildEstateChangesModel({
      now: NOW,
      windowDays: 7,
      policyChanges: { state: "ok", value: policyPage },
      changeFeed: { state: "ok", value: feedPage },
      activeCategories: new Set<EstateChangeCategory>(["rule", "config", "event"]),
      model: null,
    });

    expect(m.days).toHaveLength(7);
    expect(m.totals.rules).toBe(2);
    expect(m.totals.config).toBe(1);
    expect(m.totals.distinctNamedPeople).toBe(1);
    expect(m.totals.unknownActorChanges).toBe(1);
    expect(m.unavailableSources).toHaveLength(1);

    // Person lanes: USR-BIRCH-01 (2 changes), then UNKNOWN (1 change)
    expect(m.personLanes.map((p) => [p.actorKey, p.totalChanges, p.isUnknown])).toEqual([
      ["USR-BIRCH-01", 2, false],
      ["UNKNOWN", 1, true],
    ]);

    const grouped = groupChangeSetsByPerson(m.changeSets);
    expect(grouped).toHaveLength(2);
    expect(grouped[0].actorKey).toBe("USR-BIRCH-01");
    expect(grouped[1].actorKey).toBe("UNKNOWN");

    // Diff pairing check
    const paired = pairFeedDiffLines("FW-SIERRA-01", [
      { op: "-", text: "old-a" },
      { op: "+", text: "new-a" },
    ]);
    expect(paired).toEqual([{ where: "FW-SIERRA-01", field: "line 1", before: "old-a", after: "new-a" }]);
  });
});

describe("buildComplianceLensModel & buildBackupsLensModel", () => {
  it("computes honest compliance bar, family-by-vendor heatmap (0 evidenced -> unk), and remediation ranks", () => {
    const compOv: ComplianceOverview = {
      total_firewalls: 5,
      evaluated_firewalls: 4,
      assured_compliance_pct: 50,
      evidence_coverage_pct: 75,
      observed_compliance_pct: 66.7,
      critical_deficiencies: 2,
      data_gaps: 5,
      frameworks: [{ framework: "CIS Benchmark", score_pct: 66.7, total_controls: 20, pass_count: 10, fail_count: 5, data_unavailable_count: 5 }],
    };

    const controls: readonly ComplianceControlItem[] = [
      {
        control_id: "CIS-5.2.1",
        title: "SSH protocol version 2 only",
        description: null,
        family: "IA",
        severity: "CRITICAL",
        category: "Authentication",
        frameworks: [{ framework: "CIS Benchmark", reference: "5.2.1" }],
        status: "FAIL",
        compliance_pct: 50,
        target_device_count: 4,
        pass_count: 1,
        fail_count: 3,
        data_unavailable_count: 0,
        affected_devices: ["FW-TANGO-01", "FW-TANGO-02", "FW-SIERRA-01"],
        vendors: ["check_point"],
      },
      {
        control_id: "AU-9.1",
        title: "Remote syslog over TLS",
        description: null,
        family: "AU",
        severity: "HIGH",
        category: "Audit",
        frameworks: [{ framework: "CIS Benchmark", reference: "4.1" }],
        status: "DATA_UNAVAILABLE",
        compliance_pct: 0,
        target_device_count: 5,
        pass_count: 0,
        fail_count: 0,
        data_unavailable_count: 5,
        missing_reason: "Command not in collection scope yet",
        affected_devices: [],
        vendors: ["cisco_asa"],
      },
    ];

    const cm = buildComplianceLensModel({
      complianceOverview: compOv,
      controls,
      selectedFramework: "CIS Benchmark",
      familyFilter: "",
      complianceAt: null,
      model: null,
    });

    expect(cm.selected.pass).toBe(10);
    expect(cm.selected.fail).toBe(5);
    expect(cm.selected.unavailable).toBe(5);
    expect(cm.selected.unavailablePct).toBe(25);
    expect(cm.dataGapReasons[0]).toEqual({
      reason: "Command not in collection scope yet",
      checksCount: 5,
      controlCount: 1,
    });

    // AU family on cisco_asa has 0 evidenced checks and 5 unavailable -> badgeState MUST be 'unk', never 'ok'
    const auRow = cm.familyRows.find((r) => r.familyCode === "AU")!;
    expect(auRow.cells.get("cisco_asa")?.badgeState).toBe("unk");
  });

  it("builds 14-night backup strips, flags size deviations, and marks OUTCOME_UNKNOWN jobs as hatched unknown", () => {
    const backups: readonly BackupArtefact[] = [
      {
        artefact_id: "art-d1-prev",
        device_id: "dev-tango-01",
        collected_at: "2026-11-18T02:00:00Z",
        size_bytes: 100 * 1024 * 1024,
        digest_prefix: "11112222",
        validation_level: "V3",
        deviation_state: "unchanged",
      },
      {
        artefact_id: "art-d1-curr",
        device_id: "dev-tango-01",
        collected_at: "2026-11-19T02:00:00Z",
        size_bytes: 520 * 1024 * 1024,
        digest_prefix: "33334444",
        validation_level: "V3",
        deviation_state: "changed",
      },
    ];

    const deviations: BackupDeviations = {
      active_major_deviations: [],
      total_deviations_checked: 4,
      size_anomalies: [
        {
          device_id: "dev-tango-01",
          hostname: "FW-TANGO-01",
          deviation_id: "dev-anom-1",
          artefact_id: "art-d1-curr",
          last_good_artefact_id: "art-d1-prev",
          direction: "growth",
          plaintext_bytes: 520 * 1024 * 1024,
          median_bytes: 100 * 1024 * 1024,
          ratio: 5.2,
          summary: "Archive grew 5.2x against median",
          detected_at: "2026-11-19T02:05:00Z",
          open_copies: 1,
        },
      ],
    };

    const estateModel = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "ok", value: [] },
      backups: { state: "ok", value: { backups, deviations } },
      lifecycle: { state: "skipped" },
    });

    const jobs: readonly JobEventView[] = [
      {
        job_id: "job-unk-1",
        job_type: "backup",
        state: "OUTCOME_UNKNOWN",
        target_device_id: "dev-sierra-01",
        submitted_at: "2026-11-19T02:10:00Z",
        finished_at: "2026-11-19T02:15:00Z",
        terminal_reason: "worker restarted mid-transfer",
        has_transcript: false,
      } as unknown as JobEventView,
    ];

    const bm = buildBackupsLensModel({
      now: NOW,
      estateUnits: estateModel.units,
      backupsRead: { state: "ok", value: { backups, deviations } },
      policy: {
        state: "ok",
        value: {
          policy_id: "default",
          schedule_enabled: true,
          daily_backup_cron: "0 2 * * *",
          backup_retention_days: 7,
          snapshot_retention_depth: 4,
          last_scheduled_run_at: "2026-11-19T02:00:00Z",
        },
      },
      quotas: { state: "failed" }, // unmeasured store -> storeUsage.known === false (UNKNOWN)
      backupJobs: { state: "ok", value: jobs },
      backupEvidenceAt: "2026-11-19T02:00:00Z",
    });

    expect(bm.storeUsage.known).toBe(false);
    expect(bm.retentionDays).toBe(7);
    expect(bm.nonTargetCount).toBe(1); // DEV-BRAVO-02

    // FW-TANGO-01 has open size anomaly -> watch + 14 nights ending in 'anomaly'
    const tangoRow = bm.rows.find((r) => r.unit.name === "FW-TANGO-01")!;
    expect(tangoRow.state).toBe("watch");
    expect(tangoRow.hasSizeDeviation).toBe(true);
    expect(tangoRow.sizeDeviationLabel).toBe("Grew 5.2x");
    expect(tangoRow.nights).toHaveLength(14);
    expect(tangoRow.nights[13].state).toBe("anomaly");

    // FW-SIERRA-01 had an OUTCOME_UNKNOWN backup job on 2026-11-19 with no archive -> night[13] is 'unknown'
    const sierraRow = bm.rows.find((r) => r.unit.name === "FW-SIERRA-01")!;
    expect(sierraRow.nights[13].state).toBe("unknown");
  });

  it("a row whose only signal is a night OUTCOME_UNKNOWN is UNKNOWN, never Fine", () => {
    // FW-SIERRA-01 (dev-sierra-01): archive on 2026-11-19 only; OUTCOME_UNKNOWN attempt on 2026-11-18,
    // submitted before the newest archive, so the newest-attempt rule does not fire.
    const backups: readonly BackupArtefact[] = [
      {
        artefact_id: "art-s1-curr",
        device_id: "dev-sierra-01",
        collected_at: "2026-11-19T02:00:00Z",
        size_bytes: 100 * 1024 * 1024,
        digest_prefix: "55556666",
        validation_level: "V3",
        deviation_state: "unchanged",
      },
    ];
    const deviations: BackupDeviations = {
      active_major_deviations: [],
      total_deviations_checked: 0,
      size_anomalies: [],
    };
    const estateModel = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "ok", value: [] },
      backups: { state: "ok", value: { backups, deviations } },
      lifecycle: { state: "skipped" },
    });
    const jobs: readonly JobEventView[] = [
      {
        job_id: "job-unk-night",
        job_type: "backup",
        state: "OUTCOME_UNKNOWN",
        target_device_id: "dev-sierra-01",
        submitted_at: "2026-11-18T02:10:00Z",
        finished_at: "2026-11-18T02:15:00Z",
        terminal_reason: "worker restarted mid-transfer",
        has_transcript: false,
      } as unknown as JobEventView,
    ];
    const bm = buildBackupsLensModel({
      now: NOW,
      estateUnits: estateModel.units,
      backupsRead: { state: "ok", value: { backups, deviations } },
      policy: { state: "failed" },
      quotas: { state: "failed" },
      backupJobs: { state: "ok", value: jobs },
      backupEvidenceAt: "2026-11-19T02:00:00Z",
    });
    const row = bm.rows.find((r) => r.unit.name === "FW-SIERRA-01")!;
    expect(row.nights.filter((n) => n.state === "unknown")).toHaveLength(1);
    expect(row.badgeWord).toBe("UNKNOWN");
    expect(row.state).toBe("unk");
    expect(row.why).toMatch(/1 night with attempt outcome UNKNOWN/);
  });
});
