import type {
  BackupDeviations,
  BackupMajorDeviation,
  BackupSizeAnomaly,
  BackupStoreQuotaRow,
  BackupStoreQuotas,
} from "../auth/adminApi";
import {
  categoryWord,
  changeWords,
  groupSimultaneousDeviations,
  type DeviationLine,
} from "../screens/BackupDeviationCard";
import { bytesWords, quotaSummary } from "../screens/BackupStoreQuotaCard";
import type { SourceRead } from "../screens/overview/attentionRules";
import { plural } from "../shell/plural";
import { newestIso, type BadgeState } from "./estateModel";

export const BACKUP_MAJOR_DEVIATION_CAP = 10;
export const BACKUP_SIZE_ANOMALY_CAP = 10;
export const BACKUP_STORE_QUOTA_CAP = 8;

export type EstateReadState<T> =
  | SourceRead<T>
  | { readonly state: "loading" };

export interface BackupSizeAnomalyItem extends Omit<BackupSizeAnomaly, "plaintext_bytes" | "median_bytes" | "ratio"> {
  readonly status?: string;
  readonly plaintext_bytes?: number;
  readonly median_bytes?: number;
  readonly ratio?: number;
  readonly ratio_percent?: number;
  readonly baseline_bytes?: number;
  readonly current_bytes?: number;
}

export interface BackupDeviationOverview extends Omit<BackupDeviations, "size_anomalies"> {
  readonly size_anomalies?: readonly BackupSizeAnomalyItem[];
}

export interface BackupStoreQuotaOverview extends Omit<BackupStoreQuotas, "stores"> {
  readonly stores?: ReadonlyArray<BackupStoreQuotaRow>;
  readonly rows?: ReadonlyArray<BackupStoreQuotaRow>;
  readonly reason?: string | null;
}

export interface ProjectedBadge {
  readonly state: BadgeState;
  readonly word: string;
}

export interface ProjectedMajorDeviationCategory {
  readonly category: string;
  readonly label: string;
  readonly sections: readonly string[];
  readonly added?: number;
  readonly modified?: number;
  readonly removed?: number;
}

export interface ProjectedMajorDeviationGroup {
  readonly key: string;
  readonly deviceId: string;
  readonly hostname: string;
  readonly vendor: string;
  readonly changedAt: string;
  readonly categories: readonly ProjectedMajorDeviationCategory[];
  readonly sections: readonly string[];
  readonly changeSummary: string;
  readonly devices: readonly DeviationLine[];
}

export interface ProjectedSizeAnomaly {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly cluster_member_ref?: string | null;
  readonly deviation_id: string;
  readonly artefact_id: string;
  readonly last_good_artefact_id: string;
  readonly direction: string;
  readonly plaintext_bytes: number;
  readonly median_bytes: number;
  readonly baseline_bytes: number;
  readonly current_bytes: number;
  readonly ratio: number;
  readonly ratio_percent: number;
  readonly summary: string;
  readonly detected_at: string;
  readonly open_copies: number;
  readonly status: string;
}

export interface ProjectedBackupDeviations {
  readonly badge: ProjectedBadge;
  readonly coverageText: string;
  readonly majorTotal: number;
  readonly majorShown: readonly ProjectedMajorDeviationGroup[];
  readonly anomaliesTotal: number;
  readonly anomaliesShown: readonly ProjectedSizeAnomaly[];
  readonly newestEvidenceAt: string | null;
}

export interface ProjectedStoreQuotaRow {
  readonly store: string;
  readonly used_bytes: number | null;
  readonly quota_bytes: number | null;
  readonly percent: number | null;
  readonly near_limit: boolean;
  readonly quota_exceeded: boolean;
  readonly usageText: string;
  readonly percentText: string | null;
  readonly rowBadge: ProjectedBadge;
}

export interface ProjectedBackupStoreQuotas {
  readonly badge: ProjectedBadge;
  readonly summaryText: string;
  readonly rowsShown: readonly ProjectedStoreQuotaRow[];
  readonly rowsTotal: number;
}

function minuteKey(at: string): string {
  const t = new Date(at);
  return Number.isNaN(t.getTime()) ? at : t.toISOString().slice(0, 16);
}

/**
 * Groups active major configuration deviations per (device, timestamp minute) and projects
 * category badges + changeWords using the shared BackupDeviationCard helpers.
 */
function buildMajorDeviationGroups(
  rows: readonly BackupMajorDeviation[],
): readonly ProjectedMajorDeviationGroup[] {
  const byDeviceAndMinute = new Map<
    string,
    {
      deviceId: string;
      hostname: string;
      vendor: string;
      changedAt: string;
      categoriesMap: Map<string, { category: string; label: string; sections: string[] }>;
      sections: string[];
    }
  >();

  for (const row of rows) {
    const bucketKey = `${row.device_id}#${minuteKey(row.changed_at)}`;
    let entry = byDeviceAndMinute.get(bucketKey);
    if (!entry) {
      entry = {
        deviceId: row.device_id,
        hostname: row.hostname ?? row.device_id,
        vendor: row.vendor,
        changedAt: row.changed_at,
        categoriesMap: new Map(),
        sections: [],
      };
      byDeviceAndMinute.set(bucketKey, entry);
    }
    if (row.changed_at > entry.changedAt) {
      entry.changedAt = row.changed_at;
    }
    const label = categoryWord(row.category);
    let catEntry = entry.categoriesMap.get(label);
    if (!catEntry) {
      catEntry = { category: row.category, label, sections: [] };
      entry.categoriesMap.set(label, catEntry);
    }
    for (const s of row.sections ?? []) {
      if (!catEntry.sections.includes(s)) catEntry.sections.push(s);
      if (!entry.sections.includes(s)) entry.sections.push(s);
    }
  }

  const ordered = Array.from(byDeviceAndMinute.values()).sort((a, b) =>
    a.changedAt < b.changedAt
      ? 1
      : a.changedAt > b.changedAt
        ? -1
        : a.hostname.localeCompare(b.hostname),
  );

  return ordered.map((entry) => {
    const categories = Array.from(entry.categoriesMap.values());
    const line: DeviationLine = {
      deviceId: entry.deviceId,
      name: entry.hostname,
      vendor: entry.vendor,
      changedAt: entry.changedAt,
      categories: categories.map((c) => c.label),
      sections: entry.sections,
    };
    const simul = groupSimultaneousDeviations([line]);
    const groupKey = `${entry.deviceId}#${simul[0]?.key ?? entry.changedAt}`;
    return {
      key: groupKey,
      deviceId: entry.deviceId,
      hostname: entry.hostname,
      vendor: entry.vendor,
      changedAt: entry.changedAt,
      categories,
      sections: entry.sections,
      changeSummary: changeWords(line),
      devices: simul[0]?.devices ?? [line],
    };
  });
}

function normalizeSizeAnomaly(s: BackupSizeAnomalyItem): ProjectedSizeAnomaly {
  const baseline = s.baseline_bytes ?? s.median_bytes ?? 0;
  const current = s.current_bytes ?? s.plaintext_bytes ?? 0;
  const ratio =
    typeof s.ratio === "number"
      ? s.ratio
      : typeof s.ratio_percent === "number"
        ? s.ratio_percent / 100
        : baseline > 0
          ? current / baseline
          : 1;
  const ratioPercent =
    typeof s.ratio_percent === "number" ? s.ratio_percent : Math.round(ratio * 100);

  return {
    device_id: s.device_id,
    hostname: s.hostname ?? null,
    cluster_member_ref: s.cluster_member_ref,
    deviation_id: s.deviation_id ?? `anom-${s.device_id}`,
    artefact_id: s.artefact_id ?? "",
    last_good_artefact_id: s.last_good_artefact_id ?? "",
    direction: String(s.direction ?? "growth").toLowerCase(),
    plaintext_bytes: current,
    median_bytes: baseline,
    baseline_bytes: baseline,
    current_bytes: current,
    ratio,
    ratio_percent: ratioPercent,
    summary: s.summary ?? "",
    detected_at: s.detected_at,
    open_copies: s.open_copies ?? 1,
    status: s.status ?? "OPEN",
  };
}

export function projectBackupDeviations(
  deviations: BackupDeviationOverview | null,
  backupsState: EstateReadState<unknown>["state"],
): ProjectedBackupDeviations {
  if (backupsState === "restricted") {
    return {
      badge: { state: "na", word: "Restricted" },
      coverageText: "Restricted for this role.",
      majorTotal: 0,
      majorShown: [],
      anomaliesTotal: 0,
      anomaliesShown: [],
      newestEvidenceAt: null,
    };
  }

  if (backupsState !== "ok" || !deviations) {
    return {
      badge: { state: "unk", word: "UNKNOWN" },
      coverageText: "Deviation comparison unavailable — UNKNOWN (not assumed clear).",
      majorTotal: 0,
      majorShown: [],
      anomaliesTotal: 0,
      anomaliesShown: [],
      newestEvidenceAt: null,
    };
  }

  const openAnomalies = (deviations.size_anomalies ?? [])
    .filter((s) => s.status === undefined || s.status === "OPEN")
    .map(normalizeSizeAnomaly);
  const groupedMajor = buildMajorDeviationGroups(deviations.active_major_deviations ?? []);
  const noneChecked =
    deviations.total_deviations_checked === 0 &&
    openAnomalies.length === 0 &&
    groupedMajor.length === 0;

  const badge: ProjectedBadge = noneChecked
    ? { state: "unk", word: "UNKNOWN" }
    : groupedMajor.length > 0 || openAnomalies.length > 0
      ? {
          state: "watch",
          word: `${plural(groupedMajor.length, "major deviation group", "major deviation groups")} · ${plural(openAnomalies.length, "size anomaly", "size anomalies")}`,
        }
      : { state: "ok", word: "No active deviations" };

  const windowDays = deviations.window_days ?? 7;
  const coverageText =
    typeof deviations.devices_known === "number"
      ? `${deviations.total_deviations_checked} of ${plural(deviations.devices_known, "device", "devices")} compared · last ${plural(windowDays, "day", "days")}`
      : `${plural(deviations.total_deviations_checked, "device", "devices")} compared · last ${plural(windowDays, "day", "days")}`;

  const newestEvidenceAt = newestIso([
    ...groupedMajor.map((g) => g.changedAt),
    ...openAnomalies.map((a) => a.detected_at),
  ]);

  return {
    badge,
    coverageText,
    majorTotal: groupedMajor.length,
    majorShown: groupedMajor.slice(0, BACKUP_MAJOR_DEVIATION_CAP),
    anomaliesTotal: openAnomalies.length,
    anomaliesShown: openAnomalies.slice(0, BACKUP_SIZE_ANOMALY_CAP),
    newestEvidenceAt,
  };
}

export function projectBackupStoreQuotas(
  quotas: EstateReadState<BackupStoreQuotaOverview>,
): ProjectedBackupStoreQuotas {
  if (quotas.state === "restricted") {
    return {
      badge: { state: "na", word: "Restricted" },
      summaryText: "Restricted for this role.",
      rowsShown: [],
      rowsTotal: 0,
    };
  }

  if (quotas.state !== "ok" || !quotas.value.store_mounted) {
    return {
      badge: { state: "unk", word: "UNKNOWN" },
      summaryText:
        quotas.state === "ok"
          ? (quotas.value.reason ?? quotas.value.note ?? "Backup store is not mounted — UNKNOWN.")
          : "Quota read unavailable — UNKNOWN.",
      rowsShown: [],
      rowsTotal: 0,
    };
  }

  const rawRows = quotas.value.rows ?? quotas.value.stores ?? [];
  const s = quotaSummary(rawRows);
  const unreadable = rawRows.filter((r) => r.used_bytes === null).length;

  const badge: ProjectedBadge =
    s.exceeded > 0
      ? { state: "act", word: `${plural(s.exceeded, "store", "stores")} over quota` }
      : s.near > 0
        ? { state: "watch", word: `${plural(s.near, "store", "stores")} near limit` }
        : rawRows.length === 0 || unreadable > 0
          ? { state: "unk", word: "UNKNOWN" }
          : s.withQuota > 0
            ? { state: "ok", word: "Within quota" }
            : { state: "na", word: "No quota configured" };

  const summaryText = `${s.withQuota} of ${plural(rawRows.length, "store", "stores")} with a quota · ${plural(s.exceeded, "store", "stores")} over quota · ${plural(s.near, "store", "stores")} near limit`;

  const rowsShown: ProjectedStoreQuotaRow[] = rawRows
    .slice(0, BACKUP_STORE_QUOTA_CAP)
    .map((r) => {
      const rowBadge: ProjectedBadge =
        r.used_bytes === null
          ? { state: "unk", word: "UNKNOWN" }
          : r.quota_exceeded
            ? { state: "act", word: "Over quota" }
            : r.near_limit
              ? { state: "watch", word: "Near limit" }
              : r.quota_bytes !== null
                ? { state: "ok", word: "Within quota" }
                : { state: "na", word: "No quota" };

      return {
        store: r.store,
        used_bytes: r.used_bytes,
        quota_bytes: r.quota_bytes,
        percent: r.percent,
        near_limit: r.near_limit,
        quota_exceeded: r.quota_exceeded,
        usageText: `${bytesWords(r.used_bytes)} used / ${r.quota_bytes === null ? "no quota" : bytesWords(r.quota_bytes)}`,
        percentText: r.percent !== null && r.percent !== undefined ? `${r.percent}%` : null,
        rowBadge,
      };
    });

  return {
    badge,
    summaryText,
    rowsShown,
    rowsTotal: rawRows.length,
  };
}
