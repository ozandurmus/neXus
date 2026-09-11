# pcp_storage_engine — PRODUCTION-SCOPED. Which engine backs the registry/job plane IN PRODUCTION, against the section 10 criteria? (The local sequencing question is separate and decided -- see pcp_local_control_plane_storage.)

## Options

- extend the existing opt-in PostgreSQL evidence backend
- another engine chosen against the criteria
- filesystem-only until measured need

## Recommendation

Still defer. The local Option A decision deliberately does NOT select the production engine, and SQLite is not proposed as one. Decide with DEV.4.6 migrations/roles.
