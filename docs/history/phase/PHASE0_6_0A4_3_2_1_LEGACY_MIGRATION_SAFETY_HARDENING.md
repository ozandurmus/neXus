# Phase 0.6.0A4.3.2.1 — Legacy Migration Safety Hardening

## Scope

This is a narrow hardening build for the A4.3.2 legacy configuration-storage migration.
It does **not** change CP, VSX, PAN runtime, Panorama or PAN direct collector methods.
It does **not** change the content-addressed runtime storage contract used by new snapshots.

## Why this build exists

A4.3.2 correctly introduced vendor-neutral content-addressed storage, but review of the legacy
migration path found that `artifact_file` from legacy metadata was used to construct a filesystem
path without a strict snapshot-directory containment gate. A crafted/corrupt metadata value could
therefore escape the snapshot directory during migration.

The real pre-migration storage analysis supplied for this checkpoint was:

```text
History snapshots:       1295
SAME history events:     1140
Legacy payload files:    1295
Legacy payload size:     3.39 GiB
Unique legacy payload:   417.56 MiB
Existing CAS objects:    0
Projected net reclaim:   2.98 GiB (87.97%)
```

This confirms the CAS migration has high value, but destructive apply remains gated behind this
hardening and a reviewed dry-run.

## Changes

### 1. Legacy artifact path containment

Legacy `artifact_file` must now be a single relative filename. The migration rejects:

- empty artifact names
- `.` / `..`
- `/` or `\\` path separators
- POSIX absolute paths
- Windows absolute/drive/anchor paths
- symlink legacy artifacts
- resolved artifacts outside their snapshot directory
- metadata/snapshot paths outside the configured configuration root

Unsafe or malformed metadata makes dry-run/apply fail closed.

### 2. Analyzer hashes the actual legacy payload

`--storage-analyze` no longer uses a syntactically valid metadata SHA as the deduplication truth.
Every existing legacy payload is SHA-256 hashed from disk. The report now includes:

- payload hashes verified
- metadata SHA mismatch count
- untrusted/invalid metadata SHA count
- unsafe/malformed metadata count
- corrupt existing CAS object count

The analyzer remains non-destructive with respect to configuration/history/artifact data.
Use `py.exe -B` to also suppress Python bytecode-cache writes.

### 3. Existing CAS object integrity check in analysis

Existing SHA-named CAS files are checked for:

- symlink/containment problems
- filename SHA vs actual payload SHA mismatch

Corrupt objects are excluded from trusted existing-object accounting and surfaced in telemetry.

### 4. Migration fail-closed SHA gate

Dry-run/apply requires a valid legacy metadata SHA-256 and verifies it against the actual payload.
A mismatch or missing/invalid digest refuses migration before destructive work.

### 5. Exact pre-migration rollback state in manifest

Each operation now records exact base64-encoded pre-migration bytes for:

- `metadata.json`
- `sha256.txt` when present
- pre-existing `<artifact>.ref.json` when present

The manifest remains local/operator-sensitive and is explicitly marked:

```text
LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE
```

It can contain entity IDs and local filesystem paths and must not be put in a support bundle.

### 6. Apply-time revalidation

Immediately before mutating each legacy snapshot, the migration revalidates:

- safe artifact filename
- snapshot containment
- non-symlink artifact
- payload SHA-256

This reduces the time-of-check/time-of-use window between plan creation and mutation.

## Unchanged behavior

- New PAN snapshots continue to use A4.3.2 content-addressed storage.
- SAME snapshots reuse the existing object.
- CHANGED snapshots preserve every unique object.
- CAS objects are never deleted by this migration.
- Check Point remains storage-ready only; no CP configuration collector is introduced.
- Network Inventory collection and UI behavior are unchanged.
- `--storage-deduplicate` remains dry-run by default.
- `--apply` still requires explicit operator intent.

## Tests added

- traversal and Windows absolute path rejection
- symlink legacy-artifact rejection
- analyzer hashes actual payload instead of trusting metadata SHA
- migration rejects metadata/payload hash mismatch
- rollback manifest captures exact pre-migration state
- interrupted apply can be rerun without losing remaining payloads

## Validation

```text
pytest:              135 passed, 2 xfailed, 0 failed
python compileall:   PASS
node --check app.js: PASS
```

Known xfails remain the pre-existing baseline issues:

- VSX network canonicalization
- PAN default-route classification

## Operational gate

After installing this build, the first command remains analysis-only:

```powershell
py.exe -B .\main.py --storage-analyze
```

Do not run `--apply` directly. Review the hardened analysis output first. If all safety/integrity
counters are zero, the next step is the default dry-run:

```powershell
py.exe -B .\main.py --storage-deduplicate
```

The dry-run creates a **local-sensitive migration manifest** but does not delete legacy payloads.
