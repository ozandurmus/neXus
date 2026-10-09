import type { DeviceComplianceResult } from "../auth/adminApi";
import { GUIDANCE_WARNING, observedValue } from "./ComplianceGuidance";

const csv = (value: unknown) => {
  const text = String(value ?? "");
  // Quoting alone does not prevent spreadsheet formula execution.
  const safe = /^[\s]*[=+@-]/.test(text) || /^[\t\r\n]/.test(text) ? `'${text}` : text;
  return `"${safe.replace(/"/g, '""')}"`;
};
const html = (value: unknown) => String(value ?? "").replace(/[&<>"']/g, c =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** Export only the authenticated API projection; no raw configuration or client-side identity lookup. */
export function remediationReport(result: DeviceComplianceResult, format: "csv" | "html", masked: boolean): string {
  const findings = result.items.filter(item => item.displayStatus === "FAIL");
  const scope = masked ? "masked (aiview pseudonyms)" : "as recorded";
  if (format === "csv") {
    const rows: unknown[][] = [["device", "names", "control_id", "title", "severity", "observed", "why_it_matters",
      "summary", "steps", "cli", "references", "caution", "snippet_notice"]];
    for (const item of findings) {
      const g = item.guidance;
      rows.push([result.hostname, scope, item.controlId, item.title, item.severity, observedValue(item.observedValue),
        item.rationale ?? "Rationale not recorded", g?.summary ?? "Guidance not recorded for this evaluation.",
        g?.steps.map((s, i) => `${i + 1}. ${s}`).join("\n"), g?.cli,
        g?.references.join("\n"), g?.caution, g?.cli?.trim() ? GUIDANCE_WARNING : ""]);
    }
    return rows.map(row => row.map(csv).join(",")).join("\r\n");
  }
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>neXus remediation report</title><style>body{font:16px system-ui;max-width:960px;margin:2em auto;padding:0 1em}pre{white-space:pre-wrap;overflow-wrap:anywhere}section{border-top:1px solid #ccc;padding:1em 0}@media print{section{break-inside:avoid}body{margin:0}}</style></head><body>
<h1>Remediation report</h1><p>Device: ${html(result.hostname)} · ${html(scope)}</p>
<p>${findings.length} failing findings. Use your browser's Print action to print or save as PDF.</p>
${findings.map(item => {
    const g = item.guidance;
    return `<section><h2>${html(item.title)}</h2><p>${html(item.controlId)} · ${html(item.severity)}</p>
<h3>Observed</h3><p>${html(observedValue(item.observedValue))}</p>
<h3>Why it matters</h3><p>${html(item.rationale ?? "Rationale not recorded")}</p>
<h3>How to fix</h3><p>${html(g?.summary ?? "Guidance not recorded for this evaluation.")}</p>
<ol>${g?.steps.map(s => `<li>${html(s)}</li>`).join("") ?? ""}</ol>
${g?.cli?.trim() ? `<p>${GUIDANCE_WARNING}</p><pre>${html(g.cli)}</pre>` : ""}
${g?.caution ? `<p><strong>Caution:</strong> ${html(g.caution)}</p>` : ""}
<h3>References</h3><ul>${g?.references.map(r => `<li>${html(r)}</li>`).join("") ?? ""}</ul></section>`;
  }).join("\n")}</body></html>`;
}

export function downloadRemediationReport(result: DeviceComplianceResult, format: "csv" | "html", masked: boolean) {
  const blob = new Blob([remediationReport(result, format, masked)], { type: format === "csv" ? "text/csv;charset=utf-8" : "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `nexus-remediation-report.${format}`;
  anchor.click();
  URL.revokeObjectURL(url);
}
