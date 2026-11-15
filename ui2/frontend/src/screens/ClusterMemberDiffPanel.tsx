import Box from "@mui/material/Box";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { getClusterMemberDiff, type ClusterMemberDiff, type MemberDiffStatus } from "../auth/adminApi";
import { EmptyPanel } from "../shell/ScreenLayout";
import { StatusChip } from "../shell/M3Widgets";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { m3 } from "../theme/m3Theme";

const STATUS_WORD: Record<MemberDiffStatus, string> = { SAME: "Same", DIFFERS: "Differs", UNKNOWN: "Unknown (not stored)" };
const STATUS_TONE: Record<MemberDiffStatus, "ok" | "warn" | "neutral"> = { SAME: "ok", DIFFERS: "warn", UNKNOWN: "neutral" };

/** The result table; split from the fetch so it renders from data alone. */
export function ClusterMemberDiffView({ diff, labels }: { readonly diff: ClusterMemberDiff; readonly labels: Readonly<Record<string, string>> }) {
  if (!diff.comparable) {
    return <EmptyPanel title="Members cannot be compared"
      body={diff.reason === "PAIR_INCOMPLETE" ? "Fewer than two members of this cluster are enrolled, so there is nothing to compare." : "This cluster cannot be compared."} />;
  }
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }} data-member-diff-summary>
        {diff.summary.differs} differ · {diff.summary.same} same · {diff.summary.unknown} unknown. Compared from what is already stored for each member;
        a value that is not stored is shown as Unknown, never as equal.
      </Typography>
      <Table size="small" aria-label="Member differences">
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 600 }}>Field</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>Result</TableCell>
            {diff.members.map((m) => <TableCell key={m.device_id} sx={{ fontWeight: 600 }}>{labels[m.device_id] ?? "Member"}</TableCell>)}
          </TableRow>
        </TableHead>
        <TableBody>
          {diff.fields.map((f) => (
            <TableRow key={f.key} data-member-diff-field={f.key} data-status={f.status}>
              <TableCell sx={{ verticalAlign: "top" }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{f.label}</Typography>
                {f.note && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{f.note}</Typography>}
              </TableCell>
              <TableCell sx={{ verticalAlign: "top" }}>
                <StatusChip tone={STATUS_TONE[f.status]} label={STATUS_WORD[f.status]} dense />
                {f.status === "DIFFERS" && f.detail.length > 0 && (
                  <Typography variant="caption" component="div" sx={{ color: m3.onSurfaceVar, mt: 0.5, wordBreak: "break-word" }}>
                    Not on every member: {f.detail.join(", ")}
                  </Typography>
                )}
              </TableCell>
              {diff.members.map((m) => {
                const v = f.values[m.device_id];
                return <TableCell key={m.device_id} sx={{ verticalAlign: "top", wordBreak: "break-word" }}>{v ?? "Unknown"}</TableCell>;
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}

export function ClusterMemberDiffPanel({ clusterRef, labels }: { readonly clusterRef: string; readonly labels: Readonly<Record<string, string>> }) {
  const fetched = useFetchOnMount<ClusterMemberDiff>(() => getClusterMemberDiff(clusterRef), () => "The member differences could not be read.");
  if (fetched.error) return <EmptyPanel title="Member differences unavailable" body={fetched.error} />;
  if (!fetched.data) return <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Loading member differences…</Typography>;
  return <ClusterMemberDiffView diff={fetched.data} labels={labels} />;
}
