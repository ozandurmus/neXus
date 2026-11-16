# Verify failover four-eyes enforcement before the SCC handover

status: planned · target: FailoverAuthorizationService / FourEyesValidationRule

2026-11-16 backlog refresh: before the SCC handover, show with a test and one real-environment check that the failover approval is refused when requester and approver are the same person (FailoverAuthorizationService, FourEyesValidationRule, ActionRegistry). Code exists; enforcement end to end is not verified in this refresh.
