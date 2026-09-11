# content_addressed_history — Content-Addressed Configuration History

## summary

Stores one immutable object per unique configuration payload while history snapshots reference shared content.

## why

Preserves history without duplicating identical multi-gigabyte configuration payloads.

## criterion note (distributed_metadata_backend)

backlog.json distributed_evidence_store_migration, AUTOMATED_VALIDATED 2026-08-31. utils/evidence_backend.py moves the CAS metadata index, run manifests, last-known-good and scheduler state behind backends: filesystem by default (unchanged), PostgreSQL when SECURITYEXPERT_EVIDENCE_BACKEND=postgres. Content-addressed payload blobs are explicitly out of scope and never leave the runtime volume. Verified against a real local PostgreSQL 16, including a two-real-subprocess test proving concurrent writers never lose each other's last-known-good entities. Multi-container real-environment evidence still owed (server-blocked, DEPLOY.1).
