# Check Point advisories from Jumbo resolved-issues pages

status: in_progress · target: Amendment frozen 11-14; V213 dry run

2026-11-14: branch feat/cp-advisories-1113, not shipped. PO 11-14: freeze, take-M-or-below counts as fixed, no extra host. Migration V213 needs the BEGIN/ROLLBACK dry run on the live DB before ship. Spark and R81.10 stay NOT_COVERED with a reason.

2026-11-14: in ship batch 1 (V214).

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): CLOSED. V214__advisory_checkpoint_source.sql is applied (schema V242 live); Spark and R81.10 stay NOT_COVERED with a recorded reason by design. AUTOMATED_VALIDATED; matched findings were not re-measured in this refresh.
