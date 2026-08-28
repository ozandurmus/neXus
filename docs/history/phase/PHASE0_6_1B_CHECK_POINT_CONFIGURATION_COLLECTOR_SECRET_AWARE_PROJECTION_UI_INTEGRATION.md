# SecurityExpert — Phase 0.6.1B

## Check Point Configuration Collector + Secret-Aware Projection + UI Integration

### Objective

Promote the read-only Check Point evidence path proven in 0.6.1A/A.1 into the first production-facing **current configuration** pipeline for:

- Standalone Gaia gateways
- non-VSX ClusterXL physical members
- VSX physical hosts
- observed non-zero VSX Virtual System contexts

This phase does **not** implement Check Point Management expected-vs-actual Alignment, policy analysis, native backup/restore, or configuration write operations.

### Proven transport contract preserved

The estate's administrative SSH login shell is **Expert shell**. SecurityExpert therefore never assumes an interactive Gaia Clish login. Physical Gaia reads use explicit read-only Clish invocation from Expert:

```text
SSH -> Expert shell -> clish -c 'show ...'
```

Primary actual configuration evidence:

```text
clish -c 'show configuration'
```

VSX context evidence uses the context primitive validated in 0.6.1A.1:

```text
Expert -> vsenv <numeric VSID> -> clish -c 'show configuration'
```

If that path does not return usable configuration evidence, the previously validated interactive Clish `set virtual-system <VSID>` path may be used as a read-only fallback. Only VSIDs already observed by the mature VSX inventory are selected; SecurityExpert does not invent contexts.

### Evidence planes

```text
Check Point Management / mature inventory
    -> selection / identity / hierarchy / topology

Direct gateway SSH + Gaia Clish
    -> actual current Gaia configuration evidence

Check Point Management expected configuration
    -> NOT IMPLEMENTED in 0.6.1B
```

This is intentionally analogous to, but not a copy of, the PAN intent/actual split.

### Identity gate

Direct configuration evidence is accepted only after the 0.6.1A.1 identity gate succeeds. The gate combines the Management-selected endpoint with authenticated SSH and successful Gaia hostname/version evidence. A Management object-name / Gaia-hostname difference is retained as observed telemetry rather than automatically treated as a configuration failure.

### Secret-aware evidence contract

Raw `show configuration` can contain secret-bearing values. 0.6.1B therefore applies the following contract:

```text
raw show configuration
        |
        | process memory only
        v
canonical set-lines + full canonical fingerprint
        |
        +--> secret-bearing line detection
        |       -> line is withheld entirely
        |
        v
redacted/sanitized Gaia configuration
        |
        +--> vendor-neutral content-addressed history
        +--> safe structured current-value projection
        +--> Configuration UI
```

Raw configuration is never:

- written to disk as a collector artifact
- stored in CAS/history
- included in `cp_config_telemetry.json`
- printed to console
- exposed to browser UI
- placed in a shareable support bundle

The sanitized CAS artifact contains a full canonical configuration SHA-256 fingerprint so a change limited to a withheld secret-bearing line can still generate a new history version without persisting the secret value itself. The fingerprint is internal/local evidence and is not exposed in the browser payload.

### Projection

Only non-secret `set ...` statements are projected. Initial semantic sections include:

- System
- DNS
- NTP
- Management
- Logging
- High Availability
- Interfaces
- Routing
- SNMP
- Authentication
- Other Gaia Configuration

This parser is deliberately conservative. An unrecognized non-secret Gaia setting remains visible under `Other Gaia Configuration` rather than being assigned invented semantics.

### ClusterXL semantics

Each ClusterXL physical member is collected independently. Differences between two successful members of the same known cluster are classified as `MEMBER` / member-specific current configuration in the UI. A member difference is **not** called drift in this phase.

No Management expected-state comparison is performed yet.

### VSX hierarchy

VSX configuration is represented as:

```text
VSX physical host
    +-- Host / VS0-oriented Gaia evidence
    +-- observed VSID n -> context-specific Gaia evidence
    +-- observed VSID m -> context-specific Gaia evidence
```

Virtual Systems remain children of their physical host; they are not presented as unrelated physical firewalls.

### Storage / history

0.6.1B reuses the existing vendor-neutral `ConfigEvidenceStore.write_text_snapshot()` CAS contract established in 0.6.0A4.3.2.

Sources/artifacts:

```text
source: checkpoint-gaia
artifact: gaia_show_configuration_redacted
method: direct_ssh_expert_clish_show_configuration

source: checkpoint-gaia
artifact: gaia_vsx_context_show_configuration_redacted
method: direct_ssh_expert_vsenv_clish_show_configuration
```

`FIRST`, `SAME`, and `CHANGED` history semantics are therefore available without storing duplicate sanitized payload bytes.

### UI contract

The Configuration module now combines PAN and Check Point devices. Check Point device views show current actual Gaia configuration and safe origin/member hints. The Alignment tab explicitly states that Check Point Management intent vs gateway actual alignment is not implemented in 0.6.1B.

The browser payload excludes raw Check Point configuration, raw configuration hashes, SSH fingerprints, and secret-bearing values.

### Host-key security

For compatibility with the current POC, SSH can still operate in observe-and-record host-key mode. The collector exposes this debt explicitly:

```text
host_key_policy = observe_and_record_not_production
production_trust_ready = false
```

Production deployment requires trusted `known_hosts` / pinned host keys. Set the existing strict-host-key control before treating the transport as production-trusted.

### Failure-domain behavior

Check Point configuration is an independent full-run stage. Failure/degradation of CP configuration does not destroy the mature Network Inventory run. `--skip-config` skips both CP and PAN configuration during a normal full run.

### Development command

Run Check Point configuration only, reusing existing inventory artifacts:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

For a small representative sample:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage sample
```

The command also regenerates the HTML when `output/unified.json` is available. It does not recollect CP inventory, VSX inventory, or PAN configuration.

### Security-sensitive local artifacts

`output/cp_config_telemetry.json` contains real device identities, management addresses and non-secret current configuration values. It is a **LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE** artifact and should not be pasted into support channels.

Use the console safe summary plus screenshots containing only values acceptable for review.

### Explicitly unchanged

- mature CP inventory collection method
- ClusterXL inventory rendering
- mature VSX inventory collector/parser
- PAN runtime/configuration/alignment
- CAS architecture
- configuration retention policy
- Network Inventory UI contract
- write/change operations

### Rollback

There is no schema migration or destructive transformation. Rollback is the previous 0.6.1A.1 build plus ignoring/removing newly created Check Point configuration history/telemetry if desired. Existing CAS objects are immutable and are not deleted by rollback.

### Definition of Done

Code-level DoD:

- Expert-shell -> explicit Clish transport preserved
- identity gate required before actual configuration acceptance
- Standalone + ClusterXL + VSX host/context targets supported
- raw configuration never persisted
- secret-bearing lines withheld before CAS/UI
- sanitized text uses content-addressed history
- secret-only changes remain detectable through the internal canonical fingerprint
- ClusterXL differences are member-specific, not drift
- VSX hierarchy is preserved
- Check Point current values appear in vendor-neutral Configuration UI
- Check Point Alignment remains explicitly not implemented
- configuration failure remains an independent failure domain
- regression suite passes

Real-environment DoD still requires an `all` CP configuration run and visual validation of one Standalone/physical gateway, a ClusterXL pair, and a VSX host/Virtual System.
