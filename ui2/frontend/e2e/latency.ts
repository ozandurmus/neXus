import exceptions from "./latency-allowlist.json" with { type: "json" };

export type LatencyException = { path: string; reason: string; expires: string; budgetMs: number };
// Only these literal templates may reach reports. Unknown routes are withheld,
// including queries, opaque IDs and arbitrary path segments.
const templates = [
  "/api/v2/diagnostics/targets", "/api/v2/diagnostics/ports", "/api/v2/diagnostics/history",
  "/api/v2/diagnostics/preview", "/api/v2/diagnostics/{id}",
  "/api/v2/jobs", "/api/v2/jobs/stats", "/api/v2/jobs/facets", "/api/v2/jobs/export.csv", "/api/v2/jobs/{id}", "/api/v2/jobs/{id}/events",
  "/api/v2/search", "/api/v2/backups/policies", "/api/v2/backups/deviations",
  "/api/v2/overview", "/api/v2/compliance/controls/{id}", "/api/v2/config/notifications",
  "/api/v2/system/pods", "/api/v2/system/storage",
  ...["cp-failover", "pan-failover"].flatMap(vendor => [
    `/api/v2/${vendor}/summary`, `/api/v2/${vendor}/units`, `/api/v2/${vendor}/runs`,
    `/api/v2/${vendor}/runs/{id}`, `/api/v2/${vendor}/approvals`,
  ]),
];
export function pathTemplate(url: string): string {
  const path = new URL(url).pathname;
  return templates.find(t => new RegExp(`^${t.replaceAll(".", "\\.").replaceAll("{id}", "[^/]+")}$`).test(path)) ?? "/api/{unmapped}";
}
export function validateAllowlist(entries: readonly LatencyException[], today = new Date().toISOString().slice(0, 10)) {
  const seen = new Set<string>();
  for (const entry of entries) {
    if (!templates.includes(entry.path) || seen.has(entry.path) || !entry.reason.trim()
      || !/^\d{4}-\d{2}-\d{2}$/.test(entry.expires) || !Number.isFinite(Date.parse(entry.expires))
      || new Date(entry.expires).toISOString().slice(0, 10) !== entry.expires || entry.expires < today
      || !Number.isFinite(entry.budgetMs) || entry.budgetMs <= 3000) {
      throw new Error("Invalid or expired API latency exception (values withheld)");
    }
    seen.add(entry.path);
  }
}
export function latencyViolation(url: string, elapsedMs: number, entries: readonly LatencyException[] = exceptions): string | null {
  const path = pathTemplate(url);
  const budget = entries.find(e => e.path === path)?.budgetMs ?? 3000;
  return elapsedMs > budget ? `API latency budget exceeded: ${path} (${Math.round(elapsedMs)} ms > ${budget} ms)` : null;
}
export function fullMode(): boolean {
  const mode = process.env.NEXUS_E2E_MODE ?? "quick";
  if (mode !== "quick" && mode !== "full") throw new Error("NEXUS_E2E_MODE must be quick or full");
  if (mode === "full") validateAllowlist(exceptions);
  return mode === "full";
}
