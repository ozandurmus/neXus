# Failover reciprocal evidence — PR 8

Status: DRAFT — implementation evidence note, not a new contract or command approval.
Baseline inspected: `7b5b8361`. Local source and synthetic fixtures only; no device contact.
Authority: the PO's PR 8 brief and the existing CP/PAN execution contracts.

## Evidence already available

Paths below are relative to `ui2/worker/src/` unless stated otherwise.

| Evidence | Producer / parser | Existing fixture or test |
| --- | --- | --- |
| CP endpoint provenance | `main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java`: inventory member UUID -> endpoint record -> `ConnectionTarget` -> authenticated session. `Ui2WorkerMain` injects `strictFailoverSsh`, with TOFU disabled. `transport/ssh/PersistedManagementEndpointTrustResolver.java` resolves trust for the exact endpoint and algorithm; `SshExecTransport.java` verifies the key before authentication. | `test/java/com/securityexpert/nexus/ui2/worker/transport/ssh/PersistedManagementEndpointTrustResolverTest.java`; failover executor's scripted sessions. |
| CP enrolled identity | `DeviceRepository.findConfirmFacts(...).recordedIdentityPrimary()` and `TransportSession.presentedIdentity()` already exist. `transport/ssh/SshTransportSession.java` exposes the accepted host-key fingerprint; `inventory/InventoryCapabilityExecutor.java` already uses this identity for inventory verification. PR 8 compares these in the failover worker too, before the first device command. | `test/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutorTest.java`: synthetic recorded/presented keys, missing and mismatched identity. |
| CP local and peer roles | `failover/CpFailoverChecks.java#state` parses the opaque member ID, `(local)` marker, and state of every row from the existing `cphaprob stat`. Names and addresses are not identity joins. `inventory/cp/CheckPointHaStateParser.java#clusterModeOf` supplies the canonical mode; failover additionally rejects ambiguous/unsupported labels. | `test/resources/fixtures/inventory/cp/cphaprob_stat_r8120_ha.txt`, `cphaprob_stat_vsx_vs.txt`, `cphaprob_stat_vsls.txt`, `failover_stat_ha.txt`, `failover_stat_vsx_vs.txt`, `failover_stat_vsx_chassis.txt`, `readiness_measured_{gateway,vs0,vs1}.json`. |
| PAN endpoint identity | `failover/PanFailoverJobExecutor.java#peer/#read`: direct inventory endpoint, recorded primary serial, and exact local serial equality on every HA read. This is the existing XML API identity path, not SSH host-key verification. | `test/java/com/securityexpert/nexus/ui2/worker/failover/PanFailoverJobExecutorTest.java`: scripted direct endpoint and enrolled serial assertions. |
| PAN local/peer serials and roles | Existing `<show><high-availability><state/></high-availability></show>` returns `response/result/group/{mode,local-info/{serial-num,state},peer-info/{serial-num,state,conn-status}}`. `failover/PanFailoverChecks.java#parse` previously discarded `peer-info/state`; PR 8 retains it. `inventory/pan/PaloAltoHaStateParser.java` is the inventory parser, not the readiness admission function. | Inline XML fixtures in `test/java/com/securityexpert/nexus/ui2/worker/failover/PanFailoverChecksTest.java#xml` and `PanFailoverJobExecutorTest.java#Script` already contain both role claims. These are synthetic parser fixtures, not real-environment semantic validation. |

## Binding and admission

CP has no proven inventory-identity field in its state-table row. The binding is
transport provenance: the command runs on the session whose presented identity
exactly matches that member's recorded identity, after endpoint trust verification.
Its unique `(local)` row is that observer's claim about itself. No hostname, address,
ordinal, or numeric conversion creates that binding. Duplicate member records,
duplicate presented identities, missing recorded/presented identities, and endpoint
ownership mismatches block. The first local opaque ID is held on that session for
the run; subsequent ID changes cannot silently rebind the observer.

For each fresh CP pair of reads, require two complete tables, different local IDs,
identical opaque member sets, and agreement of A's peer role with B's own role and
B's peer role with A's own role. Pre-checks require exactly ACTIVE/STANDBY. The
existing post-switch and return polling require their exact action-specific role
pairs and the same reciprocal comparison. PAN retains the existing deployment TLS policy; serial equality adds no new TLS
authentication guarantee. PAN requires distinct local serials,
exact cross-serial equality and both reciprocal role claims, including polling.
Missing/ambiguous claims are UNKNOWN; explicit disagreements FAIL. Duplicate XML
fields are not accepted by taking the first value.

Only the reported CP High Availability mode is admitted. VSLS/load-sharing,
unknown, and other modes are UNSUPPORTED for admission, even when rows show
100%/0% assigned load or ACTIVE/STANDBY. BACKUP is no longer converted to STANDBY.
This deliberately narrows CP contract amendment 14's older VSLS-label acceptance,
as explicitly directed by the PR 8 brief. Only PAN active-passive is admitted.
This classifies reported mode; it does not determine actual load distribution.
The measured fixture expectations change accordingly; their source text is retained.

No schema migration is needed: `failover_check_result.status` supports only
PASS/FAIL/UNKNOWN. Unsupported modes carry the safe `reason: UNSUPPORTED` projection
and a blocking FAIL or UNKNOWN status; no fourth database status is introduced.
PAN projects only known role/mode enums, never arbitrary vendor strings or serials.

A VS command still uses the existing exact `bash -lc 'vsenv <VSID> && cphaprob stat'`
form. The wrapper's successful execution is the context provenance when no explicit
context marker exists. When a context header is present it must match the opaque
requested VSID exactly; chassis multi-VS tables cannot substitute for VS evidence.
Every comparison is made from new reads in the current pass, on the same two member
objects. No inventory role or previous readiness pass is substituted.

## What this proves and the remaining limits

Under the enrolled endpoint/transport trust model, the two separately collected
observations agree about their identities and roles in the requested context.
They are sequential reads, not an atomic simultaneous snapshot. This proves neither
continuous health, cryptographic attestation of vendor output, independent hardware
when keys/identities are cloned, real traffic takeover, nor execution authorization.
The initial CP member ID remains a session-bound claim, not an independently
inventory-enrolled ClusterXL ID. A coherently falsified response from a trusted
endpoint cannot be detected by these reads. No stronger mapping is invented.

Fail-closed gaps and the exact existing evidence needed to close them:

- Missing CP identity: the already available inventory confirmation identity and
  accepted SSH session `presentedIdentity()` must be present and equal. No device
  command can replace absent enrollment provenance; do not enroll during failover.
- Missing/contradictory CP roles: the existing `cphaprob stat`, on each verified
  member, must provide one local marker and the same two-member state table. For a
  VS, the existing `bash -lc 'vsenv <VSID> && cphaprob stat'` must supply the selected
  context; chassis-only or explicitly wrong-context output remains UNKNOWN.
- Missing PAN peer role: the existing exact XML read above must contain one
  `peer-info/state` per observer as well as the local state and reciprocal serials.
  If a real platform omits it, that platform remains UNKNOWN; no alternate read is
  approved or added here.
- A stronger CP row-to-inventory binding or proof of actual load sharing has no
  proven exact read in this battery. Its closing command is UNKNOWN, not guessed.
  Neither is required to admit reported HA under the PO's transport-bound design;
  non-HA modes stay unsupported. Any future alternative needs separate vendor
  evidence and PO command review.

ARP/flap informational treatment, deferred CPS, all command literals, gates,
frequency, retries, approval rules, and service request admission are preserved.
No service change is needed: the worker performs fresh blocking checks for both
readiness and execution. This lane does not close the other failover PRs' safety gaps.

## Validation

Added/extended the four existing failover checks/executor test classes for missing
and opposed claims, observer mix-up, exact opaque identifiers, both-active roles,
unsupported modes, VS context isolation/chassis refusal, identity rejection before
commands, and peer disagreement after suspend. Existing healthy flows remain tests.

Attempted locally:
`cd ui2 && ./gradlew --offline :worker:test --tests 'com.securityexpert.nexus.ui2.worker.failover.*' :architecture-tests:test`

Gradle did not start: `FileLockContentionHandler` creation failed with
`java.net.SocketException: Operation not permitted`. Java compilation, worker tests,
and architecture tests are UNVERIFIED, not passing. They require the engineering
owner's authorized container validation. No host access or sandbox bypass was used.
`python3 tools/privacy/repository_privacy_check.py` passed with zero findings;
`git diff --check` passed. These checks do not compile or execute Java.
Project state and rotating handovers are owned by the engineering session and are
intentionally untouched under the brief's explicit scope restriction.
