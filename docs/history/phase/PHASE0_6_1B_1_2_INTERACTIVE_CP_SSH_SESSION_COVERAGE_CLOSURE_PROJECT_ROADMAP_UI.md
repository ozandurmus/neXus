# Phase 0.6.1B.1.2 — Interactive CP SSH Session + Coverage Closure + Project Roadmap UI

## Why this build exists

The 0.6.1B.1.1 real-estate run proved that exec-channel adaptive detection was not sufficient: 55 physical hosts were identified as Expert→explicit-Clish while 37 remained `unknown`, and manually verified WiFi/Spark-class appliances could be reached with a normal interactive SSH session and direct Clish. Model/Serial recovery improved from 0 to 54, but direct-Clish coverage and HA runtime remained unresolved.

This build changes only the Check Point host SSH execution adapter and adds living project/product metadata. Mature CP inventory, VSX inventory, PAN collection/alignment, CAS semantics, and secret boundaries are not redesigned.

## Check Point interactive SSH contract

The collector opens one authenticated Paramiko PTY-backed shell per physical target and proves the command surface with read-only evidence:

1. Send `show hostname` in the interactive shell.
2. If successful, classify the session as `interactive_direct_clish`.
3. Otherwise send explicit `clish -c 'show hostname'`.
4. If successful, classify as `interactive_expert_explicit_clish`.
5. If interactive framing/capability cannot be proven, retain the already-proven exec-channel adapter as a bounded compatibility fallback; do not reinterpret the target as down solely because PTY framing failed.

The prompt is used only to frame command completion. `>` / `#` / `$` is never used to infer the shell or platform.

### Long configuration lines

The interactive PTY requests a wide terminal (`4096` columns) so long Gaia/Spark `set ...` commands are substantially less likely to be line-wrapped by terminal presentation. Canonical evidence remains only complete lines beginning with `set `.

## Identity and platform separation

The A.1 identity gate remains anchored on the exact Management-selected endpoint plus authenticated SSH and observed Gaia hostname evidence. Version evidence continues to raise confidence when available.

For appliances whose version command surface is limited, a successful read-only `show configuration` may complete the collector identity gate at MEDIUM confidence. This does not assert a platform family. Platform may remain `unknown` while configuration is valid/current.

This is the intended distinction:

```text
identity = which managed endpoint did we authenticate to?
platform = what product/OS family can evidence prove?
capability = which read-only operations actually work?
```

## Failure semantics

An explicitly unsupported configuration command on an authenticated/proven Gaia command surface is classified as a `capability_gap`, not as an operational outage. Reachability, authentication, authorization, identity and timeout failures remain separate.

## HA runtime evidence

ClusterXL/VSX physical members use the estate-proven read-only `cphaprob stat`. It is issued through the interactive Expert session when that surface is proven, otherwise through the existing exec fallback. ACTIVE/STANDBY/etc. are never inferred from names or static configuration. Failure to obtain runtime HA role does not invalidate otherwise-good configuration evidence.

## VSX scope

VSX context configuration continues to use the previously validated physical endpoint + numeric VSID evidence identity and Expert `vsenv <VSID>` → Clish path, with the existing Clish context fallback. The new interactive host adapter does not rewrite or weaken this proven context behavior.

## Secret/storage contract

Unchanged from 0.6.1B:

- raw `show configuration`: process memory only;
- secret-bearing `set` lines: withheld before persistence;
- raw config: not stored in CAS/history/UI/support/telemetry;
- sanitized evidence: stored through vendor-neutral content-addressed history;
- canonical full-config SHA-256: internal change fingerprint only so secret-only changes can still create a CHANGED history event;
- local telemetry remains `LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE`.

## Living Project Plan UI

A new top-level `Project Plan` module is embedded into every rendered local HTML. Source metadata:

```text
project/roadmap.json
project/feature_registry.json
project/backlog.json
project/build_history.json
project/README.md
```

`utils/project_plan.py` calculates feature, current-track and overall percentages from weighted acceptance criteria. The UI provides:

- current build and current major track;
- overall declared-roadmap progress and current-track progress;
- Now / Next / Upcoming;
- 0.5.x through provisional 1.0 major-track mapping;
- backlog and technical debt grouped by category/status/priority;
- completed feature explanations: what the capability is and why it matters;
- build history with completed/follow-up/in-progress states;
- roadmap notes including explicit rebases/deferred original targets.

The percentage is not a delivery-time estimate. A criterion stays pending until implementation or required real-environment validation is complete.

The metadata loader performs integrity checks for duplicate feature IDs, missing feature references, invalid status/state values, invalid current track, and missing current-build history. Warnings are surfaced in Project Plan rather than silently producing misleading progress.

## Current declared planning map

- `0.6.x`: Current State & Evidence Foundation — current track.
- `0.6.1C`: Discovery Lifecycle + Capability Profile Foundation — next planned build after CP coverage closure.
- Check Point Management intent ↔ actual alignment: planned/rebase after discovery/capability foundation.
- Unified Configuration History + Diff UX: planned 0.6.x follow-up.
- original `0.6.0B` native backup: preserved in history but explicitly deferred/rebase-required.
- `0.7.x`: VERIFY / compliance & expected-state planning track.
- `0.8.x`: TRACE / cross-vendor change & root-cause planning track.
- `0.9.x`: RECOVER / native backup validation & restore readiness planning track.
- `1.0`: production platform foundation planning track.

Future 0.7.x–1.0 numbers are planning labels, not frozen release commitments.

## Risk controls

- Interactive shell changes terminal behavior: wide PTY and command framing are bounded; prompt text is not trusted as capability evidence.
- No arbitrary command input: Gaia interactive dispatcher accepts only `show ...`; VSX numeric VSID remains validated before command construction.
- Interactive detection may fail on a server that still supports exec: bounded exec compatibility fallback prevents regression.
- Unknown platform is not silently relabeled Spark/Gaia Embedded.
- Existing workers remain bounded (default 6, hard max 12); this build prioritizes correctness over aggressive concurrency changes.
- SSH host-key compatibility mode remains a production blocker and stays visible in backlog/UI.

## Rollback

Rollback is file-level: restore the 0.6.1B.1.1 build. Existing CAS history and sanitized Check Point snapshots remain compatible; no storage migration is introduced. Project Plan metadata is additive and can be removed without altering device evidence.

## Definition of Done for packaged build

- PTY-backed direct-Clish and Expert→Clish handshake tests pass.
- Read-only dispatcher rejects non-`show` Gaia commands.
- Exec fallback contract remains present.
- Unknown-but-current identity gate test passes.
- Unsupported configuration capability is not reported as operational outage.
- Project Plan metadata has no integrity warnings.
- Progress is calculated from criteria, not a hard-coded percentage.
- Project Plan responsive UI/export contract passes.
- Full automated regression has zero unexpected failures.
- Python compile, JavaScript syntax and CP shell syntax checks pass.

## Real-environment acceptance still required

Run:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

Expected proof points include:

- `interactive_direct_clish` appears for at least the manually verified direct-Clish WiFi/Spark estate if network/auth permits;
- previously successful Enterprise Gaia remains current via interactive Expert→Clish or bounded fallback;
- `Unknown but current` may be non-zero without being an error;
- Model/Serial recovery does not regress;
- HA role coverage should increase where `cphaprob stat` exposes local state;
- failure families distinguish true reachability/auth/identity issues from capability gaps;
- Project Plan page renders and reflects build `0.6.1B.1.2`.

Do not call CP coverage closure complete until this real-estate validation is reviewed.
