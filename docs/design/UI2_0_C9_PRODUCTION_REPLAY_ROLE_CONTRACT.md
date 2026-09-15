# UI 2.0 — C9 production replay-role anonymized view contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Authored under
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN) as bounded
`ARCHITECTURE` work (movement `NXS-LOCAL-0231`), on explicit Product Owner
instruction in this session to review and freeze
`docs/design/UI2_0_PRODUCTION_REPLAY_ROLE_ANONYMIZED_VIEW_DESIGN.md`
(SUPERSEDED by this document) before implementation is dispatched.
Supersedes that document for every clause resolved below (its §12 items 1
through 10). No `ui2/` source, migration, or `RoleToken` addition is
authorized by this document — it fixes the initial surface, the session/
RBAC/HMAC/audit/refusal/export/search/cache boundaries, and an
implementation order; a follow-up `IMPLEMENT` movement, dispatched
separately, builds them (§10 below).

No production data, host, device, or credential was accessed to produce this
freeze; only repository source, tests, and design/contract documents were
read — including a full read of every controller in `ui2/service/src/main/
java/.../api/`, which the predecessor's own §13 evidence item 1 named as
required before freeze.

---

## 1. What this resolves, and what it does not

This contract resolves `UI2_0_PRODUCTION_REPLAY_ROLE_ANONYMIZED_VIEW_DESIGN.md`
(SUPERSEDED, hereafter "the predecessor") §12 items 1 through 10. It does
not re-derive the predecessor's non-`UNKNOWN` content — the server-side
trust-boundary requirement (§3), the "one funnel, not per-controller logic"
rule, the reconciliation with existing law (§11), or the deferral of
`PRIVATE_REPLAY_ARCHITECTURE.md` Phase B (§1) — those are restated here only
to the extent this contract's own decisions depend on them, and remain in
force unchanged.

## 2. Authority chain (highest first)

1. `AGENTS.md` — durable constitution: identity law, sensitive identity
   reporting law, raw-evidence law, UNKNOWN/fail-closed law.
2. `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN).
3. `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN) and
   `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN) — the
   closed `RoleToken` vocabulary, the `E1`–`E6` gate chain, `authz_
   decisions`, the visible-but-refused refusal contract this design
   composes with, not replaces.
4. `docs/design/PO_DECISION_RECORD_2026_09_15B_SESSION_POLICY_AND_ADMIN_
   VISIBILITY.md` (FROZEN) — idle timeout, concurrent-session policy,
   address-visibility rule.
5. `docs/design/PO_DECISION_RECORD_2026_09_14J_NEXUSADMIN_IS_THE_PRODUCTS_
   ROOT_IDENTITY.md` (FROZEN) — RT-2 (root's authorization is always
   `PERMITTED`) and RT-5 (root leaves a full audit trail, never an
   anonymized one).
6. `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` (FROZEN) — the
   "declared redaction set, digest over blank, compare locally never
   disclose" pattern this contract's audit-plane clauses (§7) reuse.
7. `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A scope
   only) — evidence and precedent for pseudonym domain-separation and
   synthetic-label patterns, per the predecessor's own §1/§2 item 7
   framing; not authority for this contract's live, production-facing
   problem, restated unchanged.
8. This document's predecessor (SUPERSEDED) — historical only, cited for
   provenance, never as authority for a clause this document restates or
   resolves.

## 3. Activation shape and prerequisite (resolves predecessor §12 items 1, 10)

**Decision: exclusive session mode, not an additive role token alone.**
`.nexus/approved_task.json`'s own requirements already directed this choice
("exclusive session mode" is stated as a requirement, not left open) — this
contract records the source evidence that makes it the sound choice as well:
`C3` §3.4 already has a proven pattern for exactly this shape — an explicit,
separate, deliberate action that produces a distinct session state (the
login/`resolve` takeover mechanism), never inferred from a timeout, a
header, or silence. Reusing that shape means no new session-state mechanism
is invented; only a new distinct outcome of an existing one.

Concretely: activating the role is authorized by **one new closed-vocabulary
`RoleToken` entry**, `role:replay_viewer` — gating only the *activation
action itself* (whether an identity may request the exclusive replay-
projected session), the same way every other token is resolved, through
`role_bindings` and `RbacEvaluator`, not a parallel mechanism (predecessor
§6 bullet 1, not reopened). Once activated, the **session** — not the
identity's other tokens — enters a distinct sub-state, mirroring `C3`
§3.4's takeover producing a superseding session `S2`: for that session's
lifetime, every read is routed through the projection stage (§4 below), and
**every action not in the frozen initial surface's read-only route set
(§5) is refused**, regardless of what other `RoleToken`s the identity
otherwise holds. This closes predecessor item 10 (prerequisite question):
activating the role does not require also holding `role:viewer` as a
separate prerequisite, because the exclusive session's own read-only
enforcement makes the underlying token irrelevant for the session's
lifetime — the identity needs only `role:replay_viewer` to activate, and
the activated session grants nothing beyond the frozen initial surface no
matter what else the identity is entitled to.

This "activation converts the session into a strictly read-only, single-
surface session for its duration" behavior is a new requirement this
contract adds, not present in the predecessor: it directly prevents the
scenario the predecessor's §6/§9 left unaddressed — an operator viewing an
anonymized picture of production state making a write decision against real
production while unable to see the raw values that decision should be
informed by. A presentation-only role must not silently retain write
capability while showing a deliberately incomplete picture.

## 4. Server-side projection funnel (grounds predecessor §3, §11 in current code)

The projection stage sits strictly after `GateChain`'s `E4` block
(`GateChain.java` lines 133–147, confirmed by this freeze's own read — the
exact point `RbacEvaluator.evaluate(...)` resolves `PERMITTED`/`NO_
APPLICABLE_AUTHORITY`/`DENIED`/`AUTHZ_NOT_EVALUATED` and `GateChain` returns
`GateOutcome.Proceed`) and before response serialization. Spring's
`ResponseBodyAdvice` mechanism (or an equivalent single interceptor
registered once for every controller in scope, never per-controller logic)
is the natural funnel for this, since it runs after a controller method
returns and before the body is written to the wire — the same "one funnel,
not per-controller logic" rule the predecessor's §3 already fixed, now
pointed at a concrete Spring mechanism rather than left abstract. This is
architectural guidance for the follow-up `IMPLEMENT` movement, not a
`ui2/` source change made by this document.

## 5. Initial surface and field classification (resolves predecessor §12 item 8)

**Decision: `DeviceRegistrationController`'s two read-only routes —
`GET /devices` and `GET /devices/{deviceId}` — and nothing else, for v1.**
This is the surface named in the predecessor's §4 coverage inventory with
the smallest, most concretely-known field list; confirmed by a full read of
`DeviceRegistrationController.java` this movement performed. No other
surface named in the predecessor's §4 (`InventoryController`,
`ConfigurationController`, `DiscoveryController`, `BackupController`,
`CredentialAdministrationController`, `LocalIdentityAdministrationController`,
`RoleBindingAdminController`, `NotificationController`,
`ProjectPlanController`, the audit-log screen) is in scope for v1 — each
refuses under this role until its own field-classification pass is done, per
the fail-closed rule §7 below restates.

Field-by-field classification, `toSummaryBody`/`toDetailBody`:

| Field | Classification | Treatment | Basis |
|---|---|---|---|
| `device_id` | identity | pseudonymize — domain-separated HMAC + synthetic display label (`cp-fw-1`-style) | `PRIVATE_REPLAY_ARCHITECTURE.md` privacy-compiler row: "Device... identities" → consistent pseudonymous IDs + synthetic labels |
| `hostname` | identity | pseudonymize — replaced by the **same** synthetic label as `device_id`, never shown raw | same row; a real hostname is itself identity-bearing |
| `cluster_member_ref` | identity reference | pseudonymize — same domain-separated scheme as `device_id`, so cross-reference equality still holds | `PRIVATE_REPLAY_ARCHITECTURE.md`: "artifact IDs/references → rewrite consistently" |
| `role` | evidence state | preserve as-is | device-role classification enum, not identity-bearing |
| `vendor_hint` | evidence state | preserve as-is | vendor classification (CP/PAN/VSX), not identity-bearing |
| `enrollment_state` | evidence state | preserve as-is | lifecycle classification enum |
| `disabled` | evidence state | preserve as-is | boolean operational fact |
| `model` | evidence state | preserve as-is | hardware/software model classification, not identity-bearing |
| `software_version` | evidence state | preserve as-is | version classification, not identity-bearing |
| `ha_role` | evidence state | preserve as-is | HA role classification enum |
| `peer_follow_outcome` | **unresolved this movement** | **exclude** (fail closed) | field's exact Java type/enum was not read this movement; the raw-evidence law's default posture for an unconfirmed field is exclusion, not a best-effort guess |
| `peer_follow_reason` | **unresolved this movement** | **exclude** (fail closed) | same — possible free text, not confirmed either way this movement |
| `identity_mismatch_state` | **unresolved this movement** | **exclude** (fail closed) | same — name suggests an enum, but type not confirmed this movement |
| `job.job_id` | identity | pseudonymize — same domain-separated scheme | artifact ID, same rule as `cluster_member_ref` |
| `job.state`, `job.outcome` | evidence state | preserve as-is | `C2` lifecycle enums |
| `job.terminal_reason` | **unresolved this movement** | **exclude** (fail closed) | possible free text; not confirmed this movement |

The three `peer_follow_reason`/`identity_mismatch_state`/`job.terminal_
reason` exclusions are not a permanent design choice — they are this
contract's fail-closed application of predecessor §8's own rule ("a surface
with no recorded field-classification entry is refused for rendering...
never a best-effort or partial projection") to three fields this movement's
source read did not confirm the exact type of. The follow-up `IMPLEMENT`
movement's first task (§10 item 1) is to read their actual Java types and
extend this table before wiring the response for them; until then they are
refused, not guessed.

**Pseudonym key lifecycle (resolves predecessor §12 item 3):** a **durable,
per-installation HMAC key**, generated once at first activation of
`role:replay_viewer` in an installation and held server-side only (under
`C1`'s existing key-custody boundary, never returned to any client, never
logged) — not a fresh key per activation or per session.
`PRIVATE_REPLAY_ARCHITECTURE.md`'s own pattern ("a fresh export normally
gets a fresh key") does not transfer directly, as the predecessor's own §5
already noted: an offline, one-shot export has no cross-session equality
requirement to preserve, while this role does (§5's own requirement that the
same device read as the same label across screens and across the role's
entire operational lifetime, not just one session). A single durable key
avoids the rotation/collision problem the predecessor left open, by
deferring rotation entirely rather than inventing an unproven rotation
scheme now: **v1 does not support key rotation** — a future movement that
needs rotation must design the collision-avoidance mechanism the
predecessor's §12 item 3 named as unresolved before adding it.

## 6. Root identity interaction (resolves predecessor §12 item 2)

**Decision: uniform — root's own session is projected identically to any
other holder's when `role:replay_viewer` is active for it.**
`.nexus/approved_task.json`'s own requirements state this directly ("no root
bypass"). This does not conflict with `PO_DECISION_RECORD_2026_09_14J`'s
RT-2: RT-2 fixes an **authorization** outcome (whether an action proceeds —
always `PERMITTED` for root), and this design's anonymization is a
**presentation** fact about what a permitted response contains, exactly the
distinction the predecessor's own §6 already drew without resolving which
way it cut. Root retains its unconditional `PERMITTED` outcome on every
action, including activating `role:replay_viewer` itself and every read
within the resulting session; what that session's reads *render* is
projected the same as any other `role:replay_viewer` session. Root's own
ordinary, unprojected access is unaffected — it simply does not activate
this role. RT-5's audit-trail guarantee (§7 below) is unaffected either way.

## 7. Audit events (resolves predecessor §12 item 4)

**Decision: activation/deactivation only, no per-read audit row in v1.**
Role activation and deactivation are audited mutations naming the actor and
the old/new state, in the same shape `PO_DECISION_RECORD_2026_09_15B` §1/§5
already require for other session-policy mutations. Per-record view
auditing is **not** added in v1: `C3` §5.3's own reasoning for keeping
`authz_decisions` separate from `audit_log` — "so the mutation trail is not
drowned in read-time noise" — applies with equal force to a candidate
per-view trail, and freezing one now would add a high-frequency storage cost
this movement has no proven need for. `.nexus/approved_task.json`'s own
requirements text ("activation-only audit unless contract evidence requires
stricter") is satisfied: no evidence this movement gathered requires
stricter.

**Unchanged, restated from the predecessor's §7 (not reopened, already
resolved there):** audit rows about actions taken while the role is active
record the real actor, never an anonymized one — `actor_fingerprint`,
`session_id`, and every other audit column `C1`/`C3` define are populated
exactly as today, regardless of role state. This is a hard requirement, per
RT-5's "an unblockable identity that left no trail would be worse than the
problem it fixes," applied here to a presentation control rather than to
root specifically.

## 8. Refusal behavior (restates predecessor §8, not reopened)

Activation refusal reuses `C3` §5.2's exact envelope — `403
{"error":"ACTION_REFUSED", "outcome":..., "reason_code":...}` — no new
refusal shape. A surface with no recorded field-classification entry (§5
above) is refused for rendering under this role in full, never with a
partial or best-effort projection; the three excluded fields in §5's table
follow this same rule at field granularity within an otherwise-classified
surface — the surface itself still renders, those three fields do not,
until classified.

## 9. Search, export, and caching boundaries (resolves predecessor §12 items 5–7, §9)

A full grep of `ui2/service` performed this movement found:

- **No search/filter/query endpoint exists anywhere.** Zero
  `@RequestParam` usages exist in the whole `service/src/main/java` tree;
  `GET /devices` is an unconditional `listAll()` with no predicate. This
  resolves predecessor item 5 **by absence**, not by design: there is
  nothing to classify today. **Forward boundary rule, frozen now:** if a
  search/filter endpoint is ever added to a surface this role can view, it
  must match only against pseudonymized/projected field values for a
  session with this role active, never against raw underlying column
  values — proven by an adversarial test before that endpoint may serve an
  anonymized-role session (§10 item 3 below).
- **No caching layer exists anywhere** — zero `@Cacheable`, `Cache-
  Control`, `ETag`, or `CacheManager` usages in `service/src/main/java`.
  This resolves predecessor item 7 by absence. **Forward boundary rule:**
  if a caching layer is introduced later, its cache key must include the
  viewing session's `role:replay_viewer` activation state; a cache entry
  must never be shared between an anonymized and a non-anonymized read.
- **No export/download/report code path exists anywhere** — no file or
  class matching `export`/`download`/`csv`/`report` exists in
  `service/src/main/java`; the two incidental `parseCsvEnv` hits are
  environment-variable parsing, unrelated. This resolves predecessor item 6
  by absence. **Forward boundary rule, restating predecessor §3's "downloads
  are not a side door":** any future download/export/report path must route
  through the identical projection funnel (§4); until proven for a specific
  path, that path is refused for an anonymized-role session by default,
  the same fail-closed rule §8 states for an unclassified surface.

No v1 implementation work is required for search, caching, or export —
there is nothing to close, only a rule to enforce if one of these surfaces
is introduced later.

## 10. In-scope-surface exclusions (resolves predecessor §12 item 9)

**Decision: `CredentialAdministrationController`, `RoleBindingAdminController`,
and the audit-log screen are out of scope for v1**, deferred to their own
future field-classification movement and their own explicit Product Owner
decision. The first two already carry `LOCAL_OPERATOR_SENSITIVE`-class
content restricted to `role:security_admin` (`C3` §4.2); the audit-log
screen is governed independently by `UI2_0_B1_02A_AUDIT_REDACTION_
CONTRACT.md` and the sensitive identity reporting law. Bringing either into
this role's scope is a separate product decision this contract does not
make, consistent with the predecessor's own framing of item 9.

## 11. Successor implementation sequence (non-binding, ordered)

Not authorized by this document — named so a Product Owner dispatch has a
concrete next movement to approve, in dependency order:

1. **Type-confirmation pass** for `peer_follow_outcome` (if reclassifying
   from exclude), `peer_follow_reason`, `identity_mismatch_state`, and
   `job.terminal_reason` (§5) — read their actual Java types/enum
   definitions and extend the classification table before wiring them.
2. **`RoleToken.REPLAY_VIEWER` addition + activation action** (gated,
   `E1`–`E4` composition unchanged) **+ exclusive-session-mode enforcement**
   (§3's read-only, single-surface behavior for the session's lifetime)
   **+ durable per-installation HMAC key generation/storage** (§5), wired
   to the v1 initial surface only (§5's table, confirmed-safe fields only).
3. **Adversarial leak test suite** proving no raw value reaches a
   serialized response under the active role across the v1 surface, and a
   route-enumeration/grep test proving the browser cannot select, toggle,
   or override the projection (mirroring `C3`'s existing `AG-J2`/`AG-J3`
   discipline for front-end role-blindness).
4. **Cross-screen pseudonym-equality test suite**, proving the same raw
   identity always projects to the same label within the session and across
   sessions (per §5's durable-key decision).
5. **Expand to remaining `§4`-inventory surfaces one at a time**
   (`InventoryController`, `ConfigurationController`, `DiscoveryController`,
   `BackupController`, `NotificationController`, `ProjectPlanController`, in
   that order — read-only/evidence-state-heavy surfaces first), each
   requiring its own field-classification pass per §5's method before being
   added; `CredentialAdministrationController`, `RoleBindingAdminController`,
   and the audit-log screen stay excluded (§10) pending their own PO
   decision.
6. **Search/cache/export closure** (§9) only if and when one of those
   surfaces is actually introduced — no movement is scheduled for it now.
7. **Human real-environment validation**, per `AGENTS.md`'s evidence laws,
   before any `DONE` status is recorded for any slice above; automated
   validation alone never suffices for a production-facing behavior.

## 12. Cross-references

- `docs/design/UI2_0_PRODUCTION_REPLAY_ROLE_ANONYMIZED_VIEW_DESIGN.md`
  (SUPERSEDED by this document) — the predecessor draft; historical record
  of the trust-boundary reasoning (§3) and risk analysis (§9/§10) this
  contract does not restate.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN).
- `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN).
- `docs/design/PO_DECISION_RECORD_2026_09_15B_SESSION_POLICY_AND_ADMIN_VISIBILITY.md` (FROZEN).
- `docs/design/PO_DECISION_RECORD_2026_09_14J_NEXUSADMIN_IS_THE_PRODUCTS_ROOT_IDENTITY.md` (FROZEN).
- `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` (FROZEN).
- `docs/design/UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`.
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A scope only)
  — evidence and precedent, not authority for this document's live
  production-facing problem, restated unchanged from the predecessor.
- `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN).
- `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/RoleToken.java`,
  `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/GateChain.java`,
  `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/DeviceRegistrationController.java`
  — the source this freeze read to ground §3–§5.
