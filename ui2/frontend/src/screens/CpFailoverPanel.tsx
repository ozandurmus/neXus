import { useCallback, useEffect, useState } from "react";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { listCpFailoverUnits, listFailoverIncidents, type CpFailoverSummary, type CpFailoverUnit } from "../auth/adminApi";
import type { SharedIncidents } from "./FailoverIncidentsPanel";
import { FailoverDetail } from "./failover/FailoverDetail";
import { FailoverRunView } from "./failover/FailoverRunView";
import { errorText } from "./failover/failoverShared";
import { failoverExecutionDisabled, failoverVendorLabel } from "./failover/failoverRowModel";

// The pieces other screens and tests import from here keep their names (the panel was split into `failover/`).
export { FINAL_PHASES, VALIDITY_MINUTES, DEFAULT_VALIDITY_MINUTES, WORK_LOG_NOTE_LABEL, WORK_LOG_NOTE_MAX, activeStates, errorText, failoverReadinessVerdict,
  isDirectoryUnreachable, runStep, type ReadinessVerdict } from "./failover/failoverShared";
export { ResumeReturn, resumeActiveStates, resumePolls, resumeStep, resumeText } from "./failover/ResumeReturn";
export { FailoverRunView as FailoverRun };

/** The cluster (or VSX chassis) unit first, then its Virtual Systems in the server's order. */
export function orderUnits(units: CpFailoverUnit[]): CpFailoverUnit[] {
  const base = (unit: CpFailoverUnit) => unit.unitKind === "VSX_CHASSIS" || unit.unitId === unit.clusterId ? 0 : 1;
  return units.map((unit, index) => ({ unit, index })).sort((a, b) => base(a.unit) - base(b.unit) || a.index - b.index).map(entry => entry.unit);
}
const withMaskedMembers = (units: CpFailoverUnit[]) => orderUnits(units).map(unit => ({ ...unit, members: unit.maskedMembers?.map(member => ({ ...member, hostname: member.maskedLabel })) ?? unit.members }));

/**
 * The failover detail of one cluster: reads its units and shows the one in focus (a cluster, a VSX chassis or one of its virtual
 * systems) as a single surface (`FailoverDetail`). `summaryRows` / `incidents` come from a screen that already read them; without
 * them (the inventory page mounts this on its own) the detail reads for itself.
 */
export function CpFailoverPanel({ memberDeviceId, vendor = "check_point", initialUnitId, summaryRows, incidents }: { memberDeviceId: string;
  vendor?: "check_point" | "palo_alto" | "cisco_asa" | "fortinet"; initialUnitId?: string | null; summaryRows?: readonly CpFailoverSummary[]; incidents?: SharedIncidents }) {
  const [units, setUnits] = useState<CpFailoverUnit[]>([]), [selected, setSelected] = useState<string | null>(initialUnitId ?? null);
  const [error, setError] = useState<string | null>(null);
  // undefined = not asked or not answered yet; null = the read failed (the dialog then words both outcomes).
  const [singleAdmin, setSingleAdmin] = useState<boolean | null | undefined>(undefined);
  const refreshUnits = useCallback(() => {
    void listCpFailoverUnits(memberDeviceId, vendor).then(value => setUnits(withMaskedMembers(value))).catch(e => setError(errorText(e)));
  }, [memberDeviceId, vendor]);
  useEffect(() => {
    let disposed = false;
    setUnits([]); setError(null); setSelected(initialUnitId ?? null);
    listCpFailoverUnits(memberDeviceId, vendor).then(value => { if (!disposed) setUnits(withMaskedMembers(value)); }).catch(e => { if (!disposed) setError(errorText(e)); });
    return () => { disposed = true; };
  }, [memberDeviceId, vendor, initialUnitId]);
  // Which dialog to word (PO 2026-11-23). The unit list does not name the session's approval policy, so the page reads the
  // one fact the server already publishes that follows the same rule: `canRelease` on the incidents read is true exactly
  // for the role that approves and starts alone. It words the dialog and nothing else; no role token is read here, and the
  // policy in the server's answer to the request decides what happens. A screen that already read the incidents passes them in.
  const startable = units.some(unit => unit.canStart && unit.masked !== true && !failoverExecutionDisabled(unit.vendor));
  useEffect(() => {
    if (!startable) return;
    if (incidents) {
      // Not read yet (undefined) is told from read and failed (null): a failed read words both outcomes, as the own read does.
      setSingleAdmin(incidents.data === null ? (incidents.failed ? null : undefined) : incidents.data.canRelease === true); return;
    }
    let disposed = false;
    (async () => listFailoverIncidents())().then(value => { if (!disposed) setSingleAdmin(value?.canRelease === true); })
      .catch(() => { if (!disposed) setSingleAdmin(null); });
    return () => { disposed = true; };
  }, [startable, incidents?.data, incidents?.failed]);
  const unit = units.find(u => u.unitId === selected) ?? units[0];
  return <Stack spacing={1} aria-label={`${failoverVendorLabel(vendor)} failover`}>
    {error && <Typography role="alert" color="error">{error}</Typography>}
    {!error && units.length === 0 && <Typography>No eligible failover units</Typography>}
    {unit && <FailoverDetail key={`${vendor}:${unit.unitId}`} unit={unit} units={units} singleAdmin={singleAdmin} summaryRows={summaryRows} incidents={incidents}
      onRefreshUnits={refreshUnits} onSelectUnit={setSelected} />}
  </Stack>;
}
