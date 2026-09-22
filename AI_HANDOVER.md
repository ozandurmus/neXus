# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot
neXus UI2 deployed on HOST-A at schema V53, image built from `origin/main`.
Overview rebuilt as an exception-and-evidence screen (frozen contract, Fable draft + engineering notes).
Collision-free aiview pseudonyms; 645 MB of unread configuration text released; limits 2 GiB.
Evidence-weighted roadmap progress 57 % (was shown as 25 % from a stale plan).

# Recent session changes (2026-09-22 .. 23)
- Builds NXS-LOCAL-0363..0368: backup download/listing/compare, Configuration rebuild with cluster DIFF,
  platform identity facts, automation / Script Execution / scheduled-write decisions, menu-tour fixes,
  notifications and service view, Overview.
- Deploy tooling: `scripts/hosta_deploy.sh` (watched, fail-fast, 12-minute limit; host from local config).
- Plans: `docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md` (four PO decisions), backlog updated.
- Real customer names removed from four test files; they remain in an applied migration and in Git history.

# Exact next action
- Product Owner signs in as aiview and runs the Overview acceptance checklist
  (`docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md` §6).
- Then `cluster_diff_member_specific_tuning`, then `script_execution_module` slice 1.

# Test delta
- UI2 Gradle suites (service, persistence, worker, cli, capability-registry, architecture-tests): green.
- Frontend vitest: 20 files, 153 tests green (new Overview tests).
- Parity test pins the Java cluster DIFF projection to the TypeScript one.

# Risks
- The GitHub repository is public and its history holds internal design, host details and some real
  customer names (V33 migration, earlier commits). Visibility is the Product Owner's call.
- Notifications are unproven against real syslog / SMTP targets (PO needs permission first).
- 38 of 39 clusters show a member DIFF; part of it is per-member by nature and needs tuning.
