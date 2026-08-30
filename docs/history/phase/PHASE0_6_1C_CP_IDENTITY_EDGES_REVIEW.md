# 0.6.1B.1.2 / 0.6.1C — CP Identity-Gate Edge Case Review

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id: `cp_identity_edges`
(P1, `planned`).

## Objective

Review the CP identity mechanisms introduced/extended around 0.6.1B.1.2 for
edge cases now that interactive-session collection is real-environment
validated, per the backlog note ("Review remaining CP identity-gate edge
cases after interactive session validation"). This is a **review-and-document**
build first; code changes are only made if the review finds an actual
false-accept or false-reject, not speculatively.

Two distinct mechanisms are in scope:

1. **Pre-poll exact-name exclusion filter** —
   `utils/inventory_exclusions.py` feeds
   `checkpoint/cp_runner.py:640-647` via
   `SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES`
   (`checkpoint_transport_value()`, lines 94-108 — exact match only, no
   regex/wildcard, commas rejected in identities by design).
2. **Post-connect identity gate** — `_identity_gate()`
   (`configuration/checkpoint_config_probe.py:476-504`) and
   `_collector_identity_gate()`
   (`configuration/checkpoint_config_collector.py:916-959`) compare observed
   Gaia hostname vs. management object name via `_identity_relation()`
   (probe.py:462-473), producing `exact` / `shortname_match` /
   `normalized_match` / `different_observed` / `unavailable`, mapped to
   `VERIFIED_MANAGEMENT_ENDPOINT_AND_HOSTNAME` (HIGH) /
   `..._HOSTNAME_DIFF_OBSERVED` (MEDIUM) / `UNVERIFIED` (LOW).

`docs/history/validation/VALIDATION_0_6_1B_1_2.txt` records real-estate
validation as **PENDING** at that checkpoint ("This package has not contacted
the user's estate") — so the `different_observed` / `shortname_match` vs
`normalized_match` boundary has not yet been exercised against real
production hostnames, only synthetic fixtures. No TODO/FIXME markers exist in
either file; this is planned-but-not-started review work, not a known defect.

## Scope

### In scope

- Re-read `_identity_relation()`'s classification boundary against a wider
  set of realistic hostname/management-name pairs (case folding, FQDN vs
  short name, domain-suffix variants, CP's own name-normalization rules) and
  confirm each relation lands in the intended confidence bucket.
- Confirm `_collector_identity_gate`'s MEDIUM-confidence acceptance path
  (`different_observed` / unknown-platform interaction, lines 925-931) cannot
  be satisfied by an unrelated device that merely shares a naming pattern —
  i.e. that MEDIUM confidence still requires the management-plane object
  identity to anchor the check, not just any responding host.
- Confirm the pre-poll exact-name exclusion filter's exact-match contract
  (`checkpoint_transport_value`) has no unintended interaction with the
  post-connect identity gate (e.g., an excluded name colliding with a
  `normalized_match` variant of an included device).
- Where the review finds a genuine false-accept/false-reject, propose the
  smallest bounded fix and re-run this as a normal implementation build; do
  not implement broad speculative hardening.

### Explicitly out of scope

- Loosening exclusions to regex/wildcard matching — deliberately exact-match
  only, per existing design intent; not up for reconsideration here.
- Turning the identity gate into a hard platform/version gate — explicitly
  against the documented design intent at
  `checkpoint_config_collector.py:925-931`.
- Any new device command, write path, or collection-frequency/concurrency
  change.

## Correctness contract

- The gate's job is bounded identity assurance for evidence attribution, not
  access control — a review finding must not turn it into a blocking gate
  for otherwise-good read-only evidence collection.
- Any proposed fix must preserve the existing confidence vocabulary
  (`exact`/`shortname_match`/`normalized_match`/`different_observed`/
  `unavailable` -> HIGH/MEDIUM/LOW) rather than inventing new states, unless
  the review demonstrates the vocabulary itself is insufficient.

## Privacy and safety invariants

1. Review evidence (sample hostname/name pairs used to test the boundary)
   must be synthetic — no real device name, hostname, or management-object
   name from the user's estate enters this repository, matching the
   "PENDING real-estate validation" note above.
2. No new device command is introduced by this review.

## Implementation plan

1. Enumerate the current `_identity_relation()` branch logic and build a
   synthetic test matrix covering FQDN/short-name/case/domain-suffix
   variants beyond what existing tests already cover.
2. Cross-check the pre-poll exclusion filter and post-connect gate for any
   shared-state or ordering assumption that could misfire.
3. Document findings (pass / edge case found) in this doc's closure section.
4. If a genuine issue is found, scope and implement the smallest fix as a
   follow-up commit within this same build, with its own targeted regression
   test.
5. Full regression + privacy gate.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | The identity-relation boundary is exercised against a documented synthetic matrix beyond current test coverage. |
| AC-2 | Any interaction between the pre-poll exclusion filter and the post-connect identity gate is confirmed safe or fixed. |
| AC-3 | Findings are written up in this doc, whether or not a code change was needed. |
| AC-4 | Any fix made preserves the existing confidence vocabulary and does not turn the gate into a platform/version blocker. |
| AC-5 | Full regression + privacy gate pass; no real device identity enters the repository. |

## Validation and merge gate

This is a review build; if no defect is found, `DONE` requires only the
documented findings (AC-3) plus AC-1/AC-2/AC-5. If a fix is made, it follows
the normal automated-validation gate before merge.

## Definition of done

`DONE` when the edge-case review is documented in this file's closure
section (found-and-fixed, or reviewed-and-clean) and `cp_identity_edges`
moves from `planned` to `automated_validated` in
`project/backlog.json`. Real-estate confirmation of the boundary remains a
separate, later `on_hardware_real_env_validation` item — this build closes
the offline review, not the real-environment gate.
