# GOV.ORCH.12-A — The relay fields RQ-2 actually reads

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-14.** Correcting amendment to
`docs/design/GOV_ORCH_12_AN_ANSWERED_RELAY_QUESTION_IS_RESUMABLE.md` RQ-2,
which is FROZEN and is not edited in place. Every other clause of
GOV.ORCH.12 — RQ-1, RQ-3, RQ-4, RQ-5, RQ-6 and its §4 acceptance — stands
unchanged.

**This corrects a Product Owner defect, not a worker's.** RQ-2 named two
fields by the names the Product Owner assistant remembered rather than the
names `scripts/local_relay.py` writes. The first movement dispatched against
it (`NXS-LOCAL-0166`, the first Codex dispatch of this loop) found the
mismatch on its own focused test, refused to substitute a field silently,
raised a `RELAY_QUESTION` and stopped — exactly the behaviour the law asks
for, and the reason this record exists instead of a quiet divergence.

## 1. The measurement

A relay file written by `scripts/local_relay.py` carries, per entry, exactly:
`actor`, `marker`, `seq`, `timestamp`, and, depending on the marker,
`subject`, `text`, `report`. There is **no `role` field on an entry** and
**no `next_actor` on an entry**; `next_actor` is a single top-level field on
the relay object, holding whose turn it is now.

RQ-2 as written reads "the entries' `role` field" and "a `RELAY_NOTE` whose
`next_actor` is `engineer`". Neither is expressible.

## 2. The correction

RQ-2's condition is restated, unchanged in intent:

- **RQ-2a. Authorship** is the entry's **`actor`** field, whose values are
  `po` and `engineer`. An entry with no `actor` is not an answer.
- **RQ-2b. Ordering** is the entry's **`seq`** field (monotonic per relay),
  never the position in the list.
- **RQ-2c. An answer** is a `po`-authored entry whose marker is
  `RELAY_DECISION`, `RELAY_CORRECTION`, or `RELAY_NOTE`, with a `seq`
  greater than the `seq` of the engineer-authored `RELAY_QUESTION` that
  stopped the run, **and** the relay's top-level `next_actor` equal to
  `engineer`. The top-level field is what carries "your turn" for a
  `RELAY_NOTE`, since the entry cannot.
- **RQ-2d.** Everything else in RQ-2 stands: `phase` failed, `exit_code` 0,
  `failure_reasons` exactly `{"relay_not_closed"}`, default-closed.

## 3. Cross-references

- `GOV_ORCH_12_AN_ANSWERED_RELAY_QUESTION_IS_RESUMABLE.md` RQ-1..RQ-6.
- `NEXUS_AGENT_RELAY_PROTOCOL.md`; `scripts/local_relay.py` — the writer
  whose field names §1 records.
