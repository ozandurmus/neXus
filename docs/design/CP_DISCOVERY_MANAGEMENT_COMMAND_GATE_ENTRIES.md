# Check Point discovery — management-plane command gate entries

## Status

**DRAFT — PENDING PRODUCT OWNER GATE APPROVAL.** This document records, for
`docs/AI_DEVELOPMENT_PROTOCOL.md`'s "Network-device command gate", the ten
required items for every command `ManagementPlaneEnumerationAdapter`
(`ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/`)
can issue. **It authorizes nothing until the Product Owner approves it.**
Nothing here may be cited as command approval, and none of these commands may
be run against a real management server before that approval — the gate lift
recorded in `docs/design/PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_
GATE.md` covers the four *methods* (session, domain enumeration, object
queries, connection-table read); this document is the separate, still-open
command-level gate `AGENTS.md` requires before any of the seven concrete
command strings below is issued for real.

Every command string in this document, and every field name
`ManagementPlaneEnumerationAdapter` binds through `ManagementApiFieldBinding`,
is `UNVERIFIED`: a candidate this movement could not measure, because the only
management server that could confirm it belongs to the Product Owner. This
document does not assert vendor semantics; it records what this transport
movement would send, so the Product Owner can confirm or correct it against
the real server in one place.

## How the ten items map to each entry

1. **Why required** — the contract clause the command discharges.
2. **Taxonomy class** — `utils/action_taxonomy.py`. Every entry below is
   `CLASS_0_READ`; no entry may become anything else without its own new gate
   review.
3. **Vendor / platform / shell / context** — Check Point Management
   (including Multi-Domain Server); a shell session opened by the existing
   `ssh_exec` transport (`SshExecTransport`); the `mgmt_cli` command-line tool
   run inside that shell, `-f json` output.
4. **Timeout** — the adapter's fixed `EXEC_TIMEOUT` (30 seconds) applies to
   every command; not configurable per command at this movement.
5. **Retry** — none. A failed command fails the run (T-1's close-on-failure
   path); this movement introduces no retry loop for any command.
6. **Maximum execution frequency per endpoint** — one full discovery run at a
   time, invoked manually by the Product Owner's runner; this movement adds no
   scheduler and no automatic re-invocation (T-6, `AGENTS.md` "Engineering
   laws").
7. **Existing-session reuse** — all seven commands are issued inside the one
   `DeviceTransport` session `connect()` opens for the run (T-1); none of them
   opens a second session.
8. **Unsupported behaviour** — a non-JSON or malformed response, a missing
   bound field, or a non-zero exit status is treated as a failed query
   (`ManagementPlaneQueryFailedException`) and ends the run with `Failed`,
   never retried and never guessed at.
9. **Secret-bearing output risk** — none of the seven commands' arguments or
   documented response shapes carry a password, private key or other secret;
   `credentialRef`/`trustRuleRef` are opaque references, never resolved to
   material before being handed to `ConnectSpec` (T-5). The session token
   (`SESSION_IDENTIFIER`/`sid`) is not a device credential, but it is
   sensitive enough to page context that it is never included in a log line,
   exception message or result object (T-7).
10. **Safe telemetry** — the run result carries only counts and shapes (§9
    check 17's request count, per-kind candidate counts, per-`CS-1`-state
    channel-state counts); no command string, no raw response and no parsed
    field value is retained or printed outside the returned candidate set
    itself.

## The seven entries

| # | Command (built by) | 1. Why required | 2. Class | 3. Vendor/platform/shell/context | 4. Timeout | 5. Retry | 6. Max frequency/endpoint | 7. Session reuse | 8. Unsupported behaviour | 9. Secret risk | 10. Safe telemetry |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ManagementShellCommands.login()` | T-1: opens the one management-plane session a run uses for everything else | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s (`EXEC_TIMEOUT`) | none | one per discovery run | first command of the session; every later command reuses its `sid` | non-JSON/missing `sid` → `Failed`, run ends | credentialRef/trustRuleRef only, never a secret; returns a session token, not a credential | none (no candidate/count derives from this call) |
| 2 | `ManagementShellCommands.loginToDomain(sessionId, domainIdentifier)` | T-2 context switch: scopes the following per-domain queries to one domain | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | once per domain per run | reuses the top-level `sid`; issued inside the one session | non-JSON/missing `sid` → `Failed`, run ends | `sessionId`/`domainIdentifier` are values this run already holds, quoted as one shell argument (never string-concatenated unquoted) | not counted by check 17's formula (context switch, not a data query) |
| 3 | `ManagementShellCommands.showDomains(sessionId, offset)` | T-2 domain enumeration | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | one call per page, once per run | reuses the top-level `sid` | non-JSON/missing `uid` on an entry → `Failed`, run ends | none | counted 1:1 into check 17's "one domain enumeration... the number of pages required" |
| 4 | `ManagementShellCommands.showObjects(GATEWAY, sessionId, offset)` | T-2 per-domain object query, gateway type (§4.1) | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | one call per page, per domain, per run | reuses that domain's `sid` from entry 2 | non-JSON/missing bound field on an entry → `Failed`, run ends | none | counted 1:1 into check 17's per-domain, per-object-type page count |
| 5 | `ManagementShellCommands.showObjects(CLUSTER, sessionId, offset)` | T-2 per-domain object query, cluster type (§4.1) | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | one call per page, per domain, per run | reuses that domain's `sid` | same as entry 4 | none | counted 1:1, same as entry 4 |
| 6 | `ManagementShellCommands.showObjects(MEMBER, sessionId, offset)` | T-2 per-domain object query, member type (§4.1) | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | one call per page, per domain, per run | reuses that domain's `sid` | same as entry 4 | none | counted 1:1, same as entry 4 |
| 7 | `ManagementShellCommands.showConnectionTable(sessionId)` | §7.4: the connection-table channel-state read, taken twice per CS-3 | CLASS_0_READ | Check Point Management, `mgmt_cli` in the `ssh_exec` shell | 30s | none | exactly twice per run, separated by the caller's CS-3 interval | reuses the top-level `sid` | non-JSON/missing bound field on a row → `Failed`, run ends | none beyond entry 1's session-token note | not counted by check 17's formula; contributes only to the per-`CS-1`-state channel-state counts |

## No command outside this table

`ManagementShellCommands.CLOSED_COMMAND_PREFIXES` is the machine-checked
mirror of the seven rows above; `ManagementShellCommandsClosedSetTest`
(`ui2/worker/src/test/java/...`) asserts every command a full fixture run
issues starts with one of them. Adding an eighth command requires both a new
row here and a new closed-set entry — never one without the other.

## Cross-references

- `docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` (FROZEN) §3, §7.4 — the
  contract these commands implement.
- `docs/design/PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`
  (FROZEN) — the collection-type gate lift these commands stay inside;
  distinct from this document's still-open command-level gate.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — the
  ten-item structure this document follows.
