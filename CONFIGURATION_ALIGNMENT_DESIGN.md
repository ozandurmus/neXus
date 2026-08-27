# Configuration Alignment — Product Design

`Alignment` remains a section inside **Configuration**, not a separate top-level product module.

```text
Configuration
├── Overview
├── Devices
├── History
├── Diff
├── Alignment
└── Backups
```

## Questions Alignment should answer

1. Which Panorama Template Stack and Device Group own this firewall/vsys?
2. Does Panorama report Template / Shared Policy synchronization?
3. What does the firewall report as local active, merged, and effective-running configuration?
4. Are those evidence layers canonically equal or different?
5. Is a difference proven to be an intentional local override, Panorama out-of-sync, or unexpected effective drift?

A4 answers 1–4 at evidence level. A4.1 compiles Template Stack scalar precedence and Device Group policy lineage. A4.2 now implements bounded setting-level classification for alignment-ready Template-Stack scalar settings.

## Classification policy

Never infer `LOCAL_OVERRIDE` solely from `active != merged` or `Panorama target active != direct active`.

Use explicit states:

```text
CANONICALLY_ALIGNED
PANORAMA_OUT_OF_SYNC
DIFFERENCE_OBSERVED
UNKNOWN
INSUFFICIENT_EVIDENCE
```

A4.2 adds fact-level states only when the expected Panorama value and effective firewall value are mapped to the same normalized scalar key:

```text
ALIGNED
LOCAL_OVERRIDE
EFFECTIVE_DRIFT
PANORAMA_OUT_OF_SYNC
EXPECTED_ONLY
LOCAL_ONLY
UNKNOWN
```

`LOCAL_OVERRIDE` requires a differing expected value whose direct local-active scalar matches the effective value. `EFFECTIVE_DRIFT` requires no local-active explanation, merged=effective, and a known Panorama Template sync state. `EXPECTED_ONLY` and `LOCAL_ONLY` are observations, not automatic drift.

## Phase 0.6.0A4.1 — Expected compiler contract

A4.1 now implements the first expected-state compiler rather than only mapping assignments.

It compiles:

- Template Stack-level scalar overrides
- ordered Template scalar precedence (top-to-bottom, highest priority first)
- source provenance for each compiled scalar setting
- firewall → Template Stack mapping
- firewall/vsys → Device Group mapping
- Device Group parent lineage
- Panorama pre/post policy evaluation lineage and rule counts

It intentionally defers:

- template-variable substitution
- generic `<member>`/ordered-list merge semantics
- final Device Group object value precedence
- Device Group policy/object value alignment

This keeps the Alignment model evidence-first: A4.1 proves **what Panorama expects and where it came from** for a bounded set of settings; A4.2 compares those scalar settings with direct effective-running facts while leaving unresolved variables and non-scalar collections explicitly outside the drift contract.

## Phase 0.6.0A4.2 — Setting-level alignment contract

A4.2 normalizes the root `/config/devices/entry` identity so Template and firewall trees can be compared without relying on the literal root device name. Other named entries remain semantic.

The engine compares hashed scalar values only; raw values are not copied into the derived alignment manifest. Detailed paths/hashes stay local under `data/derived/panorama_alignment/`, while the shareable support bundle exposes counts/categories only.

Template-Stack setting alignment uses Panorama **Template** sync state. Shared Policy sync belongs to the later Device Group policy alignment plane and must not classify a Template setting.

## Phase 0.6.0A4.2.1 — Semantic validation boundary

The setting-level engine is not allowed to validate itself by producing more classifications. A4.2.1 adds a separate semantic-validation layer:

```text
A4.2 classifications
        │
        ├── deterministic operator samples
        │
        └── conservative schema-equivalence hypotheses
                    │
                    ▼
            MANUAL CONFIRMATION
```

`POSSIBLE_SCHEMA_EQUIVALENT` is a coverage hypothesis only. It requires the same scalar hash/category/leaf plus strong path-shape evidence and is never automatically promoted to `ALIGNED`.

`LOCAL_OVERRIDE` samples are verified against Panorama Template/Stack provenance and direct firewall local/effective state. Vendor-supported local overrides and device-specific variables remain valid configuration states; the future UI must present them separately from compliance violations or unexplained drift.

Detailed sample paths/values remain local-only. Shareable support includes counts only.

## Phase 0.6.0A4.2.2 — Semantic policy and identity normalization

A4.2.1 operator validation showed that equal-looking XML comparison rules are not sufficient for all PAN configuration domains. A4.2.2 therefore inserts a semantic-policy layer **before** generic mismatch classification.

```text
Expected scalar
      │
      ▼
Semantic policy
      │
      ├── directly comparable
      ├── member-specific HA
      ├── provenance guard
      └── identity resolver
              │
              ▼
       Effective scalar
```

The confirmed VSYS case is especially important. PAN-OS has a stable/internal VSYS identifier (`vsysN`) and a separate display name. The direct effective configuration is used to build a per-device identity map. A Template path/value using the display name is canonicalized to the matching internal ID only when the mapping is unique.

Therefore:

```text
Friendly-VSYS ↔ vsys5
```

is `ALIGNED` when the identity map proves equivalence. It is never a local override merely because the strings differ.

The same resolver also canonicalizes VSYS selectors embedded in expected paths, reducing false `EXPECTED_ONLY`/`LOCAL_ONLY` pairs caused by friendly-name versus internal-ID path representations.

New non-finding semantic states:

```text
MEMBER_SPECIFIC
PROVENANCE_UNVERIFIED
IDENTITY_TRANSLATION_REQUIRED
```

`LOCAL_OVERRIDE` remains reserved for a directly-comparable setting with trusted expected provenance where the effective value differs from expected and matches the local-active value.

## Phase 0.6.0A4.3 — Configuration UI foundation

A4.3 makes Alignment a first-class **Configuration** experience while preserving the module boundary with Network Inventory.

```text
SecurityExpert
├── Overview
├── Network Inventory
└── Configuration
    ├── Overview
    ├── Alignment
    ├── Evidence
    ├── History
    └── Backup
```

The UI is intentionally semantic rather than XML-diff-centric. `LOCAL_OVERRIDE` is amber operator attention, `EFFECTIVE_DRIFT` / `PANORAMA_OUT_OF_SYNC` are red investigation states, `MEMBER_SPECIFIC` is informational, and provenance/identity/expected-only/local-only states are evidence coverage rather than failures.

The local Alignment UI may expose a bounded setting path and expected-source name from the local derived manifest, but it does not embed raw configuration values or value hashes. Large `EXPECTED_ONLY` / `LOCAL_ONLY` populations remain category counts.

`Evidence` explicitly distinguishes direct effective-running (primary) from merged/local-active/Panorama supporting layers. `History` exposes the current FIRST/SAME/CHANGED signals; full chronological diff remains later. `Backup` is only a 0.6.0B placeholder so config evidence is never presented as a recovery artifact.

---

## A4.3.1 — Vendor-neutral Configuration IA

The product boundary is now explicit:

```text
Configuration -> current actual device state
Alignment     -> expected vs current reconciliation
Policy & Objects -> security policy/NAT/object management plane
History       -> temporal change
Evidence      -> collection provenance
Backup        -> native recovery artifact
```

The current Configuration view must not be a Panorama-alignment dashboard. PAN is only the first adapter. A Check Point Gaia adapter and a VSX Host/VS adapter must be able to populate the same `current actual configuration` concept without inventing Template semantics.

For PAN, selected non-secret values are projected locally from direct `effective-running`. `PAN`, `LOCAL`, `OVERRIDE` and `MEMBER` are compact origin hints; detailed expected/current evidence remains in Alignment. Raw XML and secret-bearing values are not embedded in the browser payload.

Configured-vs-runtime interface/route reconciliation and vendor-native config rendering remain explicit backlog items rather than duplicating Network Inventory in this phase.

## A4.3.2 — Configuration history/storage contract

Configuration history is logically independent from vendor collection method. The product now separates **history events** from **payload objects**:

```text
history event -> artifact SHA-256 -> immutable object
```

`SAME` retains the observation event but reuses the existing object. `CHANGED` creates a new object and keeps prior versions for diff/history. This contract applies to PAN XML today and is explicitly compatible with future Check Point Gaia/Clish text evidence and native binary recovery artifacts.

Check Point collection is not introduced in A4.3.2; only the storage interface is made ready for it. This keeps storage migration failures distinguishable from future CP transport/parser failures.
