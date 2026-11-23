# NXS-LOCAL-0400 — Overview null-device fix, Estate UNKNOWN count fixes and golden refresh vacuum step

## Summary

A failed job without a target device no longer crashes the Overview screen (9a24f1bf), and Estate device logs, dossier Configuration, diagnostic runs and policy hygiene show any unreported count or stamp as UNKNOWN instead of OK or zero (1e09e4c4, 4733506a). The preview golden refresh runs vacuum without parallel workers and skips it instead of blocking a valid copy (4733506a).

## Batches

9a24f1bf, 4733506a (batch auto-1123-1458), 1e09e4c4 (2026-11-23, 14:50 through 15:10).

## Open

The vacuum failure cause on the host is UNKNOWN (shared-memory fit only); the next golden refresh run decides. Runbook: `docs/operations/PREVIEW_GOLDEN_DATABASE.md`.
