# Check Point management policy reads

Status: RATIFIED — Product Owner approved, 2026-10-01.

The Product Owner approved exactly the following three read forms with "ok abi":

```text
mgmt_cli -r true -d <DOMAIN> -f json show-packages limit 500 details-level full
mgmt_cli -r true -d <DOMAIN> -f json show-access-rulebase name <LAYER> limit 500 offset <N> details-level full use-object-dictionary true
mgmt_cli -r true -d <DOMAIN> -f json show-nat-rulebase package <PKG> limit 500 offset <N> details-level standard use-object-dictionary true
```

The management-plane model was stated as: "politika paketleri MDS üzerinde bulunan domainlerde; GW sadece politika paketine assign ediliyor".
Policy packages belong to MDS domains; gateways are installation targets. This is configured management intent, not proof of installation or enforcement.

The three gate rows use check_point / cp_multi_domain_server / expert / SSH_EXEC, action class read, SIGNED_OFF.
Arguments use MgmtCliCommands.quote, including numeric offsets. No other new command is authorized.
The existing show-domains read is reused. No gateway execution, bash login shell, extra object lookup, login, publish or policy installation is introduced.

Connect timeout: 30 seconds. Each read: 60 seconds, no retry. Job deadline: 30 minutes; at most 200 pages per rulebase and 200 distinct access layers per package, serially.
One trusted interactive SSH session per job, closed on success or failure. Unsupported, malformed, truncated, inconsistent or timed-out responses fail closed; previous snapshots remain.
Only completed, registered Check Point management-server discovery targets are eligible. Administration can request a collection; successful MDS discovery queues automatic collection, limited to one attempt per domain in six hours (including failed attempts).
Raw responses remain in memory. Only normalized policy data is persisted; telemetry contains outcome classes and counts. Existing Policy AIView masking applies.
The job ledger records read attempts; snapshot publication is atomic and lease-fenced. Local synthetic validation does not prove real-environment semantics.

This decision authorizes implementation and local commits on the collector lane only. It does not authorize worker device access, deployment, push, PR or merge. Project state is maintained by the parent engineering session.

Install targets use exact stored discovery relations to enrolled devices. An unmatched target retains a source/domain/native-UID-scoped opaque reference and UNKNOWN synchronization state; its display name is never used to join it to a device. Empty or unsupported vendor shapes are not interpreted as evidence of installation.

Local validation: TypeScript, 379 Vitest tests, frontend build, the offline migration/fixture test and repository privacy gate passed. Java/Gradle and live MDS behavior are UNVERIFIED (worker execution prohibited). The legacy HTML harness passed five tests, skipped one, and failed its Playwright smoke test because Chromium startup was denied by the macOS sandbox; no browser inspection completed. Project-state updates remain with the parent engineering session.
