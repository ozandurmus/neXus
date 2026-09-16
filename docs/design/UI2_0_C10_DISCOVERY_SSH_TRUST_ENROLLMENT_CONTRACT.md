# UI 2.0 — C10 discovery-integrated, management-endpoint-scoped SSH trust enrollment contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Movement `NXS-LOCAL-0234`
(`ARCHITECTURE`), explicit Product Owner authorization in
`.nexus/approved_task.json` `SESSION_START` ("User explicitly authorizes
FROZEN status after this review"). Supersedes
`docs/design/UI2_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md`, now
`SUPERSEDED` — that document remains the repository's trace-to-source
evidence base for the flow this contract governs (§2 below quotes it
rather than re-deriving the same source citations), but it is historical
only and carries no authority of its own from here forward.

This contract resolves every load-bearing `UNKNOWN` its predecessor left
open (predecessor §11, §14) that a Check Point v1 implementation needs, and
names the exact implementation/migration successor (§9). It authorizes one
new persisted data model (§6) and one new failure taxonomy (§7) for
implementation; it does not itself implement, migrate, or touch runtime
configuration — `AGENTS.md` "Mandatory build lifecycle" still requires
`TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE` before `DONE`.

## 1. Scope and authority

### 1.1 In scope

Check Point discovery-run SSH host-key trust enrollment: the pre-enrollment
case where `DiscoveryJobExecutor` enumerates a Check Point management
server that has no `devices`/`endpoints` row yet (predecessor §1.1,
DR-4). This contract states the binding v1 design for observation,
verification, authorization and lifecycle of that trust decision.

### 1.2 Out of scope

- Palo Alto TLS-certificate trust. The predecessor found the identical
  one-literal-per-vendor shape on the Palo Alto path
  (`PanoramaEnumerationAdapter`, `PanXmlApiTransport`, `PanTrustRuleResolver`)
  and asked whether one freeze should cover both (predecessor §6, open item
  4). This contract answers that question: **Check Point only.** The trust
  primitive differs (SSH host-key fingerprint vs. TLS certificate
  fingerprint) and `AGENTS.md` "Palo Alto" already tracks PAN TLS transport
  convergence as its own separate hardening concern — bundling it here would
  make this contract's v1 decisions wait on an unrelated vendor's open
  question. A Palo Alto successor to this contract's shape is future work,
  named but not scheduled by this document.
- The already-enrolled-device identity-mismatch flow
  (`docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` §4.3-§4.6, FROZEN,
  EC-4-EC-10) — unchanged by this contract. That flow operates strictly
  after this contract's transport-layer trust decision has already
  succeeded; this contract does not amend it.
- Any actual implementation, migration, or runtime configuration change —
  reserved for the named successor movement (§9).
- Parallel movements `NXS-LOCAL-0221` (audit) and `NXS-LOCAL-0220`
  (discovery display), per `.nexus/WORKER.md`.

### 1.3 Authority chain

- `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` §5 (FROZEN) —
  the `DeviceTransport`/`ssh_exec` contract this contract extends, not
  amends: "verifies the host key against the capability's `trust_rule_ref`
  before any command is sent — a mismatch is a definite `ConnectResult`
  failure, never `OUTCOME_UNKNOWN`" and "the adapter never defaults to
  accept-any or trust-on-first-use." Every decision below stays inside that
  contract; none of them relaxes it.
- `docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) — cited as
  precedent shape only, not as authority extended to this contract's own
  subject matter: its EC-9 "re-baselining is an explicit, audited operator
  action" is the pattern §5.4 below restates as this contract's own binding
  rule for host-key rotation, independently, not by amendment of that
  document.
- `AGENTS.md` — "Check Point" ("Production SSH requires trusted host keys",
  "no trust-on-first-use" is the constitution's own vocabulary, not this
  contract's invention), "Diagnostic-path law", "Identity law", "Sensitive
  identity reporting law", "Raw-evidence law", "Authority hierarchy",
  "Contract-status law", "Mandatory build lifecycle", "Network action
  taxonomy".

## 2. Flow this contract governs (carried forward from the predecessor's trace)

The predecessor traced, to source, that `SshExecTransport.connect` is
fail-closed today (`StrictHostKeyChecking=yes`, no trust-on-first-use,
`HostKeyRepository.add` is a deliberate no-op) but that `trust_rule_ref` is
one literal per vendor (`CP_TRUST_RULE_REF = "cp_discovery_trust_default"`),
not scoped to the management endpoint — so two different Check Point
management servers share exactly one resolvable fingerprint slot, and the
deployed worker and the standalone CLI resolve that one slot from two
different, never-reconciled environment variables. Every non-`Authenticated`
`ConnectResult` — host-key rejection, authentication failure, or timeout —
collapses through `ManagementPlaneEnumerationAdapter.run` into one failure
string, and then through `DiscoveryJobExecutor.failureClass` into
`UNKNOWN_FAILURE` unless that string is exactly `"unreachable"` or
`"refused"`, which no `ConnectResult` variant currently produces. This
contract does not re-derive any of that; it is quoted here only to anchor
the decisions in §5-§7 against the exact defect they close.

## 3. Vocabulary (binding, not merely proposed)

The predecessor named four concepts that must stay distinct (its §4); this
contract adopts them as binding v1 vocabulary:

- **Observation** — reading the host key a management endpoint presents,
  bounded to the key-exchange step, before any credential is transmitted
  and before any command is sent. Produces a fingerprint and nothing else;
  observation is never itself trust.
- **Verification** — comparing an observed fingerprint against an
  independently obtained expectation. §5.2 names the v1 source.
  Only a match here can feed §4.
- **Authorization** — a named, audited `security_admin` action (§4) that
  moves an observed-and-verified fingerprint into the set `HostKeyVerifier`
  will accept for a given scope (§5). Never automatic, never inferred from
  a single successful connection.
- **Lifecycle** — an authorized trust entry is revisable only through
  another named, audited `security_admin` action (§5.4) — never a silent
  overwrite by a later observation.

## 4. v1 decision: queue-item contradiction adjudicated

The predecessor reported, without adjudicating, a contradiction between
`project/QUEUE.md`'s `ui2_ssh_strict_host_key_trust_in_production`
("on mismatch connect, warn...") and the fail-closed transport
(predecessor §3.2). This contract adjudicates it:

**Ruling: the queue item's "on mismatch connect, warn" wording is
superseded for the SSH transport layer, effective with this freeze.** The
transport-layer trust decision stays fail-closed exactly as
`UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` §5 already requires: no
connect-on-mismatch, ever, at any point in the design below. Reading (b)
from the predecessor's three candidate readings is correct: the queue item
either predates the current fail-closed transport or was never reconciled
with it; either way it does not describe v1 behavior. This ruling does not
itself edit `project/QUEUE.md` — that file is state, out of this movement's
scope (`.nexus/WORKER.md`) — the implementation successor (§9) closes or
rewords the queue line through `scripts/project_queue.py`, per `AGENTS.md`
GOV.ORCH.5, citing this contract as the ruling.

Note for precision: `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` EC-6's
"connect anyway, warn" is unaffected by this ruling — it is a distinct,
already-enrolled-device business-identity comparison (§1.2 above), not a
transport-layer host-key bypass, and this contract does not touch it.

## 5. v1 decision: trust scope, observation, verification, authorization, lifecycle

### 5.1 Scope key: endpoint + port + key algorithm

A trust entry is keyed by the tuple **(management address, management
port, host-key algorithm)** — never a single process-wide literal. One
fingerprint is authorized per algorithm the endpoint presents; if a server
offers multiple host-key algorithms, each requires its own authorized
entry before a connection using that algorithm is trusted. This closes the
predecessor's §11 "key-algorithm negotiation scope" `UNKNOWN` and its §5
"endpoint, not one literal" requirement in the same decision, per
`.nexus/approved_task.json`'s explicit direction.

### 5.2 Verification source: independent, out-of-band, operator-attested

The `security_admin` performing enrollment must independently verify the
observed fingerprint before authorizing it — against an out-of-band channel
(the vendor's own documented key-display command on the management server,
or an equivalent operator-controlled source), never against another
observation this product made. A second observation agreeing with the
first is not verification; it proves only that the two connections reached
the same endpoint, which is exactly the property trust-on-first-use fails
to establish. This is a **procedural** requirement on the `security_admin`
workflow (§5.3), not a new device command — the product does not connect to
an independent channel on the operator's behalf for this purpose.

### 5.3 Authorization: explicit, audited, role-gated

Only a principal holding the `security_admin` role may authorize a trust
entry. Authorization is a distinct, individually audited action — never
automatic, never inferred from a successful connection, never bulk-applied
across endpoints without one audit record per endpoint+port+algorithm
tuple. The audit record carries: authorizing principal, timestamp, the
scope tuple (§5.1), and the classification `ENROLLED` or `RE-ENROLLED`
(§5.4) — never the raw fingerprint or raw management address in any
artifact reachable outside the authorization action itself (§8).

This closes the predecessor's §11 "authorization workflow and role" and
§14 open item 3 `UNKNOWN`s. `C3`'s full RBAC model is not read for this
contract; `security_admin` is adopted here as the role name the Product
Owner's direction names (`.nexus/approved_task.json` requirements), and the
implementation successor (§9) must verify that role exists in `C3`'s
authority chain before wiring an authorization check to it — if it does
not, that is a gap for the successor to report, not silently invent.

### 5.4 Lifecycle: rotation and mismatch require new explicit enrollment

A host-key mismatch or key rotation never silently re-authorizes and never
merges into the accepted set. The existing authorized entry for that scope
tuple is retained (never deleted) and marked superseded by a **new**,
separately audited `security_admin` enrollment action, mirroring
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` EC-9's "re-baselining is an
explicit, audited operator action" shape — restated here as this
contract's own binding rule, not an amendment of that contract. Until the
new enrollment happens, the connection stays refused
(`TRUST_MISMATCH`, §7).

### 5.5 Caller coverage: one persisted trust source, not two resolvers

Both known callers — the deployed worker
(`Ui2WorkerMain → WorkerClaimLoop → DiscoveryJobExecutor →
ManagementPlaneEnumerationAdapter → SshExecTransport`) and the
PO-runnable standalone CLI (`DiscoveryRunnerMain`) — must resolve trust
from the same persisted source (§6). **Ruling: unify, do not retire.** The
CLI remains a product surface (its own Javadoc already cites
`PO_DECISION_RECORD_2026_09_13D §4` as its authority for existing as a
non-HTTP entry point, which this contract does not revisit), but its
`EnvironmentTrustRuleResolver` — which today ignores `trustRuleRef`
entirely and reads a second, differently-named environment variable — is
retired from v1. The CLI's trust resolution must read the same persisted
per-endpoint trust store the deployed worker reads; no environment-variable
resolver remains for either caller after implementation. This closes the
predecessor's §11/§14 "is the CLI retired, unified, or kept separate"
`UNKNOWN` and satisfies `.nexus/approved_task.json`'s "no second resolver
remains" requirement.

## 6. v1 decision: storage model and migration allocation

A new persisted trust-entry model is authorized, minimal by design:

- **Table (working name, successor may rename without a new freeze if the
  rename is representation-only):** `management_endpoint_ssh_trust`.
- **Columns:** opaque primary key; `vendor` (enum, `CHECK_POINT` only
  accepted in v1 — a constraint, not a free-text field, so a future
  Palo Alto row cannot land here without its own contract per §1.2);
  `management_address` (`CLASS 2`, stored exactly as `DiscoveryRun.
  managementAddress` already is today — this is not a new precedent for
  storing that value, only a new table holding it); `management_port`;
  `key_algorithm`; `fingerprint_sha256`; `status` (`ACTIVE` /
  `SUPERSEDED`, never deleted, per §5.4); `authorized_by`
  (`security_admin` principal reference); `authorized_at`; `observed_at`;
  `superseded_by` (nullable self-reference, populated only by a §5.4
  re-enrollment).
- **Uniqueness:** at most one `ACTIVE` row per (`management_address`,
  `management_port`, `key_algorithm`) tuple — the database-level expression
  of §5.1's scope key and §5.4's "retained, not overwritten" rule.
- **Migration allocation:** the next sequential file in
  `migrations/postgres/` following the existing `0000`-`0009` series —
  `migrations/postgres/0010_management_endpoint_ssh_trust.sql` — is
  reserved by this contract for the implementation successor. This
  contract does not create that file; per `.nexus/WORKER.md` scope, no
  migration is written here.

This closes the predecessor's §11 "storage mechanism, schema and migration"
`UNKNOWN`, per `.nexus/approved_task.json`'s "minimal new storage
model/migration allocation" requirement, and per `AGENTS.md` "Mandatory
build lifecycle" ("storage/schema migration" requires a frozen contract
before implementation) — this section is that contract for this table.

## 7. v1 decision: failure taxonomy reaching the UI

Today (predecessor §2.4, §7) `HostKeyRejected`, `AuthenticationFailed`, and
`TimedOut` all collapse into the same failure string, then into
`UNKNOWN_FAILURE`. This contract authorizes a structured, closed failure
enum the implementation successor must thread from `ConnectResult` (or an
enrollment-specific result type — the successor's implementation choice,
not fixed here) through `ManagementPlaneEnumerationAdapter` and
`DiscoveryJobExecutor.failureClass` to the UI, replacing substring-matched
exception text with one of exactly these four values:

1. `TRUST_ENTRY_MISSING` — no `ACTIVE` authorized entry exists for this
   scope tuple; nothing has been observed against yet.
2. `TRUST_MISMATCH` — a key was presented and does not match the `ACTIVE`
   authorized entry; refusal, never retried into acceptance (§5.4).
3. `AUTH_FAILED` — a trusted key was accepted at the transport layer and
   the failure is downstream (credential/authentication); proves the
   trust decision succeeded.
4. `CONNECT_TIMEOUT` — no trust decision was reached at all
   (network-level timeout or unreachable).

No raw exception message, raw fingerprint, or raw vendor response may
reach the UI, a log line, or any shareable artifact through this path —
only these four enum values plus the safe disclosure labels in §8. This
closes the predecessor's §7/§11 failure-taxonomy `UNKNOWN` and satisfies
`.nexus/approved_task.json`'s "structured trust/auth/timeout failure
classes... no raw exception text" requirement.

## 8. Safe disclosure (unchanged in kind from the predecessor, now binding)

No management address, host-key fingerprint, or credential material may
appear in a console screen, log line, or shareable artifact. Any UI surface
for observation/authorization reports `MATCH` / `MISMATCH` / `MISSING` /
`NOT_EVALUABLE` only, mirroring
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` §5 PR-3's established pattern
for the adjacent identity-mismatch case, cited here as the repository's
existing convention for this exact reporting shape, not as authority
extended to this contract's subject matter. An operator-facing
fingerprint-confirmation disclosure path (predecessor §8, §11) is **not**
authorized by this contract — the default `MATCH`/`MISMATCH`/`MISSING`/
`NOT_EVALUABLE` reporting path is sufficient for v1 `AGENTS.md` compliance
without it. If a future need for raw-fingerprint disclosure to an operator
is identified, it requires its own separately authorized design; this
contract closes that `UNKNOWN` by deferring it, not by approving it.

## 9. Exact implementation/migration successor

- **Movement:** next sequential `NXS-LOCAL` id after this one, movement
  type `IMPLEMENTATION`, opened against this contract as its frozen
  authority.
- **Migration:** `migrations/postgres/0010_management_endpoint_ssh_trust.sql`
  (§6) — the successor's first targeted change.
- **Source changes named by this contract (not made here):**
  `DiscoveryJobExecutor`'s per-vendor literal `trust_rule_ref` construction
  becomes per-endpoint (§5.1); `HostKeyVerifier`/`TrustRuleResolver` read
  the new table (§6) instead of an environment variable; the deployed
  worker's `TrustRuleResolver` lambda and the CLI's
  `EnvironmentTrustRuleResolver` are both replaced by one shared resolver
  reading the same persisted source (§5.5); `ManagementPlaneEnumerationAdapter`
  and `DiscoveryJobExecutor.failureClass` stop substring-matching exception
  text and instead thread the four-value enum (§7); a new
  `security_admin`-gated enrollment action (API/console surface, exact
  shape left to the successor) writes §6 rows.
- **Command gate:** the observation step reuses the existing `ssh_exec`
  `connect` shape at `UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` §5 —
  `HostKeyRepository.check()` already runs during key exchange, structurally
  before the SSH authentication phase begins, so capturing the presented
  fingerprint there (currently discarded) is a parse-scope extension of an
  already-approved command, not a new one, per `AGENTS.md` "Network action
  taxonomy" ("a parse-scope extension of a command already issued... needs
  no new gate entry"). The successor does not need a new
  `docs/AI_DEVELOPMENT_PROTOCOL.md` command-gate entry for observation
  itself; it does need one if it introduces any connect behavior beyond
  capturing the already-presented key at the existing check point.
- **Targeted tests required (not merely proposed — carried forward from
  predecessor §10 as this contract's acceptance criteria):**
  1. No authorized entry ⇒ observation never results in a command being
     sent (credential transmission and command execution stay gated behind
     authorization, §3/§5.3).
  2. Two different endpoints ⇒ one's authorized entry never satisfies a
     connect attempt against the other, even with a colliding key (proves
     §5.1 scoping).
  3. An authorized entry whose fingerprint no longer matches the presented
     key ⇒ refused (`TRUST_MISMATCH`), never silently re-authorized, never
     merged into the accepted set (proves §5.4).
  4. Deployed worker and CLI resolve trust for the same endpoint
     identically (proves §5.5 closes the divergence, not merely relocates
     it).
  5. `TRUST_ENTRY_MISSING`, `TRUST_MISMATCH`, `AUTH_FAILED`, and
     `CONNECT_TIMEOUT` remain distinguishable end-to-end through
     `DiscoveryJobExecutor.failureClass` (proves §7 closes the
     `UNKNOWN_FAILURE` collapse).
- **Real-environment definition of done** (`AGENTS.md` "Automated validation
  and real-environment validation are separate gates" — not satisfied by
  the above tests alone):
  1. A real (or realistically simulated, if unavailable) observation
     against at least one Check Point management endpoint, with the
     `security_admin` independently confirming the fingerprint via the
     vendor's own documented key-display command (§5.2) before
     authorizing.
  2. A real key-mismatch or rotation case, proving refusal and requiring a
     new explicit enrollment (§5.4), not silent acceptance.
  3. Confirmation that the deployed worker and the CLI produce the same
     accept/refuse decision for the same endpoint and key (§5.5).
- **Recommended next reasoning tier:** implementation-and-migration tier
  per `docs/reference/MODEL_TIER_MAP.md` — this is a deterministic build
  inside an already-frozen contract (no new architecture decision remains),
  so it does not need architecture-tier reasoning; escalate only if the
  successor finds `security_admin` absent from `C3`'s RBAC model (§5.3),
  which would be a cross-subsystem gap, not a deterministic fix.

## 10. Cross-references

- `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` (FROZEN) —
  §5, the `DeviceTransport`/`ssh_exec` contract this contract extends.
- `docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) — cited
  for precedent shape (EC-9 lifecycle, PR-3 disclosure pattern), never as
  authority extended to this contract's subject.
- `docs/design/UI2_DISCOVERY_SSH_TRUST_ENROLLMENT_CONTRACT.md` (now
  `SUPERSEDED`) — predecessor, historical trace-to-source evidence base
  this contract carries forward; not authority.
- `docs/design/LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md` — precedent for
  "a trust-anchor gap on a frozen contract, reported and stopped rather
  than fixed inline," the discipline this contract's predecessor followed.
- `AGENTS.md` — "Check Point," "Diagnostic-path law," "Identity law,"
  "Sensitive identity reporting law," "Raw-evidence law," "Authority
  hierarchy," "Contract-status law," "Mandatory build lifecycle," "Network
  action taxonomy."
- `project/QUEUE.md` — `ui2_ssh_strict_host_key_trust_in_production`,
  adjudicated superseded-for-SSH-transport by §4 above; the implementation
  successor closes or rewords the line via `scripts/project_queue.py`.
- `.nexus/WORKER.md` and `.nexus/approved_task.json` — this movement's own
  dispatch, objective, and Product Owner authorization for `FROZEN` status.

## Migration allocation amendment — PO authorized, 2026-09-16

NXS-LOCAL-0236 relay seq3 and NXS-LOCAL-0268 approved dispatch allocate
`ui2/service/src/main/resources/db/migration/V24__management_endpoint_ssh_trust.sql`
to this implementation. This allocation supersedes the root `0010` path
in §6 and §9; no root migration loader is introduced. V23 remains reserved
for unrelated work and is not integrated by this movement. All C10 trust,
security, lifecycle and evidence requirements remain unchanged.
