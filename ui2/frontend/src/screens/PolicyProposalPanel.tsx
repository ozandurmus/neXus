import { useEffect, useRef, useState } from "react";
import { Accordion, AccordionDetails, AccordionSummary, Box, Button, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { getPolicyProposal, responsesAreMasked, type PolicyProposalResponse, type ProposalSectionOrder } from "../auth/adminApi";
import { policyFailureText } from "./PolicyRuleViewer";
import { CHANGE_LIST_NOTICE, CROSSED_REASON_WORDS, INSTALL_CAVEAT, loadChangeList, saveChangeList, withChange, type ChangeItem } from "./policyInsightActions";
import { ADD_LABEL, INCOMPLETE_TEXT, LOAD_FAILED_TEXT, SHOW_LABEL, UNSUPPORTED_TEXT, crossedCount, crossedRefs, equivalenceWord, mergeSentence, mergedAwayIds, namesById, orderRows,
  proposalChangeItems, proposalCountsLine, proposalHeadline, refusalItems, resultWord, stepSentence } from "./policyProposalModel";
import { m3 } from "../theme/m3Theme";

/** Reorder proposal (design section 8 items 4 and 6): read-only, requested only when the user clicks, words only. */
const MAX_ROWS = 50;

function OrderView({ section, names, merged }: { section: ProposalSectionOrder; names: ReadonlyMap<string, string>; merged: ReadonlySet<string> }) {
  const rows = orderRows(section, names, merged);
  const column = (title: string, list: typeof rows.before) => <Box sx={{ flex: 1, minWidth: 0 }}>
    <Typography variant="caption" sx={{ fontWeight: 700 }}>{title}</Typography>
    <Box component="ol" aria-label={`${title} order`} sx={{ m: 0, p: 0, listStyle: "none" }}>
      {list.map(r => <Box component="li" key={r.ruleId} data-moved={r.moved || undefined} data-struck={r.struck || undefined}
        sx={{ fontSize: 12, textDecoration: r.struck ? "line-through" : "none", fontWeight: r.moved ? 700 : 400 }}>
        {r.position}. {r.label}{r.moved && " (moved)"}{r.struck && " (merged away)"}{r.outOfWindow && " (pushed out of the window; position UNKNOWN)"}{r.enteredWindow && " (came in from outside the window)"}</Box>)}
    </Box>
  </Box>;
  return <Box data-section={section.sectionId} sx={{ my: 1 }}>
    <Stack direction="row" gap={2}>{column("Before", rows.before)}{column("After", rows.after)}</Stack>
    {rows.more && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{rows.more}</Typography>}
  </Box>;
}

function Result({ resp, policy, masked }: { resp: PolicyProposalResponse; policy: string; masked: boolean }) {
  const [added, setAdded] = useState(false);
  const steps = resp.steps ?? [], merges = resp.merges ?? [], order = resp.order ?? [];
  const names = namesById(resp);
  const items = proposalChangeItems(resp, resp.collectedAt);
  const addAll = () => {
    let list: ChangeItem[] = loadChangeList(policy, masked);
    for (const item of items) list = withChange(list, item);
    saveChangeList(policy, masked, list);
    setAdded(true);
  };
  if (!resp.supported) return <Typography role="status" data-proposal-supported="false">{UNSUPPORTED_TEXT}</Typography>;
  const refusals = refusalItems(resp.refused);
  return <Box>
    {resp.complete === false && <Typography role="status" color="warning.main">{INCOMPLETE_TEXT}</Typography>}
    <Typography variant="subtitle2" data-proposal-headline>{proposalHeadline(resp)}</Typography>
    {resp.inspectionChanges ? <Chip size="small" color="warning" label="inspection changes" sx={{ mr: 1 }} /> : null}
    <Typography variant="body2" data-proposal-counts>{proposalCountsLine(resp)}</Typography>
    {refusals.length > 0 && <Box component="ul" aria-label="Refused moves by reason" sx={{ m: 0, pl: 2.5, fontSize: 12 }}>
      {refusals.map(r => <li key={r.word}>{r.count} {r.count === 1 ? "move" : "moves"} refused: {r.text}</li>)}</Box>}
    <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, my: 1 }}>{resp.caveat} {INSTALL_CAVEAT} Each step is proven after the earlier steps.</Typography>
    {steps.length > 0 && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Proposed moves">
      <TableHead><TableRow>{["Step", "Change", "Result", "Equivalence", "Crossed rules"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
      <TableBody>{steps.slice(0, MAX_ROWS).map((s, i) => <TableRow key={i} data-step={i + 1}>
        <TableCell>{i + 1}</TableCell>
        <TableCell>{stepSentence(s)}</TableCell>
        <TableCell>{resultWord(s.result)}{s.inspectionChanges && <Chip size="small" color="warning" label="inspection changes" sx={{ ml: 1 }} />}</TableCell>
        <TableCell>{equivalenceWord(s.equivalence)}</TableCell>
        <TableCell>{s.accepted ? <>
          {crossedCount(s)} crossed
          {crossedRefs(s).length > 0 && <Box component="ul" sx={{ m: 0, pl: 2, fontSize: 12 }}>{crossedRefs(s).map((c, j) =>
            <li key={j}>rule {c.ruleNumber || "?"} {c.ruleName}{(c as { reason?: string }).reason ? ` · ${CROSSED_REASON_WORDS[(c as { reason?: string }).reason!] ?? "UNKNOWN"}` : ""}</li>)}</Box>}</> : "—"}</TableCell>
      </TableRow>)}</TableBody>
    </Table>{steps.length > MAX_ROWS && <Typography variant="caption">+{steps.length - MAX_ROWS} more steps</Typography>}</Box>}
    {merges.length > 0 && <Box sx={{ overflowX: "auto", mt: 1 }}><Table size="small" aria-label="Proposed merges">
      <TableHead><TableRow>{["Merge", "Change", "Result", "Equivalence"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
      <TableBody>{merges.slice(0, MAX_ROWS).map((m, i) => <TableRow key={i} data-merge={i + 1}>
        <TableCell>{i + 1}</TableCell><TableCell>{mergeSentence(m)}</TableCell>
        <TableCell>{m.accepted ? "Accepted" : resultWord(m.result)}</TableCell><TableCell>{equivalenceWord(m.equivalence)}</TableCell>
      </TableRow>)}</TableBody>
    </Table>{merges.length > MAX_ROWS && <Typography variant="caption">+{merges.length - MAX_ROWS} more merges</Typography>}</Box>}
    {order.slice(0, MAX_ROWS).map(o => <OrderView key={o.sectionId} section={o} names={names} merged={mergedAwayIds(resp)} />)}
    {order.length > MAX_ROWS && <Typography variant="caption">+{order.length - MAX_ROWS} more sections</Typography>}
    <Button variant="outlined" size="small" disabled={items.length === 0} onClick={addAll}>{ADD_LABEL}</Button>
    {added && <Typography role="status" variant="caption" display="block" data-proposal-added>{CHANGE_LIST_NOTICE}</Typography>}
  </Box>;
}

export function PolicyProposalPanel({ policy, days = 90 }: { policy: string; days?: number }) {
  const [state, setState] = useState<{ kind: "idle" } | { kind: "pending" } | { kind: "error"; text: string } | { kind: "done"; resp: PolicyProposalResponse; masked: boolean }>({ kind: "idle" });
  const run = useRef(0);
  useEffect(() => { run.current++; setState({ kind: "idle" }); }, [policy, days]);
  const load = () => {
    const mine = ++run.current;
    setState({ kind: "pending" });
    getPolicyProposal(policy, days).then(resp => {
      if (typeof resp?.supported !== "boolean") throw new Error("Invalid proposal response");
      if (mine === run.current) setState({ kind: "done", resp, masked: responsesAreMasked() });
    }).catch(failure => { if (mine === run.current) setState({ kind: "error", text: policyFailureText(failure, LOAD_FAILED_TEXT) }); });
  };
  return <Accordion disableGutters variant="outlined" sx={{ my: 2 }} TransitionProps={{ unmountOnExit: true }}>
    <AccordionSummary aria-label="Reorder proposal"><Typography variant="subtitle2">Reorder proposal</Typography></AccordionSummary>
    <AccordionDetails>
      <Typography variant="caption" display="block" sx={{ mb: 1 }}>Read-only. It can take several seconds on a large package, so it runs only when you ask.</Typography>
      <Button variant="contained" size="small" onClick={load} disabled={state.kind === "pending"}>{SHOW_LABEL}</Button>
      {state.kind === "pending" && <Typography role="status">Computing the reorder proposal…</Typography>}
      {state.kind === "error" && <Box role="alert">{state.text}</Box>}
      {state.kind === "done" && <Result key={state.resp.collectedAt ?? "r"} resp={state.resp} policy={policy} masked={state.masked} />}
    </AccordionDetails>
  </Accordion>;
}
