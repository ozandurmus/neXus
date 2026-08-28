# 0.6.1B.1.1 — Adaptive CP Shell Detection + Metadata Coverage Recovery

## Goal
Harden the 0.6.1B.1 Check Point configuration collector against mixed SSH login-shell estates without changing the proven inventory or PAN collectors.

## Runtime contract
The collector does not infer shell type from prompts. After SSH authentication it performs a read-only capability handshake:

1. `show hostname` succeeds -> `direct_login_clish`.
2. Otherwise `clish -c 'show hostname'` succeeds -> `expert_explicit_clish`.
3. Otherwise shell remains `unknown` and the normal failure taxonomy applies.

Follow-up Gaia reads use the observed shell profile. Only `show ...` commands are accepted by the adaptive Gaia dispatcher.

For version evidence, `show version all` remains primary and `show version` is a read-only fallback for platforms where the first form is unavailable.

## Metadata recovery
- Model/serial extraction now accepts explicit key/value and table-shaped asset identity lines using semantic key matching.
- ClusterXL/VSX runtime HA role reuses the already-proven read-only `cphaprob stat` command rather than the unproven `cphaprob state` spelling.
- VSX sidebar grouping now shows presentation-only aggregate member count, logical VS count, and observed member HA-role counts when available. Evidence identity remains exact endpoint + VSID.

## Security boundaries
- No prompt parsing or interactive shell mutation.
- No write verbs in adaptive Gaia reads.
- Raw `show configuration` remains memory-only and secret-bearing lines remain withheld from CAS/UI/support output.
- Host-key policy debt remains explicit and unchanged.

## Compatibility
- Existing B.1 Expert-first `_run_gaia_read()` compatibility wrapper is preserved for older callers/tests.
- CP inventory, VSX runtime collection, PAN runtime/config, CAS semantics, and alignment semantics are unchanged.

## DoD
- Adaptive direct-Clish and Expert->Clish detection tested.
- Version fallback tested.
- Generic explicit model/serial identity parsing tested.
- Full regression passes with known xfails unchanged.
