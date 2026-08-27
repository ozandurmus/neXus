# Phase 0.6.0A3 — PAN Direct Fleet Validation

## Goal

A2.2 proved on the real VM path that Panorama can discover the managed firewall
identity/address while SecurityExpert can connect directly to the firewall,
verify its serial, and retrieve active, effective-running and merged
configuration evidence. A3 scales that exact read-only path in controlled
stages rather than jumping from two devices to the whole fleet.

## Staged rollout

Default A3 scope is intentionally five connected devices with three workers:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage 5 --pan-config-workers 3
```

After review:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage 10 --pan-config-workers 3
```

Only after the 5- and 10-device gates pass:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3
```

`--pan-config-limit N` remains as an explicit override for controlled testing.
Direct parallelism is capped at six even if a larger number is requested.

## A3 evidence flow

```text
Panorama
  -> show devices all
  -> serial, management IP, connection state
  -> Shared Policy / Template sync state when exposed by PAN-OS

SecurityExpert collector
  -> direct HTTPS keygen to firewall management IP
  -> direct show system info
  -> require direct serial == Panorama serial
  -> direct active config
  -> direct effective-running config
  -> direct merged config
  -> direct pushed-template config (alignment evidence, optional)
  -> immutable local snapshots + SHA-256
```

No write, commit, save, import, restore or remote backup-generation action is
issued by A3.

## Fleet gate

A selected device is a full A3 evidence success when:

- direct authentication succeeds;
- direct serial identity verification succeeds;
- active configuration retrieval succeeds;
- effective-running retrieval succeeds;
- merged configuration retrieval succeeds.

`pushed-template` is collected as optional alignment evidence and does not make
the primary evidence gate fail if unsupported on a particular platform/release.

The stage passes only when every selected device meets the primary direct
identity/evidence gate and no identity mismatch exists.

## Panorama/device alignment signals

A3 begins the foundation for configuration reconciliation without claiming more
than the evidence proves.

It records, when available:

- Panorama Shared Policy sync state;
- Panorama Template sync state;
- Panorama-mediated active config vs direct active config equality;
- direct `pushed-template` availability;
- active vs merged/effective structural deltas.

These are **alignment signals**, not an automatic declaration that a local
value is an override. `merged != active` is expected in centrally managed PAN-OS
and does not by itself mean drift.

## Why override detection is a separate logical capability

Palo Alto distinguishes several concepts that must not be collapsed into one
red/yellow badge:

1. **Panorama sync** — Panorama reports whether Shared Policy and Template are
   synchronized to the managed firewall.
2. **Local override** — a local firewall value can intentionally override a
   template value; this can be legitimate.
3. **Effective drift** — the effective/running state differs from what the
   management source and approved local override model expect.

A future `Configuration > Alignment` view will reconcile pushed template,
pushed policy, local active and effective-running data at normalized XPath/fact
level and classify evidence as aligned, intentional override, out-of-sync,
drift, or unknown. A3 does not yet label raw differences as override/drift.

## Privacy

Raw configuration, values, names, IPs, rule names and API keys remain local.
The shareable support bundle contains only HMAC-pseudonymized identities,
hashes, structural counts, safe sync states, comparison deltas and failure
stages.

Share only:

```text
output\config_support_<run_id>.zip
```

Do not share `data/configs/` or `output/pan_config_telemetry.json`.
