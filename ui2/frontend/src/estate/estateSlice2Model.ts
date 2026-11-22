import type {
  BackupArtefact,
  BackupDeviations,
  BackupPolicy,
  BackupStoreQuotas,
  ChangeFeedCoverage,
  ChangeFeedEvent,
  ChangeFeedPage,
  ComplianceControlItem,
  ComplianceOverview,
  CpFailoverSummary,
  DeviceSummary,
  JobEventView,
  PolicyChangeRow,
  PolicyChangesPage,
  ReadinessCheck,
} from "../auth/adminApi";
import { PRODUCT_NAME } from "../brand/product";
import { formatBytes } from "../shell/bytes";
import { isPastFresh } from "../shell/freshness";
import { clusterModeWord, jobTypeLabel, vendorLabel } from "../shell/presenters";
import { vendorDisplayName } from "../shell/States";
import { DISPLAY_TZ } from "../shell/time";
import { actorUnknown, BACKUP_STALE_DAYS, type SourceRead } from "../screens/overview/attentionRules";
import { sizeAnomalyWord } from "../screens/BackupDeviationCard";
import { backupDisplayWord } from "../screens/backupWords";
import { familyOf } from "../screens/ComplianceScreen";
import { orderMembers } from "../screens/DeviceShared";
import { groupClusters, haReadinessItems, primaryReasonWord } from "../screens/HaReadinessList";
import { withFirewallNames } from "../screens/policyFirewalls";
import { primaryReportsPeer, primaryRolesConfirmed } from "../screens/ReadinessChecksTable";
import {
  newestIso,
  parseTimeMs,
  type BadgeState,
  type EstateCluster,
  type EstateModel,
  type EstateUnit,
  type TileState,
} from "./estateModel";

const DAY_MS = 86_400_000;

const DAY_KEY_FMT = new Intl.DateTimeFormat("en-CA", {
  timeZone: DISPLAY_TZ,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

const SHORT_DAY_FMT = new Intl.DateTimeFormat("en-GB", {
  timeZone: DISPLAY_TZ,
  day: "numeric",
  month: "short",
});

export function dayKeyInDisplayTz(isoOrDate: string | Date): string | null {
  const d = typeof isoOrDate === "string" ? new Date(isoOrDate) : isoOrDate;
  if (Number.isNaN(d.getTime())) return null;
  return DAY_KEY_FMT.format(d);
}

export function buildCalendarDays(now: Date, count: number): ReadonlyArray<{
  readonly key: string;
  readonly label: string;
  readonly shortDay: string;
}> {
  const seen = new Set<string>();
  const out: Array<{ key: string; label: string; shortDay: string }> = [];
  for (let offset = count - 1; offset >= 0; offset--) {
    const dt = new Date(now.getTime() - offset * DAY_MS);
    const key = DAY_KEY_FMT.format(dt);
    if (seen.has(key)) continue;
    seen.add(key);
    const label = SHORT_DAY_FMT.format(dt);
    out.push({ key, label, shortDay: label.split(" ")[0] ?? label });
  }
  return out;
}

// ============================================================================
// 1. FAILOVER BOARD MODEL (HA pairs + VSX chassis + Virtual Systems + Matrix)
// ============================================================================

export type FailoverBoardColumn = "not_ready" | "unknown" | "ready_stale" | "ready_fresh";

export interface FailoverMemberRef {
  readonly deviceId: string | null;
  readonly name: string;
  readonly role: string;
  readonly inEstate: boolean;
}

export interface FailoverBoardUnit {
  readonly key: string;
  readonly unitId: string;
  readonly clusterRef: string;
  readonly clusterTitle: string;
  readonly label: string;
  readonly unitKind: "CLUSTER" | "VSX_CHASSIS" | "VIRTUAL_SYSTEM";
  readonly virtualSystemName: string | null;
  readonly vendor: string;
  readonly vendorName: string;
  readonly mode: string | null;
  readonly failoverRefusal: string | null;
  readonly status: "READY" | "NOT_READY" | "UNKNOWN";
  readonly boardColumn: FailoverBoardColumn;
  readonly badgeState: BadgeState;
  readonly badgeWord: string;
  readonly reason: string;
  readonly observedAt: string | null;
  readonly stale: boolean;
  readonly activeMembers: readonly FailoverMemberRef[];
  readonly standbyMembers: readonly FailoverMemberRef[];
  readonly allMembers: readonly FailoverMemberRef[];
  readonly estateCluster: EstateCluster | null;
  readonly checks: readonly ReadinessCheck[];
}

export interface UnsupportedFailoverCluster {
  readonly clusterRef: string;
  readonly title: string;
  readonly vendor: string;
  readonly vendorName: string;
  readonly members: readonly FailoverMemberRef[];
  readonly note: string;
}

export interface FailoverCheckColumn {
  readonly key: string;
  readonly checkNo: number;
  readonly title: string;
}

export interface FailoverMatrixCell {
  readonly state: Extract<TileState, "ok" | "act" | "watch" | "unk" | "na">;
  readonly word: string;
  readonly summary: string;
}

export interface FailoverBoardModel {
  readonly sourceState: SourceRead<unknown>["state"];
  readonly units: readonly FailoverBoardUnit[];
  readonly notReady: readonly FailoverBoardUnit[];
  readonly unknown: readonly FailoverBoardUnit[];
  readonly readyStale: readonly FailoverBoardUnit[];
  readonly readyFresh: readonly FailoverBoardUnit[];
  readonly unsupported: readonly UnsupportedFailoverCluster[];
  readonly checkColumns: readonly FailoverCheckColumn[];
  readonly cellsByUnit: ReadonlyMap<string, ReadonlyMap<string, FailoverMatrixCell>>;
  readonly clusterTotals: {
    readonly enrolled: number;
    readonly supported: number;
    readonly readyFresh: number;
    readonly readyStale: number;
    readonly notReady: number;
    readonly unknown: number;
    readonly unsupported: number;
    readonly vsUnits: number;
  };
  readonly newestEvaluatedAt: string | null;
}

function normalizeCheckKey(title: string, checkNo: number): string {
  const cleaned = title.trim().toLowerCase().replace(/\s+/g, " ");
  return cleaned || `check-${checkNo}`;
}

export function evaluateCheckMatrixCell(
  unitChecks: readonly ReadinessCheck[],
  columnKey: string,
): FailoverMatrixCell {
  if (unitChecks.length === 0) {
    return {
      state: "unk",
      word: "UNKNOWN",
      summary: "No pre-check observations recorded for this unit",
    };
  }
  const matching = unitChecks.filter((c) => normalizeCheckKey(c.title, c.checkNo) === columnKey);
  if (matching.length === 0) {
    return {
      state: "na",
      word: "Not applicable",
      summary: "Not part of this unit's pre-check set",
    };
  }

  const evaluated = matching.filter((c) => c.result !== "NOT_EVALUATED");
  const pool = evaluated.length > 0 ? evaluated : matching;
  const detailText = matching
    .map((c) => `${c.member}: ${c.summary || c.result}`)
    .join(" · ");

  if (evaluated.length === 0) {
    return {
      state: "unk",
      word: "UNKNOWN",
      summary: detailText || "Not evaluated",
    };
  }

  const hasBlockingFail = pool.some((c) => c.result === "FAIL" && c.blocking);
  if (hasBlockingFail) {
    return {
      state: "act",
      word: "Blocks",
      summary: detailText,
    };
  }

  const hasNonBlockingFail = pool.some((c) => c.result === "FAIL" && !c.blocking);
  const hasWarn = pool.some((c) => c.result === "WARN");
  const hasUnknown = pool.some((c) => c.result === "UNKNOWN");

  if (hasUnknown) {
    return {
      state: "unk",
      word: "UNKNOWN",
      summary: detailText,
    };
  }

  if (hasNonBlockingFail || hasWarn) {
    return {
      state: "watch",
      word: "Review",
      summary: detailText,
    };
  }

  return {
    state: "ok",
    word: "Pass",
    summary: detailText,
  };
}

export function buildFailoverBoardModel(inputs: {
  readonly now: Date;
  readonly devices: SourceRead<readonly DeviceSummary[]>;
  readonly failover: SourceRead<readonly CpFailoverSummary[]>;
  readonly model: EstateModel | null;
}): FailoverBoardModel {
  const { now, devices, failover, model } = inputs;
  const activeDevices =
    devices.state === "ok"
      ? devices.value.filter((d) => d.enrollment_state !== "DRAFT")
      : [];

  const rowsByRef: Record<string, CpFailoverSummary[]> = {};
  if (failover.state === "ok") {
    for (const row of failover.value) {
      (rowsByRef[row.cluster_member_ref] ??= []).push(row);
    }
  }

  const baseClusters = groupClusters(activeDevices);
  const seenRefs = new Set(baseClusters.map((c) => c.ref));

  // Single-address FortiGate pairs (`fgt-single|<deviceId>`) as in OperationsScreen.tsx
  const fgtSingleClusters = activeDevices
    .filter(
      (d) =>
        d.vendor_hint === "fortinet" &&
        !d.cluster_member_ref &&
        d.ha_peer_unconfirmed === true &&
        (rowsByRef[`fgt-single|${d.device_id}`]?.length ?? 0) > 0,
    )
    .map((d) => ({
      ref: `fgt-single|${d.device_id}`,
      title: d.hostname ?? `fgt-single|${d.device_id}`,
      members: [d],
    }));
  for (const fc of fgtSingleClusters) seenRefs.add(fc.ref);

  // Any summary cluster ref not yet in baseClusters (defensive so no summary record is ever dropped)
  const summaryOnlyClusters = Object.entries(rowsByRef)
    .filter(([ref]) => !seenRefs.has(ref))
    .map(([ref, summaryRows]) => {
      const first = summaryRows[0];
      const synthMembers: DeviceSummary[] = (first?.members ?? []).map((m) => ({
        device_id: m.device_id,
        vendor_hint: first.vendor ?? "check_point",
        enrollment_state: "ENROLLED",
        hostname: m.hostname,
        model: null,
        software_version: null,
        ha_role: m.ha_role,
        cluster_member_ref: ref,
      }));
      return { ref, title: ref, members: synthMembers };
    });

  const allClusters = [...baseClusters, ...fgtSingleClusters, ...summaryOnlyClusters].sort((a, b) =>
    a.title.localeCompare(b.title),
  );

  const items = haReadinessItems(allClusters, rowsByRef);
  const estateById = model?.byId ?? new Map<string, EstateUnit>();
  const estateClusters = model?.clustersByRef ?? new Map<string, EstateCluster>();

  const units: FailoverBoardUnit[] = [];
  const unsupported: UnsupportedFailoverCluster[] = [];

  let clusterReadyFresh = 0;
  let clusterReadyStale = 0;
  let clusterNotReady = 0;
  let clusterUnknown = 0;
  let vsUnitsCount = 0;

  const buildMemberRefs = (
    vendor: string | undefined,
    rawMembers: ReadonlyArray<{ device_id: string; hostname: string | null; ha_role: string | null }>,
    checks: readonly ReadinessCheck[],
  ): {
    readonly activeMembers: readonly FailoverMemberRef[];
    readonly standbyMembers: readonly FailoverMemberRef[];
    readonly allMembers: readonly FailoverMemberRef[];
  } => {
    const fgtPeer = primaryReportsPeer(vendor, [...rawMembers]);
    if (fgtPeer && rawMembers.length === 1) {
      const m0 = rawMembers[0];
      const confirmed = primaryRolesConfirmed([...checks]);
      const primary: FailoverMemberRef = {
        deviceId: m0.device_id,
        name: m0.hostname ?? m0.device_id,
        role: confirmed ? "active" : "role unknown",
        inEstate: estateById.has(m0.device_id),
      };
      const secondary: FailoverMemberRef = {
        deviceId: null,
        name: confirmed ? "Secondary (as primary reports)" : "Secondary (role UNKNOWN)",
        role: confirmed ? "standby" : "unknown",
        inEstate: false,
      };
      return {
        activeMembers: [primary],
        standbyMembers: [secondary],
        allMembers: [primary, secondary],
      };
    }

    const ordered = [...rawMembers].sort(
      (a, b) => Number((a.ha_role ?? "").toLowerCase() !== "active") - Number((b.ha_role ?? "").toLowerCase() !== "active"),
    );
    const refs: FailoverMemberRef[] = ordered.map((m) => ({
      deviceId: m.device_id,
      name: m.hostname ?? estateById.get(m.device_id)?.name ?? m.device_id.slice(0, 8),
      role: (m.ha_role ?? "unknown").toLowerCase(),
      inEstate: estateById.has(m.device_id),
    }));
    return {
      activeMembers: refs.filter((r) => r.role === "active" || r.role === "primary"),
      standbyMembers: refs.filter((r) => r.role === "standby" || r.role === "passive" || r.role === "secondary"),
      allMembers: refs,
    };
  };

  for (const item of items) {
    const { cluster, vendor } = item;
    const orderedClusterDevices = orderMembers(cluster.members);
    if (failover.state === "ok" && item.status === "UNSUPPORTED") {
      const mRefs = buildMemberRefs(vendor, orderedClusterDevices, []);
      const listedOnly = vendor === "cisco_asa" || item.units.some((u) => u.readinessAvailable === false);
      unsupported.push({
        clusterRef: cluster.ref,
        title: cluster.title,
        vendor: vendor ?? "unknown",
        vendorName: vendorDisplayName(vendor),
        members: mRefs.allMembers,
        note: listedOnly ? "Readiness switch not active for this pair" : "Readiness pre-checks not supported for this vendor",
      });
      continue;
    }

    const childRows = failover.state === "ok" ? item.units.filter((u) => u.unitId !== u.clusterId) : [];
    const baseRow = failover.state === "ok" ? item.base : undefined;
    const baseKind: FailoverBoardUnit["unitKind"] =
      baseRow?.unitKind === "VSX_CHASSIS" || childRows.length > 0 ? "VSX_CHASSIS" : "CLUSTER";

    const classifyUnit = (
      rawStatus: "READY" | "NOT_READY" | "UNKNOWN",
      obsAt: string | null,
    ): {
      status: "READY" | "NOT_READY" | "UNKNOWN";
      col: FailoverBoardColumn;
      badgeState: BadgeState;
      badgeWord: string;
      stale: boolean;
    } => {
      if (failover.state !== "ok") {
        return {
          status: "UNKNOWN",
          col: "unknown",
          badgeState: "unk",
          badgeWord: failover.state === "restricted" ? "Restricted" : "UNKNOWN",
          stale: false,
        };
      }
      if (rawStatus === "NOT_READY") {
        return {
          status: "NOT_READY",
          col: "not_ready",
          badgeState: "act",
          badgeWord: "Not ready",
          stale: Boolean(obsAt && isPastFresh(obsAt, now)),
        };
      }
      if (rawStatus === "UNKNOWN" || !obsAt) {
        return {
          status: "UNKNOWN",
          col: "unknown",
          badgeState: "unk",
          badgeWord: "UNKNOWN",
          stale: false,
        };
      }
      const stale = isPastFresh(obsAt, now);
      if (stale) {
        return {
          status: "READY",
          col: "ready_stale",
          badgeState: "watch",
          badgeWord: "Ready (> 24 h)",
          stale: true,
        };
      }
      return {
        status: "READY",
        col: "ready_fresh",
        badgeState: "ok",
        badgeWord: "Ready",
        stale: false,
      };
    };

    const baseObsAt = baseRow?.readiness?.observedAt ?? baseRow?.readinessObservedAt ?? null;
    const baseRawStatus: "READY" | "NOT_READY" | "UNKNOWN" =
      item.status === "READY" || item.status === "NOT_READY" ? item.status : "UNKNOWN";
    const baseClass = classifyUnit(baseRawStatus, baseObsAt);

    if (baseClass.col === "not_ready") clusterNotReady++;
    else if (baseClass.col === "unknown") clusterUnknown++;
    else if (baseClass.col === "ready_stale") clusterReadyStale++;
    else clusterReadyFresh++;

    const baseChecks = baseRow?.readiness?.checks ?? [];
    const baseMembers = baseRow?.members?.length ? baseRow.members : orderedClusterDevices;
    const baseMemberRefs = buildMemberRefs(vendor, baseMembers, baseChecks);
    const baseReason =
      failover.state === "restricted"
        ? "Readiness summary restricted for this account"
        : failover.state !== "ok"
          ? "Readiness summary unavailable"
          : !baseObsAt && baseClass.status === "UNKNOWN"
            ? primaryReasonWord(baseRow)
            : primaryReasonWord(baseRow);

    units.push({
      key: `${cluster.ref}::base`,
      unitId: baseRow?.unitId ?? cluster.ref,
      clusterRef: cluster.ref,
      clusterTitle: cluster.title,
      label: baseKind === "VSX_CHASSIS" ? `${cluster.title} (VSX chassis)` : cluster.title,
      unitKind: baseKind,
      virtualSystemName: null,
      vendor: vendor ?? "unknown",
      vendorName: vendorDisplayName(vendor),
      mode: baseRow?.mode && baseRow.mode !== "UNKNOWN" ? clusterModeWord(baseRow.mode) : null,
      failoverRefusal: baseRow?.failoverRefusal ?? null,
      status: baseClass.status,
      boardColumn: baseClass.col,
      badgeState: baseClass.badgeState,
      badgeWord: baseClass.badgeWord,
      reason: baseReason,
      observedAt: baseObsAt,
      stale: baseClass.stale,
      activeMembers: baseMemberRefs.activeMembers,
      standbyMembers: baseMemberRefs.standbyMembers,
      allMembers: baseMemberRefs.allMembers,
      estateCluster: estateClusters.get(cluster.ref) ?? null,
      checks: baseChecks,
    });

    for (const vsRow of childRows) {
      vsUnitsCount++;
      const vsObsAt = vsRow.readiness?.observedAt ?? vsRow.readinessObservedAt ?? null;
      const vsRawStatus: "READY" | "NOT_READY" | "UNKNOWN" = vsRow.readiness?.status ?? "UNKNOWN";
      const vsClass = classifyUnit(vsRawStatus, vsObsAt);
      const vsChecks = vsRow.readiness?.checks ?? [];
      const vsMembers = vsRow.members?.length ? vsRow.members : orderedClusterDevices;
      const vsMemberRefs = buildMemberRefs(vsRow.vendor ?? vendor, vsMembers, vsChecks);
      const vsName = vsRow.virtual_system?.trim() || vsRow.unitId;
      units.push({
        key: `${cluster.ref}::vs::${vsRow.unitId}`,
        unitId: vsRow.unitId,
        clusterRef: cluster.ref,
        clusterTitle: cluster.title,
        label: `${cluster.title} · VS ${vsName}`,
        unitKind: "VIRTUAL_SYSTEM",
        virtualSystemName: vsName,
        vendor: vsRow.vendor ?? vendor ?? "unknown",
        vendorName: vendorDisplayName(vsRow.vendor ?? vendor),
        mode: vsRow.mode && vsRow.mode !== "UNKNOWN" ? clusterModeWord(vsRow.mode) : null,
        failoverRefusal: vsRow.failoverRefusal ?? null,
        status: vsClass.status,
        boardColumn: vsClass.col,
        badgeState: vsClass.badgeState,
        badgeWord: vsClass.badgeWord,
        reason: primaryReasonWord(vsRow),
        observedAt: vsObsAt,
        stale: vsClass.stale,
        activeMembers: vsMemberRefs.activeMembers,
        standbyMembers: vsMemberRefs.standbyMembers,
        allMembers: vsMemberRefs.allMembers,
        estateCluster: estateClusters.get(cluster.ref) ?? null,
        checks: vsChecks,
      });
    }
  }

  const colRank: Record<FailoverBoardColumn, number> = {
    not_ready: 0,
    unknown: 1,
    ready_stale: 2,
    ready_fresh: 3,
  };
  const sortedUnits = [...units].sort(
    (a, b) =>
      colRank[a.boardColumn] - colRank[b.boardColumn] ||
      a.clusterTitle.localeCompare(b.clusterTitle) ||
      (a.unitKind === "VIRTUAL_SYSTEM" ? 1 : 0) - (b.unitKind === "VIRTUAL_SYSTEM" ? 1 : 0) ||
      a.label.localeCompare(b.label),
  );

  // Build ordered pre-check columns across all units
  const columnMap = new Map<string, { checkNo: number; title: string }>();
  for (const u of sortedUnits) {
    for (const chk of u.checks) {
      const k = normalizeCheckKey(chk.title, chk.checkNo);
      const existing = columnMap.get(k);
      if (!existing || chk.checkNo < existing.checkNo) {
        columnMap.set(k, { checkNo: chk.checkNo, title: chk.title.trim() || `Check ${chk.checkNo}` });
      }
    }
  }
  const checkColumns: FailoverCheckColumn[] = [...columnMap.entries()]
    .map(([key, v]) => ({ key, checkNo: v.checkNo, title: v.title }))
    .sort((a, b) => a.checkNo - b.checkNo || a.title.localeCompare(b.title));

  const effectiveColumns: FailoverCheckColumn[] =
    checkColumns.length > 0
      ? checkColumns
      : [{ key: "__readiness__", checkNo: 1, title: "Pre-checks" }];

  const cellsByUnit = new Map<string, ReadonlyMap<string, FailoverMatrixCell>>();
  for (const u of sortedUnits) {
    const rowCells = new Map<string, FailoverMatrixCell>();
    for (const col of effectiveColumns) {
      rowCells.set(col.key, evaluateCheckMatrixCell(u.checks, col.key));
    }
    cellsByUnit.set(u.key, rowCells);
  }

  return {
    sourceState: failover.state,
    units: sortedUnits,
    notReady: sortedUnits.filter((u) => u.boardColumn === "not_ready"),
    unknown: sortedUnits.filter((u) => u.boardColumn === "unknown"),
    readyStale: sortedUnits.filter((u) => u.boardColumn === "ready_stale"),
    readyFresh: sortedUnits.filter((u) => u.boardColumn === "ready_fresh"),
    unsupported,
    checkColumns: effectiveColumns,
    cellsByUnit,
    clusterTotals: {
      enrolled: allClusters.length,
      supported: allClusters.length - unsupported.length,
      readyFresh: clusterReadyFresh,
      readyStale: clusterReadyStale,
      notReady: clusterNotReady,
      unknown: clusterUnknown,
      unsupported: unsupported.length,
      vsUnits: vsUnitsCount,
    },
    newestEvaluatedAt: newestIso(sortedUnits.map((u) => u.observedAt)),
  };
}

// ============================================================================
// 2. CHANGES MODEL (Per day, grouped by person, before/after diffs)
// ============================================================================

export type EstateChangeCategory = "rule" | "config" | "event" | "job";

export interface EstateDiffRow {
  readonly where: string;
  readonly field: string;
  readonly before: string | null;
  readonly after: string | null;
  readonly policyId?: string | null;
  readonly ruleId?: string | null;
}

export interface EstateChangeSet {
  readonly id: string;
  readonly category: EstateChangeCategory;
  readonly count: number;
  readonly time: string;
  readonly timeBasis: "UTC" | "DEVICE_LOCAL";
  readonly deviceTimeText: string | null;
  readonly observedAtCollection: boolean;
  readonly initialCapture: boolean;
  readonly actorKey: string;
  readonly actorLabel: string;
  readonly actorIsUnknown: boolean;
  readonly actorIsAmbiguous: boolean;
  readonly actorIsSystem: boolean;
  readonly headline: string;
  readonly meta: string;
  readonly unitIds: readonly string[];
  readonly unitNames: readonly string[];
  readonly diffs: readonly EstateDiffRow[];
  readonly source: string;
}

export interface DayVolumeBucket {
  readonly dayKey: string;
  readonly label: string;
  readonly shortDay: string;
  readonly rules: number;
  readonly config: number;
  readonly events: number;
  readonly jobs: number;
  readonly total: number;
}

export interface PersonChangeLane {
  readonly actorKey: string;
  readonly actorLabel: string;
  readonly isUnknown: boolean;
  readonly isAmbiguous: boolean;
  readonly isSystem: boolean;
  readonly totalChanges: number;
  readonly setCount: number;
  readonly perDay: readonly number[];
  readonly newestAt: string | null;
}

export interface EstateChangesModel {
  readonly days: readonly DayVolumeBucket[];
  readonly personLanes: readonly PersonChangeLane[];
  readonly changeSets: readonly EstateChangeSet[];
  readonly totals: {
    readonly rules: number;
    readonly config: number;
    readonly events: number;
    readonly jobs: number;
    readonly unknownActorChanges: number;
    readonly distinctNamedPeople: number;
    readonly initialCaptures: number;
  };
  readonly coverage: readonly ChangeFeedCoverage[];
  readonly unavailableSources: readonly ChangeFeedCoverage[];
  readonly orderingNote: string | null;
  readonly newestAt: string | null;
}

export function formatDiffValue(val: unknown): string | null {
  if (val === undefined || val === null) return null;
  if (typeof val === "string") return val.trim() === "" ? "(empty)" : val;
  if (typeof val === "number" || typeof val === "boolean") return String(val);
  if (Array.isArray(val)) {
    if (val.length === 0) return "(none)";
    return val.map((item) => (typeof item === "string" ? item : JSON.stringify(item))).join(", ");
  }
  return JSON.stringify(val);
}

export function pairFeedDiffLines(
  whereLabel: string,
  diffLines: ChangeFeedEvent["diff"],
): EstateDiffRow[] {
  if (!diffLines || diffLines.length === 0) return [];
  const rows: EstateDiffRow[] = [];
  let i = 0;
  while (i < diffLines.length) {
    const cur = diffLines[i];
    if (cur.op === "-") {
      const removedBatch: string[] = [];
      while (i < diffLines.length && diffLines[i].op === "-") {
        removedBatch.push(diffLines[i].text);
        i++;
      }
      const addedBatch: string[] = [];
      while (i < diffLines.length && diffLines[i].op === "+") {
        addedBatch.push(diffLines[i].text);
        i++;
      }
      const len = Math.max(removedBatch.length, addedBatch.length);
      for (let k = 0; k < len; k++) {
        rows.push({
          where: whereLabel,
          field: `line ${rows.length + 1}`,
          before: removedBatch[k] ?? null,
          after: addedBatch[k] ?? null,
        });
      }
    } else if (cur.op === "+") {
      rows.push({
        where: whereLabel,
        field: `line ${rows.length + 1}`,
        before: null,
        after: cur.text,
      });
      i++;
    } else {
      i++;
    }
  }
  return rows;
}

function classifyActor(
  rawActor: string | null | undefined,
  category: EstateChangeCategory,
  actorState?: "MATCHED" | "AMBIGUOUS",
): {
  readonly actorKey: string;
  readonly actorLabel: string;
  readonly isUnknown: boolean;
  readonly isAmbiguous: boolean;
  readonly isSystem: boolean;
} {
  if (category === "job") {
    return {
      actorKey: PRODUCT_NAME,
      actorLabel: PRODUCT_NAME,
      isUnknown: false,
      isAmbiguous: false,
      isSystem: true,
    };
  }
  if (category === "event" && (!rawActor || rawActor.toLowerCase() === "device")) {
    return {
      actorKey: "device",
      actorLabel: "device",
      isUnknown: false,
      isAmbiguous: false,
      isSystem: true,
    };
  }
  if (actorState === "AMBIGUOUS" || (rawActor && /^AMBIGUOUS\b/i.test(rawActor.trim()))) {
    return {
      actorKey: "AMBIGUOUS",
      actorLabel: rawActor?.trim() || "AMBIGUOUS actor",
      isUnknown: true,
      isAmbiguous: true,
      isSystem: false,
    };
  }
  if (actorUnknown(rawActor)) {
    return {
      actorKey: "UNKNOWN",
      actorLabel: "actor UNKNOWN",
      isUnknown: true,
      isAmbiguous: false,
      isSystem: false,
    };
  }
  const trimmed = rawActor!.trim();
  return {
    actorKey: trimmed,
    actorLabel: trimmed,
    isUnknown: false,
    isAmbiguous: false,
    isSystem: false,
  };
}

export function buildEstateChangesModel(inputs: {
  readonly now: Date;
  readonly windowDays: number;
  readonly policyChanges: SourceRead<PolicyChangesPage>;
  readonly changeFeed: SourceRead<ChangeFeedPage>;
  readonly activeCategories: ReadonlySet<EstateChangeCategory>;
  readonly actorFilter?: string | null;
  readonly model: EstateModel | null;
}): EstateChangesModel {
  const { now, windowDays, policyChanges, changeFeed, activeCategories, actorFilter = null, model } = inputs;
  const daySpan = windowDays <= 1 ? 2 : windowDays;
  const calendarDays = buildCalendarDays(now, daySpan);
  const estateById = model?.byId ?? new Map<string, EstateUnit>();
  const estateByName = model?.byName ?? new Map<string, EstateUnit>();

  const allSets: EstateChangeSet[] = [];
  const policySucceeded = policyChanges.state === "ok";

  // 1. Structured Policy Rule Revisions grouped by (policyId, collectedAt, actorKey)
  if (policySucceeded) {
    const groups = new Map<
      string,
      {
        policyId: string;
        policyName: string;
        time: string;
        actorInfo: ReturnType<typeof classifyActor>;
        rows: PolicyChangeRow[];
        initialCapture: boolean;
        observedAtCollection: boolean;
      }
    >();

    for (const row of policyChanges.value.rows) {
      const time = row.collectedAt ?? row.changedOn;
      const actorInfo = classifyActor(row.changedBy, "rule", row.actorState);
      const gKey = JSON.stringify([row.policyId, time, actorInfo.actorKey, Boolean(row.initialCapture)]);
      const existing = groups.get(gKey);
      if (existing) {
        existing.rows.push(row);
        existing.observedAtCollection = existing.observedAtCollection || Boolean(row.observedAtCollection);
      } else {
        groups.set(gKey, {
          policyId: row.policyId,
          policyName: row.policyName,
          time,
          actorInfo,
          rows: [row],
          initialCapture: Boolean(row.initialCapture),
          observedAtCollection: Boolean(row.observedAtCollection),
        });
      }
    }

    for (const [gKey, grp] of groups.entries()) {
      const added = grp.rows.filter((r) => r.changeType === "added").length;
      const modified = grp.rows.filter((r) => r.changeType === "modified").length;
      const removed = grp.rows.filter((r) => r.changeType === "removed").length;
      const parts: string[] = [];
      if (added > 0) parts.push(`${added} added`);
      if (modified > 0) parts.push(`${modified} modified`);
      if (removed > 0) parts.push(`${removed} removed`);

      const unitIdsSet = new Set<string>();
      const unitNamesSet = new Set<string>();
      const diffs: EstateDiffRow[] = [];

      for (const r of grp.rows) {
        for (const t of r.targets ?? []) {
          if (t.deviceId) unitIdsSet.add(t.deviceId);
          if (t.name) unitNamesSet.add(t.name);
        }
        const ruleLabel = `#${r.number ?? "?"} ${r.name || "Unnamed rule"}`;
        if (r.changes && r.changes.length > 0) {
          for (const ch of r.changes) {
            diffs.push({
              where: ruleLabel,
              field: ch.field,
              before: formatDiffValue(withFirewallNames(ch.before, r.targetDevices)),
              after: formatDiffValue(withFirewallNames(ch.after, r.targetDevices)),
              policyId: r.policyId,
              ruleId: r.ruleId,
            });
          }
        } else {
          diffs.push({
            where: ruleLabel,
            field: r.changedFields?.join(", ") || `(${r.changeType})`,
            before: r.changeType === "added" ? null : `(${r.changeType})`,
            after: r.changeType === "removed" ? "(removed)" : `(${r.changeType})`,
            policyId: r.policyId,
            ruleId: r.ruleId,
          });
        }
      }

      allSets.push({
        id: `pol:${gKey}`,
        category: "rule",
        count: grp.rows.length,
        time: grp.time,
        timeBasis: "UTC",
        deviceTimeText: null,
        observedAtCollection: grp.observedAtCollection,
        initialCapture: grp.initialCapture,
        actorKey: grp.actorInfo.actorKey,
        actorLabel: grp.actorInfo.actorLabel,
        actorIsUnknown: grp.actorInfo.isUnknown,
        actorIsAmbiguous: grp.actorInfo.isAmbiguous,
        actorIsSystem: grp.actorInfo.isSystem,
        headline: `${grp.policyName}: ${grp.rows.length} rule ${grp.rows.length === 1 ? "change" : "changes"}${grp.initialCapture ? " (initial capture)" : ""}`,
        meta: `${parts.join(" · ") || "stored rule revision"}${grp.observedAtCollection ? " · observed at collection" : ""}`,
        unitIds: [...unitIdsSet],
        unitNames: [...unitNamesSet],
        diffs,
        source: "policy_rule_history",
      });
    }
  }

  // 2. Estate Change Feed events (config changes, device events, jobs, and fallback rule history if policyChanges unavailable)
  if (changeFeed.state === "ok") {
    for (const evItem of changeFeed.value.events) {
      if (evItem.source === "policy_rule_history" && policySucceeded && policyChanges.value.rows.length > 0) {
        continue;
      }
      const category: EstateChangeCategory =
        evItem.kind === "NEXUS_JOB"
          ? "job"
          : evItem.kind === "DEVICE_EVENT"
            ? "event"
            : evItem.source === "policy_rule_history"
              ? "rule"
              : "config";
      const actorInfo = classifyActor(evItem.actor, category);
      const devName =
        evItem.device_name?.trim() ||
        (evItem.device_id ? estateById.get(evItem.device_id)?.name ?? evItem.device_id.slice(0, 8) : null);
      const resolvedId =
        evItem.device_id ?? (devName ? estateByName.get(devName)?.deviceId ?? null : null);

      const headline =
        category === "job" && evItem.links.job_type
          ? `${jobTypeLabel(evItem.links.job_type, evItem.vendor ?? undefined)} · ${evItem.what}`
          : devName
            ? `${devName}: ${evItem.what}`
            : evItem.what;

      const metaParts: string[] = [];
      if (evItem.detail) metaParts.push(evItem.detail);
      if (evItem.vendor) metaParts.push(vendorLabel(evItem.vendor));
      if (evItem.time_basis === "DEVICE_LOCAL") metaParts.push("device clock (zone not printed by device)");

      allSets.push({
        id: `feed:${evItem.key}`,
        category,
        count: 1,
        time: evItem.time,
        timeBasis: evItem.time_basis,
        deviceTimeText: evItem.device_time,
        observedAtCollection: false,
        initialCapture: false,
        actorKey: actorInfo.actorKey,
        actorLabel: actorInfo.actorLabel,
        actorIsUnknown: actorInfo.isUnknown,
        actorIsAmbiguous: actorInfo.isAmbiguous,
        actorIsSystem: actorInfo.isSystem,
        headline,
        meta: metaParts.join(" · ") || evItem.source,
        unitIds: resolvedId ? [resolvedId] : [],
        unitNames: devName ? [devName] : [],
        diffs: pairFeedDiffLines(devName ?? evItem.source, evItem.diff),
        source: evItem.source,
      });
    }
  }

  // Totals across all loaded categories (before category chip filter, so chip badges stay accurate)
  let totalRules = 0;
  let totalConfig = 0;
  let totalEvents = 0;
  let totalJobs = 0;
  let unknownActorChanges = 0;
  const namedPeopleSet = new Set<string>();

  for (const s of allSets) {
    if (s.category === "rule") totalRules += s.count;
    else if (s.category === "config") totalConfig += s.count;
    else if (s.category === "event") totalEvents += s.count;
    else totalJobs += s.count;

    if (s.category !== "job" && s.category !== "event") {
      if (s.actorIsUnknown) unknownActorChanges += s.count;
      else if (!s.actorIsSystem) namedPeopleSet.add(s.actorKey);
    }
  }
  if (policySucceeded && policyChanges.value.summary.total > totalRules) {
    totalRules = policyChanges.value.summary.total;
  }

  // Filter sets by active category chips and optional actor filter
  const visibleSets = allSets
    .filter((s) => activeCategories.has(s.category))
    .filter((s) => (!actorFilter ? true : s.actorKey === actorFilter))
    .sort((a, b) => (parseTimeMs(b.time) ?? 0) - (parseTimeMs(a.time) ?? 0) || a.id.localeCompare(b.id));

  // Build day volume buckets from sets matching activeCategories (ignoring actorFilter only if null)
  const dayIndexByKey = new Map(calendarDays.map((d, idx) => [d.key, idx] as const));
  const dayMutable = calendarDays.map((d) => ({
    dayKey: d.key,
    label: d.label,
    shortDay: d.shortDay,
    rules: 0,
    config: 0,
    events: 0,
    jobs: 0,
    total: 0,
  }));

  for (const s of visibleSets) {
    const dk = dayKeyInDisplayTz(s.time);
    const idx = dk ? dayIndexByKey.get(dk) : undefined;
    if (idx === undefined) continue;
    const bucket = dayMutable[idx];
    if (s.category === "rule") bucket.rules += s.count;
    else if (s.category === "config") bucket.config += s.count;
    else if (s.category === "event") bucket.events += s.count;
    else bucket.jobs += s.count;
    bucket.total += s.count;
  }

  // Build person swimlanes (grouped by person across active categories; human + UNKNOWN actors + optional system)
  const laneMap = new Map<
    string,
    {
      actorKey: string;
      actorLabel: string;
      isUnknown: boolean;
      isAmbiguous: boolean;
      isSystem: boolean;
      totalChanges: number;
      setCount: number;
      perDay: number[];
      newestAt: string | null;
    }
  >();

  for (const s of allSets.filter((x) => activeCategories.has(x.category))) {
    if (s.category === "job" && !activeCategories.has("job")) continue;
    const existing = laneMap.get(s.actorKey) ?? {
      actorKey: s.actorKey,
      actorLabel: s.actorLabel,
      isUnknown: s.actorIsUnknown,
      isAmbiguous: s.actorIsAmbiguous,
      isSystem: s.actorIsSystem,
      totalChanges: 0,
      setCount: 0,
      perDay: calendarDays.map(() => 0),
      newestAt: null,
    };
    existing.totalChanges += s.count;
    existing.setCount += 1;
    existing.newestAt = newestIso([existing.newestAt, s.time]);
    const dk = dayKeyInDisplayTz(s.time);
    const idx = dk ? dayIndexByKey.get(dk) : undefined;
    if (idx !== undefined) {
      existing.perDay[idx] += s.count;
    }
    laneMap.set(s.actorKey, existing);
  }

  // Sort person lanes: named human actors by volume desc, then UNKNOWN prominently, then system/device at bottom
  const personLanes: PersonChangeLane[] = [...laneMap.values()].sort((a, b) => {
    if (a.isSystem !== b.isSystem) return a.isSystem ? 1 : -1;
    if (a.isUnknown !== b.isUnknown) {
      // Keep UNKNOWN right after named actors (never buried under system/device)
      return a.isUnknown ? 1 : -1;
    }
    return b.totalChanges - a.totalChanges || a.actorLabel.localeCompare(b.actorLabel);
  });

  const coverage = changeFeed.state === "ok" ? changeFeed.value.coverage : [];
  const unavailableSources = coverage.filter((c) => c.state === "UNAVAILABLE" || c.state === "PARTIAL");

  return {
    days: dayMutable,
    personLanes,
    changeSets: visibleSets,
    totals: {
      rules: totalRules,
      config: totalConfig,
      events: totalEvents,
      jobs: totalJobs,
      unknownActorChanges,
      distinctNamedPeople: namedPeopleSet.size,
      initialCaptures: policySucceeded ? (policyChanges.value.summary.initialCaptures ?? 0) : 0,
    },
    coverage,
    unavailableSources,
    orderingNote: changeFeed.state === "ok" ? changeFeed.value.ordering_note : null,
    newestAt: newestIso(allSets.map((s) => s.time)),
  };
}

export function groupChangeSetsByPerson(
  sets: readonly EstateChangeSet[],
): ReadonlyArray<{
  readonly actorKey: string;
  readonly actorLabel: string;
  readonly isUnknown: boolean;
  readonly isAmbiguous: boolean;
  readonly isSystem: boolean;
  readonly totalChanges: number;
  readonly sets: readonly EstateChangeSet[];
}> {
  const byActor = new Map<
    string,
    {
      actorKey: string;
      actorLabel: string;
      isUnknown: boolean;
      isAmbiguous: boolean;
      isSystem: boolean;
      totalChanges: number;
      sets: EstateChangeSet[];
    }
  >();
  for (const s of sets) {
    const entry = byActor.get(s.actorKey) ?? {
      actorKey: s.actorKey,
      actorLabel: s.actorLabel,
      isUnknown: s.actorIsUnknown,
      isAmbiguous: s.actorIsAmbiguous,
      isSystem: s.actorIsSystem,
      totalChanges: 0,
      sets: [],
    };
    entry.totalChanges += s.count;
    entry.sets.push(s);
    byActor.set(s.actorKey, entry);
  }
  return [...byActor.values()].sort((a, b) => {
    if (a.isSystem !== b.isSystem) return a.isSystem ? 1 : -1;
    if (a.isUnknown !== b.isUnknown) return a.isUnknown ? 1 : -1;
    return b.totalChanges - a.totalChanges || a.actorLabel.localeCompare(b.actorLabel);
  });
}

// ============================================================================
// 3. COMPLIANCE LENS MODEL (Honest bar + Family x Vendor heatmap + Fix once)
// ============================================================================

export interface FamilyVendorCell {
  readonly vendor: string;
  readonly pass: number;
  readonly fail: number;
  readonly unavailable: number;
  readonly total: number;
  readonly evidenced: number;
  readonly failPctOfEvidenced: number | null;
  readonly badgeState: BadgeState;
}

export interface FamilyHeatmapRow {
  readonly familyKey: string;
  readonly familyCode: string;
  readonly familyTitle: string;
  readonly cells: ReadonlyMap<string, FamilyVendorCell>;
  readonly totalChecks: number;
  readonly totalFail: number;
}

export interface DataGapReasonRow {
  readonly reason: string;
  readonly checksCount: number;
  readonly controlCount: number;
}

export interface RemediationRankRow {
  readonly control: ComplianceControlItem;
  readonly familyLabel: string;
  readonly frameworkRef: string;
  readonly badgeState: BadgeState;
  readonly badgeWord: string;
  readonly affectedUnits: readonly EstateUnit[];
  readonly unmatchedAffectedNames: readonly string[];
}

export interface ComplianceLensModel {
  readonly frameworks: ReadonlyArray<{
    readonly key: string;
    readonly label: string;
    readonly pass: number;
    readonly fail: number;
    readonly unavailable: number;
    readonly total: number;
  }>;
  readonly selected: {
    readonly key: string;
    readonly label: string;
    readonly pass: number;
    readonly fail: number;
    readonly unavailable: number;
    readonly total: number;
    readonly passPct: number;
    readonly failPct: number;
    readonly unavailablePct: number;
    readonly criticalChecksFail: number;
  };
  readonly vendorColumns: ReadonlyArray<{ readonly key: string; readonly label: string }>;
  readonly familyRows: readonly FamilyHeatmapRow[];
  readonly dataGapReasons: readonly DataGapReasonRow[];
  readonly remediationRows: readonly RemediationRankRow[];
  readonly unassessedFirewalls: readonly EstateUnit[];
  readonly evaluatedFirewalls: number | null;
  readonly totalFirewalls: number | null;
  readonly complianceAt: string | null;
}

const DEFAULT_HEATMAP_VENDORS: ReadonlyArray<readonly [string, string]> = [
  ["check_point", "Check Point"],
  ["palo_alto", "Palo Alto"],
  ["cisco_asa", "Cisco ASA"],
  ["fortinet", "Fortinet"],
];

export function buildComplianceLensModel(inputs: {
  readonly complianceOverview: ComplianceOverview | null;
  readonly controls: readonly ComplianceControlItem[];
  readonly selectedFramework: string;
  readonly familyFilter: string;
  readonly complianceAt: string | null;
  readonly model: EstateModel | null;
}): ComplianceLensModel {
  const { complianceOverview, controls, selectedFramework, familyFilter, complianceAt, model } = inputs;
  const estateUnits = model?.units ?? [];
  const byId = model?.byId ?? new Map<string, EstateUnit>();
  const byName = model?.byName ?? new Map<string, EstateUnit>();

  const sumControls = (list: readonly ComplianceControlItem[]) => {
    let pass = 0;
    let fail = 0;
    let unavailable = 0;
    for (const c of list) {
      pass += c.pass_count ?? 0;
      fail += c.fail_count ?? 0;
      unavailable += c.data_unavailable_count ?? 0;
    }
    return { pass, fail, unavailable, total: pass + fail + unavailable };
  };

  const allSums = sumControls(controls);
  const frameworks: Array<{
    key: string;
    label: string;
    pass: number;
    fail: number;
    unavailable: number;
    total: number;
  }> = [
    {
      key: "ALL",
      label: "All controls",
      pass: allSums.pass,
      fail: allSums.fail,
      unavailable: allSums.unavailable,
      total: allSums.total,
    },
  ];

  for (const fw of complianceOverview?.frameworks ?? []) {
    const p = fw.pass_count ?? 0;
    const f = fw.fail_count ?? 0;
    const u = fw.data_unavailable_count ?? 0;
    const tot = p + f + u || fw.total_controls || 0;
    frameworks.push({
      key: fw.framework,
      label: fw.framework,
      pass: p,
      fail: f,
      unavailable: u,
      total: tot,
    });
  }

  const chosen = frameworks.find((f) => f.key === selectedFramework) ?? frameworks[0];

  const controlsInFramework =
    chosen.key === "ALL"
      ? controls
      : controls.filter((c) =>
          (c.frameworks ?? []).some(
            (m) =>
              m.framework.toLowerCase() === chosen.key.toLowerCase() ||
              chosen.key.toLowerCase().includes(m.framework.toLowerCase()) ||
              m.framework.toLowerCase().includes(chosen.key.split(" ")[0].toLowerCase()),
          ),
        );

  const criticalChecksFail = controlsInFramework
    .filter((c) => c.severity === "CRITICAL")
    .reduce((sum, c) => sum + (c.fail_count ?? 0), 0);

  const pct = (part: number, total: number) => (total > 0 ? Math.round((part / total) * 1000) / 10 : 0);

  // Determine vendor columns present in estate or controls
  const extraVendors = new Set<string>();
  for (const c of controlsInFramework) {
    for (const v of c.vendors ?? []) {
      if (!DEFAULT_HEATMAP_VENDORS.some(([k]) => k === v)) extraVendors.add(v);
    }
  }
  const vendorColumns: Array<{ key: string; label: string }> = [
    ...DEFAULT_HEATMAP_VENDORS.map(([key, label]) => ({ key, label })),
    ...[...extraVendors].sort().map((key) => ({ key, label: vendorDisplayName(key) })),
  ];

  // Build Family x Vendor heatmap from controlsInFramework
  const familyAccum = new Map<
    string,
    {
      code: string;
      title: string;
      byVendor: Map<string, { pass: number; fail: number; unavailable: number }>;
      totalChecks: number;
      totalFail: number;
    }
  >();

  for (const c of controlsInFramework) {
    const famLabel = familyOf(c);
    const [rawCode, ...rest] = famLabel.split(" · ");
    const code = rest.length > 0 ? rawCode : famLabel;
    const title = rest.length > 0 ? rest.join(" · ") : "";
    const entry = familyAccum.get(code) ?? {
      code,
      title,
      byVendor: new Map(),
      totalChecks: 0,
      totalFail: 0,
    };
    const p = c.pass_count ?? 0;
    const f = c.fail_count ?? 0;
    const u = c.data_unavailable_count ?? 0;
    entry.totalChecks += p + f + u;
    entry.totalFail += f;

    const cVendors = c.vendors && c.vendors.length > 0 ? c.vendors : [];
    if (cVendors.length === 1) {
      const vKey = cVendors[0];
      const cell = entry.byVendor.get(vKey) ?? { pass: 0, fail: 0, unavailable: 0 };
      cell.pass += p;
      cell.fail += f;
      cell.unavailable += u;
      entry.byVendor.set(vKey, cell);
    } else if (cVendors.length > 1) {
      // Attribute failing checks using affected_devices where identifiable; split remainder evenly across declared vendors
      const share = cVendors.length;
      for (const vKey of cVendors) {
        const cell = entry.byVendor.get(vKey) ?? { pass: 0, fail: 0, unavailable: 0 };
        cell.pass += Math.round(p / share);
        cell.fail += Math.round(f / share);
        cell.unavailable += Math.round(u / share);
        entry.byVendor.set(vKey, cell);
      }
    }
    familyAccum.set(code, entry);
  }

  const familyRows: FamilyHeatmapRow[] = [...familyAccum.values()]
    .map((fam) => {
      const cells = new Map<string, FamilyVendorCell>();
      for (const vc of vendorColumns) {
        const raw = fam.byVendor.get(vc.key) ?? { pass: 0, fail: 0, unavailable: 0 };
        const total = raw.pass + raw.fail + raw.unavailable;
        const evidenced = raw.pass + raw.fail;
        const failPctOfEvidenced = evidenced > 0 ? Math.round((raw.fail / evidenced) * 100) : null;
        const badgeState: BadgeState =
          total === 0
            ? "na"
            : evidenced === 0
              ? "unk"
              : (failPctOfEvidenced ?? 0) >= 50
                ? "act"
                : (failPctOfEvidenced ?? 0) > 0
                  ? "watch"
                  : "ok";
        cells.set(vc.key, {
          vendor: vc.key,
          pass: raw.pass,
          fail: raw.fail,
          unavailable: raw.unavailable,
          total,
          evidenced,
          failPctOfEvidenced,
          badgeState,
        });
      }
      return {
        familyKey: fam.code,
        familyCode: fam.code,
        familyTitle: fam.title,
        cells,
        totalChecks: fam.totalChecks,
        totalFail: fam.totalFail,
      };
    })
    .sort((a, b) => b.totalFail - a.totalFail || b.totalChecks - a.totalChecks || a.familyCode.localeCompare(b.familyCode));

  // Data gap reasons ("Why N checks have no evidence")
  const gapMap = new Map<string, { checksCount: number; controlCount: number }>();
  for (const c of controlsInFramework) {
    const u = c.data_unavailable_count ?? 0;
    if (u <= 0) continue;
    const reason = c.missing_reason?.trim() || "Evidence not collected in current scope";
    const cur = gapMap.get(reason) ?? { checksCount: 0, controlCount: 0 };
    cur.checksCount += u;
    cur.controlCount += 1;
    gapMap.set(reason, cur);
  }
  const dataGapReasons: DataGapReasonRow[] = [...gapMap.entries()]
    .map(([reason, v]) => ({ reason, checksCount: v.checksCount, controlCount: v.controlCount }))
    .sort((a, b) => b.checksCount - a.checksCount || a.reason.localeCompare(b.reason));

  // Fix once, clear many: rank controls by fail_count desc, severity, then unavailable count
  const sevRank: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
  const scopedControls = familyFilter
    ? controlsInFramework.filter((c) => familyOf(c).split(" · ")[0] === familyFilter)
    : controlsInFramework;

  const remediationRows: RemediationRankRow[] = scopedControls
    .filter((c) => (c.fail_count ?? 0) > 0 || (c.data_unavailable_count ?? 0) > 0)
    .sort(
      (a, b) =>
        (b.fail_count ?? 0) - (a.fail_count ?? 0) ||
        (sevRank[a.severity] ?? 9) - (sevRank[b.severity] ?? 9) ||
        (b.data_unavailable_count ?? 0) - (a.data_unavailable_count ?? 0) ||
        a.control_id.localeCompare(b.control_id),
    )
    .map((c) => {
      const affectedUnits: EstateUnit[] = [];
      const unmatched: string[] = [];
      for (const ref of c.affected_devices ?? []) {
        const u = byId.get(ref) ?? byName.get(ref);
        if (u) affectedUnits.push(u);
        else unmatched.push(ref);
      }
      const fwRef = (c.frameworks ?? [])
        .slice(0, 2)
        .map((m) => `${m.framework} ${m.reference || m.clauseId || ""}`.trim())
        .join(" · ");
      const hasFail = (c.fail_count ?? 0) > 0;
      const badgeState: BadgeState = !hasFail
        ? "unk"
        : c.severity === "CRITICAL"
          ? "act"
          : "watch";
      const badgeWord = !hasFail ? "UNKNOWN" : `${c.severity.toLowerCase()} · fail`;
      return {
        control: c,
        familyLabel: familyOf(c),
        frameworkRef: fwRef || c.control_id,
        badgeState,
        badgeWord,
        affectedUnits,
        unmatchedAffectedNames: unmatched,
      };
    });

  const unassessedFirewalls = estateUnits.filter(
    (u) =>
      u.role === "gateway" &&
      (u.estateCondition === "not_assessed" || u.lenses.compliance.badgeState === "unk"),
  );

  return {
    frameworks,
    selected: {
      ...chosen,
      passPct: pct(chosen.pass, chosen.total),
      failPct: pct(chosen.fail, chosen.total),
      unavailablePct: pct(chosen.unavailable, chosen.total),
      criticalChecksFail,
    },
    vendorColumns,
    familyRows,
    dataGapReasons,
    remediationRows,
    unassessedFirewalls,
    evaluatedFirewalls: complianceOverview?.evaluated_firewalls ?? null,
    totalFirewalls: complianceOverview?.total_firewalls ?? null,
    complianceAt,
  };
}

// ============================================================================
// 4. BACKUPS LENS MODEL (14-night strip + size deviation + store summary)
// ============================================================================

export type NightCellState = "stored" | "anomaly" | "failed" | "unknown" | "none";

export interface BackupNightCell {
  readonly dayKey: string;
  readonly label: string;
  readonly state: NightCellState;
  readonly glyph: "!" | "~" | "?" | null;
  readonly sizeBytes: number | null;
  readonly validationLevel: string | null;
  readonly reason: string;
}

export interface EstateBackupRow {
  readonly unit: EstateUnit;
  readonly state: BadgeState;
  readonly badgeWord: string;
  readonly nights: readonly BackupNightCell[];
  readonly sizeSeries: ReadonlyArray<number | null>;
  readonly lastSizeBytes: number | null;
  readonly medianSizeBytes: number | null;
  readonly sizeDeviationPct: number | null;
  readonly sizeDeviationLabel: string | null;
  readonly hasSizeDeviation: boolean;
  readonly lastArchiveAt: string | null;
  readonly validationWord: string | null;
  readonly why: string;
}

export interface BackupsLensModel {
  readonly rows: readonly EstateBackupRow[];
  readonly targetCount: number;
  readonly storedCount: number;
  readonly validatedCount: number;
  readonly neverBackedUpCount: number;
  readonly failedRecentCount: number;
  readonly sizeDeviationCount: number;
  readonly nonTargetCount: number;
  readonly lastFleetRunAt: string | null;
  readonly retentionDays: number | null;
  readonly storeUsage: {
    readonly known: boolean;
    readonly usedBytes: number | null;
    readonly quotaBytes: number | null;
    readonly usedPct: number | null;
    readonly storeCount: number;
    readonly exceededCount: number;
  };
}

function medianOf(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

const SIZE_DEVIATION_THRESHOLD_PCT = 25;

export function buildBackupsLensModel(inputs: {
  readonly now: Date;
  readonly estateUnits: readonly EstateUnit[];
  readonly backupsRead: SourceRead<{
    readonly backups: readonly BackupArtefact[];
    readonly deviations?: BackupDeviations | null;
  }>;
  readonly policy: SourceRead<BackupPolicy>;
  readonly quotas: SourceRead<BackupStoreQuotas>;
  readonly backupJobs: SourceRead<readonly JobEventView[]>;
  readonly backupEvidenceAt: string | null;
}): BackupsLensModel {
  const { now, estateUnits, backupsRead, policy, quotas, backupJobs, backupEvidenceAt } = inputs;
  const nowMs = now.getTime();
  const calendarDays = buildCalendarDays(now, 14);
  const retentionDays = policy.state === "ok" ? policy.value.backup_retention_days : null;

  // Group artefacts by deviceId and dayKey
  const artefactsByDevice = new Map<string, BackupArtefact[]>();
  if (backupsRead.state === "ok") {
    for (const art of backupsRead.value.backups) {
      const arr = artefactsByDevice.get(art.device_id) ?? [];
      arr.push(art);
      artefactsByDevice.set(art.device_id, arr);
    }
  }

  // Group backup jobs by deviceId and dayKey
  const jobsByDeviceDay = new Map<string, JobEventView>();
  const newestJobByDevice = new Map<string, JobEventView>();
  if (backupJobs.state === "ok") {
    for (const j of backupJobs.value) {
      if (!j.target_device_id) continue;
      const prevNewest = newestJobByDevice.get(j.target_device_id);
      if (!prevNewest || (parseTimeMs(j.submitted_at) ?? 0) > (parseTimeMs(prevNewest.submitted_at) ?? 0)) {
        newestJobByDevice.set(j.target_device_id, j);
      }
      const dk = dayKeyInDisplayTz(j.submitted_at);
      if (!dk) continue;
      const k = `${j.target_device_id}::${dk}`;
      const existing = jobsByDeviceDay.get(k);
      if (!existing || (parseTimeMs(j.submitted_at) ?? 0) > (parseTimeMs(existing.submitted_at) ?? 0)) {
        jobsByDeviceDay.set(k, j);
      }
    }
  }

  // Major config deviations by deviceId
  const majorByDevice = new Map<string, string[]>();
  if (backupsRead.state === "ok") {
    for (const md of backupsRead.value.deviations?.active_major_deviations ?? []) {
      const cats = majorByDevice.get(md.device_id) ?? [];
      if (!cats.includes(md.category)) cats.push(md.category);
      majorByDevice.set(md.device_id, cats);
    }
  }

  const rows: EstateBackupRow[] = estateUnits.map((u) => {
    const unitArts = [...(artefactsByDevice.get(u.deviceId) ?? [])].sort(
      (a, b) => (parseTimeMs(a.collected_at) ?? 0) - (parseTimeMs(b.collected_at) ?? 0),
    );
    const newestArt = unitArts.at(-1) ?? u.newestBackup;
    const newestJob = newestJobByDevice.get(u.deviceId) ?? null;

    if (!u.backupTarget && !newestArt) {
      return {
        unit: u,
        state: "na",
        badgeWord: "Not a target",
        nights: calendarDays.map((d) => ({
          dayKey: d.key,
          label: d.label,
          state: "none",
          glyph: null,
          sizeBytes: null,
          validationLevel: null,
          reason: "Switched off as a backup target",
        })),
        sizeSeries: calendarDays.map(() => null),
        lastSizeBytes: null,
        medianSizeBytes: null,
        sizeDeviationPct: null,
        sizeDeviationLabel: null,
        hasSizeDeviation: false,
        lastArchiveAt: null,
        validationWord: null,
        why: "Switched off as a backup target",
      };
    }

    if (backupsRead.state !== "ok") {
      return {
        unit: u,
        state: "unk",
        badgeWord: "UNKNOWN",
        nights: calendarDays.map((d) => ({
          dayKey: d.key,
          label: d.label,
          state: "unknown",
          glyph: "?",
          sizeBytes: null,
          validationLevel: null,
          reason: "Backup store could not be read",
        })),
        sizeSeries: calendarDays.map(() => null),
        lastSizeBytes: null,
        medianSizeBytes: null,
        sizeDeviationPct: null,
        sizeDeviationLabel: null,
        hasSizeDeviation: false,
        lastArchiveAt: u.lastBackupAt,
        validationWord: null,
        why: "Backup store unavailable — state is UNKNOWN",
      };
    }

    // Index unit's newest artefact per calendar day
    const artByDay = new Map<string, BackupArtefact>();
    for (const art of unitArts) {
      const dk = dayKeyInDisplayTz(art.collected_at);
      if (dk) artByDay.set(dk, art);
    }

    const medianSizeBytes =
      u.sizeAnomaly?.median_bytes ?? medianOf(unitArts.map((a) => a.size_bytes));
    const lastSizeBytes = u.sizeAnomaly?.plaintext_bytes ?? newestArt?.size_bytes ?? null;

    let sizeDeviationPct: number | null = null;
    if (lastSizeBytes !== null && medianSizeBytes !== null && medianSizeBytes > 0 && (unitArts.length >= 2 || u.sizeAnomaly)) {
      sizeDeviationPct = Math.round(((lastSizeBytes - medianSizeBytes) / medianSizeBytes) * 100);
    }

    const sharpHistoryJump =
      sizeDeviationPct !== null && Math.abs(sizeDeviationPct) >= SIZE_DEVIATION_THRESHOLD_PCT;
    const hasSizeDeviation = Boolean(u.sizeAnomaly || sharpHistoryJump || u.largeBackup);

    const sizeDeviationLabel = u.sizeAnomaly
      ? sizeAnomalyWord(u.sizeAnomaly)
      : sharpHistoryJump && sizeDeviationPct !== null
        ? `${sizeDeviationPct > 0 ? "+" : ""}${sizeDeviationPct}% vs median`
        : u.largeBackup
          ? `${u.largeBackup.factor.toFixed(1)}x vendor median`
          : sizeDeviationPct !== null
            ? `${sizeDeviationPct > 0 ? "+" : ""}${sizeDeviationPct}% vs median`
            : null;

    const nights: BackupNightCell[] = calendarDays.map((d, dayIdx) => {
      const art = artByDay.get(d.key);
      if (art) {
        const isAnomArt =
          u.sizeAnomaly?.artefact_id === art.artefact_id ||
          (medianSizeBytes !== null &&
            medianSizeBytes > 0 &&
            unitArts.length >= 2 &&
            Math.abs((art.size_bytes - medianSizeBytes) / medianSizeBytes) * 100 >= SIZE_DEVIATION_THRESHOLD_PCT);
        if (isAnomArt) {
          return {
            dayKey: d.key,
            label: d.label,
            state: "anomaly",
            glyph: "~",
            sizeBytes: art.size_bytes,
            validationLevel: art.validation_level,
            reason: `${formatBytes(art.size_bytes)} · size deviation`,
          };
        }
        return {
          dayKey: d.key,
          label: d.label,
          state: "stored",
          glyph: null,
          sizeBytes: art.size_bytes,
          validationLevel: art.validation_level,
          reason: `Stored (${formatBytes(art.size_bytes)} · ${backupDisplayWord(art.validation_level)})`,
        };
      }

      const job = jobsByDeviceDay.get(`${u.deviceId}::${d.key}`);
      if (job) {
        const st = (job.state ?? "").toUpperCase();
        if (st === "OUTCOME_UNKNOWN") {
          return {
            dayKey: d.key,
            label: d.label,
            state: "unknown",
            glyph: "?",
            sizeBytes: null,
            validationLevel: null,
            reason: `Attempt outcome UNKNOWN${job.terminal_reason ? `: ${job.terminal_reason}` : ""}`,
          };
        }
        if (st === "FAILED" || st === "REJECTED") {
          return {
            dayKey: d.key,
            label: d.label,
            state: "failed",
            glyph: "!",
            sizeBytes: null,
            validationLevel: null,
            reason: `Backup failed${job.terminal_reason ? `: ${job.terminal_reason}` : ""}`,
          };
        }
      }

      // Latest night fallback when device summary reports latest_job_type === "backup" FAILED
      if (
        dayIdx === calendarDays.length - 1 &&
        u.latestJobType === "backup" &&
        u.latestJobState?.toUpperCase() === "FAILED"
      ) {
        return {
          dayKey: d.key,
          label: d.label,
          state: "failed",
          glyph: "!",
          sizeBytes: null,
          validationLevel: null,
          reason: `Latest backup failed${u.latestJobReason ? `: ${u.latestJobReason}` : ""}`,
        };
      }

      const daysAgo = calendarDays.length - 1 - dayIdx;
      const outsideRetention = retentionDays !== null && daysAgo >= retentionDays;
      return {
        dayKey: d.key,
        label: d.label,
        state: "none",
        glyph: null,
        sizeBytes: null,
        validationLevel: null,
        reason: outsideRetention ? "Outside retention window (no archive kept)" : "No archive stored on this date",
      };
    });

    const sizeSeries = nights.map((n) => n.sizeBytes);
    const lastArchiveAt = newestArt?.collected_at ?? u.lastBackupAt;
    const validationWord = newestArt ? backupDisplayWord(newestArt.validation_level) : null;

    const latestAttemptFailed =
      (u.latestJobType === "backup" && u.latestJobState?.toUpperCase() === "FAILED") ||
      (newestJob &&
        ["FAILED", "REJECTED"].includes((newestJob.state ?? "").toUpperCase()) &&
        (!lastArchiveAt || (parseTimeMs(newestJob.submitted_at) ?? 0) > (parseTimeMs(lastArchiveAt) ?? 0)));

    const latestAttemptUnknown =
      newestJob !== null &&
      (newestJob.state ?? "").toUpperCase() === "OUTCOME_UNKNOWN" &&
      (!lastArchiveAt || (parseTimeMs(newestJob.submitted_at) ?? 0) > (parseTimeMs(lastArchiveAt) ?? 0));

    const unknownNightCount = nights.filter((n) => n.state === "unknown").length;

    const archiveAgeStale =
      lastArchiveAt !== null &&
      parseTimeMs(lastArchiveAt) !== null &&
      nowMs - parseTimeMs(lastArchiveAt)! > BACKUP_STALE_DAYS * DAY_MS;

    const majorCats = majorByDevice.get(u.deviceId);

    let state: BadgeState = "ok";
    let badgeWord = "Fine";
    let why = validationWord ? `Stored · ${validationWord}` : "Stored";

    if (u.noArchive || (!newestArt && !lastArchiveAt)) {
      state = "act";
      badgeWord = "Act";
      why =
        (newestJob?.terminal_reason ?? u.latestJobReason) ||
        "No stored backup archive in the store";
    } else if (latestAttemptFailed) {
      state = "act";
      badgeWord = "Act";
      why =
        (newestJob?.terminal_reason ?? u.latestJobReason) ||
        "Latest backup attempt failed";
    } else if (latestAttemptUnknown) {
      state = "unk";
      badgeWord = "UNKNOWN";
      why = "Latest backup attempt ended with OUTCOME_UNKNOWN";
    } else if (unknownNightCount > 0) {
      state = "unk";
      badgeWord = "UNKNOWN";
      why = `${unknownNightCount} ${unknownNightCount === 1 ? "night" : "nights"} with attempt outcome UNKNOWN in the window`;
    } else if (u.sizeAnomaly) {
      state = "watch";
      badgeWord = "Review";
      why = u.sizeAnomaly.summary || `${sizeAnomalyWord(u.sizeAnomaly)} (${formatBytes(u.sizeAnomaly.median_bytes)} → ${formatBytes(u.sizeAnomaly.plaintext_bytes)})`;
    } else if (sharpHistoryJump && sizeDeviationLabel) {
      state = "watch";
      badgeWord = "Review";
      why = `Archive size changed (${sizeDeviationLabel})`;
    } else if (u.largeBackup && newestArt) {
      state = "watch";
      badgeWord = "Review";
      why = `${u.largeBackup.factor.toFixed(1)}x vendor median (${formatBytes(u.largeBackup.medianBytes)})`;
    } else if (archiveAgeStale) {
      state = "watch";
      badgeWord = "Review";
      why = `Newest archive is older than ${BACKUP_STALE_DAYS} days`;
    } else if (majorCats && majorCats.length > 0) {
      state = "watch";
      badgeWord = "Review";
      why = `Major config change: ${majorCats.join(", ")}`;
    }

    return {
      unit: u,
      state,
      badgeWord,
      nights,
      sizeSeries,
      lastSizeBytes,
      medianSizeBytes,
      sizeDeviationPct,
      sizeDeviationLabel,
      hasSizeDeviation,
      lastArchiveAt,
      validationWord,
      why,
    };
  });

  const rowRank: Record<BadgeState, number> = {
    act: 0,
    unk: 1,
    watch: 2,
    ok: 3,
    na: 4,
  };
  const sortedRows = [...rows].sort(
    (a, b) => rowRank[a.state] - rowRank[b.state] || a.unit.name.localeCompare(b.unit.name),
  );

  const targetRows = sortedRows.filter((r) => r.state !== "na");
  const nonTargetRows = sortedRows.filter((r) => r.state === "na");

  // Store usage summary from GET /api/v2/backups/store-quotas
  const storeUsage = (() => {
    if (quotas.state !== "ok" || !quotas.value.store_mounted) {
      return {
        known: false,
        usedBytes: null,
        quotaBytes: null,
        usedPct: null,
        storeCount: quotas.state === "ok" ? quotas.value.stores.length : 0,
        exceededCount: 0,
      };
    }
    const stores = quotas.value.stores;
    const measured = stores.filter((s) => s.used_bytes !== null);
    if (measured.length === 0 && stores.length > 0) {
      return {
        known: false,
        usedBytes: null,
        quotaBytes: null,
        usedPct: null,
        storeCount: stores.length,
        exceededCount: stores.filter((s) => s.quota_exceeded).length,
      };
    }
    const usedBytes = measured.reduce((sum, s) => sum + (s.used_bytes ?? 0), 0);
    const withQuota = stores.filter((s) => s.quota_bytes !== null && s.quota_bytes > 0);
    const quotaBytes =
      withQuota.length > 0 && withQuota.length === stores.length
        ? withQuota.reduce((sum, s) => sum + (s.quota_bytes ?? 0), 0)
        : null;
    const usedPct = quotaBytes && quotaBytes > 0 ? Math.round((usedBytes / quotaBytes) * 100) : null;
    return {
      known: true,
      usedBytes,
      quotaBytes,
      usedPct,
      storeCount: stores.length,
      exceededCount: stores.filter((s) => s.quota_exceeded).length,
    };
  })();

  const lastScheduled = policy.state === "ok" ? (policy.value.last_scheduled_run_at ?? null) : null;
  const newestStoredAt = newestIso(sortedRows.map((r) => r.lastArchiveAt));

  return {
    rows: sortedRows,
    targetCount: targetRows.length,
    storedCount: targetRows.filter((r) => r.lastArchiveAt !== null).length,
    validatedCount: targetRows.filter((r) => r.lastArchiveAt !== null && Boolean(r.validationWord && r.validationWord !== "UNKNOWN")).length,
    neverBackedUpCount: targetRows.filter((r) => r.lastArchiveAt === null && r.state === "act").length,
    failedRecentCount: targetRows.filter((r) => r.lastArchiveAt !== null && r.state === "act").length,
    sizeDeviationCount: targetRows.filter((r) => r.hasSizeDeviation).length,
    nonTargetCount: nonTargetRows.length,
    lastFleetRunAt: lastScheduled ?? newestStoredAt ?? backupEvidenceAt,
    retentionDays,
    storeUsage,
  };
}
