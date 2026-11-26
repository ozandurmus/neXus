# Job transcript for aiview: HTTP 403 stays by design; a masked transcript widens an authorization boundary and needs a PO decision

status: planned · target: 15753699

2026-11-26 (15753699): the aiview backup job list keeps HTTP 403 on the job transcript read as intended behaviour. JOB_TRANSCRIPT_READ is security_admin/backup_admin only in ActionRegistry, JobTranscriptController refuses replay_viewer explicitly, and the masked job page strips has_transcript, so no dangling affordance is offered; e2e/key-views.spec.ts asserts this. Serving a masked transcript to aiview would widen an authorization boundary; not done. Open for the PO: whether aiview may read a masked job transcript at all. Nothing is decided here.
