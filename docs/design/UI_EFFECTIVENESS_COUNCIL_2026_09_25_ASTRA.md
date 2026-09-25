**Recommendation: merge Devices and Configuration into one device workspace, while keeping their evidence separate. Retain Compliance and Backups as fleet workflows. Accept the onboarding design conditionally; its deployed acceptance remains UNKNOWN. Keep ASA text backup as the current scope and treat native archives as a separate, gated recovery build.**

This is a **single-author, six-lens review**, as requested—not six independent reviewers or a recorded external council. All 19 screenshots were reviewed. Proposed layouts below are recommendations, not observations.

References: **O** = [Onboarding contract](/Users/OzanDur/Codo/nexus/docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md); **A** = [ASA contract](/Users/OzanDur/Codo/nexus/docs/design/CISCO_ASA_CONTRACT.md); **G** = [AGENTS.md](/Users/OzanDur/Codo/nexus/AGENTS.md). Procedure: [council skill](/Users/OzanDur/Codo/nexus/.claude/skills/nexus-decision-council/SKILL.md), [reviewer role](/Users/OzanDur/Codo/nexus/roles/REVIEWER.md), and [product decision lenses](/Users/OzanDur/Codo/nexus/PROJECT_VISION.md:83).

Two authority issues require explicit treatment:

- **O is already FROZEN and PO-approved.** This review concerns acceptance of delivery and any amendments, not first approval of the design.
- **A is DRAFT but claims implementation authority.** That conflicts with G’s contract-status law for unresolved command/security semantics. The stated Backbox observation does not resolve that authority conflict.

## 1. UI/UX Product Designer — agenda 1 chair

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Merge the duplicated device navigation into one workspace. Preserve Inventory and Configuration as clearly labelled tabs. Evidence: `05_devices_cluster_interfaces.png`, `10_config_list.png`, `11_config_cluster_top.png`. |
| 2 — Onboarding | Consent to server-owned progress, explicit stopped states and a final destination in Devices. Closing a dialog must not abandon the task. [O] |
| 3 — ASA | Consent to explicitly named backup types: “Configuration text” and, if delivered, “ASA archive.” Their coverage must be visible before selection. [A] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| UX-1 | “One screen” means placing every table on one long page. | Dense identity and comparison sections: `11_config_cluster_top.png`, `12_config_cluster_diff.png`. | One persistent header, short summary, task tabs and progressive disclosure. |
| UX-2 | The current dialog is ready for acceptance. | Permission denial beside “Create credential”; post-submit flow absent: `20_add_device_dialog.png`. | Coherent permitted actions; demonstrate running, stopped, skipped and completed states. |
| UX-3 | A generic green “Backed up” badge adequately describes ASA coverage. | Text/archive distinction in A. | Name the backup type, included scope and unverified recovery status. |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | Approve one Devices workspace and removal of the separate Configuration navigation item? |
| 2 | Approve opening the newly registered device immediately, with progress in its detail panel? |
| 3 | Require backup coverage to be visible wherever backup success is shown? |

## 2. Network/Security Manager

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Keep failed collection, missing backups and critical compliance failures one click from Overview. Their summaries already exist: `01_overview.png`. |
| 2 — Onboarding | Conditional consent: manual add and discovery import must produce the same accountable result, independently of the browser. [O] |
| 3 — ASA | Accept text backup temporarily if the team knows its limits and another recovery arrangement covers excluded assets. [A] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| MGR-1 | Raw failure strings are an effective management work queue. | `01_overview.png`, `02_overview_mid.png`. | Group by understandable cause, affected devices and next action; retain technical detail underneath. |
| MGR-2 | A stopped reason on hover is sufficient operational visibility. | O, “Devices list.” | Persistent stopped-step text, a filter for onboarding exceptions and direct Retry access. |
| MGR-3 | Text-only coverage may remain an indefinite, unowned compromise. | A excludes archive content. | Named recovery owner, accepted gap and review date. |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | Which evidence-age and backup-age thresholds should trigger attention? |
| 2 | Who owns stopped imports and unresolved prerequisites? |
| 3 | Who owns ASA recovery coverage until native archives are validated? |

## 3. Firewall operator — Check Point / Palo Alto

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Keep the selected device and its hierarchy fixed while switching between runtime and configuration. Current navigation requires separate destinations: `05_devices_cluster_interfaces.png`, `11_config_cluster_top.png`. |
| 2 — Onboarding | Consent to identity → inventory → configuration, with retry at the failed step. [O] |
| 3 — ASA | Prefer a gated native archive where certificate/VPN assets are required for recovery. Text backup alone is insufficient for that requirement. [A] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| OP-1 | “Live,” “Identity verified” and an unexplained “UNKNOWN” tell me the device’s state. | `09_devices_gateway.png`. | Separate enrollment, identity verification, last successful read and applicable HA state. |
| OP-2 | Registration is usable without a direct route to the new device. | O does not specify selection, tree expansion or navigation after submission. | Select its stable device reference, expand its known hierarchy and retain progress after reopening. |
| OP-3 | Text backup should be the permanent answer for VPN-bearing ASAs. | A, “Backbox comparison.” | Validated archive coverage or a demonstrated alternative recovery procedure. |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | May the first release remove the second tree while preserving direct links to every current device tab? |
| 2 | Should successfully collected facts be inspectable while a later onboarding step remains running? |
| 3 | Is certificate/VPN recovery required in the first accepted ASA release? |

## 4. Configuration Management Specialist + Data/Inventory Architect

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Yes: separate product planes may share a screen. Use one entity selection with distinct runtime, configured-state and comparison projections. G requires semantic separation, not separate navigation. |
| 2 — Onboarding | Consent to separate audited jobs and explicit unsupported-step outcomes. [O] |
| 3 — ASA | Consent to distinguishing text bundles and native archives by manifest, scope and evidence provenance. [A] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| DATA-1 | Member differences establish drift or noncompliance. | `11_config_cluster_top.png`, `12_config_cluster_diff.png`; G. | Preserve “member difference”; reserve drift/alignment conclusions for comparison against proven expected intent. |
| DATA-2 | Every COMPLETED onboarding necessarily has filled inventory/configuration. | O permits unsupported skips but its acceptance paragraph promises filled inventory. | Define COMPLETED as all applicable steps succeeded, with unsupported planes explicitly disclosed. |
| DATA-3 | A single-context backup represents the entire ASA. | A, “Not yet.” | Explicit context scope; unsupported contexts remain uncovered until separately validated. |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | Approve independent timestamps, provenance and completeness for each plane inside the shared workspace? |
| 2 | Approve “Completed — configuration unsupported” rather than implying full data coverage? |
| 3 | Is single-context ASA coverage an acceptable initial product boundary? |

## 5. Security Reviewer

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Consent to consolidated navigation if every view uses the same authorized, masked projection. The merged screen must not expand access. [G] |
| 2 — Onboarding | Consent to identity gating, audited reads and backup opt-in outside onboarding. [O] |
| 3 — ASA | Prefer the read-only text scope for now, subject to resolving A’s authority conflict and validating protected secret-bearing storage. [A, G] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| SEC-1 | Masking is proven complete. | The reported Jobs leak was patched before this image; original leakage is UNKNOWN from `18_operations_jobs.png`. Serial-looking tokens also appear in `08_devices_cluster_identity.png`; their masking provenance is UNKNOWN. | Verify canonical pseudonyms across tables, reasons, detail views and exports; no raw values in evidence. |
| SEC-2 | Visible action buttons establish correct authorization. | `19_admin_device_registry.png`, `20_add_device_dialog.png`, `16_backups_bottom.png`. | Role tests and server-side enforcement; screenshot appearance alone cannot prove access control. |
| SEC-3 | Enabling SCP is merely part of a class-1 archive operation. | Cisco defines `ssh scopy enable` as a global configuration command. | Treat SCP as an externally provisioned prerequisite; refuse if absent. Do not introduce a configuration write through backup. [Cisco command reference](https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/so-st-commands.html). |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | Make masking verification a release blocker for the consolidated workspace? |
| 2 | Require acceptance evidence for denied credentials, trust failure and unauthorized retries? |
| 3 | Keep native archives disabled until their own FROZEN write contract and real-environment evidence exist? |

## 6. Business/Executive stakeholder

**Consent**

| Agenda | Position |
|---|---|
| 1 — UI | Keep Overview focused on estate coverage, recovery exposure and compliance evidence. Detailed software distributions can move to Devices: `02_overview_mid.png`, `03_overview_bottom.png`. |
| 2 — Onboarding | Conditional consent: completion must mean the promised supported evidence is available, with exclusions stated. [O] |
| 3 — ASA | Accept staged delivery only with an explicit account of what can and cannot be recovered. [A] |

**Dissent**

| ID | Claim refused | Evidence | What resolves it |
|---|---|---|---|
| EXEC-1 | The current Overview is a clear executive account. | Operational errors, framework scores and component distributions compete for attention: `01_overview.png`, `02_overview_mid.png`. | A bounded set of defined measures, timestamped scope and direct exception links. |
| EXEC-2 | Legacy devices being “treated as onboarded” proves evidence completeness. | O’s pre-V77 rule. | Keep workflow history separate from current evidence coverage. |
| EXEC-3 | Archive creation proves recoverability. | A specifies acquisition measurement, not recovery validation. | Defined recovery scope and a separately authorized recovery exercise; until then, “Recovery not tested.” |

**Questions for the Product Owner**

| Agenda | Decision required |
|---|---|
| 1 | Is Overview primarily a management briefing, with technical investigation delegated to detail screens? |
| 2 | Must reports distinguish completed workflow from complete evidence coverage? |
| 3 | What recovery requirement and accepted residual gap govern the ASA decision? |

## Council synthesis

### Supported consensus

Across these analytical lenses:

- One **Devices workspace** is supported; combining evidence models is not.
- Compliance and Backups deserve separate fleet screens using the same entity references.
- Onboarding should survive browser closure, disclose unsupported capabilities and lead directly to the device.
- Backup success, archive coverage and tested recoverability are separate facts.
- The evidence provided does not justify final onboarding delivery approval or ASA archive authorization.

### Unresolved dissent

| Holder | Unresolved position |
|---|---|
| Firewall operator vs Security Reviewer | The operator wants native archives in the first recovery-capable ASA release; Security supports text-only scope until a separate write contract and validation exist. |
| Manager and Executive | Temporary text-only coverage is acceptable only with an owner, accepted scope and a recovery alternative or deadline. These facts are UNKNOWN. |
| Designer vs Firewall operator | Designer favors a progress-first onboarding panel; operator wants successful intermediate evidence accessible. Recommendation: progress-first in release 1, without discarding collected evidence. This remains a product choice. |

These are positions within this single-author analysis, not independently elicited votes.

### Decisions the PO must take

1. Approve the unified Devices navigation and its preserved plane boundaries.
2. Approve the eight Overview definitions below and their thresholds.
3. Ratify onboarding clarifications, then accept delivery only against demonstrated outcomes.
4. Decide whether ASA’s first accepted scope is explicitly **configuration text only**, or must include validated native archive recovery coverage.
5. Resolve A’s DRAFT/implementation-authority contradiction before further boundary-changing implementation.

### Onboarding: required before delivery acceptance

| Required change or proof | Acceptance condition |
|---|---|
| Resolve completion semantics | Applicable reads succeed; unsupported reads show a recorded reason. Timeout, denied access or collection failure must not become “unsupported.” Identity cannot be skipped into success. |
| Complete the user journey | Manual add and discovery import select the registered entity in Devices; hierarchy expands where known. Unknown placement is stated, never inferred from a name. |
| Prove persistence and retry | Closing/reopening the browser preserves progress; retry re-admits only the stopped step without duplicate work. Service restart behavior is currently UNKNOWN. |
| Explain incomplete coverage | Unsupported planes and legacy devices without onboarding history remain distinguishable from observed complete evidence. |
| Correct the dialog | Resolve the contradictory credential affordance and align backup eligibility wording with the actual target policy. Evidence: `20_add_device_dialog.png`. |
| Demonstrate safe access | Identity/trust failure, unauthorized actions and masked error rendering pass acceptance checks. |
| Keep backup separate | Completion offers an off-by-default backup-target choice. It does not silently create a backup. |

In the combined workspace: registration opens a persistent progress panel; STOPPED shows the failed step, safe reason and permitted next action; COMPLETED reveals Summary and supported tabs. Unsupported tabs explain the limitation instead of presenting unexplained empty values.

### ASA recommendation and conditions

**Keep text backup as the current scope. Plan native archives as a separate optional capability if the recovery requirement demands them.** This recommendation does not certify the existing implementation: A still records real-environment measurement as pending.

| Option | Conditions |
|---|---|
| Configuration text | Label the exact files/context covered; protect configurations containing secrets; validate collection completeness and identity; state excluded recovery assets. Line counts and an `ASA Version` marker alone do not establish recovery readiness. |
| Native ASA archive | Own FROZEN per-vendor write contract, explicit target authorization, controlled credential use, before/after ledger, bounded execution and no automatic SCP configuration changes. |
| Archive transfer and cleanup | Verify trusted transport and durable encrypted receipt before normal deletion. Restrict cleanup to the exact artifact created by that attempt. Define interruption, transfer failure, cleanup failure and leftover-file handling; no wildcard deletion or blind recreation retries. |
| Recovery claim | Verify included assets and context scope, then conduct a separately authorized recovery exercise before claiming recoverability. FXOS/chassis recovery coverage remains UNKNOWN. |

Cisco’s ASA 9.22 guide documents certificate/VPN/image content, certificate passphrase handling, per-context backup requirements, at least 300 MB free space and one backup/restore at a time. These requirements need release-specific validation; the proposed bare command is not a complete automation contract. [Cisco ASA 9.22 backup guide](https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/general/asa-922-general-config/admin-swconfig.html).

## Agenda 1 deliverables

### Verdicts on the PO’s questions

| Question | Verdict |
|---|---|
| Is the UI effective? | **Partly.** It exposes useful evidence but makes users reconcile too many status labels and destinations. Examples: `09_devices_gateway.png`, `18_operations_jobs.png`. |
| Is everything in the right place? | **No.** The same selected entity has separate inventory/configuration navigation, and backup policy summaries sit below a large table. `05_devices_cluster_interfaces.png`, `11_config_cluster_top.png`, `16_backups_bottom.png`. |
| Is data duplicated? | **Presentation is duplicated.** The trees and member identity tables repeat. Backend storage duplication is UNKNOWN. `08_devices_cluster_identity.png`, `10_config_list.png`, `11_config_cluster_top.png`. |
| Can inventory and configuration share one screen? | **Yes.** Share entity selection, header and navigation; retain separate data ownership, timestamps and provenance. This preserves G’s product-plane law. |
| Is it clean and readable? | **Visually consistent, but too dense in important places.** Long chip rows, narrow identity columns and a large inline difference index impede scanning. `08_devices_cluster_identity.png`, `11_config_cluster_top.png`, `12_config_cluster_diff.png`. |
| Are the critical facts right? | **Some are useful; several are ambiguous.** Job/check/device units need care, “Live” is unexplained, and heterogeneous members receive a single cluster model label. `01_overview.png`, `05_devices_cluster_interfaces.png`, `07_devices_cluster_members.png`. |

### Proposed information architecture

| Navigation | Owns | Remove or merge |
|---|---|---|
| **Overview** | Eight defined measures, evidence timestamp and prioritized exception links. | Move raw error lists and software/hardware distributions into their task screens. |
| **Devices** | One searchable entity browser; enrollment progress; device/cluster summary; Inventory; Configuration; evidence provenance. | Merge Configuration navigation and its second tree. Preserve old links by routing to the appropriate tab. |
| **Compliance** | Fleet controls, framework mappings, evidence gaps and device/control drill-down. | Reuse device links; no separate identity authority. |
| **Backups** | Targets, schedules, retention, archives, coverage, comparison and recovery evidence. | Keep its fleet table: it serves a different task. |
| **Operations** | Jobs, queue, history, collection schedules and HA-readiness assessments. | Friendly job types and safe error summaries before technical details. |
| **Administration** | Credentials, access, integrations, system settings and audit records. | Move routine device registration/lifecycle actions into authorized Devices controls; retire the third everyday device browser. |

Multiple fleet tables are not inherently duplication. Requiring users to re-find the same device to change evidence planes is the duplication to remove first.

### Combined device screen: top to bottom

1. **Shared entity browser.** Search plus vendor, lifecycle and evidence filters. Preserve manager → domain → cluster → member relationships where observed. Cluster and member remain distinct entities.

2. **Compact header.** Masked name, entity type, vendor and breadcrumb. Show model/version only when meaningful; use “Mixed models” for heterogeneous clusters. Put opaque IDs and detailed provenance behind “Identity details.”

3. **Status strip.** Enrollment/onboarding state, identity verification, inventory age and configuration age. Each status has its own meaning. A cluster shows per-member observations where they differ.

4. **Summary, the default tab.** A short operational summary; member HA roles and observation times; collection exceptions; device compliance status and evidence coverage; latest backup type/age and schedule state. Compliance and backup summaries link directly to filtered fleet views.

5. **Inventory tab.** Interfaces, Routing, Members/virtual systems and Identity & provenance. These are observed runtime facts, with source and age.

6. **Configuration tab.** Sanitized sections and configuration history. For clusters, **Member comparison** lives here: differences-only filter, compact section index and side-by-side values. Separate observed differences, expected member-specific values and unavailable evidence.

7. **Activity tab.** This entity’s collection/onboarding jobs and safe reasons, linking to Operations for full investigation.

Use one primary action appropriate to context—such as **Collect evidence**—with permission-aware secondary actions. No new all-in-one backend entity model is required to consolidate the navigation.

### Overview: at most eight key facts

Every measure must expose its scope, denominator and timestamp, and open the corresponding filtered list.

| Fact | Definition |
|---|---|
| **1. Estate coverage** | Registered devices split into enrolled, onboarding and stopped/draft; operational cluster count shown separately. Do not call enrollment “Live.” |
| **2. Inventory freshness** | Enrolled devices with successful direct inventory within the agreed window / enrolled devices expected to support that read. Show unsupported and never-read separately. |
| **3. Collection failures, 24 h** | Failed collection jobs in the window, plus distinct affected devices. Job count is not device count. |
| **4. Backup coverage gaps** | Enabled backup targets lacking an archive that meets the target’s age, validation and required-content policy. An archive merely existing does not prove recoverability. |
| **5. Configuration changes** | Devices with a semantic change between comparable configuration observations in a stated period. First observations and noncomparable evidence are separate. |
| **6. Cluster member differences** | Comparable clusters with observed differences after contract-approved member-specific exclusions. Label as differences, not drift or unhealthy clusters. |
| **7. Critical failed checks** | Distinct applicable critical control × device evaluations that failed; show affected-device count alongside. Do not add duplicate framework mappings. |
| **8. Compliance and evidence coverage** | Pass / applicable checks, paired with evidence-present / applicable checks. Show unavailable checks separately from failures; assessed scope must be explicit. |

Software versions, appliance models and policy-install age remain available in Devices. They need not compete with exceptions on Overview.

### Concrete screenshot defects

Severity here reflects review priority, not a verified backend fault.

| Priority | Screenshot file and element | Why it needs attention |
|---|---|---|
| P1 | `01_overview.png` — “Why jobs failed” | Raw implementation strings and malformed-looking digest text dominate a management view. Show safe cause categories first. |
| P1 | `01_overview.png`, `15_backups.png`, `19_admin_device_registry.png` — backup totals | Overview/admin reference 110 targets while Backups shows 108; missing archives also differ. Snapshot timing or scope may explain it, but the UI does not. Cause: UNKNOWN. |
| P2 | `02_overview_mid.png` — Policy installed | Install-age buckets do not establish expected policy or deployment compliance. Keep this distinction explicit. |
| P2 | `02_overview_mid.png`, `03_overview_bottom.png` — software/hardware charts | Five distributions and legends consume substantial Overview space; better suited to fleet inventory analysis. |
| P2 | `03_overview_bottom.png` — Cluster member DIFF | Setting counts lack a compact distinction between observed difference, expected member specificity and missing comparison evidence. |
| P1 | `05_devices_cluster_interfaces.png`, `07_devices_cluster_members.png` — cluster model header | One model labels a cluster whose members show different models. The header can misrepresent the operational unit. |
| P2 | `06_devices_cluster_routing.png` — “Shared” | The table gives no visible per-member evidence age/completeness. Matching displayed routes must not imply current HA readiness. |
| P2 | `07_devices_cluster_members.png` — Device ID column | Full UUIDs occupy prime operator space; place them in details or a copy action. |
| P2 | `08_devices_cluster_identity.png` — identity table | Narrow columns wrap platform facts while the table requires horizontal scrolling. Serial token masking provenance is UNKNOWN. |
| P1 | `09_devices_gateway.png` — state badges | “Standalone,” “Live,” “Identity verified” and “UNKNOWN” coexist without a clear explanation of the unknown dimension. |
| P2 | `10_config_list.png` — second tree and empty selection | Users must locate an entity again to inspect another evidence plane. |
| P2 | `11_config_cluster_top.png` — repeated members table | Repeats identity evidence before the configuration task; comparison content starts below substantial header material. |
| P2 | `12_config_cluster_diff.png` — difference index | A long stream of inline links is difficult to scan; use a compact section index with counts. |
| P1 | `13_compliance.png` — “Assured compliance” | The label can sound like audit assurance although the displayed metric is a pass fraction including evidence gaps in its denominator. |
| P2 | `14_compliance_controls.png` — control rows | Internal identifiers and many framework chips compete with title, affected devices and result. Collapse mapping detail. |
| P2 | `15_backups.png` — action column | Six or seven actions repeat per row; retain a primary action and move secondary operations into a menu. Explain “V1” and “FIRST.” |
| P1 | `16_backups_bottom.png` — Management Center archive row | “Not enrolled” plus a Delete action is ambiguous beside the enrolled registry entry in `19_admin_device_registry.png`. It may be historical/orphaned evidence; relationship and deletion target are UNKNOWN. |
| P2 | `16_backups_bottom.png` — schedule/retention cards | Operationally important planning facts are below the fleet table. Put the policy summary above it. |
| P2 | `17_operations_ha.png` — readiness | “NOT EVALUATED” is appropriately cautious, but read-only assessment needs a clearer action and prerequisite path. Active/standby roles alone do not establish readiness. |
| P1 | `18_operations_jobs.png` — target labels | This supplied image contains replacement `FW-MASKED-*` labels. It cannot prove the reported original leak or canonical pseudonym consistency. |
| P2 | `19_admin_device_registry.png` — third device browser | Enrollment administration introduces another place to locate the same entity. Move lifecycle controls into Devices with role gating. |
| P1 | `20_add_device_dialog.png` — credentials and backup wording | “No permission” appears beside “Create credential”; backup text refers to a pilot allowlist. Reconcile permitted actions and current policy. Post-submit behavior is UNKNOWN. |

Keyboard navigation, screen-reader behavior, measured contrast, export masking and actual button authorization are **UNKNOWN** from screenshots.

### Phased plan — first release small

| Phase | Deliver | Exit evidence |
|---|---|---|
| **1. One device journey** | Reuse the existing browser/header; place current Inventory and Configuration content under one selected entity. Redirect old Configuration links. Open new devices with onboarding progress. Correct permission wording and ambiguous state labels; resolve reported masking gaps. | AIView demonstrations: find existing device, add one, import a batch, close/reopen, inspect stopped/retry and unsupported states, switch planes without losing selection. Targeted checks plus required render, regression and privacy gates. |
| **2. Clearer summaries** | Introduce the eight Overview facts, reconcile scope definitions, compact cluster headers, simplify comparison navigation and backup row actions. | Every measure opens a matching filtered population; counts, timestamps and missing evidence are explainable. |
| **3. Recovery coverage** | Resolve ASA authority and scope. Add native archives only if selected, under a separate FROZEN write contract. | Real-environment acquisition, interrupted-transfer/cleanup evidence and separately authorized recovery validation. |

**SESSION CLOSE — review delivered; no implementation or delivery certification.** Source, configuration, project state, Git, deployments, devices and production data were unchanged. No tests were run and no handover file was written in this read-only session. Next: PO decisions above, followed by a bounded UI contract amendment; ASA write-boundary work remains separate.