// Literal path segments from src/auth/adminApi.ts and latency.ts only.
// Never learn words from requests, decode segments, or include query/fragment values.
const staticWords = new Set(`accept acknowledge add-single api approvals audit-logs backup backup-baseline
backup-target backups clusters collect collect-all compare compliance config configuration confirm controls
cp-failover credential credentials delete devices deviations diagnostics disable discovery download download-ticket
enable entries evaluate events export.csv facets history https-certificate import inventory jobs local-identities
management-tree notifications onboarding overview pan-failover pods policies ports preview project-plan readiness
relist replace-secret retry revoke role-bindings runs search secrets sessions set-password stats storage strict
summary system targets text transcript units v2`.split(/\s+/));

export function sanitizedPath(url: string): string {
  try {
    return new URL(url).pathname.split("/").map(segment =>
      segment === "" ? "" : staticWords.has(segment) ? segment : "{id}").join("/");
  } catch {
    return "/{id}";
  }
}

export function requestViolation(method: string, url: string, status?: number): string {
  // HTTP methods are extensible; report only known verbs, never arbitrary request tokens.
  const verb = /^(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|CONNECT|TRACE)$/.test(method) ? method : "{method}";
  return `API ${verb} ${sanitizedPath(url)} ${status === undefined ? "request failed" : `returned HTTP ${status}`}`;
}
