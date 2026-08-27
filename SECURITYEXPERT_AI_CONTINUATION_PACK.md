# SecurityExpert --- AI Continuation Pack v2

**Baseline:** 2026-08-24\
**Current line:** `0.6.1B.1.2`

This is Tier-3 historical/architectural context. Do not read it every
session. Prefer `CLAUDE.md` + `CURRENT_STATE.md`.

## Vision

Multi-vendor security evidence platform:
`SEE → VERIFY → TRACE → RECOVER → OPERATE`.

Modules evolve across Inventory, Configuration, Alignment, Policy &
Objects, History, Compliance, Backup/Recovery and later controlled
Operations.

Target vendors include PAN, Check Point/VSX/Spark, Fortinet, Cisco
families and other network/security platforms.

## Philosophy

Evidence \> assumption. Unknown \> guessed. Preserve proven collectors.
Incremental \> rewrite. Real validation \> theoretical correctness.

Management plane = discovery/topology/intent. Direct device = actual
evidence.

Normal future onboarding should be management-led auto-discovery, not
manual device-by-device definition.

Target lifecycle:
`DISCOVER → IDENTIFY → TOPOLOGY → PLATFORM → CAPABILITY → COLLECTION PLAN → OBSERVE`.

## Proven PAN

Panorama supplies discovery/intent/provenance. Direct firewall supplies
actual/effective evidence. Primary current config is
`effective-running`. Direct evidence requires identity verification
(serial mapping contract). Configuration and Alignment remain separate.

Production debt: corporate CA/TLS verification.

## Proven CP

Enterprise admins log into Expert. Use explicit Gaia Clish
(`clish -c ...`). Do not change shell.

CP actual configuration baseline: `show configuration`, secret-aware and
RAM-first.

Cluster member differences are MEMBER_SPECIFIC until intent proves
drift.

VSX evidence identity = physical endpoint + VSID. `vsenv <VSID>` is an
important validated Expert context path.

Some WiFi/Spark-like devices land directly in Clish. B.1.2
real-environment validation proved SecurityExpert interactive
direct-Clish collection works. Do not equate direct Clish with Spark
classification.

## Storage

CAS migration preserved 1295 snapshots/1140 SAME events, reduced legacy
corpus to 153 unique objects (\~417.56 MiB) and reclaimed \~2.98 GiB
with no observed safety/hash/corruption errors. History is immutable;
SAME references existing payload, CHANGED writes new unique payload.

## Current CP checkpoint

B.1.2: - 101/122 current, - 18 interactive_direct_clish, - 55
interactive_expert_explicit_clish, - identity failures removed from
previous 18, - HA coverage 42, - model/serial 54, - 21 unavailable (17
reachability, 2 auth, 2 operational), - workers 6, - \~182s collection.

A known manually direct-Clish WiFi endpoint is Current in UI with
structured config values. Interactive mechanism is PASS; total coverage
remains PARTIAL.

## Safety concern

Some firewalls were observed down temporally around runs; causality
unproven. Audit all CP
connections/sessions/commands/retries/timeouts/concurrency before
scheduler expansion or worker increases. Stability \> speed.

## Roadmap

Active 0.6.x = SEE/current-state/evidence foundation.

After CP safety/coverage closure:
`0.6.1C — Discovery Lifecycle + Capability Profile Foundation`.

Planning map: - 0.7 VERIFY/compliance/expected state - 0.8
TRACE/change/root cause - 0.9 RECOVER/backup - 1.0 production platform

Future numbering is provisional.

## Backlog

CP trusted host keys; PAN trusted CA; Spark classification; CP
model/serial/HA coverage; CP interaction safety; known VSX
canonicalization and PAN default-route xfails; discovery lifecycle;
capability planner; scheduler; credential/vault; Git/Bitbucket/CI;
responsive/history/bulk UX.

## SDLC direction

Internal Bitbucket Data Center exists, with
Jenkins/SonarQube/Artifactory ecosystem. Move from ZIP-as-source-control
to Git as history/rollback/branching; ZIP only release/VM artifact.
Establish secure `.gitignore` before first commit.

## Privacy

See `PRIVACY_AND_DATA_HANDLING.md`. Secrets never share. Runtime
operational evidence is local-only by default. AI should consume SAFE
SUMMARY/sanitized narrow evidence.

## AI efficiency

Repository is memory. Use progressive context. Sonnet/medium for normal
implementation, stronger reasoning only for major
architecture/security/vendor semantics. Start new conversations when
task changes materially. Do not pay multiple agents to rediscover
settled architecture.
