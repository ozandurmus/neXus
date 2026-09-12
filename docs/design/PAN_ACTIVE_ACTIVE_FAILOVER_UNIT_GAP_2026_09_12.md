# PAN active/active is not excluded as a failover unit — finding

## Status

**FINDING REPORTED, 2026-09-12. NOT FIXED HERE.** This is a vendor-semantic
safety classification on the failover path, and the Product Owner holds an open
decision that owns the question (`op_aa_vsls_scope`: "PAN Active/Active and
Check Point VSLS failover: first-class in `OP.2`, or deferred?"). Changing the
predicate sets now would pre-empt that decision and silently alter a frozen
safety surface, so this document states the defect and stops.

Evidence grade: **official vendor documentation plus repository source.** No
real-environment evidence, and none is needed to establish the gap — it is
visible in the code.

## 1. The asymmetry

`utils/failover/assessment.py` excludes a Check Point load-sharing cluster from
failover assessment entirely, because such a cluster has no standby:

```python
# P3: a load-sharing cluster has no standby; "fail it over" is not a
# coherent request, so it gets neither a safe nor an unsafe verdict.
if unit.vendor == "checkpoint" and unit.cluster_mode in _CP_LOAD_SHARING_MODES:
    return VERDICT_NOT_A_FAILOVER_UNIT, "load_sharing_member_evacuation_not_failover"
```

The guard is `vendor == "checkpoint"` only. **Palo Alto active/active has no
equivalent**, although it is the same situation in the vendor's own terms: both
members pass traffic and neither is a passive standby, so evacuating one is not
a failover.

`utils/failover/preflight_readiness.py`'s own comment states that load-sharing
modes "are handled by the canonical roll-up as `NOT_A_FAILOVER_UNIT`". That is
true for Check Point and silently untrue for Palo Alto.

## 2. Why `no_viable_target` does not catch it

`_PAN_STANDBY_CAPABLE_STATES = {"passive", "active-secondary"}`
(`utils/failover/assessment.py`).

The `standby_capable_member` predicate fails only when **no** member is in that
set. In an active/active pair one member is `active-secondary`, so the check
passes — the preflight concludes a viable failover target exists.

`exactly_one_active` also passes: `_PAN_ACTIVE_STATES = {"active",
"active-primary"}`, so in an A/A pair only `active-primary` counts and
`len(actives) == 1`. No split-brain is reported either.

So both cross-member predicates pass for a pair that has no standby at all.

## 3. Vendor documentation

PAN-OS 11.1 "HA Firewall States"
(`https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-firewall-states`)
documents exactly eight states: `Initial`, `Active`, `Passive`,
`Active-Primary`, `Active-Secondary`, `Tentative`, `Non-functional`,
`Suspended`. The estate runs PAN-OS 11.

Two things follow.

**The vocabulary is complete.** The code's recognised set is exactly those eight
states, so there is no undocumented token that could slip through — and an
unrecognised value would in any case yield `value_not_established`
(INSUFFICIENT), never PASS. The "unknown token" risk does not exist here.

**The classification is wrong for one state.** The documentation places
`Active-Primary` and `Active-Secondary` in active/active mode, and describes
`Active-Secondary` as a firewall that connects to User-ID agents and runs a
DHCP server — that is, one carrying traffic, not one standing by. Counting it
as standby-capable is what makes an A/A pair look like a healthy
active/passive pair.

`Passive`, by contrast, is documented as the active/passive standby and belongs
in that set. `Tentative` is classified non-functional, which is conservative
(the documentation says a tentative member can sync and become
active-secondary), so it fails closed and needs no change.

## 4. There is no PAN mode signal to gate on

`FailoverUnit.cluster_mode` defaults to `"unknown"` and is populated only from
`cp_ha_runtime` (`ha_cluster_mode`). For a Palo Alto unit it is always
`"unknown"`. So the A/A guard could not be written today even if authorized:
**the data model carries no PAN HA-mode fact.** Any fix therefore starts with
an evidence question — which authorized PAN read establishes A/P versus A/A —
and that is a command-gate question, not a code edit.

`show high-availability all` already appears in the evidence surface for
`preemption_known`, which is where that fact most plausibly lives, but whether
its mode field is collected, parsed and gate-approved **for this purpose** is
`UNKNOWN` here. `AGENTS.md`: a command already being issued somewhere does not
authorize using its output for a new purpose.

## 5. Blast radius today, and why it still matters

Bounded, and deliberately so. `SAFE_TO_FAILOVER` is unreachable in `OP.0a` —
enforced over a generated matrix by `tests/test_op0a_ha_readiness.py` (AC-6),
not by convention. The product therefore never tells an operator that failing
over an active/active pair is safe.

What it does do is classify such a pair as a failover unit **with a viable
target**, instead of `NOT_A_FAILOVER_UNIT`. That is a misclassification, not a
false "safe". It becomes dangerous at `OP.2`, where an actual failover action
exists: a request that is incoherent by the vendor's own model would arrive at
an executor having passed the two cross-member checks meant to stop it.

Readiness is not authorization (`AGENTS.md` evidence laws), so this is not a
live device risk today. It is a latent one, and the cheapest moment to fix a
latent classification defect is before the action that depends on it ships.

## 6. What a fix requires, in order

1. A Product Owner ruling on `op_aa_vsls_scope`, which owns whether A/A is
   first-class in `OP.2` or deferred. If deferred, the correct behaviour is
   `NOT_A_FAILOVER_UNIT` for A/A, which is a refusal and needs no new device
   read beyond mode detection.
2. An evidence decision: which authorized PAN read establishes HA mode, and
   whether using it for this purpose needs a new network-device command gate
   entry.
3. Only then: a PAN mode fact on the unit, the roll-up guard, and the
   `active-secondary` reclassification — with the regression matrix extended so
   an A/A pair is proven to reach `NOT_A_FAILOVER_UNIT` rather than a readiness
   verdict.

Doing 3 without 1 and 2 would be a guess dressed as a safety fix.

## 7. What this finding does not claim

It does not claim any member state is misread, that any parser is wrong, or
that a real device has been observed in this shape — no real-environment
evidence was collected. It does not claim `SAFE_TO_FAILOVER` is reachable; it
is not. And it does not decide `op_aa_vsls_scope`.
