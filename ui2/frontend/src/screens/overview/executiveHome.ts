import { evidenceIsStale, staleSentence, type FleetFreshness } from "../../shell/freshness";
import { relativeAge } from "../../shell/time";

/**
 * The executive home's logic (UI review 2026-11-05, C1; Argon §3 amended by Fable): the verdict word, the freshness
 * sentence, the "needs attention" lines and the framework order. Pure functions over figures the existing Overview,
 * device, failover-summary and compliance-control reads already return; nothing here invents a figure. A figure that
 * could not be read is `null` and is said as UNKNOWN, never as zero.
 */

export type Verdict = "HEALTHY" | "AT RISK" | "UNKNOWN";

export interface ExecutiveFigures {
  /** Firewalls with at least one critical check failing, of the assessed firewalls (estate facts `k` of `n`). */
  readonly security: { readonly failing: number; readonly of: number } | null;
  /** Backup targets holding a stored archive, of all backup targets (estate facts `a` of `b`). */
  readonly backups: { readonly archived: number; readonly of: number } | null;
  /** Cluster readiness counts (the Operations list's own counting). */
  readonly ha: { readonly total: number; readonly ready: number; readonly notReady: number; readonly unknown: number; readonly unsupported: number } | null;
  /** Active devices read in 24 h. */
  readonly freshness: FleetFreshness | null;
}

export interface VerdictResult { readonly word: Verdict; readonly reason: string }

/**
 * HEALTHY only when every pillar is known, clean and the data is fresh; AT RISK as soon as the last stored reads show a
 * known defect (a critical check failing, a backup target without an archive, a cluster not ready), even when those reads
 * are old -- the freshness sentence beside the word says how old; UNKNOWN otherwise (fail-closed: absence of evidence is
 * not evidence of health).
 */
export function executiveVerdict(f: ExecutiveFigures): VerdictResult {
  const risks: string[] = [];
  if (f.security && f.security.failing > 0) risks.push(`${f.security.failing} ${f.security.failing === 1 ? "firewall fails" : "firewalls fail"} a critical check`);
  if (f.backups && f.backups.of - f.backups.archived > 0) {
    const missing = f.backups.of - f.backups.archived;
    risks.push(`${missing} backup ${missing === 1 ? "target has" : "targets have"} no stored backup`);
  }
  if (f.ha && f.ha.notReady > 0) risks.push(`${f.ha.notReady} HA ${f.ha.notReady === 1 ? "cluster is" : "clusters are"} not ready for failover`);
  if (risks.length > 0) return { word: "AT RISK", reason: `${capitalise(risks.join(", "))}.` };

  const gaps: string[] = [];
  if (!f.security) gaps.push("security posture could not be read");
  else if (f.security.of === 0) gaps.push("no firewall has been assessed yet");
  if (!f.backups) gaps.push("backup coverage could not be read");
  if (!f.ha) gaps.push("HA readiness could not be read");
  else if (f.ha.unknown > 0) gaps.push(`HA readiness of ${f.ha.unknown} ${f.ha.unknown === 1 ? "cluster is" : "clusters is"} UNKNOWN`);
  if (!f.freshness) gaps.push("data freshness could not be read");
  else if (evidenceIsStale(f.freshness)) gaps.push("the data is out of date");
  if (gaps.length > 0) return { word: "UNKNOWN", reason: `No known problem, but ${gaps.join(", ")}.` };

  return { word: "HEALTHY", reason: "No critical check failing, every backup target backed up, every supported cluster ready." };
}

function capitalise(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s;
}

/** "98 of 105 active devices read in the last 24 h · newest read 20 min ago · oldest 3 d ago" (C7: plain words). */
export function freshnessSentence(f: FleetFreshness | null, newestAt: string | null | undefined, oldestAt: string | null | undefined, now = new Date()): string {
  if (!f) return "Data freshness UNKNOWN: the device read times could not be read.";
  if (f.of === 0) return "No active devices yet.";
  const ages = [newestAt ? `newest read ${relativeAge(newestAt, now)}` : null, oldestAt ? `oldest ${relativeAge(oldestAt, now)}` : null].filter(Boolean);
  const head = evidenceIsStale(f)
    ? `Data is out of date: ${staleSentence(f)}`
    : `${f.fresh} of ${f.of} active devices read in the last 24 h${f.never ? `; ${f.never} never read` : ""}`;
  return `${head}${ages.length ? ` · ${ages.join(" · ")}` : ""}.`;
}

export type AttentionSeverity = "critical" | "serious" | "warning";
const SEVERITY_ORDER: Record<AttentionSeverity, number> = { critical: 0, serious: 1, warning: 2 };

export interface AttentionLine {
  readonly key: string;
  readonly severity: AttentionSeverity;
  readonly text: string;
  /** What the age is of ("readiness evaluated", "last backup run", ...). */
  readonly ageOf: string;
  readonly at: string | null;
  readonly href: string;
}

export interface AttentionInputs extends ExecutiveFigures {
  readonly ages: {
    readonly haNotReadyAt?: string | null; readonly haUnknownAt?: string | null; readonly backupAt?: string | null;
    readonly complianceAt?: string | null; readonly oldestReadAt?: string | null;
  };
  /** The most widespread critical failing control, from the control list. */
  readonly topControl?: { readonly id: string; readonly title: string; readonly failing: number; readonly of: number } | null;
}

export const MAX_ATTENTION_LINES = 5;

const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;

/**
 * At most five lines, most severe first, each with the age of the evidence behind it and a link to the filtered problem
 * set. Counts only: no device or cluster name, so the lines never carry an identity the masked projection did not choose.
 */
export function attentionLines(x: AttentionInputs): AttentionLine[] {
  const lines: AttentionLine[] = [];
  if (x.ha && x.ha.notReady > 0) {
    lines.push({ key: "ha-not-ready", severity: "critical", text: `${x.ha.notReady} of ${x.ha.total} HA ${x.ha.total === 1 ? "cluster" : "clusters"} not ready for failover`,
      ageOf: "readiness checked", at: x.ages.haNotReadyAt ?? null, href: q({ screen: "operations", readiness: "NOT_READY" }) });
  }
  if (x.backups && x.backups.of - x.backups.archived > 0) {
    const missing = x.backups.of - x.backups.archived;
    lines.push({ key: "backup-missing", severity: "critical", text: `${missing} of ${x.backups.of} backup targets have no stored backup`,
      ageOf: "last backup run", at: x.ages.backupAt ?? null, href: q({ screen: "backups", artefact: "none" }) });
  }
  if (x.security && x.security.failing > 0) {
    lines.push({ key: "critical-failing", severity: "critical", text: `${x.security.failing} of ${x.security.of} firewalls fail at least one critical check`,
      ageOf: "compliance data", at: x.ages.complianceAt ?? null, href: q({ screen: "compliance", severity: "critical", result: "fail" }) });
  }
  if (x.topControl && x.topControl.failing > 0) {
    lines.push({ key: "top-control", severity: "serious", text: `Most widespread critical failure: ${x.topControl.title} (${x.topControl.failing} of ${x.topControl.of} firewalls)`,
      ageOf: "compliance data", at: x.ages.complianceAt ?? null, href: q({ screen: "compliance", q: x.topControl.id }) });
  }
  if (x.freshness && x.freshness.of - x.freshness.fresh > 0) {
    const older = x.freshness.of - x.freshness.fresh;
    lines.push({ key: "stale-devices", severity: evidenceIsStale(x.freshness) ? "serious" : "warning",
      text: `${older} of ${x.freshness.of} active devices not read in the last 24 h`,
      ageOf: "oldest read", at: x.ages.oldestReadAt ?? null, href: q({ screen: "inventory", inventory_age: "stale" }) });
  }
  if (x.ha && x.ha.unknown > 0) {
    lines.push({ key: "ha-unknown", severity: "warning", text: `${x.ha.unknown} of ${x.ha.total} HA ${x.ha.total === 1 ? "cluster has" : "clusters have"} UNKNOWN readiness`,
      ageOf: "readiness checked", at: x.ages.haUnknownAt ?? null, href: q({ screen: "operations", readiness: "UNKNOWN" }) });
  }
  return lines
    .map((line, i) => ({ line, i }))
    .sort((a, b) => SEVERITY_ORDER[a.line.severity] - SEVERITY_ORDER[b.line.severity] || a.i - b.i)
    .slice(0, MAX_ATTENTION_LINES)
    .map(({ line }) => line);
}

/** The PO's primary frameworks first (2026-11-05: CIS, then NIST), the rest in the order the service returned them. */
export function primaryFrameworksFirst<T>(items: readonly T[], name: (t: T) => string): T[] {
  const rank = (n: string) => (/\bCIS\b/i.test(n) ? 0 : /\bNIST\b/i.test(n) ? 1 : 2);
  return items.map((t, i) => ({ t, i })).sort((a, b) => rank(name(a.t)) - rank(name(b.t)) || a.i - b.i).map(({ t }) => t);
}

const pct1 = (a: number, b: number) => (b > 0 ? Math.round((1000 * a) / b) / 10 : null);

/**
 * B4: the share of evidenced checks that pass (pass / (pass + fail)) and the share with no evidence (unavailable / all);
 * a check without evidence is never counted as failed. `null` when nothing is evidenced / nothing is assigned.
 */
export function evidencedShares(pass: number, fail: number, unavailable: number): { readonly passPct: number | null; readonly noEvidencePct: number | null } {
  return { passPct: pct1(pass, pass + fail), noEvidencePct: pct1(unavailable, pass + fail + unavailable) };
}

/** "37.5% of evidenced controls pass, 19.9% have no evidence"; UNKNOWN parts said as such. */
export function evidencedHeadline(passPct: number | null | undefined, noEvidencePct: number | null | undefined): string {
  const pass = passPct === null || passPct === undefined ? "UNKNOWN" : `${passPct}%`;
  const none = noEvidencePct === null || noEvidencePct === undefined ? "UNKNOWN" : `${noEvidencePct}%`;
  return `${pass} of evidenced controls pass, ${none} have no evidence`;
}

/** The no-evidence share from a coverage percentage, without float noise (100 - 80.1 = 19.9, not 19.900000000000006). */
export function noEvidenceFromCoverage(coveragePct: number | null | undefined): number | null {
  if (coveragePct === null || coveragePct === undefined || Number.isNaN(coveragePct)) return null;
  return Math.round((100 - coveragePct) * 10) / 10;
}
