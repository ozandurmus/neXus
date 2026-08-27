# 0.6.3 — Unified Configuration History + Diff UX

## Status

**DONE — release closure confirmed 2026-08-27**

Product baseline at start: `0.6.1D REAL_ENV_VALIDATED`.

Release evidence: 14 targeted tests passed; 30 impacted regression tests
passed with 1 skipped; `--render-only` passed; repository privacy gate passed
with 0 findings. Commit-scope review confirmed no collector, CAS
write/migration, polling, concurrency, network, native-backup or `main.py`
semantic change. Release commit `2a2d245` was pushed to `origin/main`.

This is a bounded TRACE increment. It turns existing immutable
`FIRST`/`SAME`/`CHANGED` evidence history into an operator-usable,
local-only device history and safe change-summary experience. It does not
change collection, storage immutability, vendor command behavior, or recovery
semantics.

## Objective

For one configuration entity and one compatible artifact type, let an operator:

1. see a chronological, artifact-scoped snapshot timeline;
2. select two retained snapshots; and
3. view a bounded, secret-safe normalized change summary.

The Configuration plane remains current actual state. Alignment remains the
separate expected-versus-current plane. History answers *when observed
configuration evidence changed*; it does not claim policy intent, root cause,
compliance impact, or recovery readiness.

## Scope

### In scope

- Local `ConfigEvidenceStore` metadata query for a single source/entity/artifact
  history.
- Chronological timeline rows derived from successful immutable snapshot
  metadata.
- Safe, server-side/in-process normalized comparison for compatible PAN
  XML history objects.
- Additive History tab rendering in the existing static HTML Configuration UI.
- Explicit unavailable/insufficient-evidence states for unsupported or
  non-comparable history.
- Deterministic synthetic tests for query, timeline ordering, selection,
  normalization, redaction and UI payload contracts.

### Explicitly out of scope

- New device or management-plane commands, collector changes, polling,
  scheduling, concurrency, retries, or network access.
- CAS/history migration, retention, deletion, object mutation, or storage-schema
  replacement.
- Browser access to filesystem/CAS, an HTTP history API, server persistence, or
  fleet-wide timeline/diff.
- Raw/native configuration viewer, raw XML/text diff, artifact download, hash or
  object-path exposure.
- CP raw Gaia configuration persistence or exposure.
- Alignment, compliance, root-cause, event intake, alerting, backup or restore
  semantics.
- Cross-device/member comparison. ClusterXL and VSX member differences remain
  `MEMBER_SPECIFIC` semantics and are never converted to drift by history.

## Architecture decision

### Selected option

Implement a **device-scoped, artifact-scoped timeline plus safe normalized
comparison**. The HTML export is self-contained: all history query and diff
work completes during local payload construction; the browser renders only the
safe projection.

This preserves the static/exportable UI architecture and avoids introducing a
new browser-to-sensitive-storage data path before the server/RBAC foundation.

### Components

| Component | Responsibility | Contract boundary |
| --- | --- | --- |
| `ConfigEvidenceStore` | Read successful snapshot metadata for one `source + entity_id + artifact_type`; resolve compatible immutable objects locally. | Read-only. No mutation, migration, retention or collector responsibility. |
| `ConfigHistoryService` (new) | Validate scope, order timeline rows, select compatible pairs, derive bounded safe PAN diff rows. | Never emits raw payloads, SHA-256 values, object paths, management IPs or secret values. |
| PAN adapter | Parse retained XML only in process, extract the existing allowlisted structured configuration projection, then compare normalized fields. | No raw XML reaches UI or shareable output. Unsupported/invalid history is explicit. |
| CP adapter | Supplies secret-aware timeline state only in this increment. | No raw/redacted Gaia text line diff; a historic structured safe projection is required before CP diff can be enabled. |
| `build_configuration_ui_payload()` | Adds an optional, local-only `history_v1` projection while preserving existing current/evidence/alignment fields. | Older callers and unavailable history remain valid. |
| Static UI | Renders timeline, selects a compatible pair already present in payload, and renders supplied normalized diff rows. | No filesystem, CAS or raw-content access. |

## Data and API contracts

### Timeline query contract

A new read-only store/service method has this logical signature:

```text
list_history(source, entity_id, artifact_type, limit) -> HistoryTimeline
```

Rules:

- `source`, `entity_id` and `artifact_type` are exact stored metadata scope,
  not user-provided paths.
- Only `metadata.json` entries with `status == "success"`, a valid
  `collected_at`, and a valid snapshot directory are eligible.
- Sort chronologically descending using `collected_at`, with snapshot directory
  name only as a deterministic tie-breaker.
- Ignore malformed/unreadable metadata entries and count them in a safe,
  value-free diagnostics field; do not fail a healthy timeline.
- Limit is fixed by implementation policy (recommended maximum: 50 timeline
  events per artifact) and is surfaced as a boolean `truncated`, never by
  silently misrepresenting chronology.
- `FIRST`, `SAME` and `CHANGED` are historical observation states only; they
  are not a security finding, drift verdict or backup validity claim.

A safe timeline event contains only:

```json
{
  "id": "opaque-local-snapshot-id",
  "collected_at": "timestamp",
  "change_state": "first|same|changed",
  "artifact_type": "logical-type",
  "status": "available|unavailable",
  "comparison_eligible": true
}
```

It must not contain `sha256`, object path, snapshot filesystem path,
`management_ip`, credentials, raw configuration, raw configuration line,
or secret-withheld source content.

### History UI payload contract

Each configuration device gains an additive optional field:

```json
{
  "history_v1": {
    "status": "available|insufficient_evidence|unavailable",
    "scope": "single_entity_single_artifact",
    "artifacts": [],
    "privacy": {
      "raw_configuration_included": false,
      "value_hashes_included": false,
      "artifact_paths_included": false,
      "credentials_included": false
    }
  }
}
```

For each artifact, the payload contains its display label, timeline events and
zero or more pair results. A pair result references timeline event `id` values,
not hashes or paths. It uses exactly one of:

- `available`: normalized safe diff rows are supplied;
- `insufficient_evidence`: snapshots exist but no safe compatible comparison is
  available; or
- `unavailable`: local history cannot be read or is not attached to this export.

Pair results are bounded to the latest compatible `CHANGED` transition per
artifact for the initial build. The static UI may select any available pair
already supplied in the payload; it must not calculate a diff from raw content.
The service emits at most 100 diff rows per pair and reports a value-free
`truncated` flag if more safe changes exist.

### Safe normalized diff row

A diff row is semantic and allowlisted, never a source line or XML fragment:

```json
{
  "section": "system|dns|ntp|management|high_availability|interfaces|other_allowlisted",
  "setting": "operator-facing safe label",
  "change": "added|removed|modified",
  "before": "safe selected value or null",
  "after": "safe selected value or null",
  "scope": "local|central|member_specific|unknown"
}
```

`before` and `after` are present only when the same field is already eligible
for the current local Configuration UI. A field is withheld rather than
hashed, tokenized or partially reconstructed when it is secret-bearing,
ambiguous or outside the allowlist. Any withheld count is aggregate only.

## Vendor-specific comparison rules

### Palo Alto

- Eligible artifact type starts with direct `effective-running` evidence.
- Both retained objects must be validated XML and use a compatible projection
  version.
- Parse objects locally with hardened XML settings. Extract the existing
  allowlisted structured current-configuration projection, normalize ordering,
  then compare its semantic keys.
- Active, merged and Panorama-control artifacts may appear in the timeline but
  do not become an actual-state diff baseline in this increment.
- A diff is a current-history observation. It does not alter existing PAN
  alignment classifications such as `LOCAL_OVERRIDE` or `EFFECTIVE_DRIFT`.

### Check Point

- The secret-aware Gaia `actual` artifact participates in timeline display.
- Raw and redacted `show configuration` text are not rendered or line-diffed in
  browser payloads.
- A CP pair has `insufficient_evidence` until a future approved build retains a
  versioned historic structured safe projection. Raw canonical fingerprint
  change detection may remain operational metadata but never appears in the
  payload.
- ClusterXL/VSX physical endpoint and VSID identity boundaries are preserved.
  Two distinct members or virtual systems are never selected as one pair.

## Compatibility and failure semantics

- Existing `ConfigEvidenceStore` write methods and `SnapshotResult` stay
  source-compatible.
- Existing Configuration UI consumers remain valid if `history_v1` is absent.
- An empty history, a single snapshot, incompatible artifact types, absent CAS
  object, invalid XML, malformed metadata, or a projection failure produces an
  explicit safe unavailable state; it never falls back to raw content.
- `SAME` events are visible in timeline but do not create a fabricated diff.
- No device identity is inferred from path names. The stored source/entity
  scope remains authoritative.

## Privacy and security contract

1. Raw configuration payloads, XML fragments, Gaia lines, SHA-256 values,
   object/snapshot paths, credentials, PSKs, tokens and secret values must not
   enter `history_v1`, the HTML export, tests, project metadata or logs.
2. The browser sees only the safe projection generated during local execution.
3. Secret-bearing/unknown fields fail closed: omit values and report a generic
   aggregate withheld count where needed.
4. UI output remains local-only; this build does not alter sharing/export/RBAC
   policy.
5. No new storage write or device network access is permitted.
6. Tests use synthetic values only.

## Implementation plan

1. **Contract tests first** — add synthetic fixture builders for immutable
   PAN XML and CP redacted-text snapshots; lock query ordering, status and
   privacy assertions.
2. **Read-only history service** — implement safe metadata enumeration,
   timeline projection, artifact compatibility and explicit failure states.
3. **PAN projection adapter** — reuse existing safe current-configuration
   projection logic to derive normalized semantic keys in memory; implement
   bounded pair comparison and withheld-field behavior.
4. **Configuration payload integration** — attach `history_v1` only when a
   local history service/store is available; preserve the current payload for
   render-only and old callers.
5. **UI refinement** — replace the History placeholder with device-scoped
   timeline and pair-summary rendering, retaining the existing History tab,
   accessibility and responsive patterns.
6. **Validation** — targeted history/diff tests, existing configuration UI and
   storage regression, static render validation, privacy gate, then broaden
   regression according to actual changed shared-core surface.
7. **State update/handover** — update project metadata only after evidence
   supports the next lifecycle state. Human real-environment validation is not
   required unless implementation adds network-facing behavior (which this
   contract prohibits).

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Timeline lists synthetic successful snapshots in deterministic descending chronological order and identifies `FIRST`, `SAME`, `CHANGED`. |
| AC-2 | Query scope is exactly one source/entity/artifact; no cross-device, ClusterXL peer or VSX-context mixing occurs. |
| AC-3 | PAN compatible historical effective-running objects produce deterministic normalized `added`, `removed`, `modified` rows. |
| AC-4 | Same-content events produce no fabricated changed-field result. |
| AC-5 | CP history renders the secret-aware timeline but returns `INSUFFICIENT_EVIDENCE` for raw/text diff. |
| AC-6 | Missing/malformed metadata, object or XML produces safe explicit state without raw fallback or UI failure. |
| AC-7 | UI supports timeline and supplied compatible-pair selection; no browser filesystem/CAS request exists. |
| AC-8 | Browser payload and rendered export contain no raw config, secret fixture value, value hash, SHA-256, artifact path, credential or management address. |
| AC-9 | Existing Configuration, Alignment, CP collector and CAS immutability/storage tests remain green. |

## Merge gate

Merge to `main` is blocked until:

- all acceptance criteria have targeted automated evidence;
- impacted configuration UI, CP configuration and CAS/storage regression pass;
- static `--render-only` behavior remains healthy when configuration history is
  absent;
- repository privacy gate passes with zero findings;
- diff review confirms no collector, CAS write/migration, polling, concurrency,
  network or native-backup semantic change.

## Definition of done

The build may advance to `AUTOMATED_VALIDATED` only after all merge-gate
conditions pass. It remains a local UI/read-only history increment; no
real-environment device collection is part of this architecture contract.

## Deferred follow-up

- CP safe historic structured projection and semantic diff, under a separate
  privacy/storage contract.
- Fleet timeline, cross-device comparison and alerting.
- API-backed/server history browsing, RBAC and export authorization.
- Alignment/compliance impact correlation.
- Native backup/restore-readiness integration.
