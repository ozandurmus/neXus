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
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "../shell/Icon";
import type { ReadinessCheck, ReadinessMember } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { readinessReasonLabel } from "../shell/presenters";
import { m3 } from "../theme/m3Theme";
import { formatStamp, relativeAge } from "../shell/time";
import { readyWord } from "./readinessWarnings";

/** Check 10 (traffic and ARP) is read on the active member only. */
const TRAFFIC_ARP_CHECK = 10;
const SECONDARY_NOT_ACTIVE = "Read on the active member only";

/**
 * A single-address FortiGate cluster has one enrolled device: the secondary has no address, device record or checks of its
 * own (design question Q2). The primary's reads describe both members, so the second column is filled from the primary's report.
 */
export function primaryReportsPeer(vendor: string | undefined, members: ReadinessMember[]) {
  return vendor === "fortinet" && members.length === 1;
}
/** Check 1 PASS means one primary and one secondary, the enrolled unit identified as the primary (identity MATCH). */
export function primaryRolesConfirmed(checks: ReadinessCheck[]) {
  return checks.some(check => check.checkNo === 1 && check.result === "PASS");
}

function parseDerived(raw: unknown): Record<string, unknown> | null {
  if (!raw) return null;
  if (typeof raw === "string") {
    try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
  }
  return typeof raw === "object" ? (raw as Record<string, unknown>) : null;
}

export function isActiveRole(role: string | null | undefined): boolean {
  const r = (role ?? "").toLowerCase();
  return r === "active" || r === "primary";
}

export function isStandbyRole(role: string | null | undefined): boolean {
  const r = (role ?? "").toLowerCase();
  return r === "standby" || r === "passive" || r === "secondary";
}

/**
 * Each member's role in the latest readiness cluster-state check (check 1, its own `role`), with the observation time. The
 * inventory role (`ha_role`) is older evidence: after a failover it shows the roles before the switch (live 2026-11-19, a
 * VSX chassis panel showed member 1 ACTIVE / member 2 STANDBY while the readiness showed the reverse).
 */
export function observedRoles(checks: ReadinessCheck[], members: ReadonlyArray<{ device_id: string }> = [],
    fallbackAt?: string | null, vendor?: string): Map<string, { role: string; at?: string }> {
  const roles = new Map<string, { role: string; at?: string }>();
  for (const check of checks) {
    if (check.checkNo !== 1) continue;
    const deviceId = check.device_id
      ?? (check.member === "Member 1" ? members[0]?.device_id : check.member === "Member 2" ? members[1]?.device_id : undefined);
    if (!deviceId) continue;
    const derived = parseDerived(check.derived);
    const role = derived?.role;
    if (typeof role !== "string" || !role || role.toUpperCase() === "UNKNOWN") continue;
    const at = check.observedAt ?? (fallbackAt || undefined);
    const earlier = roles.get(deviceId);
    if (!earlier || (at && (!earlier.at || at > earlier.at)))
      roles.set(deviceId, { role: role.toLowerCase(), at });
  }
  // Cisco ASA readiness runs whose stored check-1 JSON omitted `role` identify the standby member on check 10 (NOT_ACTIVE_MEMBER).
  if (roles.size === 0 && members.length === 2) {
    const check1 = checks.filter(c => c.checkNo === 1);
    const check1Passed = check1.length > 0 && check1.every(c => c.result === "PASS" || parseDerived(c.derived)?.oneActiveOneStandbyReady === true);
    const standbyCheck10 = checks.find(c => c.checkNo === TRAFFIC_ARP_CHECK
      && (parseDerived(c.derived)?.reason === "NOT_ACTIVE_MEMBER" || c.summary === SECONDARY_NOT_ACTIVE));
    const standbyId = standbyCheck10?.device_id
      ?? (standbyCheck10?.member === "Member 1" ? members[0]?.device_id : standbyCheck10?.member === "Member 2" ? members[1]?.device_id : undefined);
    if (check1Passed && standbyId && members.some(m => m.device_id === standbyId)) {
      const at = check1.map(c => c.observedAt).filter((t): t is string => Boolean(t)).sort().at(-1) ?? (fallbackAt || undefined);
      for (const m of members) roles.set(m.device_id, { role: m.device_id === standbyId ? "standby" : "active", at });
    }
  }
  // FortiGate pair where the primary's check 1 PASS confirms one primary (active) and one secondary (standby).
  if (vendor === "fortinet" && members.length === 2 && roles.size < 2 && primaryRolesConfirmed(checks)) {
    const check1 = checks.find(c => c.checkNo === 1 && c.result === "PASS");
    const primaryId = check1?.device_id ?? (roles.size === 1 ? [...roles.keys()][0] : members[0]?.device_id);
    const at = check1?.observedAt ?? (roles.size === 1 ? [...roles.values()][0].at : undefined) ?? (fallbackAt || undefined);
    if (primaryId && members.some(m => m.device_id === primaryId)) {
      for (const m of members) if (!roles.has(m.device_id))
        roles.set(m.device_id, { role: m.device_id === primaryId ? "active" : "standby", at });
    }
  }
  return roles;
}

function checkRows(checks: ReadinessCheck[], labels: ReadinessMember[] = [], peerFromPrimary = false) {
  return [...new Set(checks.map(check => check.checkNo))].sort((a, b) => a - b).map(no => {
    const members = ["Member 1", "Member 2"].map((member, index) => {
      if (peerFromPrimary) return checks.find(check => check.checkNo === no && (!check.device_id || check.device_id === labels[0]?.device_id));
      return checks.find(check => check.checkNo === no && (check.device_id && labels.length ? check.device_id === labels[index]?.device_id : check.member === member));
    });
    const first = checks.find(check => check.checkNo === no)!;
    const blocking = checks.some(check => check.checkNo === no && check.blocking);
    const failed = blocking && members.some(check => check?.result === "FAIL");
    const passed = blocking && members.every(check => check?.result === "PASS");
    // Palo Alto config sync off on one member: the row is still blocking, but only that member's own cell is red.
    const perMember = failed && members.some(check => check?.memberSync !== undefined);
    // WARN: non-blocking warning (VS0 policy row, PO 2026-11-23) or a blocking check that warns (PAN idle session sync, PO 2026-11-23); a FAIL on either member wins.
    const warned = !failed && members.some(check => check?.result === "WARN")
      && (!blocking || members.every(check => check?.result === "PASS" || check?.result === "WARN"));
    return { no, title: first.title, members, blocking, failed, passed, perMember, warned };
  });
}

/** `rowAction`: an action the server offers for one check row (the PAN config sync on check 4), rendered under its title. */
export function ReadinessChecksTable({ checks, members = [], compact = false, rowAction, vendor, observedAt }: { checks: ReadinessCheck[]; members?: ReadinessMember[]; compact?: boolean;
  rowAction?: (checkNo: number, failed: boolean) => ReactNode; vendor?: string; observedAt?: string | null }) {
  const peer = primaryReportsPeer(vendor, members);
  const singleObserverFgt = !peer && vendor === "fortinet" && members.length === 2
    && new Set(checks.map(c => c.device_id ?? c.member).filter(Boolean)).size === 1;
  const confirmed = peer && primaryRolesConfirmed(checks);
  // The column roles come from the latest readiness cluster-state check when it names every member, else from inventory,
  // labelled as such. Order presentation by that role; retain opaque device IDs for check matching.
  const observed = observedRoles(checks, members, observedAt, vendor);
  const fromReadiness = members.length > 0 && members.every(member => observed.has(member.device_id));
  const roleOf = (member?: ReadinessMember) => member ? fromReadiness ? observed.get(member.device_id)!.role : member.ha_role?.toLowerCase() : undefined;
  const roleAt = (member?: ReadinessMember) => member && fromReadiness ? observed.get(member.device_id)!.at : undefined;
  const ordered = [...members].sort((a, b) => Number(!isActiveRole(roleOf(a))) - Number(!isActiveRole(roleOf(b))));
  const memberOrder = [0, 1].map(index => members.length ? members.indexOf(ordered[index]) : index);
  return <Table size="small" aria-label="Readiness results" sx={{ width: "100%", tableLayout: "fixed" }}>
    <TableHead><TableRow>
      <TableCell sx={{ width: "24%" }}>Check</TableCell>
      {peer ? [0, 1].map(index => <TableCell key={index}>
        {index === 0 ? members[0].hostname ?? "Primary" : "Secondary"}
        <Typography component="span" variant="caption" sx={{ color: m3.onSurfaceVar }}> · {confirmed ? (index === 0 ? "active" : "standby") : "role unknown"} (as the primary reports)</Typography>
      </TableCell>) : [0, 1].map(index => <TableCell key={index}>
        {ordered[index]?.hostname ?? `Member ${index + 1}`}
        <Typography component="span" variant="caption" data-role-source={fromReadiness ? "readiness" : "inventory"}
          title={roleAt(ordered[index]) ? `Readiness cluster-state check, ${formatStamp(roleAt(ordered[index]))}` : fromReadiness ? "Readiness cluster-state check" : "Inventory role (older than the readiness checks)"}
          sx={{ color: m3.onSurfaceVar }}> · {roleOf(ordered[index]) ?? "role unknown"}{fromReadiness
            ? ` (readiness${roleAt(ordered[index]) ? `, ${relativeAge(roleAt(ordered[index])!, new Date())}` : ""})`
            : ordered[index]?.ha_role ? " (inventory)" : ""}</Typography>
      </TableCell>)}
    </TableRow></TableHead>
    <TableBody>{checkRows(checks, members, peer || singleObserverFgt).map(row => {
      const icon = row.failed ? "x-circle" : row.warned ? "warning" : row.passed ? "check-circle" : "info";
      const result = row.failed ? "Blocking" : row.warned ? "Warning" : row.passed ? "Passed" : !row.blocking ? "Info" : "Unknown";
      return <TableRow key={row.no} data-row-tone={row.failed ? "blocking" : row.warned ? "warning" : undefined}
        sx={{ bgcolor: row.failed && !row.perMember ? m3.errorContainer : row.warned ? m3.warningContainer : undefined }}>
        <TableCell component="th" scope="row" sx={{ py: compact ? 0.5 : 1.5, overflowWrap: "anywhere" }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Box component="span" role="img" aria-label={result} sx={{ display: "flex", color: row.failed ? m3.onErrorContainer : row.warned ? m3.onWarningContainer : row.passed ? m3.goodInk : m3.onSurfaceVar }}><Icon name={icon} /></Box>
            <Typography variant="body2">{row.title}</Typography>
            {row.warned ? <Chip size="small" label="warning" sx={{ height: 20, bgcolor: m3.sc, color: m3.onWarningContainer }} />
              : !row.blocking && <Chip size="small" label="info" sx={{ height: 20, bgcolor: m3.sc, color: m3.onSurfaceVar }} />}
          </Stack>
          {rowAction?.(row.no, row.failed)}
        </TableCell>
        {memberOrder.map((memberIndex, index) => <TableCell key={index} data-member-sync={row.perMember ? row.members[memberIndex]?.memberSync ?? "unknown" : undefined}
          sx={{ py: compact ? 0.5 : 1.5, whiteSpace: "normal", overflowWrap: "anywhere",
            ...(row.perMember && row.members[memberIndex]?.memberSync === "off" ? { bgcolor: m3.errorContainer, color: m3.onErrorContainer } : {}) }}>
          <CheckMessage text={(peer || singleObserverFgt) ? (index === 1 && row.no === TRAFFIC_ARP_CHECK ? SECONDARY_NOT_ACTIVE : row.members[0]?.summary ?? "Not collected")
            : row.members[memberIndex]?.summary ?? "Not collected"} /></TableCell>)}
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
  return stopCode === "COMMAND_UNAVAILABLE" || stopCode?.startsWith("COMMAND_UNAVAILABLE:") ? readinessReasonLabel(stopCode) : null;
}

export function ReadinessCard({ checks, members, status, cluster, vendor, observedAt, masked = false, running = false, disabled = false, canRun, onRun, error, stopCode, rowAction }: {
  checks: ReadinessCheck[]; members?: ReadinessMember[]; status?: string | null; cluster: string;
  vendor?: string; observedAt?: string | null; masked?: boolean; running?: boolean; disabled?: boolean; canRun: boolean;
  onRun: () => void; error?: string | null; stopCode?: string | null; rowAction?: (checkNo: number, failed: boolean) => ReactNode;
}) {
  const rows = checkRows(checks, members, primaryReportsPeer(vendor, members ?? []));
  const blocking = rows.filter(row => row.failed).length;
  const warnings = rows.filter(row => row.warned).length;
  const ready = !running && status === "READY";
  const readyClean = ready && warnings === 0;
  const notReady = !running && status === "NOT_READY";
  const verdict = running ? "Running pre-checks" : ready ? readyWord(warnings) : notReady
    ? `Not ready${blocking ? ` · ${blocking} ${blocking === 1 ? "blocker" : "blockers"}` : ""}` : "Unknown";
  const icon = readyClean ? "check-circle" : notReady ? "x-circle" : "warning";
  const observed = observedAt ? Date.parse(observedAt) : NaN;
  const minutes = Math.max(0, Math.floor((Date.now() - observed) / 60_000));
  const age = Number.isFinite(minutes) ? minutes < 60 ? `${minutes} min ago` : `${Math.floor(minutes / 60)} h ago` : "Not evaluated";
  const vendorLabel = vendor === "palo_alto" ? "Palo Alto · Active/passive" : vendor === "cisco_asa" ? "Cisco ASA · Active/standby failover" : vendor === "fortinet" ? "FortiGate · FGCP active-passive (as the primary reports)" : "Check Point · ClusterXL HA";
  return <Card component="section" aria-label="Readiness checks" sx={{ p: { xs: 2, md: 3 }, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: 3 }}>
    <Stack direction="row" alignItems="center" spacing={2} useFlexGap sx={{ flexWrap: "wrap" }}>
      <Box sx={{ display: "flex", p: 1.25, borderRadius: "50%", bgcolor: readyClean ? m3.successContainer : notReady ? m3.errorContainer : m3.warningContainer,
        color: readyClean ? m3.onSuccessContainer : notReady ? m3.onErrorContainer : m3.onWarningContainer }}><Icon name={icon} size={24} /></Box>
      <Box sx={{ flex: 1, minWidth: 180 }}>
        <Typography variant="h6" sx={{ fontWeight: 600 }}>{verdict}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{cluster} · {vendorLabel} · {age}</Typography>
        {masked && <Chip size="small" label="AIView Pseudonymized" sx={{ mt: 0.75, bgcolor: m3.sc, color: m3.onSurfaceVar }} />}
      </Box>
      {canRun && <M3Button deviceJob emphasis="outlined" disabled={running || disabled} onClick={onRun}>Run pre-checks</M3Button>}
    </Stack>
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 1.5, my: 2.5 }}>
      {[["Passed", rows.filter(row => row.passed).length], ["Blocking", blocking], ["Info", rows.filter(row => !row.blocking && !row.warned).length]].map(([label, count]) =>
        <Box key={label} aria-label={`${label}: ${count}`} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, px: 2, py: 1 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{label}</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600 }}>{count}</Typography>
        </Box>)}
    </Box>
    {!running && readinessStopReason(stopCode) && <Typography variant="body2" sx={{ mb: 1 }}>{readinessStopReason(stopCode)}</Typography>}
    {error && <Typography role="alert" variant="body2" sx={{ color: m3.criticalInk, mb: 1 }}>{error}</Typography>}
    <TableContainer><ReadinessChecksTable checks={checks} members={members} rowAction={rowAction} vendor={vendor} observedAt={observedAt} /></TableContainer>
    {!checks.length && <Typography variant="body2" sx={{ color: m3.onSurfaceVar, py: 2 }}>No observations yet</Typography>}
    <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 2 }}>Failover runs re-check everything at start.</Typography>
  </Card>;
}
