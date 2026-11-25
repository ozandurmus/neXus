import { useCallback, useEffect, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { m3 } from "../../theme/m3Theme";
import { Hint } from "./zoneMatrixUi";
import {
  getZoneMatrixSummary, zoneErrorLines,
  type SummaryStalenessThreshold, type ZoneMatrixSummaryParams, type ZoneMatrixSummaryResponse,
} from "./zoneMatrixApi";

/**
 * The stored summary (`GET /api/v2/zone-matrix/summary`), read by the Violations and Coverage tabs. It is read lazily: the
 * first time one of those tabs is opened, so the screen's own load keeps its requests. One read serves both tabs; the
 * Violations tab changes `params` (the filters the route honours) and the read repeats.
 */
export interface ZoneSummaryHandle {
  readonly data: ZoneMatrixSummaryResponse | null;
  readonly error: string | null;
  readonly rawError: unknown;
  readonly loading: boolean;
  readonly params: ZoneMatrixSummaryParams;
  readonly setParams: (params: ZoneMatrixSummaryParams) => void;
  /** Starts the first read (a no-op once started). */
  readonly ensure: () => void;
  readonly reload: () => void;
}

export function useZoneSummary(): ZoneSummaryHandle {
  const [wanted, setWanted] = useState(false);
  const [params, setParamsState] = useState<ZoneMatrixSummaryParams>({});
  const [generation, setGeneration] = useState(0);
  const [data, setData] = useState<ZoneMatrixSummaryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rawError, setRawError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const ensure = useCallback(() => setWanted(true), []);
  const reload = useCallback(() => setGeneration((g) => g + 1), []);
  const setParams = useCallback((next: ZoneMatrixSummaryParams) => { setWanted(true); setParamsState(next); }, []);
  useEffect(() => {
    if (!wanted) return undefined;
    let cancelled = false;
    setLoading(true);
    getZoneMatrixSummary(params)
      .then((result) => { if (!cancelled) { setData(result); setError(null); setRawError(null); } })
      .catch((err: unknown) => { if (!cancelled) { setError(zoneErrorLines(err).join(" ")); setRawError(err); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [wanted, params, generation]);
  return { data, error, rawError, loading, params, setParams, ensure, reload };
}

/** Starts the summary read when a summary tab is first shown. */
export function useEnsureSummary(summary: ZoneSummaryHandle) {
  const { ensure } = summary;
  useEffect(() => { ensure(); }, [ensure]);
}

/**
 * What a summary tab shows until it has a response: the reading line, the shared restricted panel for a refused read, the
 * shared error panel with Retry for a failure. `null` once there is a response to draw.
 */
export function summaryGate(summary: ZoneSummaryHandle, area: string): ReactNode | null {
  if (summary.data !== null) return null;
  if (summary.error === null) return <StatePanel variant="empty" title="Reading the stored summary…" announce="status" />;
  if (isRestricted(summary.rawError)) return <RestrictedPanel area={area} />;
  return (
    <StatePanel variant="error" title="The stored summary could not be read" body={summary.error}
      action={<M3Button emphasis="outlined" onClick={summary.reload}>Retry</M3Button>} />
  );
}

/** A failed re-read while an older response is still shown: said above the data, never silently dropped. */
export function SummaryReadFailure({ summary }: { readonly summary: ZoneSummaryHandle }) {
  if (summary.error === null || summary.data === null) return null;
  return (
    <Box role="alert" data-testid="summary-read-failure"
      sx={{ border: `1px solid ${m3.outlineVar}`, bgcolor: m3.errorContainer, color: m3.onErrorContainer, borderRadius: 1, px: 2, py: 1 }}>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>The latest read of the stored summary failed; the screen shows the previous data.</Typography>
      <Typography variant="body2">{summary.error}</Typography>
      <M3Button emphasis="outlined" onClick={summary.reload}>Retry</M3Button>
    </Box>
  );
}

/** The staleness threshold with where it comes from; while it is the interim value the response's own label is printed. */
export function ThresholdNote({ threshold }: { readonly threshold: SummaryStalenessThreshold }) {
  const value = threshold.hours !== null ? `${threshold.hours} h` : `${threshold.seconds} s`;
  return (
    <Hint id="staleness-threshold">
      <span data-testid="staleness-threshold">
        A policy is stale when its snapshot is older than {value}
        {threshold.interim ? " (INTERIM)" : ""}: {threshold.label}.
      </span>
    </Hint>
  );
}

/** A snapshot age in words; an age the server could not state prints UNKNOWN, never 0. */
export function ageText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return "UNKNOWN";
  if (seconds < 60) return `${Math.floor(seconds)} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min`;
  const hours = Math.floor(seconds / 3600);
  if (seconds < 86400) return `${hours} h ${Math.floor((seconds % 3600) / 60)} min`;
  return `${Math.floor(seconds / 86400)} d ${hours % 24} h`;
}

const STALENESS_REASON_WORDS: Readonly<Record<string, string>> = {
  SNAPSHOT_OLDER_THAN_THRESHOLD: "Snapshot older than the staleness threshold",
  LAST_RUN_FAILED: "Latest evaluation run failed",
  VERSION_NOT_ACTIVE: "Latest complete run used a version that is not the active one",
  SNAPSHOT_TIME_UNKNOWN: "Snapshot time unknown",
};

export function stalenessReasonWords(reason: string): string {
  return STALENESS_REASON_WORDS[reason] ?? flagWords(reason);
}

/** A machine token as words (`BECAME_UNDECIDED` -> `Became undecided`); an empty value is UNKNOWN. */
export function flagWords(token: string | null | undefined): string {
  if (!token) return "UNKNOWN";
  const words = token.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export const BECAME_UNDECIDED = "BECAME_UNDECIDED";
