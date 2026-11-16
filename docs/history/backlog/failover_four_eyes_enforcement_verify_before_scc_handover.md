# Verify failover four-eyes enforcement before the SCC handover

status: planned · target: FailoverAuthorizationService / FourEyesValidationRule

2026-11-16 backlog refresh: before the SCC handover, show with a test and one real-environment check that the failover approval is refused when requester and approver are the same person (FailoverAuthorizationService, FourEyesValidationRule, ActionRegistry). Code exists; enforcement end to end is not verified in this refresh.

## Verification 
Result: enforced. No path reaches device commands without a recorded second approval.

- Worker: admission before contact and before each write requires `approved_by`, an unrevoked approval, an open window and a matching request revision and initiator (JooqCpFailoverRepository mutationAdmission, prepareDispatch, dispatch).
- Run creation and the scheduler require the second approval; resume uses the two-person policy and a DB CHECK (V170).
- The legacy generic engine always refuses; its endpoints return 403. There is no CLI failover command.
- Self-approval is refused in code and by DB CHECKs (V137, V170). Both sides are opaque session fingerprints from one function, so case or whitespace variants cannot occur; no change made.
- Approver role: only security_admin or operation_admin. Approvals are immutable and revoked by membership or discovery changes.
- No switch weakens four-eyes; NEXUS_FAILOVER_MUTATION_ENABLED only turns all mutation off.

Tests: ten tests in FourEyesValidationRuleTest and JooqFailoverApprovalTest (shipped as batch 16).

Open:
- failover_approver_role_recheck_at_worker: a disabled or demoted approver's approval stays valid until expiry, revoke or membership change. Needs the PO.
- One person holding two accounts (local and directory) can act as both parties. Design limit; needs an identity-to-person binding or an organisational control.

Not done: the real-environment check (a same-person approval attempt on the live UI). It needs an operator session and is left for the SCC handover rehearsal.
