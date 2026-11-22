import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import Typography from "@mui/material/Typography";
import { listJobs, type JobEventView } from "../auth/adminApi";
import { attemptWords } from "../screens/backupWords";
import { formatStamp, relativeAge } from "../shell/time";
import { EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { standardBackupsHref } from "./estateRouting";
import { ESTATE_MONO, ev } from "./estateTokens";

/** Rows shown in the drawer; the rest are counted in the "+N more" footer. */
export const ATTEMPTS_DRAWER_CAP = 10;

/** Outcome states the attempt words describe. Anything else (blank, OUTCOME_UNKNOWN, a state we do not know) is UNKNOWN. */
const READABLE_STATES = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "EXECUTING", "CLAIMED", "REQUESTED", "SUBMITTED"]);

export interface AttemptsUnit {
  readonly deviceId: string;
  readonly name: string;
}

/** The outcome word for one attempt. An unreadable outcome is UNKNOWN, never a pass. */
export function attemptOutcome(job: JobEventView): { readonly word: string; readonly tone: "ok" | "bad" | "warn" | "neutral" } {
  const state = (job.state ?? "").trim().toUpperCase();
  if (!READABLE_STATES.has(state)) return { word: "UNKNOWN", tone: "neutral" };
  const words = attemptWords(state, job.terminal_reason);
  return { word: words.word, tone: words.tone };
}

const TONE_INK = { ok: ev.ink2, bad: ev.act, warn: ev.watch, neutral: ev.ink2 } as const;

/** The newest submission time among the attempts shown, or null when none carries a readable time. */
function newestSubmittedAt(items: readonly JobEventView[]): string | null {
  let newest: string | null = null;
  let newestMs = Number.NEGATIVE_INFINITY;
  for (const job of items) {
    const ms = job.submitted_at ? Date.parse(job.submitted_at) : Number.NaN;
    if (Number.isFinite(ms) && ms > newestMs) {
      newestMs = ms;
      newest = job.submitted_at;
    }
  }
  return newest;
}

type AttemptsRead =
  | { readonly state: "loading" }
  | { readonly state: "error" }
  | { readonly state: "ok"; readonly items: readonly JobEventView[]; readonly total: number };

function AttemptsList({ unit }: { readonly unit: AttemptsUnit }) {
  const [read, setRead] = useState<AttemptsRead>({ state: "loading" });

  useEffect(() => {
    let active = true;
    setRead({ state: "loading" });
    listJobs({ device_id: unit.deviceId, job_type: "backup", page: 1, page_size: ATTEMPTS_DRAWER_CAP })
      .then((value) => {
        if (active) setRead({ state: "ok", items: value.items ?? [], total: value.total ?? (value.items?.length ?? 0) });
      })
      .catch(() => {
        if (active) setRead({ state: "error" });
      });
    return () => { active = false; };
  }, [unit.deviceId]);

  if (read.state === "loading") {
    return <Typography variant="body2" sx={{ fontSize: "12.5px", color: ev.ink2 }}>Loading backup attempts…</Typography>;
  }
  if (read.state === "error") {
    return (
      <Box>
        <StateBadge state="unk" word="UNKNOWN" dense />{" "}
        <Typography component="span" sx={{ fontSize: "12.5px", color: ev.ink2 }}>Backup attempts could not be loaded.</Typography>
      </Box>
    );
  }
  if (read.items.length === 0) {
    return <Typography variant="body2" sx={{ fontSize: "12.5px", color: ev.ink2 }}>No backup attempts on record.</Typography>;
  }

  const shown = read.items.slice(0, ATTEMPTS_DRAWER_CAP);
  const now = new Date();
  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "8px" }}>
        <Typography component="span" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
          Newest of the {shown.length} attempts shown:
        </Typography>
        <EvidenceStamp at={newestSubmittedAt(shown)} now={now} missingLabel="UNKNOWN" source="newest attempt shown" />
      </Box>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}>
        {shown.map((job) => {
          const outcome = attemptOutcome(job);
          const age = job.submitted_at ? relativeAge(job.submitted_at, now) : "UNKNOWN";
          return (
            <Box
              component="li"
              key={job.job_id}
              data-attempt-row={job.job_id}
              sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap", fontSize: "12.5px" }}
            >
              <Box component="span" title={job.submitted_at ? formatStamp(job.submitted_at) : "No submission time recorded"} sx={{ fontFamily: ESTATE_MONO, color: ev.ink2, minWidth: "96px" }}>
                {age}
              </Box>
              <Box component="span" data-attempt-outcome={outcome.word} sx={{ color: TONE_INK[outcome.tone], fontWeight: outcome.tone === "bad" ? 650 : 500 }}>
                {outcome.word}
              </Box>
            </Box>
          );
        })}
      </Box>
      <Box sx={{ mt: "6px" }}>
        <MoreFooter shown={shown.length} total={read.total} noun="attempts" testId="attempts-drawer-more" />
      </Box>
    </Box>
  );
}

/**
 * Read-only drawer: the unit's most recent backup attempts (one `listJobs` read, the same call the standard Backups
 * screen makes). It starts no job, retries nothing and contacts no device; its only link opens the standard view.
 */
export function EstateBackupsAttemptsDrawer({ unit, onClose }: {
  readonly unit: AttemptsUnit | null;
  readonly onClose: () => void;
}) {
  return (
    <Drawer anchor="right" open={unit !== null} onClose={onClose} data-testid="estate-attempts-drawer">
      {unit !== null && (
        <Box sx={{ width: { xs: "100vw", sm: 420 }, maxWidth: "100vw", p: "18px", boxSizing: "border-box", fontFamily: "inherit" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Recent backup attempts
          </Typography>
          <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "13px", fontWeight: 650, color: ev.ink, mt: "2px", mb: "12px" }}>
            {unit.name}
          </Typography>
          <AttemptsList key={unit.deviceId} unit={unit} />
          <Box sx={{ mt: "18px", pt: "12px", borderTop: `1px solid ${ev.line}` }}>
            <Box component="a" href={standardBackupsHref(unit.name)} sx={{ fontSize: "12.5px", color: ev.accent }}>
              Backups (standard view) →
            </Box>
          </Box>
          <Box sx={{ mt: "14px" }}>
            <Alert severity="info" variant="outlined" sx={{ fontSize: "12px" }}>
              Read-only. Nothing runs from this drawer.
            </Alert>
          </Box>
        </Box>
      )}
    </Drawer>
  );
}
