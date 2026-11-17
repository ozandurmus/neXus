# PO decision record -- 2026-11-14: Cisco ASA failover readiness reads approved

**Status: RATIFIED -- PO APPROVED (chat, 2026-11-14, relayed by the PO session).** Recorded by the engineering session
from the Product Owner's answer to Q1 of `PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md`. This record is the
implementation authority for signing off the four readiness read rows only. It approves no state-changing command.

## Decision (PO's words quoted)

1. **The four readiness reads (Q1):** "Onaylıyorum" to the exact literals `show failover`, `show failover state`,
   `show failover history`, `show conn count` for permanent readiness use, under these conditions as relayed by the PO
   session:
   - run only when a readiness check runs (on demand "Run pre-checks", or the scheduled readiness pass);
   - on both members of a Cisco ASA failover pair;
   - read-only;
   - masked output (only derived values are stored; a read that does not parse is logged as its masked shape).
2. **The switch command is not included.** `no failover active` (design method approved 2026-11-13, record
   `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 2) waits for the decision council on the
   session-drop dispatch state. No ASA gate row, code path or execution entry point for it exists.

## What this record changes

- Migration `V215__asa_failover_readiness_signoff.sql` moves exactly the four rows `asa_failover_show_failover`,
  `asa_failover_show_failover_state`, `asa_failover_show_failover_history`, `asa_failover_show_conn_count` (inserted
  `DRAFTED` by V212) to `SIGNED_OFF`, with this approval and its date in `source_document_pointer`. Only rows still
  `DRAFTED`, vendor `cisco_asa`, action class `read` are touched. The one-time measurement rows `asa_measure_*` (V210)
  are unchanged.
- Use and frequency stay as V212 wrote them: `show failover` twice per run per member at least 5 s apart (the counter
  comparison of check 6, as §1 of the request states it), the other three once per run per member.

## What this record does not change

- **The readiness switch `UI2_ASA_FAILOVER_READINESS` stays off by default.** It is not only an approval-gap fence: the
  request asks a separate rollout question (Q3, all five pairs at once or one pair first), still open. Turning it on
  (value `on`, on both the service and the worker) makes ASA pairs offer "Run pre-checks" and adds every ASA pair to
  the scheduled readiness pass (inside the readiness job windows, when the HA readiness schedule is on). The switch is
  global, not per pair.
- **Q2 (run `show failover` twice or once)** was not answered separately; the approval covers the literals as the
  request's §1 states their use, which is twice per run.
- Execution, approvals and resume for `cisco_asa` keep refusing with `FAILOVER_VENDOR_NOT_AVAILABLE`.

## Amendment 2026-11-17: readiness switch on, all five pairs (Q3)

PO chat 2026-11-17, before a site-to-site failover planned for 2026-11-18: "Evet, 5 çifte birden" (yes, all five pairs
at once). `UI2_ASA_FAILOVER_READINESS=on` is set on `ui2-service` and `ui2-worker` (live 2026-11-17 and in
`deploy/ui2/50-service-deployment.yaml`, `deploy/ui2/52-worker-deployment.yaml`). Every ASA pair now offers
"Run pre-checks" and joins the scheduled readiness pass. Nothing else changes: the same four signed-off reads, readiness
only; execution, approvals and resume for `cisco_asa` still refuse with `FAILOVER_VENDOR_NOT_AVAILABLE`.
