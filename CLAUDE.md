# CLAUDE.md --- SecurityExpert

## Communication

User-facing conversation, analysis, build summaries and validation
instructions are **Turkish**. Code identifiers, technical artifact names
and vendor-native commands remain English where appropriate. Do not
translate vendor CLI commands/API fields/schema identifiers.

## Mission

SecurityExpert is a multi-vendor network-security evidence platform:
`SEE → VERIFY → TRACE → RECOVER → OPERATE`.

Current line: `0.6.1B.1.2`. Read `CURRENT_STATE.md` for the latest
validation/blockers.

This is not greenfield. Preserve real-device validated behavior.

## Context order

1.  this file,
2.  `CURRENT_STATE.md`,
3.  current `project/*` metadata,
4.  current phase doc,
5.  relevant source/tests,
6.  full Continuation Pack only if necessary.

Do not scan historical docs/runtime output by default.

## Core rules

-   Incremental changes; no broad rewrite without evidence/approval.
-   Evidence over assumptions; UNKNOWN over guessed semantics.
-   Preserve mature Network Inventory.
-   Configuration = current actual configured state.
-   Alignment = expected/central intent vs actual.
-   Management plane = discovery/topology/intent.
-   Direct device = actual evidence.
-   Secrets never enter normal UI/shareable support.
-   No automatic device write/change operations.
-   Do not weaken identity gates for coverage.
-   Do not increase CP polling concurrency while device-load safety is
    unresolved.

## Check Point

Enterprise admin login shell in this environment is Expert. From Expert
use explicit Gaia Clish (`clish -c ...`).

Quantum Spark/Gaia Embedded/direct-Clish behavior is capability/evidence
driven. Do not infer Spark solely from direct Clish or naming.

VSX actual identity = `physical endpoint + VSID`; `vsenv <VSID>` is a
validated Expert-shell context mechanism.

ClusterXL member differences are MEMBER_SPECIFIC unless expected-state
evidence proves drift.

Raw `show configuration` is sensitive: process in memory, withhold
secrets, never expose/persist raw secret-bearing config casually.

Production SSH requires trusted host keys.

## Palo Alto

Panorama = discovery/intent/provenance. Direct firewall =
actual/effective evidence. Primary current config = effective-running.
Direct identity verification is mandatory. Production TLS requires
corporate CA trust.

## Storage

Immutable CAS: - SAME = reference only. - CHANGED = new unique object
while preserving history. Destructive retention/migration requires
explicit design and approval.

## Privacy

Read `PRIVACY_AND_DATA_HANDLING.md`.

Default: do not scan `data/`, `output/`, logs, CAS runtime objects,
support artifacts or credential stores.

A narrow local-sensitive record may be inspected only for a concrete
task; do not dump whole sensitive files into conversation.

## Development

Read `AI_DEVELOPMENT_PROTOCOL.md` when changing workflow or doing a
meaningful build.

Use partial modes where possible: - `--render-only` -
`--only pan-config` - `--only vsx` - `--only cp` -
`--cp-config-collect --cp-config-stage all`

Normal `main.py` is an integration checkpoint.

Targeted tests first. Full regression only when justified.

## Current validated state

B.1.2 interactive direct-Clish collection is REAL-ENV PASS. Overall CP
Configuration coverage remains PARTIAL (101/122). See
`CURRENT_STATE.md`.

## Immediate priority
Engineering track: `DEV.1 — Corporate Git Development Foundation`. DEV.0.3A/B are complete; DEV.0.3C History/CAS is deferred pre-server. DEV.0.4 currently blocks Corporate Git on one environment-specific CP exclusion identity literal that must be externalized without changing collection safety.

CP Device Interaction Safety Audit remains P0 and must be completed before recurring scheduling or concurrency increases. Product architecture proceeds toward 0.6.1C after the engineering-readiness checkpoint.

## Next architecture

After CP safety/coverage closure:
`0.6.1C — Discovery Lifecycle + Capability Profile Foundation`.

## Known xfails

-   VSX network canonicalization.
-   PAN default-route classification.

## Detailed history

Use `SECURITYEXPERT_AI_CONTINUATION_PACK.md` only when current
source/state docs cannot answer a concrete architectural/history
question.
