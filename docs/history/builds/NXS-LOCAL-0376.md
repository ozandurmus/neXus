# NXS-LOCAL-0376 — Failover pilot: P0 closure, admin window bypass, first Check Point pilot run, PAN write routes

## Summary

Incident release, the Active Up return gate, final post-checks, resume of the return step, failover CLI parity, PAN write routes and config-sync remediation, the VSX chassis unit and a framed one-shell session (off, per-cluster allow-list) shipped (V168-V169, V174-V175, V177-V179; PRs 653-680). The first Check Point pilot run on 2026-10-21 stopped before the up step and was fixed in PR 666; no completed real failover is recorded.

Evidence (2026-11-10 plan refresh): pilot run 2026-10-21 on one Check Point R81.20 ClusterXL cluster: after the down step the member reported admin_down plus the built-in Problem Notification, check 11 accepted only admin_down, so the run stopped before the up step (commit 29776915, PR 666). Resume of the return step (PR 656, contract RATIFIED 2026-10-20) is the recovery path. Argon reviews: ARGON_REVIEW_2026_10_20_WINDOW_AND_FAILOVER_P0.md, ARGON_REVIEW_2026_10_21_FAILOVER_RESUME.md. Not REAL_ENV_VALIDATED: no completed failover and return is recorded; backlog failover_pilot_preparation and failover_p0_closure_set stay in progress.
