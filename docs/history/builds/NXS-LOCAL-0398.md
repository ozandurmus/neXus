# NXS-LOCAL-0398 — Window-preserving requeue (V263), checkpoint-and-yield hardening, Estate view review fixes and debt splits

## Summary

Deferred in-window jobs stay claimable for a bounded grace after the window ends (a4b00131, V263), Check Point resume recollects a changed domain and read jobs yield at unit boundaries, and module_deploy keeps ownership on routine replacement with the backup cutover prepared, not performed. Nine ship batches (7f8c3a40 through c039ff22) add the Estate dossier Policy hygiene, Configuration and logs blocks, System Roles and queue fixes and Reports standard-view links, and make missing or non-finite counts UNKNOWN instead of zero.

## Batches

7f8c3a40, 92f3e8e6, a4b00131, 2e8282b8, ccc7353b, 51f0b301, dc6e1cd9, 52a7584e, c039ff22 (ship batches auto-1123-0146 through auto-1123-0715, all 2026-11-23).

## Open

Backup module cutover is prepared, not performed (`docs/operations/BACKUP_MODULE_CUTOVER.md`); it needs a separate Product Owner authorization reference.
