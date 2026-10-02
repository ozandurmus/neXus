# Check Point management policy reads

Status: RATIFIED — Product Owner approved, 2026-10-01; pagination amendment approved 2026-10-02 02:41 UTC.

The Product Owner approved exactly the following three read forms with "ok abi":

```text
mgmt_cli -r true -d <DOMAIN> -f json show-packages limit 500 details-level full
mgmt_cli -r true -d <DOMAIN> -f json show-access-rulebase name <LAYER> limit 100 offset <N> details-level full use-object-dictionary true
mgmt_cli -r true -d <DOMAIN> -f json show-nat-rulebase package <PKG> limit 500 offset <N> details-level standard use-object-dictionary true
```

The management-plane model was stated as: "politika paketleri MDS üzerinde bulunan domainlerde; GW sadece politika paketine assign ediliyor".
Policy packages belong to MDS domains; gateways are installation targets. This is configured management intent, not proof of installation or enforcement.

The three gate rows use check_point / cp_multi_domain_server / expert / SSH_EXEC, action class read, SIGNED_OFF.
Arguments use MgmtCliCommands.quote, including numeric offsets. No other new command is authorized.
The existing show-domains read is reused. No gateway execution, bash login shell, extra object lookup, login, publish or policy installation is introduced.

Connect timeout: 30 seconds. Package/domain/NAT reads: 60 seconds, no retry. Access pages: limit 100, 300 seconds per page (bounded by the job deadline); retry a timed-out page exactly once at the same offset with limit 50. The retry retains details-level full and use-object-dictionary true. V118 amends the existing access gate; no new verb is authorized. Job deadline: 30 minutes; at most 200 pages per rulebase and 200 distinct access layers per package, serially.
One trusted SSH connection per job, closed on success or failure. The existing transport closes and recreates a timed-out interactive shell before the one approved retry, so a late response cannot be mistaken for the next page. Unsupported, malformed, truncated or inconsistent pages fail closed. A failed access layer is omitted and recorded by opaque layer reference and safe reason; completed sibling layers survive in an explicitly incomplete snapshot. Partial publication and the FAILED terminal transition remain atomic and lease-fenced. Fatal preflight/package/NAT failures preserve previous snapshots.
Sanitized transcripts record each request template, opaque target reference, byte count, elapsed time and failure step; raw responses and real command arguments are never retained.
Only completed, registered Check Point management-server discovery targets are eligible. Administration can request a collection; successful MDS discovery queues automatic collection, limited to one attempt per domain in six hours (including failed attempts).
Raw responses remain in memory. Only normalized policy data is persisted; telemetry contains outcome classes and counts. Existing Policy AIView masking applies.
The job ledger records read attempts; snapshot publication is atomic and lease-fenced. Local synthetic validation does not prove real-environment semantics.

This decision authorizes implementation and local commits on the collector lane only. It does not authorize worker device access, deployment, push, PR or merge. Project state is maintained by the parent engineering session.

Install targets use exact stored discovery relations to enrolled devices. An unmatched target retains a source/domain/native-UID-scoped opaque reference and UNKNOWN synchronization state; its display name is never used to join it to a device. Empty or unsupported vendor shapes are not interpreted as evidence of installation.

Local validation: TypeScript, 379 Vitest tests, frontend build, the offline migration/fixture test and repository privacy gate passed. Java/Gradle and live MDS behavior are UNVERIFIED (worker execution prohibited). The legacy HTML harness passed five tests, skipped one, and failed its Playwright smoke test because Chromium startup was denied by the macOS sandbox; no browser inspection completed. Project-state updates remain with the parent engineering session.
