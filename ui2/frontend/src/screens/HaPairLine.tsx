import { Box, Chip, Typography } from "@mui/material";
import type { CpFailoverSummary } from "../auth/adminApi";
import { m3 } from "../theme/m3Theme";

const STATE_WORD = { HEALTHY: "Pair healthy", DEGRADED: "Pair degraded", UNKNOWN: "Pair state unknown" } as const;
const REASON_WORD: Record<string, string> = {
  MEMBER_FAILED: "A member reports it has failed",
  ROLES_NOT_COMPLEMENTARY: "Both members report the same role",
  ROLE_NOT_OBSERVED: "A member's role has not been observed",
  ROLE_NOT_RECOGNISED: "A member reports a role this view does not recognise",
  PAIR_INCOMPLETE: "The pair does not have two enrolled members",
};

/** Role words as the vendor says them; a missing role is "Unknown", never filled in from the other member. */
export function haRoleWord(role: string | null | undefined): string {
  const r = (role ?? "").trim().toLowerCase();
  return !r || r === "unknown" ? "Unknown" : r.charAt(0).toUpperCase() + r.slice(1);
}

/** The member's display name: the masked label when the server sent one, else the observed hostname. */
function memberName(row: CpFailoverSummary, deviceId: string): string {
  const masked = (row as { maskedMembers?: Array<{ device_id: string; maskedLabel: string }> }).maskedMembers?.find(m => m.device_id === deviceId);
  return masked?.maskedLabel ?? row.members?.find(m => m.device_id === deviceId)?.hostname ?? "Unknown member";
}

/** Members with roles, pair state and sync state for a Cisco ASA failover pair or FortiGate HA cluster; presentation only. */
export function HaPairLine({ row }: { row: CpFailoverSummary }) {
  const view = row.haView;
  if (!view) return null;
  const tone = view.state === "HEALTHY" ? { bg: m3.successContainer, ink: m3.onSuccessContainer }
    : view.state === "DEGRADED" ? { bg: m3.errorContainer, ink: m3.onErrorContainer } : { bg: m3.scHigh, ink: m3.onSurfaceVar };
  return <Box data-ha-pair={view.vendor} sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
    <Chip size="small" label={STATE_WORD[view.state]} title={view.reason ? REASON_WORD[view.reason] ?? view.reason : undefined}
      sx={{ bgcolor: tone.bg, color: tone.ink }} />
    {view.members.map(m => <Typography key={m.device_id} variant="body2" data-ha-member={m.device_id}>
      {memberName(row, m.device_id)}: {haRoleWord(m.role)}</Typography>)}
    <Typography variant="caption" title={view.syncReason}>Sync: Unknown (not collected)</Typography>
    {view.reason && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{REASON_WORD[view.reason] ?? view.reason}</Typography>}
  </Box>;
}
