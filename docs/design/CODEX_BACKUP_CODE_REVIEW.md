[REJECTED]

The supplied implementation does not yet enforce the recovery-plane, retention, integrity, and API-security contracts. Several issues are release blockers, not optional hardening.

Review basis: supplied code only. Encryption-at-rest, egress controls, global authorization, database constraints, command-gate records, and `ArtefactStore` behavior remain unverified.

## 1. Architectural decoupling and plane separation

### Critical findings

- `PaloAltoBackupExecutor` couples recovery collection with configuration-evidence analysis. It loads the running configuration into a plaintext `String`, accepts the previous plaintext configuration from its caller, and invokes the deviation engine within the recovery executor.
- `Ui2BackupServer./api/v1/backup/diff` accepts raw configurations over HTTP and returns derived details. This creates a direct HTTP surface for sensitive configuration material.
- `SemanticDeviationEngine.diffMaps()` includes raw configuration values in results. Check Point interface addresses, routes, usernames, role settings, or secret-bearing lines could therefore reach API responses, logs, or browser rendering.
- `RetentionPruningService` crosses the storage abstraction and deletes arbitrary filesystem paths from database-supplied strings. This defeats opaque artefact addressing and creates a path-manipulation boundary.
- Write-once encrypted storage and egress denial are asserted only by comments. The supplied code does not prove encryption, key isolation, immutable storage, encrypted temporary handling, or network-policy enforcement.

### Required action

- Separate the workflows:
  - Recovery plane: acquire, validate, encrypt, register, retain, and restore recovery artefacts.
  - Evidence plane: consume a sanitized semantic projection or independently collect configuration evidence.
- Remove raw configuration input and raw-value output from the HTTP diff API. Prefer internal processing that emits only category, counts, opaque field paths, and masked pseudonyms.
- Replace `recoveryVolumePath` deletion with a store-owned operation such as `artefactStore.delete(ArtefactId)`. The retention layer must never construct or receive filesystem paths.
- Verify encryption and write-once behavior in the actual store implementation and deployment policy. `open(..., false)` is especially unclear and needs a named, security-relevant type rather than a boolean.
- Do not claim plane isolation until infrastructure-level egress controls and key boundaries are demonstrated.

## 2. Capacity and retention

### Critical findings

- Policy is inconsistent:
  - `PruningPolicy.DEFAULT`: 14 days and depth 4.
  - `BackupController`: 30 days and depth 2.
  - `Ui2BackupServer`: 30 days and depth 2.
  - Several comments still describe 30 days/depth 2.
- `maxStorageBudgetBytes` is never used. The 400 GiB limit is informational, not enforced.
- A single permitted 25 GiB snapshot means only 16 maximum-sized snapshots would consume the complete 400 GiB budget before daily backups, metadata, temporary writes, or safety reserve.
- The policy `PUT` endpoint does not persist anything, validates nothing, and nevertheless returns `UPDATED`.
- Failed deletions are recorded as pruned, counted, returned in `prunedArtefactIds`, and tombstoned.
- If file deletion succeeds and tombstone insertion fails, a destructive deletion exists without its mandatory audit record.
- The tombstone interface does not demonstrate a cryptographic ledger. It has no artefact digest, timestamp, actor, reason, previous-chain hash, deletion outcome, or signature/MAC.
- Negative retention values, invalid capacity values, duplicate records, overlapping backup/snapshot queries, concurrent pruning, legal holds, and in-progress artefacts are not guarded.
- `bytesReclaimed` trusts manifest metadata rather than actual deletion outcome/size and can misstate usable capacity.

### Required action

- Establish one immutable, validated policy source containing `400 GiB / 14 days / depth 4`; every API, scheduler, admission check, and health response must read it.
- Remove the fake policy `PUT` endpoint until durable validated updates exist.
- Add atomic capacity reservation before collection, including:
  - expected or maximum upload size;
  - encryption/temp-file overhead;
  - mandatory safety reserve;
  - concurrent reservations;
  - per-device or fleet allocation rules.
- Prune snapshots per operational identity/device and retain the newest four successfully validated recoverable snapshots—not merely four arbitrary rows.
- Make pruning idempotent and concurrency-safe.
- Use a store/ledger transaction or durable outbox. A practical lifecycle is `DELETE_INTENT → storage deletion → DELETE_COMPLETED`; failures remain visible and are never counted as pruned.
- A failed deletion must leave the artefact active and return a failure result.

## 3. Multi-vendor execution

### Check Point

#### Critical findings

- `BackupCapabilities` gates only the Gaia backup flow. The snapshot executor introduces `add snapshot`, snapshot status, snapshot fetch, and `delete snapshot` operations without a demonstrated capability/gate entry.
- The two disk-space parsers disagree:
  - Backup executor assumes the first integer is KiB.
  - Snapshot executor searches selected lines and assumes the first integer is MiB.
- Both parsers can select a percentage, filesystem size, or unrelated number. The snapshot comment “assume MB … or scale” is not safe vendor semantics.
- The claimed “at least 3x” snapshot precondition is not implemented. The executor merely compares against a caller-provided threshold.
- Snapshot completion is detected by broad substring matching and is not correlated with `snapshotName`. It may observe another operation or misread text such as “not completed”.
- Snapshot archive location is constructed as a hard-coded filesystem path rather than obtained from verified command output or a frozen vendor contract.
- There is no end-to-end snapshot digest verification before the remote snapshot is deleted.
- `Math.abs(jobId.hashCode())` is collision-prone; `Integer.MIN_VALUE` remains negative. It is unsuitable as a durable operation identity.
- Both executors hold one SSH session and a worker invocation throughout polling. The device operation is asynchronous, but the application workflow is not durable or restart-safe.
- In `BackupCapabilityExecutor`, interruption sets the interrupt flag but polling continues; subsequent sleeps may immediately interrupt and create a tight loop.
- Transport/poll failures are treated as “still in progress” until deadline, obscuring actual collection failure.
- A completed `ArtefactStore.finish()` followed by digest mismatch may leave a committed but invalid artefact without quarantine or removal.
- Device command output and archive names are embedded in result messages without sanitization.

#### Required action

- Freeze and sign off a separate snapshot command capability before enabling this executor.
- Use one vendor-proven disk-space parser with explicit columns, unit recognition, filesystem selection, overflow checks, and fail-closed fixtures.
- Implement the 3x requirement explicitly from a defined snapshot-size estimate or rename the policy honestly as a fixed local reserve.
- Use a validated UUID-derived safe operation token and correlate status to that token.
- Persist a job state machine: `PREFLIGHT → SUBMITTED → POLLING → FETCHING → VERIFIED → CLEANUP → COMPLETE`. Resume/reconcile after restart.
- Require device-side and received-byte digest agreement before deletion.
- Quarantine incomplete or mismatched local artefacts.
- Track orphaned remote archives and retry cleanup through a bounded reconciler.
- Treat interrupted polling and transport failures as typed outcomes immediately.

### Palo Alto Networks

#### Critical findings

- The API key is supplied through both parameters and `X-PAN-KEY`. Duplicating credentials increases exposure through request logging, tracing, and error reporting.
- Configuration retrieval failure is converted to an empty string and then evaluated as a real configuration. This can generate false deviation claims.
- `config action=show` is not persisted or identified as a distinct configuration artefact, so the stated support for configuration exports is incomplete.
- The streaming device-state copy has no byte limit. A device, proxy, or error response can consume vault capacity indefinitely.
- A completed stream is accepted without checking HTTP status, content type, PAN XML result status, or whether the body is an API error page.
- No explicit managed-device/Panorama target identity is visible. A request could export the manager rather than the intended firewall.
- Raw `apiKey` and raw XML are passed through application method boundaries rather than credential references and narrowly scoped parsing.
- There is no demonstrated restore-validation metadata, export type/version record, or integrity verification beyond whatever `ArtefactStore.finish()` may perform.

#### Required action

- Inject a credential reference through the transport and use exactly one approved authentication channel.
- Give configuration and device-state exports distinct artefact classes, schemas, retention rules, and provenance.
- Treat configuration collection failure as `COLLECTION_FAILED`; do not run a diff.
- Enforce a streamed byte limit and capacity reservation.
- Validate status, headers, PAN result envelope, target identity, and expected archive format before committing the artefact.
- For Panorama, require an explicit opaque managed-device target and verify the returned identity locally.

## 4. Diff engine and deviation tracking

### Critical findings

- This is regex matching, not an XML AST or semantic diff.
- `PAN_RULE_TAG` matches every `<entry>` element regardless of its XML path. Objects, interfaces, rules, profiles, and unrelated entries are conflated.
- Modifications inside an existing named PAN rule are invisible because only entry names are compared.
- Routing and HA changes are detected only when the entire container appears or disappears.
- `PAN_INTERFACE_TAG` and `CP_HA_LINE` are unused.
- Check Point maps overwrite multiple statements sharing an entity key, losing information.
- Check Point access rules, NAT, BGP/OSPF detail, cluster changes, and many administrator changes are not actually covered as documented.
- Unknown vendors fall back to a keyword heuristic instead of returning `UNSUPPORTED`.
- Missing current configuration becomes an empty configuration rather than `NOT_EVALUABLE`.
- Secret masking is absent. `diffMaps()` deliberately copies raw before/after values into output.
- The summary “bit-for-bit identical” is inaccurate because the comparison trims surrounding whitespace.
- Generic substring matching produces false positives—for example, any occurrence of `ha` can classify a line as major.

### Required action

- Parse PAN XML with a hardened XML parser: DTD and external entities disabled, bounded depth/size, canonical field extraction.
- Compare allowlisted semantic paths and emit only masked field identifiers and counts.
- Represent Check Point commands as multi-valued normalized records; never overwrite repeated statements.
- Return `COLLECTION_FAILED`, `NOT_EVALUABLE`, or `UNSUPPORTED` when appropriate.
- Never place raw before/after values, IP addresses, usernames, secrets, or topology identifiers in `DeviationOutcome`.
- Until those semantics are proven, describe this component as an advisory heuristic and do not trigger authoritative alerts from it.

## 5. API security and file traversal

### Findings

- The shown listing endpoints do not expose storage paths, which is correct.
- Opaque UUID enforcement is not demonstrated: artefact and device identifiers remain unvalidated `String` values.
- No download endpoint or download audit path is present, so the audited, fail-closed download requirement is unimplemented in the supplied code.
- The controller does not itself demonstrate device-level or fleet-level authorization.
- `type` is caller-controlled free text rather than a closed enum.
- Policy input is untyped and unvalidated.
- Operator reason, nonce, and identifiers have no visible length or format limits.
- `toSummaryBody()` can fail if the digest is null.
- Returning raw device IDs conflicts with masked AIView presentation unless the repository guarantees they are already pseudonymized.

### Required action

- Parse artefact IDs as UUIDs at the API boundary and resolve them through the repository only. Never accept or derive paths from request data.
- Implement downloads as an authorized store stream by UUID:
  - fail if audit storage is unavailable;
  - record actor, reason, artefact UUID, decision, start, completion/failure, and byte count;
  - never expose paths or decryption material;
  - sanitize `Content-Disposition`;
  - abort on authorization, integrity, or audit failure.
- Replace `type` and policy maps with closed, validated request records.
- Enforce resource-scoped authorization for both device and fleet listings.
- Return only masked device presentation identities.

## 6. Daemon lifecycle and robustness

### Critical findings

- The daemon duplicates controller policy and diff functionality, creating policy drift and an unnecessary second security surface. The simplest safe solution is to remove it unless a separate process boundary is contractually required.
- It binds to all interfaces by default.
- The diff endpoint has no demonstrated authentication or authorization.
- `readAllBytes()` permits unbounded request-body allocation.
- Virtual threads do not bound concurrent memory, parser, socket, or vault consumption.
- The virtual-thread executor is not retained or closed during shutdown.
- Health reports static `UP`, capacity, retention, and vault path without checking keys, ledger, storage availability, or headroom.
- Liveness and readiness are conflated.
- `/healthz` exposes an internal vault filesystem path.
- Raw exception messages are returned to clients.
- Graceful in-flight shutdown, request deadlines, media-type validation, and exact-path handling are absent.

### Required action

- Prefer deleting this daemon and using the existing authenticated service surface.
- If it must remain:
  - bind to an explicitly configured internal address;
  - require service authentication/mTLS;
  - cap body size before allocation;
  - limit concurrency and apply request deadlines;
  - own and close the executor;
  - separate `/livez` from a real `/readyz`;
  - do not expose filesystem paths;
  - return stable error codes, not exception messages;
  - source policy from the same authoritative policy object.

## Minimum approval gates

Approval should require all of the following:

1. One enforced `400 GiB / 14 days / depth 4` policy source.
2. Store-owned UUID deletion with transactional cryptographic tombstones.
3. Vault capacity reservation and headroom enforcement.
4. Signed-off Check Point snapshot command contract and deterministic disk parser.
5. Snapshot integrity verification before device cleanup.
6. Bounded, authenticated PAN exports with explicit target identity.
7. No raw configuration or secret-bearing diff details in HTTP responses.
8. UUID-only, authorized, audited, fail-closed downloads.
9. Removal or security hardening of the duplicate daemon.
10. Failure-injection tests covering deletion/tombstone ordering, process restart during polling, digest mismatch, vault exhaustion, oversized PAN responses, traversal attempts, and audit-sink failure.