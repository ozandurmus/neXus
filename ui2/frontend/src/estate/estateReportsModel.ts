import type { ReportCatalogue, ReportPlaneAsOf, ReportRunView, ReportTemplateCard } from "../auth/adminApi";
import { ageBucketOf, newestIso, parseTimeMs, type AgeBucket, type BadgeState } from "./estateModel";
import { plural } from "../shell/plural";

/**
 * Estate Reports list model (Opus bold concept, row 20: "Reports · evidence packs, templates, history").
 * Pure functions only: the screen fetches the catalogue and the run history through the standard Reports clients and
 * passes them here. Nothing in this model reads a device or creates a report; generation stays on the standard screen.
 */

export const REPORT_LIST_CAP = 8;

/** A list cut to `cap` rows, with the count of rows left out so the screen can print "+N more". */
export interface CappedList<T> {
  readonly shown: readonly T[];
  readonly more: number;
}

export function capList<T>(items: readonly T[], cap: number = REPORT_LIST_CAP): CappedList<T> {
  const limit = Math.max(0, cap);
  return { shown: items.slice(0, limit), more: Math.max(0, items.length - limit) };
}

/** Age of a piece of evidence as a short label; a missing or unreadable timestamp is UNKNOWN, never "0 h". */
export function evidenceAgeLabel(iso: string | null | undefined, now: Date): string {
  const t = parseTimeMs(iso);
  if (t === null) return "UNKNOWN";
  const ms = Math.max(0, now.getTime() - t);
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 48) return `${hours} h`;
  return `${Math.floor(hours / 24)} d`;
}

/** The newest evidence timestamp across data planes; null when no plane carries evidence. */
export function newestPlaneEvidence(planes: readonly ReportPlaneAsOf[]): string | null {
  return newestIso(planes.map(plane => plane.newest));
}

/**
 * The newest evidence timestamp across a run's data planes; null when the run carries no plane evidence.
 * Read defensively: a null, omitted or malformed `data_as_of` / `planes` carries no evidence (UNKNOWN), never a throw.
 */
export function newestEvidenceAt(run: ReportRunView): string | null {
  const planes = (run as unknown as { readonly data_as_of?: { readonly planes?: unknown } | null }).data_as_of?.planes;
  return Array.isArray(planes) ? newestPlaneEvidence(planes as readonly ReportPlaneAsOf[]) : null;
}

export type TemplateStatus = "AVAILABLE" | "REFUSED";

export interface TemplateRow {
  readonly templateId: string;
  readonly title: string;
  readonly audience: string;
  readonly question: string;
  readonly formats: readonly string[];
  readonly status: TemplateStatus;
  /** Plain statement of the status; a refused template names the reads the session lacks. */
  readonly statusText: string;
  readonly missingReads: readonly string[];
}

export function buildTemplateRows(catalogue: ReportCatalogue): readonly TemplateRow[] {
  return catalogue.templates.map((t: ReportTemplateCard): TemplateRow => {
    const refused = t.missing_reads.length > 0;
    return {
      templateId: t.template_id,
      title: t.title,
      audience: t.audience,
      question: t.question,
      formats: [...t.formats],
      status: refused ? "REFUSED" : "AVAILABLE",
      statusText: refused
        ? `Refused: needs read access to ${t.missing_reads.join(", ")}`
        : "Available for this session",
      missingReads: [...t.missing_reads],
    };
  });
}

export type RunTone = "ok" | "review" | "act";

export interface RunRow {
  readonly runId: string;
  readonly templateId: string;
  readonly title: string;
  readonly format: string;
  readonly projection: string;
  readonly state: ReportRunView["state"];
  readonly tone: RunTone;
  readonly requestedAt: string;
  /** Age of the stored evidence the run was built from (newest plane timestamp), not the age of the request. */
  readonly evidenceAt: string | null;
  readonly evidenceAgeLabel: string;
  readonly evidenceBucket: AgeBucket;
  readonly expiresAt: string;
  readonly downloadable: boolean;
  readonly errorCode: string | null;
}

function toneOfState(state: ReportRunView["state"] | null | undefined): RunTone {
  if (state === "SUCCEEDED") return "ok";
  if (state === "FAILED") return "act";
  return "review";
}

export function buildRunRows(runs: readonly ReportRunView[], now: Date): readonly RunRow[] {
  return [...runs]
    .sort((a, b) => (parseTimeMs(b.requested_at) ?? 0) - (parseTimeMs(a.requested_at) ?? 0))
    .map((r): RunRow => {
      const evidenceAt = newestEvidenceAt(r);
      return {
        runId: r.run_id,
        templateId: r.template_id,
        title: r.title,
        format: r.format,
        projection: r.projection,
        state: r.state,
        tone: toneOfState(r.state),
        requestedAt: r.requested_at,
        evidenceAt,
        evidenceAgeLabel: evidenceAgeLabel(evidenceAt, now),
        evidenceBucket: ageBucketOf(evidenceAt, now),
        expiresAt: r.expires_at,
        downloadable: r.downloadable,
        errorCode: r.error_code,
      };
    });
}

/** Counts of runs by state. Each label says what it counts: runs, not reports or evidence packs. */
export interface RunSummary {
  readonly total: number;
  readonly succeeded: number;
  readonly failed: number;
  readonly inProgress: number;
  readonly sentence: string;
}

export function summariseRuns(runs: readonly ReportRunView[]): RunSummary {
  const succeeded = runs.filter(r => r.state === "SUCCEEDED").length;
  const failed = runs.filter(r => r.state === "FAILED").length;
  const inProgress = runs.length - succeeded - failed;
  return {
    total: runs.length,
    succeeded,
    failed,
    inProgress,
    sentence: `${runs.length} report runs listed: ${succeeded} succeeded, ${failed} failed, ${inProgress} in progress.`,
  };
}

/** The standard Reports screen, which the Estate screen links to for generation (never generates here). */
export function standardReportsHref(): string {
  return "?screen=reports&standard=1";
}

export const REPORT_SECTION_CAP = 12;

/** "N sections", "1 section": names what the count counts (document sections, not runs or files). */
export function sectionCountLabel(n: number): string {
  return plural(n, "section", "sections");
}

/** Plain state wording; an unrecognised state is named as-is and is never reported as OK. */
export function runStateLabel(state: string | null | undefined): string {
  if (state === "SUCCEEDED" || state === "FAILED" || state === "QUEUED" || state === "RUNNING") return state;
  return "UNKNOWN";
}

/**
 * Badge state of a run state: SUCCEEDED is ok, FAILED is act, QUEUED/RUNNING are watch.
 * An unknown or missing state is UNKNOWN (`unk`), never watch and never ok.
 */
export function runStateBadge(state: string | null | undefined): BadgeState {
  if (state === "SUCCEEDED") return "ok";
  if (state === "FAILED") return "act";
  if (state === "QUEUED" || state === "RUNNING") return "watch";
  return "unk";
}

export interface DetailSection {
  readonly id: string;
  readonly title: string;
  readonly notes: readonly string[];
}

export type RunDocument =
  | { readonly available: true; readonly sections: readonly DetailSection[] }
  /** `malformed` is true only when the run carries a non-null `document` that could not be read (UNKNOWN). */
  | { readonly available: false; readonly malformed: boolean };

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * The run's document sections, read defensively: a missing, non-array or malformed document is
 * "not available" (never OK), and a section without a string id and title makes the whole document unavailable.
 */
export function runDocument(run: ReportRunView): RunDocument {
  const doc = (run as unknown as { readonly document?: unknown }).document;
  if (doc === undefined || doc === null) return { available: false, malformed: false };
  if (!isRecord(doc) || !Array.isArray(doc.sections)) return { available: false, malformed: true };
  const sections: DetailSection[] = [];
  for (const s of doc.sections as unknown[]) {
    if (!isRecord(s) || typeof s.id !== "string" || typeof s.title !== "string") return { available: false, malformed: true };
    const notes = Array.isArray(s.notes) ? s.notes.filter((n): n is string => typeof n === "string") : [];
    sections.push({ id: s.id, title: s.title, notes });
  }
  return { available: true, sections };
}

/** Evidence age of a run, from the newest plane timestamp (UNKNOWN when the run carries none). */
export function runEvidenceAgeLabel(run: ReportRunView, now: Date): string {
  return evidenceAgeLabel(newestEvidenceAt(run), now);
}
