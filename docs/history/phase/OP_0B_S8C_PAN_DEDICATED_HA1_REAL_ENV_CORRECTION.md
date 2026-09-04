# OP.0b S8-C — PAN dedicated-HA1 pairing correction, real-env validation, OP.0b read-only closure

## Status

**S8-C: REAL_ENV_VALIDATED, 2026-09-04.** Corrected code merged to the
working branch, validated on the approved real PAN HA pair. **OP.0b:
READ-ONLY SCOPE CLOSED, 2026-09-04** for S1–S8 (S9 UI-authority
reconciliation explicitly NOT closed — see "OP.0b closure assessment"
below).

## What this session found

The first live `--pan-ha-preflight-check` run against the approved real PAN
pair failed before contacting either device:

```
PreflightTargetResolutionError: pan_preflight_targets: selected targets do
not resolve to exactly one known operational HA entity (pair identity B2
remains unresolved for this selection), refusing to contact any device
```

`application/workflows/preflight.py::_resolve_pan_operational_entity`
required the operator's two explicitly-selected candidates to already match
a unit `utils.failover.assessment._derive_pan_units` independently derived
from stored config-intent evidence — and that derivation's only Grade-A
pairing test was `A.configured_peer_ip == B.management_ip` (contract
OP.0a.P7, `docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md`).

A value-free real-env diagnostic (the existing, previously-unexercised
`--pan-ha-peer-diagnostic` opt-in flag) confirmed, for both approved
members: HA enabled, a configured HA1 peer address present, self-identity
internally consistent — and the configured peer address matched neither
member's management address, symmetrically on both sides. That is the
signature of a **dedicated HA1 control-link** topology (peer-ip configured
on a link separate from the management interface), not a misconfiguration.
Manually captured `show high-availability all` output (outside the approved
S8-C battery, not used as runtime authority) confirmed the actual address
planes: management, primary HA1, backup HA1 and HA2 are four genuinely
distinct addresses on this pair.

**REAL_ENV_DISPROVEN**: "configured HA1 peer-ip == peer's management_ip" is
not a universal PAN HA pairing invariant. It is the correct test only for
the specific, valid topology where the management interface is itself
configured as HA1. Requiring it before contact made the correction circular:
the P1/P2 evidence that could actually prove pair correspondence for a
dedicated-HA1 pair could never be collected, because contact was refused
first.

## Correction

Kept unchanged, deliberately: `_derive_pan_units`'s own fleet-wide,
stored-telemetry pairing (the normal console/report path with no explicit
preflight invocation) — it still only recognizes the management-as-HA1
topology as Grade A, and still degrades a dedicated-HA1 device to a
conservative single-member unit. Weakening it globally would let an
unrelated device's management_ip coincidentally satisfy the predicate for
the wrong reason.

Added, additive-only:

- **Bounded candidate resolution** (`application/workflows/preflight.py`):
  `_resolve_pan_operational_entity` now only resolves the operator's exact
  two selected candidates from local inventory via the existing serial
  selector — no pairing proof required before contact. It establishes a
  BOUNDED CANDIDATE SET, never a TRUSTED OPERATIONAL PAIR.
- **P1-dialed endpoint as evidence** (`panorama/preflight_collector.py`,
  `panorama/pan_preflight_projection.py`): the management address `P1`
  already independently dialed and identity-gated per member becomes a
  fact (`local_management_endpoint`, tokenized) — an in-memory value the S6
  collector already held, never a new read.
- **P2 management-plane fields** (`configuration/panorama_config_collector.py`):
  `local-info/mgmt-ip` / `peer-info/mgmt-ip` — real, confirmed field names
  (this repository's own real-environment field enumeration, commit
  `1d97cd6`) on the SAME already-fetched `show high-availability state`
  response — parsed and tokenized alongside the existing fields. Best-effort
  `group-id` also added (path unconfirmed by any official source; disclosed,
  never gating).
- **Fresh reciprocal correspondence**
  (`utils/failover/preflight_readiness.py::_pan_reciprocal_correspondence`):
  after both candidates independently pass `P1`, compares each member's own
  P2 `mgmt-ip` self-report against its own P1-dialed endpoint, and each
  member's P2 peer-claim against the OTHER member's P1-dialed endpoint —
  reporting `MATCH`/`MISMATCH`/`MISSING`/`NOT_EVALUABLE`/`AMBIGUOUS`, plus
  HA-mode correspondence and the best-effort group-id. Purely descriptive:
  never gates any of the seven canonical checks, never establishes PAN
  `B2` (the frozen, stronger, bidirectional-serial-corroboration
  requirement — AGENTS.md "one-sided peer claim is not bidirectional
  corroboration").
- **Explicit candidate unit** (`utils/failover/assessment.py`):
  `derive_ha_units`/`compute_ha_readiness` gained an optional
  `pan_explicit_candidate_members` parameter. When given, the two orphan
  single-member `HaUnit`s `_derive_pan_units` would otherwise leave for
  those same two entity ids are replaced, for this invocation's own report
  only, by one bounded-candidate unit tagged `explicit_candidate=True` with
  a distinct `pair_identity` grade
  (`explicit_bounded_candidate_pending_correspondence`) — never claimed as
  the stronger `established_configuration_intent` grade
  (`_pair_identity_state`). Every other caller (console, `--ha-readiness-check`,
  normal reports) never passes this parameter and is completely
  unaffected. (Second commit, same session: the FIRST version of this
  change appended the candidate unit purely additively, which left the
  operator's generated report showing three near-identical PAN rows for one
  bounded two-device invocation — real-env operator finding, fixed same
  session.)

No new API operation. No new command. No mutation. No retry/fallback.
`group_running_sync_enabled` was checked and confirmed never consulted by
the `parity` check predicate — real evidence showing one member
`Enabled: no` / the other `Enabled: yes`, both `Running Configuration:
synchronized`, was not a defect to fix.

## Real-environment validation (S8-C)

Operator-executed, SAFE counts only, against the approved real pair:

```
Reads: P1 success facts=4, P2 success facts=82, P4 success facts=2 (both members)
Operational unit: <hostname-A>+<hostname-B>   (requested 2 / resolved 2 / extra 0)
Snapshot applied: True   Coherent: True
Checks: viable_target PASS, state_sync_current PASS, parity PASS,
        no_split_brain PASS, preemption_known PASS,
        control_sync_link_health INSUFFICIENT_EVIDENCE (unknown:pan_path_monitoring_any_down —
          path monitoring not configured on this pair; fail-closed, not a defect),
        flap_history INSUFFICIENT_EVIDENCE (D-F3 open policy, both vendors, by design)
Readiness verdict: INSUFFICIENT_EVIDENCE (stop_conditions_not_fully_evaluable)
Pair correspondence: MATCH (self-management MATCH/MATCH, reciprocal peer-management
  MATCH/MATCH, mode MATCH, group-id NOT_EVALUABLE — best-effort field, expected)
```

Matches the S8-C acceptance bar: exactly two independently observed
members, both independently P1-gated, P2/P4 executed only after P1, one
coherent snapshot, no phantom peer, canonical readiness evidence-correct
(5/7 PASS, 2/7 correctly INSUFFICIENT_EVIDENCE for open-policy/unconfigured-
feature reasons — never fabricated), CLI/generated-report parity
structurally guaranteed unchanged (one evaluation, two renderers — this
correction did not touch `_publish_fresh_readiness`/`run_html_export`), no
unauthorized operation, no mutation. **PAN B2 stays NOT ESTABLISHED** — the
`MATCH` pair correspondence above is a narrower, read-only, management-plane
question, not the frozen serial-based bidirectional requirement, and is
never promoted to it.

### Manual B2 evidence (recorded, not runtime-authorized)

During this session the operator additionally captured `show
high-availability all` manually (outside the approved S8-C `P1`/`P2`/`P4`
battery) and reported reciprocal serial correspondence for both members
(`local_serial == P1.serial`, `peer_serial == the other member's P1.serial`,
both directions). This is potentially useful future `B2` evidence but is
**not** treated as authoritative here: `show high-availability all` is not
in the approved runtime battery, was not independently re-verified by this
session's own code path, and appears to conflict with an earlier-recorded
S0 finding (`project/backlog.json` `pan_serial_representation_identity_evidence_closure`:
one member `MISMATCH` on the same axis). That earlier finding is NOT
overwritten or reconciled here — see the backlog item for the honest,
unresolved state. `B2` remains `NOT ESTABLISHED` in this repository's
runtime authority.

## Contract correction

`docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md` recorded
Grade A pairing as "`peer-ip` → `management_ip` match" without qualifying it
to the management-as-HA1 topology; that document's own status line is
updated with a pointer to this real-env disproof. The corrected law:
**HA1 control-link addressing and management transport addressing are
independent planes unless the operator has explicitly configured the
management interface as HA1.** `_derive_pan_units`'s own docstring
(`utils/failover/assessment.py`) carries the full correction and is the
canonical text; this document does not restate it.

## OP.0b closure assessment

Real-env matrix, all three now closed:

- S8-A ClusterXL: REAL_ENV_VALIDATED (`op0b_s8a_clusterxl_execution_model_console_parity`)
- S8-B'' VSX/VSLS: REAL_ENV_VALIDATED (`docs/history/phase/OP_0B_S4A_VSX_PER_VS_FAILOVER_DOMAIN_REVIEW.md`)
- S8-C PAN: REAL_ENV_VALIDATED (this document)

Remaining work, classified per the frozen `OP.0b.0` contract's own terms —
none of these are new findings; all were already tracked before this
session:

**A. READ-ONLY OP.0b PRODUCT BLOCKER (genuinely keeps OP.0b's S0–S9 sequence
open):**
- **S9 — Authority reconciliation / UI heuristics retirement**
  (`static/inventory_ui.js`, `utils/merge.py`, `utils/config_ui.py`).
  Confirmed NOT STARTED this session (operator UI review): client-side PAN
  pairing/cluster-name inference (`clusterNameSource: "inferred_ha_runtime_pair"`,
  `static/inventory_ui.js`), CP cluster synthesis from hostname-token
  overlap, `presentation_group_id` grouping, and `utils/config_ui.py`'s
  independent `_ha_header_evidence` HA vocabulary all still compute
  authority the canonical `compute_ha_readiness` evaluator should own alone.
  Separately, this session's own S8-C UI review found the explicit-candidate
  PAN report row needed a same-day fix (see "Correction" above) precisely
  because the legacy single-member/VSYS-labelled rows are confusing next to
  Check Point's one-row-per-cluster shape — direct evidence S9 is overdue,
  not merely theoretical. PO decision this session: log honestly, do not
  start in this campaign (independent, larger, cross JS/Python surface;
  `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
  already sequences it "independent after S7"). Recommended next dedicated
  movement.

**B. INTENTIONALLY UNRESOLVED POLICY (does not block OP.0b, blocks a
positive verdict):**
- `D-F3` — flap/failover-frequency numeric threshold, both vendors. Product-
  owner decision, not an engineering blocker.
- `op_degraded_verdict` — DEGRADED_PROCEED_WITH_RISK inclusion, owed before
  OP.1.

**C. CLASS-2-TIME BLOCKER (frozen contract explicitly permits these open at
OP.0b's read-only boundary):**
- `D-V3a`/`D-V3b` — PAN successor serial-keyed identity model / real B2
  correspondence. `B2` stays NOT ESTABLISHED (see above).
- `D-V7b` — CP configured-recovery machine-readable read surface.
- `pan_serial_representation_identity_evidence_closure` (backlog) — the
  earlier S0 MISMATCH finding's root cause, still UNKNOWN; this session's
  manual (non-authoritative) evidence does not resolve it.
- CLASS 2 mutation/authorization gates generally (OP.2.0 architecture
  FROZEN 2026-09-04; CLASS 2 not implemented, not reachable).

**D. PRODUCTION-HARDENING DEBT (unaffected by this session):**
- CP production SSH strict host-key enforcement (deferred by PO decision).
- PAN production TLS/corporate-CA trust (DEPLOY.1-gated).

**E. FUTURE CAPABILITY:**
- Future CLASS 2 VSLS/PAN A-A operation semantics (`op_aa_vsls_scope`,
  deferred to OP.3 scoping).

## Validation

Targeted: PAN target-selector, P1 identity, S2 PAN extraction/projection,
S6 collector, S7 readiness (66 tests, 20 new/changed for this correction),
S7.5 controlled entrypoint, S8 PAN real-CLI-path, report/UI parity,
architecture convergence — all green. One full serial regression at
campaign completion: **1681 passed / 26 skipped / 0 failed.** Repository
privacy gate: **PASS / 0 findings** (one accidental real-address-in-docstring
finding from an earlier draft of this correction was caught and fixed
before merge — never reached a device or persisted evidence, only a code
comment). `metadata_warnings == []`.

## Mechanical corrections in-campaign (no PO round-trip, per task authority)

- Replaced the two now-redundant single-member PAN report rows with the one
  bounded-candidate row for an explicit preflight's own generated report
  (operator UI finding, same session).

No new API operations. No unauthorized operations. No device mutations.
