import type { PolicyHygieneSummary, PolicyHygieneSummaryRow, PolicyMetadata } from "../auth/adminApi";
import { oldestIso, parseTimeMs, type BadgeState } from "./estateModel";
import { capList } from "./estatePolicyBrowserModel";
import { plural } from "../shell/plural";
import { formatStamp } from "../shell/time";

/** Container rows shown in the dossier block before the list says "+N more". */
export const DOSSIER_HYGIENE_ROW_CAP = 5;

export type DossierHygieneRead<T> = { readonly ok: true; readonly value: T } | { readonly ok: false };

export interface DossierHygieneCount {
  readonly finding: string;
  readonly rules: number;
}

export type DossierHygieneRow =
  | {
      readonly kind: "ready";
      readonly key: string;
      readonly label: string;
      /** Rules in this container (the container's own rule total, not a package total). */
      readonly rules: number;
      readonly counts: readonly DossierHygieneCount[];
      readonly snapshotAt: string | null;
      readonly stamp: string;
    }
  | {
      readonly kind: "pending";
      readonly key: string;
      readonly label: string;
      readonly snapshotAt: string | null;
      readonly stamp: string;
    }
  | {
      readonly kind: "unknown";
      readonly key: string;
      readonly label: string;
      readonly snapshotAt: string | null;
      readonly stamp: string;
    };

export interface DossierHygieneModel {
  /** "na" when the unit owns no policy container: a not-applicable line, never a pass. */
  readonly badge: BadgeState;
  readonly badgeWord: string;
  readonly headline: string;
  /** What the per-class numbers count, and over which containers. */
  readonly countsLine: string;
  readonly pendingLine: string | null;
  readonly totals: readonly DossierHygieneCount[];
  readonly rows: readonly DossierHygieneRow[];
  readonly totalContainers: number;
  readonly evidenceAt: string | null;
  readonly moreLabel: string | null;
  readonly notApplicable: boolean;
}

function rowLabel(r: PolicyHygieneSummaryRow): string {
  return `${r.containerName} · ${r.sourceName} (${r.vendor})`;
}

function validSnapshotAt(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  if (!trimmed || parseTimeMs(trimmed) === null) return null;
  return raw;
}

function unknownModel(headline: string, countsLine: string): DossierHygieneModel {
  return {
    badge: "unk",
    badgeWord: "UNKNOWN",
    headline,
    countsLine,
    pendingLine: null,
    totals: [],
    rows: [],
    totalContainers: 0,
    evidenceAt: null,
    moreLabel: null,
    notApplicable: false,
  };
}

/**
 * Pure model for the dossier "Policy hygiene" block. A unit's containers are the stored policy containers whose
 * targets name this unit's device id, joined by ids (sourceId + containerId) to the fleet hygiene summary. A
 * failed or malformed read is UNKNOWN, never OK or zero. A PENDING container has no counts and is never shown as
 * zero. A unit with no policy container is not applicable.
 */
export function buildDossierHygieneModel(input: {
  readonly deviceId: string;
  readonly policies: DossierHygieneRead<readonly PolicyMetadata[]>;
  readonly summary: DossierHygieneRead<PolicyHygieneSummary>;
  readonly cap?: number;
}): DossierHygieneModel {
  if (!input.policies.ok || !Array.isArray(input.policies.value)) {
    return unknownModel(
      "Policy containers for this unit could not be read",
      "UNKNOWN: the policy container list could not be read. Not shown as OK.",
    );
  }
  if (!input.summary.ok || !input.summary.value || !Array.isArray(input.summary.value.containers)) {
    return unknownModel(
      "Hygiene summary could not be read",
      "UNKNOWN: the fleet hygiene summary could not be read. Not shown as OK or zero.",
    );
  }

  const allPolicies: readonly PolicyMetadata[] = input.policies.value;
  const owned = allPolicies.filter(
    (p): boolean => Array.isArray(p?.targets) && p.targets.some((t) => t?.deviceId === input.deviceId),
  );
  if (owned.length === 0) {
    return {
      badge: "na",
      badgeWord: "Not applicable",
      headline: "Not applicable: this unit has no stored policy container",
      countsLine: "Management and appliance units do not report gateway policy packages, so there is nothing to count here.",
      pendingLine: null,
      totals: [],
      rows: [],
      totalContainers: 0,
      evidenceAt: null,
      moreLabel: null,
      notApplicable: true,
    };
  }

  const ownedKeys = new Set(owned.map((p) => `${p.sourceId}:${p.containerId}`));
  const summaryByKey = new Map<string, PolicyHygieneSummaryRow>();
  for (const r of input.summary.value.containers) {
    if (r) summaryByKey.set(`${r.sourceId}:${r.containerId}`, r);
  }

  const all: DossierHygieneRow[] = [...ownedKeys].map((key): DossierHygieneRow => {
    const r = summaryByKey.get(key);
    if (!r) {
      return { kind: "unknown", key, label: "Container without a hygiene row", snapshotAt: null, stamp: "UNKNOWN" };
    }
    const label = rowLabel(r);
    const snapshotAt = validSnapshotAt(r.snapshotAt);
    const stamp = `snapshot ${formatStamp(r.snapshotAt)}`;
    if (r.state === "PENDING") return { kind: "pending", key, label, snapshotAt, stamp };
    if (
      r.state !== "READY" ||
      typeof r.rules !== "number" ||
      !Number.isFinite(r.rules) ||
      r.rules < 0 ||
      r.counts === null ||
      typeof r.counts !== "object" ||
      Array.isArray(r.counts)
    ) {
      return { kind: "unknown", key, label, snapshotAt, stamp };
    }
    const countEntries = Object.entries(r.counts);
    if (countEntries.some(([, v]) => typeof v !== "number" || !Number.isFinite(v) || v < 0)) {
      return { kind: "unknown", key, label, snapshotAt, stamp };
    }
    return {
      kind: "ready",
      key,
      label,
      rules: r.rules,
      counts: countEntries.map(([finding, rules]) => ({ finding, rules: rules as number })),
      snapshotAt,
      stamp,
    };
  });

  const ready = all.filter((r): r is Extract<DossierHygieneRow, { kind: "ready" }> => r.kind === "ready");
  const pending = all.filter((r) => r.kind === "pending").length;
  const unknown = all.filter((r) => r.kind === "unknown").length;

  const sums = new Map<string, number>();
  for (const r of ready) for (const c of r.counts) sums.set(c.finding, (sums.get(c.finding) ?? 0) + c.rules);
  const totals = [...sums.entries()].sort((a, b) => b[1] - a[1]).map(([finding, rules]) => ({ finding, rules }));
  const flagged = totals.reduce((acc, t) => acc + t.rules, 0);

  const capped = capList(all, input.cap ?? DOSSIER_HYGIENE_ROW_CAP);

  let badge: BadgeState;
  let badgeWord: string;
  if (unknown > 0) {
    badge = "unk";
    badgeWord = "UNKNOWN";
  } else if (pending > 0) {
    badge = "watch";
    badgeWord = "PENDING";
  } else if (flagged > 0) {
    badge = "watch";
    badgeWord = "Review";
  } else {
    badge = "ok";
    badgeWord = "No findings";
  }

  const headlineParts = [
    plural(owned.length, "policy container", "policy containers") + " for this unit",
    `${ready.length} READY`,
    pending > 0 ? `${pending} PENDING` : null,
    unknown > 0 ? `${unknown} UNKNOWN` : null,
  ].filter((p): p is string => p !== null);

  return {
    badge,
    badgeWord,
    headline: headlineParts.join(" · "),
    countsLine: `Counts are rules per finding class over ${plural(ready.length, "READY container", "READY containers")} of this unit; a rule with two findings counts under both. Each row carries its own snapshot age.`,
    pendingLine: pending > 0 ? `${plural(pending, "container", "containers")} PENDING: no counts yet, not counted as zero.` : null,
    totals,
    rows: capped.shown,
    totalContainers: all.length,
    // A container without a valid snapshot time is UNKNOWN; the block stamp must not
    // present the age of the remaining containers as the age of the whole block.
    evidenceAt:
      unknown > 0 || all.some((r) => r.snapshotAt === null)
        ? null
        : oldestIso(all.map((r) => r.snapshotAt)),
    moreLabel: capped.moreLabel,
    notApplicable: false,
  };
}
