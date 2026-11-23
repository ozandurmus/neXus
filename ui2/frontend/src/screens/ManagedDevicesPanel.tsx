import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { EmptyPanel } from "../shell/ScreenLayout";
import { statusWord } from "../shell/presenters";
import { Tone } from "../shell/tone";
import { StatusChip } from "../shell/M3Widgets";
import { plural } from "../shell/plural";
import { MONO } from "../theme/m3Theme";
import type { GridMemberView } from "../auth/adminApi";

/**
 * A manager's word for a managed device's management state, in sentence case (`FULLY_MANAGED` → "Fully managed",
 * FortiManager's `UP` → "Up"); `UNKNOWN` stays UNKNOWN. The expected states are neutral grey (P2-1B: `UP` was amber);
 * a device the manager calls unmanaged, down or unknown keeps the amber word.
 */
export function managementWord(status: string): string {
  const value = status.trim();
  if (!value) return "UNKNOWN";
  if (value === "UNKNOWN") return value;
  const words = value.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
export function managementTone(status: string): Tone {
  return /^(UP|MANAGED|FULLY_MANAGED|CONNECTED)$/.test(status.trim()) ? "neutral" : "warn";
}

/** A Symantec Management Center's managed devices (ProxySG, Reporter, WSS) as it lists them; values read, never inferred. */
export function ManagedDevicesPanel({ members, selected, onSelect }: {
  readonly members: readonly GridMemberView[];
  readonly selected?: string | null;
  readonly onSelect?: (name: string | null) => void;
}) {
  if (members.length === 0) {
    return <EmptyPanel title="No managed-device evidence" body="Collect reads the Management Center's device list; none has been recorded yet." />;
  }
  const deployment = (m: GridMemberView) => m.services.find((s) => s.service === "DEPLOYMENT")?.status ?? null;
  // The Deployment column only when some device reports one (R17): a column of dashes says nothing and cost the header its room.
  const hasDeployment = members.some((m) => deployment(m) !== null);
  // Widths at 1440 (post-release review 2026-10-20, N12): the header words stay whole, the address and version columns
  // are mono and clip with the full value on hover, DEPLOYMENT keeps 110 px.
  const widths = hasDeployment ? ["19%", "15%", "11%", "14%", "16%", "13%", "12%"] : ["22%", "17%", "12%", "16%", "17%", "16%"];
  const selectedMember = members.find(m => m.virtual_system === selected);
  const shown = selectedMember ? [selectedMember] : members;
  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {plural(members.length, "device")} · manager-reported inventory
      </Typography>
      {selectedMember && <Stack direction="row" spacing={2} alignItems="center">
        <Typography variant="h6">{selectedMember.virtual_system}</Typography>
        <Button onClick={() => onSelect?.(null)}>All managed devices</Button>
      </Stack>}
      <TableContainer>
        {/* Fixed column widths at 1440 (UI review 2026-10-18, P2-1B / roadmap 3.4): a long value clips inside its own cell
            with the full text on hover, instead of pushing the Deployment column out of the card. */}
        <Table size="small" data-managed-devices sx={{ tableLayout: "fixed", minWidth: 900, "& td, & th": { overflow: "hidden", textOverflow: "ellipsis", px: 1 }, "& th": { whiteSpace: "nowrap" } }}>
          <colgroup>
            {widths.map((width, i) => <col key={i} style={{ width }} />)}
          </colgroup>
          <TableHead>
            <TableRow>
              <TableCell>Device</TableCell>
              <TableCell>Address</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Model</TableCell>
              <TableCell>OS version</TableCell>
              <TableCell>Management</TableCell>
              {hasDeployment && <TableCell>Deployment</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((m) => (
              <TableRow key={m.virtual_system}>
                <TableCell sx={{ fontWeight: 600, whiteSpace: "nowrap" }}>
                  <Button onClick={() => onSelect?.(m.virtual_system)}>{m.virtual_system}</Button>
                </TableCell>
                <TableCell sx={{ whiteSpace: "nowrap", fontFamily: MONO, fontSize: 12.5 }} title={m.address ?? undefined}>{m.address ?? "UNKNOWN"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }} title={m.platform ?? undefined}>{m.platform ?? "\u2014"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }} title={m.hardware_type ?? undefined}>{m.hardware_type ?? "\u2014"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap", fontFamily: MONO, fontSize: 12.5 }} title={m.hypervisor ?? undefined}>{m.hypervisor ?? "\u2014"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }}>{m.node_status ? <StatusChip tone={managementTone(m.node_status)} label={managementWord(m.node_status)} dense /> : "\u2014"}</TableCell>
                {hasDeployment && <TableCell sx={{ whiteSpace: "nowrap" }} title={deployment(m) ?? undefined}>{deployment(m) ? statusWord(deployment(m)) : "\u2014"}</TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
}
