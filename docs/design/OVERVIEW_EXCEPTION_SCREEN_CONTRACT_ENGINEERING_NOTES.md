# Overview contract — engineering review notes on Fable's draft (2026-09-22)

Status: review notes, not a contract. Fable's draft
(`OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md`) is kept unaltered as the
reviewer wrote it. These are the points the engineer will apply when the
Product Owner accepts it and it is frozen:

1. §5.1 says the browser DIFF comparison is ported "to Python": the service is
   Java (Spring). It is ported to Java, same semantics as
   `configurationProjection.ts` `projectCluster` (MEMBER-specific settings not
   counted), with a shared fixture test proving both give the same count.
2. §6 names `tests/test_html_render_harness.py` and `tests/fixtures/uitest/`:
   those belong to the legacy Python console. The UI2 equivalent is the
   vitest suite under `ui2/frontend/tests/` plus the `aiview` browser tour.
3. §3 "ACTIVE" is left UNKNOWN by the draft: the Inventory screen's enrolled
   predicate is `enrollment_state = 'ENROLLED'`.
4. Cluster counts: under `aiview` the pseudonyms currently collide (backlog
   `aiview_masking_leak_audit`, F20) — the Overview's cluster denominators
   must be computed on raw references server-side and only labels masked.
5. `backup_target` is a boolean, not nullable: "IS NOT NULL" reads "= true".

**Applied 2026-09-23** in the frozen `OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md`, plus one change of mechanism:
the cluster DIFF summary is recomputed by a service task every 5 minutes (and at startup) instead of "at
job completion" -- the service does not observe job completion; the worker does.
