import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import Button from "@mui/material/Button";
import { useEffect, useRef, useState } from "react";
import { Icon } from "../shell/Icon";
import type { ReadinessCheck, ReadinessMember } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

function checkRows(checks: ReadinessCheck[], labels: ReadinessMember[] = []) {
  return [...new Set(checks.map(check => check.checkNo))].sort((a, b) => a - b).map(no => {
    const members = ["Member 1", "Member 2"].map((member, index) => checks.find(check => check.checkNo === no && (check.device_id && labels.length ? check.device_id === labels[index]?.device_id : check.member === member)));
    const first = checks.find(check => check.checkNo === no)!;
    const blocking = checks.some(check => check.checkNo === no && check.blocking);
    const failed = blocking && members.some(check => check?.result === "FAIL");
    const passed = blocking && members.every(check => check?.result === "PASS");
    return { no, title: first.title, members, blocking, failed, passed };
  });
}

export function ReadinessChecksTable({ checks, members = [], compact = false }: { checks: ReadinessCheck[]; members?: ReadinessMember[]; compact?: boolean }) {
  // Order presentation by observed role; retain opaque device IDs for check matching.
  const ordered = [...members].sort((a, b) => Number(a.ha_role?.toLowerCase() !== "active") - Number(b.ha_role?.toLowerCase() !== "active"));
  const memberOrder = [0, 1].map(index => members.length ? members.indexOf(ordered[index]) : index);
  return <Table size="small" aria-label="Readiness results" sx={{ width: "100%", tableLayout: "fixed" }}>
    <TableHead><TableRow>
      <TableCell sx={{ width: "24%" }}>Check</TableCell>
      {[0, 1].map(index => <TableCell key={index}>
        {ordered[index]?.hostname ?? `Member ${index + 1}`}
        <Typography component="span" variant="caption" sx={{ color: m3.onSurfaceVar }}> · {ordered[index]?.ha_role?.toLowerCase() ?? "role unknown"}</Typography>
      </TableCell>)}
    </TableRow></TableHead>
    <TableBody>{checkRows(checks, members).map(row => {
      const icon = row.failed ? "x-circle" : row.passed ? "check-circle" : "info";
      const result = row.failed ? "Blocking" : row.passed ? "Passed" : !row.blocking ? "Info" : "Unknown";
      return <TableRow key={row.no} sx={{ bgcolor: row.failed ? m3.errorContainer : undefined }}>
        <TableCell component="th" scope="row" sx={{ py: compact ? 0.5 : 1.5, overflowWrap: "anywhere" }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Box component="span" role="img" aria-label={result} sx={{ display: "flex", color: row.failed ? m3.onErrorContainer : row.passed ? m3.goodInk : m3.onSurfaceVar }}><Icon name={icon} /></Box>
            <Typography variant="body2">{row.title}</Typography>
            {!row.blocking && <Chip size="small" label="info" sx={{ height: 20, bgcolor: m3.sc, color: m3.onSurfaceVar }} />}
          </Stack>
        </TableCell>
        {memberOrder.map((memberIndex, index) => <TableCell key={index} sx={{ py: compact ? 0.5 : 1.5, whiteSpace: "normal", overflowWrap: "anywhere" }}><CheckMessage text={row.members[memberIndex]?.summary ?? "Not collected"} /></TableCell>)}
      </TableRow>;
    })}</TableBody>
  </Table>;
}

function CheckMessage({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false);
  const [overflow, setOverflow] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = ref.current;
    if (!node || expanded) return;
    const measure = () => setOverflow(node.scrollHeight > node.clientHeight);
    measure();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(measure);
    observer?.observe(node);
    return () => observer?.disconnect();
  }, [text, expanded]);
  return <Box><Box ref={ref}
    className="readiness-message" sx={{ whiteSpace: "normal", overflowWrap: "anywhere", display: expanded ? "block" : "-webkit-box", WebkitBoxOrient: "vertical", WebkitLineClamp: expanded ? undefined : 2, overflow: "hidden" }}>{text}</Box>
    {(overflow || expanded) && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(value => !value)} sx={{ p: 0 }}>{expanded ? "Less" : "More"}</Button>}
  </Box>;
}

export function readinessStopReason(stopCode?: string | null) {
  return stopCode === "COMMAND_UNAVAILABLE" ? "Command not available on device (login environment)" : null;
}

export function ReadinessCard({ checks, members, status, cluster, vendor, observedAt, masked = false, running = false, disabled = false, canRun, onRun, error, stopCode }: {
  checks: ReadinessCheck[]; members?: ReadinessMember[]; status?: string | null; cluster: string;
  vendor?: string; observedAt?: string | null; masked?: boolean; running?: boolean; disabled?: boolean; canRun: boolean;
  onRun: () => void; error?: string | null; stopCode?: string | null;
}) {
  const rows = checkRows(checks, members);
  const blocking = rows.filter(row => row.failed).length;
  const ready = !running && status === "READY";
  const notReady = !running && status === "NOT_READY";
  const verdict = running ? "Running pre-checks" : ready ? "Ready" : notReady
    ? `Not ready${blocking ? ` · ${blocking} ${blocking === 1 ? "blocker" : "blockers"}` : ""}` : "Unknown";
  const icon = ready ? "check-circle" : notReady ? "x-circle" : "warning";
  const observed = observedAt ? Date.parse(observedAt) : NaN;
  const minutes = Math.max(0, Math.floor((Date.now() - observed) / 60_000));
  const age = Number.isFinite(minutes) ? minutes < 60 ? `${minutes} min ago` : `${Math.floor(minutes / 60)} h ago` : "Not evaluated";
  const vendorLabel = vendor === "palo_alto" ? "Palo Alto · Active/passive" : "Check Point · ClusterXL HA";
  return <Card component="section" aria-label="Readiness checks" sx={{ p: { xs: 2, md: 3 }, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: 3 }}>
    <Stack direction="row" alignItems="center" spacing={2} useFlexGap sx={{ flexWrap: "wrap" }}>
      <Box sx={{ display: "flex", p: 1.25, borderRadius: "50%", bgcolor: ready ? m3.successContainer : notReady ? m3.errorContainer : m3.warningContainer,
        color: ready ? m3.onSuccessContainer : notReady ? m3.onErrorContainer : m3.onWarningContainer }}><Icon name={icon} size={24} /></Box>
      <Box sx={{ flex: 1, minWidth: 180 }}>
        <Typography variant="h6" sx={{ fontWeight: 600 }}>{verdict}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{cluster} · {vendorLabel} · {age}</Typography>
        {masked && <Chip size="small" label="AIView Pseudonymized" sx={{ mt: 0.75, bgcolor: m3.sc, color: m3.onSurfaceVar }} />}
      </Box>
      {canRun && <M3Button emphasis="outlined" disabled={running || disabled} onClick={onRun}>Run pre-checks</M3Button>}
    </Stack>
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 1.5, my: 2.5 }}>
      {[["Passed", rows.filter(row => row.passed).length], ["Blocking", blocking], ["Info", rows.filter(row => !row.blocking).length]].map(([label, count]) =>
        <Box key={label} aria-label={`${label}: ${count}`} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, px: 2, py: 1 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{label}</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600 }}>{count}</Typography>
        </Box>)}
    </Box>
    {!running && readinessStopReason(stopCode) && <Typography variant="body2" sx={{ mb: 1 }}>{readinessStopReason(stopCode)}</Typography>}
    {error && <Typography role="alert" variant="body2" sx={{ color: m3.criticalInk, mb: 1 }}>{error}</Typography>}
    <TableContainer><ReadinessChecksTable checks={checks} members={members} /></TableContainer>
    {!checks.length && <Typography variant="body2" sx={{ color: m3.onSurfaceVar, py: 2 }}>No observations yet</Typography>}
    <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 2 }}>Failover runs re-check everything at start.</Typography>
  </Card>;
}
