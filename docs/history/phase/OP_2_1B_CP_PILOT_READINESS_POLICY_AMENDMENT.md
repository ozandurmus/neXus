# OP.2.1b — CP pilot readiness-policy amendment (D-V7b / D-F3 / D-F2)

## Status

**IMPLEMENTED 2026-09-05.** Readiness-layer policy amendment only — no
device contact, no vendor adapter, no taxonomy member, no change to
`OP.2.0`/`OP.2.1`'s own frozen text. Amends `utils/failover/assessment.py`
and `utils/failover/preflight_readiness.py` (the OP.0a/OP.0b S7 readiness
authority) per explicit product-owner decision.

## What this build is, and is not

Prior sessions (`op2_1_cp_clusterxl_command_gate`, and the vendor-blocker
research session immediately before this one) established two facts by
reading the code directly: `D-V7b` (Check Point configured
recovery/preemption — no supported machine-readable read exists after
exhaustive official-source research) and `D-F3` (flap/failover-frequency
threshold — an open product-owner numeric decision) were each,
independently, a hard-coded reason `utils.failover.assessment._verdict_for`
could never return `SAFE_TO_FAILOVER` for a Check Point ClusterXL entity —
true for a bounded pilot exactly as much as for production.

This build is the product owner's answer to that finding: **not** a vendor
research result (D-V7b's underlying vendor fact stays exactly as unreadable
as before — nothing here invents a Check Point attribute, a numeric
threshold, or a PAN identity), but a **policy decision about how the
readiness roll-up treats three specific, already-identified evidence gaps**:

- **`D-V7b`** (`preemption_known`, Check Point only): **advisory-exempt**.
  The check stays `INSUFFICIENT_EVIDENCE`, visible, with its exact existing
  reason (`configured_recovery_not_readable_d_v7b`) — but that status no
  longer, by itself, blocks an otherwise-positive verdict.
- **`D-F3`** (`flap_history`, both vendors): **advisory-exempt**, not given
  an invented numeric threshold. The approved A8/P2 flap/failover counters
  are cumulative since an operator-triggered reset with no recency/window
  semantics — the product owner declined to fabricate a "zero-count" or any
  other threshold against evidence that cannot support one. The check stays
  `INSUFFICIENT_EVIDENCE` (`threshold_policy_unresolved:D-F3`), visible,
  forever, and is exempted the same way as `D-V7b`.
- **`D-F2`** (member-skew tolerance): **no threshold, ever**. Skew is
  recorded (`evidence.member_skew_ms`/`member_skew_policy`) for disclosure
  only; it never gated a check's own status in the first place (unlike
  `D-V7b`/`D-F3`, no check depends on a `D-F2` fact-level predicate) — the
  fix here is narrower: the roll-up's separate "no open numeric policy" gate
  (`UNRESOLVED_POLICY_DECISIONS`) no longer lists `D-F2` (or `D-F3`) as
  something that, merely by applying to the evidence, blocks an
  all-checks-passing unit. `D-F1` (configuration-intent max age) is
  untouched and stays the one genuinely open, still-blocking decision in
  that set — dormant in practice for Check Point today because
  `checkpoint/preflight_collector.py` always passes
  `configuration_facts=()`.

## Mechanism (closed-list, deterministic, no override)

`utils/failover/assessment.py` adds `ADVISORY_EXEMPT_CHECKS`: a frozen set
of exact `(vendor, check_id, reason)` triples —

```
("checkpoint", "preemption_known", "configured_recovery_not_readable_d_v7b")
("checkpoint", "flap_history",     "threshold_policy_unresolved:D-F3")
("panorama",   "flap_history",     "threshold_policy_unresolved:D-F3")
```

`_verdict_for` is rewritten so that, after the existing `FAIL`-always-blocks
rule (unchanged, first, unconditional): every check must be `PASS`, **or**
its exact `(vendor, id, reason)` must be a member of this set. Any other
non-`PASS` status — an identity-gate failure, an incoherent snapshot, a
collection failure, an unrecognized vendor value, a generic
"not evaluable without a preflight battery" reason, or a fact that is simply
never collected for this unit type (e.g. `cp_failover_count` for a VSX
Virtual System, D-V5b) — still blocks exactly as before, because its reason
string is not an exact match. `UNRESOLVED_POLICY_DECISIONS` shrinks from
`{D-F1, D-F2, D-F3}` to `{D-F1}`.

Deliberately absent from the exemption: `("panorama", "preemption_known",
...)`. PAN has a supported read (`local-info/preemptive`) — an equivalent
PAN gap is a real evidentiary failure, not a documented vendor-surface
absence, and stays fully blocking.

Nothing here is per-run, per-operator, or configurable: the set is a module
constant, consulted identically on every run, before any human is in the
loop. No new verdict is introduced (`SAFE_TO_FAILOVER`/
`UNSAFE_DO_NOT_FAILOVER`/`INSUFFICIENT_EVIDENCE`/`NOT_A_FAILOVER_UNIT`/
`DEGRADED_PROCEED_WITH_RISK` — vocabulary unchanged); a positive verdict
whose exemption applied is disclosed via its own `reason` string
(`all_stop_conditions_passed_or_advisory_exempt:<check ids>`), never
silently indistinguishable from a fully-evidenced `all_stop_conditions_
passed`.

## Preserved, unchanged

- `OP.0a`'s stored-telemetry basis (no fresh preflight snapshot):
  `SAFE_TO_FAILOVER` stays structurally unreachable — `AC-6`
  (`tests/test_op0a_ha_readiness.py`) is untouched and still passes, because
  the stored-telemetry path's own reason for `preemption_known`/
  `flap_history` (`not_evaluable_without_preflight_battery`) is not a member
  of the exact-reason closed list.
- `OP.2.0` eligibility item 6 ("the canonical readiness authority returned a
  positive verdict for this entity") — wording unchanged; only the
  readiness authority's own internal definition of "positive" changed,
  which the frozen contract itself already classified as `OP.0a`/`OP.1`
  readiness-layer territory, not an `OP.2.0`/`OP.2.1` concern.
- `OP.2.0` safety contract item 2 ("a non-positive readiness verdict is not
  operator-overridable") — no override exists; the exemption is a fixed,
  pre-registered fact evaluated before any human sees anything.
- Same-workflow fresh preflight, explicit confirmation, operational-entity
  lock, no blind retry, `OUTCOME_UNKNOWN`, independent post-action
  verification, reversal-as-new-typed-action (`P12`) — none of this build's
  edits touch `utils/operate/` or any adapter; none exists yet.
- `D-V7b`, `D-F3`'s underlying vendor/product questions — not resolved,
  only reclassified as non-blocking. The still-open `docs/history/phase/
  OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` (a prior, separate build's
  own record) is left as-is: a historical account of what was true then,
  not amended in place.

## Test evidence

`tests/test_op0b_s7_readiness_v2.py` (68 tests, all passing): rewrote
`test_safe_reachable_only_for_the_exact_fully_healthy_combination_over_
snapshot_matrix` (formerly asserting `SAFE`/`DEGRADED` unreachable
everywhere) to prove, over the same generated CP-role × PAN-state matrix,
that `SAFE_TO_FAILOVER` is reachable for **exactly** the fully-healthy
combination (one `ACTIVE` + one `STANDBY`-capable member, no
attention/pnote, synchronized for CP; one active + one passive member, HA1
up, no monitored path down for PAN) and never for any other combination in
the same matrix. Added
`test_advisory_exempt_checks_are_closed_list_and_never_cover_fail_or_open_
evidence`, asserting directly against the roll-up that the exemption never
fires for a `FAIL`, an identity/coherence/collection-failure reason, or the
same check id under a vendor the exemption does not name. Updated the
`D-V7b`/`D-F2`/`D-F3`/PAN-happy-path tests that previously hard-coded
`VERDICT_INSUFFICIENT` for a fully-healthy snapshot to their new,
correct `VERDICT_SAFE`. `tests/test_op0b_s4a_vsls_per_vs.py`'s end-to-end
VSX Virtual System fixture is a real illustration of the exemption's
precision: its `preemption_known` is exempt (D-V7b) but its `flap_history`
is **not** — `cp_failover_count` is never collected per VS at all (D-V5b),
a genuine missing-evidence reason distinct from `D-F3`'s — so that unit
correctly still cannot reach `SAFE_TO_FAILOVER`. Updated the stale
`FRAMING_NOTE` in `utils/failover_readiness_ui.py` (previously claimed
`SAFE_TO_FAILOVER` is unreachable "by design", now false) and one CLI
regression assertion in `tests/test_op0b_s8_real_cli_path_regression.py`
that matched the literal substring `"FAIL"` and false-positived on
`SAFE_TO_FAILOVER`'s own spelling.

Full suite: `1764 passed, 24 skipped, 0 failed` (serial, this session).
`test_architecture_convergence.py` (project-state cross-authority
consistency) included and green. Repository privacy gate: `FAIL / 3`, all
three the known gitignored `data/`/`logs/`/`.support_hmac.key`
runtime-artifact finding (confirmed untracked, not repository content) —
same baseline prior sessions recorded.

## CP ClusterXL positive readiness reachability — the actual answer

**Reachable for the readiness layer, for a Check Point ClusterXL entity,
given a fresh OP.0b preflight run in which the other five stop-conditions
(`viable_target`, `state_sync_current`, `parity`, `no_split_brain`,
`control_sync_link_health`) genuinely pass.** Proven by the matrix test
above, not merely by reading. This is a readiness-authority fact — it says
nothing about `OP.2.C` eligibility, which does not exist as code yet (no
adapter, no taxonomy member, `authorize()` still unconditional `DENY`).

PAN incidentally reaches the same readiness result under the identical
policy change (its `flap_history` is exempted too, since `D-F3` is a
vendor-symmetric decision) — a readiness-layer side effect only. PAN
`CLASS 2` stays out of scope (`OP.3`): PAN's own identity contract (`B2`,
`D-V3a`, both `STILL_UNKNOWN`/`NOT ESTABLISHED`) is a separate, unresolved
gate that a future eligibility layer would enforce and that this readiness
verdict never encodes.

## Exact next step toward `OP.2.C` / the first controlled failover pilot

`OP.2.C` (the first CP ClusterXL vendor adapter) remains blocked on
everything this build did not touch:

- `DEPLOY.1A` OIDC boundary + RBAC `OPERATE` role (the authorization gate —
  `authorize()` is still unconditional `DENY`; no `PermitAllAuthorizer`
  exists outside tests).
- `cp_production_ssh_host_key_trust_hardening` (P0 before CLASS 2 — the
  class 2 identity gate is host-key trust + hostname match).
- The vendor adapter itself: `ActionPlan` construction, the mutation
  primitive call (`clusterXL_admin down`/`up`, already `APPROVED_FOR_OP2C`
  by `OP.2.1`), `settle_observation` (`UNKNOWN` for every vendor today).
- The signed change-management / network-security review
  (`FAILOVER_ENGINE_ARCHITECTURE.md` §10, an organizational gate).
- The real-environment `OP.2.D` pilot itself, once the above exist.

None of these is a readiness-layer question — this build closes the last
readiness-layer objection (D-V7b/D-F3/D-F2) to a positive CP verdict, so
the honest framing is: **the readiness gate is no longer the reason
`OP.2.C` cannot start; the authorization/adapter/trust/change-management
gates are.** Recommended movement: `OP.2.C` scoping (the adapter's own
contract — `ActionPlan` construction, `check_precondition`, the one-shot
submission path), `Sonnet 5, extended thinking (high)` given it is new
architecture surface against a frozen parent contract, not mechanical
implementation.

## Rollback

Documentation and code-review artifact; if superseded, mark this file's
status and name the superseding build. No operational rollback: no device
was contacted, nothing was executed.
