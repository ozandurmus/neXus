# Phase 0.6.0A4.1.1 - Unified Full-Run Orchestration

## Goal
A normal `py.exe .\main.py` run now executes both the frozen inventory pipeline and the PAN configuration evidence / expected-configuration compiler. The two share the same orchestration run ID while retaining separate support bundles and security boundaries.

## Default full-run flow

```text
main.py
  -> Check Point inventory
  -> VSX inventory
  -> Panorama runtime inventory
  -> PAN configuration evidence (all connected firewalls)
       -> Panorama intent
       -> direct firewall identity gate
       -> local active
       -> merged
       -> effective-running
       -> expected configuration compiler
       -> config support bundle
  -> failure-aware inventory snapshot
  -> merge
  -> verification
  -> HTML
  -> inventory support bundle
```

## Output classes

### Shareable diagnostics
- `output/support_bundle_<run_id>.zip` - inventory diagnostics
- `output/config_support_<run_id>.zip` - configuration diagnostics

Both use the same `<run_id>` during a normal full run. Raw configuration is not included in either bundle.

### Sensitive local evidence
- `data/configs/...`
- `data/derived/panorama_expected/...`
- local failure/compiler reports under `output/`

These remain local-only and are not bundled into the shareable inventory support archive.

## CLI behavior

- `py.exe .\main.py` - inventory + full connected PAN configuration collection
- `py.exe .\main.py --skip-config` - legacy/frozen inventory full run only
- `py.exe .\main.py --only pan-config` - PAN configuration POC mode; defaults to first 5 connected devices
- `--pan-config-stage 5|10|all` or `--pan-config-limit N` explicitly overrides PAN configuration scope
- `--pan-config-workers N` retains the existing capped parallelism behavior

## Failure isolation
Configuration is a separate run stage. If the PAN configuration stage raises before producing a bundle, the stage is marked `degraded` and the inventory snapshot/merge/verification/HTML pipeline continues. This prevents a configuration-plane problem from destroying an otherwise valid inventory run.

## Invariants
- No change to CP, VSX, Panorama runtime collection methods.
- No automatic SSH fallback added.
- No raw configuration is added to shareable support bundles.
- Full-run configuration uses the same orchestration run ID as inventory for operator correlation.
