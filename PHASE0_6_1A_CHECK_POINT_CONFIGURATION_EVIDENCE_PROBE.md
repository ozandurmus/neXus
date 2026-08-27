# SecurityExpert — Phase 0.6.1A

## Check Point Configuration Evidence Probe

### Purpose

Phase 0.6.1A validates the proposed Check Point **actual configuration evidence** method before any Check Point configuration is promoted into the product UI/history model.

The target architecture must eventually support:

```text
Standalone / physical gateway
ClusterXL member
VSX physical host/member
VSX Virtual System context
```

This phase is intentionally **probe-only** and keeps the existing mature CP/VSX inventory collectors unchanged.

---

## Environment contract discovered during development

In the target estate, administrator SSH sessions start in **Expert shell**.

SecurityExpert therefore does **not** assume that SSH login opens Gaia Clish. Gaia configuration reads are explicitly invoked from Expert shell:

```text
Expert SSH login
    ↓
clish -c 'show hostname'
clish -c 'show version all'
clish -c 'show configuration'
```

No `exit`/prompt trick is used to try to return from Expert to Clish.

For VSX context validation, two read-only context mechanisms are observed:

```text
A) Expert login → interactive clish
   set virtual-system <VSID>
   show configuration

B) Expert login
   vsenv <VSID>
   clish -c 'show configuration'
```

The probe records which method succeeds and whether the canonical `set ...` configuration fingerprint is distinct from the host/VS0 configuration. It does **not** assume that `show configuration` is Virtual-System-specific until the real environment proves it.

---

## Why SSH / Gaia Clish is the primary candidate

`show configuration` is the broad Gaia configuration representation and is available through Gaia Clish. It is therefore the primary candidate for actual gateway configuration evidence across physical Gaia targets.

The role split remains:

```text
Check Point Management
    → discovery / topology / intent

Direct gateway SSH + Gaia Clish
    → actual Gaia configuration candidate

Gaia REST API
    → future structured supplement / cross-validation
```

Gaia REST API is deliberately **not** added to this probe. Adding API endpoint semantics at the same time as proving SSH/Clish across Standalone, ClusterXL and VSX would mix risk domains.

---

## Probe command

Requires a previous full checkpoint because target selection reuses the already-proven local CP/VSX inventory artifacts:

```powershell
py.exe -B .\main.py --cp-config-probe
```

The probe automatically attempts to select:

```text
1 x Standalone / non-cluster physical gateway
2 x members from the same non-VSX ClusterXL cluster
1 x VSX physical member already represented by the VSX runtime collector
1 x non-zero Virtual System context from that member
```

If a platform shape cannot be selected, the safe summary reports a selection gap instead of guessing.

---

## Security contract

### Read-only

Allowed command families are fixed in code. User/device data is never interpolated into shell commands except a validated numeric VSID.

No configuration-changing command is used.

### Raw configuration

`show configuration` can contain secret-bearing data such as community/authentication material.

Therefore 0.6.1A:

```text
RAW show configuration
    → process memory only
    → byte/line counts
    → canonical set-line SHA256
    → feature markers
    → secret-bearing line count
    → raw reference dropped
```

Raw configuration is **not**:

```text
written to output
written to CAS
written to history
printed to console
placed in support bundle
promoted to Configuration UI
```

The local report contains device/IP identity and host-key fingerprints and is marked:

```text
LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE
```

Only the console summary is intended to be pasted back for validation.

### SSH host key

The probe defaults to compatibility observation mode so it can record the real server host-key fingerprint without blocking the POC:

```text
observe_and_record_not_production
```

Production Check Point configuration collection must move to trusted `known_hosts` or explicit pinned host-key fingerprints.

Strict mode can already be tested with:

```powershell
$env:SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY="1"
py.exe -B .\main.py --cp-config-probe
```

---

## Evidence gates

For each physical target the probe checks:

```text
SSH reachable
authentication accepted
Expert shell command execution
clish show hostname
clish show version all
clish show configuration
non-empty set-line corpus
```

Identity relation is recorded conservatively:

```text
exact
shortname_match
different_review_required
unavailable
```

A hostname difference is not silently treated as failure or equivalence.

For VSX, the probe additionally records:

```text
context command success
host canonical set fingerprint
VS canonical set fingerprint
context_distinct_from_host
recommended_context_method
```

---

## Existing behavior preserved

Not changed:

```text
CP inventory collection
CP ClusterXL aggregation
VSX runtime collector/parser
PAN runtime/configuration
Configuration UI
Alignment
CAS/history
support bundles
full checkpoint orchestration
```

No CP configuration is yet visible in the Configuration module.

---

## Risks

1. Direct SSH may not be reachable from the current collector host for every management subnet.
2. Existing admin credentials may authenticate to MDS but not to every gateway.
3. Host-key trust is not production-hardened in compatibility probe mode.
4. `show configuration` may expose secrets; raw output must remain memory-only until a secret-aware evidence-storage policy is finalized.
5. VSX `show configuration` may be host/global rather than Virtual-System-specific. The probe must establish this from real device evidence.
6. Gaia REST API coverage and permissions remain a separate validation domain.

---

## Rollback

No persistent configuration/state migration is performed.

Rollback is simply reverting to the prior A4.3.3.2 build. Probe output under `output/cp_config_probe_*.json` can be deleted locally after review.

---

## Definition of Done

0.6.1A evidence validation is complete when real-device results establish:

```text
[ ] Standalone direct SSH + clish show configuration works
[ ] ClusterXL member 1 works
[ ] ClusterXL member 2 works
[ ] VSX physical host/member works
[ ] At least one VS context method is characterized
[ ] Host vs VS context distinction is understood
[ ] Identity mapping behavior is reviewed
[ ] No raw config was persisted
[ ] Production host-key strategy is selected before promotion
```

Only after these gates are reviewed should 0.6.1A.1 promote Standalone actual configuration into vendor-neutral Configuration/CAS history.
