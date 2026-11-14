# Cisco ASA policy evaluation over stored configurations: hygiene and lookup, 2026-11-14

## Status

MEASUREMENT RECORD (evidence, not a contract) - 2026-11-14. Steps 2b, 5 and 6 of `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md`
(branch `feat/asa-policy-wiring-1114`), run under `PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` items 1-2
(stored artefacts only, counters only, no device contact). Harness: `scripts/measurement/asa_policy_evaluation/`.

## Method

The branch's parser, mapper and engine compiled against the pod's `service.jar` and run in `ui2-service` over the
latest `show_configuration` artefact of each of the 12 enrolled ASAs: `PolicyHygiene.analyze` with the production
work limit, and 1,022 lookups. Each lookup takes one access rule of an ingress list whose destination is one resolved
host and whose service is one tcp port, enters on that list's interface from a documentation source address and leaves
by a random other interface of the same unit, so every query meets at least that rule. Only verdicts, fixed reasons and
counts left the pod.

## Results (final run)

| Check | Result |
|---|---|
| Snapshots mapped / with collection failures | 12 / 0 |
| Heap held by all 12 snapshots | about 124 MB |
| Hygiene time, 12 units | 2.7 s (largest unit 0.7 s); work budget never reached |
| Shadow status | NOT_SHADOWED 15,120; REDUNDANT 7,829; CONFLICT 588; UNKNOWN 91,592 |
| UNKNOWN reasons | earlier rule has unsupported constraints 65,563; user or category (identity firewall) 12,002; outside security evaluation ("ASA not evaluated") 7,501; empty or dynamic group (FQDN) 2,029; time 1,875; unresolved object 1,379; unsupported service 1,243 |
| Other findings | broad 24,450; unused (ACL with no binding and no use) 502; disabled 1 |
| Lookups | 1,022: ALLOWED 37, DENIED 166, UNKNOWN 819; 2.2 s in all |
| DENIED reasons | implicit deny of the access list 100; same security level 44; first matching deny rule 22 |
| UNKNOWN reasons | more than 128 earlier rules the lookup cannot decide ("analysis budget") 550; earlier rule could match 269 |
| Lists | 70 access lists; 13 hold 128 or more rules the lookup cannot decide; 9 hold 128 or more identity, time or unparsed rules |
| List normalization work | at most 180,000 of the 2,000,000 per-list budget |

## Reading

- The engine works at production scale; the UNKNOWN share is evidence-driven, not a defect. The dominant cause is the
  identity firewall: 8.2 % of bound ACEs carry `user-group` (shape measurement) and, because A0-ASA-16 leaves their
  meaning UNKNOWN (decision record item 4), every later rule of the same list stays "could be preceded". Lists with
  many such rules exceed the lookup's 128-undecidable-rule cap.
- Passing over a rule the lookup cannot fully decide when what did resolve of it (source, destination, port) is
  disjoint from the query (the same partial-prior rule hygiene already uses) roughly doubled the decided lookups
  (95 -> 203 of 1,022); identity rules are never passed over this way.
- The PO question that would move most of the rest: may an ASA ACE's identity criterion (`user`, `user-group`,
  `object-group-user`, TrustSec) be treated as narrowing only, so its address and port cells bound what it can match,
  as the 2026-11-04 amendment did for Check Point access roles? Not decided here.

## Identity criteria narrowing (A0-ASA-16a), before and after

Same harness, same 12 stored configurations (no collection ran in between; the "before" run reproduces the final run
above exactly), 2026-11-14 evening. Before: Trace branch head (identity ACEs UNKNOWN and never passed over). After:
A0-ASA-16a (PO approved 2026-11-14 evening; amendment section 15): an identity ACE's address and port cells bound
it, used only to prove it does not cover a flow. Both runs ended `done`, `filtered_lines 0`, 0 collection failures,
hygiene budget never reached.

| Count | Before | After |
|---|---|---|
| Shadow status UNKNOWN | 91,592 | 61,334 (-30,258) |
| NOT_SHADOWED | 15,120 | 45,378 |
| REDUNDANT / CONFLICT | 7,829 / 588 | 7,829 / 588 (the bound never proves cover) |
| UNKNOWN reason "earlier rule has unsupported constraints" | 65,563 | 35,305 |
| Hygiene findings "unknown" | 197,074 | 166,816 |
| Lookups (1,022): ALLOWED / DENIED / UNKNOWN | 37 / 166 / 819 | 37 / 515 / 470 |
| Lookup UNKNOWN "analysis budget" / "earlier rule could match" | 550 / 269 | 0 / 470 |
| DENIED by the list's implicit deny | 100 | 449 |
| Hygiene / lookup time, 12 units | 2.7 s / 2.2 s | 3.5 s / 2.4 s |

Reading: no ALLOWED appeared or disappeared (an allow is never decided by identity); the new decisions are denies a
packet reaches only after passing identity ACEs whose addresses or ports it does not meet, and NOT_SHADOWED proofs
for rules disjoint from earlier identity ACEs. The remaining 470 lookup UNKNOWNs are packets inside an identity ACE's
bound (or another undecidable rule's) before any decision.
