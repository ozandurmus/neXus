import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { TONE_COLORS, type Tone } from "../shell/tone";
import { formatStamp, relativeAge } from "../shell/time";
import { readinessReasonLabel } from "../shell/presenters";
import { m3 } from "../theme/m3Theme";
import { backupDisplayWord } from "./backupWords";
import {
  getDeviceCompliance,
  getDeviceConfiguration,
  listDeviceBackups,
  type BackupArtefact,
  type CpFailoverSummary,
  type DeviceComplianceResult,
  type DeviceConfiguration,
} from "../auth/adminApi";

/**
 * The object header's state row, part 2 (UI product review 2026-10-18 §4.2, roadmap 2.1): the planes connected at
 * the object. Under the read ages, the pills always come in the same plane order -- Compliance (VERIFY), Config
 * changed (TRACE), Backup (RECOVER), Readiness (OPERATE), Certificate -- so the eye finds a problem by position; no
 * composite score is computed and only a problem word is tinted. Each pill links to the fleet screen filtered for it.
 *
 * Rules: a pill the vendor does not have is absent (not UNKNOWN); a pill the vendor has but neXus has not read says
 * `Not read yet`; a read that was refused or failed makes no claim (the pill is absent); zero is a grey `0`, never
 * green. Every fact is built by a pure function here so the words are tested without a browser.
 */
export interface HeaderFact {
  readonly key: string;
  readonly label: string;
  readonly tone: Tone;
  /** The fleet screen filtered to this fact. */
  readonly href?: string;
  /** The exact values behind the words (stamps, per-member breakdown), on hover. */
  readonly title?: string;
}

/** Vendors whose clusters have a readiness check (Operations › HA & readiness). */
export const READINESS_VENDORS: ReadonlySet<string> = new Set(["check_point", "palo_alto"]);

export const complianceHref = (vendor: string | null | undefined) =>
  `?screen=compliance&result=fail&severity=critical${vendor ? `&vendor=${encodeURIComponent(vendor)}` : ""}`;
export const configChangedHref = () => "?screen=inventory&change_state=changed";
export const backupsHref = (q: string) => `?screen=backups&q=${encodeURIComponent(q)}`;
export const readinessHref = (clusterRef: string) => `?screen=operations&cluster_ref=${encodeURIComponent(clusterRef)}`;

// -------------------------------------------------------------------------------------------------------------------
// Compliance (VERIFY)
// -------------------------------------------------------------------------------------------------------------------

export interface ComplianceCounts { readonly critical: number; readonly pass: number; readonly fail: number; readonly unavailable: number; readonly checked: number }

/** The same counting the fleet figures use: NOT_APPLICABLE is not a check; anything but PASS/FAIL is a data gap. */
export function complianceCounts(result: Pick<DeviceComplianceResult, "items">): ComplianceCounts {
  let critical = 0, pass = 0, fail = 0, unavailable = 0;
  for (const item of result.items ?? []) {
    const status = String(item.displayStatus ?? "").toUpperCase();
    if (status === "NOT_APPLICABLE") continue;
    if (status === "PASS") pass++;
    else if (status === "FAIL") {
      fail++;
      if (String(item.severity ?? "").toUpperCase() === "CRITICAL") critical++;
    } else unavailable++;
  }
  return { critical, pass, fail, unavailable, checked: pass + fail + unavailable };
}

/**
 * `Compliance 3 critical failing · 59 checked`. With no items the device was not evaluated: `Not read yet` when its
 * configuration was never read (nothing to assess), `Not assessed` when it was read and still nothing came back.
 */
export function complianceFact(result: Pick<DeviceComplianceResult, "items"> | null, options: { readonly configurationRead: boolean; readonly vendor?: string | null; readonly href?: string }): HeaderFact | null {
  if (!result) return null;
  const href = options.href ?? complianceHref(options.vendor);
  const counts = complianceCounts(result);
  if (counts.checked === 0) {
    return { key: "compliance", label: `Compliance · ${options.configurationRead ? "Not assessed" : "Not read yet"}`, tone: "neutral", href,
      title: options.configurationRead ? "The configuration was read but no control check came back for this device." : "No configuration has been read yet, so no control was checked." };
  }
  return {
    key: "compliance",
    label: `Compliance ${counts.critical} critical failing · ${counts.checked} checked`,
    tone: counts.critical > 0 ? "bad" : "neutral",
    href,
    title: `${counts.pass} passing · ${counts.fail} failing · ${counts.unavailable} without evidence`,
  };
}

/** Members' checks pooled, as the fleet pools checks over firewalls; the per-member split is the hover text. */
export function clusterComplianceFact(members: ReadonlyArray<{ readonly label: string; readonly result: Pick<DeviceComplianceResult, "items"> | null; readonly configurationRead: boolean }>, vendor: string | null | undefined): HeaderFact | null {
  const read = members.filter((m) => m.result !== null);
  if (read.length === 0) return null;
  const perMember = read.map((m) => ({ label: m.label, counts: complianceCounts(m.result!), configurationRead: m.configurationRead }));
  const total = perMember.reduce((acc, m) => ({ critical: acc.critical + m.counts.critical, checked: acc.checked + m.counts.checked }), { critical: 0, checked: 0 });
  const href = complianceHref(vendor);
  if (total.checked === 0) {
    const anyRead = perMember.some((m) => m.configurationRead);
    return { key: "compliance", label: `Compliance · ${anyRead ? "Not assessed" : "Not read yet"}`, tone: "neutral", href };
  }
  return {
    key: "compliance",
    label: `Compliance ${total.critical} critical failing · ${total.checked} checked`,
    tone: total.critical > 0 ? "bad" : "neutral",
    href,
    title: perMember.map((m) => `${m.label}: ${m.counts.critical} critical failing · ${m.counts.checked} checked`).join(" · "),
  };
}

// -------------------------------------------------------------------------------------------------------------------
// Config changed (TRACE)
// -------------------------------------------------------------------------------------------------------------------

/** Distinct sections named by the run's deviation summary. */
export function changedSections(configuration: Pick<DeviceConfiguration, "change_deviation_summary">): string[] {
  const entries = configuration.change_deviation_summary?.entries ?? [];
  return Array.from(new Set(entries.map((e) => (e.context && e.context !== "shared" && e.context !== "physical" ? `${e.context} · ${e.section}` : e.section))));
}

/**
 * `Config changed · 18 sections` for a run that differs from the previous one; `Config unchanged`, `Config first read`
 * otherwise. A change is TRACE content, not a problem: the pill stays grey. No read yet: no pill (the read-age row
 * already says `Config not read yet`).
 */
export function configChangedFact(configuration: Pick<DeviceConfiguration, "collected_at" | "change_state" | "change_deviation_summary"> | null, prefix = ""): HeaderFact | null {
  if (!configuration || !configuration.collected_at) return null;
  const href = configChangedHref();
  if (configuration.change_state === "changed") {
    const sections = changedSections(configuration);
    const count = sections.length;
    return {
      key: "config-changed",
      label: `Config changed${prefix ? ` · ${prefix}` : ""}${count > 0 ? ` · ${count} ${count === 1 ? "section" : "sections"}` : ""}`,
      tone: "neutral",
      href,
      title: count > 0 ? `Since the previous read: ${sections.slice(0, 8).join(", ")}${count > 8 ? ` and ${count - 8} more` : ""}` : "Differs from the previous read; the sections were not summarised.",
    };
  }
  if (configuration.change_state === "first_run") return { key: "config-changed", label: `Config first read${prefix ? ` · ${prefix}` : ""}`, tone: "neutral", href, title: "The first configuration read of this device: nothing earlier to compare with." };
  if (configuration.change_state === "unchanged") return { key: "config-changed", label: `Config unchanged${prefix ? ` · ${prefix}` : ""}`, tone: "neutral", href, title: "Same as the previous read." };
  return null;
}

/** One pill for a cluster: the members that changed, by M1/M2 label; `Config unchanged` when none did. */
export function clusterConfigChangedFact(members: ReadonlyArray<{ readonly label: string; readonly configuration: Pick<DeviceConfiguration, "collected_at" | "change_state" | "change_deviation_summary"> | null }>): HeaderFact | null {
  const read = members.filter((m) => m.configuration?.collected_at);
  if (read.length === 0) return null;
  const changed = read.filter((m) => m.configuration!.change_state === "changed");
  if (changed.length === 0) {
    const first = read.every((m) => m.configuration!.change_state === "first_run");
    return { key: "config-changed", label: first ? "Config first read" : "Config unchanged", tone: "neutral", href: configChangedHref() };
  }
  const parts = changed.map((m) => {
    const n = changedSections(m.configuration!).length;
    return `${m.label} ${n > 0 ? `${n} ${n === 1 ? "section" : "sections"}` : "changed"}`;
  });
  return { key: "config-changed", label: `Config changed · ${parts.join(" · ")}`, tone: "neutral", href: configChangedHref(),
    title: changed.map((m) => `${m.label}: ${changedSections(m.configuration!).slice(0, 6).join(", ") || "sections not summarised"}`).join(" · ") };
}

// -------------------------------------------------------------------------------------------------------------------
// Backup (RECOVER)
// -------------------------------------------------------------------------------------------------------------------

export function newestBackup(backups: readonly BackupArtefact[]): BackupArtefact | null {
  return backups.reduce<BackupArtefact | null>((best, b) => (!best || new Date(b.collected_at).getTime() > new Date(best.collected_at).getTime() ? b : best), null);
}

/**
 * `Backup 8 h ago · Checked`; `Backup · no archive` (amber: a target without a stored archive is the RECOVER problem
 * the Overview counts) or `Backup · not a target` (grey) when nothing is stored. A malformed or failed read: no pill.
 */
export function backupFact(backups: readonly BackupArtefact[] | null | undefined, device: { readonly backup_target?: boolean }, href: string, prefix = "", now: Date = new Date()): HeaderFact | null {
  if (!Array.isArray(backups)) return null;
  const head = `Backup${prefix ? ` ${prefix}` : ""}`;
  const newest = newestBackup(backups);
  if (!newest) {
    return device.backup_target === false
      ? { key: `backup${prefix ? `-${prefix}` : ""}`, label: `${head} · not a target`, tone: "neutral", href, title: "This device is not a backup target; no archive is expected." }
      : { key: `backup${prefix ? `-${prefix}` : ""}`, label: `${head} · no archive`, tone: "warn", href, title: "No stored archive for this device." };
  }
  return {
    key: `backup${prefix ? `-${prefix}` : ""}`,
    label: `${head} ${relativeAge(newest.collected_at, now)} · ${backupDisplayWord(newest.validation_level)}`,
    tone: "neutral",
    href,
    title: `Latest stored archive ${formatStamp(newest.collected_at)} · ${backups.length} stored`,
  };
}

// -------------------------------------------------------------------------------------------------------------------
// Readiness (OPERATE)
// -------------------------------------------------------------------------------------------------------------------

const READINESS_WORDS: Record<string, { readonly word: string; readonly tone: Tone }> = {
  READY: { word: "Ready", tone: "ok" }, NOT_READY: { word: "Not ready", tone: "bad" }, UNKNOWN: { word: "Unknown", tone: "neutral" },
};

/** The cluster's own unit (not a virtual system's) from the failover summary rows of one cluster reference. */
export function clusterReadinessRow(rows: readonly CpFailoverSummary[] | null | undefined, clusterRef: string): CpFailoverSummary | null {
  if (!Array.isArray(rows)) return null;
  const own = rows.filter((r) => r.cluster_member_ref === clusterRef);
  return own.find((r) => r.unitId === r.clusterId) ?? own[0] ?? null;
}

/** `Readiness Ready · 4 h ago`; `Readiness · Not read yet` when the cluster has no readiness on record; no rows at all: no pill. */
export function readinessFact(rows: readonly CpFailoverSummary[] | null | undefined, clusterRef: string, now: Date = new Date()): HeaderFact | null {
  if (!Array.isArray(rows)) return null;
  const href = readinessHref(clusterRef);
  const row = clusterReadinessRow(rows, clusterRef);
  if (!row || !row.readiness) {
    return { key: "readiness", label: "Readiness · Not read yet", tone: "neutral", href,
      title: row?.refusalReason ? readinessReasonLabel(row.refusalReason) : "No readiness check has been recorded for this cluster." };
  }
  const { word, tone } = READINESS_WORDS[row.readiness.status] ?? { word: row.readiness.status, tone: "neutral" as Tone };
  const reason = row.readiness.status === "NOT_READY" ? readinessReasonLabel(row.readiness.stopCode ?? row.readiness.failedCheck) : null;
  return {
    key: "readiness",
    label: `Readiness ${word} · ${relativeAge(row.readiness.observedAt, now)}`,
    tone,
    href,
    title: `${formatStamp(row.readiness.observedAt)}${reason ? ` · ${reason}` : ""}`,
  };
}

// -------------------------------------------------------------------------------------------------------------------
// Rendering
// -------------------------------------------------------------------------------------------------------------------

/** One state pill: word (+ count, + age), tone by the colour-role table, a link to the filtered fleet screen. */
export function StatePill({ fact }: { readonly fact: HeaderFact }) {
  const colors = TONE_COLORS[fact.tone];
  const sx = {
    display: "inline-flex", alignItems: "center", height: 22, px: 1, borderRadius: "8px", whiteSpace: "nowrap",
    bgcolor: colors.bg, color: colors.fg, fontSize: 11, fontWeight: 500, textDecoration: "none",
    "&:hover": { textDecoration: fact.href ? "underline" : "none" },
    "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 },
  } as const;
  const body = fact.href
    ? <Box component="a" href={fact.href} data-fact={fact.key} sx={sx}>{fact.label}</Box>
    : <Box component="span" data-fact={fact.key} sx={sx}>{fact.label}</Box>;
  return fact.title ? <Tooltip title={fact.title}><Box component="span" sx={{ display: "inline-flex" }}>{body}</Box></Tooltip> : body;
}

/** The pills in their fixed plane order; `null` facts (absent pills) are simply left out. */
export function HeaderStateRow({ facts, trailing }: { readonly facts: ReadonlyArray<HeaderFact | null>; readonly trailing?: React.ReactNode }) {
  const shown = facts.filter((f): f is HeaderFact => f !== null);
  if (shown.length === 0 && !trailing) return null;
  return (
    <Box data-testid="header-state-row" sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
      {shown.map((f) => <StatePill key={f.key} fact={f} />)}
      {trailing}
    </Box>
  );
}

// -------------------------------------------------------------------------------------------------------------------
// Cluster reads: one effect over all members (hooks cannot run in a loop)
// -------------------------------------------------------------------------------------------------------------------

export interface MemberHeaderReads {
  readonly backups: Record<string, BackupArtefact[] | null>;
  readonly compliance: Record<string, DeviceComplianceResult | null>;
  readonly configuration: Record<string, DeviceConfiguration | null>;
  readonly loaded: boolean;
}

const EMPTY_READS: MemberHeaderReads = { backups: {}, compliance: {}, configuration: {}, loaded: false };

/**
 * Reads the backups, configuration summary and (where the vendor is assessed) compliance of every member of a cluster
 * for the header. A read that fails leaves `null`, which the fact builders turn into an absent pill. Re-run on
 * `version` (after a Collect now).
 */
export function useMemberHeaderReads(members: ReadonlyArray<{ readonly device_id: string }>, options: { readonly configuration: boolean; readonly assessed: boolean; readonly version?: number }): MemberHeaderReads {
  const [reads, setReads] = useState<MemberHeaderReads>(EMPTY_READS);
  const ids = members.map((m) => m.device_id).join("|");
  useEffect(() => {
    let cancelled = false;
    const settle = <T,>(p: Promise<T>) => p.then((v) => v as T | null).catch(() => null);
    (async () => {
      const list = ids ? ids.split("|") : [];
      const [backups, configuration, compliance] = await Promise.all([
        Promise.all(list.map((id) => settle(listDeviceBackups(id).then((r) => (Array.isArray(r?.backups) ? r.backups : null))))),
        Promise.all(list.map((id) => (options.configuration ? settle(getDeviceConfiguration(id)) : Promise.resolve(null)))),
        Promise.all(list.map((id) => (options.assessed ? settle(getDeviceCompliance(id)) : Promise.resolve(null)))),
      ]);
      if (cancelled) return;
      const by = <T,>(values: Array<T | null>) => Object.fromEntries(list.map((id, i) => [id, values[i]])) as Record<string, T | null>;
      setReads({ backups: by(backups), configuration: by(configuration), compliance: by(compliance), loaded: true });
    })();
    return () => { cancelled = true; };
  }, [ids, options.configuration, options.assessed, options.version]);
  return reads;
}
