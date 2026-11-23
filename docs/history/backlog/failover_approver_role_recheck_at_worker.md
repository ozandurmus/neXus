# Failover approver role re-check at the worker

Found by the four-eyes verification on 2026-11-16. `approved_by` and `initiated_by` are checked for role only when the request and the approval are recorded. If the approver is later disabled or loses the approver role, the approval and any queued run stay valid until the window expires, an explicit revoke, or a membership or discovery change.

Suggested fix: in mutationAdmission, re-evaluate that both principals still hold an approver role and are enabled; or revoke their pending approvals when a role binding is removed. Waiting for the PO decision.

Shipped 4501dff4 (V260): worker start rechecks initiator and approver authority. Automated tests only; real-environment check open.
