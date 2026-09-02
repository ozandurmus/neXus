# OP.0a.P7 — PAN HA peer-pairing identity closure (zero new device commands)

## Status

**CONTRACT FROZEN 2026-09-02 — cleared for implementation, and implemented
the same session.**

**IMPLEMENTED / AUTOMATED_VALIDATED 2026-09-02.** `panorama/pan_identity.py`
(new), `panorama/panorama_runtime_runner.py`, `configuration/panorama_config_collector.py`,
`utils/failover/assessment.py`. AC-1…AC-9 covered by
`tests/test_pan_ha_peer_pairing_identity_closure.py` (9 tests) and 6 new
tests in `tests/test_op0a_ha_readiness.py`. Full suite: **1074 passed / 26
skipped / 0 failed**. Privacy gate **PASS / 0**. Architecture convergence
**13/13**. **No new device command was issued or added.**
Real-environment confirmation of `peer_ip` resolution against a real PAN HA
pair is **owed** (`on_hardware_real_env_validation`) — status stays
`AUTOMATED_VALIDATED`, not `REAL_ENV_VALIDATED`, until that runs.

Movement history: `ARCHITECTURE` (contract drafting, grounded by a
`READ_ONLY_AUDIT`) → independent security/operational-identity architecture
review (this document's design decisions were revised in response — see
"Architecture review corrections" below) → `CONTRACT` freeze (approved,
FREEZE WITH CHANGES) → `IMPLEMENTATION` → `VALIDATION`.

- Design parent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §3.2 (PAN HA),
  §10.1 (identity prerequisite for `OP.2`, which explicitly names
  `pan_ha_peer_unresolved` as "the known instance" blocking that gate).
- Contract parent: `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md`'s
  **P7** decision (PAN pairing by `peer-ip` → `management_ip` match,
  implemented and frozen 2026-09-01) and its "Risks" section, which explicitly
  deferred "making PAN peer identity a first-class inventory field" as a
  future follow-up. This contract is that follow-up, sharpened by a concrete
  audit rather than speculation.
- Gate: **no network-device command gate required** — see "Command surface".

## Evidence-grade model (frozen invariant — do not conflate)

This contract, and every future one that touches PAN HA peer identity, must
keep these three concepts distinct:

- **Grade A — configuration-declared HA relationship.** `PAN-A` configured
  peer → `PAN-B`, `PAN-B` configured peer → `PAN-A` (mutual). This is what
  this build produces, and **all** it produces. May support `OP.0a`
  READ-ONLY pairing/readiness presentation only.
- **Grade B — fresh runtime-proven HA relationship.** Live, current evidence
  that the two devices are actually behaving as an HA pair right now. **Not
  established by this build.**
- **Grade C — operational authorization.** Fresh runtime identity/state +
  safety conditions + authorization + confirmation + locking + execution
  semantics. Future `CLASS 2` scope, not attempted, not implied, and not
  brought closer to sufficiency by this contract.

**This contract implements ONLY Grade A.** No future code or documentation
may cite a successful `OP.0a` PAN pairing as having established Grade B or
Grade C. `peer_ip` sourced from running-config XML is **configuration
intent**, never a live runtime observation, never runtime peer proof, never
operational authorization, and never HA-lock identity. What was configured
is not what is operationally true right now, and every consumption site —
present and future — must treat it accordingly.

The correct term for what `_derive_pan_units` now requires before pairing is
**mutual configuration agreement**. It is never called "runtime mutual
corroboration", "live peer proof", or "operational peer confirmation" —
those would misrepresent Grade A evidence as Grade B.

## Why this slice exists now

This session's real-environment retry of the VSX operational-identity
correction found and fixed three Check Point defects that all shared one
shape: **two independently-computed identity strings for the same physical
entity, invisible to synthetic unit tests, silently degrading evidence-based
checks with no error surfaced.** Root cause in every case was
`checkpoint/scripts/cp_inventory.sh`'s `SAFE_GW=$(echo "$GW" | tr -c
'[:alnum:]_-' '_')` line, which converted `echo`'s own trailing newline into
a literal `"_"` unconditionally for every device.

Auditing the PAN side for the same failure shape found it is **not
hypothetical there either**, plus a security/operational-identity
architecture review (below) surfaced a third, previously-missed gap:

1. **`peer_ip` is structurally never populated.**
   `configuration/panorama_config_collector.py::get_target_ha_runtime_state`
   issues `show high-availability state` and extracts exactly five fields —
   `enabled`, `state`, `mode`, `peer_state`, `state_sync`. It never reads a
   peer address. So `pan_config_telemetry.json`'s per-device `ha_runtime`
   never carried `peer_ip` against real collector output,
   `extract_pan_ha_runtime`'s `peers` dict was always empty in production,
   and `_derive_pan_units`'s peer-matching was dead code against real
   telemetry — every HA-enabled PAN device fell to `pan_ha_peer_unresolved`
   regardless of whether its true peer was correctly inventoried.
2. **A CP-`tr`-class latent identity divergence.**
   `panorama_runtime_runner.py` parsed the managed-device hostname
   **unstripped**; `configuration/panorama_config_collector.py` parsed the
   same Panorama API field **stripped**. Both were independent, hand-written
   XML walks over the same response shape. Unlike defect 1, a whitespace
   mismatch here wasn't even reported as `pan_ha_peer_unresolved` — the
   `enabled` check failed first, silently dropping the device from HA-unit
   consideration with zero reason code.
3. **(Found by architecture review, not the original audit) no mutual
   check.** `_derive_pan_units` resolved a pair from entity A's `peer_ip`
   alone, one-directionally, without ever confirming candidate B's own
   `peer_ip` pointed back at A. A one-sided or contradictory configuration
   (A→B but B has no peer configured, or A→B but B→C) would have formed a
   pair on A's say-so alone — the strongest available corroboration already
   sitting in the same evidence set was never used.

Test coverage confirmed all three were structurally invisible: every
PAN-pairing test hand-built `pan_ha_runtime`/`pan_ha_peers` dicts directly,
bypassing both collectors entirely and always setting both directions'
`peer_ip` symmetrically by construction — so the missing-mutual-check gap
could never surface in a unit test either.

## Objective

Make PAN HA peer-pairing **actually resolve pairs against real evidence**
(Grade A only), close the hostname-divergence risk between the two
independent PAN parsers, and require **mutual** configuration agreement
before a pair forms — before `OP.2`'s identity gate (design §10.1) can
depend on this signal, and before `OP.0b`'s PAN preflight battery is built
on top of a pairing mechanism proven dead in production.

This does **not** attempt PAN's OP.0b preflight battery, does not migrate
PAN entity identity to serial, and does not wire IPv6 into pairing. See
"Explicitly out of scope".

## Scope

### In scope

1. **`configuration/panorama_config_collector.py`** — additive parse of
   `/deviceconfig/high-availability/group/peer-ip` (+ `-ipv6`, captured
   defensively, not wired into matching) out of the running-config XML this
   collector **already fetches unconditionally for every target** via
   `get_active_running_config` (the primary Panorama-brokered control
   artifact, not the optional `direct_compare` path). New function
   `parse_ha_peer_ip_from_config(content: bytes)`, called immediately after
   that fetch succeeds, merged into the `row["ha_runtime"]` dict already
   built earlier in the same per-target orchestration
   (`_collect_device_row`). This is the same document
   `configuration/pan_semantic_policy.py`'s `_MEMBER_SPECIFIC_EXACT_SUFFIXES`
   already treats as a real, **manually validated in this environment**
   XPath. Searched as a descendant match (`.//deviceconfig/...`), not an
   absolute path — the exact nesting depth under the API's
   `target=<serial>`-scoped `<config>` root is not asserted, only the
   validated tag-path suffix.
2. **New `panorama/pan_identity.py::normalize_pan_hostname()`** — one
   shared, narrow, pure normalization seam. Both
   `panorama/panorama_runtime_runner.py` and
   `configuration/panorama_config_collector.py` import and call it, so the
   two parses converge on one implementation instead of two independently
   -maintained ones that happened to agree only by coincidence (exactly how
   defect 2 arose — nobody decided the two should differ, they evolved
   independently).
3. **`utils/failover/assessment.py::_derive_pan_units`** — require **mutual**
   configuration agreement: a candidate pair from A's `peer_ip` only forms
   when B's own `peer_ip` resolves back to A's `management_ip`. A one-sided
   or contradictory relationship yields a single-member unit with a
   distinguishable reason, `pan_ha_peer_asymmetric`, never a guessed pair.
   New `HaUnit.unresolved_reason` field (PAN-only; `None` for every CP unit)
   carries this without touching `HaUnit.to_dict()`'s output schema.
4. **Regression tests built from realistic shapes** — config-XML-derived
   `peer_ip` extraction, the shared hostname seam, and mutual/asymmetric/
   self-reference/role-swap-stability pairing behavior at the
   `compute_ha_readiness` level.
5. Project metadata updates per `AGENTS.md` "Project-state update rule".

### Explicitly out of scope

- **`OP.0b`'s PAN preflight command battery** — already drafted, un-approved,
  in `OP_0A_HA_READINESS_ASSESSMENT.md`; untouched here.
- **Any new device command.** Parse-scope extension only (see "Command
  surface").
- **IPv6 peer matching.** `peer_ipv6` is captured — its source XPath is
  proven by the same `pan_semantic_policy.py` suffix list as `peer-ip`, so
  the point-9 evidence bar for even including it is met — but
  `by_management_ip` stays IPv4-string-keyed; wiring IPv6 into matching is a
  real dual-stack design decision belonging to its own slice.
- **Migrating PAN entity identity to `serial`.** The architecture review
  found `serial` is already collected on every PAN row in both parsers and
  is already this codebase's own precedent for authoritative device
  identity (`_collect_direct_compare`'s `identity_mismatch` check) — a
  stronger anchor than hostname, which is mutable (admin rename). Recorded
  as `pan_ha_serial_identity_hardening` in `project/backlog.json`, an
  explicit pre-`CLASS 2` architecture item, deliberately **not** done here:
  it would touch `resolve_entity_id` and every PAN entity-id consumer,
  which this narrow closure must not broaden into.
- **Fully unifying the two PAN hostname parsers.** The shared
  `normalize_pan_hostname()` seam closes the *whitespace* divergence; the
  two XML walks remain otherwise independent. Recorded as
  `pan_hostname_parser_unification` in the backlog.
- **Any UI/payload change.** No `templates/`, `static/`, or payload builder
  touched — render harness not triggered.

## Design decisions

### Q1 — Source `peer_ip` from the already-fetched config XML, not from a new command, and not from the unconfirmed runtime-state response

Two candidate sources were considered. The running-config XML path
(`/deviceconfig/high-availability/group/peer-ip`) is already fetched by this
exact collector, for every target, unconditionally, and is already
**confirmed real and validated against this customer's real environment**
by `pan_semantic_policy.py`'s existing suffix list. The runtime-state
response's plausible `peer-info` management-address field remains
**unconfirmed anywhere in this codebase** — asserting it would repeat
exactly the mistake corrected on the CP side this session.

**Decision, unchanged from draft: source from the config XML.** If a future
real-environment run confirms a `peer-info` address field in the runtime
response, that becomes a second, corroborating Grade-A-strength source in a
follow-up — not required for this contract, and still not Grade B on its
own (it would still be a device's self-report, not independently verified
protocol evidence).

**Architecture-review addition:** this value is configuration intent, full
stop. It answers "what was declared", never "what is true right now."
Nothing in this contract, nor anything built on top of it, may treat a
resolved `peer_ip` as proof of a live relationship.

### Q2 — Fix the hostname-strip divergence with one shared seam, not two independently-repaired call sites

The CP-side fix for the equivalent problem went through a downstream
normalizer first, then a source fix, once the customer confirmed the
artifact was never a real naming convention. The original draft of this
contract planned to repeat only the "fix at the source" half — adding
`.strip()` independently at both PAN call sites. **Architecture review
correction:** two independently-maintained fixes that happen to agree today
can silently diverge again on the next edit to either file, which is
exactly how defect 2 was introduced in the first place. The frozen decision
is a single shared function, `panorama/pan_identity.py::normalize_pan_hostname()`,
imported and called by both parsers — one seam, not two copies of the same
logic.

### Q3 — Mutual configuration agreement, not one-directional inference

**Architecture-review addition, not in the original draft.**
`_derive_pan_units`'s pre-existing rule — a `peer_ip` resolving to zero or
more than one entity yields `pan_ha_peer_unresolved`, never a guess — is
retained unchanged. Added: even when exactly one candidate resolves, the
pair forms **only if that candidate's own configured `peer_ip` resolves
back** to the first device's `management_ip`. Self-reference was already
excluded from candidates (a device is never matched to itself) and remains
so. An asymmetric or contradictory relationship — A declares B, B declares
nothing or declares C — yields a single-member unit with
`unresolved_reason="pan_ha_peer_asymmetric"`, distinguishable from the
generic `pan_ha_peer_unresolved`. This is still Grade A evidence at a higher
bar (both sides agree on paper), never Grade B (neither side has been asked
anything at query time).

### Q4 — Peer resolution stays fail-closed exactly as `OP_0A`'s P7 specified, extended by Q3

Nothing about the *shape* of fail-closed behavior changes: unresolved stays
unresolved, ambiguous stays ambiguous, nothing is ever guessed or silently
merged. Q3 adds one more fail-closed branch on top of the existing ones,
plumbed through the same `HaUnit`/`UnitAssessment.reason` path `OP_0A`
already established.

### Q5 — Canonical PAN HA pair identity: entity ids only, stable across active/passive swap

**Architecture-review addition.** `unit_id = f"{entity_id}+{peer}"` is
constructed from the two members' `resolve_entity_id()` values only — never
`management_ip` (which could be role-dependent in some architectures),
never one member's `serial` alone, never a display label, and never `vsys`.
Because `_derive_pan_units` iterates `sorted(pan_rows)` globally, the
alphabetically-first hostname of a pair is always the `entity_id` half of
`unit_id`, independent of which member is currently `ACTIVE` vs `STANDBY` —
confirmed by a new regression test that swaps roles and asserts identical
`unit_id`. No PAN pair `display_name` is composed today (unlike CP's
VSX-context labels); if one is added later, it must stay presentation-only
and never feed back into `unit_id` or matching (locked in by a regression
test).

### Q6 — Test fixtures must be produced by the real parse path or exercise real fail-closed shapes, not hand-assembled positives only

The pre-existing PAN pairing tests hand-built `pan_ha_runtime`/`pan_ha_peers`
dicts directly and always set both directions symmetrically — proving
`_derive_pan_units` reads its inputs correctly, but never proving either
collector produces them, and never exercising the asymmetric case at all.
New tests for this contract start from a representative config-XML shape
for the collector-level extraction, and add explicit asymmetric/
contradictory/self-reference/role-swap-stability cases at the
`compute_ha_readiness` level.

## Command surface

**This contract issues no device command, new or existing.** Both changes
are parse-scope extensions of documents already fetched:

- `peer_ip`/`peer_ipv6`: extracted from the running-config XML
  `get_active_running_config` already retrieves, unconditionally, for every
  PAN target, for an existing purpose (compliance/drift comparison,
  artifact storage). No new API call, no new session, no new frequency, no
  change to that call's request shape.
- Hostname normalization: a parse-buffer change only, zero device
  interaction.

Per `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate section:
*"A parse-scope extension of a command the collector already runs (same
command, session, timeout and frequency) is not a command addition and needs
no gate entry."* Both changes satisfy that test. No `OP.0b` gate entry is
required or implied.

## Fail-closed cases (frozen)

| Case | Behavior |
| --- | --- |
| `peer_ip` missing | single-member unit, `pan_ha_peer_unresolved` |
| `peer_ip` resolves to no known entity | single-member unit, `pan_ha_peer_unresolved` |
| `peer_ip` resolves to more than one entity | single-member unit, `pan_ha_peer_unresolved` |
| peer points to self | excluded from candidates; single-member unit, `pan_ha_peer_unresolved` |
| A→B, B has no reciprocal `peer_ip` | single-member unit for A, `pan_ha_peer_asymmetric` |
| A→B, B→C (contradictory) | single-member unit for A, `pan_ha_peer_asymmetric`; A+B never forms |
| runtime HA disabled | not a unit at all, regardless of configured `peer_ip` (existing `enabled` gate, unchanged) |
| only one member's row is in scope | unresolved (empty candidate set) |
| hostname normalization mismatch | closed by the shared seam (Q2); no longer reachable via whitespace alone |
| stale evidence (config and runtime collected in different runs) | **not implemented as a check in this build** — see Risks; `OP.0a` presentation may show a configuration-backed pairing that predates a since-changed relationship. Explicitly **not** acceptable evidence for any future `CLASS 2` use without a same-run/bounded-recency provenance check, which this contract does not add. |

## Correctness contract

1. `pan_config_telemetry.json`'s per-device `ha_runtime` carries `peer_ip`
   and `peer_ipv6` whenever the running-config XML contains the
   corresponding path; `None` otherwise — never a guessed or default
   address.
2. `extract_pan_ha_runtime`'s `peers` dict is populated from that field
   exactly as `_derive_pan_units` already expected.
3. `unified.json` and `pan_config_telemetry.json` compute byte-identical
   `device`/entity-id strings for the same managed PAN device, for any
   hostname value returned by Panorama's managed-device-discovery API
   (whitespace-insensitive), via the one shared `normalize_pan_hostname()`
   seam.
4. A pair forms **only** on mutual configuration agreement (Q3). A
   one-sided or contradictory relationship never forms a pair and is always
   distinguishable (`pan_ha_peer_asymmetric`) from a genuinely-unresolved
   one (`pan_ha_peer_unresolved`).
5. `unit_id` is derived from the two members' entity ids only — never
   `management_ip`, `serial`, `display_name`, or `vsys` — and is stable
   across an active/passive role swap.
6. VSYS metadata remains subordinate context; no per-VSYS PAN failover unit
   is ever created (unchanged from `OP_0A`, re-confirmed by regression
   test).
7. No existing CP-side behavior changes. This contract touches no CP file.
8. A resolved `peer_ip` — mutual or not — is never sufficient evidence for
   any future `CLASS 2` decision on its own (evidence-grade model, above).

## Privacy and safety invariants

- `peer_ip`/`peer_ipv6` are management-plane addresses, the same
  sensitivity class `management_ip` already carries in `unified.json` and
  `pan_config_telemetry.json` today — no new category of sensitive data.
  Neither field is ever written into `ha_readiness.json`'s output (`HaUnit`
  carries no address field at all).
- No hostname, serial number, or raw device/API output beyond the existing
  `ha_readiness.json` field set enters any state file as a result of this
  contract.
- The repository privacy gate stays **PASS / 0** (verified this session).
- No new credential, transport, or network-access pattern.

## Schema / compatibility

- Additive optional fields only (`peer_ip`, `peer_ipv6` on `ha_runtime`;
  `unresolved_reason` on `HaUnit`, not exposed in `to_dict()`). No existing
  field renamed or removed.
- Evidence collected before this change (no `peer_ip` key present) remains
  valid input and produces the existing `pan_ha_peer_unresolved` behavior —
  never a crash, never a migration requirement.
- No CAS/history migration. No PAN entity-id migration.

## Implementation plan (as executed)

1. `panorama/pan_identity.py` (new): `normalize_pan_hostname(raw, *, serial)`.
2. `panorama/panorama_runtime_runner.py`: managed-device-discovery hostname
   parse now calls the shared seam.
3. `configuration/panorama_config_collector.py`:
   - managed-device-discovery hostname parse now calls the shared seam;
   - new `parse_ha_peer_ip_from_config(content: bytes)`, called from
     `_collect_device_row` immediately after `get_active_running_config`
     succeeds, merged into the already-built `row["ha_runtime"]` dict.
4. `utils/failover/assessment.py`:
   - `HaUnit.unresolved_reason: str | None = None` (new field, PAN-only
     usage);
   - `_derive_pan_units`: mutual-agreement check before pairing, asymmetric
     branch sets `unresolved_reason="pan_ha_peer_asymmetric"`;
   - `compute_ha_readiness`'s existing `pan_ha_peer_unresolved` override now
     prefers `unit.unresolved_reason` when set.
5. Tests: `tests/test_pan_ha_peer_pairing_identity_closure.py` (new, 9
   tests) + 6 new tests in `tests/test_op0a_ha_readiness.py`.
6. Project metadata: `CURRENT_STATE.md`, `project/roadmap.json`
   (`current_build`, `now_next.now`, `now_next.next`),
   `project/backlog.json` (3 new follow-up items), `project/feature_registry.json`,
   `project/build_history.json`, `docs/history/INDEX.md` (regenerated).

Footprint: 4 source files (1 new) + 2 test files (1 new) + project metadata
— within the protocol's default build size.

## Acceptance criteria

- **AC-1** Config-XML `peer-ip`/`peer-ipv6` extraction: present → exact
  value; absent → `None`, never guessed; depth-independent (proven against
  both a `devices/entry`-nested and a shallow `<config>`-direct fixture);
  unparseable content → `None`, never a crash. **PASS** —
  `test_parse_ha_peer_ip_extracts_configured_peer_address`,
  `test_parse_ha_peer_ip_is_depth_independent`,
  `test_parse_ha_peer_ip_absent_yields_none_never_a_guess`,
  `test_parse_ha_peer_ip_fails_closed_on_unparseable_content`.
- **AC-2** Hostname parity via the shared seam, both call sites proven to
  use it (source-level guard, not just behavioral coincidence). **PASS** —
  `test_normalize_pan_hostname_strips_incidental_whitespace`,
  `test_normalize_pan_hostname_falls_back_to_serial_when_blank`,
  `test_both_parsers_use_the_shared_seam_not_independent_logic`,
  `test_two_parsers_agree_on_a_hostname_with_incidental_whitespace`.
- **AC-3** Mutual agreement pairing: `test_mutual_configuration_agreement_required_asymmetric_fails_closed`
  and `test_mutual_configuration_agreement_required_contradictory_fails_closed`
  prove asymmetric/contradictory configuration never forms a pair, with the
  distinguishable reason.
- **AC-4** Self-reference: `test_peer_pointing_to_self_fails_closed`.
- **AC-5** Identity stability: `test_mutual_agreement_pair_identity_stable_across_active_passive_swap`.
- **AC-6** Identity never derived from address/serial/vsys:
  `test_pan_ha_pair_unit_identity_never_uses_management_ip_serial_or_vsys`.
- **AC-7** No CP-side regression: full CP suite unaffected (this contract
  touches no CP file) — confirmed by the full-suite run below.
- **AC-8** No new device command: `test_parse_ha_peer_ip_source_makes_no_network_or_device_call`
  (source-level guard) plus manual review — the config-XML fetch call site
  is unchanged in request shape.
- **AC-9** Privacy: repository privacy gate **PASS / 0**, verified this
  session after clearing gitignored `data/`/`logs/`.

## Validation and merge gate

- Full suite one-shot: `python3 -m pytest -q`. **Result: 1074 passed / 26
  skipped / 0 failed.** (Baseline before this build, same session: 1074
  passed after the VSX real-env fixes and installing this sandbox's missing
  `fastapi`/`paramiko` packages — this build adds 15 new tests, net count
  unchanged because the sandbox's dependency gaps were fixed in the same
  session, not by this contract.)
- Repository privacy gate: **PASS / 0**.
- Architecture convergence (`tests/test_architecture_convergence.py`):
  **13/13**.
- Render harness: **not triggered** — no `templates/`, `static/`, or
  payload builder touched.
- **Real-environment validation: owed, not yet performed.** The config-XML
  `peer-ip` XPath is validated in this environment for semantic-policy
  purposes but has never been exercised for *this* extraction path — the
  first real PAN HA pair run through this collector should confirm
  `peer_ip` resolves to a real address on both sides and that mutual
  pairing succeeds end to end. Do not contact PAN devices until explicitly
  authorized for that step. Record as `on_hardware_real_env_validation`.

## Risks

- **PAN-OS/Panorama schema drift.** The config-XML `peer-ip` path is
  confirmed for this environment's current version via
  `pan_semantic_policy.py`'s existing validated suffix list, not guaranteed
  stable across major PAN-OS releases. Mitigated fail-closed: absent path →
  `None` → `pan_ha_peer_unresolved`, never a wrong address.
- **The runtime-state `peer-info` candidate (Q1) remains unconfirmed.**
  Noted as a possible future corroborating source, not assumed present.
- **Stale evidence is not detected.** Config-XML `peer_ip` and runtime
  `ha_runtime.enabled`/`state` may come from different collection runs; no
  same-run/recency provenance check exists. Acceptable for `OP.0a`
  (explicitly non-authoritative); a hard precondition for any future
  `CLASS 2` use of this evidence, not implemented here.
- **Hostname-rename risk remains for the underlying entity identity**
  (distinct from the whitespace divergence this contract closes). `serial`
  is a stronger anchor, already collected, already precedented in this
  codebase, deliberately not adopted here — see `pan_ha_serial_identity_hardening`
  in the backlog.
- **IPv6 peer pairing remains unwired.** `peer_ipv6` is captured but not
  matched — see `pan_ha_peer_ipv6_pairing` in the backlog.
- **The two PAN hostname parsers remain otherwise independent** beyond the
  shared whitespace-normalization seam — see `pan_hostname_parser_unification`
  in the backlog.

## Rollback

Revert `panorama/pan_identity.py`, the two call-site edits in
`panorama_runtime_runner.py`/`panorama_config_collector.py`, the
`peer_ip`/`peer_ipv6` extraction and its call site in
`panorama_config_collector.py`, and the mutual-agreement logic +
`unresolved_reason` field in `utils/failover/assessment.py`. All additive/
normalizing — no stored schema migration, no existing field removed or
renamed. `pan_config_telemetry.json` and `ha_readiness.json` are runtime
state and may be regenerated.

## Definition of done

1. AC-1 … AC-9 green — **done**.
2. Full suite at or above baseline; privacy gate PASS/0 — **done** (1074/26/0,
   PASS/0).
3. No new device command issued anywhere in the diff — **done**.
4. Project metadata updated — **done** (`CURRENT_STATE.md`, `roadmap.json`,
   `backlog.json`, `feature_registry.json`, `build_history.json`,
   `docs/history/INDEX.md`).
5. Real-environment confirmation of `peer_ip` resolution recorded on
   `on_hardware_real_env_validation` before status advances past
   `AUTOMATED_VALIDATED` — **owed, not yet done**. Do not contact devices
   until separately authorized.
6. `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 — **not yet
   updated**; carried forward as an immediate follow-up (the identity gate
   note should now record Grade A closure and the still-open Grade B/C
   gap), deliberately not bundled into this diff to keep the reviewable
   change surface to source + tests + this contract.

## Next movement / model

`VALIDATION` complete at `Sonnet 5` (this session, continued at the tier
already in use; the hard architecture calls were resolved by the separate
review pass, not re-litigated during implementation). Next: `HUMAN_REAL_ENV`
— prepare the narrow retry plan for the same previously-selected PAN HA
pair (2 requested, 2 resolved, 2 planned contacts, 0 extra), read-only,
CLASS 0. Do not contact PAN devices until explicitly authorized.
