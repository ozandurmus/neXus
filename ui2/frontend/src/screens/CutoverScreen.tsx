import { useCallback, useEffect, useState } from "react";
import { Alert, Box, Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";

import {
  createCutoverSegment, deleteCutoverSegment, getCutoverSegment, listCutoverSegments, updateCutoverSegment,
  type CutoverAssessmentHead, type CutoverSegment,
} from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { ROLE_LOCK_LABEL } from "../shell/actionAccess";
import { formatStamp } from "../shell/time";
import { urlParam, useUrlParamSync, writeUrlParams } from "../shell/urlParams";
import { m3 } from "../theme/m3Theme";
import { CutoverAssessmentPage } from "./cutover/CutoverAssessmentPage";
import { CutoverNewAssessment } from "./cutover/CutoverNewAssessment";
import { cutoverFailureText } from "./cutover/cutoverErrors";
import { readinessOf, targetSourceLabel } from "./cutover/cutoverWording";

/**
 * Cutover impact (ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md section 4, step 1; CUTOVER_IMPACT_UI_BRIEF_2026_11_14.md),
 * under Policy and changes. Before a segment's traffic is redirected through other firewalls, the uploaded traffic logs
 * of the segment are replayed against the stored policy of the firewall each flow would cross. Three levels, each an
 * address: the segment list (`?screen=cutover`), one segment (`&segment=`), one assessment (`&assessment=`).
 * Every verdict is the server's; nothing is decided in the browser.
 */
export function CutoverScreen() {
  const [segment, setSegment] = useState(() => urlParam("segment"));
  const [assessment, setAssessment] = useState(() => urlParam("assessment"));
  useUrlParamSync("segment", setSegment);
  useUrlParamSync("assessment", setAssessment);
  const open = (changes: { segment?: string | null; assessment?: string | null }) => {
    const next = { segment: changes.segment === undefined ? segment : changes.segment, assessment: changes.assessment ?? null };
    setSegment(next.segment); setAssessment(next.assessment);
    writeUrlParams({ segment: next.segment, assessment: next.assessment, tab: null }, "push");
  };
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Cutover impact"
        subtitle="Before a segment's traffic is redirected, replay its uploaded traffic logs against the stored policy of the firewall each flow would cross. Nothing is sent to any device." />
      {assessment ? <CutoverAssessmentPage assessmentId={assessment} back={() => open({ assessment: null })} />
        : segment ? <SegmentPage segmentId={segment} back={() => open({ segment: null })} openAssessment={(id) => open({ assessment: id })} />
        : <SegmentList openSegment={(id) => open({ segment: id })} />}
    </ScreenRoot>
  );
}

const lines = (text: string) => text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);

/** Create or edit a segment. Blocks are one CIDR per line; the server refuses a non-canonical block and its message is shown as it is. */
export function SegmentForm({ initial, onSaved, onCancel }: {
  readonly initial?: CutoverSegment; readonly onSaved: (id: string) => void; readonly onCancel: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [blocks, setBlocks] = useState((initial?.blocks ?? []).join("\n"));
  const [peers, setPeers] = useState((initial?.peerBlocks ?? []).join("\n"));
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const save = async () => {
    setBusy(true); setError("");
    const input = { name: name.trim(), blocks: lines(blocks), peerBlocks: lines(peers) };
    try {
      if (initial) { await updateCutoverSegment(initial.segmentId, initial.revision, input); onSaved(initial.segmentId); }
      else onSaved((await createCutoverSegment(input)).segmentId);
    } catch (failure) { setError(cutoverFailureText(failure, "The segment could not be saved.")); }
    finally { setBusy(false); }
  };
  return <Box component="form" aria-label={initial ? "Edit segment" : "New segment"} onSubmit={(e) => { e.preventDefault(); void save(); }}
    sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1, p: 2, my: 2, display: "flex", flexDirection: "column", gap: 2, maxWidth: 720 }}>
    <Typography variant="subtitle1" component="h2">{initial ? `Edit segment ${initial.name}` : "New segment"}</Typography>
    {error && <Alert severity="error" data-fact="segment-error">{error}</Alert>}
    <TextField label="Name" value={name} required disabled={busy} inputProps={{ maxLength: 64 }} onChange={(e) => setName(e.target.value)} helperText="1 to 64 characters" />
    <TextField label="Segment blocks" value={blocks} required multiline minRows={3} disabled={busy} onChange={(e) => setBlocks(e.target.value)}
      helperText="The addresses being moved behind the fabric: one canonical CIDR per line, for example 192.0.2.0/24" />
    <TextField label="Peer blocks" value={peers} required multiline minRows={3} disabled={busy} onChange={(e) => setPeers(e.target.value)}
      helperText="The addresses the segment talks to that the redirect affects: one canonical CIDR per line" />
    <Stack direction="row" gap={1}>
      <Button type="submit" variant="contained" disabled={busy || !name.trim() || lines(blocks).length === 0 || lines(peers).length === 0}>Save segment</Button>
      <Button disabled={busy} onClick={onCancel}>Cancel</Button>
    </Stack>
  </Box>;
}

function SegmentList({ openSegment }: { readonly openSegment: (id: string) => void }) {
  const [data, setData] = useState<{ segments: CutoverSegment[]; canManage: boolean } | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  useEffect(() => {
    let active = true;
    listCutoverSegments().then((result) => { if (active) setData(result); }).catch((error) => { if (active) { setFailure(error); setData({ segments: [], canManage: false }); } });
    return () => { active = false; };
  }, []);
  if (data === null) return <StatePanel variant="empty" title="Reading the cutover segments…" announce="status" />;
  if (failure) return isRestricted(failure) ? <RestrictedPanel area="Cutover impact" />
    : <StatePanel variant="error" title="Cutover segments could not be read" body={cutoverFailureText(failure, "The segment list could not be loaded.")} />;
  return <Box>
    <Stack direction="row" gap={1} alignItems="center" sx={{ mb: 1 }}>
      <Button variant="outlined" disabled={!data.canManage || creating} onClick={() => setCreating(true)}>New segment</Button>
      {!data.canManage && <Typography variant="caption" data-fact="role-lock">{ROLE_LOCK_LABEL}</Typography>}
    </Stack>
    {creating && <SegmentForm onCancel={() => setCreating(false)} onSaved={openSegment} />}
    {data.segments.length === 0 ? <StatePanel variant="empty" title="No cutover segment yet"
        body="A segment names the address blocks being moved behind the fabric and the peers they talk to. Each assessment replays that segment's uploaded logs."
        nextStep={data.canManage ? "Create a segment with New segment." : "Security and onboarding administrators create segments."} />
      : <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Cutover segments">
        <TableHead><TableRow><TableCell>Segment</TableCell><TableCell align="right">Blocks</TableCell><TableCell align="right">Peer blocks</TableCell><TableCell>Updated</TableCell></TableRow></TableHead>
        <TableBody>{data.segments.map((s) => <TableRow key={s.segmentId} hover data-segment={s.segmentId}>
          <TableCell><Button size="small" onClick={() => openSegment(s.segmentId)} sx={{ textTransform: "none" }}>{s.name}</Button></TableCell>
          <TableCell align="right">{s.blocks?.length ?? 0}</TableCell>
          <TableCell align="right">{s.peerBlocks?.length ?? 0}</TableCell>
          <TableCell>{formatStamp(s.updatedAt ?? s.createdAt)}</TableCell>
        </TableRow>)}</TableBody>
      </Table></Box>}
  </Box>;
}

/** One assessment's line in a segment's history: its verdict chip, its target label and the server's headline. */
export function AssessmentRow({ head, onOpen }: { readonly head: CutoverAssessmentHead; readonly onOpen: () => void }) {
  const readiness = readinessOf(head.readiness);
  return <TableRow hover data-assessment={head.assessmentId}>
    <TableCell><Button size="small" onClick={onOpen} sx={{ textTransform: "none" }}>{formatStamp(head.createdAt)}</Button></TableCell>
    <TableCell><StatusPill dense tone={readiness.tone} word={readiness.word} fact="readiness" /></TableCell>
    <TableCell>{targetSourceLabel(head.targetSource)}</TableCell>
    <TableCell>{head.headline ?? ""}{head.status === "INCOMPLETE" ? " Incomplete." : ""}</TableCell>
  </TableRow>;
}

function SegmentPage({ segmentId, back, openAssessment }: { readonly segmentId: string; readonly back: () => void; readonly openAssessment: (id: string) => void }) {
  const [data, setData] = useState<(CutoverSegment & { assessments: CutoverAssessmentHead[]; canManage: boolean }) | null>(null);
  const [failure, setFailure] = useState<unknown>(null), [editing, setEditing] = useState(false), [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  useEffect(() => {
    let active = true; setFailure(null);
    getCutoverSegment(segmentId).then((result) => { if (active) setData(result); }).catch((e) => { if (active) setFailure(e); });
    return () => { active = false; };
  }, [segmentId, refresh]);
  const reload = useCallback(() => { setEditing(false); setRefresh((n) => n + 1); }, []);
  const remove = async () => {
    if (!data || !window.confirm(`Delete segment ${data.name} and its ${data.assessments.length} stored assessments? This cannot be undone.`)) return;
    setBusy(true); setError("");
    try { await deleteCutoverSegment(segmentId); back(); }
    catch (e) { setError(cutoverFailureText(e, "The segment could not be deleted.")); }
    finally { setBusy(false); }
  };
  if (failure) return <Box><Button onClick={back}>All segments</Button>{isRestricted(failure) ? <RestrictedPanel area="Cutover impact" />
    : <StatePanel variant="error" title="The segment could not be read" body={cutoverFailureText(failure, "The segment could not be loaded.")} />}</Box>;
  if (!data) return <StatePanel variant="empty" title="Reading the segment…" announce="status" />;
  return <Box role="region" aria-label={`Segment ${data.name}`}>
    <Button onClick={back}>All segments</Button>
    <Stack direction="row" gap={1} alignItems="baseline" flexWrap="wrap">
      <Typography variant="h6" component="h2">{data.name}</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Revision {data.revision} · updated {formatStamp(data.updatedAt ?? data.createdAt)}</Typography>
    </Stack>
    {error && <Alert severity="error">{error}</Alert>}
    {editing ? <SegmentForm initial={data} onCancel={() => setEditing(false)} onSaved={reload} />
      : <Stack direction={{ xs: "column", md: "row" }} gap={3} sx={{ my: 1 }}>
        {([["Segment blocks", data.blocks], ["Peer blocks", data.peerBlocks]] as const).map(([label, values]) => <Box key={label} data-fact={label === "Segment blocks" ? "blocks" : "peer-blocks"}>
          <Typography variant="subtitle2">{label} · {values.length}</Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5, maxHeight: 180, overflow: "auto", fontFamily: "monospace" }}>{values.map((v) => <li key={v}>{v}</li>)}</Box>
        </Box>)}
      </Stack>}
    {!editing && <Stack direction="row" gap={1} alignItems="center">
      <Button disabled={!data.canManage || busy} onClick={() => setEditing(true)}>Edit segment</Button>
      <Button color="error" disabled={!data.canManage || busy} onClick={() => void remove()}>Delete segment</Button>
      {!data.canManage && <Typography variant="caption">{ROLE_LOCK_LABEL}</Typography>}
    </Stack>}

    <Typography variant="subtitle1" component="h3" sx={{ mt: 3 }}>Assessments</Typography>
    {data.assessments.length === 0 ? <Typography>No assessment of this segment yet.</Typography>
      : <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Stored assessments">
        <TableHead><TableRow><TableCell>Made</TableCell><TableCell>Verdict</TableCell><TableCell>Target</TableCell><TableCell>Headline</TableCell></TableRow></TableHead>
        <TableBody>{data.assessments.map((a) => <AssessmentRow key={a.assessmentId} head={a} onOpen={() => openAssessment(a.assessmentId)} />)}</TableBody>
      </Table></Box>}

    {data.canManage ? <CutoverNewAssessment segmentId={segmentId} onDone={openAssessment} />
      : <Typography variant="body2" sx={{ mt: 2 }} data-fact="new-assessment-locked">New assessment · {ROLE_LOCK_LABEL}</Typography>}
  </Box>;
}
