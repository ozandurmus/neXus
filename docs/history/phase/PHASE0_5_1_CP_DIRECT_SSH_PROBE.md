# F-Buddy Phase 0.5.1 — CP Direct SSH Capability Probe

## Goal

Phase 0.5.1 distinguishes **CPRID unavailable** from **appliance unreachable** without changing the current inventory truth model.

A Check Point device may be `communicating` in management and still fail `cprid_util` collection. Quantum Spark / Gaia Embedded appliances are a concrete candidate for this behavior. The 0.5.1 fallback therefore probes direct SSH from the F-Buddy runtime host only after CPRID has failed or produced a partial collection.

## Safety contract

0.5.1 is **observe-only**.

- CPRID remains the primary Check Point collector.
- Management-down/uninitialized devices are not probed.
- Direct SSH is tried only for `collection_failed` or `partial` CP entities whose management state is `communicating`/`unknown`.
- The probe uses read-only operational commands only.
- `show configuration`, `set`, `add`, `delete`, `save config`, policy install, reboot and similar write/configuration commands are not executed.
- Direct-SSH output is **not promoted into `cp.json`** in this release. A device cannot become `LIVE` solely because the capability probe succeeds.

This is intentional: the first real Spark output should validate the exact CLI format before it is allowed to affect inventory correctness.

## Read-only command families

The probe tries compatible variants in order:

- version: `show version all`, `show version`, then `clish -c ...` variants
- interfaces: `show interfaces table`, `show interfaces`, then `clish -c ...` variants
- routes: `show route all`, `show route`, then `clish -c ...` variants

Check Point's current Quantum Spark CLI documentation explicitly documents SSH support, `show interfaces [all|table]`, and Gaia/Embedded route monitoring through `show route` / `show route all` patterns.

## Local artifact

`output/cp_direct_ssh_probe.json`

This local artifact may contain operational CLI output and management IP addresses. It is deliberately treated as runtime data and must not be committed to Git.

The shareable support bundle strips raw stdout/stderr and HMAC-tokenizes the device and management IP.

## Expected support evidence

For a Spark-like device that CPRID cannot collect, the desired first-run evidence is:

```text
cprid_outcome          = collection_failed
management_state       = communicating
ssh_reachable          = true
authenticated          = true
interfaces.success     = true
routes.success         = true
inventory_cli_capable  = true
platform_hint          = quantum_spark / quantum_spark_candidate / gaia_cli_candidate
```

If authentication fails but SSH is reachable, that is reported separately and can later be mapped to a Spark-specific credential.

## Environment controls

All are optional:

```text
FBUDDY_CP_DIRECT_SSH_PROBE_ENABLED=1
FBUDDY_CP_DIRECT_SSH_USERNAME=<optional override>
An optional direct-SSH authentication-secret environment override is supported.
FBUDDY_CP_DIRECT_SSH_PORT=22
FBUDDY_CP_DIRECT_SSH_CONNECT_TIMEOUT_SECONDS=8
FBUDDY_CP_DIRECT_SSH_COMMAND_TIMEOUT_SECONDS=20
FBUDDY_CP_DIRECT_SSH_PARALLELISM=4
FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY=0
```

If no direct-SSH credential override is supplied, the current runtime username/password is tried. Credentials are not persisted.

`STRICT_HOST_KEY=0` preserves the compatibility behavior used by the current prototype. Production hardening must move to managed trusted host keys / known_hosts before this becomes a server-side service.

## CP collector status extension

The remote CP status TSV now appends two local-only fields:

1. management IP
2. CMA name

The first 13 fields remain backward compatible. These target fields allow F-Buddy to perform the direct probe after CPRID failure. Support bundles never expose them in clear text.

## Verification

`verification.json` now reports aggregate direct-SSH capability evidence. If one or more CPRID-failed devices are operationally reachable through the read-only SSH CLI, the verifier emits:

`CP_DIRECT_SSH_FALLBACK_CAPABLE`

This remains a warning/observation, not a successful inventory collection.

## Regression

Phase 0.5.1 regression result:

```text
47 passed
2 xfailed
0 failed
```

The two pre-existing semantic xfails are unchanged.
