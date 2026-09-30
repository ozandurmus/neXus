import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import type { ReadinessCheck } from "../auth/adminApi";

export function ReadinessChecksTable({ checks }: { checks: ReadinessCheck[] }) {
  const numbers = [...new Set(checks.map(check => check.checkNo))].sort((a, b) => a - b);
  return <Table size="small" aria-label="Readiness results">
    <TableHead><TableRow><TableCell>Check</TableCell><TableCell>Member 1</TableCell><TableCell>Member 2</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
    <TableBody>{numbers.map(no => {
      const rows = checks.filter(check => check.checkNo === no);
      const statuses = rows.map(row => row.result);
      const result = statuses.includes("FAIL") ? "FAIL" : statuses.includes("UNKNOWN") || rows.length < 2 ? "UNKNOWN" : statuses.includes("WARN") ? "WARN" : "PASS";
      return <TableRow key={no}>
        <TableCell>{rows[0].title}<Typography variant="caption" display="block">{rows[0].blocking ? "Blocking" : "Informational"}</Typography></TableCell>
        {["Member 1", "Member 2"].map(member => {
          const row = rows.find(check => check.member === member);
          return <TableCell key={member}>{row?.summary ?? "Not collected"}<Typography variant="caption" display="block">{row?.result ?? "UNKNOWN"}</Typography></TableCell>;
        })}
        <TableCell>{result}</TableCell>
      </TableRow>;
    })}</TableBody>
  </Table>;
}
