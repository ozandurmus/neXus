# UI 2.0 — discovery-integrated, management-endpoint-scoped SSH trust enrollment contract

## Status

**SUPERSEDED — historical only, not implementation authority.** Movement
`NXS-LOCAL-0222` (`ARCHITECTURE`) produced this document as a DRAFT trace-
to-source and proposal; movement `NXS-LOCAL-0234` froze
`docs/design/UI2_0_C10_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md` as its
successor, resolving every load-bearing `UNKNOWN` this document left open
(its §11 register and §14 open items) for a Check Point v1 implementation.
This document remains the repository's trace-to-source evidence base for
that contract — its source citations and defect trace are not
re-derived there — but per `AGENTS.md` "Contract-status law" and
"Authority hierarchy" item 2, a superseded document is never implementation
authority; the C10 contract above is authoritative for this subject from
here forward.

Evidence grade: repository source only. No real Check Point or Palo Alto
management server was contacted while writing this; every code path cited
below is quoted or paraphrased from the file named, not from memory of what
similar code usually does (`AGENTS.md` vendor semantics law).

## 1. Scope and authority

### 1.1 In scope

Tracing, and a draft design for, the SSH host-key trust boundary
`ui2_discovery_run` crosses when `DiscoveryJobExecutor` enumerates a Check
Point management server that has **no `devices`/`endpoints` row** — the
pre-enrollment case `DiscoveryRun`'s own Javadoc calls out ("DR-4: the
management server itself is not a device row"). It does not touch the Palo
Alto TLS-certificate-pin path except where the same design question
(one-literal-fits-every-endpoint) recurs there identically.

### 1.2 Out of scope

- Any implementation: no source file changes, no migration, no runtime
  configuration change.
- The Check Point confirm/enrollment identity-mismatch flow for an
  **already-enrolled** device (`docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md`
  §4.3–§4.6, FROZEN) — that flow's "connect anyway, warn" behavior (EC-6)
  operates on a device that has already passed transport-layer host-key
  trust and already has a recorded identity row; it is discussed in §3.2
  below only to distinguish it from the transport-layer question this
  document is about, never to extend or reinterpret it.
- Palo Alto XML-API TLS trust as its own subject — noted only where the
  same architectural gap repeats.
- Parallel movement `NXS-LOCAL-0221` (audit) and `NXS-LOCAL-0220` (discovery
  display), per `.nexus/WORKER.md`.

### 1.3 What this document is written against

- `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` §5 (FROZEN) —
  the `DeviceTransport` port and the `ssh_exec` adapter's contractual shape:
  "verifies the host key against the capability's `trust_rule_ref` before
  any command is sent — a mismatch is a definite `ConnectResult` failure,
  never `OUTCOME_UNKNOWN`" and "the adapter never defaults to accept-any or
  trust-on-first-use." This document does not amend §5; it addresses what
  §5 leaves open — how `trust_rule_ref` is populated, scoped and enrolled
  for a management endpoint that has no device row yet.
- `docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) — cited in
  §3.2 for contrast, not as authority for anything this document proposes.
- `AGENTS.md` "Check Point": "Production SSH requires trusted host keys" and
  "no trust-on-first-use"; "Diagnostic-path law": "reuse the existing
  controlled application path"; "Identity law" and "Sensitive identity
  reporting law" (management address is `CLASS 2`).
- Source, read directly for this document:
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/DiscoveryJobExecutor.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/ManagementPlaneEnumerationAdapter.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/ssh/SshExecTransport.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/ssh/HostKeyVerifier.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/ssh/TrustRuleResolver.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/Ui2WorkerMain.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/EnvironmentTrustRuleResolver.java`
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/cp/DiscoveryRunnerMain.java`
  - `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/transport/{ConnectResult,ConnectSpec,ConnectionTarget,DeviceTransport}.java`
  - `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/discovery/cp/ManagementPlaneEnumerationRequest.java`
  - `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/discovery/DiscoveryRun.java`
  - `project/QUEUE.md` line naming `ui2_ssh_strict_host_key_trust_in_production` (P0/planned).

## 2. Current flow, traced to source

### 2.1 Transport-layer host-key decision (the part that is fail-closed today)

`SshExecTransport.connect` (`SshExecTransport.java:63-92`) sets
`session.setConfig("StrictHostKeyChecking", "yes")` and wires a
`HostKeyRepository` whose `check(host, key)` (`SshExecTransport.java:187-225`)
delegates to `HostKeyVerifier.isTrusted(trustRuleRef, presentedFingerprint)`.
`HostKeyVerifier.isTrusted` (`HostKeyVerifier.java:28-31`) returns `true`
**only** when `TrustRuleResolver.resolveExpectedFingerprint(trustRuleRef)` is
present **and** equals the presented SHA-256 fingerprint exactly — no
partial match, no case-insensitive match, no fallback. The repository's
`add(HostKey, UserInfo)` (`SshExecTransport.java:196-201`) is a deliberate
no-op with a comment naming exactly why: "Never trust-on-first-use." This
matches §5's contract and `AGENTS.md`'s Check Point law as written; nothing
in this file is the source of the contradiction traced below.

### 2.2 Discovery's `trust_rule_ref` is one literal per vendor, not per endpoint

`DiscoveryJobExecutor` declares (`DiscoveryJobExecutor.java:72-73`):

```java
static final String CP_TRUST_RULE_REF = "cp_discovery_trust_default";
static final String PAN_TRUST_RULE_REF = "pan_discovery_trust_default";
```

Every `discovery_run` — for Check Point, `enumerateCheckPoint`
(`DiscoveryJobExecutor.java:153-167`) — builds its
`ManagementPlaneEnumerationRequest` with the same `CP_TRUST_RULE_REF`
literal regardless of `run.managementAddress()`. `trust_rule_ref` is a
capability-level opaque string in the `ssh_exec` contract (§5 above,
`ConnectSpec.java:12`); nothing about it is management-endpoint-scoped by
construction. Two different Check Point management servers under
discovery therefore share exactly one trust reference and, downstream, one
resolvable fingerprint slot.

### 2.3 Two divergent resolutions of that one literal

**Deployed worker** (`Ui2WorkerMain.java:118-120`, the only process
`deploy/ui2/52-worker-deployment.yaml` runs, per that file's own Javadoc
lines 76-85):

```java
TrustRuleResolver trustRuleResolver = trustRuleRef -> Optional.ofNullable(
        System.getenv("UI2_" + trustRuleRef.toUpperCase(Locale.ROOT).replace('.', '_')
                + "_FINGERPRINT"));
```

For `trustRuleRef = "cp_discovery_trust_default"` this reads environment
variable `UI2_CP_DISCOVERY_TRUST_DEFAULT_FINGERPRINT` — the exact variable
the Product Owner reported `MISSING` in the deployed worker
(`.nexus/approved_task.json` baseline). This resolver **does** incorporate
`trustRuleRef` into the lookup key, so it is not itself the "ignores the ref"
defect; it is simply unset.

**Standalone PO-runnable CLI runner**
(`DiscoveryRunnerMain.java`, Javadoc lines 17-24: "The Product-Owner-runnable
entry point ... no HTTP endpoint") wires
`EnvironmentTrustRuleResolver.INSTANCE` instead
(`DiscoveryRunnerMain.java:56`). That class's `resolveExpectedFingerprint`
(`EnvironmentTrustRuleResolver.java:22-25`) reads environment variable
`CP_DISCOVERY_TRUST_FINGERPRINT` **unconditionally, ignoring the
`trustRuleRef` parameter entirely** — its own Javadoc calls the literal an
"opaque placeholder, never a lookup key" (`DiscoveryJobExecutor.java:65-71`
describes the same thing for both vendor resolvers). The Palo Alto
standalone runner (`PanDiscoveryRunnerMain.java:41-43`) uses the equivalent
`EnvironmentPanTrustRuleResolver.INSTANCE`.

So there are **two different environment-variable names**
(`UI2_CP_DISCOVERY_TRUST_DEFAULT_FINGERPRINT` for the deployed worker,
`CP_DISCOVERY_TRUST_FINGERPRINT` for the standalone runner) resolving the
same nominal trust rule, in two code paths that are never exercised
together. Neither path is management-endpoint-scoped: both resolve to
exactly one fingerprint slot for every Check Point management server ever
discovered by that process, for as long as that process runs.

### 2.4 The observed failure, traced through the collapse points

With `UI2_CP_DISCOVERY_TRUST_DEFAULT_FINGERPRINT` unset, `HostKeyVerifier`
returns `false` for every presented key
(`resolveExpectedFingerprint` returns `Optional.empty()`), so
`HostKeyRepository.check` returns `NOT_INCLUDED`, and JSch's connect throws
a `JSchException`. `SshExecTransport.connect`'s catch block
(`SshExecTransport.java:82-91`) classifies the exception by **substring
match on `e.getMessage()`**: `"HostKey"` or `"reject"` → `HostKeyRejected`;
`"timeout"` or `"Auth cancel"` → `TimedOut`; anything else →
`AuthenticationFailed`. This is itself a fragile boundary — `AGENTS.md`
vendor semantics law ("a field name is not its contract") applies equally
to an exception message string, which is JSch's own text, not a contract
this repository controls — but it is not this document's subject; it is
named because it feeds directly into the next collapse.

`ManagementPlaneEnumerationAdapter.run` (`ManagementPlaneEnumerationAdapter.java:90-94`)
then collapses **every** non-`Authenticated` `ConnectResult` — whether
`HostKeyRejected`, `AuthenticationFailed`, or `TimedOut` — into one
`ManagementPlaneEnumerationResult.Failed("management-plane session could not
be authenticated", 0, NOT_OPENED)`. `DiscoveryJobExecutor.failureClass`
(`DiscoveryJobExecutor.java:198-204`) then maps any reason string besides
its two known literals (`"unreachable"`, `"refused"`) to `UNKNOWN_FAILURE`.
That final mapping is exactly the `outcome_summary
{"failure_reason_class:UNKNOWN_FAILURE":1}` the Product Owner observed live.
This traces the missing pin to the observed symptom through named source —
it does **not** prove the missing pin is the only path to `UNKNOWN_FAILURE`;
a wrong credential, a genuine timeout, or a real host-key mismatch on an
already-enrolled trust rule collapse to the identical symptom today, which
is this document's §7 finding, not resolved here.

## 3. The contradiction

### 3.1 Management-endpoint identity exists; trust scope does not use it

`DiscoveryRun.managementAddress` (`DiscoveryRun.java:16`) is per-run,
`CLASS 2` (its own `toString()` redacts it, line 24-27, per
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` §5 PR-1's classification of every
management address). The request built from it,
`ManagementPlaneEnumerationRequest.managementHost` (line 12), reaches
`ConnectionTarget` (`ConnectionTarget.java:9`) as the dial target. Nothing in
that chain carries the management address, or any derivative of it, into
`trust_rule_ref` — the ref is the fixed literal from §2.2. A trust store
keyed only by a global literal cannot express "server A's key is X, server
B's key is Y" — the second discovery run against a second Check Point
management server would need the operator to overwrite the single pinned
env-var fingerprint before running, silently invalidating trust for the
first. This is the concrete shape of "one global pin is inadequate"
(`.nexus/WORKER.md` risks).

### 3.2 An unresolved, pre-existing contradiction with the fail-closed law — reported, not adjudicated here

`project/QUEUE.md` line 17 carries a **P0/planned** item,
`ui2_ssh_strict_host_key_trust_in_production` — "UI2 SSH host-key trust: on
mismatch connect, warn with the ..." (target: `ui2 worker HostKeyVerifier /
TrustRuleRe[solver]`), per `.nexus/approved_task.json`'s baseline. Read
literally, "on mismatch connect, warn" describes exactly the behavior
`SshExecTransport`/`HostKeyVerifier` do **not** implement today — the
current transport is fail-closed (§2.1) — and it is also not what
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` EC-4–EC-10 describe, because that
flow's "connect anyway, warn" (EC-6) fires only **after** the transport
layer has already accepted the presented host key against `trust_rule_ref`
(§4.3's recorded identity is captured "on first success"); it compares a
separately recorded **business identity baseline** for an
**already-enrolled** device, and is not a host-key trust bypass at the SSH
layer. This document cannot determine from the queue line alone whether the
queue item (a) describes a stale/rejected proposal that the current
fail-closed transport already supersedes, (b) is itself in error, or (c)
names a real conflict this repository has not yet reconciled. Per
`AGENTS.md` "Authority hierarchy" — "never silently reconcile a
disagreement between two authorities... report the contradiction and let
the human or the higher authority resolve it" — this document reports the
contradiction and takes no position on which reading is correct. Any
trust-enrollment successor to this draft must read the queue item's full
text and adjudicate this explicitly before proposing storage or a
verification default.

## 4. Vocabulary this document proposes for the successor: observation, verification, authorization, lifecycle

A trust-enrollment design needs these kept distinct — collapsing them is
exactly how "connect and record what was presented" (safe) turns into
"connect authenticated on an unverified key" (forbidden). None of the
following is decided; this section names the shape the next contract needs
to fill in.

- **Observation**: reading the host key a management endpoint presents,
  bounded to the key-exchange step, **before** any credential is
  transmitted and before any command is sent. `AGENTS.md` "Diagnostic-path
  law" requires reusing the existing controlled application transport
  rather than opening a parallel diagnostic credential path — so
  observation should be a bounded connect that stops at key exchange, over
  the same `ssh_exec` transport shape §5 of the collection-engine contract
  already defines, never a new standalone probe tool. Observation produces
  a fingerprint and nothing else; it is not itself trust.
- **Verification**: comparing an observed fingerprint against an
  independently obtained expectation (out-of-band from the operator, or a
  prior authorized enrollment — which source is `UNKNOWN`, §11). Only a
  match here can feed §5.
- **Authorization**: a named, audited operator action that moves an
  observed-and-verified fingerprint into the set `HostKeyVerifier` will
  accept for a given scope (§5). Never automatic, never inferred from a
  single successful connection (that would be trust-on-first-use by another
  name) — `AGENTS.md` Check Point law forbids exactly this.
- **Lifecycle**: an authorized trust entry must be revisable (key rotation,
  device replacement) only through another named, audited action — never a
  silent overwrite by a later observation, mirroring
  `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` EC-9's "re-baselining is an
  explicit, audited operator action" for the adjacent device-identity case,
  cited here as a **precedent for the shape**, not as authority extended to
  this document's own subject matter.

## 5. Trust scope: management-endpoint, not one literal

The successor design should key a trust entry by, at minimum, the
management endpoint's address and port (`ManagementPlaneEnumerationRequest.managementHost`/
`managementPort`) and the presented key's algorithm — never by a single
process-wide literal (§3.1), and never by anything the Identity law forbids
normalizing (a raw address is `CLASS 2` and must not appear in a shareable
artifact per §8). Whether the scope key is the raw address, an
already-existing `OpaqueId`/opaque endpoint reference, or something else is
`UNKNOWN` (§11) — this document states the requirement (endpoint-scoped, not
global) without inventing the storage shape.

## 6. Caller coverage

Both known callers of the SSH trust path must be accounted for by any
successor, or the design fixes one and leaves the other silently divergent
as §2.3 shows it already is:

1. The deployed worker (`Ui2WorkerMain` → `WorkerClaimLoop` →
   `DiscoveryJobExecutor` → `ManagementPlaneEnumerationAdapter` →
   `SshExecTransport`) — the only process that ever runs in production
   (`Ui2WorkerMain.java:76-85`).
2. The PO-runnable standalone CLI (`DiscoveryRunnerMain`,
   `PanDiscoveryRunnerMain`) — explicitly not an HTTP endpoint, its own
   Javadoc citing `PO_DECISION_RECORD_2026_09_13D §4`. A successor contract
   must state whether this CLI is retired, folded into the same
   trust-resolution path as the deployed worker, or kept as a deliberately
   separate diagnostic tool — `UNKNOWN` here (§11); today it silently
   resolves trust differently (§2.3), which is itself a finding this
   document surfaces regardless of which resolution the PO picks.

Both Check Point (`ManagementPlaneEnumerationAdapter`, `SshExecTransport`)
and Palo Alto (`PanoramaEnumerationAdapter`, `PanXmlApiTransport`,
`PanTrustRuleResolver`) share the identical one-literal-per-vendor shape
(`DiscoveryJobExecutor.java:72-73`); a successor should state explicitly
whether it covers both vendors together or Check Point only, since the
trust primitive differs (SSH host-key fingerprint vs. TLS certificate
fingerprint, `Ui2WorkerMain.java:129-135`).

## 7. Failure classes: what must stay distinguishable

Today (§2.4) `HostKeyRejected`, `AuthenticationFailed`, and `TimedOut` all
collapse to the same `Failed` reason string in
`ManagementPlaneEnumerationAdapter.run`, which then collapses to the same
`UNKNOWN_FAILURE` in `DiscoveryJobExecutor.failureClass` unless the reason
string happens to be exactly `"unreachable"` or `"refused"` — which no
`ConnectResult` variant's `.reason()` currently produces (§2.4 traces this
precisely; it is a source-verified gap, not a guess). A trust-enrollment
successor needs a failure taxonomy that keeps at minimum these cases
separable, without ever persisting a raw fingerprint, raw exception message,
or raw vendor response into any evidence, log, or artifact
(`AGENTS.md` raw-evidence law):

- **No trust entry exists yet for this endpoint** (nothing to observe
  against) — distinct from a key that was observed and did not match.
- **Key presented does not match an authorized entry** — a `HostKeyRejected`-
  shaped failure, definite, never retried into acceptance.
- **Credential/authentication failure** after a trusted key was accepted —
  proves the transport-layer trust decision succeeded; the problem is
  elsewhere.
- **Timeout / unreachable** — no trust decision was reached at all.

Whether this becomes a new `ConnectResult` variant, a structured field on
the existing ones, or a separate enrollment-specific result type is
`UNKNOWN` (§11) and is an implementation decision, not this document's to
make.

## 8. Safe disclosure

Per `AGENTS.md` "Sensitive identity reporting law": no management address,
no host-key fingerprint, and no credential material may appear in a
console screen, a shareable artifact, a log line, or this document itself
beyond what is already true of existing FROZEN contracts (none appears
here). Any UI surface for the observation/authorization step (§4) must
report `MATCH` / `MISMATCH` / `MISSING` / `NOT_EVALUABLE`, mirroring
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` §5 PR-3's existing pattern for
the adjacent identity-mismatch case, cited as the repository's established
convention for this exact reporting shape — an operator who needs to
*confirm* a specific fingerprint (e.g., against an out-of-band vendor
record) is a distinct, separately authorized disclosure action, not the
default reporting path, and its existence or shape is `UNKNOWN` here.

## 9. Required gates before any implementation

- **Security-contract freeze.** This document is DRAFT; nothing in it may
  be implemented until a human freezes a successor contract, per
  `AGENTS.md` "Contract-status law" and this movement's own merge gate.
- **Network-device command gate.** If the successor's "observation" step
  (§4) requires any new connect/probe shape beyond what `ssh_exec`'s
  existing `connect` already does at contract §5, that shape needs its own
  `docs/AI_DEVELOPMENT_PROTOCOL.md` command-gate entry before
  implementation — this document does not assume a bounded key-exchange-only
  connect is already an approved distinct command; it may be exactly what
  `DeviceTransport.connect` already does (in which case no new gate entry is
  needed), or it may require stopping before authentication in a way the
  current `connect` does not support. Which is true is `UNKNOWN` (§11).
- **Storage/schema review.** Any persisted trust entry is new storage and,
  per the "Mandatory build lifecycle," needs a frozen contract before
  implementation; no migration number is assigned or permitted by this
  document.

## 10. Proposed bounded acceptance tests for an implementation successor

Proposed only — none of these exist yet, none is authorized to be written
against production code until the contract above is frozen:

1. Given no authorized trust entry for a management endpoint, an
   observation attempt against that endpoint never results in a command
   being sent (credential transmission and command execution stay gated
   behind authorization, per §4) — provable as a unit test against the
   transport seam without a real socket, mirroring
   `HostKeyVerifierTest`'s existing no-socket-required shape
   (`HostKeyVerifier.java` Javadoc, lines 6-12).
2. Given two different management endpoints, an authorized trust entry for
   one never satisfies a connect attempt against the other, even when both
   present a key that would satisfy the other's entry (proves per-endpoint
   scoping, §5, over `HostKeyVerifier`-equivalent logic).
3. Given an authorized entry whose expected fingerprint no longer matches
   the presented key, the connect is refused (`HostKeyRejected`-shaped, per
   §7), never silently re-authorized, and never merged into the accepted
   set — proves lifecycle (§4) stays fail-closed on change, not
   trust-on-first-use by a different name.
4. Given the deployed-worker and standalone-CLI code paths (§6 item 1 and
   2), both resolve trust for the same `trustRuleRef`/endpoint identically —
   proves the divergence in §2.3 is closed, not merely relocated.
5. A structured-failure test proving `HostKeyRejected`, `AuthenticationFailed`
   and `TimedOut` remain distinguishable through
   `ManagementPlaneEnumerationAdapter`/`PanoramaEnumerationAdapter` into
   `DiscoveryJobExecutor`'s failure-reason class (§7) — the current
   `ManagementPlaneEnumerationAdapterTest` exercises the adapter's collapse
   as of this writing; a successor test must prove the collapse no longer
   happens for these three cases specifically.

## 11. `UNKNOWN` register

- Storage mechanism, schema and migration for a per-endpoint trust entry.
- The authorization workflow and role(s) permitted to authorize an
  observed fingerprint (`C3`'s RBAC model is not consulted in this
  document — reaching it was out of this movement's read list).
- Key-algorithm negotiation scope: one fingerprint per endpoint, or one per
  (endpoint, algorithm) pair.
- Whether `DiscoveryRunnerMain`/`PanDiscoveryRunnerMain` (§6 item 2) are
  retired, unified, or kept deliberately separate.
- The shape of the "observation" connect: whether `DeviceTransport.connect`
  already stops at the right point or a new bounded seam is needed (§9).
- The §3.2 queue-item contradiction's correct reading — reported, not
  resolved.
- Whether an operator-facing fingerprint-confirmation disclosure path (§8)
  exists at all in this product's UI model.
- Real-environment behavior of Check Point's SSH host-key rotation
  (whether an HA member pair share a key, whether a VSX context does) — not
  measured here; no vendor documentation was consulted for this document.

## 12. Real-environment definition of done (for the eventual implementation, not this draft)

Per `AGENTS.md` "Automated validation and real-environment validation are
separate gates" and "Never mark a network-facing behavior `DONE` from
automated tests alone": an implementation of this design is not `DONE` on
passing unit/contract tests alone. It additionally needs, at minimum —
proposed, not committed to a specific band structure, since that is the
successor contract's decision:

- A real (or realistically simulated, if a real management server is not
  available) observation against at least one Check Point management
  endpoint, proving the observed fingerprint matches what an independent
  channel (e.g., the vendor's own documented key-display command, itself
  gated per §9) reports.
- A real key-mismatch case — either a genuine key rotation or a
  substituted endpoint — proving refusal, not silent acceptance.
- Confirmation that the deployed worker and any retained standalone runner
  (§6, §11) produce the same accept/refuse decision for the same endpoint
  and key.

This document makes no real-environment validation claim of its own: it is
a draft design, not a build.

## 13. Cross-references

- `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` §5 (FROZEN) —
  the `DeviceTransport`/`ssh_exec` contract this document extends, not
  amends.
- `docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) — cited in
  §1.2, §3.2, §4 and §8 for contrast and precedent shape only; this document
  claims no authority from it and amends nothing in it.
- `docs/design/LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md` (FINDING
  REPORTED, NOT FIXED) — the closest existing precedent in this repository
  for "a trust-anchor gap on a frozen contract, reported and stopped rather
  than fixed inline"; this document follows the same discipline for a
  different transport.
- `AGENTS.md` — "Check Point," "Diagnostic-path law," "Identity law,"
  "Sensitive identity reporting law," "Raw-evidence law," "Authority
  hierarchy," "Contract-status law," "Mandatory build lifecycle."
- `project/QUEUE.md` — `ui2_ssh_strict_host_key_trust_in_production`
  (P0/planned), the contradiction named in §3.2.
- `.nexus/WORKER.md` and `.nexus/approved_task.json` — this movement's own
  dispatch, objective and observed baseline.

## 14. Open items for the Product Owner

1. Adjudicate §3.2: does `ui2_ssh_strict_host_key_trust_in_production`
   describe a rejected proposal already superseded by the current
   fail-closed transport, or a real requirement this repository has not
   yet reconciled? This document takes no position.
2. Decide whether `DiscoveryRunnerMain`/`PanDiscoveryRunnerMain` remain a
   product surface (§6, §11) — their trust resolution diverges from the
   deployed worker today regardless of this document's proposal.
3. Name the authorization workflow/role for moving an observed fingerprint
   into an accepted trust entry (§4, §11) — this document deliberately does
   not propose one; `C3`'s RBAC model was not read for this movement.
4. Decide whether this successor contract is scoped to Check Point SSH
   only or must cover the Palo Alto TLS-certificate case in the same
   freeze (§6).
