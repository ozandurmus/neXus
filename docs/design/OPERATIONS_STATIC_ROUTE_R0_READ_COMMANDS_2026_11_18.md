# Operations static route -- phase R0 read commands (literal approval list)

## Status

**APPROVED -- PO 2026-11-18 (chat, verbatim: "go komutlar ok").** The PO was shown every `R0-*` literal below by id, literal, reuse/NEW and
source, and approved the list as shown for phase `R0` of `OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md` (sections 1.3, 3, 4.1 and
the path-confirmation appendix). Scope of the approval: `CLASS_0` reads only, used by the R0 read/pre-check/preview flow and nothing else; the
widened frequency/caller wording on the reused gates; the NEW literals R0-CP-1, R0-CP-2, R0-PAN-1, R0-PAN-3, R0-ASA-3, R0-ASA-4, R0-ASA-6 each
as its own gate row. Not approved: R0-CP-5 (no literal proposed, stays `UNKNOWN`), any write, and the static-route write carve-out
(`PO_DECISION_RECORD_2026_11_18_STATIC_ROUTE_CONFIGURATION_WRITES.md` stays PROPOSED). Literals whose syntax is `UNKNOWN` per estate version
(R0-PAN-3, R0-ASA-4, R0-ASA-6) are parsed fail-closed: an unrecognised answer is `UNKNOWN`, never a pass, and the first real answer per version
is recorded (masked shape only) before the preview relies on it. FortiGate is out of scope (FortiManager-managed). No device or host was
contacted while writing this document.

## 1. Common gate fields (`docs/AI_DEVELOPMENT_PROTOCOL.md` fields 1-10)

Unless a row says otherwise: class 0 read; retry `none`; frequency "once per member per approved preview or job" (a read the registry already
signs off keeps its own wording and needs the stated widening); session reuse "one session or API key per member per job"; secret-output risk
`none` for the parsed projection, raw output discarded in memory (raw-evidence law); no `bash -lc` anywhere. Gate role scopes:
`cp_gaia_gateway`, `pan_firewall`, `cisco_asa_firewall` (`gate_registry_fixture.yaml`).

"Reuse" means the same command, session and timeout is already SIGNED_OFF. A parse-scope extension is not a new command; a wider
frequency/caller statement is a gate-text change the PO still confirms. "NEW" needs a new gate row after approval.

## 2. Check Point Gaia (non-VSX), Gaia Clish and Expert over `SSH_EXEC`

Doc base: Check Point Gaia Administration Guide, "Configuring IPv4 Static Routes in Gaia Clish"
(https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_Gaia_AdminGuide/Content/Topics-GAG/IPv4-Static-Routes-Gaia-Clish.htm, fetched).

### R0-CP-1 -- static-route configuration read -- NEW
- Literal: `clish -c 'show route static'` (registry quote style, as `cp_configuration_show_configuration`); the guide also lists `show route static all`.
- Shell/context: `expert` landing invoking Clish via `clish -c`; `SSH_EXEC`; timeout 30 s.
- Unsupported behavior: Clish error or lock text (`Database is locked`) is `GAIA_DB_LOCKED`, a result not a retry; empty output means no static routes.
- Safe fields: static-route count, per-route (destination, next-hop, priority) kept in memory for the pre-check; route-table SHA-256; flags `logical`/`blackhole`/`reject`/`ping`/`rank`/`scopelocal` as `UNSUPPORTED_ROUTE_ATTRIBUTE`.
- Doc: the guide above shows `show route static` in its example output and `show route static all` in syntax. The guide's removal syntax reads `nexthop gateway <IPv4> off` (no word `address`), unlike the contract's DELETE literal -- an R1 point to settle, not R0.
- Alternative needing no new command: the signed-off `cp_configuration_show_configuration` (`clish -c 'show configuration'`, 60 s) already contains `set static-route` lines; reading them from a stored or fresh configuration capture is a parse-scope extension. It is whole-config and secret-bearing, so the PO may prefer R0-CP-1.

### R0-CP-2 -- single-host route lookup (path confirmation) -- NEW
- Literal: `ip -4 route get <DST>` (Expert, plain non-login form; `<DST>` one validated IPv4 host inside the destination prefix).
- Shell/context: `expert`; `SSH_EXEC`; timeout 30 s. Frequency: once before and once after a change per member, preview included.
- Why not reuse: `path_validation_cp_route_get` is `bash -lc 'ip -4 route get <DST>'`, limited to path_validation jobs; the contract forbids `bash -lc`.
- Unsupported behavior: error, missing route line or unknown route type is `UNKNOWN` verdict, never a pass.
- Safe fields: selected prefix, next hop, egress interface, route source type, digest; raw output discarded.
- Doc: https://man7.org/linux/man-pages/man8/ip-route.8.html (fetched; Linux iproute2, not a Check Point page -- no Check Point page found that documents it).
- Gaia Clish alternative `show route destination <ip>`: UNKNOWN. Seen only in search results for an R77 guide; the R81 Routing Monitor page (https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_Gaia_Advanced_Routing_AdminGuide/Topics-GARG/Routing-Monitor.htm, fetched) says to discover subcommands with `show route` plus Esc Esc and does not list it. Not proposed.

### R0-CP-3 -- routing table read (RIB) -- REUSE `cp_inventory_ip_route_show_table_all`
- Literal: `ip -4 route show table all`; `expert`; `SSH_EXEC`; 30 s; SIGNED_OFF. Needs its frequency text widened from "once per device per run" to include the route preview and the before/after reads.
- Doc: Linux iproute2 (`CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entry 3); same man page as R0-CP-2.

### R0-CP-4 -- ClusterXL state and Cloning Group check -- REUSE `cp_inventory_cphaprob_stat`
- Literal: `cphaprob stat`; `expert`; `SSH_EXEC`; 30 s; SIGNED_OFF. Reading the cluster mode line is a parse-scope extension. Its frequency text is failover-specific, so a route-preview caller needs a wording change.
- Doc: R82.20 ClusterXL Administration Guide, "Viewing Cluster State" (https://sc1.checkpoint.com/documents/R82.20/WebAdminGuides/EN/CP_R82.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Viewing-Cluster-State.htm, found in search; its page text was not fetched).
- UNKNOWN: how Cloning Group mode appears in `cphaprob stat`. The results found do not mention Cloning Group. Until measured, an unrecognised mode string is treated as `CLONING_GROUP_UNSUPPORTED` (fail closed).

### R0-CP-5 -- anti-spoofing topology ("defined by routes") -- UNKNOWN, no literal proposed
- No citable Gaia-side read was found; anti-spoofing is a SmartConsole (management) setting. Per the contract, an unknown mode raises `CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING` for both approvers, so R0 needs no new command. A future source (management-plane read) is a separate proposal.

## 3. Palo Alto Networks (direct firewall, legacy virtual-router), `PAN_XML_API`

Doc base: PAN-OS XML API, "Configuration (API)" (https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/configuration-api, fetched: documents `action=show` with an `xpath` for the active configuration and `action=get` for the candidate).

### R0-PAN-1 -- running static-route configuration of one virtual router -- NEW
- Literal: `type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route` (`<VR>` a name validated against stored inventory; `action=show` always, never `action=get`).
- Shell/context: `not_applicable`; `PAN_XML_API`; 30 s. Frequency: once per member per preview or job.
- Why NEW: the signed-off `pan_configuration_active` is `xpath=/config` (whole config); a narrower xpath is a different literal.
- Unsupported behavior: missing VR is `ROUTING_CONTEXT_NOT_FOUND`; API status other than success is `UNKNOWN`.
- Safe fields: route count, per-route (name, destination, next-hop, interface, metric, admin-dist, `src` attribute) in memory; digest.
- Doc: URL above documents the request form; the exact element schema for each PAN-OS version is UNKNOWN until checked with `debug cli on`.
- Alternative needing no new command: parse the same node from the whole running config already collected by `pan_configuration_active` (parse-scope extension, but a whole-config read, 11.8 MB class).

### R0-PAN-2 -- route lookup for one host (path confirmation) -- REUSE `path_validation_pan_fib_lookup`
- Literal: `<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>`; `type=op`; 30 s; SIGNED_OFF (PO 2026-11-04). Its frequency/caller text is "PO-chosen sample, path_validation job only", so a route-preview caller needs a wording change.
- Doc: UNKNOWN as an official page. The Palo Alto KB article "How to Perform FIB Lookup for a Particular Destination" (https://live.paloaltonetworks.com/t5/Learning-Articles/How-to-Perform-FIB-Lookup-for-a-Particular-Destination/ta-p/52188) returned HTTP 403 and the PAN-OS 11.0 web help page returned 404 when fetched; the form was seen only in search results.

### R0-PAN-3 -- pending-changes check -- NEW
- Literal: `type=op&cmd=<check><pending-changes/></check>` (expected `<result>no</result>`; `yes` refuses with `PENDING_CHANGES_PRESENT`).
- Shell/context: `not_applicable`; `PAN_XML_API`; 30 s. Read-only: it reports, it changes nothing.
- Unsupported behavior: any other answer shape is `UNKNOWN` and stops the job.
- Safe fields: the yes/no value only.
- Doc: UNKNOWN as an official page. Secondary sources show the CLI `check pending-changes` (PAN-OS 11.1 cheat sheet, https://blog.boll.ch/wp-content/uploads/2025/01/CheatSheet-PANOS-11.1-v1.2.pdf, a third-party document found in search, not fetched). The XML form is to be confirmed with `debug cli on` per PAN-OS version, and the command's class (a read) confirmed from the output.

### R0-PAN-4 -- template provenance (`src="tpl"`) -- REUSE `pan_configuration_effective_running`
- Literal: `<show><config><effective-running/></config></show>`; 120 s; SIGNED_OFF. The contract calls it `pan_config_show_effective_running`; the registry id is `pan_configuration_effective_running`. Reading `src="tpl"` on the VR and route is a parse-scope extension; frequency "once per device per run" needs a route-preview wording.
- Doc: XML API, operational commands (https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/run-operational-mode-commands-api, seen in search, not fetched); registry source `PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md` entry 3.

### R0-PAN-5 -- HA state and config sync -- REUSE `pan_inventory_show_ha_state`
- Literal: `<show><high-availability><state/></high-availability></show>`; 30 s; SIGNED_OFF. Frequency text already covers config-sync jobs; a route-preview caller needs adding. Doc: registry source `PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md` item 7.

### R0-PAN-6 -- routing table (RIB) -- REUSE `pan_inventory_show_routing_route`
- Literal: `<show><routing><route/></routing></show>`; 30 s; SIGNED_OFF. Frequency widening as for R0-CP-3. Doc: `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` entry 4. Advanced Routing Engine equivalents (`show advanced-routing route`, PAN-OS CLI cheat sheet https://docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/cli-cheat-sheet-networking, fetched) are not proposed: no ARE unit exists in the estate (38 of 38 on legacy virtual-router) and ARE is refused.

The commit-job poll (`show jobs id`) is not R0: R0 commits nothing. It belongs to `R1`.

## 4. Cisco ASA (routed mode), privileged EXEC

Doc base: Cisco ASA Series General Operations CLI Configuration Guide, "Static and Default Routes" (https://www.cisco.com/c/en/us/td/docs/security/asa/asa916/configuration/general/asa-916-general-config/route-static.html, fetched: `route if_name dest_ip mask gateway_ip [distance]`, distance 1 to 254 on that page, while the contract says 1..255 -- settle before R1).

### R0-ASA-1 -- device mode -- REUSE `asa_show_mode`
- Literal: `show mode`; `privileged_exec`; registry transport `SSH_EXEC`; 30 s; SIGNED_OFF. Frequency "one diagnostic command per endpoint per minute" needs a route-preview wording. Doc: Cisco ASA command reference (registry pointer); no page fetched for this command.

### R0-ASA-2 -- privilege level -- REUSE `asa_show_curpriv`
- Literal: `show curpriv`; 30 s; SIGNED_OFF; below 15 stops the run. Re-checked after every context switch (the Cisco guide says the username changes to `enable_15` on `changeto context`). Doc: registry pointer only.

### R0-ASA-3 -- context switch (multiple-context mode only) -- NEW
- Literal: `changeto context <CTX_NAME>` (name validated against stored inventory, never free text).
- Shell/context: `context`; `SSH_INTERACTIVE` (the switch changes session state, so every following read must run in the same interactive session; the registry's other ASA reads are `SSH_EXEC`, UNKNOWN whether one exec channel can carry a context change); timeout 30 s. Frequency once per context per preview or job.
- Related signed-off precedent: `asa_sb_changeto_system` (`changeto system`, 10 s, `SSH_INTERACTIVE`) is a different literal; it does not cover this one.
- Unsupported behavior: any prompt other than `<host>/<CTX_NAME>#` aborts with `UNEXPECTED_PROMPT`; a refusal ends the read, nothing further is sent.
- Safe fields: success flag and the verified prompt shape only (no hostname).
- Doc: https://www.cisco.com/c/en/us/td/docs/security/asa/asa91/configuration/general/asa_91_general_config/ha_contexts.html (fetched, ASA 9.1 guide; prompt becomes `ciscoasa/name#`, `changeto system` returns to `ciscoasa#`). The contract cites a 9.22 guide; not fetched.
- Behaviour to measure: `show mode` first (R0-ASA-1); in single mode no `changeto` is sent. Changing context does not alter configuration or operational state, but the PO confirms this reading.

### R0-ASA-4 -- configured static routes -- NEW, doc UNKNOWN
- Literal: `show running-config route` (inside the target context).
- Shell/context: `privileged_exec` in the context; `SSH_INTERACTIVE` (or `SSH_EXEC` single-context); 30 s.
- Unsupported behavior: empty output means no static routes in that context; unexpected prompt aborts.
- Safe fields: per-route (interface, destination, mask, next-hop, distance, `track` flag) in memory; digest; routes with `track` or `null0` flagged unsupported.
- Doc: UNKNOWN. The Cisco pages fetched (static-route guide, command reference chapter, which was truncated) did not show this command; only a Cisco Community result mentions `show run route` for VPN-injected routes. Confirm with `?` help on the estate version.
- Fallback with no new command: the signed-off `asa_more_system_running_config` is whole-config, secret-bearing, "once per backup"; not recommended.

### R0-ASA-5 -- routing table (RIB) -- REUSE `asa_show_route`
- Literal: `show route`; `privileged_exec`; SIGNED_OFF; the registry timeout is 180 s, longer than the contract's 30 s. Run per context. Frequency wording needs a route-preview caller. Doc: Cisco ASA command reference, show route (https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/m_show_p-show_r.html, page fetched but its `show route` syntax lines fell in the truncated part).

### R0-ASA-6 -- single-host route lookup (path confirmation) -- NEW
- Literal: `show route <HOST>` (one validated IPv4 host, in the context).
- Shell/context: `privileged_exec` in the context; same transport as R0-ASA-5; 30 s (180 s if the PO keeps the registry timeout for `show route`). Once before and once after a change.
- Unsupported behavior: unparsed or "not in table" answer gives `UNKNOWN` verdict.
- Safe fields: selected prefix, next hop, egress interface, route type; digest.
- Doc: same command-reference chapter as R0-ASA-5, which lists an `ip_address mask` form (search result summary; syntax text not read in the fetched page). A Cisco Community post says ASA 9.x accepts `show route X.X.X.X` (not an official source). Treat the syntax as UNKNOWN until measured.

ASA failover state reads (`show failover`, `show failover state`) are R1 Stage B pre-checks and reuse the signed-off `asa_failover_*` gates; they are not new R0 literals.

## 5. Literal index (for approval)

| Id | Vendor | Literal | Gate | Doc |
|---|---|---|---|---|
| R0-CP-1 | Check Point | `clish -c 'show route static'` | NEW | Gaia guide static routes (fetched) |
| R0-CP-2 | Check Point | `ip -4 route get <DST>` | NEW | man7 ip-route (fetched) |
| R0-CP-3 | Check Point | `ip -4 route show table all` | REUSE `cp_inventory_ip_route_show_table_all` | man7 / registry |
| R0-CP-4 | Check Point | `cphaprob stat` | REUSE `cp_inventory_cphaprob_stat` | R82.20 ClusterXL guide (not fetched) |
| R0-CP-5 | Check Point | anti-spoofing read | none proposed | UNKNOWN |
| R0-PAN-1 | Palo Alto | `type=config&action=show&xpath=...virtual-router/entry[@name='<VR>']/routing-table/ip/static-route` | NEW | XML API configuration (fetched) |
| R0-PAN-2 | Palo Alto | `<test><routing><fib-lookup>...` | REUSE `path_validation_pan_fib_lookup` | UNKNOWN (403/404) |
| R0-PAN-3 | Palo Alto | `type=op&cmd=<check><pending-changes/></check>` | NEW | UNKNOWN (third-party only) |
| R0-PAN-4 | Palo Alto | `<show><config><effective-running/></config></show>` | REUSE `pan_configuration_effective_running` | registry / API docs (not fetched) |
| R0-PAN-5 | Palo Alto | `<show><high-availability><state/></high-availability></show>` | REUSE `pan_inventory_show_ha_state` | registry |
| R0-PAN-6 | Palo Alto | `<show><routing><route/></routing></show>` | REUSE `pan_inventory_show_routing_route` | registry |
| R0-ASA-1 | Cisco ASA | `show mode` | REUSE `asa_show_mode` | registry |
| R0-ASA-2 | Cisco ASA | `show curpriv` | REUSE `asa_show_curpriv` | registry |
| R0-ASA-3 | Cisco ASA | `changeto context <CTX_NAME>` | NEW | ASA 9.1 contexts guide (fetched) |
| R0-ASA-4 | Cisco ASA | `show running-config route` | NEW | UNKNOWN |
| R0-ASA-5 | Cisco ASA | `show route` | REUSE `asa_show_route` | command reference (page fetched, syntax truncated) |
| R0-ASA-6 | Cisco ASA | `show route <HOST>` | NEW | UNKNOWN syntax |

## 6. UNKNOWN list

Cloning Group appearance in `cphaprob stat`; any Check Point anti-spoofing read; Check Point `show route destination`; official PAN-OS pages for `fib-lookup` and `check pending-changes`; the exact PAN-OS static-route element schema per version; `show running-config route` and `show route <HOST>` syntax on the estate ASA versions; whether an ASA context change can run over `SSH_EXEC`; the ASA static-route distance range (254 versus 255).
