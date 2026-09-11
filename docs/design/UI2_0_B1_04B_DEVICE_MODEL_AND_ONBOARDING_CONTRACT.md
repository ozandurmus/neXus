# UI 2.0 — B1-4b minimal device model and onboarding contract

**DRAFT — FOR PRODUCT OWNER FREEZE, 2026-09-11.**

## 1. Scope and authority

Implements `UI2_0_DEVELOPMENT_WORKFLOW.md` §5, Phase B1, row 4b: Device /
Endpoint / CredentialReference records, an authorized manual registration
flow and a test-target flag, so a job can start against a real, registered
object; full discovery stays in `REL-DISCOVERY`; no hard-coded device
object (R-07).

This movement performs **no discovery and contacts no device**. Everything
it enables is exercised against fixtures or an operator-registered test
target — never a probe this movement initiates. Out of scope, owned by
`REL-DISCOVERY` (workflow §5): the enrollment workflow beyond one manual
registration action (bulk import, scheduled re-discovery), pre-enrollment
network probing, and device-to-device identity inference (VSX/cluster
grouping is already `C4` §4.3's, not reopened here). Row 5's inventory
capability and row 6's implementation are downstream consumers of this
contract's objects, not extensions of its scope.

Authority, cited not re-decided: `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`
(`C1`) §3.1–§3.2 fixes the three tables' ownership, columns, data class;
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (`C3`) §4.1 fixes the
registering role, §3.5/§4.2 the audit mechanism; `UI2_0_C4_CAPABILITY_
REGISTRY_GATE_RESOLUTION_CONTRACT.md` (`C4`) §3/§6 fixes what a resolved
gate is and that no unresolved step reaches a device; `UI2_0_B1_04_
COLLECTION_ENGINE_CORE_CONTRACT.md` (`B1-4`) §5 fixes the `DeviceTransport`
port these objects must satisfy.

## 2. The three records

**Device** (`C1` §3.2): the opaque identity of one physical target.
`device_id` (opaque `TEXT`, application-generated, never numeric or
hostname-derived — `AGENTS.md` identity law), `vendor_hint`,
`registration_source` (`'manual_registration'` here), `created_at`,
`is_test_target` (§5). `C1` §3.1 marks the owner "`B1-4b` (placeholder
identity only)"; this movement owns the full column set and may add the
enrollment columns §3 needs without a further `C1` amendment.

**Endpoint** (`C1` §3.2): one transport surface per device. `endpoint_id`
(opaque), `device_id` (FK), `transport_kind` (`'ssh_exec'` at B1 scope —
the only transport `B1-4` implements), `address_ref` (management address;
`C1` §7 names this the never-logged CLASS 2 field, §7 below), `created_at`.
The schema permits multiple endpoints per device; this movement's
registration flow creates exactly one — a second is `REL-DISCOVERY`'s
concern.

**CredentialReference** (`C1` §3.2, §6): names a credential, holds none.
`credential_reference_id` (opaque), `purpose` (closed, non-free-text —
`C1` §3.2: "never a free-text label that could carry a secret"),
`backend_pointer` (opaque pointer into the secret backend, `C1` §6.2),
`created_at`.

**Relationships**: `devices 1—* endpoints`, `devices *—1
credential_references` (one credential reference per registration;
reassignment is `role:onboarding_admin`'s later "credential-profile
*reference* assignment," `C3` §4.1, not created here). `job-engine`
consumes both only through `B1-4`'s `ConnectionTarget`, never a foreign key
of its own (`B1-1` `DIR-3`).

### 2.1 Under either answer to the B1-2 open item

`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §11 item 1 leaves unresolved whether
Flyway `V1` creates these three tables in full (its own reading, "required
by FK dependency order") or stubs/omits them for this movement to create.
This contract does not resolve that — it is `B1-2`'s and the PO's to close
(§12) — and holds under either answer: **if `V1` creates the full tables**,
this movement ships an additive migration adding only the enrollment
columns §3 needs, no `CREATE TABLE`; **if `V1` stubs or omits them**, this
movement's migration issues the `CREATE TABLE` statements from `C1` §3.2
verbatim plus the same columns. Either way the column set, data class, and
`C1` §3.5 audit-trigger attachment are identical; only the `V<n>` file
differs, and §9's acceptance criteria are written against the resulting
schema state, not a specific file.

## 3. Enrollment states

The vocabulary is not invented here: `UI2_0_MOCKUP_REFERENCE_NOTES.md`'s
Administration-screen notes already show the UI consuming four states —
Enrolled, Unreachable, Degraded, Draft — plus "a draft entry is never
collected from." This contract adopts that set for
`devices.enrollment_state`, subject to the `C1`/`C4` review the mockup
notes themselves flag, and adds only the transition rule needed for manual
registration.

| State | Meaning | Permits |
| --- | --- | --- |
| `DRAFT` | registered, not yet confirmed reachable; every registration starts here | display, edit, delete; **never** collection or a job |
| `ENROLLED` | confirmed reachable, eligible for read collection | read-class collection (§4's "read collection only") |
| `UNREACHABLE` | was `ENROLLED`/`DEGRADED`, latest contact failed | display; submission is still possible, judged on outcome, never inferred from the label |
| `DEGRADED` | reachable, capability-level non-fatal signal | same eligibility as `ENROLLED`; presentational distinction only |

| From | To | Trigger |
| --- | --- | --- |
| — | `DRAFT` | manual registration (§4) |
| `DRAFT` | `ENROLLED` | authorized confirm (`onboarding_admin`'s "enrollment preview/confirm," `C3` §4.1) succeeds |
| `DRAFT` | deleted | withdrawal before confirmation |
| `ENROLLED`/`DEGRADED` | `UNREACHABLE` | a contact attempt fails |
| `UNREACHABLE`/`DEGRADED` | `ENROLLED` | a subsequent attempt succeeds |
| `ENROLLED` | `DEGRADED` | non-fatal capability signal |
| any | disabled | `onboarding_admin`'s "registry disable" (`C3` §4.1) |

Every transition is audited (`C1` §3.5). No other transition is legal; an
unrecognized value fails closed (`AGENTS.md` UNKNOWN/fail-closed law), never
defaulting to `ENROLLED`. The `DRAFT` → no-collection rule is load-bearing,
not presentational: job admission must refuse a job whose
`target_device_id` resolves to `DRAFT` before any device contact, exactly
as `C4` §6 refuses an unresolved gate — two independent "cannot start"
checks, neither a substitute for the other.

## 4. Manual registration flow

**Who** (`C3` §4.1): only `role:onboarding_admin` — "`operator` plus
enrollment preview/confirm, registry disable, credential-profile
*reference* assignment" — may register; `role:viewer`/`role:operator`
cannot, and `role:security_admin` is explicitly refused "any device-facing
action" (separation of duties, `C3` §4.1). Registration is gated as a
`DENIED`/`AUTHZ_NOT_EVALUATED`-capable action like any other (`C3`
§5.1–§5.2): visible-but-refused, never a silent 404.

**Captured**: `vendor_hint`, one endpoint's `transport_kind`/`address_ref`,
and a reference to an existing `credential_reference` by opaque id — never
a credential value inline (that is `credential_references`/
`secrets_metadata`'s own surface, out of scope). The mockup's enrollment
dialog ("device name, vendor, transport, credential profile") maps here;
"device name" is a display label, not `device_id`, and is CLASS 2 (§7).

**Validated**: `transport_kind` must be one of `B1-4`'s implemented or
declared transports; the referenced `credential_reference_id` must already
exist; `address_ref` must be well-formed for the transport. Registration
performs **no connectivity check** — `DRAFT` → `ENROLLED` is a separate,
authorized confirm action, so "registered" and "confirmed reachable" stay
independently auditable.

**Audited**: the `devices` INSERT (and paired `endpoints`/
`credential_references` rows) each get an `audit_log` row via
`fn_audit_capture()` (`C1` §3.5), `actor_fingerprint` set to the
registering session's opaque fingerprint (`C3` §3.2/§6), `action_id` naming
the registration action in `C4`'s registry. `C1` §3.5 raises
`audit_context_missing` and aborts if either is unset — no `devices` row
is ever written without an audit row.

**Write-capability limit**: registration grants **read collection only**,
matching the mockup's own dialog copy ("Enrolling a device grants read
collection only. Backup creation stays off until the device enters the
pilot allowlist") and `C4` §6: no `recovery-write`/`controlled-restore-
write` step resolves merely because a device row exists. Registration adds
no row to any table `C4`/`C7` consult for write eligibility.

## 5. The test-target flag

`devices.is_test_target` (`C1` §3.2) marks a non-production validation
target. It **permits** distinguishing test traffic in job-history/audit
views (`B1-4` §1.2's PO validation run) without inferring status from
naming (which the identity law forbids). It **forbids** nothing at the
transport or gate layer — a test-target device runs under the same `C4`
gate resolution and the same `DRAFT` prohibition as any device; the flag
classifies, it never bypasses. `is_test_target = true` does **not** grant
write eligibility and does **not** exempt a device from the pilot-allowlist
mechanism the mockup shows gating backup creation ("blocked by 'Outside the
D3 pilot allowlist'"). The two facts are deliberately independent: a
device can be a test target and still outside the pilot allowlist (the
common case), and allowlist membership is presumed `C7`'s own mechanism
(§12 item 5) — not defined and not toggled here.

## 6. Credential handling

A `CredentialReference` names a credential; it never holds one.
`backend_pointer` is "an opaque pointer into the secret backend... never
the secret value itself" (`C1` §3.2); the value itself is resolved only at
execution time by the worker process through the `<COMPONENT>_<PURPOSE>_
FILE`/`<COMPONENT>_<PURPOSE>` mechanism (`C1` §6.2, `DEV.2.1`/`DEV.2.2`) —
never persisted in any table this contract owns. `C1` §6.1's fail-closed
rule carries over unchanged: a job requiring an unresolvable credential is
refused before any device contact.

No credential value reaches a log, an exception, or a report: no column in
this contract's tables can carry one, and `C1` §3.5's trigger captures only
control-plane columns — `credential_reference_id`, `purpose`,
`backend_pointer` (itself opaque) — never a resolved secret, which never
exists inside a transaction these tables participate in. A
`CredentialReference` cannot be made to reveal a value through any read
path this contract defines (§8 test 4).

## 7. Identity and privacy

Every identifier this movement creates — `device_id`, `endpoint_id`,
`credential_reference_id` — is opaque `TEXT`, application-generated, never
numeric, never normalized against a hostname/serial/vendor token
(`AGENTS.md` identity law; `C1` §7). Sensitive fields, never exported,
logged, or committed to a tracked file (including this document, which
contains no example values): `endpoints.address_ref` (management address,
`C1` §7); any device display label captured at registration (CLASS 2 by
the instance-wide rule, `C1` §7); `credential_references.backend_pointer`
(never a secret, but a routing detail with no reason to appear outside the
schema and the secret backend's own audit trail). The whole schema is
CLASS 2 (`C1` §7); none of it is ever enumerated into a support bundle or a
CLASS 0/1 export.

## 8. Test specification

1. **`draft_device_never_collected`** fails if a job whose
   `target_device_id` resolves to `enrollment_state = 'DRAFT'` reaches
   `DeviceTransport.connect`.
2. **`unauthorized_role_cannot_register`** fails if a `viewer`/`operator`
   session gets anything other than `DENIED`/`AUTHZ_NOT_EVALUATED` from
   registration, or a `devices` row is inserted anyway.
3. **`security_admin_cannot_register`** fails if a `security_admin`-only
   session is permitted to register (`C3` §4.1's separation of duties).
4. **`credential_reference_never_reveals_value`** fails if any read over
   `credential_references` — query, audit row, API response, exception
   message — returns anything but the opaque `backend_pointer`.
5. **`unresolved_gate_blocks_job`** fails if a step whose gate resolution
   is `UNKNOWN` (`C4` §6) reaches device execution against an `ENROLLED`
   device.
6. **`registration_grants_no_write_class`** fails if, right after
   registration, any write-classed step resolves eligible without a
   separate, independently authorized write grant.
7. **`audit_row_written_for_every_mutation`** fails if an INSERT/UPDATE/
   DELETE on the three tables completes with neither an `audit_log` row
   nor an `audit_context_missing` abort.
8. **`test_target_flag_is_not_a_write_bypass`** fails if `is_test_target`
   changes any gate-resolution or pilot-allowlist outcome.
9. **`opaque_identifiers_only`** fails if any generated identifier is
   numeric-reliant or derived from `address_ref`/a vendor identifier.

## 9. Acceptance criteria

- **AC-1.** The three tables exist with `C1` §3.2's column set regardless
  of which `V<n>` created them (§2.1), each carrying `fn_audit_capture()`.
- **AC-2.** `enrollment_state` is a closed, checked vocabulary of exactly
  `{DRAFT, ENROLLED, UNREACHABLE, DEGRADED}` plus a disabled marker; only
  §3's transitions are reachable.
- **AC-3.** Registration's action-registry entry requires
  `role:onboarding_admin` and no other token (`C3` §4.1/§5).
- **AC-4.** A `DRAFT` device is provably unreachable by job admission
  (test 1) with a named refusal, not a silent no-op.
- **AC-5.** No column ever holds a secret value; `backend_pointer` is the
  only persisted reference (test 4).
- **AC-6.** Registration writes only to the three tables and their audit
  rows; nothing to a table `C4`/`C7` consult for write eligibility.
- **AC-7.** `is_test_target` has zero effect on gate or allowlist outcomes
  (test 8) and is never inferred from naming.
- **AC-8.** Every generated identifier satisfies the identity law (test 9).
- **AC-9.** No sensitive field named in §7 appears in this document, a
  committed fixture, or a log line, beyond its own column.

## 10. Validation plan

```
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
git diff --check
```

Implementation-phase (not this document's own scope): the JUnit suite
exercising §8's nine tests against a Testcontainers PostgreSQL instance
(`integration-tests`, `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §2),
plus `architecture-tests`' ArchUnit checks confirming `DIR-3`/`DIR-6` are
not violated by any new dependency this movement introduces.

## 11. Worker route

**Sonnet 5, normal.** Deterministic implementation against frozen contracts
(`C1`, `C3`, `C4`, `B1-4`) plus one closed-vocabulary state machine already
implied by the mockup notes — no new architecture, storage, or
security-boundary decision is on the table, so extended thinking is more
than the step needs.

## 12. Open items for the Product Owner

1. **`V1` table-creation ambiguity** (`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`
   §11 item 1, unresolved): confirm whether `V1` or this movement creates
   the three tables; §2.1 holds under either answer, but `V<n>` sequencing
   still needs assignment so the two movements' migrations do not collide.
2. **Enrollment-state vocabulary** is adopted from the mockup, not yet
   `C1`/`C4`-reviewed, as the mockup notes themselves flag.
3. **Multi-endpoint devices**: the schema permits more than one endpoint
   per device; whether a second manually-registered endpoint belongs to
   this movement or to `REL-DISCOVERY` is undecided.
4. **`connect` timeout default**: `B1-4` §11 item 4 defers this to "the
   PO's to set or delegate to `B1-4b`'s device-registration defaults"; this
   contract sets none.
5. **Pilot-allowlist ownership**: presumed `C7`'s mechanism; no frozen `C7`
   section was available to cite for its storage or membership rule.
6. **Disabled-state persistence**: whether "disabled" is a fifth
   `enrollment_state` value or a separate column is left open pending the
   `C1`/`C4` review in item 2.
