import type {
  SystemHealth,
  SystemHealthDevice,
  BackupArtefact,
  BackupDeviations,
  BackupSizeAnomaly,
  ChangeFeedPage,
  ComplianceControlItem,
  CpFailoverSummary,
  DeviceSummary,
  EstateCondition,
  EstateConditionReason,
  IdentityCheckView,
  LifecycleDevice,
  LifecycleFleet,
  OverviewView,
  ReadinessCheck,
} from "../auth/adminApi";
import { PRODUCT_NAME } from "../brand/product";
import { formatBytes } from "../shell/bytes";
import { AGEING_UNTIL_MS, FRESH_UNDER_MS, isPastFresh } from "../shell/freshness";
import { clusterModeWord, vendorLabel } from "../shell/presenters";
import { vendorDisplayName } from "../shell/States";
import { relativeAge } from "../shell/time";
import { BACKUP_STALE_DAYS, buildAttention, licenseState, type AttentionResult, type SourceRead } from "../screens/overview/attentionRules";
import { hasBackupMethod } from "../screens/backupCounts";
import { largeBackupFlag, vendorMedianSizes, type LargeBackupFlag } from "../screens/backupSize";
import { backupDisplayWord } from "../screens/backupWords";
import { knownHaRole, orderMembers } from "../screens/DeviceShared";
import { groupClusters, haReadinessItems, primaryReasonWord, readinessCounts } from "../screens/HaReadinessList";
import { ESTATE_LENSES, type EstateLens } from "./estateRouting";

export type BadgeState = "act" | "watch" | "ok" | "unk" | "na";
export type TileState = BadgeState | "fresh" | "day" | "old" | "stale";

export interface LensAnswer {
  readonly tileState: TileState;
  readonly badgeState: BadgeState;
  readonly badgeWord: string;
  readonly summary: string;
  readonly detail: string | null;
  readonly evidenceAt: string | null;
  readonly evidenceSource: string;
}

export interface LensMeta {
  readonly key: EstateLens;
  readonly label: string;
  readonly question: string;
}

export const LENS_META: Readonly<Record<EstateLens, LensMeta>> = {
  worst: { key: "worst", label: "Worst of all", question: "What needs attention anywhere?" },
  failover: { key: "failover", label: "Failover", question: "Can each HA cluster fail over cleanly?" },
  backups: { key: "backups", label: "Backups", question: "Is there a stored, validated backup?" },
  compliance: { key: "compliance", label: "Controls", question: "Do its critical controls pass?" },
  exposure: { key: "exposure", label: "Exposure", question: "Is its software or support exposed?" },
  install: { key: "install", label: "Policy installed", question: "Does the gateway report an installed policy?" },
  health: { key: "health", label: "Health", question: "Is each unit's device health normal?" },
  age: { key: "age", label: "Evidence age", question: "How old is what we know?" },
};

export const BADGE_WORD_DEFAULT: Readonly<Record<BadgeState, string>> = {
  act: "Act",
  watch: "Review",
  ok: "Fine",
  unk: "UNKNOWN",
  na: "Not applicable",
};

/**
 * Sorting rank for every tile/badge state: UNKNOWN (`1`) always sorts immediately after `act` (`0`) and before
 * `watch` (`2`) and `ok` (`4`), so un-evidenced units surface with problems and never sink into "Fine".
 */
export const TILE_STATE_RANK: Readonly<Record<TileState, number>> = {
  act: 0,
  stale: 0,
  unk: 1,
  watch: 2,
  old: 2,
  day: 3,
  ok: 4,
  fresh: 4,
  na: 5,
};

export function badgeStateOf(tile: TileState): BadgeState {
  if (tile === "fresh" || tile === "day") return "ok";
  if (tile === "old") return "watch";
  if (tile === "stale") return "act";
  return tile;
}

export function needsAttentionOnLens(tile: TileState): boolean {
  return TILE_STATE_RANK[tile] <= 2;
}

export function isActOrUnknown(tile: TileState): boolean {
  return TILE_STATE_RANK[tile] <= 1;
}

export type AgeBucket = "fresh" | "day" | "old" | "stale" | "never";

const SIX_HOURS_MS = 6 * 3_600_000;
const DAY_MS = 86_400_000;

export function parseTimeMs(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? null : ms;
}

export function ageBucketOf(iso: string | null | undefined, now: Date): AgeBucket {
  const t = parseTimeMs(iso);
  if (t === null) return "never";
  const ageMs = Math.max(0, now.getTime() - t);
  if (ageMs < SIX_HOURS_MS) return "fresh";
  if (ageMs < FRESH_UNDER_MS) return "day";
  if (ageMs <= AGEING_UNTIL_MS) return "old";
  return "stale";
}

export interface EstateUnit {
  readonly deviceId: string;
  readonly name: string;
  readonly vendor: string;
  readonly vendorName: string;
  readonly model: string | null;
  readonly version: string | null;
  readonly hotfix: string | null;
  readonly role: "gateway" | "management_server" | "appliance";
  readonly haRole: string | null;
  readonly clusterRef: string | null;
  readonly enrollmentState: string;
  readonly managementIp: string | null;
  readonly lastReadAt: string | null;
  readonly ageBucket: AgeBucket;
  readonly platformFactsAt: string | null;
  readonly platformFactsSource: string | null;
  readonly policyName: string | null;
  readonly policyInstalledAt: string | null;
  readonly policyInstalledText: string | null;
  readonly policyReadAt: string | null;
  readonly backupTarget: boolean;
  readonly newestBackup: BackupArtefact | null;
  readonly lastBackupAt: string | null;
  readonly noArchive: boolean;
  readonly sizeAnomaly: BackupSizeAnomaly | null;
  readonly largeBackup: LargeBackupFlag | null;
  readonly estateCondition: EstateCondition | null;
  readonly estateConditionReason: EstateConditionReason | null;
  readonly criticalFail: number | null;
  readonly lifecycle: LifecycleDevice | null;
  readonly identityCheck: IdentityCheckView | null;
  readonly latestJobState: string | null;
  readonly latestJobType: string | null;
  readonly latestJobReason: string | null;
  readonly lenses: Readonly<Record<EstateLens, LensAnswer>>;
}

export interface EstateCluster {
  readonly ref: string;
  readonly title: string;
  readonly vendor: string;
  readonly vendorName: string;
  readonly mode: string | null;
  readonly members: readonly EstateUnit[];
  readonly failoverStatus: "READY" | "NOT_READY" | "UNKNOWN" | "UNSUPPORTED";
  readonly pairState: BadgeState;
  readonly reason: string;
  readonly observedAt: string | null;
  readonly stale: boolean;
  readonly checks: ReadonlyArray<ReadinessCheck & { status?: string }>;
}

export interface VendorWallGroup {
  readonly key: string;
  readonly title: string;
  readonly clusters: readonly EstateCluster[];
  readonly standaloneGateways: readonly EstateUnit[];
  readonly appliances: readonly EstateUnit[];
  readonly managers: readonly EstateUnit[];
  readonly unitCount: number;
  readonly newestReadAt: string | null;
}

export interface EstateFreshnessBreakdown {
  readonly fresh: number;
  readonly day: number;
  readonly old: number;
  readonly stale: number;
  readonly never: number;
  readonly total: number;
}

export interface CheckedFineRow {
  readonly key: string;
  readonly label: string;
  readonly figure: string;
  readonly context: string;
  readonly unknownNote: string | null;
  readonly evidenceAt: string | null;
  readonly evidenceSource: string;
}

export interface EstateModel {
  readonly now: Date;
  readonly units: readonly EstateUnit[];
  readonly byId: ReadonlyMap<string, EstateUnit>;
  readonly byName: ReadonlyMap<string, EstateUnit>;
  readonly clusters: readonly EstateCluster[];
  readonly clustersByRef: ReadonlyMap<string, EstateCluster>;
  readonly vendorGroups: readonly VendorWallGroup[];
  readonly freshness: EstateFreshnessBreakdown;
  readonly haCounts: {
    readonly total: number;
    readonly ready: number;
    readonly notReady: number;
    readonly unknown: number;
    readonly unsupported: number;
    readonly readyStale: number;
  } | null;
  readonly attention: AttentionResult | null;
  readonly checkedFine: readonly CheckedFineRow[];
  readonly newestEvidenceAt: string | null;
  readonly oldestEvidenceAt: string | null;
  readonly draftCount: number;
}

export interface EstateInputs {
  readonly now: Date;
  readonly devices: SourceRead<readonly DeviceSummary[]>;
  readonly overview: SourceRead<OverviewView>;
  readonly failover: SourceRead<readonly CpFailoverSummary[]>;
  readonly backups: SourceRead<{
    readonly backups: readonly BackupArtefact[];
    readonly deviations?: BackupDeviations | null;
  }>;
  readonly lifecycle: SourceRead<LifecycleFleet>;
  readonly controls?: SourceRead<readonly ComplianceControlItem[]>;
  readonly changes?: SourceRead<ChangeFeedPage>;
  /** Device health snapshot (`/api/v2/system-logs/health`); absent or not read means UNKNOWN on the Health lens. */
  readonly health?: SourceRead<SystemHealth>;
  readonly permissions?: readonly string[];
}

/**
 * Health lens answer for one unit. Fail-closed: a missing, failed, restricted, uncomputed or stale snapshot never
 * reads as NORMAL; a NORMAL verdict needs a current snapshot and a collection that finished OK.
 * Evidence age is the age of the snapshot the verdict was computed in.
 */
export function healthLensAnswer(input: {
  readonly source: SourceRead<SystemHealth> | undefined;
  readonly device: SystemHealthDevice | null;
  readonly now: Date;
}): LensAnswer {
  const { source, device, now } = input;
  const src = "device health";
  if (!source || source.state === "skipped") {
    return makeAnswer("unk", "Device health not read", null, src);
  }
  if (source.state === "restricted") {
    return makeAnswer("unk", "Device health restricted for this account", null, src);
  }
  if (source.state !== "ok") {
    return makeAnswer("unk", "Device health UNKNOWN (read failed)", null, src);
  }
  const computation = source.value.computation;
  const computedAt = computation.computed_at ?? null;
  if (computation.state === "FAILED") {
    return makeAnswer("unk", "Device health computation failed", computedAt, src);
  }
  if (computation.state === "COMPUTING" || !computedAt) {
    return makeAnswer("unk", "Device health not computed yet", null, src);
  }
  if (!device) {
    return makeAnswer("unk", "No device health row for this unit", computedAt, src);
  }
  const rel = relativeAge(computedAt, now);
  if (device.state === "CRITICAL") {
    return makeAnswer("act", `Critical at snapshot ${rel}`, computedAt, src, { detail: device.state_reason });
  }
  if (device.state === "ATTENTION") {
    return makeAnswer("watch", `Attention at snapshot ${rel}`, computedAt, src, { detail: device.state_reason });
  }
  if (device.state !== "NORMAL") {
    return makeAnswer("unk", `Health UNKNOWN at snapshot ${rel}`, computedAt, src, { detail: device.state_reason });
  }
  if (ageBucketOf(computedAt, now) === "stale") {
    return makeAnswer("unk", `Normal at snapshot ${rel}: stale (> 72 h), not shown as normal`, computedAt, src);
  }
  if (device.collection.state !== "OK") {
    return makeAnswer("unk", `Normal not proven: collection ${device.collection.state.toLowerCase()}`, computedAt, src);
  }
  return makeAnswer("ok", `Normal at snapshot ${rel}`, computedAt, src);
}

const FIREWALL_VENDORS = new Set(["check_point", "palo_alto", "cisco_asa", "fortinet"]);

function normalizeRole(device: Pick<DeviceSummary, "role" | "vendor_hint">): "gateway" | "management_server" | "appliance" {
  if (device.role === "management_server") return "management_server";
  if (device.role === "appliance") return "appliance";
  if (device.role === "gateway" || device.role === "firewall") return "gateway";
  return FIREWALL_VENDORS.has(device.vendor_hint) ? "gateway" : "appliance";
}

function newestIso(isos: ReadonlyArray<string | null | undefined>): string | null {
  let best: string | null = null;
  let bestMs = -Infinity;
  for (const iso of isos) {
    const ms = parseTimeMs(iso);
    if (ms !== null && ms > bestMs) {
      bestMs = ms;
      best = iso!;
    }
  }
  return best;
}

function oldestIso(isos: ReadonlyArray<string | null | undefined>): string | null {
  let worst: string | null = null;
  let worstMs = Infinity;
  for (const iso of isos) {
    const ms = parseTimeMs(iso);
    if (ms !== null && ms < worstMs) {
      worstMs = ms;
      worst = iso!;
    }
  }
  return worst;
}

function makeAnswer(
  tileState: TileState,
  summary: string,
  evidenceAt: string | null,
  evidenceSource: string,
  options: { badgeWord?: string; detail?: string | null } = {},
): LensAnswer {
  const badgeState = badgeStateOf(tileState);
  return {
    tileState,
    badgeState,
    badgeWord: options.badgeWord ?? BADGE_WORD_DEFAULT[badgeState],
    summary,
    detail: options.detail ?? null,
    evidenceAt,
    evidenceSource,
  };
}

export function buildEstateModel(inputs: EstateInputs): EstateModel {
  const { now } = inputs;
  const nowMs = now.getTime();
  const overview = inputs.overview.state === "ok" ? inputs.overview.value : null;

  // 1. Base device list: active non-DRAFT devices from /devices, or fallback to overview.estate.devices
  const rawDevices: readonly DeviceSummary[] =
    inputs.devices.state === "ok"
      ? inputs.devices.value
      : overview?.estate?.devices
        ? overview.estate.devices.map((d) => ({
            device_id: d.device_id,
            vendor_hint: d.vendor,
            enrollment_state: "ENROLLED",
            hostname: d.hostname,
            model: d.model,
            software_version: null,
            ha_role: null,
            cluster_member_ref: null,
            inventory_collected_at: d.last_read_at,
            backup_target: d.backup_target,
          }))
        : [];

  const draftCount = rawDevices.filter((d) => d.enrollment_state === "DRAFT").length;
  const activeDevices = rawDevices.filter((d) => d.enrollment_state !== "DRAFT");

  // Index overview estate facts by device_id
  const estateByDevice = new Map(
    (overview?.estate?.devices ?? []).map((d) => [d.device_id, d] as const),
  );

  // Index backups by device_id (newest artefact per device) + vendor medians + size anomalies
  const newestBackupByDevice = new Map<string, BackupArtefact>();
  const sizeAnomalyByDevice = new Map<string, BackupSizeAnomaly>();
  if (inputs.backups.state === "ok") {
    for (const art of inputs.backups.value.backups) {
      const prev = newestBackupByDevice.get(art.device_id);
      const artMs = parseTimeMs(art.collected_at) ?? -Infinity;
      const prevMs = prev ? (parseTimeMs(prev.collected_at) ?? -Infinity) : -Infinity;
      if (!prev || artMs > prevMs) {
        newestBackupByDevice.set(art.device_id, art);
      }
    }
    for (const anomaly of inputs.backups.value.deviations?.size_anomalies ?? []) {
      sizeAnomalyByDevice.set(anomaly.device_id, anomaly);
    }
  }
  const medians = vendorMedianSizes(
    activeDevices
      .map((d) => {
        const b = newestBackupByDevice.get(d.device_id);
        return b ? { vendor: d.vendor_hint, sizeBytes: b.size_bytes } : null;
      })
      .filter((x): x is { vendor: string; sizeBytes: number } => x !== null),
  );

  // Index lifecycle by device_id
  const lifecycleByDevice = new Map<string, LifecycleDevice>();
  const lifecycleAsOf = inputs.lifecycle.state === "ok" ? inputs.lifecycle.value.as_of : null;
  if (inputs.lifecycle.state === "ok") {
    for (const ld of inputs.lifecycle.value.devices) {
      lifecycleByDevice.set(ld.device_id, ld);
    }
  }

  // Group HA clusters & compute failover readiness items using the app's canonical HaReadinessList logic
  const rawClusters = groupClusters(activeDevices);
  const failoverRowsByRef: Record<string, CpFailoverSummary[]> = {};
  if (inputs.failover.state === "ok") {
    for (const r of inputs.failover.value) {
      (failoverRowsByRef[r.cluster_member_ref] ??= []).push(r);
    }
  }
  const readinessItems = inputs.failover.state === "ok" ? haReadinessItems(rawClusters, failoverRowsByRef) : null;
  const readinessByRef = new Map(
    (readinessItems ?? []).map((item) => [item.cluster.ref, item] as const),
  );

  // Detect policy parity differences across members of the same cluster when both report a policy name
  const clusterPolicyNames = new Map<string, Set<string>>();
  for (const d of activeDevices) {
    if (d.cluster_member_ref && d.policy_name?.trim()) {
      const set = clusterPolicyNames.get(d.cluster_member_ref) ?? new Set<string>();
      set.add(d.policy_name.trim());
      clusterPolicyNames.set(d.cluster_member_ref, set);
    }
  }

  const complianceAt = overview?.evidence?.compliance?.at ?? null;
  const backupEvidenceAt = overview?.evidence?.backup?.at ?? null;

  const healthByDevice = new Map<string, SystemHealthDevice>(
    inputs.health?.state === "ok" ? inputs.health.value.devices.map((h) => [h.device_id, h] as const) : [],
  );

  // Build every operational unit and evaluate its lenses
  const units: EstateUnit[] = activeDevices.map((d) => {
    const est = estateByDevice.get(d.device_id);
    const name = d.hostname?.trim() || est?.hostname?.trim() || d.device_id.slice(0, 8);
    const role = normalizeRole(d);
    const haRole = knownHaRole(d);
    const lastReadAt = d.inventory_collected_at ?? est?.last_read_at ?? d.platform_facts_observed_at ?? null;
    const ageBucket = ageBucketOf(lastReadAt, now);
    const newestBackup = newestBackupByDevice.get(d.device_id) ?? null;
    const lastBackupAt = newestBackup?.collected_at ?? est?.last_backup_at ?? null;
    const backupTarget =
      d.backup_target === true ||
      (d.backup_target === undefined && est?.backup_target !== false && hasBackupMethod(d));
    const noArchive =
      backupTarget &&
      (est ? est.no_archive : inputs.backups.state === "ok" ? newestBackup === null : false);
    const sizeAnomaly = sizeAnomalyByDevice.get(d.device_id) ?? null;
    const largeBackup = newestBackup
      ? largeBackupFlag({ vendor: d.vendor_hint, sizeBytes: newestBackup.size_bytes }, medians)
      : null;
    const ld = lifecycleByDevice.get(d.device_id) ?? null;

    // Lens 1: Failover
    const failoverAnswer: LensAnswer = (() => {
      if (!d.cluster_member_ref) {
        return makeAnswer("na", "Standalone unit (not in an HA cluster)", lastReadAt, "inventory");
      }
      if (inputs.failover.state === "restricted") {
        return makeAnswer("unk", "Readiness restricted for this account", null, "readiness");
      }
      if (inputs.failover.state !== "ok") {
        return makeAnswer("unk", "Readiness UNKNOWN (failover summary unavailable)", null, "readiness");
      }
      const item = readinessByRef.get(d.cluster_member_ref);
      if (!item) {
        return makeAnswer("unk", "Readiness UNKNOWN (cluster missing from the readiness rows)", null, "readiness", {
          badgeWord: "No evidence",
        });
      }
      if (item.status === "UNSUPPORTED") {
        // No readiness evidence exists for this vendor: that is UNKNOWN, never "not applicable".
        return makeAnswer("unk", "No readiness evidence (pre-checks not supported for this vendor)", null, "readiness", {
          badgeWord: "No evidence",
        });
      }
      const obsAt = item.base?.readiness?.observedAt ?? item.base?.readinessObservedAt ?? null;
      const reason = primaryReasonWord(item.base);
      if (item.status === "NOT_READY") {
        return makeAnswer("act", "Not ready to fail over", obsAt, "readiness", {
          badgeWord: "Not ready",
          detail: reason,
        });
      }
      if (item.status === "UNKNOWN") {
        return makeAnswer("unk", "Readiness UNKNOWN", obsAt, "readiness", {
          detail: reason,
        });
      }
      if (!obsAt) {
        return makeAnswer("unk", "Readiness timestamp UNKNOWN", null, "readiness");
      }
      if (isPastFresh(obsAt, now)) {
        return makeAnswer("watch", `Ready, checked ${relativeAge(obsAt, now)}`, obsAt, "readiness", {
          badgeWord: "Review",
          detail: "Evaluation is older than 24 h; re-check before relying on Ready",
        });
      }
      return makeAnswer("ok", "Ready to fail over", obsAt, "readiness", {
        badgeWord: "Ready",
        detail: reason,
      });
    })();

    // Lens 2: Backups
    const backupsAnswer: LensAnswer = (() => {
      if (d.backup_target === false || (!backupTarget && !newestBackup)) {
        return makeAnswer("na", "Not a backup target", backupEvidenceAt, "backup");
      }
      if (inputs.backups.state !== "ok" && !est) {
        return makeAnswer("unk", "Backup status UNKNOWN (store not read)", null, "backup");
      }
      const latestBackupFailed =
        d.latest_job_type === "backup" && d.latest_job_state?.toUpperCase() === "FAILED";
      if (noArchive || (inputs.backups.state === "ok" && !newestBackup && !lastBackupAt)) {
        return makeAnswer("act", "No stored backup archive", backupEvidenceAt, "backup", {
          detail: latestBackupFailed && d.latest_job_terminal_reason ? d.latest_job_terminal_reason : "Target has no archive in the store",
        });
      }
      if (latestBackupFailed) {
        return makeAnswer("act", "Latest backup attempt failed", lastBackupAt ?? backupEvidenceAt, "backup", {
          detail: d.latest_job_terminal_reason ?? "Newest stored archive is from an earlier run",
        });
      }
      const bkMs = parseTimeMs(lastBackupAt);
      if (bkMs !== null && nowMs - bkMs > BACKUP_STALE_DAYS * DAY_MS) {
        return makeAnswer("watch", `Newest archive older than ${BACKUP_STALE_DAYS} d`, lastBackupAt, "backup");
      }
      if (sizeAnomaly) {
        const pct = Math.round(Math.abs(sizeAnomaly.ratio - 1) * 100);
        return makeAnswer(
          "watch",
          `Backup size ${sizeAnomaly.direction === "shrink" ? "shrank" : "grew"} (${pct}%)`,
          sizeAnomaly.detected_at ?? lastBackupAt,
          "backup",
          { detail: sizeAnomaly.summary },
        );
      }
      if (largeBackup && newestBackup) {
        return makeAnswer(
          "watch",
          `Unusually large archive (${formatBytes(newestBackup.size_bytes)})`,
          lastBackupAt,
          "backup",
          { detail: `${largeBackup.factor.toFixed(1)}x vendor median (${formatBytes(largeBackup.medianBytes)})` },
        );
      }
      const valWord = newestBackup ? backupDisplayWord(newestBackup.validation_level) : null;
      const sizeWord = newestBackup ? formatBytes(newestBackup.size_bytes) : null;
      return makeAnswer(
        "ok",
        `Stored${valWord ? ` · ${valWord}` : ""}`,
        lastBackupAt ?? backupEvidenceAt,
        "backup",
        { detail: sizeWord ? `Latest archive ${sizeWord}` : null },
      );
    })();

    // Lens 3: Controls (compliance)
    const complianceAnswer: LensAnswer = (() => {
      if (role === "management_server" || est?.condition_reason === "no_critical_control") {
        return makeAnswer("na", "No applicable critical control set", complianceAt, "compliance");
      }
      if (inputs.overview.state !== "ok" || !overview?.estate) {
        return makeAnswer("unk", "Controls UNKNOWN (overview unavailable)", complianceAt, "compliance");
      }
      if (overview.estate.compliance_state !== "OK" || !est) {
        return makeAnswer("unk", "Controls not evaluated", complianceAt, "compliance");
      }
      if (est.condition === "not_assessed" || est.condition === "partly_assessed") {
        return makeAnswer(
          "unk",
          est.condition === "partly_assessed" ? "Controls partly evidenced (UNKNOWN)" : "Controls not evidenced",
          complianceAt,
          "compliance",
        );
      }
      const crit = est.critical_fail ?? 0;
      if (crit > 0) {
        return makeAnswer(
          "act",
          `${crit} critical ${crit === 1 ? "control fails" : "controls fail"}`,
          complianceAt,
          "compliance",
        );
      }
      if (est.condition === "ageing") {
        return makeAnswer("watch", "Evidenced critical controls pass on ageing read", complianceAt ?? lastReadAt, "compliance");
      }
      return makeAnswer("ok", "Evidenced critical controls pass", complianceAt, "compliance");
    })();

    // Lens 4: Exposure (lifecycle & advisories)
    const exposureAnswer: LensAnswer = (() => {
      if (inputs.lifecycle.state === "restricted" || inputs.lifecycle.state === "skipped") {
        return makeAnswer("unk", "Lifecycle restricted for this account", null, "lifecycle");
      }
      if (inputs.lifecycle.state !== "ok" || !ld) {
        return makeAnswer("unk", "Exposure UNKNOWN (lifecycle unavailable)", lifecycleAsOf, "lifecycle");
      }
      const vuln = ld.vulnerabilities;
      const vulnAt = vuln?.compared?.loaded_at ?? lifecycleAsOf;
      const lic = licenseState(ld);
      if (vuln?.state === "EVALUATED" && (vuln.affected_exploited ?? 0) > 0) {
        const n = vuln.affected_exploited ?? 1;
        return makeAnswer(
          "act",
          `${n} actively exploited ${n === 1 ? "advisory matches" : "advisories match"}`,
          vulnAt,
          "advisories",
          { detail: "Version match in CISA Known Exploited Vulnerabilities catalog" },
        );
      }
      if (ld.risk === "EXPIRED" || lic?.state === "expired") {
        return makeAnswer(
          "act",
          lic?.state === "expired" ? `Expired: ${lic.label}` : "Past end of support",
          lifecycleAsOf,
          "lifecycle",
        );
      }
      if (vuln?.state === "EVALUATED" && (vuln.affected_critical_high ?? 0) > 0) {
        const n = vuln.affected_critical_high ?? 1;
        return makeAnswer(
          "watch",
          `${n} critical/high ${n === 1 ? "advisory matches" : "advisories match"}`,
          vulnAt,
          "advisories",
        );
      }
      if (lic?.state === "soon" || ld.risk === "HIGH") {
        return makeAnswer(
          "watch",
          lic?.state === "soon"
            ? `${lic.label} expires${lic.days !== null ? ` in ${lic.days} d` : " soon"}`
            : "Support milestone approaching",
          lifecycleAsOf,
          "lifecycle",
        );
      }
      if (vuln && vuln.state !== "EVALUATED") {
        return makeAnswer(
          "unk",
          `Advisories UNKNOWN (${vuln.state.replace(/_/g, " ").toLowerCase()})`,
          vulnAt,
          "advisories",
        );
      }
      if (ld.risk === "UNKNOWN" || ld.risk === "NOT_EVALUATED") {
        return makeAnswer("unk", "Lifecycle UNKNOWN (not evaluated)", lifecycleAsOf, "lifecycle");
      }
      if (vuln?.state === "EVALUATED" && (vuln.affected ?? 0) > 0) {
        return makeAnswer("watch", `${vuln.affected} advisory ${vuln.affected === 1 ? "match" : "matches"}`, vulnAt, "advisories");
      }
      return makeAnswer(
        "ok",
        vuln?.state === "EVALUATED" ? "No matching advisories; supported" : "Supported",
        vulnAt,
        vuln?.state === "EVALUATED" ? "advisories" : "lifecycle",
      );
    })();

    // Lens 5: Installed policy
    const installAnswer: LensAnswer = (() => {
      if (role !== "gateway") {
        return makeAnswer("na", "Not a policy-enforcing gateway", lastReadAt, "inventory");
      }
      const polAt = d.policy_read_at ?? overview?.policy_install?.last_read_at ?? d.inventory_collected_at ?? null;
      const peerSet = d.cluster_member_ref ? clusterPolicyNames.get(d.cluster_member_ref) : undefined;
      if (d.policy_name || d.policy_installed_at || d.policy_installed_at_text) {
        if (peerSet && peerSet.size > 1) {
          return makeAnswer(
            "watch",
            `Policy ${d.policy_name ?? "reported"} differs between cluster members`,
            polAt,
            "policy read",
          );
        }
        const when = d.policy_installed_at
          ? relativeAge(d.policy_installed_at, now)
          : d.policy_installed_at_text ?? "reported";
        return makeAnswer(
          "ok",
          `${d.policy_name ? `${d.policy_name} · ` : ""}installed ${when}`,
          polAt,
          "policy read",
        );
      }
      return makeAnswer("unk", "Installed policy UNKNOWN (not read yet)", polAt, "policy read");
    })();

    // Lens: Device health (snapshot verdict; not part of Worst of all)
    const healthAnswer = healthLensAnswer({ source: inputs.health, device: healthByDevice.get(d.device_id) ?? null, now });

    // Lens 6: Evidence age
    const ageAnswer: LensAnswer = (() => {
      if (ageBucket === "never" || !lastReadAt) {
        return makeAnswer("unk", "Never read", null, "inventory");
      }
      const rel = relativeAge(lastReadAt, now);
      if (ageBucket === "fresh") {
        return makeAnswer("fresh", `Read ${rel}`, lastReadAt, "inventory", { badgeWord: "Under 6 h" });
      }
      if (ageBucket === "day") {
        return makeAnswer("day", `Read ${rel}`, lastReadAt, "inventory", { badgeWord: "6–24 h" });
      }
      if (ageBucket === "old") {
        return makeAnswer("old", `Read ${rel} (24–72 h)`, lastReadAt, "inventory", { badgeWord: "24–72 h" });
      }
      return makeAnswer("stale", `Read ${rel}: stale (> 72 h)`, lastReadAt, "inventory", { badgeWord: "Stale" });
    })();

    // Lens 0: Worst of all
    const coreAnswers: ReadonlyArray<[string, LensAnswer]> = [
      ["Failover", failoverAnswer],
      ["Backups", backupsAnswer],
      ["Controls", complianceAnswer],
      ["Exposure", exposureAnswer],
      ["Evidence age", ageAnswer],
    ];
    const worstAnswer: LensAnswer = (() => {
      if (d.enrollment_state === "UNREACHABLE" || d.enrollment_state === "DEGRADED") {
        return makeAnswer(
          d.enrollment_state === "UNREACHABLE" ? "act" : "watch",
          `Device ${d.enrollment_state.toLowerCase()} at last contact`,
          lastReadAt,
          "inventory",
        );
      }
      const acts = coreAnswers.filter(([, a]) => a.badgeState === "act");
      if (acts.length > 0) {
        const [firstLabel, first] = acts[0];
        return makeAnswer(
          "act",
          acts.length === 1 ? `${firstLabel}: ${first.summary}` : `Needs action on ${acts.length} lenses (${firstLabel} first)`,
          first.evidenceAt ?? lastReadAt,
          first.evidenceSource,
        );
      }
      const watches = coreAnswers.filter(([, a]) => a.badgeState === "watch");
      if (watches.length > 0) {
        const [firstLabel, first] = watches[0];
        return makeAnswer(
          "watch",
          watches.length === 1 ? `${firstLabel}: ${first.summary}` : `Review on ${watches.length} lenses (${firstLabel} first)`,
          first.evidenceAt ?? lastReadAt,
          first.evidenceSource,
        );
      }
      const unks = coreAnswers.filter(([, a]) => a.badgeState === "unk");
      if (unks.length > 0) {
        const [firstLabel, first] = unks[0];
        return makeAnswer(
          "unk",
          unks.length === 1 ? `${firstLabel} is UNKNOWN` : `${unks.length} lenses UNKNOWN`,
          first.evidenceAt ?? lastReadAt,
          first.evidenceSource,
        );
      }
      return makeAnswer("ok", "Every evaluated lens fine", lastReadAt, "inventory");
    })();

    return {
      deviceId: d.device_id,
      name,
      vendor: d.vendor_hint,
      vendorName: vendorDisplayName(d.vendor_hint),
      model: d.model ?? est?.model ?? d.platform_family ?? null,
      version: d.software_version ?? null,
      hotfix: d.hotfix_level ?? null,
      role,
      haRole,
      clusterRef: d.cluster_member_ref ?? null,
      enrollmentState: d.enrollment_state,
      managementIp: d.management_ip ?? null,
      lastReadAt,
      ageBucket,
      platformFactsAt: d.platform_facts_observed_at ?? null,
      platformFactsSource: d.platform_facts_source ?? null,
      policyName: d.policy_name ?? null,
      policyInstalledAt: d.policy_installed_at ?? null,
      policyInstalledText: d.policy_installed_at_text ?? null,
      policyReadAt: d.policy_read_at ?? null,
      backupTarget,
      newestBackup,
      lastBackupAt,
      noArchive,
      sizeAnomaly,
      largeBackup,
      estateCondition: est?.condition ?? null,
      estateConditionReason: est?.condition_reason ?? null,
      criticalFail: est?.critical_fail ?? null,
      lifecycle: ld,
      identityCheck: d.identity_check ?? null,
      latestJobState: d.latest_job_state ?? null,
      latestJobType: d.latest_job_type ?? null,
      latestJobReason: d.latest_job_terminal_reason ?? null,
      lenses: {
        worst: worstAnswer,
        failover: failoverAnswer,
        backups: backupsAnswer,
        compliance: complianceAnswer,
        exposure: exposureAnswer,
        install: installAnswer,
        health: healthAnswer,
        age: ageAnswer,
      },
    };
  });

  const byId = new Map(units.map((u) => [u.deviceId, u] as const));
  const byName = new Map(units.map((u) => [u.name, u] as const));

  // Build joined HA clusters
  const clusters: EstateCluster[] = rawClusters
    .map((cl) => {
      const orderedRaw = orderMembers(cl.members);
      const members = orderedRaw.map((m) => byId.get(m.device_id)).filter((u): u is EstateUnit => Boolean(u));
      const vendor = members[0]?.vendor ?? cl.members[0]?.vendor_hint ?? "unknown";
      const item = readinessByRef.get(cl.ref);
      const failoverStatus: EstateCluster["failoverStatus"] =
        inputs.failover.state !== "ok" ? "UNKNOWN" : item?.status ?? "UNKNOWN";
      const observedAt = item?.base?.readiness?.observedAt ?? item?.base?.readinessObservedAt ?? null;
      const stale = Boolean(observedAt && isPastFresh(observedAt, now));
      const pairState: BadgeState =
        failoverStatus === "NOT_READY"
          ? "act"
          : failoverStatus === "UNSUPPORTED" || failoverStatus === "UNKNOWN" || !observedAt
              ? "unk"
              : stale
                ? "watch"
                : "ok";
      const reason =
        inputs.failover.state !== "ok"
          ? "Readiness summary unavailable"
          : primaryReasonWord(item?.base);
      return {
        ref: cl.ref,
        title: cl.title,
        vendor,
        vendorName: vendorDisplayName(vendor),
        mode: item?.base?.mode && item.base.mode !== "UNKNOWN" ? clusterModeWord(item.base.mode) : null,
        members,
        failoverStatus,
        pairState,
        reason,
        observedAt,
        stale,
        checks: item?.base?.readiness?.checks ?? [],
      };
    })
    .sort((a, b) => a.title.localeCompare(b.title));

  const clustersByRef = new Map(clusters.map((c) => [c.ref, c] as const));

  // Group units into vendor wall sections (Check Point, Palo Alto, Cisco ASA, Fortinet, Other appliances)
  const PRIMARY_VENDOR_GROUPS: ReadonlyArray<readonly [string, string]> = [
    ["check_point", "Check Point"],
    ["palo_alto", "Palo Alto"],
    ["cisco_asa", "Cisco ASA"],
    ["fortinet", "Fortinet"],
  ];
  const primarySet = new Set(PRIMARY_VENDOR_GROUPS.map(([k]) => k));

  const buildGroup = (key: string, title: string, match: (v: string) => boolean): VendorWallGroup | null => {
    const groupClustersList = clusters.filter((c) => match(c.vendor));
    const nonCluster = units.filter((u) => match(u.vendor) && !u.clusterRef);
    const standaloneGateways = nonCluster.filter((u) => u.role === "gateway").sort((a, b) => a.name.localeCompare(b.name));
    const appliances = nonCluster.filter((u) => u.role === "appliance").sort((a, b) => a.name.localeCompare(b.name));
    const managers = nonCluster.filter((u) => u.role === "management_server").sort((a, b) => a.name.localeCompare(b.name));
    const clusterUnits = groupClustersList.flatMap((c) => c.members);
    const allUnits = [...clusterUnits, ...standaloneGateways, ...appliances, ...managers];
    if (allUnits.length === 0) return null;
    return {
      key,
      title,
      clusters: groupClustersList,
      standaloneGateways,
      appliances,
      managers,
      unitCount: allUnits.length,
      newestReadAt: newestIso(allUnits.map((u) => u.lastReadAt)),
    };
  };

  const vendorGroups: VendorWallGroup[] = [];
  for (const [key, label] of PRIMARY_VENDOR_GROUPS) {
    const grp = buildGroup(key, label, (v) => v === key);
    if (grp) vendorGroups.push(grp);
  }
  const otherVendors = [...new Set(units.map((u) => u.vendor).filter((v) => !primarySet.has(v)))].sort();
  if (otherVendors.length > 0) {
    const otherTitle = otherVendors.map((v) => vendorLabel(v)).join(" · ");
    const grp = buildGroup("other", otherTitle, (v) => !primarySet.has(v));
    if (grp) vendorGroups.push(grp);
  }

  // Freshness breakdown
  const freshness: EstateFreshnessBreakdown = {
    fresh: units.filter((u) => u.ageBucket === "fresh").length,
    day: units.filter((u) => u.ageBucket === "day").length,
    old: units.filter((u) => u.ageBucket === "old").length,
    stale: units.filter((u) => u.ageBucket === "stale").length,
    never: units.filter((u) => u.ageBucket === "never").length,
    total: units.length,
  };

  const haCounts = readinessItems
    ? {
        ...readinessCounts(readinessItems),
        readyStale: clusters.filter((c) => c.failoverStatus === "READY" && c.stale).length,
      }
    : null;

  // Attention rows (reusing the product's canonical attentionRules.ts)
  const attention = overview
    ? buildAttention(
        {
          now,
          overview,
          failover:
            inputs.failover.state === "ok"
              ? { state: "ok", value: { devices: activeDevices, rows: inputs.failover.value } }
              : inputs.failover,
          lifecycle: inputs.lifecycle,
          changes: inputs.changes ?? { state: "skipped" },
          controls: inputs.controls?.state === "ok" ? inputs.controls.value : null,
        },
        inputs.permissions,
      )
    : null;

  // Checked and fine (absence stated explicitly alongside population & UNKNOWN counts)
  const checkedFine: CheckedFineRow[] = [];
  if (overview) {
    const fj = overview.attention?.failed_jobs_24h;
    if (fj?.state === "OK" && fj.count === 0) {
      checkedFine.push({
        key: "jobs-24h",
        label: `${PRODUCT_NAME} failed jobs, last 24 h`,
        figure: "0",
        context: `of ${fj.terminal_24h ?? 0} finished jobs`,
        unknownNote: fj.other_terminal_24h ? `${fj.other_terminal_24h} outcome UNKNOWN / rejected` : null,
        evidenceAt: overview.evidence?.jobs?.at ?? overview.generated_at,
        evidenceSource: "jobs",
      });
    }
    const bm = overview.attention?.backup_missing;
    if (bm?.state === "OK" && bm.count === 0) {
      checkedFine.push({
        key: "backups-complete",
        label: "Backup targets without a stored archive",
        figure: "0",
        context: `all ${bm.of ?? 0} enrolled backup targets hold an archive`,
        unknownNote: null,
        evidenceAt: overview.evidence?.backup?.at ?? overview.generated_at,
        evidenceSource: "backup",
      });
    }
    if (haCounts && haCounts.total > 0 && haCounts.notReady === 0) {
      checkedFine.push({
        key: "ha-ready",
        label: "HA clusters failing pre-checks",
        figure: "0",
        context: `${haCounts.ready} of ${haCounts.total} ready`,
        unknownNote: haCounts.unknown > 0 ? `${haCounts.unknown} UNKNOWN` : null,
        evidenceAt: newestIso(clusters.map((c) => c.observedAt)),
        evidenceSource: "readiness",
      });
    }
    if (inputs.lifecycle.state === "ok") {
      const lds = inputs.lifecycle.value.devices.filter((d) => byId.has(d.device_id));
      const evals = lds.filter((d) => d.vulnerabilities?.state === "EVALUATED");
      const exploited = evals.filter((d) => (d.vulnerabilities?.affected_exploited ?? 0) > 0);
      const unjudged = lds.length - evals.length;
      if (evals.length > 0 && exploited.length === 0) {
        checkedFine.push({
          key: "kev-zero",
          label: "Actively exploited advisories matched",
          figure: "0",
          context: `${evals.length} units evaluated`,
          unknownNote: unjudged > 0 ? `${unjudged} not evaluated` : null,
          evidenceAt: newestIso(evals.map((d) => d.vulnerabilities?.compared?.loaded_at)) ?? lifecycleAsOf,
          evidenceSource: "advisories",
        });
      }
    }
    const pi = overview.policy_install;
    if (pi?.state === "OK" && (pi.of ?? 0) > 0) {
      const evidenced = (pi.today ?? 0) + (pi.yesterday ?? 0) + (pi.days_2_7 ?? 0) + (pi.older ?? 0);
      const unk = pi.unknown ?? 0;
      checkedFine.push({
        key: "policy-install",
        label: "Gateways reporting an installed policy",
        figure: String(evidenced),
        context: `of ${pi.of ?? 0} active gateways`,
        unknownNote: unk > 0 ? `${unk} UNKNOWN (not read yet)` : null,
        evidenceAt: pi.last_read_at ?? overview.generated_at,
        evidenceSource: "policy read",
      });
    }
  }

  const allReadTimes = units.map((u) => u.lastReadAt).filter((t): t is string => Boolean(t));

  return {
    now,
    units,
    byId,
    byName,
    clusters,
    clustersByRef,
    vendorGroups,
    freshness,
    haCounts,
    attention,
    checkedFine,
    newestEvidenceAt: newestIso([...allReadTimes, overview?.generated_at]),
    oldestEvidenceAt: oldestIso(allReadTimes),
    draftCount,
  };
}

export function countLensStates(units: readonly EstateUnit[], lens: EstateLens): Record<TileState, number> {
  const counts: Record<TileState, number> = {
    act: 0,
    watch: 0,
    ok: 0,
    unk: 0,
    na: 0,
    fresh: 0,
    day: 0,
    old: 0,
    stale: 0,
  };
  for (const u of units) {
    counts[u.lenses[lens].tileState]++;
  }
  return counts;
}

/** Badge count shown on the lens bar pill: `act` (or `stale` + `unk` for Evidence age). */
export function lensProblemCount(units: readonly EstateUnit[], lens: EstateLens): number {
  if (lens === "age") {
    return units.filter((u) => u.ageBucket === "stale" || u.ageBucket === "never").length;
  }
  if (lens === "install") {
    return units.filter((u) => {
      const s = u.lenses.install.badgeState;
      return s === "act" || s === "watch" || s === "unk";
    }).length;
  }
  return units.filter((u) => {
    const s = u.lenses[lens].badgeState;
    return s === "act" || s === "unk";
  }).length;
}

export function sortUnitsForLens(
  units: readonly EstateUnit[],
  lens: EstateLens,
  options: { readonly onlyAttention: boolean; readonly query: string; readonly vendor: string },
): EstateUnit[] {
  const q = options.query.trim().toUpperCase();
  return units
    .filter((u) => {
      if (options.vendor && u.vendor !== options.vendor) return false;
      if (options.onlyAttention && !needsAttentionOnLens(u.lenses[lens].tileState)) return false;
      if (!q) return true;
      return (
        u.name.toUpperCase().includes(q) ||
        (u.clusterRef ?? "").toUpperCase().includes(q) ||
        (u.model ?? "").toUpperCase().includes(q) ||
        (u.version ?? "").toUpperCase().includes(q) ||
        u.vendorName.toUpperCase().includes(q)
      );
    })
    .sort(
      (a, b) =>
        TILE_STATE_RANK[a.lenses[lens].tileState] - TILE_STATE_RANK[b.lenses[lens].tileState] ||
        a.name.localeCompare(b.name),
    );
}

const WATCHLIST_KEY = "nx.estate.watchlist";

export function readEstateWatchlist(): string[] {
  try {
    const raw = window.localStorage.getItem(WATCHLIST_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export function toggleEstateWatchlist(id: string): string[] {
  const current = readEstateWatchlist();
  const next = current.includes(id) ? current.filter((x) => x !== id) : [...current, id];
  try {
    window.localStorage.setItem(WATCHLIST_KEY, JSON.stringify(next));
  } catch {
    // preference only
  }
  return next;
}

export { ESTATE_LENSES };
