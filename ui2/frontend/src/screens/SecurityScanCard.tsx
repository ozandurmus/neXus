import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { StatusChip } from "../shell/M3Widgets";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { getSecurityScanSummary, type SecurityScanSummaryView } from "../auth/adminApi";

const TOOLS = ["semgrep", "gitleaks", "trivy", "zap"] as const;
const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNKNOWN"] as const;

/** Why the daily results are not reaching a person by mail, in the words of Settings > Notifications. */
export function mailWarning(view: SecurityScanSummaryView): string | null {
  const mail = view.mail;
  if (mail.delivering) return null;
  const missing = [
    !mail.route_enabled && "the Security scans notification route is off",
    !mail.smtp_configured && "SMTP is not configured",
    !mail.recipients_configured && "no recipient is set",
  ].filter(Boolean);
  return `Daily scan results are not being mailed: configure Settings > Notifications (${missing.join("; ")}).`;
}

/** Administration > Platform status > Security scans: the last daily scan, counts and the HIGH/MEDIUM findings. */
export function SecurityScanCard() {
  const [view, setView] = useState<SecurityScanSummaryView | null>(null);
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    let live = true;
    Promise.resolve().then(() => getSecurityScanSummary())
      .then((v) => { if (live) setView(v); })
      .catch(() => { if (live) setUnavailable(true); });
    return () => { live = false; };
  }, []);

  const shell = (children: React.ReactNode) => (
    <Card data-testid="security-scans-card" sx={{ p: 2, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
      <Typography variant="overline" sx={{ color: m3.onSurfaceVar }}>Security scans</Typography>
      <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5, mb: 1.5 }}>Latest daily scan</Typography>
      {children}
    </Card>
  );

  if (unavailable) return shell(<Typography variant="body2" color="text.secondary">The latest scan summary is not available to your role or could not be read.</Typography>);
  if (!view) return shell(<Typography variant="body2" color="text.secondary">Reading the latest scan summary…</Typography>);

  const warning = mailWarning(view);
  const rows = view.findings ?? [];
  const total = view.findings_total ?? rows.length;
  return shell(
    <>
      {warning && <Alert severity="warning" data-status-alert="operational" data-testid="security-scan-mail-warning" sx={{ mb: 1.5 }}>{warning}</Alert>}
      {!view.available && (
        <Alert severity="warning" data-status-alert="operational" sx={{ mb: 1.5 }}>
          {view.reason === "SUMMARY_UNREADABLE"
            ? "The daily scan wrote a summary this page cannot read."
            : "No daily scan summary exists yet. The scan runs at 02:30."}
        </Alert>
      )}
      {view.available && view.stale && (
        <Alert severity="warning" data-status-alert="operational" data-testid="security-scan-stale" sx={{ mb: 1.5 }}>
          {`The last daily scan finished ${view.age_hours} h ago; it is expected every 24 h. Check the security-daily job.`}
        </Alert>
      )}
      {view.available && (
        <>
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap", mb: 1.5 }}>
            <Typography variant="body2">Last daily scan: {formatStamp(view.generated_at ?? "")}</Typography>
            <StatusChip tone={view.passed ? "ok" : "bad"} label={view.passed ? "Gate passed" : "Gate blocked"} dense />
            {(view.scan_errors ?? 0) > 0 && <StatusChip tone="warn" label={`${view.scan_errors} scan error${view.scan_errors === 1 ? "" : "s"}`} dense />}
            {(view.blocking ?? 0) > 0 && <StatusChip tone="bad" label={`${view.blocking} blocking`} dense />}
          </Box>
          {(view.failed ?? []).length > 0 && (
            <Typography variant="body2" sx={{ mb: 1 }}>Failed reads: {(view.failed ?? []).map((f) => `${f.file} (${f.cause})`).join(", ")}</Typography>
          )}
          <Table size="small" data-testid="security-scan-counts" sx={{ mb: 2 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>TOOL</TableCell>
                {SEVERITIES.map((s) => <TableCell key={s} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{s}</TableCell>)}
              </TableRow>
            </TableHead>
            <TableBody>
              {TOOLS.map((tool) => (
                <TableRow key={tool}>
                  <TableCell sx={{ fontWeight: 600 }}>{tool}</TableCell>
                  {SEVERITIES.map((s) => {
                    const c = view.counts?.[tool]?.[s];
                    const open = c ? c.new + c.existing + c.accepted : 0;
                    return <TableCell key={s} title={c ? `new ${c.new}, existing ${c.existing}, accepted ${c.accepted}, fixed ${c.fixed}` : undefined}>{open === 0 ? "—" : c && c.new > 0 ? `${open} (${c.new} new)` : String(open)}</TableCell>;
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {view.findings_listed_in_summary === false ? (
            <Typography variant="body2" color="text.secondary">This summary was written before the finding list existed; the list appears after the next daily scan.</Typography>
          ) : rows.length === 0 ? (
            <Typography variant="body2">No high or medium findings.</Typography>
          ) : (
            <>
              <Typography variant="subtitle2" sx={{ mb: 0.5 }}>High and medium findings ({total}{total > rows.length ? `, first ${rows.length} shown` : ""})</Typography>
              <Table size="small" data-testid="security-scan-findings">
                <TableHead>
                  <TableRow>
                    {["SEVERITY", "STATUS", "TOOL", "RULE", "LOCATION", "FIX AVAILABLE"].map((h) => <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{h}</TableCell>)}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((r, i) => (
                    <TableRow key={`${r.tool}-${r.rule}-${r.location}-${i}`} hover>
                      <TableCell><StatusChip tone={r.severity === "MEDIUM" ? "warn" : "bad"} label={r.severity} dense /></TableCell>
                      <TableCell>{r.status}</TableCell>
                      <TableCell>{r.tool}</TableCell>
                      <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{r.rule}</TableCell>
                      <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{r.location}</TableCell>
                      <TableCell>{r.fix_available ? "Yes" : "No"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </>
      )}
    </>,
  );
}
