# SecurityExpert Phase 0.6.0A4.3.3.2 — PAN HA Runtime Role + Development Workflow Modes

## Goal

Close the remaining PAN header gap with evidence-backed HA runtime role and reduce the local development feedback loop without weakening the full integration checkpoint.

This build deliberately does **not** start Check Point configuration collection. It also does not change CAS/history semantics, VSX collection methods, PAN effective-running primary evidence, or the full-run checkpoint contract.

## Existing behavior preserved

- Normal `py.exe -B .\main.py` remains the full integration checkpoint.
- CP / VSX / PAN runtime collectors keep their normal full-run behavior.
- PAN direct `effective-running` remains primary current configuration evidence.
- Panorama remains discovery/intent/provenance.
- Configuration and Alignment remain separate product semantics.
- A4.3.2 content-addressed storage and migrated history remain unchanged.

## Change 1 — PAN HA runtime role

A4.3.3.1 could fall back to static `HA Enabled · Group <id>` when Panorama managed-device discovery did not expose `ha-state`. That is useful configuration evidence but is not the operational role an operator needs.

A4.3.3.2 uses the following evidence order:

1. `show devices all` managed-device `ha-state`, when Panorama already exposes it.
2. Otherwise a read-only Panorama-targeted operational query:

```text
show high-availability state
```

via XML API `type=op`, `target=<serial>`.

The parser consumes only explicit runtime fields:

```text
result/group/local-info/state
result/group/local-info/mode
result/group/local-info/state-sync
result/group/peer-info/state
```

The header displays the returned runtime role in uppercase, for example:

```text
ACTIVE
PASSIVE
ACTIVE-PRIMARY
ACTIVE-SECONDARY
SUSPENDED
```

No Active/Passive role is inferred from Group ID, election priority, peer IP or any other static configuration.

If the auxiliary runtime query fails, primary configuration evidence remains valid and the UI falls back only to proven static state (`HA Enabled` / `HA Disabled`) rather than inventing a role.

The auxiliary HA query uses a maximum 10 second request timeout even when the primary PAN configuration timeout is larger.

## Change 2 — five development/checkpoint modes

### 1. Render only

```powershell
py.exe -B .\main.py --render-only
```

- no username/password prompt
- no network access
- no CP / VSX / Panorama / PAN config collector
- reuses `output/unified.json`
- reuses local `output/pan_config_telemetry.json` when available
- rebuilds projection + HTML
- explicitly marked `NOT A CHECKPOINT`

Use for CSS, JS, layout and current-configuration projection work.

### 2. PAN configuration only

```powershell
py.exe -B .\main.py --only pan-config
```

Existing safe development default remains first 5 connected PAN firewalls.

For the complete connected PAN config fleet without CP/VSX inventory collection:

```powershell
py.exe -B .\main.py --only pan-config --pan-config-stage all
```

After collection, the command automatically regenerates HTML using the latest existing unified inventory.

### 3. VSX only

```powershell
py.exe -B .\main.py --only vsx
```

Runs only:

```text
VSX raw collection
→ VSX parser
→ merge with latest CP + PAN runtime artifacts
→ HTML render with latest PAN config telemetry
```

Does not invoke CP or PAN collectors.

### 4. Physical Check Point only, excluding VSX

```powershell
py.exe -B .\main.py --only cp
```

Uses the existing CP collection mechanism but changes the **development scope only** to standalone / non-VSX physical gateways and non-VSX ClusterXL members.

Explicitly excluded from this partial mode:

```text
VSX hosts
VSX physical members
Virtual Systems
VSX network objects
```

The normal full checkpoint does not set this filter and therefore preserves the original proven CP discovery method.

After collection, the command merges fresh non-VSX CP with the latest VSX + PAN runtime artifacts and regenerates HTML.

### 5. Full integration checkpoint

```powershell
py.exe -B .\main.py
```

This remains the authoritative phase-close path:

```text
CP baseline collection
VSX collection/parser
PAN runtime
PAN configuration / expected compiler / alignment
failure-aware snapshot
merge
verification
HTML
support bundles
```

Partial modes are development accelerators, not production observation cycles.

## Mixed-cycle safety

Partial modes intentionally reuse untouched planes from the latest local artifacts. Their generated HTML therefore carries workflow metadata and Overview marks it as:

```text
Development view
Mixed-cycle artifacts · not a checkpoint
```

This prevents a partial development render from being confused with a single-cycle full integration run.

A full normal `main.py` run restores a same-run checkpoint with the existing RunContext isolation contract.

## Risk assessment

### PAN HA runtime

Risk: vendor operational response differences.

Mitigation:

- uses vendor-native read-only operation
- conservative parser
- no role inference
- auxiliary failure cannot fail primary config collection
- bounded timeout

### CP partial scope

Risk: accidentally changing the mature full CP collector behavior.

Mitigation:

- scope is activated only by `SECURITYEXPERT_CP_EXCLUDE_VSX=1`
- only `--only cp` sets the environment switch
- full `main.py` executes the original discovery query unchanged

### Partial artifact reuse

Risk: mixed observation times could be mistaken for a checkpoint.

Mitigation:

- console labels partial modes as `NOT A CHECKPOINT`
- HTML embeds workflow metadata and visibly marks mixed-cycle development views
- missing baseline artifacts fail closed and instruct the operator to create a full checkpoint first

## Rollback

No persistent schema migration is introduced.

Rollback is the previous A4.3.3.1 build. Existing `data/`, CAS objects and configuration history are compatible and do not need rollback.

## Definition of Done

- [x] Runtime HA role comes only from explicit runtime evidence.
- [x] Static Group ID is not used as runtime role.
- [x] HA auxiliary query is read-only and bounded.
- [x] `--render-only` is credential-free and collector-free.
- [x] `--only pan-config` refreshes HTML using existing inventory.
- [x] `--only vsx` does not invoke CP/PAN collectors and refreshes merge/HTML.
- [x] `--only cp` excludes VSX only in the development scope.
- [x] Full checkpoint CP behavior remains unchanged.
- [x] Partial HTML is visibly marked mixed-cycle / not checkpoint.
- [x] Full regression passes with only the pre-existing known xfails.

## Next planned capability

After real-environment validation of this build, the next major configuration-plane step is Check Point configuration evidence. That work remains separate because Standalone, ClusterXL and VSX require explicit vendor-method validation rather than reusing PAN semantics.
