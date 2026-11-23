# NXS-LOCAL-0402 — Estate dossier Management tree scope and malformed-payload hardening

## Summary

The Estate dossier Management tree block renders only for role management_server, excluding BlueCoat and Fortinet (the standard Inventory scope). The tree value, every domain and every node are validated before reading; a malformed payload is UNKNOWN "Management tree could not be read", never a crash or 0/OK (14277931).

## Batches

14277931 (2026-11-23, 20:29). Frontend only; no schema change.

## Open

Not real-environment validated.
