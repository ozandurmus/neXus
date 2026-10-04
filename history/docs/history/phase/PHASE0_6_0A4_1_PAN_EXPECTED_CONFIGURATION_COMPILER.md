# Phase 0.6.0A4.1 — PAN Expected Configuration Compiler

## Goal

A4.1 converts the Panorama intent inventory introduced in A4 into a conservative, machine-readable **expected configuration model** without pretending to reproduce every private PAN-OS/Panorama merge rule.

The actual-state authority remains direct firewall `show config effective-running`. Panorama remains the discovery and intent authority.

```text
Panorama active management config
        │
        ├── Template
        ├── Template Stack
        │     ├── stack-level overrides
        │     └── ordered templates
        ├── Device Group hierarchy
        └── firewall / vsys assignments
                    │
                    ▼
        Expected Configuration Compiler
                    │
          ┌─────────┴──────────┐
          ▼                    ▼
 Template expected       Policy lineage
 scalar manifest         / provenance
          │                    │
          └─────────┬──────────┘
                    ▼
         A4.2 setting-level alignment
                    │
                    ▼
         direct effective-running
```

A4.1 is read-only. It performs no commit, push, override, save, export, or remote mutation.

## Why the compiler is conservative

Panorama configuration is not a generic XML overlay. Some settings are scalar, some are named objects, some are ordered lists, and some use variables. Device Group object inheritance can also be configured with different ancestor/descendant precedence behavior.

A4.1 therefore compiles only semantics that are sufficiently well-defined for this phase and marks the rest as deferred rather than guessing.

## Template Stack precedence contract

The compiler uses this source priority:

```text
1. Template Stack-level override
2. First template in the stack
3. Second template in the stack
4. ...
```

Within the Template Stack, templates are interpreted top-to-bottom, highest priority first. The first scalar definition for the same normalized setting path wins. Stack-level values are treated as overrides above inherited templates.

For every selected scalar setting the local manifest stores:

- normalized local-only setting path
- SHA-256 of the path
- SHA-256 of the value
- source type (`template_stack_override` or `template`)
- source name
- source priority
- whether the setting is alignment-ready

**Raw configuration values are not copied into the compiled manifest.**

## What is deliberately not compiled yet

### Template variables

Values beginning with `$` are detected as template-variable references and marked:

```text
alignment_ready = false
```

A4.1 does not substitute Template, Template Stack, or firewall-specific variable values. This prevents false drift when the expected Panorama value is a variable but the effective firewall value is already resolved.

### Arbitrary list/collection merge semantics

Repeated un-keyed XML leaves, especially `<member>` lists, are inventoried but excluded from the scalar manifest. Named `<entry name="...">` nodes remain addressable because their identity is explicit.

### Device Group object value precedence

A4.1 inventories Device Group object collections but does not compile final object values. Panorama can use descendant-over-ancestor object precedence or an optional ancestor-precedence behavior. Until that mode is explicitly resolved from evidence, object-value alignment is deferred.

### Vendor merge-engine equivalence

A4.1 does **not** claim:

```text
compiled_expected == byte-identical Panorama pushed config
```

The output is an evidence-oriented expected manifest for facts with proven precedence semantics.

## Device Group policy lineage

For every firewall/vsys Device Group assignment, A4.1 resolves the parent chain up to Shared and records policy evaluation lineage.

Example:

```text
Shared
  └── DG-GLOBAL
       └── DG-SITE
            └── DG-FIREWALL
```

Expected rule order is represented as:

```text
PRE-RULES
Shared
→ highest ancestor
→ descendants
→ direct Device Group
→ local firewall rules

POST-RULES
local firewall rules
→ direct Device Group
→ ancestors
→ Shared
```

A4.1 counts pre/post rules for Security, NAT, QoS, PBF, Decryption, Application Override, Captive Portal, and DoS rulebases without exporting rule names or values to the support bundle.

## Compiler anomalies

A4.1 explicitly detects:

- firewall without a Template Stack assignment
- firewall assigned to multiple Template Stacks
- Template Stack referencing a missing Template
- Device Group parent cycle
- Device Group missing parent
- selected firewall absent from the compiled Panorama assignment model

It does not silently select an arbitrary source when an assignment is ambiguous.

## Local artifacts

Full local-only compiler manifest:

```text
data/derived/panorama_expected/<run_id>/expected-compiler.json
```

This contains real Template/Stack/Device Group names and hashed expected values/paths, but no copied raw configuration values.

Compact operator report:

```text
output/pan_expected_compiler_<run_id>.json
```

It contains real assignment names and compiler status/counts for troubleshooting, but not the full setting manifest.

Both are local evidence and must not be committed or shared externally.

## Shareable support bundle

`output/config_support_<run_id>.zip` includes only privacy-safe compiler telemetry:

- compiler status
- number of assigned serials
- count of exactly-one-stack mappings
- count of multiple-stack mappings
- missing-template-reference count
- compiled scalar setting counts
- alignment-ready setting counts
- unresolved variable counts
- per-device stack/DG scope counts
- HMAC-pseudonymized primary stack identity
- bounded anomaly enums

It does **not** include:

- raw Panorama configuration
- raw firewall configuration
- expected setting paths
- expected value hashes/manifests
- real Template/Stack/Device Group names
- credentials or API keys

## Gate semantics

A4.1 preserves the existing A3/A4 `stage_pass` primary-evidence contract for regression compatibility:

```text
identity verified + effective-running available
```

A4.1 adds a stricter gate:

```text
expected_compiler_gate
```

which requires:

- compiler available
- every selected firewall mapped to a Template Stack
- no multiple-stack assignment anomaly
- no missing referenced Template

A separate policy-lineage gate requires every selected firewall to have at least one Device Group scope and all resolved parent chains to compile without a cycle/missing parent.

The combined phase result is:

```text
a4_1_stage_pass = stage_pass
                  AND expected_compiler_gate
                  AND expected_policy_lineage_gate
```

Unresolved variables and omitted non-scalar list semantics reduce coverage but do not by themselves make the compiler gate fail.

## Run

From the management-reachable VM:

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3
```

Do not increase worker count merely because compiler work is local. Direct firewall collection remains the rate-limiting and operationally sensitive portion.

## What to send for validation

Send only:

```text
output/config_support_<run_id>.zip
```

Keep these local unless a specific diagnostic requires them:

```text
output/pan_expected_compiler_<run_id>.json
data/derived/panorama_expected/<run_id>/expected-compiler.json
output/pan_config_failures_<run_id>.json
```

## Next phase

A4.2 will consume this expected manifest and direct `effective-running` evidence to perform setting-level alignment only where both sides can be mapped with sufficient confidence.

Candidate classifications:

```text
ALIGNED
LOCAL_OVERRIDE
EFFECTIVE_DRIFT
PANORAMA_OUT_OF_SYNC
PANORAMA_ONLY
LOCAL_ONLY
UNKNOWN
INSUFFICIENT_EVIDENCE
```

A4.2 must not classify unresolved template variables or unsupported collection semantics as drift.
