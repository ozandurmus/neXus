import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { Icon } from "../shell/Icon";
import { M3Button } from "../shell/M3Widgets";
import { StatePanel } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { DISPLAY_TZ, DISPLAY_TZ_LABEL, formatStamp } from "../shell/time";
import type { Tone } from "../shell/tone";
import { m3 } from "../theme/m3Theme";
import {
  getScheduledJobs,
  setScheduledJobFamily,
  type ApiError,
  type ScheduledJobFamily,
  type ScheduledJobFamilyUpdate,
  type ScheduledJobsView,
} from "../auth/adminApi";

export const REASON_MIN = 5;
export const REASON_MAX = 500;
export const REASON_WITHHELD = "Reason withheld (masked view)";

interface Problem { readonly message: string; readonly code: string | null }

function problemOf(err: unknown): Problem {
  const e = err as ApiError | undefined;
  const body = (e?.body ?? {}) as { message?: unknown; code?: unknown; error?: unknown };
  const code = typeof body.code === "string" ? body.code : typeof body.error === "string" ? body.error : null;
  if (typeof body.message === "string" && body.message) return { message: body.message, code };
  return { message: `The request failed${e?.status ? ` (status ${e.status})` : ""}.`, code };
}

/** `in 12 min`, `in 3 h`, `in 2 d` for an instant ahead of now; null when it is not ahead or not a date. */
export function untilText(iso: string | null | undefined, now: Date = new Date()): string | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return null;
  const s = (t - now.getTime()) / 1000;
  if (s <= 0) return null;
  if (s < 90) return "in about a minute";
  if (s < 3600) return `in ${Math.round(s / 60)} min`;
  if (s < 172800) return `in ${Math.round(s / 3600)} h`;
  return `in ${Math.round(s / 86400)} d`;
}

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** When jobs start after a family is switched ON: right away in an open window for families that admit work all through it. */
function resumeWords(family: { readonly label: string; readonly resumes_in_open_window?: boolean; readonly next_window_at: string | null }): string {
  if (family.resumes_in_open_window) return `${family.label} jobs may start within a minute: the window is open now.`;
  return `${family.label} jobs start again at the next window${family.next_window_at ? `: ${formatStamp(family.next_window_at)}` : ""}.`;
}

/** The sentence shown after a save: the new state, and what switching OFF cleaned up. */
export function savedSentence(update: ScheduledJobFamilyUpdate): string {
  if (update.enabled && !update.will_run) {
    return `${update.label} is ON, but idle${update.note ? `: ${update.note}` : ""}. No job runs until that is resolved.`;
  }
  if (update.enabled) {
    return `${update.label} is ON. ${resumeWords(update)}`;
  }
  const parts = [
    update.cancelled_jobs > 0 ? `${count(update.cancelled_jobs, "queued job was", "queued jobs were")} cancelled` : null,
    update.closed_runs > 0 ? `${count(update.closed_runs, "open run was", "open runs were")} closed` : null,
  ].filter((part): part is string => part !== null);
  return `${update.label} is OFF. ${parts.length ? `${parts.join(" and ")}.` : "No queued job needed cancelling."}`;
}

const STATE_TONE: Readonly<Record<string, Tone>> = { ON: "ok", OFF: "warn", LOCKED: "neutral" };
/** ON with `will_run` false: the switch is on, yet something else stops the family. Never drawn as running. */
export const IDLE_WORD = "ON, but idle";
/** The server's own answer to "does this family run"; a missing value is never read as yes. */
const willRun = (family: ScheduledJobFamily) => family.will_run === true;
/** What stops a family that will not run, as the word used in the row. */
function stoppedWord(family: ScheduledJobFamily): string {
  return family.state === "ON" ? "idle" : family.state;
}

/** What an admin looks for first: how many families run, and which one runs next. */
function Summary({ view, now }: { readonly view: ScheduledJobsView; readonly now: Date }) {
  const on = view.families.filter(willRun);
  const idle = view.families.filter((f) => f.state === "ON" && !willRun(f));
  const open = on.filter((f) => f.window_open_now);
  const upcoming = on.filter((f) => f.next_window_at && !Number.isNaN(new Date(f.next_window_at).getTime()))
    .sort((a, b) => new Date(a.next_window_at!).getTime() - new Date(b.next_window_at!).getTime());
  const firstAt = upcoming[0]?.next_window_at ?? null;
  const first = firstAt ? upcoming.filter((f) => new Date(f.next_window_at!).getTime() === new Date(firstAt).getTime()) : [];
  const ahead = untilText(firstAt, now);
  return (
    <Box data-testid="scheduled-jobs-summary" sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
      <Typography variant="body1" sx={{ fontWeight: 600, color: m3.onSurface }}>
        {on.length} of {view.families.length} job families will run
      </Typography>
      {idle.length > 0 && (
        <Typography variant="body2" sx={{ color: m3.onSurface }}>
          {IDLE_WORD}: {idle.map((f) => f.label).join(", ")}
        </Typography>
      )}
      {open.length > 0 && (
        <Typography variant="body2" sx={{ color: m3.onSurface }}>
          Window open now: {open.map((f) => f.label).join(", ")}
        </Typography>
      )}
      <Typography variant="body2" sx={{ color: m3.onSurface }}>
        {on.length === 0
          ? "Nothing is scheduled to run."
          : first.length === 0
            ? "Next window: UNKNOWN"
            : <>Next to run: {first.map((f) => f.label).join(", ")} · <Timestamp at={firstAt} age={false} />{ahead ? ` · ${ahead}` : ""}</>}
      </Typography>
    </Box>
  );
}

function Fact({ label, children }: { readonly label: string; readonly children: React.ReactNode }) {
  return (
    <Box sx={{ display: "grid", gridTemplateColumns: "96px 1fr", columnGap: 1.5, alignItems: "baseline" }}>
      <Typography component="dt" variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.04em", textTransform: "uppercase" }}>{label}</Typography>
      <Box component="dd" sx={{ m: 0, minWidth: 0, fontSize: 14, color: m3.onSurface, display: "flex", flexDirection: "column", gap: 0.25 }}>{children}</Box>
    </Box>
  );
}

function FamilyRow({ family, masked, canWrite, busy, now, onFlip }: {
  readonly family: ScheduledJobFamily; readonly masked: boolean; readonly canWrite: boolean; readonly busy: boolean;
  readonly now: Date; readonly onFlip: () => void;
}) {
  const running = willRun(family);
  const idle = family.state === "ON" && !running;
  const locked = family.state === "LOCKED";
  const ahead = untilText(family.next_window_at, now);
  const neverChanged = !family.changed_at && !family.changed_by;
  return (
    <Box component="li" data-testid={`scheduled-family-${family.family}`} data-state={family.state} aria-label={family.label}
      sx={{ listStyle: "none", display: "grid", gap: 2, alignItems: "start", px: 2, py: 1.75,
            gridTemplateColumns: { xs: "1fr", md: canWrite ? "minmax(220px, 5fr) minmax(280px, 6fr) 96px" : "minmax(220px, 5fr) minmax(280px, 6fr)" },
            borderTop: `1px solid ${m3.outlineVar}`, "&:first-of-type": { borderTop: "none" } }}>
      <Box sx={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          <Typography component="h4" sx={{ fontSize: 15, fontWeight: 600, color: m3.onSurface, m: 0 }}>{family.label}</Typography>
          <StatusPill tone={idle ? "warn" : STATE_TONE[family.state] ?? "neutral"} word={idle ? IDLE_WORD : family.state} fact="scheduled-family-state" />
        </Box>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{family.description}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurface }}>{family.cadence}</Typography>
        {locked && (
          <Typography variant="body2" data-testid="scheduled-family-lock" sx={{ display: "flex", gap: 0.75, alignItems: "flex-start", color: m3.onSurface, fontWeight: 500 }}>
            <Box component="span" sx={{ display: "inline-flex", pt: "2px" }}><Icon name="lock" size={14} /></Box>
            <span>Locked: {family.lock_reason ?? "a deployment switch stops this family."}{" "}
              The stored setting is {family.enabled ? "on" : "off"}.</span>
          </Typography>
        )}
        {family.note && (
          <Typography variant="body2" data-testid="scheduled-family-note" sx={{ display: "flex", gap: 0.75, alignItems: "flex-start", color: m3.onSurface }}>
            <Box component="span" sx={{ display: "inline-flex", pt: "2px" }}><Icon name="info" size={14} /></Box>
            <span>{idle ? "Idle: " : ""}{family.note}</span>
          </Typography>
        )}
      </Box>

      <Box component="dl" sx={{ m: 0, display: "flex", flexDirection: "column", gap: 0.75, minWidth: 0 }}>
        <Fact label="Next window">
          {running ? (
            <>
              {family.window_open_now && <Box component="span" sx={{ fontWeight: 600 }}>Window open now</Box>}
              <span><Timestamp at={family.next_window_at} age={false} />{ahead ? ` · ${ahead}` : ""}</span>
            </>
          ) : (
            <>
              <Box component="span" sx={{ fontWeight: 600 }}>Will not run while {stoppedWord(family)}</Box>
              {family.next_window_at && (
                <Box component="span" sx={{ color: m3.onSurfaceVar }}>The window at <Timestamp at={family.next_window_at} age={false} /> is skipped.</Box>
              )}
            </>
          )}
        </Fact>
        <Fact label="Last run">
          {family.last_run_at ? <span><Timestamp at={family.last_run_at} /></span> : <span>No run recorded</span>}
        </Fact>
        <Fact label="Last change">
          {neverChanged ? <span>Not changed since installation</span> : (
            <>
              <span>{family.changed_by ?? "UNKNOWN"} · <Timestamp at={family.changed_at} /></span>
              <Box component="span" data-testid="scheduled-family-reason" sx={{ color: m3.onSurfaceVar, overflowWrap: "anywhere" }}>
                {masked ? REASON_WITHHELD : family.reason ? `Reason: ${family.reason}` : "No reason recorded"}
              </Box>
            </>
          )}
        </Fact>
      </Box>

      {canWrite && (
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: { xs: "flex-start", md: "flex-end" }, gap: 0.5 }}>
          <Switch checked={family.enabled} disabled={locked || busy} onChange={onFlip}
            inputProps={{ role: "switch", "aria-label": `${family.label}: scheduled jobs on` }} />
          <Typography aria-hidden variant="body2" sx={{ minWidth: 24, fontWeight: 500, color: locked ? m3.onSurfaceVar : m3.onSurface }}>
            {family.enabled ? "On" : "Off"}
          </Typography>
        </Box>
      )}
    </Box>
  );
}

function ChangeDialog({ family, enable, view, onCancel, onSaved, onSettled }: {
  readonly family: ScheduledJobFamily; readonly enable: boolean; readonly view: ScheduledJobsView;
  readonly onCancel: () => void; readonly onSaved: (update: ScheduledJobFamilyUpdate) => void; readonly onSettled: () => void;
}) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const trimmed = reason.trim();
  const valid = trimmed.length >= REASON_MIN && trimmed.length <= REASON_MAX;
  const hours = view.business_hours;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setProblem(null);
    try {
      onSaved(await setScheduledJobFamily(family.family, enable, trimmed));
    } catch (e) {
      setProblem(problemOf(e));
      setBusy(false);
    } finally {
      // The server is the authority, on success and on refusal alike: read the list again.
      onSettled();
    }
  };

  return (
    <Dialog open onClose={busy ? undefined : onCancel} fullWidth maxWidth="sm" aria-labelledby="scheduled-jobs-change-title">
      <DialogTitle id="scheduled-jobs-change-title">Turn {enable ? "ON" : "OFF"} {family.label}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          <Typography variant="body1" data-testid="scheduled-jobs-effect">
            {enable
              ? resumeWords(family)
              : `No new ${family.label} jobs will be submitted from the next tick. Scheduled ${family.label} jobs still queued are cancelled; jobs already running finish; manually started jobs are not touched.`}
          </Typography>
          {hours?.inside_now && (
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              It is business hours ({hours.start} to {hours.end} {hours.zone === DISPLAY_TZ ? DISPLAY_TZ_LABEL : hours.zone}), so you will be asked to confirm once more after Save.
            </Typography>
          )}
          <TextField label="Reason" required autoFocus multiline minRows={2} value={reason} disabled={busy}
            onChange={(e) => setReason(e.target.value)}
            inputProps={{ maxLength: REASON_MAX + 50 }}
            error={trimmed.length > REASON_MAX}
            helperText={`${trimmed.length} of ${REASON_MIN} to ${REASON_MAX} characters. Shown on this page with your account and the time.`} />
          {problem && <StatePanel variant="error" announce="alert" title="Not saved" body={problem.message} code={problem.code} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" disabled={busy} onClick={onCancel}>Cancel</M3Button>
        <M3Button emphasis="filled" disabled={!valid || busy} onClick={() => void save()}>{busy ? "Saving…" : "Save"}</M3Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Administration › Platform › Scheduled jobs (`GET /api/v2/config/scheduled-jobs`, `PUT .../{family}`): one row per
 * scheduled job family with its effective state as a word (ON, "ON, but idle", OFF, LOCKED), when it last ran, its next window and
 * who changed it, when and why. The screen answers two questions: what runs next, and how to stop one family safely.
 *
 * Every signed-in role reads it. A switch is drawn only when the server says this session may write (`can_write`);
 * a reader sees no control at all, only the facts. Flipping a switch saves nothing: it opens a dialog that states the
 * effect and asks for a reason, and Save sends the request. The business-hours confirmation is the shared one inside
 * `adminApi.call`; this panel builds no second path. After every save or refusal the list is read again.
 */
export function ScheduledJobsPanel({ now }: { readonly now?: Date } = {}) {
  const [view, setView] = useState<ScheduledJobsView | null>(null);
  const [readAt, setReadAt] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<Problem | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<{ readonly family: ScheduledJobFamily; readonly enable: boolean } | null>(null);

  const load = useCallback(() => {
    setRefreshing(true);
    setLoadError(null);
    getScheduledJobs()
      .then((next) => { setView(next); setReadAt(new Date().toISOString()); })
      .catch((e) => setLoadError(problemOf(e)))
      .finally(() => setRefreshing(false));
  }, []);
  useEffect(() => { load(); }, [load]);

  if (!view) {
    if (loadError) {
      return <StatePanel variant="error" title="The scheduled jobs list is unavailable" body={loadError.message} code={loadError.code}
        action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
    }
    return <Typography sx={{ p: 3 }} role="status">Loading scheduled jobs…</Typography>;
  }

  const clock = now ?? new Date();
  const families = view.families ?? [];
  const saved = (update: ScheduledJobFamilyUpdate) => {
    setPending(null);
    setNotice(savedSentence(update));
    // Show the server's answer at once; the re-read that follows replaces the whole list.
    setView((current) => current && { ...current, families: current.families.map((f) => (f.family === update.family ? update : f)) });
  };

  return (
    <Stack spacing={2} sx={{ maxWidth: 1040 }}>
      <Box sx={{ display: "flex", gap: 2, alignItems: "flex-start", justifyContent: "space-between", flexWrap: "wrap" }}>
        <Box sx={{ minWidth: 0, flex: 1, display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 760 }}>
            The jobs the product starts by itself, grouped into families. Each family is switched on or off on its own;
            a family that is OFF submits no new jobs until it is switched on again.
          </Typography>
          {families.length > 0 && <Summary view={view} now={clock} />}
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flex: "none" }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Read <Timestamp at={readAt} age={false} seconds /></Typography>
          <M3Button emphasis="text" disabled={refreshing} onClick={load}>{refreshing ? "Refreshing…" : "Refresh"}</M3Button>
        </Box>
      </Box>

      {!view.master_enabled && (
        <Box role="status" data-testid="scheduled-jobs-master-off"
          sx={{ display: "flex", gap: 1.5, alignItems: "flex-start", p: 2, borderRadius: "12px", bgcolor: m3.warningContainer, color: m3.onWarningContainer }}>
          <Box sx={{ display: "flex", pt: 0.25 }}><Icon name="lock" size={20} /></Box>
          <Box>
            <Typography sx={{ fontSize: 15, fontWeight: 600 }}>Scheduling is switched off by the deployment ({view.master_env}=false)</Typography>
            <Typography variant="body2">
              {view.families.some((f) => f.state !== "LOCKED")
                ? "Only the families shown as not locked still run (system log collection runs on its own timer). The switches of the locked families are locked. "
                : "No scheduled job family runs and the switches on this page are locked. "}
              Turning scheduling back on needs a deployment change; it can not be done from this page.
            </Typography>
          </Box>
        </Box>
      )}

      {loadError && (
        <Typography role="status" variant="body2" data-testid="scheduled-jobs-stale">
          The list was not refreshed ({loadError.message}). The values below are from the last read.
        </Typography>
      )}
      {!view.can_write && (
        <Typography role="status" variant="body2" data-testid="scheduled-jobs-read-only">
          Read-only: turning a job family on or off needs the Security Admin or Operation Admin role.
          {view.masked ? " Masked view: accounts are shown as tokens and reasons are withheld." : ""}
        </Typography>
      )}
      {notice && <Typography role="status" variant="body2" data-testid="scheduled-jobs-notice" sx={{ fontWeight: 500 }}>{notice}</Typography>}

      {families.length === 0 ? (
        <StatePanel variant="empty" title="No scheduled job family is defined"
          body="The server returned an empty list, so there is nothing to switch on or off here." />
      ) : (
        <Card component="ul" aria-label="Scheduled job families"
          sx={{ m: 0, p: 0, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" }}>
          {families.map((family) => (
            <FamilyRow key={family.family} family={family} masked={view.masked} canWrite={view.can_write} busy={refreshing} now={clock}
              onFlip={() => { setNotice(null); setPending({ family, enable: !family.enabled }); }} />
          ))}
        </Card>
      )}

      {pending && (
        <ChangeDialog key={`${pending.family.family}-${pending.enable}`} family={pending.family} enable={pending.enable} view={view}
          onCancel={() => setPending(null)} onSaved={saved} onSettled={load} />
      )}
    </Stack>
  );
}
