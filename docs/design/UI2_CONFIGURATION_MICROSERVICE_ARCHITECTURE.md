# UI 2.0 — Configuration Microservice Architecture (`ui2-configuration`)

## Status

**CONSENSUS DRAFT — REFINED POST-CODEX ARCHITECTURAL REVIEW (2026-09-19)**  
Authority: `PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md` (FROZEN), `CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md` (APPROVED), `UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` (FROZEN), and `docs/design/CODEX_CONFIGURATION_ARCHITECTURE_REVIEW.md`.

---

## 1. Executive Summary & Boundaries

The `ui2-configuration` microservice is an **internal, stateless parsing and sanitization engine**. Following the architectural consensus reached with Codex:

1. **Internal Stateless Parser**:
   - Zero database credentials or direct database connections.
   - Zero network device credentials or SSH/API access.
   - No external Ingress or public browser routing. Exposed only within the Kubernetes cluster to `ui2-worker` (and `ui2-service` for on-demand read parsing).
2. **Single Orchestration & Persistence Boundary**:
   - `ui2-worker` remains the single owner of device contact (SSH/API), encrypted raw artefact persistence, change state determination, and database transactions (`device_configuration_run`, `device_configuration_index`).
   - During collection, `ui2-worker` streams the raw output to the encrypted artefact store and concurrently to `ui2-configuration` via internal HTTP streaming.
3. **Multi-Vendor SPI**:
   - Check Point Gaia in Phase 1 (1:1 port of `checkpoint_config_collector.py`).
   - Palo Alto Networks in Phase 2 (reusing the existing StAX XML streaming processor per frozen CG-4..CG-7).
4. **Privacy & Replay Compliance (`aiview`)**:
   - Native compatibility with `role:replay_viewer` relationship-preserving HMAC masking and field-level classification.

---

## 2. Multi-Vendor Parser SPI

```java
package com.securityexpert.nexus.ui2.configuration.core;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public interface VendorConfigParser {
    ConfigVendor vendor();
    ConfigFormat format();

    ConfigParseResult parse(ConfigParseContext context, InputStream content);
}
```

### Core Enums & Domain Models

```java
public enum ConfigVendor {
    CHECK_POINT,
    PALO_ALTO
}

public enum ConfigFormat {
    GAIA_CLISH,
    PAN_OS_XML
}

public record ConfigParseContext(
    String deviceId,
    ConfigVendor vendor,
    ConfigFormat format,
    String entityType   // "physical" (Check Point is host-level per CG-1)
) {}

public record ConfigSetting(
    String setting,              // e.g. "Hostname", "Primary DNS", "Password · Min-Password-Length"
    String value,                // e.g. "FW-TITAN-01", "192.0.2.1", "12"
    String origin,               // "local", "panorama_template", "panorama_device_group"
    Optional<String> context     // context label if vsys (PAN)
) {}

public record ConfigSection(
    String id,                   // e.g. "system", "dns", "interfaces", "routing", "other"
    String label,                // e.g. "System", "DNS", "Interfaces", "Routing", "Other Gaia Configuration"
    int count,
    List<ConfigSetting> settings
) {}

public record ConfigHighlight(
    String label,                // "Hostname", "Domain", "Timezone", "Primary DNS", ...
    String value,
    String section,
    String sectionLabel
) {}

public record ConfigIndexEntry(
    String context,
    String section,
    Optional<String> source,
    int entryCount,
    boolean hasOverride
) {}

public record ConfigParseResult(
    ConfigVendor vendor,
    Optional<String> canonicalHash, // SHA-256 over set lines; empty if no set lines
    int withheldLineCount,          // Number of secret lines withheld
    String sanitizedText,           // Exact 4-line header + sanitized text
    List<ConfigIndexEntry> index,
    List<ConfigSection> sections,
    List<ConfigHighlight> highlights,
    int totalSettingsCount
) {}
```

---

## 3. Check Point Gaia 1:1 Implementation Rules (`CheckPointGaiaConfigParser`)

Aligned 100% with `nexus/configuration/checkpoint_config_collector.py`:

### 3.1. Canonical Line Normalization & Hash Calculation (CG-2)
- Line trimming uses `.strip()`, exactly matching Python.
- Filters lines starting with `set `.
- Skips control-flow context switches (e.g. `set virtual-system \d+`).
- If no canonical `set` lines are found: `canonicalHash = Optional.empty()`.
- If `set` lines exist: `canonicalHash = SHA-256(String.join("\n", set_lines))`.
- Excludes the dynamic `#` header containing timestamps, preventing false drift detection.

### 3.2. Secret Withholding & Safe Allowlist (CG-3 & 0.7.2 Rules)
- **Secret Detection Regex (`SECRET_LINE_RE`)**:
  `(?i)(?:password|passwd|secret|community|credential|token|psk|pre[-_ ]?shared|private[-_ ]?key|api[-_ ]?key|auth(?:entication)?[-_ ]?key|encrypted[-_ ]?secret|(?:^|[\s_-])key(?:$|[\s_-]))`
- **Safe Policy Knobs Allowlist (`PASSWORD_POLICY_SAFE_RE`)**:
  Matches `^set\s+password-controls\s+(?:min-password-length|password-min-length|complexity|palindrome-check|history-check|password-history|password-expiration|expiration-warning-days|expiration-lockout-days|deny-on-fail|deny-on-nonuse|force-change-when|password-format)\b`.
  *Lines matching this allowlist are preserved in the sanitized view and NOT withheld.*
- **Banner / MOTD Redaction (`MESSAGE_BODY_RE`)**:
  `^(set\s+message\s+\S+(?:\s+(?:on|off))?)\s+msgvalue\s+.*$` replaced by `$1 msgvalue [SECURITYEXPERT BANNER BODY WITHHELD]`.
- Withheld lines are replaced with:
  `# [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]`.
- Output starts with the exact four-line sanitized header:
  ```
  # SecurityExpert Check Point Gaia configuration evidence (redacted)
  # schema=checkpoint-gaia-redacted-v1
  # raw-canonical-sha256=<hash or unavailable>
  # secret-bearing-lines-withheld=<count>
  ```

### 3.3. Governed 14-Section Mapping
Every `set` line is tokenized using POSIX shell splitting and classified into the 14 governed sections:
1. `system`: `hostname`, `domainname`, `timezone`, `time`, `clock`
2. `dns`: `dns`
3. `ntp`: `ntp`
4. `management`: `web`, `ssh`, `allowed-client`, `management`, `inactivity-timeout`, `expert-password`
5. `password_policy`: `password-controls`
6. `banner`: `message`, `banner`
7. `services`: (reserved; per Python logic, `snmptrap` falls to `other`)
8. `logging`: `syslog`, `log`, `logging`
9. `high_availability`: `cluster`, `clusterxl`, `ha`, `high-availability`
10. `interfaces`: `interface`, `bonding`, `bridge`, `vlan`
11. `routing`: `static-route`, `route`, `routing`, `ospf`, `bgp`, `rip`
12. `snmp`: `snmp`
13. `authentication`: `user`, `aaa`, `radius`, `tacacs`, `ldap`, `authentication`
14. `other`: Fallback for all other Gaia configuration directives

### 3.4. Highlights Extraction
- `Hostname` (from `system`)
- `Domain` (from `system`)
- `Timezone` (from `system`)
- `Primary DNS` (from `dns`)
- `Secondary DNS` (from `dns`)
- `Primary NTP Server` (from `ntp`)
- `Secondary NTP Server` (from `ntp`)

---

## 4. Palo Alto Extension Architecture (Phase 2)

- Reuses the existing streaming StAX XML parser (`PaloAltoConfigStreamProcessor`) without holding entire documents in memory.
- `PAN_OS_XML` format only (CLI set format dropped per frozen CG-4).
- Category indexing per vsys: `address`, `service`, `rulebase`, `zone`, `network`, `deviceconfig`.
- Panorama template and device group override detection (`src="local"` vs `src="tpl"`).

---

## 5. Microservice Internal REST API

Exposed on internal port `8084` (`ui2-configuration` ClusterIP):

### `POST /api/v1/parse`
Streaming endpoint:
- Headers:
  - `Content-Type: text/plain` (or `application/xml` for PAN)
  - `X-Nexus-Vendor: check_point`
  - `X-Nexus-Format: gaia_clish`
  - `X-Nexus-Device-Id: <uuid>`
- Body: raw configuration stream.
- Response: JSON `ConfigParseResult`.

### Health Probes
- `GET /healthz`: lightweight probe returning `{"status":"UP"}`.

---

## 6. Worker & Storage Integration Lifecycle

```
[Target Device]
       |
       | 1. SSH / API Read
       v
  [ui2-worker]
   ├── 2. Stream to Artefact Store (raw bytes -> encrypted AES-GCM + hash)
   ├── 3. Stream to ui2-configuration (/api/v1/parse)
   │        │
   │        v (returns canonicalHash, sanitizedText, index, highlights)
   ├── 4. IndexDeviationComputer.compute(previousIndex, currentIndex)
   └── 5. Write single DB Transaction:
            - device_configuration_run
            - device_configuration_index
```

- **Persistence Reality**: Writes to existing database tables (`device_configuration_run`, `device_configuration_index`) without requiring schema breaking changes.
- **Deviation Computation**: Kept inside `ui2-worker` via existing `IndexDeviationComputer`.

---

## 7. Privacy & `aiview` Compliance

- `ui2-configuration` produces sanitized text and structured settings.
- When `ui2-service` serves configuration data to an `aiview` session (`role:replay_viewer`):
  - `PrivacyMaskingResponseBodyAdvice` intercepts the response.
  - IP addresses in setting values (DNS, NTP, gateways, interfaces) are deterministically mapped to synthetic subnets via `SubnetPreservingIpMasker`.
  - Hostnames in highlights/settings are pseudonymized via `TopologyNamePseudonymizer`.
  - Withheld lines remain safely withheld; zero cleartext credentials reach the response.
- Concrete privacy test suite: `ConfigurationPrivacyMaskingTest.java`.

---

## 8. Kubernetes Deployment Specifications

File: `deploy/ui2/54-configuration-deployment.yaml`
- Service: `ClusterIP` on port 8084 (no Ingress).
- Security Context:
  - `runAsNonRoot: true`
  - `readOnlyRootFilesystem: true`
  - `allowPrivilegeEscalation: false`
  - Capabilities drop: `ALL`
  - `seccompProfile.type: RuntimeDefault`
- Resources:
  - Requests: 64Mi RAM / 50m CPU
  - Limits: 256Mi RAM / 200m CPU
- Probes:
  - Liveness & Readiness: HTTP GET `/healthz` on port 8084.
- Network Policy: ingress restricted to `app.kubernetes.io/name: ui2-worker` and `ui2-service`.
