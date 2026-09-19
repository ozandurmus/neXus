# UI2 Compliance Microservice Architecture Specification

**Status:** CONSENSUS RATIFIED (Post-Review with Codex/Astra & Claude/Fable)  
**Date:** 2026-09-19  
**Service Name:** `ui2-compliance`  
**Target Port:** `8085` (Internal ClusterIP, stateless evaluation engine)  
**Authorities & References:**
- `AGENTS.md` (Constitution: Identity, Evidence, Fail-Closed, Vendor Semantics laws)
- `docs/design/COMPLIANCE_CHECK_ENGINE.md` (Check model, selectors, fixed 14 assertion operators)
- `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` (Control enrichment, multi-framework grouping, assignment policy)
- `docs/design/CODEX_COMPLIANCE_ARCHITECTURE_REVIEW.md` (Codex / Astra review)
- `docs/design/CLAUDE_COMPLIANCE_ARCHITECTURE_REVIEW.md` (Claude / Fable review)
- `docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` (`aiview` privacy boundary)

---

## 1. Executive Summary & Product Objective

The neXus platform requires an enterprise-grade, dedicated **Compliance Microservice** (`ui2-compliance`) to evaluate network security device configurations (starting with Check Point Gaia, followed by Palo Alto Networks PAN-OS) against leading industry security benchmarks and regulatory frameworks:
- **CIS Benchmark** (CIS Check Point Gaia Firewall Benchmark v1.1.0, CIS Palo Alto PAN-OS Benchmark)
- **PCI-DSS v4.0.1** (Requirements 1.2, 1.3, 2.2, 8.2, 8.3, 10.2 covering network security controls)
- **NIST SP 800-53 Rev 5 / SP 800-41 Rev 1** (CM-6 Configuration Settings, CM-7 Least Functionality, AC-7, AC-11/12, AU-8)
- **Financial Baseline / BDDK / COBIT 2019** (Banking security baselines, change governance contributions, continuous technical audit)
- **Vendor-proven operational best practices** inspired by market leaders (BackBox, Indeni, Tufin, Opinnate).

### Core Product Directives
1. **Dedicated Stateless Microservice:** `ui2-compliance` runs as an independent stateless microservice on port 8085 alongside `ui2-service`, `ui2-worker`, and `ui2-configuration`. It has zero database access, zero SSH/device credentials, and zero public ingress.
2. **Deterministic Control Assignability:** Controls can be assigned individually or via framework profiles/bundles to specific firewalls using opaque device UUIDs or registered tags/groups (never fragile hostname globs).
3. **Fail-Closed Missing Data Handling (`DATA_UNAVAILABLE`):** If a control requires a piece of configuration/evidence not yet collected from the firewall, the control **must NOT be omitted or discarded**. It is explicitly recorded as `verdict: UNKNOWN`, `reason_code: EVIDENCE_MISSING`, and rendered in the UI as `DATA_UNAVAILABLE` ("Veri Yok / Eksik"), with the missing required evidence/command documented.
4. **Balanced Tri-Metric Scoring:** Avoids the denominator defect where missing data artificially inflates scores:
   - **Observed Compliance:** `PASS / (PASS + FAIL)` (compliance of verifiable controls)
   - **Evidence Coverage:** `(PASS + FAIL) / (PASS + FAIL + DATA_UNAVAILABLE)` (visibility rate)
   - **Assured Compliance (Headline):** `PASS / (PASS + FAIL + DATA_UNAVAILABLE)` (strict audit posture)
5. **Dedicated Compliance UI Screen:** A separate, first-class screen in `ui2/frontend` (`/compliance` or `?screen=compliance`) providing:
   - High-level KPIs (Assured Compliance %, Evidence Coverage %, Critical Deficiencies, Data Gaps).
   - Framework summary cards (CIS, PCI-DSS v4.0.1, NIST SP 800-53, Financial Baseline).
   - Interactive Controls Table: Control ID, Title, Severity, Frameworks, Target Device Count, Compliance Rate (%), Status breakdown (`PASS`, `FAIL`, `DATA_UNAVAILABLE`).
   - Per-device compliance drill-down and assignment manager.
6. **Multi-Vendor Extensibility (3-Tier Model):**
   - **Tier 1 - Control Intent:** Vendor-neutral security requirement, rationale, severity, framework mappings.
   - **Tier 2 - Vendor Binding:** Vendor-specific evidence requirement, gate entry reference, and assertion rules.
   - **Tier 3 - Evaluator SPI:** Vendor normalizer and evidence extractor (Check Point Gaia first, Palo Alto PAN-OS in Phase 2).

---

## 2. System Architecture & Boundaries

```
                           +-------------------------------------+
                           |            React Frontend           |
                           |  (ComplianceScreen.tsx @ /compliance)|
                           +-------------------------------------+
                                              |
                                              | HTTPS / JSON API
                                              v
                           +-------------------------------------+
                           |             ui2-service             |
                           |  (REST Gateway, Auth, DB Access,   |
                           |   Assignment Manager, Posture Rollup)|
                           +-------------------------------------+
                                    /                       \
        Store/Query Runs & Policies/                         \ POST /api/v1/compliance/evaluate
                                  /                           \ (Stateless JSON Evidence Envelope)
                                 v                             v
       +-------------------------------+             +----------------------------------+
       |          ui2-db               |             |          ui2-compliance          |
       |  - compliance_profile         |             |  (Internal Port 8085, Stateless) |
       |  - compliance_assignment      |             |  - Pure Evaluation Engine        |
       |  - compliance_waiver          |             |  - 14 Fixed Assertion Operators  |
       |  - compliance_eval_run        |             |  - Versioned Control Catalog     |
       |  - compliance_eval_item       |             |  - Check Point Gaia Evaluator    |
       +-------------------------------+             |  - Palo Alto PAN-OS Evaluator SPI|
                                                     +----------------------------------+
                                                               ^
                                                               | Evaluated via ui2-service
                                                     +----------------------------------+
                                                     |           ui2-worker             |
                                                     | (SSH Collector -> ui2-config     |
                                                     |  -> saves config to DB)          |
                                                     +----------------------------------+
```

### 2.1 Microservice Responsibilities
- **`ui2-compliance` (Stateless Pure Function Engine):**
  - Receives `POST /api/v1/compliance/evaluate` with device evidence snapshot (parsed sections, settings AST, inventory metadata).
  - Evaluates assigned controls using the 14 fixed operators (`present`, `absent`, `equals`, `not_equals`, `matches`, `not_match`, `any_match`, `none_match`, `gte`, `lte`, `in`, `not_in`, `count_gte`, `count_lte`).
  - Returns per-control evaluation results with verdicts, reason codes, and missing evidence references.
- **`ui2-service` (Orchestration, API & Persistence):**
  - Manages CRUD for control assignments and waivers.
  - Queries latest parsed configurations and triggers compliance evaluation.
  - Persists immutable run snapshots (`compliance_eval_run`, `compliance_eval_item`).
  - Enforces `aiview` role privacy masking (HMAC hashing of identifiers, redaction of sensitive evidence).

---

## 3. Data & Control Model

### 3.1 Control Schema (3-Tier Separation)

```json
{
  "id": "sec_password_min_length",
  "title": "Minimum Password Length Enforcement (>= 12 chars)",
  "rationale": "Enforcing adequate password length prevents brute force and dictionary attacks.",
  "severity": "high",
  "control_plane": "device_os",
  "evaluation_scope": "device",
  "frameworks": [
    { "framework": "CIS", "reference": "2.1.1", "version": "1.1.0", "profile": "Level 1", "mapping_strength": "SATISFIES" },
    { "framework": "PCI-DSS", "reference": "8.3.6", "version": "4.0.1", "mapping_strength": "SATISFIES" },
    { "framework": "NIST-800-53", "reference": "IA-5(1)", "version": "Rev 5", "mapping_strength": "CONTRIBUTES_TO" },
    { "framework": "FINANCIAL_BASELINE", "reference": "Identity & Access §4.1", "mapping_strength": "SATISFIES" }
  ],
  "bindings": [
    {
      "vendor": "check_point",
      "platform_family": "gaia",
      "evidence_requirement": {
        "source": "current_configuration.sections[id=password_policy].settings",
        "key": "password-controls min-password-length",
        "required_evidence_id": "gaia.password_controls.min_length",
        "gate_entry_id": "CP-CMD-0012"
      },
      "assertion": {
        "op": "gte",
        "target_value": 12
      }
    },
    {
      "vendor": "palo_alto",
      "platform_family": "panos",
      "evidence_requirement": {
        "source": "current_configuration.sections[id=password_complexity].settings",
        "key": "password-complexity minimum-length",
        "required_evidence_id": "panos.password_complexity.min_length",
        "gate_entry_id": "PAN-CMD-0045"
      },
      "assertion": {
        "op": "gte",
        "target_value": 12
      }
    }
  ]
}
```

### 3.2 Verdict States & Reason Codes

| Verdict | Reason Code | Display Status | Audit Meaning | Scoring Impact |
|---|---|---|---|---|
| `PASS` | `ASSERTION_SATISFIED` | `PASS` | Configuration satisfies rule | Counted in PASS |
| `FAIL` | `ASSERTION_FAILED` | `FAIL` | Configuration violates rule | Counted in FAIL |
| `UNKNOWN` | `EVIDENCE_MISSING` | `DATA_UNAVAILABLE` | Command/evidence not collected yet | Gaps metric |
| `UNKNOWN` | `COLLECTION_FAILED` | `COLLECTION_FAILED` | SSH/collector timed out or failed | Alert / Gaps metric |
| `UNKNOWN` | `EVIDENCE_STALE` | `STALE_EVIDENCE` | Configuration older than max age | Gaps metric |
| `NOT_APPLICABLE` | `PROVEN_UNSUPPORTED`| `NOT_APPLICABLE` | Feature inapplicable (e.g. cluster vs standalone)| Excluded |

### 3.3 Scoring Formulas

For assigned, applicable controls:
- **Observed Compliance:**
  $$\text{Observed} = \frac{\sum w \cdot \text{PASS}}{\sum w \cdot (\text{PASS} + \text{FAIL})}$$
- **Evidence Coverage:**
  $$\text{Coverage} = \frac{\sum w \cdot (\text{PASS} + \text{FAIL})}{\sum w \cdot (\text{PASS} + \text{FAIL} + \text{DATA\_UNAVAILABLE})}$$
- **Assured Compliance (Headline Metric):**
  $$\text{Assured} = \text{Observed} \times \text{Coverage} = \frac{\sum w \cdot \text{PASS}}{\sum w \cdot \text{Assigned Controls}}$$

---

## 4. Check Point Gaia Initial Control Catalog

A curated, financial-grade catalog of 20+ controls mapped to CIS Gaia v1.1.0 and PCI-DSS v4.0.1:

1. **Authentication & Password Hygiene:**
   - Minimum password length (>= 12 chars) [CIS 2.1.1, PCI-DSS 8.3.6]
   - Password complexity required (mixed case, digits, symbols) [CIS 2.1.2, PCI-DSS 8.3.6]
   - Password history reuse restriction (>= 5 generations) [CIS 2.1.3, PCI-DSS 8.3.7 - STRICTER_THAN]
   - Account lockout threshold (<= 5 attempts) [CIS 2.1.4, PCI-DSS 8.3.4 - STRICTER_THAN]
   - Account lockout duration (>= 30 minutes) [CIS 2.1.5, PCI-DSS 8.3.4]
2. **Administrative Access & Remote Management:**
   - Administrative inactivity session timeout (<= 10 min) [CIS 2.5.2, PCI-DSS 8.2.8 - STRICTER_THAN]
   - SSH Protocol v2 strictly enforced [CIS 2.5.3, PCI-DSS 2.2.5]
   - Insecure Telnet service strictly disabled [CIS 2.5.6, PCI-DSS 2.2.4]
   - HTTP web management disabled / HTTPS only [CIS 2.5.5, PCI-DSS 2.2.3]
   - Non-default admin username configured [CIS 2.1.8, PCI-DSS 2.2.1]
   - AAA central provider configured (RADIUS/TACACS) [CIS 2.5.4, PCI-DSS 8.3.1]
   - Weak SSH ciphers disabled (CBC/3DES/RC4) [CIS 2.5.4, NIST CM-6] *(DATA_UNAVAILABLE until `show ssh ciphers` is collected)*
3. **Audit Logging & Time Synchronization:**
   - Dual redundant NTP servers configured [CIS 2.3.1, PCI-DSS 10.6.1]
   - Remote Syslog / SIEM logging enabled [CIS 2.4.1, PCI-DSS 10.2.1]
   - Audit log rotation and storage quota set [CIS 2.4.2, NIST AU-4]
   - System core dump generation restricted [CIS 2.4.3, Ops Baseline]
4. **Network & System Hardening:**
   - Legal warning login banner configured [CIS 2.2.1, PCI-DSS 1.2.1]
   - SNMP v1/v2c disabled, SNMP v3 Auth/Priv enforced [CIS 2.6.1, PCI-DSS 2.2.2] *(DATA_UNAVAILABLE until `show snmp v3` is collected)*
   - Unused network interfaces disabled [CIS 2.7.1, PCI-DSS 1.2.2] *(DATA_UNAVAILABLE until interface detail collected)*
   - ARP proxy & cache validation enabled [CIS 2.7.2, BackBox/Indeni Baseline]

---

## 5. PostgreSQL Schema (`ui2-service` managed)

```sql
-- Profiles (e.g. CIS Level 1, PCI-DSS v4.0.1, Financial Baseline)
CREATE TABLE compliance_profile (
    profile_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    framework VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Assignments by Opaque Target ID (Device UUID or Tag/Group ID)
CREATE TABLE compliance_assignment (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id VARCHAR(64) NOT NULL,            -- Device UUID or Tag ID (never hostname)
    target_type VARCHAR(16) NOT NULL,          -- 'DEVICE' or 'TAG'
    profile_id VARCHAR(64),                    -- Optional profile bundle
    control_id VARCHAR(64),                    -- Optional individual control
    effect VARCHAR(8) NOT NULL DEFAULT 'INCLUDE', -- 'INCLUDE' or 'EXCLUDE'
    priority INT NOT NULL DEFAULT 100,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Evaluation Runs
CREATE TABLE compliance_eval_run (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES device(device_id),
    catalog_version VARCHAR(32) NOT NULL,
    catalog_hash VARCHAR(64) NOT NULL,
    observed_compliance NUMERIC(5,2),
    evidence_coverage NUMERIC(5,2),
    assured_compliance NUMERIC(5,2),
    total_assigned INT NOT NULL,
    pass_count INT NOT NULL,
    fail_count INT NOT NULL,
    data_unavailable_count INT NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Evaluation Item Results
CREATE TABLE compliance_eval_item (
    item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES compliance_eval_run(run_id) ON DELETE CASCADE,
    control_id VARCHAR(64) NOT NULL,
    verdict VARCHAR(16) NOT NULL,              -- 'PASS', 'FAIL', 'UNKNOWN', 'NOT_APPLICABLE'
    reason_code VARCHAR(32) NOT NULL,          -- 'ASSERTION_SATISFIED', 'EVIDENCE_MISSING', etc.
    display_status VARCHAR(32) NOT NULL,       -- 'PASS', 'FAIL', 'DATA_UNAVAILABLE'
    severity VARCHAR(16) NOT NULL,
    missing_evidence_id VARCHAR(64),
    required_gate_entry VARCHAR(32),
    details_json JSONB
);
```

---

## 6. Frontend UI Specification (`ComplianceScreen.tsx`)

Following Page 4 & Page 7 of the Design PDF:
1. **Top Metric Cards:**
   - **Assured Compliance:** `74%` (Headline tile: `88% observed · 84% coverage`)
   - **Evaluated Firewalls:** `60 / 60` (48 full evidence, 12 partial)
   - **Critical Deficiencies:** `4` (highlighted in red)
   - **Data Gaps (`DATA_UNAVAILABLE`):** `6` (highlighted in amber)
2. **Framework Compliance Cards:**
   - **CIS Gaia Benchmark v1.1.0:** 82% Assured · Level 1 & 2
   - **PCI-DSS v4.0.1:** 76% Assured · Cardholder firewalls
   - **NIST SP 800-53 Rev 5:** 85% Assured · Access & Configuration
   - **Financial Core Baseline:** 70% Assured · Banking hardening
3. **Interactive Controls Table:**
   - Quick Filters: `All (24)`, `Failing (4)`, `Data Unavailable (6)`, `Passing (14)`.
   - Columns: `Control ID` | `Title & Frameworks` | `Severity` | `Target Devices` | `Compliance Rate` | `Status Breakdown` | `Actions`.
   - Stacked Progress Bar: Shows green (PASS), red (FAIL), and amber (DATA_UNAVAILABLE) portions.
4. **Detail Drawer:**
   - Rationale, remediation guidance, required command gate entry, and list of evaluated devices.
