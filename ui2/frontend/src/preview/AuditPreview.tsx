import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { StatusChip } from "../shell/M3Widgets";

/**
 * Design-canvas target for the Audit screen, populated with clearly
 * synthetic rows -- never fetched, never real. Exists only so `?preview=audit`
 * satisfies the same "populated look" contract every other screen's preview
 * does; the real screen (`AuditScreen`) never renders any of this.
 */
const ROWS = [
  { id: 4821, when: "2026-09-15T08:12:03Z", table: "role_bindings", pk: "rb-ist-0142", op: "UPDATE", actor: "fp-a91c3e0d", action: "ui2.role_bindings.create" },
  { id: 4820, when: "2026-09-15T08:10:41Z", table: "credential_references", pk: "cr-ist-0031", op: "INSERT", actor: "fp-2fbb7711", action: "ui2.credentials.create" },
  { id: 4819, when: "2026-09-15T07:58:19Z", table: "sessions", pk: "sess-8de210", op: "DELETE", actor: "fp-a91c3e0d", action: "ui2.session.logout" },
];

export function AuditPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Audit</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>3 rows shown</Typography>

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "minmax(280px, 380px) minmax(0, 1fr)" }}>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2, boxShadow: "none" }}>
          <Stack spacing={1.25}>
            {ROWS.map((row) => (
              <Box key={row.id} sx={{ border: "1px solid", borderColor: "divider", borderRadius: 2, p: 1.25 }}>
                <Box sx={{ display: "flex", justifyContent: "space-between" }}>
                  <Typography variant="body2">{row.table}</Typography>
                  <StatusChip tone="neutral" label={row.op} dense />
                </Box>
                <Typography variant="caption" color="text.secondary">{row.when}</Typography>
                <Typography variant="caption" sx={{ display: "block", fontFamily: "ui-monospace, monospace" }}>
                  row {row.pk}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", fontFamily: "ui-monospace, monospace" }}>
                  actor {row.actor}
                </Typography>
              </Box>
            ))}
          </Stack>
        </Card>

        <Card sx={{ bgcolor: m3.scLowest, borderRadius: "16px", p: 2.5, boxShadow: m3.e1 }}>
          <Typography variant="h4" sx={{ mb: 1.5 }}>Field projection · after</Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Column</TableCell>
                <TableCell>State</TableCell>
                <TableCell>Value</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              <TableRow>
                <TableCell>role_token</TableCell>
                <TableCell><StatusChip tone="ok" label="value" dense /></TableCell>
                <TableCell>security_admin</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>revoked_at</TableCell>
                <TableCell><StatusChip tone="neutral" label="null" dense /></TableCell>
                <TableCell><i>null</i></TableCell>
              </TableRow>
              <TableRow>
                <TableCell>group_reference_key_id</TableCell>
                <TableCell><StatusChip tone="warn" label="redacted" dense /></TableCell>
                <TableCell>Tier 1 · directory key material</TableCell>
              </TableRow>
              <TableRow>
                <TableCell>legacy_note</TableCell>
                <TableCell><StatusChip tone="neutral" label="not present" dense /></TableCell>
                <TableCell><i>not present in this snapshot</i></TableCell>
              </TableRow>
              <TableRow>
                <TableCell>future_column</TableCell>
                <TableCell><StatusChip tone="bad" label="unclassified" dense /></TableCell>
                <TableCell>role_bindings.future_column is not yet classified for display</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>
      </Box>
    </Box>
  );
}
