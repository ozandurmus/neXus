/**
 * A blocking readiness check whose result is WARN warns and does not block (PO 2026-11-23,
 * PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md: a Palo Alto pair with no sessions has zero session sync
 * counters). The unit stays READY; its badge must never say "Ready" without naming the warning.
 */
export interface WarnableCheck {
  readonly checkNo: number;
  readonly result?: string | null;
  readonly status?: string | null;
  readonly blocking?: boolean;
}

/** The distinct blocking check numbers with a WARN result and no FAIL, ascending. */
export function readinessWarningChecks(checks: ReadonlyArray<WarnableCheck> | null | undefined): number[] {
  const rows = checks ?? [];
  const outcome = (check: WarnableCheck) => check.result ?? check.status ?? null;
  const warned = new Set<number>();
  for (const check of rows) if (check.blocking && outcome(check) === "WARN") warned.add(check.checkNo);
  for (const check of rows) if (check.blocking && outcome(check) === "FAIL") warned.delete(check.checkNo);
  return [...warned].sort((a, b) => a - b);
}

/** "Ready", or "Ready · 1 warning" / "Ready · 2 warnings" when blocking checks warned. */
export function readyWord(warnings: number, ready = "Ready"): string {
  return warnings > 0 ? `${ready} · ${warnings} ${warnings === 1 ? "warning" : "warnings"}` : ready;
}
