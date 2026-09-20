# Security & Second-Opinion Review — neXus Backup & Recovery Engine

**Reviewer:** Fable (Enterprise Security Architect)
**Basis:** Supplied code only. `ArtefactStore`, `DeviceTransport`, `BackupCollectService`, `GateChainInterceptor`, `BackupReadPlan`, the retention SQL, key management, and NetworkPolicy are **not supplied** and are treated as `UNKNOWN` — not as satisfied.

## VERDICT: [REJECTED]

I concur with Astra's rejection. I found no factual error in their review. However, their review understates the severity in four places and misses six issues, two of which are credential-exposure paths and one of which means a shipped endpoint cannot ever have executed successfully.

---

## Executive summary for the Product Owner

The engine does not currently deliver a *recovery* capability. It delivers a collection pipeline with five silent-failure chains:

| # | Chain | Consequence |
|---|---|---|
| A | Unproven disk parser → ungated `add snapshot` on a production gateway | **Device impact.** A parser that reads *total* rather than *free* space can trigger an LVM snapshot on a nearly-full gateway. |
| B | PAN export committed without status/format validation → retention prunes the older good artefacts | **Total silent loss of recoverability.** Digests are valid; the bytes are an error page. |
| C | `show backup status` false-SUCCEEDED → fetch partial archive → digest matches (both sides partial) → **remote archive deleted** | The only good copy is destroyed to make room for an unrestorable one. |
| D | Retention prunes every artefact older than 14 days regardless of whether a newer one exists | A device that fails backup for 15 days is left with **zero** recovery points, by design, silently. |
| E | Tombstone written on failed deletion | The cryptographic ledger asserts destruction of artefacts that still exist in plaintext-recoverable form in the vault. |

Additionally: **no restore path, and no restore validation, exists anywhere in the supplied code.** Per `AGENTS.md` ("Automated validation != real-environment validation"), a backup system whose restore has never been demonstrated cannot progress past `PARTIAL`. Requirement 4 ("audited downloads with fail-closed semantics") is **not implemented at all** — there is no download endpoint in the supplied set.

---

## 1. Assessment of Astra's review

### Where I strongly agree (no further comment needed)

Policy divergence (14/4 vs 30/2 vs 30/2), unenforced `maxStorageBudgetBytes`, the fake `PUT` returning `UPDATED`, failed-deletion-counted-as-pruned, missing tombstone transactionality, two disagreeing disk parsers, uncorrelated status polling, hard-coded snapshot path, `Math.abs(hashCode())`, duplicated PAN credential, unbounded PAN stream, regex-instead-of-AST, unused `PAN_INTERFACE_TAG`/`CP_HA_LINE`, CP map key overwrite, wildcard bind, unbounded `readAllBytes()`, unretained virtual-thread executor, static `UP` health, `/healthz` path disclosure, raw exception messages. All verified correct.

### Where Astra **understates** severity

- **Gate bypass is broader than the CP snapshot.** `BackupCapabilities.all()` registers exactly one capability (`CP_GAIA_BACKUP_LOCAL`). `CheckPointSnapshotExecutor` **and** `PaloAltoBackupExecutor` both contact devices with no capability entry, so `Capability#executionEligible()` cannot gate either. `add snapshot` / `delete snapshot` are device-**mutating** operations; PAN `type=export` likewise. This is not "a missing registry row" — it is an unreviewed path around the network-device command gate and `utils/action_taxonomy`, i.e. a constitutional violation, not hardening.
- **`type` as free text is an architectural-invariant issue, not an input-validation issue.** `BackupController.collect()` accepts `type` as an unvalidated `String` and passes it to `requestCollect(...)`. The test-enforced invariant is *"typed intent against a closed module-level registry."* An open string selecting a device-contacting plan is exactly what that invariant exists to prevent. If `type="snapshot"` reaches the snapshot executor, a browser request drives an ungated device mutation.
- **The retention/UI divergence is an operator-safety issue.** `GET /api/v2/backups/policies` reports `backup_retention_days: 30`. Operators will plan recovery windows against a 30-day promise the engine does not keep. Under the authority hierarchy this is a contradiction to be *reported*, not reconciled in code.
- **The digest check does not do what the comment claims.** `BackupCapabilityExecutor` computes the device-side digest *after* fetch, over the same file it fetched. That proves **transport integrity only**. It cannot detect that the archive was still being written when the status parser falsely reported success. And the code deletes the remote archive on that basis.

### Where I disagree or would refine

- **Deletion is not primarily a path-traversal problem — it is a key-destruction problem.** Astra's fix (`artefactStore.delete(ArtefactId)` plus a `DELETE_INTENT → delete → DELETE_COMPLETED` outbox) is correct but leaves a window in which ciphertext is still decryptable. The stronger primitive for an encrypted vault is **crypto-shredding**: destroy the artefact's DEK first (a single atomic ledger-transacted row), then unlink best-effort. Key destruction is atomic, cheap, verifiable, and makes the failure mode safe — if the unlink later fails, the artefact is already unrecoverable, so the tombstone is *truthful*. I recommend this over the outbox as the primary design; the outbox becomes a garbage-collection detail rather than a correctness mechanism.
- **The diff API should be deleted, not restricted.** Astra proposes emitting "category, counts, opaque field paths." I would not keep an endpoint that *accepts* raw configurations under any auth model — see §2. The correct design computes a **canonical per-path digest projection at collection time** and diffs projections. Two plaintext configurations then never coexist anywhere: not in the worker heap, not on a socket, not in a request body. This is strictly better than hardening the current shape and eliminates the decrypt affordance discussed in §2.
- **Hardened XML parsing introduces new risk.** Astra's "parse with a hardened XML parser" is right, but today's regex approach has one accidental virtue: no XXE. Moving to DOM/SAX over a semi-trusted device response requires `disallow-doctype-decl=true`, external entities off, `FEATURE_SECURE_PROCESSING`, and explicit depth/element/attribute caps — or, preferably, StAX streaming that only ever emits digests. Do not let this land as an unqualified "use a parser."
- **"Atomic capacity reservation" presumes knowledge that does not exist.** A Gaia snapshot's size is not knowable pre-collection. The implementable control is: (i) a hard per-artefact byte cap enforced *at the sink*, aborting mid-stream; (ii) a vault high-water mark that refuses admission below a configured headroom; (iii) per-device-class allocation. Frame it that way or the team will build an estimator that lies.
- **Scope the durable job state machine.** Full resumable polling is phase-2. The v1 minimum is narrower and must not slip: **durably record `(device, remote artefact name, submitted_at)` before submit**, so a bounded reconciler can clean up orphans after a worker restart. For snapshots this is mandatory (see §5); for backups it can follow.
- **I defend `sftpGetStepNotApplicable()`.** The `gateNotApplicable = true` justification holds *because* `ARCHIVE_NAME = ([\w][\w.\-]*\.tgz)` cannot yield a path separator and cannot begin with `-`. That is sound. But the exemption is silently coupled to that regex — add a test that pins the regex and fails if it is ever widened.

### What Astra missed

Six items, in §§2–6 below: the cleartext transport of CLASS-sensitive configuration; the Check Point admin-line regex capturing password material; the PAN API key reaching exception messages; the null actor fingerprint; deviation-alert suppression via induced failure; and the `Ui2BackupServer` diff handler being non-functional at runtime.

---

## 2. Secret containment & plane separation

**[BLOCKER] Plaintext firewall configuration transits an unauthenticated cleartext HTTP endpoint.**
`Ui2BackupServer` uses `com.sun.net.httpserver.HttpServer` (not `HttpsServer`), binds `0.0.0.0:8086`, and `/api/v1/backup/diff` accepts `previous_config` and `current_config` as raw strings. Any caller of that endpoint ships complete Gaia/PAN-OS configurations in the clear across the cluster network. Any pod, sidecar, service mesh tap, or namespace-local observer captures them. This is not "an endpoint missing auth" — it is a CLASS-sensitive data path that defeats the encrypted, egress-denied recovery plane by routing its contents around it. **Delete the endpoint.**

**[BLOCKER] `CP_USER_LINE` captures credential material into API responses.**
`CP_USER_LINE = ^(?:add|set)\s+user\s+(\S+)\s+(.*)$` captures *everything after the username* into `group(2)`. Gaia user statements carry `password-hash $6$…` forms. `diffMaps()` then writes that value verbatim:
```java
diffs.add(entity + " '" + key + "' modified: [" + prev.get(key) + "] -> [" + entry.getValue() + "]");
```
Result: **password hashes, verbatim, in `DeviationOutcome.details`**, which is serialized into the `/api/v1/backup/diff` response body and, via `PanBackupResult.deviationOutcome`, into whatever persists deviation state. Same mechanism leaks interface IPs (`CP_INTERFACE_LINE`) and next-hop topology (`CP_ROUTE_LINE`).

**[HIGH] The generic fallback preferentially selects secret-bearing lines.**
`evaluateGeneric()` emits up to 20 raw added and 20 raw removed lines. `isMajorKeyword()` matches on `"password"`. The heuristic therefore *selects for* lines containing passwords and then copies them into the output map. Unknown vendors must return `UNSUPPORTED`, per the UNKNOWN/fail-closed law — not a keyword heuristic.

**[BLOCKER] The PAN API key reaches exception messages.**
`PaloAltoBackupExecutor` places `apiKey` in the query map (`"key", apiKey`) *and* the `X-PAN-KEY` header. The failure path returns `"Device state export failed: " + e.getMessage()`. HTTP client exceptions routinely embed the request URI — which contains `key=<apikey>`. Combine with the redundancy: pick **one** channel (the header), and inject a **credential reference** through the transport as the Check Point path already does via `ConnectSpec(credentialRef, …)`. The repository already has the correct pattern; PAN deviates from it.

**[HIGH] Connect/exec failure reasons carry identity material into result strings.**
`describeConnect()` propagates `AuthenticationFailed.reason()` (may contain principals) and `HostKeyRejected.reason()` (may contain host-key fingerprints and addresses). `CheckPointSnapshotExecutor` is worse: `new BackupResult.ConnectFailed("SSH connection failed to " + request.connectionTarget())` puts the **management address directly into an operator-visible result**. `CredentialUnresolvable(e.getMessage())` propagates raw credential-subsystem exception text. All four violate the sensitive-identity reporting law. Return typed codes; log detail only to a sanitized sink.

**[HIGH] The diff feature requires a decrypt affordance inside the worker.**
`executeBackup(..., String previousConfigXml)` means *something* supplies the previous plaintext configuration. If that is obtained by decrypting a prior vault artefact, the worker holds decryption capability over the recovery plane — the exact affordance plane separation exists to deny, and it makes worker compromise equal vault compromise. **Architectural requirement: the worker must be encrypt-only.** With KMS envelope encryption, grant the worker `GenerateDataKey`/`Encrypt` and *not* `Decrypt`; reserve `Decrypt` to the audited download service under dual control. Then store a per-path digest projection at collection time so the diff never needs plaintext at all.

**[MEDIUM] `System.err.println` in `RetentionPruningService` prints the full vault path**, which — if the store composes paths from `deviceId` — writes unmasked device identity to pod logs, contrary to the AIView masking law.

**[MEDIUM] Encryption is asserted only in Javadoc.** `CheckPointSnapshotExecutor`'s Javadoc claims "AES-256-GCM envelope encryption"; the code calls `artefactStore.open(deviceId, jobId, "check_point", false)`. A comment is not a control. Also replace that trailing `boolean` with a named type — `open(..., false)` is unreadable at a security boundary.

---

## 3. Path traversal & identifier safety

**No exploitable traversal found in the supplied code**, but the containment is accidental rather than enforced:

- `ARCHIVE_NAME` excludes `/` and cannot start with `-`, so neither directory traversal nor argument injection is reachable through the Check Point archive name. **This is currently correct** — and the SFTP gate exemption depends on it. Pin it with a test.
- `snapshotName = "nxs_snap_" + Math.abs(jobId.hashCode())` is numeric and injection-safe. Its defects are collision and determinism, not traversal (see §6).
- **[HIGH] `RetentionPruningService.deleteStorageFile()` performs `Files.deleteIfExists(Path.of(dbString))` with no confinement check.** Today the string is DB-sourced, so this is defence-in-depth rather than a live vulnerability — but there is no assertion anywhere that the path resolves under the vault root, and one write-side change makes it live. Per §1, the fix is store-owned deletion keyed by `ArtefactId`, with crypto-shred first.
- **[MEDIUM] No UUID validation at the API boundary.** `deviceId`, `artefact_id`, `nonce`, `reason`, and `type` are all unvalidated `String`s with no length or format bound. Parse artefact/device identifiers as `UUID` at the controller edge and resolve only through the repository.
- **[MEDIUM] Likely hostname propagation via the Gaia archive name.** Gaia's auto-generated backup filename conventionally embeds the device hostname. That name flows into `BackupResult.CleanupFailed(metadata, name, …)`, into operator-visible messages, and possibly into the vault filename. Verify against a real-environment sample; if confirmed, mask before the name leaves the executor. Marked `UNKNOWN` pending evidence.
- **[LOW] `digest_prefix` exposes 48 bits of a *plaintext* digest.** For an attacker able to generate candidate configurations, that is sufficient to confirm a guess. Prefer a digest over ciphertext, or an HMAC-keyed display token.

---

## 4. Audit logging & fail-closed guarantees

**[BLOCKER] The actor fingerprint may be null and nothing fails closed.**
```java
String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
```
If the interceptor is not applied to `/api/v2/backups/{deviceId}/run` — a second, differently-shaped route that may not match the interceptor's path patterns — this returns `null`, and `requestCollect(deviceId, null, …)` proceeds to a device-contacting operation with no attributable actor. An operation that cannot be attributed must be refused, not attributed to nothing. Hard-fail on null.

**[HIGH] The audit reason is fabricated when absent.** `reason == null ? "operator requested backup collection" : reason`. An operator reason is an audit field; auto-filling it manufactures audit content that no human asserted. Reject the request instead.

**[HIGH] `nonce` is optional**, so replay protection is opt-in. A replayed `POST …/run` re-triggers device contact.

**[BLOCKER] The tombstone ledger records destruction that did not occur.** In `prune()`, `recordTombstone(...)`, `backupsPruned++`, and `prunedIds.add(...)` all execute unconditionally, while `deleteStorageFile()` may have returned `false`. The append-only ledger — the audit authority for destruction — therefore contains false entries, while the artefact remains readable in the vault. For a retention control with compliance meaning, a false destruction record is worse than a missing one.

**[HIGH] The tombstone is a name, not a mechanism.** `recordTombstone(String ledgerId, String artefactId, String retentionTier)` has no artefact digest, timestamp, actor, reason, policy version, deletion outcome, previous-entry hash, or MAC/signature. Nothing about it is cryptographic or chain-verifiable. Either implement the chain or rename the interface honestly.

**[HIGH] No exception handling around `recordTombstone`.** A throw mid-loop aborts after files are already deleted, producing deletions with no records and no summary. Partial-failure semantics are undefined.

**[HIGH] Concurrent pruning produces duplicate ledger entries.** `prune()` takes no lock. `deleteIfExists` is idempotent; the ledger is not. Two concurrent runs write two tombstones for one artefact, breaking append-only chain integrity.

**Requirement 4 is unimplemented.** There is no download endpoint, no download audit record, and no fail-closed-on-audit-sink-unavailable behaviour anywhere in the supplied code. When it is built: authorize → open audit record → stream by UUID → close record with byte count and outcome; abort if the audit sink is unavailable *before* any byte is served; never expose paths or key material; sanitize `Content-Disposition`.

---

## 5. Denial of service & vault exhaustion

**[BLOCKER] The 400 GiB budget is decorative.** `maxStorageBudgetBytes` is read nowhere. There is no admission check, no headroom reserve, and no accounting of temp/encryption overhead.

**[BLOCKER] The capacity directives are mutually inconsistent at fleet scale.** With `MAX_SNAPSHOT_BYTES = 25 GiB` and depth 4, one device's worst case is **100 GiB — a quarter of the entire vault**. Four such devices exhaust it before a single daily backup lands. At a more typical 5–10 GiB snapshot, depth 4 is 20–40 GiB/device, plus 14 daily backups, giving roughly **6–20 Check Point devices total**. The Product Owner's three directives — 400 GiB, depth 4, "snapshot quantity will be high" — cannot all hold for a fleet of meaningful size. This requires a PO decision, not a code fix (see §8). Real artefact sizes are `UNKNOWN` and must be measured before the budget is ratified.

**[HIGH] `PaloAltoBackupExecutor.copyStream()` has no byte limit.** A hung device, a proxy, or a chunked error response can write until the volume is full. One unbounded stream can consume the entire vault and take down every other device's recovery path. Enforce the cap at the sink with mid-stream abort.

**[HIGH] Unbounded request body plus memory amplification on an unauthenticated endpoint.** `is.readAllBytes()` in `DiffHandler`, followed by `String` configs, regex matchers, and `evaluateGeneric()`'s `.lines().toList()` materialization, gives several multiples of body size in heap per request. Virtual threads remove the natural concurrency bound that a fixed pool would have provided. A handful of concurrent large POSTs OOMs the worker. Cap the body before allocation, bound concurrency, and set request deadlines.

**[HIGH] Snapshot orphans accumulate on the gateway.** On the `OutcomeUnknown` deadline path, `CheckPointSnapshotExecutor` returns **without attempting cleanup**, and the deterministic name means the next run of the same job collides with its own orphan. With a weekly cadence and "high snapshot quantity," orphaned snapshots progressively consume the device's snapshot volume — a **device-availability** risk, not merely vault waste. This is why the durable `(device, remote name)` record before submit is a v1 blocker for snapshots specifically.

**[MEDIUM] Concurrent transient footprint.** If the store stages to a temp file on the vault volume, N concurrent collections need up to 2×N× the per-artefact cap in transient space, none of it budgeted.

**[MEDIUM] No poll jitter or backoff.** Fleet-wide scheduled polling at a fixed interval synchronizes load against the management plane. Add jitter before any concurrency increase, per the interaction-safety rule.

---

## 6. Check Point & PAN native security

### Check Point

- **[BLOCKER] `CheckPointSnapshotExecutor.parseFreeSpaceBytes()` is unsafe for a device-mutating precondition.** It selects lines containing `free`/`available`/`/var/log`, takes the **first integer in the line** — which in a `df`-style row is the *total*, not the available column — and multiplies by 1 MiB on the strength of a comment reading `// assume MB if in show diskspace, or scale`. A comment that says "assume … or scale" is not vendor semantics; it is an admission that the semantics are unproven. The consequence is a snapshot initiated on a nearly-full gateway. **Per the vendor-semantics law this must be `UNKNOWN` until proven against real output, and the executor must not run.**
- **[HIGH] `BackupCapabilityExecutor.parseFreeSpaceBytes()` is column-agnostic too.** `FIRST_INTEGER` over the whole output takes the first integer *anywhere*. Common `df`-style headers contain digits (e.g. a `1K-blocks` column header), in which case this yields 1 KiB and the backup never runs; a different header shape yields the total and it always passes. Either way it is non-deterministic. One parser, explicit column selection, explicit unit recognition, real-environment fixtures, fail-closed on ambiguity.
- **[BLOCKER] `classifyStatus()` has a false-success path.** `"fail"` is tested first, then `"completed"`. Output reading *"backup not completed"* contains no `fail` and does contain `completed` → **SUCCEEDED**. The executor then fetches a partial archive, computes matching digests on both sides (both over the same partial file), and **deletes the remote archive**. This is chain C in the summary. Status must be correlated to the archive name from the submit output and matched against an enumerated set of vendor-proven terminal tokens — never substring-scanned.
- **[HIGH] Transport failure is conflated with device state.** `exec()` maps `TimedOut → ExecOutcome("", false)` (→ `IN_PROGRESS` forever) and `ChannelFailed → ExecOutcome(reason, false)` — where a reason containing "error" classifies as a **device backup failure**. Both must be typed outcomes surfaced immediately, per the UNKNOWN/fail-closed law.
- **[HIGH] Interrupted polling busy-loops.** `sleep()` catches `InterruptedException`, re-sets the flag, and returns; the loop continues; the next `Thread.sleep` throws immediately. Result is a tight loop until the deadline. (`CheckPointSnapshotExecutor` handles this correctly — port that behaviour.)
- **[HIGH] The snapshot archive path is asserted, not discovered.** `"/var/CPsnapshot/snapshots/" + snapshotName + ".tgz"` presumes that `add snapshot` produces a fetchable tarball at that location. On Gaia, snapshot creation and snapshot *export* are, to my understanding, distinct operations — meaning this file may not exist at all and the fetch fails every time. I will not assert vendor behaviour from model knowledge: mark `UNKNOWN`, resolve against official documentation, and freeze the contract before implementation.
- **[HIGH] No digest verification before snapshot deletion.** The backup executor gets this right; the snapshot executor deletes on a bare exit status. Inconsistent, and the weaker of the two is the one handling the larger, harder-to-reacquire artefact.
- **[MEDIUM] Session lifetime.** One Expert-shell SSH session is held across preflight, unbounded polling, and a 30-minute fetch. Acceptable for v1 given the gate doc's session-reuse column, but it makes worker restart equal total run loss and holds a privileged shell open for up to an hour.
- **[MEDIUM] `DigestMismatch` leaves a committed invalid artefact.** `finish()` has already been called. Quarantine it; do not leave it listable or prunable-against.
- **Credit where due:** refusing to guess the archive name, fail-closed on unparseable disk space, digest-before-delete, single bounded delete retry, typed terminal outcomes, `credentialRef` rather than a raw credential, and the `MAX_ARCHIVE_BYTES` cap are all correct and should be preserved and propagated to the snapshot path.

### Palo Alto

- **[BLOCKER] HTTP 200 does not mean success in the PAN-OS XML API.** Errors are returned as `200` with `<response status="error" …>`. `configResult.httpStatus() == 200` therefore accepts an error envelope as the running configuration, and the streaming export has **no status check whatsoever** — `XmlApiStreamOutcome.Completed` is the only condition. An error page is streamed into the vault, digested, and committed as a device-state backup. This is chain B.
- **[BLOCKER] Failure becomes a false MAJOR alert that exfiltrates rule names.** `currentConfigXml = ""` on any non-200, then `evaluate("palo_alto", previousConfigXml, "")` → every `<entry name>` appears in `rules_removed`. Any 401/503/timeout produces a full dump of PAN entry names — which include security-rule, address-object, and `mgt-config` user names, routinely encoding customer, partner, application, and topology identity — into a deviation record. Configuration collection failure must be `COLLECTION_FAILED` and **no diff may run**.
- **[HIGH] Deviation alerting fails open in both directions.** Blank *previous* → `FIRST_RUN` → no alert. So anyone able to induce a previous-config read failure **suppresses deviation alerting entirely** while the system reports a benign baseline. Blank *current* → false MAJOR. Neither is fail-closed; a security control with an inducible mute is not a control. Distinguish "no prior artefact" from "prior artefact unreadable" and return `NOT_EVALUABLE` for the latter.
- **[HIGH] No explicit target identity.** Without an explicit managed-device target and local verification of the returned identity, a Panorama request exports the *manager* while the operator believes they hold firewall backups. Treat the target identity as opaque, per the identity law.
- **[MEDIUM] `config action=show` is never persisted as an artefact**, so "Palo Alto configuration exports" (PO requirement 3) is unmet — only device-state is stored, and only unvalidated.
- **[MEDIUM] No export-format or version validation.** Nothing confirms the bytes are a PAN device-state bundle of a known, restorable format.

### `Ui2BackupServer` — additional

**[HIGH] `DiffHandler` cannot work at runtime.** `mapper.readValue(bodyBytes, Map.of().getClass())` targets `java.util.ImmutableCollections$MapN`, which Jackson cannot construct — it has no default constructor and no creator. Every request should therefore fall into the `catch` and return `400 {"error":"Invalid request","message":"<Jackson internals>"}`. Verify against the pinned Jackson version, but if it holds it means **this endpoint has never been executed by a test**, which is a process finding as much as a code finding. It also leaks internal class names to unauthenticated clients.

---

## 7. Contradictions requiring a Product Owner decision

Per the authority hierarchy I am reporting these rather than reconciling them:

1. **400 GiB vs depth 4 vs "snapshot quantity will be high."** Not simultaneously satisfiable beyond roughly 6–20 Check Point devices (sizes `UNKNOWN`, must be measured). Options, in my order of preference: (a) tier snapshot depth by device criticality — depth 4 for a named critical set, depth 1–2 elsewhere; (b) raise the vault budget after measuring real artefact sizes; (c) introduce content-addressed deduplication/compression before ratifying any budget.
2. **14 days (`PruningPolicy.DEFAULT`) vs 30 days (controller, daemon, and `RetentionPruningService`'s own Javadoc).** The PO directive says 14. Confirm 14 and make it the single enforced source.
3. **"Write-once, encrypted, egress-denied" vs a worker that must decrypt to diff and must egress to collect.** The claim needs precise scoping: the *vault volume* is egress-denied and mounted only by the storage service; the *collector* egresses to an allowlist of device management addresses; the worker holds no decrypt capability. As written, the claim is unfalsifiable and rests on comments.

---

## 8. Revised minimum approval gates

I adopt Astra's ten gates and add five. Reordered by risk:

1. **No device-mutating operation without a `SIGNED_OFF` command-gate entry and a registered capability** — covers `add snapshot`, `delete snapshot`, and both PAN API operations.
2. **One vendor-proven disk parser**, explicit columns and units, real-environment fixtures, fail-closed on ambiguity — gating any snapshot execution.
3. **Status correlation to the submitted artefact name** over an enumerated terminal-token set, with typed transport-failure outcomes. No substring classification.
4. **Archive validity — not just transport digest — verified before remote deletion**; quarantine on mismatch.
5. **The worker holds no decrypt capability.** Encrypt-only key grant; decrypt confined to the audited download service.
6. **No raw configuration on any network hop, in any request or response body, in any log, or in any `DeviationOutcome`.** Diff over stored per-path digest projections only. Delete `/api/v1/backup/diff`.
7. **One enforced `400 GiB / 14 days / depth 4` policy object**, with an architecture test that fails if `30`, `2`, or `"400Gi"` appears as a literal anywhere else.
8. **Store-owned deletion keyed by `ArtefactId`, crypto-shred first**, transacted with a real chained, MAC'd tombstone. A failed deletion leaves the artefact active and returns failure.
9. **Retention never prunes the last valid recoverable artefact for a device**, regardless of age; emit a `RECOVERY_POINT_AT_RISK` signal instead.
10. **Per-artefact byte cap at the sink plus a vault headroom admission check**; no unbounded stream anywhere.
11. **Non-null actor fingerprint and mandatory operator reason and nonce, enforced fail-closed** at the API boundary; `type` a closed validated enum; identifiers parsed as `UUID`.
12. **Audited, fail-closed, UUID-only download** implemented and tested — currently absent.
13. **Durable `(device, remote artefact name, submitted_at)` record before submit**, plus a bounded orphan reconciler for snapshots.
14. **Daemon removed** (preferred) or bound to an explicit internal address behind mTLS with bounded bodies and concurrency.
15. **A demonstrated real-environment restore** for each vendor and artefact class. Until then the build cannot exceed `PARTIAL`; `REAL_ENV_VALIDATED` is not reachable from collection evidence alone.

**Failure-injection tests required before re-review:** deletion succeeds / tombstone fails, and the reverse; worker restart during polling and during fetch; digest match over a partial archive; vault at 99% during a 25 GiB snapshot; PAN returns `200` with an error envelope; PAN returns an unbounded stream; previous-config read failure (must not emit `FIRST_RUN`); concurrent prune runs; null actor fingerprint; `type` outside the enum; audit sink unavailable during download; `ARCHIVE_NAME` regex widening (must fail the gate-exemption test).

---

## 9. Recommended sequencing for the development team

1. **Stop-ship the snapshot path.** Disable `CheckPointSnapshotExecutor` until gates 1–4 are met. It is the only code here that can affect a production gateway's disk.
2. **Excise the secret paths.** Delete the daemon; delete raw values from `DeviationOutcome`; move PAN to `credentialRef` with header-only auth; replace all exception-message-bearing result strings with typed codes. These are small, independent, and remove the highest-consequence exposure.
3. **Make failure visible.** `COLLECTION_FAILED` / `NOT_EVALUABLE` / `UNSUPPORTED` throughout the diff engine; PAN status and envelope validation; status-token correlation in both CP executors.
4. **Make destruction truthful.** Store-owned crypto-shred deletion, chained tombstone, last-artefact protection, single policy object.
5. **Then** build the audited download and the restore validation that make this a *recovery* engine rather than a collection pipeline.

Astra's rejection was correct and their analysis is sound. The disagreements above are about mechanism and sequencing, not about whether this ships — it does not, in its current form.