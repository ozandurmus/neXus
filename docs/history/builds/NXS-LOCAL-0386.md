# NXS-LOCAL-0386 — Platform and operations: /opt/nexus deploy tree, scheduled-job switches, credential canary, frontend fast path

## Summary

The HOST-A deploy tree moved to /opt/nexus under the service account (live since 2026-11-12), scheduled collection resumed with per-family switches (V203), the MDS export runs without a login shell, credential failures alert and a nightly canary checks them (V207), deploys fail on a dropped SSH connection, and frontend-only changes ship on a fast path. System-log masking reached logmask-v6 and aiview may start configuration collection and discovery.
