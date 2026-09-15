# UI 2.0 — Production replay-role anonymized view boundary (design draft)

## Status

**DRAFT — FOR PRODUCT OWNER REVIEW, 2026-09-15.** Authored under
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN) as bounded
`ARCHITECTURE` work (movement `NXS-LOCAL-0229`). This document is **not
implementation authority**: it names a server-side trust boundary and a list
of unresolved product/security decisions, and requires its own Product Owner
freeze — following the same successor-contract discipline
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` and its PO decision-record
successors already use — before any `ui2/` source, migration, or role token
is added. No production data, host, device, or credential was accessed to
write it; only repository source, tests and design/contract documents were
read.

---

## 1. What this is, and what it is not

**What it is.** A new, authorized, production-only role that, when active
for a session, causes the existing UI 2.0 console to render **anonymized
projections of real, currently-live production data** through the ordinary
authenticated console — no export, no package, no offline dataset, no
synthetic scenario. The operator sees the real product working on the real
estate, with identity-bearing and other sensitive fields replaced before
they leave the server.

**What it is not, explicitly:**

- It is not `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`'s `PRIVATE_REPLAY`
  or `SYNTHETIC_SCENARIO` mode. Those modes replay a previously exported,
  offline, transformed **package** against isolated replay providers with
  **no production contact at all** — the opposite of this role, which
  requires live production contact and anonymizes only the outbound
  presentation. `PRIVATE_REPLAY_ARCHITECTURE.md` is read here for its
  privacy-engineering patterns (pseudonym domains, field-classification
  discipline, provenance labelling) as **evidence and precedent, not
  authority** — the same posture `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
  §1.4 already takes toward a different draft document, applied here to a
  document that is FROZEN but scoped to a different problem (Phase A/B
  offline replay, not a live production role).
- It is not `PRIVATE_REPLAY_ARCHITECTURE.md`'s deferred `LIVE_PRIVATE` Phase
  B. Phase B is an **agent-mediated** live-collection intent, gated behind
  its own unmet isolation and command-gate prerequisites (`PRIVATE_REPLAY_
  ARCHITECTURE.md` §"Phase B", explicitly deferred, "grants no authority to
  initiate device commands"). This design is for a **human operator**, using
  the existing console, viewing existing collected/live reads through the
  existing collection path — it introduces no agent-initiated collection
  intent, no credential-entry mediator, and no new relationship to
  `utils/action_taxonomy.py`. Phase B's deferral is unaffected and is not
  reopened by this document.
- It is not a client-side display filter. §3 below states this as the
  central trust-boundary requirement.
- It is not implemented by this document. No `ui2/` Java source, SQL
  migration, `RoleToken` addition, or controller change accompanies this
  draft.

## 2. Authority chain consulted (evidence, not all binding on this draft)

1. `AGENTS.md` — durable constitution: identity law, sensitive identity
   reporting law, raw-evidence law, UNKNOWN/fail-closed law, contract-status
   law. Binding on this draft and on any successor.
2. `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN) —
   authorizes this bounded architecture movement.
3. `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN) and
   `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN) — the
   closed `RoleToken` vocabulary, the `E1`–`E6` gate chain, the
   `authz_decisions` audit table, and the "visible-but-refused" refusal
   contract this design must compose with, not replace.
4. `docs/design/PO_DECISION_RECORD_2026_09_15B_SESSION_POLICY_AND_ADMIN_
   VISIBILITY.md` (FROZEN) — idle timeout, concurrent-session policy,
   lockout, and admin session visibility; this design's role activation is a
   session-scoped fact and must compose with these rules, not fork them.
5. `docs/design/PO_DECISION_RECORD_2026_09_14J_NEXUSADMIN_IS_THE_PRODUCTS_
   ROOT_IDENTITY.md` (FROZEN) — the root identity's unconditional `PERMITTED`
   authorization outcome. §6 below states why this is an authorization fact,
   not a presentation fact, and flags the interaction as unresolved rather
   than assuming an answer.
6. `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` (FROZEN) — the
   "declared redaction set, digest over blank, compare locally never
   disclose" pattern this design's audit-plane clauses (§7) reuse.
7. `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A scope
   only) — evidence and precedent only, per §1 above, for privacy-compiler
   field-classification discipline, provenance labelling and pseudonym
   domain-separation patterns. Not authority for this document's live,
   production-facing problem.
8. `ui2/platform-core/.../RoleToken.java`, `ui2/service/.../security/
   GateChain.java`, `ui2/service/.../security/ActionRegistry.java` — the
   actual, current implementation of the closed role-token enum and the
   `E1`–`E6` interceptor chain this design's role-gated projection must sit
   downstream of. Read to ground this draft in current code, per `AGENTS.md`
   "when this brief or the approved task names a mechanical detail... and
   the code says otherwise, the code is authoritative": the code confirms
   the six-token closed vocabulary and the `E4`→body-refusal shape the
   contract documents describe, with no drift found.

## 3. The server-side trust boundary (core requirement)

**The browser never chooses anonymization fields, paths, or raw source
access — full stop.** Concretely:

- The role (if bound to the acting identity, §6) is a **session-scoped
  server fact**, resolved once at session/role-activation time from
  `sessions`/`role_bindings`-equivalent server state, never from a request
  parameter, query string, header, or client-supplied flag. A request that
  attempts to select "anonymized" vs "raw" per-call is not honoured; the
  server's own session state is the only source of truth, mirroring `C3`
  §5.4's existing rule that "the front end renders whatever it is given; it
  holds no role concept and computes no visibility decision of its own" —
  extended here from *visibility* to *content*.
- The projection is a **server-side response-assembly stage**, applied
  after the existing data-access/read path produces its normal result and
  **before** that result is serialized onto the wire. No raw value, in any
  form (a hidden field, an unreferenced JSON key, an HTML comment, an ETag
  or cache-validator derived from the raw value, a stack trace), reaches the
  HTTP response when the role is active. This is the same "never persist/
  return more than the declared minimum" discipline `AGENTS.md`'s raw
  -evidence law states for storage, applied here to the response boundary.
- **Composition with the existing gate chain, not a parallel one.** The
  projection stage sits strictly after `E4` (`RbacEvaluator` `PERMITTED`/
  `NO_APPLICABLE_AUTHORITY`, `GateChain.java` line ~134) and before response
  serialization; it does not re-implement or bypass `E1`–`E6`. A request
  refused at any existing gate is refused exactly as today — the
  anonymization role changes what a *permitted* response contains, never
  whether a request is permitted. This mirrors `C3` §6.2's own composition
  discipline for `E7` ("this contract does not re-specify... a shared
  boundary fact, stated once").
- **One funnel, not per-controller logic.** Every response-producing
  controller enumerated in §4 must route through the same projection stage,
  the same way `GateChain` states "run as one ordered pipeline no route can
  bypass." A controller that independently decides how to redact its own
  output is exactly the failure mode `PRIVATE_REPLAY_ARCHITECTURE.md`
  warned about for its own exporter ("masking `/api/payloads` alone would
  leave important surfaces uncovered") — applied here to a live server
  instead of an offline exporter.
- **Downloads and exports are not a side door.** Any code path that
  produces a downloadable artefact (report, CSV, audit export) while the
  role is active must go through the identical projection stage — never a
  separate, unaudited "raw download" route. §9 below states this is
  unverified against the current controller set and must be closed before
  freeze.

## 4. Surfaces in scope for field classification

Located by a targeted read of `ui2/service/src/main/java/.../api/*Controller.java`
(names only; response payload shapes were not read in full this movement —
see §13 evidence item 1):

`InventoryController`, `ConfigurationController`, `DiscoveryController`,
`DeviceRegistrationController`, `BackupController`,
`CredentialAdministrationController`, `LocalIdentityAdministrationController`,
`RoleBindingAdminController`, `NotificationController`,
`ProjectPlanController`, plus the audit-log screen specified in
`docs/design/UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`.

This list is a **coverage inventory, not a classification** — each surface
still needs its own field-by-field pass (§13 evidence item 1). Two
surfaces are called out as `UNKNOWN`-by-default rather than assumed safe:

- `CredentialAdministrationController` and `RoleBindingAdminController`:
  these already carry `LOCAL_OPERATOR_SENSITIVE`-class content under `C3`
  §4.2 (directory group display attribute, resolved live, shown only to
  `role:security_admin`). Whether the anonymized role may view these
  surfaces at all — even anonymized — is a product decision (§12 item 9),
  not assumed here.
- The audit-log screen: audit content is evidence about *actions*, and
  `AGENTS.md`'s sensitive identity reporting law and `UI2_0_B1_02A_AUDIT_
  REDACTION_CONTRACT.md`'s redaction set already govern it independently of
  this role. §7 below states why audit rows about the anonymized-role
  session itself must record the real actor, never an anonymized one — a
  live *display* of historical audit rows to an anonymized-role viewer is a
  distinct, separately unresolved question (§12 item 9).

## 5. Field classification approach and stable-pseudonym/equality requirements

`PRIVATE_REPLAY_ARCHITECTURE.md`'s "privacy compiler" table (identity →
consistent pseudonym; credentials → omit; IPs/networks → typed relationship
-preserving replacement; free text → exclude by default; roles/evidence
states → preserve; times → shifted-but-ordered; counts/topology → explicit
fidelity policy; artifact IDs → rewritten) is a proven **pattern**, not a
transferable **answer**: it was designed for an offline, one-shot export
into an isolated package, not a live server projecting a continuously
changing production read on every request. This draft adopts the pattern's
shape and treats every specific treatment as `UNKNOWN` for this live context
until re-derived against this design's own constraints:

- **Equality must be preserved within and across screens for the pseudonym
  to be useful** (an operator must be able to tell "same device" across the
  Inventory and Configuration views) — this requires a domain-separated HMAC
  pseudonym scheme in the same spirit as `PRIVATE_REPLAY_ARCHITECTURE.md`
  §"Identity and topology," but **the key-lifecycle question is materially
  different and unresolved** (§12 item 3): an offline export mints one key
  per package by default; a live role serving the same identity across many
  sessions, over an indefinite operational lifetime, needs a durable
  per-installation (or per-role-activation) key whose rotation does not
  silently produce colliding or reused pseudonyms. No such mechanism is
  proposed here — it is named as an open decision, not designed.
- **A digest is not a display value.** `UI2_0_B1_02A_AUDIT_REDACTION_
  CONTRACT.md`'s `sha256:<hex>` pattern proves a value changed or matches
  without disclosing it, which is exactly right for an audit column an
  operator never needs to *read* as a label — but this role's UI needs a
  short, stable, human-legible synthetic label (a device still needs to be
  clickable, sortable, and nameable in conversation). A raw digest is
  unusable for that; a typed synthetic-label generator (of the kind
  `PRIVATE_REPLAY_ARCHITECTURE.md`'s `cp-fw-1`-style labels illustrate) is
  the right shape, but generating one **live, from real production data, on
  every read**, with the collision-avoidance and registry properties that
  document required of an offline export, is unimplemented and unproven.
- **Network/topology relationship preservation** (same-vs-different subnet,
  prefix containment, next-hop links) is a separate algorithm from identity
  pseudonymization in the same document's own words, and this draft
  inherits that separation without resolving it: whether it is in scope for
  the first delivery slice or deferred is §12 item 8's classification pass
  to decide, surface by surface.
- **Free text, error messages, and raw configuration/command text**: per
  `AGENTS.md`'s raw-evidence law and the source evidence already gathered
  in `PRIVATE_REPLAY_ARCHITECTURE.md` ("raw `show configuration` is
  sensitive... never persist or surface it raw"), the default posture for
  this role is **exclude or refuse the surface**, never a best-effort
  substring scrub. A generic regular-expression pass is explicitly not an
  acceptable projection mechanism, for the same reason it was rejected for
  the offline exporter.

## 6. Role activation and session semantics

- **A new closed-vocabulary addition, not a parallel mechanism.** If
  authorized, this role is one more entry in `RoleToken`'s closed,
  repository-committed set (`ui2/platform-core/.../RoleToken.java`),
  resolved and evaluated the same way every other token is — through
  `role_bindings`, through `RbacEvaluator`, through `authz_decisions`. This
  draft does not propose a second authorization mechanism running alongside
  the existing one.
- **Two candidate activation shapes, unresolved (§12 item 1):**
  1. *Additive role token* — an identity holds this token in addition to
     its normal operational role(s); every read that token's holder makes is
     projected, every write/action authorization is otherwise unaffected.
  2. *Exclusive session mode* — activating the role is a distinct,
     explicit action (like `C3`'s login-resolve takeover/refuse pattern)
     that produces a session, or a session sub-state, in which **all** reads
     are projected for that session's lifetime, chosen deliberately and not
     silently defaulted, mirroring `C3` §3.4's "takeover is always an
     explicit, separate, deliberate call" discipline.
  This draft does not pick between them — both satisfy "browser never
  chooses the transform," but they have different session-model,
  audit-granularity, and revocation consequences that the implementing
  contract must work out.
- **Root identity interaction is explicitly unresolved, not assumed.**
  `PO_DECISION_RECORD_2026_09_14J_NEXUSADMIN_IS_THE_PRODUCTS_ROOT_IDENTITY.md`
  RT-2 makes the root identity's *authorization* outcome always `PERMITTED`.
  That is an **authorization** fact about whether an action proceeds; this
  design's anonymization is a **presentation** fact about what a permitted
  response contains. The two are different planes, and this draft takes no
  position on whether they must, or must not, interact — stating only that
  a future contract must say explicitly whether `nexusadmin` activating this
  role sees anonymized output like any other holder, or whether root's
  authority extends to bypassing the projection too, and must not let RT-2's
  "permit always" wording be read as silently deciding the presentation
  question by default (§12 item 2).
- **Session-scoped, not request-scoped, and left unresolved to mid-session
  toggling.** Whether the role can be turned on/off mid-session (with the
  cross-screen-consistency and caching consequences that implies, §10) or
  is fixed for the session's lifetime is `UNKNOWN` (§12 item 1, folded into
  the activation-shape decision).
- **Composition with `PO_DECISION_RECORD_2026_09_15B`.** Idle timeout,
  concurrent-session ceiling, and lockout are session-policy facts this
  design does not alter. If activation is an exclusive session mode
  (option 2 above), the implementing contract must state whether it counts
  toward the same concurrent-session ceiling as the identity's ordinary
  session — this draft assumes it does, by default, absent a stated reason
  otherwise, but does not freeze that assumption.

## 7. Audit events

- **Role activation/deactivation is an audited mutation**, naming the
  actor and old/new state, in the same shape `PO_DECISION_RECORD_2026_09_15B`
  §1 requires for the idle-timeout configuration change and §5 requires for
  account unlock: "every change... is an audited mutation naming the actor,
  the old value and the new one."
- **Per-record view auditing is `UNKNOWN` and separately costed.** Whether
  every anonymized read itself needs an audit row (high-frequency, the same
  shape `authz_decisions` already accepts for *authorization* evaluations,
  per `C3` §5.3's "a much higher-frequency event than a mutation") is not
  decided here. `C3` §5.3's own reasoning for keeping `authz_decisions`
  separate from `audit_log` — so the mutation trail is not drowned in
  read-time noise — applies with equal force to a candidate per-view audit
  trail, and is named as an open decision rather than assumed either way
  (§12 item 4).
- **Audit rows about actions taken while the role is active must record the
  real actor, never an anonymized one.** `PO_DECISION_RECORD_2026_09_14J`
  RT-5 states plainly that an unblockable identity that left no trail would
  be worse than the problem it fixes; the same principle applies here in
  reverse — a *presentation* control must never degrade the *audit* plane's
  fidelity. `actor_fingerprint`, `session_id`, and every other audit column
  `C1`/`C3` already define are populated exactly as they are today,
  regardless of whether the role is active. This is a hard requirement of
  this draft, not an open item.
- **The address-visibility rule in `PO_DECISION_RECORD_2026_09_15B` §4**
  ("the address is operational identity... shown to an administrator who
  already holds the role... never included in an artefact that leaves the
  product") composes unchanged: an anonymized-role session viewing the
  active-sessions admin surface (if it may view that surface at all — §4's
  `UNKNOWN`) is bound by that rule identically to any other viewer; this
  role neither loosens nor tightens it.

## 8. Refusal behavior

- **Activation refusal reuses the existing envelope.** If the acting
  identity does not hold the role (however it is ultimately bound, §6),
  activation is refused with the same `403 {"error":"ACTION_REFUSED",
  "outcome":..., "reason_code":...}` shape `C3` §5.2 and the current
  `GateChain.java` implementation already produce for every other
  role-gated action — no new refusal envelope shape is introduced.
- **Fail-closed on an unclassified surface.** A surface with no recorded
  field-classification entry (§4/§5) is refused for rendering under this
  role, not rendered with a best-effort or partial projection — the same
  "unclassified fields or schema versions block publication" rule
  `PRIVATE_REPLAY_ARCHITECTURE.md`'s privacy compiler states for its own
  export, applied here to a live render. A screen going blank/refused under
  this role is the correct, auditable failure; a screen silently leaking one
  unclassified field because the rest of the payload was covered is not.

## 9. Pagination, search, and export restrictions — largely `UNKNOWN`

None of the following were resolved by this movement; each requires its own
targeted source read of the specific controller/query path before freeze
(§13 evidence item 5), because the answer depends on implementation details
this movement did not read in full:

- Whether existing search/filter endpoints on `InventoryController` /
  `ConfigurationController` match against raw values server-side (in which
  case a search term typed by an anonymized-role operator could act as an
  oracle that confirms a guessed raw value, even though the *result* is
  projected) or whether search must itself operate only over pseudonymized/
  indexed fields under this role. This is a concrete, named leak risk, not
  a hypothetical one — the same class of risk `PRIVATE_REPLAY_ARCHITECTURE.md`
  flagged for its own export ("an unmapped sensitive value blocks
  publication").
- Whether pagination cursors, sort keys, or `ETag`/cache-validator headers
  derived from raw values could let an anonymized-role client infer
  ordering or existence facts about raw data it never sees rendered.
- Whether any existing download/export/report code path (§3's "downloads
  are not a side door" requirement) exists today outside the controllers
  enumerated in §4, and whether it can be reached while the role is active.

## 10. Caching and cross-screen consistency

- **Pseudonym stability is required within the scope §6 ultimately fixes**
  (session-lifetime at minimum; possibly cross-session, §5/§12 item 3) so
  that the same device reads as the same label on every screen in that
  scope.
- **Cache-partitioning by viewing role is a named risk, not a designed
  mitigation.** If any response-caching layer exists or is introduced
  between the data-access path and the projection stage (§3), a cache key
  that does not include the viewing role/session's anonymization state
  could serve a real value to an anonymized-role session, or a stale
  anonymized value to a normal session, after the projection stage is
  correctly implemented in isolation. This movement did not locate an
  existing caching layer in the controllers read (§4); its absence or
  presence must be confirmed, not assumed, before freeze (§13 evidence
  item 6).

## 11. Explicit reconciliation with existing law and contracts

- **Sensitive identity reporting law.** This role does not create an
  exception to it — it is a *mechanism* for satisfying it live, for a
  defined viewer, not a bypass for anyone else. The law's "compare locally
  → report the relationship, not the values" vocabulary is the same
  discipline §5's equality-preserving pseudonym must honour: the projected
  view may say two devices are related or identical; it may never carry the
  matched raw value to do so.
- **Raw-evidence law.** The projection stage (§3) must compute from the
  same governed evidence store the ordinary read path already uses, at
  response-assembly time, never from a new denormalized "raw snapshot"
  cache created to make this feature easier to build. No new raw-retention
  surface is introduced by this design.
- **Existing RBAC (`C3`, `RoleToken`, `GateChain`, `authz_decisions`).** §3
  and §6 above state the composition boundary explicitly: this design adds
  at most one closed-vocabulary token and one post-`E4` response-assembly
  stage; it does not add a parallel authorization mechanism, and every
  activation and use goes through the existing gate chain and audit table
  like any other role-gated fact.
- **`PRIVATE_REPLAY_ARCHITECTURE.md` Phase B deferral.** Restated from §1:
  this document does not authorize, extend, or depend on Phase B. It
  introduces no agent-initiated collection intent, no credential-entry
  mediator, and no new relationship to `utils/action_taxonomy.py` or the
  network-device command gate. An agent obtains neither raw nor anonymized
  production access through this design — the role is for a human console
  operator using the existing authenticated console, exactly as every other
  role is.

## 12. Unresolved product/security decisions (explicit list)

1. Activation shape: additive role token vs. exclusive session mode (§6).
2. Root identity (`nexusadmin`) interaction with the projection — does RT-2's
   "permit always" extend to bypassing anonymization, or is anonymization
   applied to root's own view identically to any other holder (§6)?
3. Pseudonym key lifecycle for a live, indefinite-duration role: durable
   per-installation key vs. per-activation key; rotation and collision
   handling (§5).
4. Per-record view audit granularity: activation-only, or per-read, and at
   what storage cost (§7).
5. Search/filter semantics per existing endpoint: does search match raw or
   projected values server-side (§9)?
6. Full inventory of existing download/export/report code paths and
   whether each can be reached while the role is active (§9).
7. Presence/absence of a response-caching layer between data access and the
   projection stage, and whether it needs role-partitioned keys (§10).
8. Field-by-field classification for each surface in §4, not inherited from
   `PRIVATE_REPLAY_ARCHITECTURE.md`'s offline-export table.
9. Whether `CredentialAdministrationController`, `RoleBindingAdminController`,
   and the audit-log screen are in scope for this role at all, even
   anonymized (§4/§7).
10. Whether holding this role requires also holding `role:viewer` (or any
    other existing token) as a prerequisite, or is independent of the
    existing read-role ladder.

## 13. Validation evidence required before freeze

1. A field-by-field classification pass over every surface named in §4,
   performed against the actual current response payload shapes (not read
   in full this movement), reviewed and recorded per-field, in the same
   spirit as `PRIVATE_REPLAY_ARCHITECTURE.md` acceptance gate 1 ("every
   rendered module, API route, event stream, download and persisted
   artefact has a field policy and coverage owner").
2. An adversarial test suite proving no raw value reaches a serialized
   response under the active role, across every §4 surface — adapted from
   `PRIVATE_REPLAY_ARCHITECTURE.md`'s "isolation tests from the agent's
   actual perspective" discipline, retargeted at a curious or malicious
   **human operator** holding this role rather than an agent's tool
   surface, since this design's threat model is a live production console,
   not an isolated replay environment.
3. A route-enumeration or grep-level test proving the browser cannot select,
   toggle, or override the projection (mirroring the existing `AG-J2`/`AG-J3`
   discipline already used for the `E1`–`E6` chain and front-end role
   -blindness).
4. A cross-screen pseudonym-equality test suite proving the same raw
   identity always projects to the same label within whatever scope §12
   item 1/3 ultimately fixes.
5. Resolution, with source evidence, of every §9 search/pagination/export
   question against the actual current controller implementations.
6. Confirmation of §10's caching-layer question against the actual current
   implementation.
7. Explicit Product Owner resolution of every item in §12.
8. Real-environment validation is explicitly **out of scope for this design
   movement** and for its contract-freeze successor: per `AGENTS.md`,
   automated validation and real-environment validation are separate gates,
   and no network-facing or production-facing behaviour from this design
   may be marked `DONE` from automated tests alone. The implementing
   movement owns that gate, after its own contract is frozen.

## 14. Successor implementation sequence (non-binding sketch)

1. **Contract-freeze movement** (`ARCHITECTURE`/`CONTRACT`): resolve §12
   items 1–4 and 8–10, producing a `C`-series-style frozen contract the same
   way `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` and its PO
   decision-record successors were produced. This document, once
   referenced only as a `## Status`/`## Cross-references`-style pointer
   (never as authority, per the contract-status law), is superseded by that
   contract for every clause it resolves.
2. **`RoleToken` addition + projection-stage skeleton**: add the closed
   -vocabulary token (if the frozen contract keeps the additive shape) and
   the single response-assembly funnel (§3), wired to the smallest possible
   read-only, non-exportable surface first, with its field classification
   already resolved by the contract.
3. **Remaining §4 surfaces**, in an order the frozen contract states,
   expanding field-classification coverage surface by surface.
4. **Search/export/download closure** (§9/§12 items 5–6), with the
   adversarial leak tests from §13 item 2 covering each newly-closed path.
5. **Caching interaction** (§10/§12 item 7), only if a caching layer is
   found to exist or is introduced.
6. **Validation**: the full §13 evidence set, followed by human
   real-environment validation per `AGENTS.md`'s evidence laws, before any
   `DONE` status is recorded for any slice above.

No slice above is authorized by this document. Each requires its own
Product Owner authorization at dispatch, per this repository's git authority
and execution law and build lifecycle.

## Cross-references

- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
- `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md`
- `docs/design/PO_DECISION_RECORD_2026_09_15B_SESSION_POLICY_AND_ADMIN_VISIBILITY.md`
- `docs/design/PO_DECISION_RECORD_2026_09_14J_NEXUSADMIN_IS_THE_PRODUCTS_ROOT_IDENTITY.md`
- `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md`
- `docs/design/UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`
- `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md`
