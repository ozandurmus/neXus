import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { PATH_VALIDATION_MAX_HOPS, type PathResult, type PathValidation, type PathValidationHop } from "../../auth/adminApi";
import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel } from "../../shell/States";
import { PillRow, StatusPill } from "../../shell/StatusPill";
import { Timestamp } from "../../shell/Timestamp";
import { MONO, m3 } from "../../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../../theme/tokens";
import {
  OVERALL_WORD, UDP_NOTICE, VERIFY_EXPLANATION, checkLabel, countValidation, explainValidation, resultTone, resultWord, summarise,
  verifiedBranchId, windowTime, type ValidationFailure, type ValidationHopView,
} from "./pathValidationWords";
import type { PathVerification } from "./usePathValidation";

/**
 * "Verify on devices" for a computed result (PATH_ANALYSIS_DESIGN.md §7, stage A6): the action with its one-line
 * explanation, the progress while the device jobs run, then the summary. The per-hop badges live on the hop cards
 * (`HopDeviceCheck`). Every refusal the API makes keeps its own honest state; a verification that could not be made is
 * never shown as "not evaluable" hops.
 */
export function PathValidationPanel({ result, verification, now = new Date() }: {
  readonly result: PathResult; readonly verification: PathVerification; readonly now?: Date;
}) {
  const { state, start, checkAgain } = verification;
  const multi = result.branches.length > 1;
  const udp = result.query.protocol === "udp";
  const busy = state.phase === "submitting" || state.phase === "polling";
  const verified = verifiedBranchId(state.validation, state.branchId, result);
  const button = (b: { readonly branch_id: string }, emphasis: "tonal" | "outlined") => (
    <M3Button key={b.branch_id} emphasis={emphasis} disabled={busy} onClick={() => start(multi ? b.branch_id : undefined)}>
      {multi ? `Verify branch ${b.branch_id} on devices` : "Verify on devices"}
    </M3Button>
  );
  /** After the first request: the verified branch again, and (when the route splits) each other branch. */
  const others = multi ? result.branches.filter((b) => b.branch_id !== verified) : [];
  return (
    <Card data-testid="path-verify" data-phase={state.phase}
      sx={{ border: `1px solid ${m3.outlineVar}`, boxShadow: "none", borderRadius: `${RADIUS.card}px`, p: 2.5, bgcolor: m3.scLowest }}>
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography component="h4" sx={{ ...TYPE.section, m: 0 }}>Verify on devices</Typography>
          {state.validation && (
            <StatusPill tone={resultTone(state.validation.result)} word={OVERALL_WORD[state.validation.result] ?? state.validation.result} fact="verify-overall" />
          )}
        </Stack>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 880 }} data-testid="path-verify-explanation">{VERIFY_EXPLANATION}</Typography>
        {udp && (
          <Typography variant="body2" data-testid="path-verify-udp" sx={{ color: m3.onAttentionContainer, bgcolor: m3.attentionContainer, px: 1, py: 0.5, borderRadius: `${RADIUS.pill}px`, maxWidth: 880 }}>
            {UDP_NOTICE}
          </Typography>
        )}

        {state.phase === "idle" && (
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            {result.branches.map((b) => button(b, "tonal"))}
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              One branch per request, its first {PATH_VALIDATION_MAX_HOPS} unit hops; up to 6 requests a minute. Each request is recorded; only hop states are stored.
            </Typography>
          </Stack>
        )}

        {state.phase === "submitting" && (
          <Typography role="status" variant="body1" data-testid="path-verify-progress">Submitting the verification: the server computes the path again and plans one read job per hop…</Typography>
        )}

        {state.phase === "polling" && state.validation && (
          <Progress validation={state.validation} polls={state.polls} lastPolledAt={state.lastPolledAt} now={now} />
        )}

        {state.phase === "stalled" && state.validation && (
          <StatePanel variant="not_evaluated" title="The devices have not all answered yet" announce="status"
            body={`${summarise(state.validation)} Five minutes passed; the remaining jobs stay queued and finish on their own, nothing is lost.`}
            action={<M3Button emphasis="outlined" onClick={checkAgain}>Check again</M3Button>} />
        )}

        {state.phase === "failed" && state.failure && (
          <ValidationFailureState failure={state.failure} afterSubmit={state.validationId !== null}
            retry={() => start(state.branchId)} checkAgain={checkAgain} />
        )}

        {state.phase === "complete" && state.validation && (
          <Summary result={result} validation={state.validation} verified={verified} now={now} />
        )}

        {state.phase === "failed" && (
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            {result.branches.map((b) => button(b, "outlined"))}
          </Stack>
        )}
        {(state.phase === "polling" || state.phase === "complete" || state.phase === "stalled") && (
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <M3Button emphasis="text" disabled={busy} onClick={() => start(multi ? verified : undefined)}>{multi ? `Verify branch ${verified} again` : "Verify again"}</M3Button>
            {others.map((b) => button(b, "outlined"))}
          </Stack>
        )}
      </Stack>
    </Card>
  );
}

function Progress({ validation, polls, lastPolledAt, now }: { readonly validation: PathValidation; readonly polls: number; readonly lastPolledAt: string | null; readonly now: Date }) {
  const c = countValidation(validation);
  return (
    <Stack spacing={0.75} role="status" data-testid="path-verify-progress" aria-live="polite">
      <Typography variant="body1" sx={{ color: m3.onSurface }}>{summarise(validation)}</Typography>
      <PillRow ariaLabel="Hop states so far">
        <StatusPill tone="neutral" word="Pending" count={c.pending} fact="verify-pending" />
        {c.match > 0 && <StatusPill tone="ok" word="Match" count={c.match} fact="verify-match" />}
        {c.mismatch > 0 && <StatusPill tone="bad" word="Mismatch" count={c.mismatch} fact="verify-mismatch" />}
        {c.notEvaluable > 0 && <StatusPill tone="unknown" word="NOT_EVALUABLE" count={c.notEvaluable} fact="verify-not-evaluable" />}
      </PillRow>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Read-only jobs, one per hop, in the device job queue; this page re-reads the record with increasing intervals.
        {polls > 0 ? ` Checked ${polls} time${polls === 1 ? "" : "s"}` : " Submitted"}
        {lastPolledAt ? <>, last <Timestamp at={lastPolledAt} age now={now} /></> : null}.
      </Typography>
    </Stack>
  );
}

function Summary({ result, validation, verified, now }: { readonly result: PathResult; readonly validation: PathValidation; readonly verified: string | undefined; readonly now: Date }) {
  const c = countValidation(validation);
  const multi = result.branches.length > 1;
  return (
    <Stack spacing={0.75} role="status" data-testid="path-verify-summary" data-result={validation.result}>
      <Typography variant="body1" sx={{ color: m3.onSurface, fontWeight: 600 }}>{summarise(validation)}</Typography>
      {c.mismatch > 0 && (
        <Typography variant="body2" sx={{ color: m3.onErrorContainer, bgcolor: m3.errorContainer, px: 1, py: 0.5, borderRadius: `${RADIUS.pill}px`, maxWidth: 880 }}>
          A device's own matcher disagrees with the computed hop (marked on the hop). The computed verdict is management intent; the device's answer is what it would do now for this sample.
        </Typography>
      )}
      {c.total > 0 && c.notContacted === c.total && (
        <Typography variant="body2" data-testid="path-verify-none-contacted" sx={{ color: m3.onSurfaceVar }}>No device was contacted: every hop was refused before a job reached it (the reasons are on the hops).</Typography>
      )}
      {multi && verified && (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Branch {verified} was verified; the other equal-cost branches were not asked by this request.</Typography>
      )}
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 880 }}>{validation.limitations}</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Verification <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{validation.validation_id}</Box>
        {" · submitted "}<Timestamp at={validation.submitted_at} age now={now} />
        {validation.topology_snapshot_id !== result.snapshot_id ? " · computed again on a newer topology snapshot than the result shown" : ""}
      </Typography>
    </Stack>
  );
}

/** Every refusal of a verification in its own words; the code stays visible in mono so the engineer can quote it. */
export function ValidationFailureState({ failure, afterSubmit, retry, checkAgain }: {
  readonly failure: ValidationFailure; readonly afterSubmit: boolean; readonly retry: () => void; readonly checkAgain: () => void;
}) {
  const retryButton = <M3Button emphasis="outlined" onClick={retry}>Retry</M3Button>;
  const again = <M3Button emphasis="outlined" onClick={checkAgain}>Check again</M3Button>;
  if (afterSubmit && failure.kind !== "restricted" && failure.kind !== "record_gone") {
    return (
      <StatePanel variant="error" title="The verification record could not be read" code={failure.code ?? `HTTP ${failure.status}`} action={again}
        body="The verification was submitted and its jobs go on; this page could not read the record three times in a row. Check again; the hop states so far are kept." />
    );
  }
  switch (failure.kind) {
    case "restricted":
      return <RestrictedPanel area="Verification on devices" role="Security Administrator, Operation Administrator or aiview"
        nextStep="The computed verdict above stands as it is; nothing was sent to a device." />;
    case "window": {
      const at = windowTime(failure.nextWindowStart);
      return (
        <StatePanel variant="not_evaluated" title="Outside the job window" code={failure.code} announce="status"
          body={failure.message ?? "Device jobs run only inside the scheduled windows. Nothing was sent and nothing was written; the computed verdict stands as it is."}
          nextStep={at ? `Next window: ${at}. Evaluate the path again then and verify.` : "Verify again inside the next window."} />
      );
    }
    case "not_confirmed":
      return (
        <StatePanel variant="not_evaluated" title="Not started: business hours were not confirmed" code={failure.code} announce="status"
          body="Inside business hours a manual device job needs the confirmation. Nothing was sent." action={retryButton} />
      );
    case "not_built":
      return (
        <StatePanel variant="not_evaluated" title="Topology not built" code={failure.code} announce="status"
          body="The server computes the path again before asking any device, and no topology is held any more. A verification never builds one."
          nextStep="A Security Administrator can build it with a topology rebuild from the stored inventory; then evaluate and verify again." />
      );
    case "refused":
      return (
        <StatePanel variant="empty" title="Verification refused: a value was not recognised" code={failure.code} announce="status"
          body="The server computes the path again from the masked query and could not resolve a value this time. A raw, unknown and ambiguous value are refused in one and the same way; nothing about them is stored."
          nextStep="Evaluate the path again, then verify the new result." />
      );
    case "branch_gone":
      return (
        <StatePanel variant="not_evaluated" title="This branch is not in the recomputed path" code={failure.code} announce="status"
          body="The server computed the path again and the branch asked for does not exist in that computation (the topology or a policy changed since this result). Nothing was sent."
          nextStep="Evaluate the path again, then verify the new result." />
      );
    case "rate_limited":
      return (
        <StatePanel variant="error" title="Verification limit reached" code={failure.code} action={retryButton}
          body="At most 6 verifications a minute per user. Retry after one minute; nothing was sent." />
      );
    case "busy":
      return (
        <StatePanel variant="error" title="Path analysis is busy" code={failure.code} action={retryButton}
          body="Every analysis slot is taken by a policy or path computation. Nothing was sent; retry in a few seconds." />
      );
    case "audit":
      return (
        <StatePanel variant="error" title="Not started: the request record could not be written" code={failure.code} action={retryButton}
          body="Every verification is recorded (the masked query, the verdict and the hop counts) before any job is admitted. The record could not be written, so nothing was sent." />
      );
    case "invalid":
      return (
        <StatePanel variant="error" title="The verification request was not accepted" code={failure.code} announce="status"
          body="The body is the computed result's own query and the branch; the server did not accept it. Evaluate the path again and verify the new result." />
      );
    case "record_gone":
      return (
        <StatePanel variant="error" title="The verification record was not found" code={failure.code}
          body="The record this page was reading no longer exists on the server. The hop states shown so far are the last ones read." action={retryButton} />
      );
    case "offline":
      return (
        <StatePanel variant="error" title="The request did not complete" code={failure.code} action={retryButton}
          body="No answer arrived from the server in time. Whether the verification was admitted is not known: check the connection and retry; a repeat inside the same minute counts against the limit." />
      );
    default:
      return (
        <StatePanel variant="error" title="The verification could not be started" code={failure.code ?? `HTTP ${failure.status}`} action={retryButton}
          body="The server answered with a failure this screen does not know. No hop state is claimed." />
      );
  }
}

/** The device's badge beside the neXus verdict of one hop. */
export function HopDeviceBadge({ view }: { readonly view: ValidationHopView }) {
  if (view.kind === "none") return null;
  if (view.kind === "beyond_cap") return <StatusPill tone="unknown" word="Device not asked" dense fact="hop-device" title={`Beyond the ${PATH_VALIDATION_MAX_HOPS}-hop cap of one request.`} />;
  const hop = view.hop;
  return <StatusPill tone={resultTone(hop.result)} word={resultWord(hop.result)} dense fact="hop-device"
    suffix={hop.result === "PENDING" && hop.job_state ? `job ${hop.job_state.toLowerCase()}` : null} />;
}

/** What the device said about one hop, in plain words: each check with its outcome, then the hop's own reasons. */
export function HopDeviceCheck({ view, hopIndex }: { readonly view: ValidationHopView; readonly hopIndex: number }) {
  if (view.kind === "none") return null;
  const bg = view.kind === "validated" && view.hop.result === "MISMATCH" ? m3.errorContainer : m3.scLow;
  const ink = view.kind === "validated" && view.hop.result === "MISMATCH" ? m3.onErrorContainer : m3.onSurface;
  return (
    <Box data-testid={`hop-${hopIndex}-device`} sx={{ p: 1, borderRadius: `${RADIUS.card}px`, bgcolor: bg, color: ink }}>
      <Typography component="div" sx={{ ...LABEL_UPPER, mb: 0.5 }}>Device check</Typography>
      {view.kind === "beyond_cap"
        ? <Typography variant="body2">Not asked: beyond the {PATH_VALIDATION_MAX_HOPS}-hop cap of one request. The computed verdict for this hop is unverified.</Typography>
        : <HopChecks hop={view.hop} />}
    </Box>
  );
}

function HopChecks({ hop }: { readonly hop: PathValidationHop }) {
  if (hop.result === "PENDING") {
    return <Typography variant="body2">Waiting for the device job{hop.job_state ? ` (${hop.job_state.toLowerCase()})` : ""}; nothing is claimed yet.</Typography>;
  }
  const covered = new Set(hop.checks.map((c) => c.reason));
  const rest = hop.reasons.filter((r) => !covered.has(r));
  return (
    <Stack component="ul" spacing={0.25} sx={{ m: 0, pl: 0, listStyle: "none" }}>
      {hop.checks.map((c) => (
        <Typography component="li" key={`${c.check}-${c.reason}`} variant="body2" data-check={c.check} data-check-result={c.result}>
          <Box component="span" sx={{ fontWeight: 600 }}>{checkLabel(c.check)}: {c.result}</Box>{" "}
          {explainValidation(c.reason)}
          <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, ml: 1, opacity: 0.85 }}>{c.reason}</Box>
        </Typography>
      ))}
      {rest.map((code) => (
        <Typography component="li" key={code} variant="body2" data-code={code}>
          {hop.checks.length === 0 ? <><Box component="span" sx={{ fontWeight: 600 }}>Not asked:</Box>{" "}</> : null}
          {explainValidation(code)}
          <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, ml: 1, opacity: 0.85 }}>{code}</Box>
        </Typography>
      ))}
      {hop.checks.length === 0 && rest.length === 0 && <Typography component="li" variant="body2">No check was recorded for this hop.</Typography>}
    </Stack>
  );
}
