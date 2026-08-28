# SecurityExpert Phase 0.6.1A.1 — Check Point Configuration Identity Gate + VSX Target Resolution

## Goal
Harden the observe-only 0.6.1A Check Point configuration evidence probe before any configuration evidence is promoted to CAS/history or UI.

## Scope
- Keep the real estate SSH login contract: administrators enter Expert shell.
- Enter Gaia Clish explicitly through fixed `clish -c ...` commands.
- Establish an automatic CP identity acceptance gate suitable for Management object names that may differ from Gaia hostnames.
- Resolve a VSX physical member + non-zero VSID from the mature VSX inventory artifact even when CP telemetry cannot join it by object name.
- Compare VSX host and VS-context configuration fingerprints using both Clish context switching and Expert-shell `vsenv` validation paths.
- Never persist raw `show configuration` output.

## Identity model
The direct SSH endpoint is selected from the already observed Management/VSX topology, not from user-entered free-form target data.

Evidence levels:
- `VERIFIED_MANAGEMENT_ENDPOINT_AND_HOSTNAME` / `HIGH`: exact/short/normalized Gaia hostname relation plus authenticated exact target endpoint and successful Gaia identity commands.
- `VERIFIED_MANAGEMENT_ENDPOINT_HOSTNAME_DIFF_OBSERVED` / `MEDIUM`: authenticated exact discovered endpoint with successful Gaia hostname/version evidence, while the Management object name differs from the Gaia hostname.
- `UNVERIFIED` / `LOW`: endpoint, authentication, hostname, or version evidence is incomplete.

A Management-object/Gaia-hostname difference is retained as telemetry; it is not silently rewritten into equality.

## Additional read-only evidence
`clish -c 'show asset all'` is sampled and immediately reduced to safe metadata/fingerprint only. It is not yet part of the acceptance gate because the current legacy Management discovery artifact has no comparable hardware serial field.

## VSX target resolution
Resolution order:
1. Join mature `vsx.json` physical member to CP telemetry by device name.
2. If name join fails, join by exact management/device IP.
3. If CP telemetry has no join, accept the mature VSX artifact's authenticated physical member IP for this observe-only probe.
4. Select one non-zero VSID from that same member.

This resolves probe selection only; it does not assert that `show configuration` is VS-context-specific. Context distinctness is still proven by comparing canonical configuration fingerprints.

## Safety
- Read-only probe only.
- Raw configuration is never persisted.
- Raw asset output is never persisted.
- No CAS/history writes.
- No Configuration UI promotion.
- Numeric VSID only for VSX context commands.
- Host-key compatibility mode remains POC-only; production promotion still requires trusted known_hosts or pinned fingerprints.

## Definition of done
- Standalone candidate resolved.
- Complete non-VSX ClusterXL pair resolved.
- VSX physical member + non-zero VSID resolved from existing artifacts.
- Identity gate accepted on each successful physical target.
- VSX context command succeeds and its distinctness from host config is reported.
- Probe gate is true only when every required role succeeds and no selection gap remains.
