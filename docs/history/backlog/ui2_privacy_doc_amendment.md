# Amend PRIVACY_AND_DATA_HANDLING.md to name the UI 2.0 (PostgreSQL) database and its data classes

status: done · target: docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md §10

Flagged by NXS-LOCAL-0051 (C1) AC-7 self-check as a documentation-hygiene gap, not a contradiction with frozen authority. Folded into the C5 amendments bundle (NXS-LOCAL-0056, 2026-09-09) per this note's own instruction -- see ui2_b0_c5_amendments_bundle's note and docs/design/UI2_0_C5_AMENDMENTS_BUNDLE.md §5. New 'UI 2.0 database (Java product line)' section added to PRIVACY_AND_DATA_HANDLING.md, adjacent to the existing DEV.3.3 section, naming the dedicated mandatory PostgreSQL instance as CLASS 2 and naming audit_log/provenance_records/secrets_metadata plus every other table by the same instance-wide rule; no existing Line-1 rule in the document altered.
