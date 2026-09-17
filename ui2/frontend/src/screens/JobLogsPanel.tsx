import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";

import { EmptyPanel } from "../shell/ScreenLayout";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listJobs, type ApiError } from "../auth/adminApi";

function describeError(err: ApiError): string {
  return typeof err.body?.error === "string" ? err.body.error : `request failed (status ${err.status})`;
}

export function JobLogsPanel() {
  const { data, error } = useFetchOnMount(
    () => listJobs(),
    (err) => describeError(err as ApiError),
  );

  if (error) return <EmptyPanel title="Job logs unavailable" body={error} />;
  if (data === null) return <EmptyPanel title="Job logs" body="Loading…" />;
  if (data.length === 0) return <EmptyPanel title="No jobs" body="No background jobs have been submitted." />;

  return (
    <Table size="small" aria-label="Job logs">
      <TableHead>
        <TableRow>
          <TableCell>Job ID</TableCell><TableCell>Target Device</TableCell><TableCell>Job Type</TableCell>
          <TableCell>State</TableCell><TableCell>Terminal Reason</TableCell><TableCell>Submitted At</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {data.map((job) => (
          <TableRow key={job.job_id}>
            <TableCell>{job.job_id}</TableCell>
            <TableCell>{job.target_device_id}</TableCell>
            <TableCell>{job.job_type}</TableCell>
            <TableCell>{job.state}</TableCell>
            <TableCell>{job.terminal_reason || "-"}</TableCell>
            <TableCell>{job.submitted_at}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
