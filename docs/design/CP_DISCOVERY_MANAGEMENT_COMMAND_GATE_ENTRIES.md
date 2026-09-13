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
command-level gate `AGENTS.md` requires before any of the three concrete
command forms below is issued for real.

**Rewritten 2026-09-13** to the method the Product Owner measured against a
live multi-domain management server, recorded in `docs/design/
CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md` (DRAFT —
evidence, not authority) §§4 and 10. The seven `mgmt_cli` entries this
document previously carried were entirely guessed, never run against a real
server; the three entries below are the forms that were actually run and
whose output the record describes. Every command string in this document,
and every field name `ManagementPlaneEnumerationAdapter` binds through
`ManagementApiFieldBinding`, is still `UNVERIFIED`: a candidate this movement
aligned to the measurement, not a Product-Owner-run confirmation of the
*aligned transport* itself — record §10's closing line: "every entry stays
UNVERIFIED until the first live run of the aligned transport reports its
counts".

## How the ten items map to each entry

1. **Why required** — the contract clause the command discharges.
2. **Taxonomy class** — `utils/action_taxonomy.py`. Every entry below is
   `CLASS_0_READ`; no entry may become anything else without its own new gate
   review.
3. **Vendor / platform / shell / context** — Check Point Management,
   Multi-Domain Server; a shell session opened by the existing `ssh_exec`
   transport (`SshExecTransport`); the management server's own shell —
   `$MDSVERUTIL`, the `mdsenv` shell function, and the `cpmiquerybin`
   command-line tool run inside that shell (record §4). No `mgmt_cli`
   anywhere (record §0).
4. **Timeout** — the adapter's fixed `EXEC_TIMEOUT` (30 seconds) applies to
   every command; not configurable per command at this movement.
5. **Retry** — none. A failed command fails the run (T-1's close-on-failure
   path); this movement introduces no retry loop for any command.
6. **Maximum execution frequency per endpoint** — one full discovery run at a
   time, invoked manually by the Product Owner's runner; this movement adds no
   scheduler and no automatic re-invocation (T-6, `AGENTS.md` "Engineering
   laws").
7. **Existing-session reuse** — all three commands are issued inside the one
   `DeviceTransport` session `connect()` opens for the run (T-1); none of them
   opens a second session, and none needs a session token — the SSH session
   itself is the one management-plane session T-1 requires (no `mgmt_cli
   login`-shaped call exists in the measured method; `SESSION_IDENTIFIER` is
   not a bound role in this transport).
8. **Unsupported behaviour** — a non-zero exit status, or a response the
   parser cannot make sense of (an unparsable object dump, a missing bound
   field where the record expects one), is treated as a failed query
   (`ManagementPlaneQueryFailedException`) and ends the run with `Failed`,
   never retried and never guessed at.
9. **Secret-bearing output risk** — none of the three commands' arguments or
   documented response shapes carry a password, private key or other secret;
   `credentialRef`/`trustRuleRef` are opaque references, never resolved to
   material before being handed to `ConnectSpec` (T-5).
10. **Safe telemetry** — the run result carries only counts and shapes (§9
    check 17's request count, per-kind candidate counts, per-object-type
    parsed/missing-stable-identifier counts, per-`CS-1`-state channel-state
    counts); no command string, no raw response and no parsed field value is
    retained or printed outside the returned candidate set itself.

## The three entries

| # | Command (built by) | 1. Why required | 2. Class | 3. Vendor/platform/shell/context | 4. Timeout | 5. Retry | 6. Max frequency/endpoint | 7. Session reuse | 8. Unsupported behaviour | 9. Secret risk | 10. Safe telemetry |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ManagementShellCommands.domainList()` — literally `$MDSVERUTIL AllCMAs`, no interpolation (record §4 item 1) | T-2 domain enumeration: lists the domain management servers so every later query is scoped to one | CLASS_0_READ | Check Point Multi-Domain Server, the management server's own shell reached through `ssh_exec` | 30s (`EXEC_TIMEOUT`) | none | once per discovery run | first command of the session; the SSH session itself is reused for every later command | non-zero exit → `Failed`, run ends; the response is read one domain identifier per line (record §10), never JSON | none | counted once into check 17's formula |
| 2 | `ManagementShellCommands.contextSwitchAndObjectQuery(domainIdentifier, objectType)` — `mdsenv <domain>&& cpmiquerybin object "" network_objects "type='<gateway\|gateway_cluster\|cluster_member>'"`, one shell line, issued once per domain per object type (record §4 items 2 and 5, §10 row 4) | T-2 context switch AND per-domain, per-object-type object query, together — record §3 row 2/§10: `mdsenv` alters the calling shell and MUST run on the same line as the query it scopes, or the context is silently discarded and every later query runs against the wrong domain (measured, not assumed) | CLASS_0_READ | Check Point Management (per-domain), the management server's own shell reached through `ssh_exec` | 30s | none | one call per domain per object type (three object types), per run — no pagination in the `object` result type (record §10 row 4) | reuses the one session; `domainIdentifier` is quoted as a single POSIX shell argument (T-5, never string-concatenated unquoted) — the filter clause (`type='...'`) is one of exactly three closed, code-controlled constants, never an interpolated response value | non-zero exit, or an object dump `CpObjectDumpParser` cannot parse, → `Failed`, run ends; an individual object missing its stable identifier (`AdminInfo/chkpf_uid`) is counted and skipped, not a run failure | `domainIdentifier` is a value this run already holds from entry 1's own response, not a secret | counted 1:1 into check 17's per-domain, per-object-type count; per-object-type parsed/missing-stable-identifier counts are the only per-object telemetry that survives into the run report |
| 3 | `ManagementShellCommands.connectionTable()` — literally `netstat -an`, no interpolation (record §4 item 6) | §7.4: the management server's own operating-system connection table — no packet sent to any device, kernel state read only. Read twice per CS-3 | CLASS_0_READ | the management server's own operating-system shell, reached through the same `ssh_exec` session — not a management-tool command at all | 30s | none | exactly twice per run, separated by the caller's CS-3 interval | reuses the one session | non-zero exit → `Failed`, run ends; the raw text is filtered and reduced to `ConnectionTableRow` values in Java (`NetstatConnectionTableParser`, `ConnectionTableReducer`) — never by an awk pipeline on the server (T-7) | none | not counted by check 17's formula; contributes only to the per-`CS-1`-state channel-state counts |

## No command outside this table

`ManagementShellCommands.CLOSED_COMMAND_PREFIXES` is the machine-checked
mirror of the three rows above; `ManagementShellCommandsClosedSetTest`
(`ui2/worker/src/test/java/...`) asserts every command a full fixture run
issues starts with one of them. Adding a fourth command requires both a new
row here and a new closed-set entry — never one without the other.

## Cross-references

- `docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` (FROZEN) §3, §7.4 — the
  contract these commands implement.
- `docs/design/CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md`
  (DRAFT — evidence, not authority) §§3, 4, 5, 10 — the measurement these
  three entries are aligned to.
- `docs/design/PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`
  (FROZEN) — the collection-type gate lift these commands stay inside;
  distinct from this document's still-open command-level gate.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — the
  ten-item structure this document follows.
