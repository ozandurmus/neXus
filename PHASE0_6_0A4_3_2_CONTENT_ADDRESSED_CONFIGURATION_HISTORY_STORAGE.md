# Phase 0.6.0A4.3.2 — Content-Addressed Configuration History & Storage

## Decision

This phase fixes configuration-evidence storage growth before native backup artifacts are introduced.

The storage layer is **vendor-neutral now**, but collector scope remains intentionally unchanged:

- Palo Alto configuration evidence is wired to the new store in this phase.
- Check Point is **storage-ready** through the same API (`write_text_snapshot`) for future Gaia/Clish `show configuration` evidence.
- Check Point collection method, authentication, parsing and promotion rules are **not** changed here. Mixing those changes with a storage migration would make failure attribution and regression harder.
- Future native backup binaries can use the same content-addressed store through `write_binary_snapshot`.

## Problem fixed

Pre-A4.3.2 history wrote the full configuration payload into every snapshot directory even when the SHA-256 was identical to the previous snapshot.

Old physical behavior:

```text
data/configs/<source>/<entity>/run-1/effective.xml   # SHA-A
data/configs/<source>/<entity>/run-2/effective.xml   # SHA-A duplicate
data/configs/<source>/<entity>/run-3/effective.xml   # SHA-A duplicate
data/configs/<source>/<entity>/run-4/effective.xml   # SHA-B
```

`change_state=SAME` was logically correct, but physical storage still grew.

## New storage model

Large payloads are now content-addressed by SHA-256:

```text
data/
├── artifacts/
│   └── config/
│       └── sha256/
│           ├── ab/<sha256-A>
│           └── 73/<sha256-B>
│
└── configs/
    └── <source>/<entity>/
        ├── <snapshot-1>/
        │   ├── metadata.json
        │   ├── sha256.txt
        │   └── <logical-name>.ref.json
        ├── <snapshot-2>/   # SAME -> metadata/reference only
        └── <snapshot-3>/
```

The snapshot history remains immutable and timestamped. Payload bytes are stored only once per unique SHA-256.

### SAME

```text
collect -> validate -> SHA-256 -> object already exists
                                -> reuse object
                                -> publish small history metadata/reference
```

No duplicate configuration payload is written.

### CHANGED

```text
SHA-A -> existing immutable object
SHA-B -> new immutable object
```

Both versions remain available for future History/Diff.

## Integrity and atomicity

For a new object:

1. Validate the vendor evidence.
2. Compute SHA-256 in memory.
3. Write to a temporary object file.
4. Recompute SHA-256 from disk.
5. Atomically publish the immutable object.
6. Atomically publish snapshot metadata/reference.

An existing content-addressed object is hash-verified before reuse.

A snapshot metadata directory never contains a second full payload copy.

## Vendor-neutral API

`ConfigEvidenceStore` now exposes:

```text
write_xml_snapshot()    # PAN today
write_text_snapshot()   # CP Gaia/Clish ready; collector comes in 0.6.1
write_binary_snapshot() # native recovery artifacts later
```

Example future Check Point use:

```text
source        = checkpoint-gaia
artifact_type = gaia_show_configuration
artifact_name = show-configuration.txt
method        = direct_ssh_clish_show_configuration
```

This is a storage contract only. A4.3.2 does not start collecting Check Point configuration.

## Current-run storage telemetry

PAN config support telemetry now includes safe aggregate counters:

```text
storage_artifact_events
storage_new_objects
storage_reused_objects
storage_logical_payload_bytes
storage_new_object_bytes
storage_dedup_bytes_avoided
```

No configuration values or local storage paths are added to the shareable support bundle.

## Existing 4+ GB legacy data

Do not delete `data/configs` manually before analysis.

### Analyze only

```powershell
py.exe .\main.py --storage-analyze
```

This requires no device credentials and changes no files. It reports:

- history snapshot count
- SAME event count
- legacy payload file count/bytes
- unique payload bytes
- existing CAS object count/bytes
- projected net reclaim
- per-source storage totals

### Migration dry-run

```powershell
py.exe .\main.py --storage-deduplicate
```

Dry-run is the default. It writes a local migration manifest under `output/` and changes no configuration evidence.

### Apply migration

Only after reviewing the dry-run:

```powershell
py.exe .\main.py --storage-deduplicate --apply
```

Per legacy payload the order is:

1. verify legacy SHA-256
2. create/reuse CAS object
3. verify CAS SHA-256
4. atomically update metadata/reference/sha256.txt
5. remove legacy payload copy

The migration manifest is written **before** destructive work begins. Each operation records the legacy path and its CAS object path, so the removed payload can be reconstructed if rollback is required. CAS objects are never deleted by this migration.

## Retention

This phase implements de-duplication, not destructive retention.

- `SAME`: reference-only history.
- `CHANGED`: keep all unique versions.
- no automatic age-based deletion yet.

Retention tiers (for example detailed / daily / monthly) belong to a later server-storage policy after History/Diff and native backup requirements are measured.

## Production direction

The local model intentionally maps cleanly to the future server design:

```text
PostgreSQL
  device/snapshot/history/artifact metadata

Object storage / PVC
  immutable content-addressed payload objects
```

Collector flow:

```text
collect -> validate -> hash -> object exists?
                           ├─ yes: reference only
                           └─ no: atomic object publish
```

## Validation

A4.3.2 adds tests for:

- SAME PAN XML -> one physical object
- CHANGED PAN XML -> both versions preserved
- Check Point-style text evidence -> same vendor-neutral store
- duplicate legacy storage analysis
- dry-run migration makes no changes
- applied migration creates one verified CAS object and removes duplicate legacy payload copies only after metadata publication

