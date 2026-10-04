# SecurityExpert Platform — Target Architecture

## Product vision

F-Buddy should evolve from a local inventory script into the read-only evidence and analysis plane for the Security Platforms team.

The target is a **SecurityExpert** platform with multiple capabilities sharing the same identity, collection, snapshot, storage, audit and UI foundations:

1. **Inventory** — live interfaces, routes, runtime availability and topology.
2. **Configuration Backup** — immutable dated vendor configurations with integrity hashes.
3. **Configuration Diff / History** — what changed, when, on which device, and which facts changed.
4. **Configuration Intelligence** — vendor-specific parsing into normalized facts.
5. **Compliance / Posture** — technical control checks mapped to frameworks and internal standards.
6. **Evidence** — reproducible findings that point back to an immutable source snapshot and hash.
7. **Change plane (later)** — controlled, approved, audited configuration changes. This must remain separated from the read-only plane until the read path is mature.

This can replace a meaningful part of the current configuration-backup workflow over time, but backup, inventory, compliance and change orchestration should remain separate jobs/modules even if they initially run from one codebase and one image.

## Core principle: raw evidence is immutable

A collected configuration is evidence. Never normalize by overwriting the original.

```text
collect
  -> raw immutable artifact
  -> sha256 + metadata
  -> parser/normalizer
  -> facts
  -> compliance rules
  -> findings
```

A finding must always be traceable back to:

- source/device identity
- collection timestamp
- collection method
- artifact SHA-256
- parser version
- rule version
- evidence location

## Proposed logical modules

```text
SecurityExpert
|
+-- Inventory
|   +-- Check Point CPRID
|   +-- Check Point Direct SSH / Spark
|   +-- VSX
|   +-- Panorama / PAN-OS
|
+-- Backup
|   +-- vendor collectors
|   +-- immutable config store
|   +-- retention
|
+-- Normalize
|   +-- vendor parsers
|   +-- common fact schema
|
+-- Compliance
|   +-- rule engine
|   +-- framework mappings
|   +-- findings/evidence
|
+-- Diff / History
|
+-- API
|
+-- Web UI
|
+-- Scheduler / Jobs
|
+-- Audit / RBAC / OIDC
```

These are logical boundaries first, not mandatory microservices. The current recommendation remains **one codebase / one build artifact with multiple entrypoint commands** until scaling or security isolation requires separate deployments.

## Phase 0.6 — Configuration snapshot foundation

0.6 should stay read-only and focus on correct backup/evidence, not compliance logic yet.

### Artifact contract

```text
data/configs/<source>/<entity-id>/<timestamp>/
+-- config.txt / config.xml
+-- metadata.json
+-- sha256.txt
+-- collection.log
```

`metadata.json` should contain at minimum:

```json
{
  "source": "checkpoint|spark|vsx|panorama",
  "entity_id": "...",
  "collected_at": "...",
  "method": "...",
  "status": "success|failed|partial",
  "sha256": "...",
  "size_bytes": 0,
  "collector_version": "..."
}
```

The actual runtime store must not live in Git.

### Vendor collection candidates

- **Gaia / Check Point**: `show configuration` is an appropriate read-only system-configuration representation for Gaia. Management/policy configuration is a separate evidence class and must not be confused with OS configuration.
- **Quantum Spark / Gaia Embedded**: use the supported CLI discovered/validated by 0.5.1. Configuration commands must be verified against the actual firmware before the adapter is promoted.
- **PAN-OS / Panorama**: running/candidate configuration and Panorama/device configuration bundle workflows are supported by PAN-OS/Panorama APIs and export mechanisms. Store XML as immutable raw evidence.
- **VSX**: host/VS OS state and centrally managed policy are different configuration layers; model them separately rather than concatenating them into one ambiguous file.

### Backup UI

Device detail page should gain a Configuration History section:

```text
22.08.2026 23:30  LIVE BACKUP  sha256: 8f3a...  184 KB
21.08.2026 23:30  CHANGED      sha256: e921...  182 KB
20.08.2026 23:30  SAME         sha256: e921...  182 KB
```

Actions in the read-only phase:

- View raw config
- Download raw config (authorized users only)
- View diff against previous
- View parsed facts
- View findings

## Phase 0.6.x — normalization before compliance

Do not run regulation checks directly against ad-hoc regexes over raw text. First create a normalized fact model.

Example facts:

```json
{
  "management": {
    "ssh_enabled": true,
    "allowed_admin_networks": []
  },
  "logging": {
    "remote_syslog_enabled": true
  },
  "routing": {},
  "interfaces": [],
  "ha": {},
  "ntp": {},
  "dns": {},
  "snmp": {},
  "password_policy": {},
  "security_policy": {}
}
```

Vendor parsers translate configuration into facts; compliance rules consume facts.

## Compliance engine

A compliance rule is versioned code/data, not a sentence in the UI.

```json
{
  "rule_id": "NET-MGMT-SSH-001",
  "title": "Restrict administrative SSH exposure",
  "vendor": "checkpoint",
  "severity": "high",
  "fact_query": "management.ssh...",
  "framework_refs": [
    {"framework": "internal", "control": "..."},
    {"framework": "pci-dss-4.0.1", "control": "..."},
    {"framework": "nist", "control": "..."},
    {"framework": "bddk", "control": "..."}
  ],
  "evidence_required": true,
  "remediation": "..."
}
```

A rule result should be `pass`, `fail`, `not_applicable`, `unknown`, or `insufficient_evidence` — never force missing data into `pass` or `fail`.

## Framework model

The platform should distinguish **technical evidence** from a formal compliance opinion.

- NIST CSF 2.0 is outcome/risk-oriented and explicitly does not prescribe a single implementation. Use it as a mapping/profile layer; technical checks can also map to more specific control catalogs such as NIST SP 800-53 where appropriate.
- PCI DSS v4.0.1 is the current published PCI DSS baseline and contains many requirements beyond firewall configuration. F-Buddy can automate technical evidence for applicable network/security configuration requirements, but cannot by itself certify PCI DSS compliance.
- BDDK's information-systems/electronic-banking regulations include governance, process and operational requirements beyond device configuration. The platform should therefore report `technical evidence coverage`, not claim regulatory compliance solely from configuration snapshots.

The same principle applies to future ISO 27001, CIS, vendor hardening guides and internal standards.

## Evidence-first finding

```json
{
  "finding_id": "...",
  "rule_id": "NET-MGMT-SSH-001",
  "result": "fail",
  "device": "...",
  "observed_at": "...",
  "config_sha256": "...",
  "parser_version": "...",
  "rule_version": "...",
  "evidence": [
    {"artifact": "config.txt", "line_start": 120, "line_end": 123}
  ]
}
```

This makes findings reproducible and auditable.

## Storage evolution

Local prototype:

```text
filesystem -> run/config snapshots
```

Container stage:

```text
PVC or object storage -> immutable raw artifacts
PostgreSQL -> identities, jobs, metadata, normalized facts, findings, diffs
```

Do not put large raw configurations into relational rows unless there is a concrete operational reason. Object/PVC storage plus hashes/metadata in PostgreSQL is cleaner.

## Security boundaries

Collector credentials are high-value secrets. Keep them out of API/UI containers.

```text
API/UI
  no device credentials

Collector workers
  scoped vendor credentials
  restricted egress
  short-lived execution

Processor/compliance
  no network-device credentials
```

Configuration backups can contain secrets, hashes, certificates, community strings or encrypted credentials. Therefore:

- encryption at rest
- strict RBAC
- access auditing
- no raw config in support bundles
- no raw config in Git
- configurable retention
- secret-aware display/redaction

are baseline product requirements, not optional polish.

## Future edit mode

Do not build write access as an extension of a read collector method. Treat it as a separate change-control plane:

```text
proposed change
 -> generated diff
 -> validation
 -> approval
 -> pre-change backup
 -> apply
 -> commit/install
 -> post-change verification
 -> rollback evidence
 -> audit trail
```

The platform can eventually become a central firewall operations console, but the read/evidence plane must remain independently usable if write functions are disabled.

## Recommended sequence

1. 0.5 Final — freeze the operational Network Inventory module.
2. 0.6.0A — Palo Alto active running-config evidence through Panorama XML API; immutable XML + SHA-256 + privacy-safe diagnostics.
3. 0.6.0B — Palo Alto vendor-native recovery artifact / bundle POC and validation.
4. 0.6.1 — Check Point standalone and ClusterXL configuration evidence, then native backup artifact.
5. 0.6.2 — VSX physical/logical configuration evidence model.
6. 0.6.3 — unified configuration history + diff UI and normalized facts foundation.
7. 0.6.4 — first internal hardening rule engine.
8. 0.7 — framework mappings, evidence reports, API/database/container maturity.
9. Later — scheduled backup worker, OIDC/RBAC, PostgreSQL/object storage, Kubernetes jobs, approved edit/change plane.

## Configuration evidence storage (A4.3.2)

Configuration collection and configuration storage are separate contracts. Collectors produce validated evidence; storage assigns the SHA-256 identity and persists one immutable object per unique payload. History records reference those objects. Repeated `SAME` observations remain visible without duplicating the payload.

The same storage contract accepts PAN XML, future Check Point Gaia/Clish text, and later binary native-backup artifacts. Vendor transport/authentication remains outside the storage layer.
