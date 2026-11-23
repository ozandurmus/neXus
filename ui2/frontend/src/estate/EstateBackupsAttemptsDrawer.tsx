import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import Typography from "@mui/material/Typography";
import { listJobs, type JobEventView } from "../auth/adminApi";
import { attemptWords } from "../screens/backupWords";
import { plural } from "../shell/plural";
import { formatStamp, relativeAge } from "../shell/time";
import { newestIso, parseTimeMs, type BadgeState } from "./estateModel";
import { EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { standardBackupsHref } from "./estateRouting";
import { ESTATE_MONO, ev } from "./estateTokens";

/** Rows shown in the drawer; the rest are counted in the "+N more" footer. */
export const ATTEMPTS_DRAWER_CAP = 10;

export const ATTEMPTS_TOTAL_UNKNOWN_TEXT = "Total attempts UNKNOWN; more attempts may exist beyond the listed rows";

/** Outcome states the attempt words describe. Anything else (blank, OUTCOME_UNKNOWN, a state we do not know) is UNKNOWN. */
const READABLE_STATES = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "EXECUTING", "CLAIMED", "REQUESTED", "SUBMITTED"]);

export interface AttemptsUnit {
  readonly deviceId: string;
  readonly name: string;
}

/** The outcome word and badge state for one attempt. An unreadable outcome is UNKNOWN, never a pass. */
export function attemptOutcome(job: JobEventView): {
  readonly word: string;
  readonly tone: "ok" | "bad" | "warn" | "neutral";
  readonly badgeState: BadgeState;
} {
  const state = (job.state ?? "").trim().toUpperCase();
  if (!READABLE_STATES.has(state)) return { word: "UNKNOWN", tone: "neutral", badgeState: "unk" };
  const words = attemptWords(state, job.terminal_reason);
  const badgeState: BadgeState =
    words.tone === "ok"
      ? "ok"
      : words.tone === "bad"
        ? "act"
        : words.tone === "warn"
          ? "watch"
          : "na";
  return { word: words.word, tone: words.tone, badgeState };
}

type AttemptsRead =
  | { readonly state: "loading" }
  | { readonly state: "error" }
  | { readonly state: "ok"; readonly items: readonly JobEventView[]; readonly total: number | null };

function AttemptsList({ unit }: { readonly unit: AttemptsUnit }) {
  const [read, setRead] = useState<AttemptsRead>({ state: "loading" });

  useEffect(() => {
    let active = true;
    setRead({ state: "loading" });
    listJobs({ device_id: unit.deviceId, job_type: "backup", page: 1, page_size: ATTEMPTS_DRAWER_CAP })
      .then((value) => {
        if (!active) return;
        if (!value || typeof value !== "object" || !Array.isArray(value.items)) {
          setRead({ state: "error" });
          return;
        }
        const shownCount = Math.min(value.items.length, ATTEMPTS_DRAWER_CAP);
        const total =
          typeof value.total === "number" && Number.isFinite(value.total) && value.total >= shownCount
            ? value.total
            : null;
        setRead({ state: "ok", items: value.items, total });
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
        <Box sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
          <StateBadge state="unk" word="UNKNOWN" dense />
          <Typography component="span" sx={{ fontSize: "12.5px", color: ev.ink2 }}>Backup attempts could not be loaded.</Typography>
        </Box>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={null} source="backup attempts" missingLabel="UNKNOWN" />
        </Box>
      </Box>
    );
  }
  if (read.items.length === 0) {
    return (
      <Box>
        <Typography variant="body2" sx={{ fontSize: "12.5px", color: ev.ink2 }}>No backup attempts on record.</Typography>
        {read.total === null && (
          <Typography data-testid="attempts-drawer-total-unknown" sx={{ fontSize: "12px", color: ev.unk, mt: "4px" }}>
            {ATTEMPTS_TOTAL_UNKNOWN_TEXT}
          </Typography>
        )}
      </Box>
    );
  }

  const shown = read.items.slice(0, ATTEMPTS_DRAWER_CAP);
  const now = new Date();
  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "8px" }}>
        <Typography component="span" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
          Newest of the {plural(shown.length, "attempt", "attempts")} shown:
        </Typography>
        <EvidenceStamp at={newestIso(shown.map((job) => job.submitted_at))} now={now} missingLabel="UNKNOWN" source="newest attempt shown" />
      </Box>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}>
        {shown.map((job) => {
          const outcome = attemptOutcome(job);
          const hasSubmittedAt = parseTimeMs(job.submitted_at) !== null;
          const age = hasSubmittedAt ? relativeAge(job.submitted_at, now) : "UNKNOWN";
          return (
            <Box
              component="li"
              key={job.job_id}
              data-attempt-row={job.job_id}
              sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap", fontSize: "12.5px" }}
            >
              <Box component="span" title={hasSubmittedAt ? formatStamp(job.submitted_at) : "No submission time recorded"} sx={{ fontFamily: ESTATE_MONO, color: ev.ink2, minWidth: "96px" }}>
                {age}
              </Box>
              <Box component="span" data-attempt-outcome={outcome.word}>
                <StateBadge state={outcome.badgeState} word={outcome.word} dense />
              </Box>
            </Box>
          );
        })}
      </Box>
      <Box sx={{ mt: "6px" }}>
        {read.total === null ? (
          <Typography component="span" data-testid="attempts-drawer-total-unknown" sx={{ fontSize: "12px", color: ev.unk }}>
            {ATTEMPTS_TOTAL_UNKNOWN_TEXT}
          </Typography>
        ) : (
          <MoreFooter shown={shown.length} total={read.total} noun="attempts" testId="attempts-drawer-more" />
        )}
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
