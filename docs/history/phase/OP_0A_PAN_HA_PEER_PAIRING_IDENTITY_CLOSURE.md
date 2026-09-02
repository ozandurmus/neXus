# OP.0a.P7 — PAN HA peer-pairing identity closure (zero new device commands)

## Status

**DRAFT CONTRACT — not yet frozen, not yet implemented.** Prepared for
product-owner / security review and freeze approval before any source change.

Movement that produced it: `ARCHITECTURE` (contract drafting), grounded by a
`READ_ONLY_AUDIT` of the PAN peer-pairing path. Per `docs/AI_DEVELOPMENT_PROTOCOL.md`
reasoning routing, a contract on new scope belongs at **`Sonnet 5, extended
thinking (high)`**; this draft was produced at a lighter tier during an
already-running session — flagged here rather than silently absorbed. Review
the design decisions below with that in mind before freezing.

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

## Why this slice exists now

This session's real-environment retry of the VSX operational-identity
correction (`docs/history/phase/...` VSX commits, same session) found and
fixed three Check Point defects that all shared one shape: **two
independently-computed identity strings for the same physical entity,
invisible to synthetic unit tests, silently degrading evidence-based checks
with no error surfaced.** Root cause in every case was
`checkpoint/scripts/cp_inventory.sh`'s `SAFE_GW=$(echo "$GW" | tr -c
'[:alnum:]_-' '_')` line, which converted `echo`'s own trailing newline into
a literal `"_"` unconditionally for every device — invisible until tested
against real hardware, because every unit test hand-writes clean synthetic
device names.

Auditing the PAN side for the same failure shape (`READ_ONLY_AUDIT`, this
session) found it is **not hypothetical there either** — two concrete,
already-present defects:

1. **`peer_ip` is structurally never populated.**
   `configuration/panorama_config_collector.py::get_target_ha_runtime_state`
   (lines 253–290) issues `show high-availability state` and extracts exactly
   five fields — `enabled`, `state`, `mode`, `peer_state`, `state_sync`. It
   never reads a peer address. Both call sites that build
   `row["ha_runtime"]` (lines ~1450–1496) spread only those five keys. So
   `pan_config_telemetry.json`'s per-device `ha_runtime` **never carries
   `peer_ip`** against real collector output, `extract_pan_ha_runtime`'s
   `peers` dict (`utils/failover_readiness_ui.py:140–142`) is **always
   empty** in production, and `_derive_pan_units`'s peer-matching
   (`utils/failover/assessment.py:580–581`) is dead code against real
   telemetry — every HA-enabled PAN device falls to `pan_ha_peer_unresolved`
   regardless of whether its true peer is correctly inventoried. `OP_0A`'s P7
   contract text describes inference "by matching a device's configured
   `peer-ip`"; the field that inference depends on was never wired end to
   end.
2. **A CP-`tr`-class latent identity divergence.** `panorama_runtime_runner.py`
   parses the managed-device hostname **unstripped**
   (`hostname = d.findtext("hostname")`, line 116; `"hostname": hostname or
   serial`, line 123) and that value becomes `unified.json`'s `device` /
   entity-id field. `configuration/panorama_config_collector.py` parses the
   *same* Panorama API field **stripped**
   (`(entry.findtext("hostname") or serial).strip()`, line 233) into
   `pan_config_telemetry.json`'s `device`/`entity_id`. Both are independent,
   hand-written XML walks over the same response shape — exactly the CP
   `SAFE_GW`-vs-`vsx_runner.py` pattern (one collector sanitizes, one
   doesn't). If Panorama ever returns a hostname with incidental whitespace,
   `_derive_pan_units`'s `pan_ha_runtime.get(entity_id)` lookup
   (`assessment.py:573`) silently returns nothing. Unlike defect 1, this
   isn't even reported as `pan_ha_peer_unresolved` — the `enabled` check at
   line 577 fails first, and the device is **dropped from HA-unit
   consideration entirely, with zero reason code**.

Test coverage confirms both were structurally invisible: every PAN-pairing
test in `tests/test_op0a_ha_readiness.py` (AC-5, lines ~640–736) hand-builds
`pan_ha_runtime`/`pan_ha_peers` dicts directly, bypassing both collectors
entirely, and the one test that exercises `extract_pan_ha_runtime`
(`tests/test_op0c_failover_readiness_ui.py:165–173`) **fabricates** a
`"peer_ip"` key inside `ha_runtime` that the real collector has never
produced — the same "clean fixture papers over a field that doesn't exist in
production output" gap that let the three CP defects reach real-environment
testing before anyone noticed.

## Objective

Make PAN HA peer-pairing **actually resolve pairs against real evidence**,
not just structurally-possible-but-dead-in-practice code, and close the
hostname-divergence risk between the two independent PAN parsers — before
`OP.2`'s identity gate (design §10.1) can depend on this signal, and before
`OP.0b`'s PAN preflight battery is built on top of a pairing mechanism proven
dead in production.

This does **not** attempt PAN's OP.0b preflight battery, does not unify the
two PAN hostname parsers into one shared resolver, and does not add IPv6 peer
matching. See "Explicitly out of scope".

## Scope

### In scope

1. **`configuration/panorama_config_collector.py`** — additive parse of
   `/deviceconfig/high-availability/group/peer-ip` (and, captured
   defensively but not wired into matching yet, `peer-ipv6`) out of the
   running-config XML this collector **already fetches**
   (`get_active_running_config`/`get_direct_active_config`, `type=config
   action=show xpath=/config` — lines 305–390). This is the same document
   `configuration/pan_semantic_policy.py:15–16`'s
   `_MEMBER_SPECIFIC_EXACT_SUFFIXES` already treats as a real, **manually
   validated in this environment** XPath ("narrowly-scoped settings whose
   PAN representation was manually shown to be member-relative in the
   validation environment" — line 12–13). Threaded into
   `row["ha_runtime"]["peer_ip"]` so `extract_pan_ha_runtime`'s `peers` dict
   is populated from real telemetry for the first time.
2. **`panorama/panorama_runtime_runner.py`** — `.strip()` the hostname parse
   (lines 116/123) so it matches `panorama_config_collector.py`'s existing
   `.strip()` (line 233). One-line parity fix that removes the divergence
   risk **at the source**, the same precedent this session's CP `SAFE_GW`
   fix established (fix the injection point, don't just reconcile two keys
   downstream of it).
3. **Regression tests built from a realistic shape** — a
   `pan_config_telemetry.json`/config-XML-derived `peer_ip` produced by
   the actual parse path, not a hand-injected dict key — closing the exact
   fixture-vs-reality gap the audit found.
4. **`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1`** — update the
   `pan_ha_peer_unresolved` note to record that peer identity is now sourced
   from evidence rather than absent, and what (if anything) still blocks
   `OP.2`'s identity gate.
5. Project metadata updates per `AGENTS.md` "Project-state update rule".

### Explicitly out of scope

- **`OP.0b`'s PAN preflight command battery** (`show high-availability all`,
  `state-synchronization`, etc.) — already drafted, un-approved, in
  `OP_0A_HA_READINESS_ASSESSMENT.md`; untouched here.
- **Any new device command.** This is a parse-scope extension of a document
  already fetched (see "Command surface").
- **IPv6 peer matching.** `by_management_ip` (`assessment.py:554–565`) is
  IPv4-string-keyed today; `peer-ipv6` is captured for future-proofing but
  not wired into `_derive_pan_units`'s matching. A real IPv6 design decision
  (address normalization, dual-stack pairing) belongs to its own slice.
- **Unifying the two PAN hostname parsers into one shared resolver.** The
  `.strip()` parity fix closes *this* divergence risk; a `resolve_entity_id`-
  style single PAN identity function (mirroring the CP precedent) is a
  larger refactor recorded as a follow-up, not smuggled in here.
- **Any UI/payload change.** No `templates/`, `static/`, or payload builder
  touched — render harness not triggered, same posture `OP_0A` itself took.

## Design decisions

### Q1 — Source `peer_ip` from the already-fetched config XML, not from a new command, and not from the runtime-state response

Two candidate sources were considered:

- **Running-config XML** (`/deviceconfig/high-availability/group/peer-ip`) —
  already fetched by this exact collector for compliance/drift comparison,
  and already **confirmed real and validated against this customer's real
  environment** by `pan_semantic_policy.py`'s existing, narrowly-scoped
  suffix list. Zero schema risk: the path is proven, not guessed.
- **Runtime-state response** (`show high-availability state`, already issued
  by `get_target_ha_runtime_state`) — PAN-OS's real schema for this command
  typically nests a peer management address under `result/group/peer-info`
  (alongside the `peer-info/state` this collector already reads at line
  282). If present, this would be an even tighter "same command, same
  session" parse-scope extension, mirroring `OP_0A`'s P2 precedent almost
  exactly, and would reflect *live* peer visibility rather than *configured*
  intent — consistent with this collector's own stated principle at line
  265 ("static HA configuration is deliberately not used to infer a role").

**Decision: source from the config XML.** The runtime-state candidate's
exact node name is **not confirmed anywhere in this codebase** — asserting it
without a captured real response would repeat exactly the mistake this
session corrected on the CP side (a plausible-sounding mechanism accepted
without verification). The config-XML path, by contrast, is already
real-device-validated in this repository. If a future real-environment run
confirms a `peer-info` management-address field in the runtime response, that
becomes a second, corroborating source (see "Risks") — not required for this
contract to close.

### Q2 — Fix the hostname-strip divergence at the source, not by reconciling two keys downstream

The CP-side fix for the equivalent problem (`SAFE_GW`'s spurious trailing
`"_"`) went through two stages this session: first a downstream
normalization (`_join_device_key`/`_normalize_cp_entity_key` in
`utils/failover/assessment.py`), then — once the customer confirmed the
underlying artifact was never a real naming convention — a **source fix**
(`SAFE_GW="${SAFE_GW%_}"` in `cp_inventory.sh`) that made the downstream
normalization defensive rather than load-bearing.

This contract applies the lesson directly rather than repeating the two-stage
path: `panorama_runtime_runner.py`'s hostname parse gets `.strip()` added now,
at the point of divergence, so `unified.json` and `pan_config_telemetry.json`
compute the *same* string for the same device from day one. No downstream
join-key normalizer is introduced for PAN, because the fix is one line at the
actual injection point — a normalizer would be defensive plumbing for a risk
this contract removes outright.

### Q3 — Peer resolution stays fail-closed exactly as `OP_0A`'s P7 specified

Nothing about the pairing *rule* changes: a `peer_ip` resolving to zero or to
more than one PAN entity's `management_ip` still yields a single-member unit
with `pan_ha_peer_unresolved` (`assessment.py:592–597`), never a guess. This
contract only makes the `peer_ip` input to that rule real instead of always
absent. `_derive_pan_units` itself needs **no code change** for defect 1 —
only its two upstream inputs (the collector's output, and
`extract_pan_ha_runtime`'s read of it) need to actually carry the field
`assessment.py` already expects and already handles correctly.

### Q4 — Test fixtures must be produced by the real parse path, not hand-assembled

`tests/test_op0c_failover_readiness_ui.py:165–173`'s existing test
fabricates `"peer_ip"` inside a hand-built `ha_runtime` dict — proving
`extract_pan_ha_runtime` reads the key correctly, but never proving the
collector produces it. New tests for this contract must start from a
representative `get_active_running_config`-shaped XML fixture (or the parsed
config-XML structure this collector already works with) and assert the
`peer_ip` value comes out the other end of the actual collector function —
the same "assert against the real pipeline shape, not a shortcut fixture"
discipline the CP-side regression tests (`test_op0a_ha_readiness.py`'s
`test_real_pipeline_shape_survives_cp_vs_vsx_device_name_separator_mismatch`
and its sibling) now follow.

## Command surface

**This contract issues no device command, new or existing.** Both changes
are parse-scope extensions of documents already fetched:

- `peer_ip`: extracted from the running-config XML `get_active_running_config`
  / `get_direct_active_config` already retrieve (`type=config action=show
  xpath=/config`) for every PAN target, for an existing purpose (compliance/
  drift comparison). No new API call, no new session, no new frequency.
- Hostname `.strip()`: a parse-buffer change only, zero device interaction.

Per `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate section:
*"A parse-scope extension of a command the collector already runs (same
command, session, timeout and frequency) is not a command addition and needs
no gate entry."* Both changes here satisfy that test. No `OP.0b` gate entry
is required or implied.

## Correctness contract

1. `pan_config_telemetry.json`'s per-device `ha_runtime` carries `peer_ip`
   (and `peer_ipv6`, unconsumed by matching) whenever the running-config XML
   contains `/deviceconfig/high-availability/group/peer-ip`; `None`/absent
   otherwise — never a guessed or default address.
2. `extract_pan_ha_runtime`'s `peers` dict is populated from that field
   exactly as `_derive_pan_units` already expects — no change to
   `assessment.py`'s pairing logic itself.
3. `unified.json` and `pan_config_telemetry.json` compute byte-identical
   `device`/entity-id strings for the same managed PAN device, for any
   hostname value returned by Panorama's managed-device-discovery API
   (whitespace-insensitive).
4. A `peer_ip` resolving to zero or to more than one in-scope PAN entity's
   `management_ip` still yields `pan_ha_peer_unresolved` — never a guessed or
   silently-merged pair (unchanged from `OP_0A` P7).
5. A device whose `ha_runtime` lookup previously failed silently due to the
   hostname-strip divergence (defect 2) now either resolves correctly or, if
   still absent for a genuine evidence gap, is omitted with the same "not an
   HA unit" posture `_derive_pan_units` already uses for HA-disabled devices
   — never a new failure mode.
6. No existing CP-side behavior changes. This contract touches no CP file.

## Privacy and safety invariants

- `peer_ip` is a management-plane IP address, the same sensitivity class
  `management_ip` already carries in `unified.json` and
  `ha_readiness.json` today — no new category of sensitive data is
  introduced.
- No hostname, serial number, or raw device/API output beyond the existing
  `ha_readiness.json` field set (`entity_id`, unit id, vendor, mode,
  per-check status/reason) enters any state file as a result of this
  contract.
- The repository privacy gate stays **PASS / 0**.
- No new credential, transport, or network-access pattern.

## Implementation plan

1. `configuration/panorama_config_collector.py`: extend the config-XML
   parse path that already produces compliance/drift evidence to also
   extract `/deviceconfig/high-availability/group/peer-ip` (+ `-ipv6`) per
   target, threading the value into `row["ha_runtime"]["peer_ip"]` /
   `["peer_ipv6"]` alongside the five existing `get_target_ha_runtime_state`
   fields. Fail-closed: absent path → `None`, never a fabricated address.
2. `panorama/panorama_runtime_runner.py`: add `.strip()` to the hostname
   parse (line 116/123), matching `panorama_config_collector.py:233`
   byte-for-byte in normalization behavior.
3. `utils/failover_readiness_ui.py::extract_pan_ha_runtime`: confirm (add a
   test rather than a code change, since the function already reads
   `ha.get("peer_ip")` at line 140) that it now receives a real value.
4. Tests: a realistic config-XML-shaped fixture proving `peer_ip` survives
   the collector's own parse path into `pan_config_telemetry.json`'s shape;
   a hostname-with-incidental-whitespace fixture proving `unified.json` and
   `pan_config_telemetry.json` now agree; an end-to-end
   `compute_ha_readiness` test pairing two PAN entities from that
   realistically-sourced `peer_ip` (replacing/augmenting the fabricated-key
   test at `test_op0c_failover_readiness_ui.py:165–173`).
5. `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1: update the
   `pan_ha_peer_unresolved` note.
6. Project metadata: `CURRENT_STATE.md`, `project/roadmap.json`,
   `project/backlog.json`, `project/feature_registry.json`,
   `project/build_history.json`.

Expected footprint: 2 source files + 1 design-doc update + 1 test file —
within the protocol's default build size, matching `OP_0A`'s own footprint
category.

## Acceptance criteria

- **AC-1** Config-XML `peer-ip` extraction: a fixture running-config XML
  containing `/deviceconfig/high-availability/group/peer-ip` →
  `row["ha_runtime"]["peer_ip"]` carries that exact value; a fixture without
  the path → `None`, never guessed.
- **AC-2** Hostname parity: for a managed-device-discovery XML response whose
  `hostname` text node carries incidental leading/trailing whitespace,
  `panorama_runtime_runner.py`'s parsed hostname and
  `panorama_config_collector.py`'s parsed hostname are byte-identical.
- **AC-3** Real-pipeline PAN pairing: two PAN entities whose config XML
  cross-references each other's `management_ip` via `peer-ip`, run through
  the actual collector parse path (not a hand-built runtime dict), resolve
  into one `pan_ha_pair` unit via `compute_ha_readiness`.
- **AC-4** Fail-closed unchanged: a `peer_ip` resolving to zero or to more
  than one entity still yields `pan_ha_peer_unresolved`, proven against the
  same real-pipeline-shaped fixtures as AC-3, not a re-derivation of the
  existing hand-built-dict tests.
- **AC-5** No CP-side regression: the full CP test suite (`test_op0a_ha_readiness.py`
  and siblings) is unaffected — this contract touches no CP file.
- **AC-6** No new device command: reviewable as the absence of any new
  command string in the diff; the config-XML fetch call sites
  (`get_active_running_config`/`get_direct_active_config`) are unchanged in
  their request shape.
- **AC-7** Privacy: no new sensitive-data category in `ha_readiness.json` or
  `pan_config_telemetry.json` across the full fixture fleet.

## Validation and merge gate

- Full suite one-shot, file-backed: `py -m pytest -q > pytest_result.log 2>&1`.
  Baseline to beat: the count in effect at freeze time (record the exact
  figure when this contract is frozen); zero new failures.
- Repository privacy gate **PASS / 0**.
- Render harness: **not triggered** — no `templates/`, `static/`, or payload
  builder touched.
- **Real-environment validation:** required before `DONE`, same posture as
  `OP_0A`'s own P2 mode-parse caveat. The config-XML `peer-ip` XPath is
  validated in this environment for semantic-policy purposes but has **never
  been exercised for this extraction path** — the first real PAN HA pair run
  through this collector should confirm `peer_ip` resolves to a real address
  and that pairing succeeds end to end, not just that the parse doesn't
  crash on a synthetic fixture. Record as `on_hardware_real_env_validation`.

## Risks

- **PAN-OS/Panorama schema drift.** The config-XML `peer-ip` path is
  confirmed for this environment's current PAN-OS/Panorama version via
  `pan_semantic_policy.py`'s existing validated suffix list, but is not
  guaranteed stable across major PAN-OS releases. Mitigated fail-closed:
  absent path → `None` → `pan_ha_peer_unresolved`, never a wrong address.
- **The runtime-state `peer-info` candidate (Q1) remains unconfirmed.** If a
  future real-environment capture of `show high-availability state` shows a
  peer management-address field there, it is a stronger, tighter-scoped
  source and should be added as a corroborating/preferred source in a
  follow-up — not assumed present by this contract.
- **This closes two specific defects, not the general two-parser
  duplication.** `panorama_runtime_runner.py` and
  `panorama_config_collector.py` remain two independent hand-written XML
  walks over the same Panorama API shapes. The `.strip()` parity fix removes
  *this* divergence; any *other* field the two parsers compute differently
  (case, other whitespace classes, future fields) is not audited by this
  contract. A shared PAN identity resolver (CP's `resolve_entity_id`
  precedent) is the durable fix, recorded as a follow-up.
- **IPv6 peer pairing remains absent.** `peer_ipv6` is captured but not
  wired into matching (Q1/scope). A dual-stack PAN estate gets no pairing
  benefit from this contract.

## Rollback

Revert the `peer_ip`/`peer_ipv6` extraction in
`configuration/panorama_config_collector.py` and the `.strip()` change in
`panorama/panorama_runtime_runner.py`. Both are purely additive/normalizing —
no stored schema migration, no existing field removed or renamed, so nothing
downstream needs to migrate. `pan_config_telemetry.json` and `ha_readiness.json`
are runtime state and may be regenerated.

## Definition of done

1. AC-1 … AC-7 green.
2. Full suite at or above the freeze-time baseline; privacy gate PASS / 0.
3. No new device command issued anywhere in the diff.
4. Project metadata updated.
5. Real-environment confirmation of `peer_ip` resolution recorded on
   `on_hardware_real_env_validation` before status advances past
   `AUTOMATED_VALIDATED`.
6. `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 updated to reflect
   the closed (or narrowed) `pan_ha_peer_unresolved` gap.

## Next movement / model

`CONTRACT` freeze decision first (human/product-owner review of this
document — the Q1 source-XPath decision in particular). Once frozen:
`IMPLEMENTATION` at **`Sonnet 5, normal`** — the hard calls (Q1's source
choice, Q2's fix-at-source precedent, Q3's unchanged fail-closed rule) are
all decided above and each is pinned by an acceptance criterion; what
remains is a bounded XML-parse extension, a one-line strip fix, and
realistically-shaped tests, the same "high reasoning to decide, normal
reasoning to implement" shape `OP_0A` itself used.
