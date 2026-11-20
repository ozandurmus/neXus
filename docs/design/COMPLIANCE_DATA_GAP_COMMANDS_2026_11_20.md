# Compliance data gaps: the read literals that would close them

## Status

DRAFT, DO NOT IMPLEMENT. A catalogue of findings for the Product Owner (PO) session. No literal in this document is
approved; every literal marked NEW needs per-literal PO approval before any gate row, migration or code is written.
Nothing in this lane adds a gate row, a command, a frequency or a command context, and the first lane changed no code (section 7); the wiring done afterwards is recorded in section 0.
Method: read the compliance catalogues, the four evaluators, the collection read plans and the gate registry (fixture and
migrations V1 to V253). No device, database or HOST-A was reached, so the fleet counts are formulas, not measurements
(section 1.3).

## 0. Wiring status (lane compliance-wiring-1120)

Implemented on branch `lane/compliance-wiring-1120`; no gate row, command, frequency or context was added. Catalog
versions are bumped (`CheckPointComplianceCatalog` and `PaloAltoComplianceCatalog`; the PAN version is now part of the
fingerprint the service compares), so every stored evaluation is re-run.

| Item | Status |
|---|---|
| 2. Check Point `add allowed-client` and `add snmp usm user` | CLOSED in code. The sanitizer keeps exactly those two `add` forms; SNMP user lines are whitelist-redacted to user name, security level and protocol names (pass-phrases and keys are dropped, not filtered by spelling). `gaia_cis_2_5_1_mgmt_trusted_subnets` and `gaia_cis_2_6_1_snmp_v3_only` are decided from them. A device whose stored text predates this change stays UNKNOWN until its next configuration run (the kept lines are not in the old text). Line spellings are still to be confirmed on a lab unit |
| 5. Palo Alto SNMPv3, unused interfaces, DDNS | CLOSED in code for `pan_cis_4_1_1`, `4_1_2`, `4_1_3`, from the ACTIVE XML. The sanitizer also masks `authpwd`, `privpwd` and `snmp-community-string`, which it did not before. Definitions used: see 4.1 |
| 4. Cisco ASA failover and mode reads | NOT DONE: not a wiring gap. See 6.1 |
| 6. Check Point unused interfaces, 9 and 10 ciphers, Palo Alto active-vs-effective | not touched, as instructed |

## 1. What a data gap is in code

The Compliance screen's "Data Gaps" figure is the count of control checks whose display status is not PASS, FAIL or
NOT_APPLICABLE (`ComplianceService.checkOutcome`: anything else is UNAVAILABLE; `data_gaps` is that count). Only
firewalls with a stored configuration run count; a management server (Panorama, MDS) is excluded. A check becomes
DATA_UNAVAILABLE on exactly these paths:

| Path | Where | Vendors | Closed by a read literal? |
|---|---|---|---|
| P1. Sentinel gate id: the binding's gate id starts with `CP-GATE-` or `PAN-GATE-`, so the evaluator returns DATA_UNAVAILABLE before it looks at any configuration | `CheckPointGaiaComplianceEvaluator` step 1, `PaloAltoPanOsComplianceEvaluator` step 1 | Check Point (4 controls), Palo Alto (4 controls) | Mostly no: see sections 3 and 4 |
| P2. Verdict UNKNOWN: the collected text is incomplete, a value is withheld, or the setting is absent and its default cannot be told from "not collected" | `FortinetAsaComplianceEvaluator` | Fortinet (7 controls), Cisco ASA (9 controls) | Partly: sections 5 and 6 |
| P3. No configuration text for the device (never collected, or collection failed) | step 2 of the Check Point and Palo Alto evaluators | all | No: a collection problem, not a literal |
| P4. Unsupported vendor (no catalogue) | `Ui2ComplianceServer` answers 400 | Blue Coat, Radware, Pulse, Infoblox, FortiManager, Spark and others | Out of scope here: no catalogue exists, so no check exists |

Check Point and Palo Alto controls that are not sentinel-gated never produce a gap when configuration text exists: an
absent setting is a FAIL ("not configured"). So for those two vendors the gaps are exactly the eight sentinel controls
below (times the devices) plus any device on P3.

Catalogue sizes (from the catalogues): Check Point 24 controls, Palo Alto 23, Fortinet 7, Cisco ASA 9.

### 1.1 The approved reads that already feed compliance

| Vendor | Compliance input | Approved gate row(s) (SIGNED_OFF) | Literal |
|---|---|---|---|
| Check Point | sanitized text of the Gaia configuration (only lines starting `set ` survive: `CheckPointGaiaConfigParser.extractCanonicalSetLines`) | `cp_configuration_show_configuration` (expert, `cp_gaia_gateway`) | `clish -c 'show configuration'` |
| Palo Alto | sanitized XML of the ACTIVE read (`PAN_ACTIVE_FORM_PARAMS`); effective-running and merged are stored without text | `pan_configuration_active` (`pan_firewall`) | `type=config&action=show&xpath=/config` |
| Fortinet | sanitized text of the top-level `show`, with three sections replaced by their `show full-configuration` form | `fgt_show_configuration`, `fgt_show_full_configuration_system_global`, `..._system_interface`, `..._system_ha` (`fortigate`) | `show`; `show full-configuration system global`; `... system interface`; `... system ha` |
| Cisco ASA | sanitized running configuration | `asa_more_system_running_config` (`cisco_asa_firewall`, privileged_exec) | `more system:running-config` |

### 1.2 Wiring gap versus approval gap

- Wiring gap: an approved gate row already returns the evidence, or could return it with a parse-scope change that is not
  a command addition (the same reasoning as `docs/design/CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md` section 3). It can be
  fixed without a PO literal approval. It may still need a design decision (what the assertion means).
- Approval gap: no approved row returns the evidence. A literal must be approved first.

### 1.3 How many checks each item closes

Checks closed = (controls closed by the item) x (evaluated devices of that vendor that the item applies to). This lane
cannot read the fleet, so it gives the controls-per-device figure and the formula. The PO session can produce the
measured per-control figures with one read-only statement (a single-level expansion of a small array, not a recursive
walk), run in a read-only transaction with a statement timeout:

```sql
SELECT e.result->>'vendor' AS vendor, i->>'controlId' AS control_id, count(*) AS checks
FROM compliance_evaluation e, jsonb_array_elements(e.result->'items') i
WHERE i->>'displayStatus' = 'DATA_UNAVAILABLE'
GROUP BY 1, 2 ORDER BY 3 DESC;
```

Recorded figure for context: `project/feature_registry.json` (`java_checks`) states 102 firewalls evaluated and 428 data
gaps; the screen now shows about 496. Four sentinel controls per Check Point firewall and four per Palo Alto firewall are
the bulk of any such figure; the Fortinet and ASA UNKNOWN paths add the rest.

## 2. Ranking (controls closed per device, then severity)

N_cp, N_pan, N_fgt, N_asa are the evaluated device counts per vendor (`compliance_evaluation.result->>'vendor'`).
Cross-vendor order is by controls per device because the N values are not measured here.

| # | Item | Vendor | Controls closed per device | Checks closed | Already approved? | Needs |
|---|---|---|---|---|---|---|
| 1 | `show running-config all logging` | Cisco ASA | 3 (`asa_logging_enabled`, `asa_log_timestamps`, `asa_remote_syslog`) | 3 x N_asa | no | NEW literal, PO approval |
| 2 | Keep `add allowed-client` and `add snmp usm user` lines from the approved `show configuration` | Check Point | 2 (`gaia_cis_2_5_1_mgmt_trusted_subnets`, `gaia_cis_2_6_1_snmp_v3_only`) | 2 x N_cp | yes, `cp_configuration_show_configuration` | wiring (sanitizer scope plus two evaluator rules) |
| 3 | `show running-config all http` | Cisco ASA | 2 (`asa_http_sources_restricted`, `asa_http_aaa`) | 2 x N_asa | no | NEW literal, PO approval |
| 4 | Use the approved failover and mode reads in the ASA evaluator | Cisco ASA | 1 (`asa_failover_link`) | 1 x N_asa in failover | yes, `asa_failover_show_failover`, `asa_show_mode` | wiring (evaluator takes a second input) |
| 5 | Rules over the ACTIVE XML for SNMPv3 mode, link state, DDNS | Palo Alto | 3 (`pan_cis_4_1_1`, `pan_cis_4_1_2`, `pan_cis_4_1_3`) | 3 x N_pan | yes, `pan_configuration_active` | wiring (new assertion rules; semantics decided first) |
| 6 | Rule over the config's interface lines for unused interfaces | Check Point | 1 (`gaia_cis_2_7_1_unused_interfaces_disabled`) | 1 x N_cp | yes, `cp_configuration_show_configuration` | wiring (definition of "unused", then a rule) |
| 7 | `show full-configuration system ntp` | Fortinet | 1 (`fg_ntp_configured`) | 1 x N_fgt | no | NEW literal, PO approval |
| 8 | `show full-configuration log syslogd setting` and `show full-configuration log fortianalyzer setting` | Fortinet | 1 (`fg_remote_logging`) | 1 x N_fgt | no | NEW literals (two), PO approval |
| 9 | SSH cipher evidence | Check Point | 1 (`gaia_cis_2_5_4_ssh_strong_ciphers`) | 1 x N_cp | no, unless the lab shows it in `show configuration` | NEW literal (candidate), PO approval |
| 10 | SSH cipher evidence | Palo Alto | 1 (`pan_cis_4_1_4_ssh_strong_ciphers`) | partial: only devices with an explicit cipher block | yes (ACTIVE read) | wiring plus a frozen per-version default set; no new literal proposed |

Items 2, 4, 5 and 6 are wiring gaps: 7 controls per device on Check Point, Palo Alto and ASA that need no literal
approval. Items 1, 3, 7, 8, 9 are approval gaps: 8 NEW literals in total (one of them a candidate whose need depends on a lab read).

## 3. Check Point (4 sentinel controls, 4 x N_cp checks)

All four carry a `CP-GATE-*` id that is not a gate row; it is a placeholder the evaluator treats as "command pending".
No row with these ids exists in `gate_registry_fixture.yaml` or any migration.

### 3.1 `gaia_cis_2_5_1_mgmt_trusted_subnets` (CRITICAL), `CP-GATE-CLIENTS-01`

- Evidence needed: the allowed management clients (`allowed-client` entries).
- Literal: none new. The approved `clish -c 'show configuration'` (clish context, `cp_gaia_gateway`,
  `cp_configuration_show_configuration`) is expected to contain them as `add allowed-client ...` lines.
- Approved gate row exists: yes.
- Why unused: `CheckPointGaiaConfigParser.extractCanonicalSetLines` keeps only lines starting with `set `. An `add ...`
  line is read from the device and then dropped before the text is stored, so the evaluator can never see it. A
  second cause is the sentinel id itself, which short-circuits the control.
- Wiring needed: keep `add allowed-client` lines in the sanitized text, and replace the sentinel with a real rule
  (relationship verdict, not the address list: sensitive-identity reporting law). Changing which lines are kept changes
  the canonical hash for every Check Point device and the cached evaluation fingerprint, so it needs its own lane and a
  bump of `CATALOG_VERSION`.
- UNKNOWN to confirm on a lab unit: the exact Gaia spelling of these lines. No fixture or test in this repository
  contains an `allowed-client` line, so the form is not proven here.

### 3.2 `gaia_cis_2_6_1_snmp_v3_only` (HIGH), `CP-GATE-SNMP3-01`

- Evidence needed: SNMP agent version and the v3 users with authentication and privacy settings.
- Literal: none new, same approved `show configuration`. Users are expected as `add snmp usm user ...` lines, which the
  sanitizer drops for the same reason as 3.1. The agent version line (`set snmp agent-version ...`) is a `set` line and
  is already kept.
- Approved gate row exists: yes. Wiring as in 3.1. The secret filter (`SECRET_LINE_RE`) must be checked against the
  user lines before they are kept (pass-phrase fields).
- UNKNOWN to confirm on a lab unit: line spelling and whether the hashed pass-phrase fields are matched by the filter.

### 3.3 `gaia_cis_2_7_1_unused_interfaces_disabled` (MEDIUM), `CP-GATE-IFACE-01`

- Evidence needed: which interfaces are unused, and their administrative state.
- Literal: none new. `set interface <name> state on|off` and the address lines are `set` lines, already in the
  sanitized text (the parser files them under "Interfaces").
- Approved gate row exists: yes (`cp_configuration_show_configuration`). The inventory reads
  (`cp_inventory_ip_addr_show_v4` and its VSX form) also exist but need no use here.
- Why unused: the control is a sentinel and "unused" has no definition in any contract. The gap is semantic, not a read.
- Needed: a definition (for example: no IPv4 address configured and not a bond or bridge member), then an evaluator rule.
  This is a design decision, so it is not a pure wiring fix.

### 3.4 `gaia_cis_2_5_4_ssh_strong_ciphers` (HIGH), `CP-GATE-CIPHERS-01`

- Evidence needed: the cipher list the SSH server accepts.
- Approved row: none. Whether the Gaia `show configuration` carries a cipher line is UNKNOWN (the parser knows a
  `set ssh server ...` family, but no cipher line is shown anywhere in this repository).
- Candidate literal (NEW, needs PO approval, form not proven): a read-only SFTP open of `/etc/ssh/sshd_config`, with the
  precedent of `cp_syslog_collect_read` (read-only SFTP open, no shell). Per the PO direction recorded in
  `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`, a `bash -lc` form is not proposed.
  Gate-row shape if approved: vendor `check_point`, scope `cp_gaia_gateway`, transport SFTP, class read, once per device
  per configuration collection, secret output risk none (the file holds no key material, but host-key paths appear:
  parse only the `Ciphers`, `MACs` and `KexAlgorithms` lines).
- First step that needs no approval: a PO-run lab check of whether `clish -c 'show configuration'` already shows the
  cipher setting; if it does, this becomes a wiring item and needs no literal.

## 4. Palo Alto (4 sentinel controls, 4 x N_pan checks)

Palo Alto reads are XML API requests, not CLI literals. The ACTIVE read is the approved evidence source:
`type=config&action=show&xpath=/config` (`pan_configuration_active`, scope `pan_firewall`, shell context
`not_applicable`). The evaluator reads its sanitized text with regular expressions (`extractFromXml`). The four
sentinels are placeholders from the review that first proposed them
(`docs/design/CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md` section 3: "three of the four gates are probably unnecessary").

| Control | Severity | Evidence in the ACTIVE read | Approved row | Status |
|---|---|---|---|---|
| `pan_cis_4_1_1_snmpv3_only` (`PAN-GATE-SNMP3-01`) | HIGH | SNMP setting block of the device configuration (version and v3 users) | `pan_configuration_active` | wiring gap, no approval |
| `pan_cis_4_1_2_unused_interfaces_down` (`PAN-GATE-IFACE-01`) | MEDIUM | interface entries and their link-state | `pan_configuration_active` | wiring gap plus a definition of "unused" |
| `pan_cis_4_1_3_ddns_disabled` (`PAN-GATE-DDNS-01`) | LOW | DDNS settings under the interface entries | `pan_configuration_active` | wiring gap, no approval |
| `pan_cis_4_1_4_ssh_strong_ciphers` (`PAN-GATE-CIPHERS-01`) | HIGH | an explicit cipher list under the SSH settings, when one is configured | `pan_configuration_active` | partial: absence of a block means platform defaults, which vary by version, so absent stays UNKNOWN until a frozen per-version default set exists |

Why unused: each control's placeholder id starts with `PAN-GATE-`, so the evaluator returns before it looks at the XML.
No literal is proposed. Two cautions before wiring, both to be confirmed against a synthetic PAN-OS export:

- Redaction: `sanitizePaloAltoXml` masks `phash`, `admin-password`, `pre-shared-key`, `private-key`, `community`,
  `passphrase`, `bind-password`, `secret`, `shared-secret`, `auth-key` and `priv-key`. SNMPv3 user entries may carry other
  element names for their authentication and privacy values; check them before a rule reads that block (the stored
  sanitized text is what the Configuration screen shows).
- Source of truth: compliance reads ACTIVE (device-local), not effective-running. A setting pushed by a Panorama template
  appears in effective-running only. This produces false FAILs on template-managed devices, not gaps, and is a separate
  open item in the review (section 4).

### 4.1 Rules now wired (Palo Alto)

- `pan_cis_4_1_1_snmpv3_only`: no `snmp-setting` block -> UNKNOWN (an absent block cannot be told from template-managed SNMP,
  the open active-vs-effective item); a `v2c` version -> FAIL; `v3` with every user having `authpwd` and `privpwd` -> PASS;
  a user without one -> FAIL; `v3` with no user -> UNKNOWN.
- `pan_cis_4_1_2_unused_interfaces_down`: an ethernet interface entry with no role child (layer3, layer2, virtual-wire, tap,
  ha, aggregate-group, decrypt-mirror, log-card) is "unused". All such entries `link-state` down -> PASS; any not down -> FAIL;
  none present -> UNKNOWN (an unconfigured port has no entry, so it is not visible in a configuration). This definition is
  a design choice made in this lane and should be confirmed by the PO session.
- `pan_cis_4_1_3_ddns_disabled`: any `ddns-enabled` set to `yes` -> FAIL; interface configuration present and none enabled ->
  PASS; no interface configuration -> UNKNOWN.
- `pan_cis_4_1_4_ssh_strong_ciphers` is unchanged (still the sentinel).

## 5. Fortinet (7 controls, UNKNOWN path P2)

The three full-configuration sections the collector already reads (`system global`, `system interface`, `system ha`) are
wired: `fg_ssh_v1_disabled`, `fg_global_telnet_disabled`, `fg_admin_tls_minimum`, `fg_admin_no_cleartext` and
`fg_ha_heartbeat_defined` can resolve to PASS or FAIL from them. These are not gaps by literal.

The gaps by literal are the two controls whose section is read only through the top-level `show`, which prints
non-default settings (inference from the evaluator's own handling of absent values, to confirm on a lab unit):

### 5.1 `fg_ntp_configured` (MEDIUM)

- The rule needs `ntpsync` (default `enable`, so omitted by `show`) and a `type` or a `config ntpserver` child. With a
  default `system ntp` block `show` prints nothing, so the evaluator returns UNKNOWN.
- Literal (NEW, needs PO approval): `show full-configuration system ntp`.
  Gate-row shape, copied from V98: vendor `fortinet`, scope `fortigate`, context `top_or_global`, transport SSH_EXEC,
  class read, timeout 600 s, frequency "once per configuration/backup collection", same SSH session as `show`.
  Collector change when approved: add `system ntp` to the section list in `ConfigurationCapabilityExecutor.collectFortiGate`
  (`List.of("system global", "system interface", "system ha")`).
- Approved row exists: no. Closes 1 control per FortiGate.

### 5.2 `fg_remote_logging` (HIGH)

- The rule looks at `log syslogd setting` and `log fortianalyzer setting`. `status` defaults to `disable`, so a device with
  no remote logging prints no block in `show`, which the evaluator returns as UNKNOWN (a device with logging on shows
  `status enable` and `server`, which passes). The full form would turn "no logging" from a gap into a FAIL.
- Literals (NEW, two, need PO approval): `show full-configuration log syslogd setting` and
  `show full-configuration log fortianalyzer setting`, same gate-row shape as 5.1.
- Approved row exists: no. Closes 1 control per FortiGate. A separate, non-literal cause: a `server` value containing
  `[withheld]` is also UNKNOWN; that is the sanitizer's secret filter and not a read.

Multi-VDOM devices: a `system interface` or logging block seen in more than one scope returns UNKNOWN by design
(`fg_admin_no_cleartext`, `fg_remote_logging`); no literal changes that.

## 6. Cisco ASA (9 controls, UNKNOWN path P2)

The approved read is `more system:running-config`. A running configuration omits default-valued commands, and the
evaluator accepts only an explicit positive or negative line (`explicit()`), so an absent line is UNKNOWN. The
following reasoning is inferred from the evaluator and its tests and is to be confirmed on a lab unit.

| Control | Cause of UNKNOWN | Literal | Approved row | Status |
|---|---|---|---|---|
| `asa_logging_enabled`, `asa_log_timestamps` | neither `logging enable` / `no logging enable` nor `logging timestamp` / `no logging timestamp` appears | `show running-config all logging` | no | NEW, approval |
| `asa_remote_syslog` | depends on `asa_logging_enabled` being PASS; UNKNOWN otherwise | same literal as above | no | closed together with the two above |
| `asa_http_sources_restricted`, `asa_http_aaa` | no `http server enable` / `no http server enable` line to anchor on | `show running-config all http` | no | NEW, approval |
| `asa_failover_link` | with failover on, the evaluator always returns UNKNOWN: "a single-context projection cannot prove system context and independent peer observation" | none: `show failover`, `show failover state`, `show mode` | yes: `asa_failover_show_failover`, `asa_failover_show_failover_state`, `asa_show_mode` (all SIGNED_OFF, class read) | wiring gap: the evaluator takes only configuration text, so these approved reads are never given to it |
| `asa_ssh_aaa`, `asa_telnet_absent`, `asa_ntp_server` | already resolve to PASS or FAIL from the running configuration; UNKNOWN only on masked (`[withheld]`) lines | none | n/a | sanitizer, not a read |

### 6.1 Why the ASA failover wiring was not done

The collected reads do not reach compliance and cannot without a new frequency or context:

- The configuration collection session (`CiscoAsaExecutor.configuration`) runs only `show mode` and
  `more system:running-config`; it refuses a multiple-context device, so every evaluated ASA is single-context and
  `show mode` adds nothing to the evaluator.
- `show failover` (`asa_failover_show_failover`) is run only by the failover readiness job, twice per run per member; its
  gate row says the output is "reduced in memory to derived values, never stored". The inventory reads only
  `show failover | include This host` (a role word, `InventoryHaFact`). Nothing the compliance service can read holds the
  full output.
- Feeding it to the evaluator therefore needs either a new `show failover` read inside the configuration session (a new
  frequency and context for an approved literal) or a stored derived value plus a second input on the compliance request.
  Both are beyond a pure wiring change; this needs a PO decision. `asa_failover_link` stays UNKNOWN when failover is on.

Gate-row shape for the two NEW ASA literals if approved: vendor `cisco_asa`, scope `cisco_asa_firewall`, context
`privileged_exec`, transport SSH_EXEC, class read, bounded timeout, frequency once per configuration collection, same
session as `more system:running-config`; secret output risk: the filtered output of `all logging` and `all http` carries
server addresses and an enable flag only (to confirm; `logging` lines can carry a key in some forms, so the existing
secret filter applies).

## 7. Why the first lane changed no code

The brief permits code only for a pure wiring gap whose gate row already exists. None of the wiring items qualifies as
pure:

- Items 2 and 3.3 to 3.4 change which lines the Check Point sanitizer keeps (the canonical hash and every cached
  evaluation change) and need a definition or secret-filter decision.
- Item 5 (Palo Alto) needs assertion semantics and a redaction check first (section 4).
- Item 4 (ASA failover) changes the evaluator's input contract (a second read result per device).

Each is a separate lane with its own tests and a `CATALOG_VERSION` bump (stored evaluations are cached by the rule-set
fingerprint). Proposed order for the PO session: first measure the real per-control counts (section 1.3), then the
Check Point `add` lines (2 controls, critical and high), then Palo Alto 4.1.1 to 4.1.3, then put the NEW literals
(Cisco ASA logging and http, Fortinet ntp and logging, optional Check Point ciphers) to the PO one by one.

## 8. Cross-references

- Catalogues: `ui2/worker/.../compliance/catalog/{CheckPoint,PaloAlto,FortinetAsa}ComplianceCatalog.java`.
- Evaluators: `ui2/worker/.../compliance/evaluator/`.
- Counting: `ui2/service/.../compliance/ComplianceService.java` (`checkOutcome`, `data_gaps`).
- Collection plans: `ConfigurationCapabilityExecutor`, `ConfigurationReadPlan`, `CheckPointGaiaConfigParser`,
  `FortiGateConfigurationAllowlist`.
- Gate rows: `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`,
  `ui2/service/src/main/resources/db/migration/` (V80, V85, V98, V78, V210, V212).
- Review that first called the Palo Alto sentinels unnecessary: `docs/design/CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md`.
- Shell-form direction for any Check Point literal: `docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md`.
