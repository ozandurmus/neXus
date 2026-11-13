# Path analysis — vendor semantics (stage A0)

Status: FROZEN — 2026-11-04, under the PO freeze delegation (PO_DECISION_RECORD_2026_11_04_DEV_MODE_DELEGATIONS.md).
Every claim marked UNKNOWN below stays UNKNOWN; freezing records the cited evidence, it does not decide what the documentation leaves open.

Input to `PATH_ANALYSIS_DESIGN.md` §5/§7. Official vendor documentation only, read 2026-11-03; no device contacted,
no customer data read. Per the vendor semantics law, what the documents do not establish is `UNKNOWN`. Documentation
does not replace real-environment corroboration (A6).

Confidence: **HIGH** stated directly on a current page; **MEDIUM** older page, direct implication, or vendor example
only; **LOW** indirect or another product line.

Short source keys (full URLs in §Sources): CP-NAT, CP-MNAT, CP-LAY, CP-COL, CP-SGW, CP-PKG, CP-INST, CP-R76, CP-SEC,
CP-API, CP-CME, CP-ISP, CP-SMB, CP-ASP, CP-ECMP; PA-NAT, PA-DNAT, PA-DG, PA-SEC, PA-TGT, PA-SUB, PA-ARE, PA-CLI,
PA-RT, PA-LR, PA-KB1, PA-KB2, PA-AD, PA-CSR.

## 1. Check Point: packet order through a gateway

**1a. NAT order = rulebase position; manual first match.** HIGH. CP-NAT (R82; same text R81).
- "The Security Gateway enforces the NAT Rule Base in a sequential manner - in the order you place the rules in the NAT Policy"
- "The Security Gateway enforces the first Manual NAT rule that matches a connection."
- Automatic: "can enforce two Automatic NAT rules that match a connection - one rule for the Source and one for the Destination."
  SmartConsole orders automatic rules: static host/gateway, hide host/gateway, static network/range, hide network/range.
- Design correction: CP-MNAT (R82) instructs "Add a new rule (#3) below the existing Automatic NAT rules." Manual
  rules may sit above or below automatic ones; evaluate in collected position, not "manual then automatic".
- UNKNOWN: how a manual match combines with automatic matches on one connection.

**1b. Access rulebase on the pre-NAT tuple?** UNKNOWN — sources conflict.
- For: CME generated access rule source is "The same as the Original Source in the corresponding NAT rule", destination
  the NAT rule's original destination object (CP-CME, CME guide, 24 Sep 2026).
- Against/ambiguous: CP-MNAT workflow says "Configure the Access Control Policy to allow traffic to the applicable
  translated objects with the valid IP addresses."; its worked access rule targets the internal DMZ network while
  the NAT original destination is the gateway object.
- No page found states access-vs-NAT order. CP-ASP (R80.10) only: "NAT is performed after Anti-Spoofing checks".
- sk articles (support.checkpoint.com) require login; not consulted. Design "access on pre-NAT first" stays ASSUMPTION.

**1c. Routing relative to NAT.** UNKNOWN.
- Global Properties has "Translate destination on client side" in the Manual NAT rules section (CP-ISP, R82).
- CP-SMB (SMB R80.20, LOW) lists an automatic-rules twin: "Translates destination IP addresses on client side (for automatically generated NAT rules)."
- UNKNOWN: Gaia defaults of both options and whether route lookup uses the pre- or post-translation destination.

## 2. Check Point: ordered and inline layers

**2a. Ordered layers.** HIGH. CP-LAY (R82, 12 Oct 2026; same R81).
- "If the Action is Accept, the Security Gateway continues to check rules in the next Ordered Layer."
- Drop: "the Security Gateway stops matching against later rules in the Policy Rule Base and drops the packet."
- "Every Ordered Layer has its own implicit cleanup rule. You can configure the rule to Accept or Drop in the Layer settings"; default Drop (R80.10+ gateways).
- Allowed only if every ordered layer ends in accept. Fall-through action = explicit cleanup if present, else the
  layer's implicit action. UNKNOWN: whether the implicit action is collected; if not, fall-through is `UNKNOWN`, never assumed Drop.

**2b. Inline layers.** HIGH (MEDIUM where noted). CP-LAY.
- "The Action of the parent rule is the name of the Inline Layer."
- No parent match: "the matching continues to the next rule of the Ordered Layer".
- "If the packet matches a sub rule in the Inline Layer (Rule 2.1), no more rule matching is done."
- No sub-rule match: explicit cleanup; "If this rule is missing, the Implicit Cleanup Rule is applied ... No more rule matching is done."
- Each inline layer has its own implicit cleanup (creation step: "Configure the Implicit Cleanup Rule to Drop or Accept").
- Hence a matched parent is terminal within its ordered layer. MEDIUM: an inline Accept then proceeds to the next
  ordered layer (follows from 2a; not stated for inline layers).
- API: `inline-layer` is "Relevant only if \"Action\" was set to \"Apply Layer\"." (CP-API, `add-access-rule`).

**2c. Sections.** MEDIUM. CP-SEC (R80.20; no R82 statement found): "The division is only visual".
- CP-API `show-access-rulebase`: "This layer is divided into sections. An Access Rule may be within a section, or independent of a section".
- UNKNOWN: whether reply list order is enforcement order; `rule-number` appears in vendor examples but is undocumented
  in the `AccessRuleReply` schema, so numbering continuity across sections is unknown.

## 3. Check Point: Install On

**3a. Meaning.** HIGH for the restriction, MEDIUM for Policy Targets.
- Install On: "Network objects that will get the rule(s) of the policy." (CP-COL, R82).
- Values: "Policy Targets or Specific Security Gateway and Cluster objects" (CP-SGW, R82).
- Package: "select the gateways the policy will be installed on" — All or Specific (CP-PKG, R82).
- "The installation targets are defined for the whole Policy Package, thereby eliminating the need to specify them per-rule" (CP-R76, R76: MEDIUM).
- API object comment: "The policy target gateways" (CP-API example).
- Inference (MEDIUM): Policy Targets = the package installation targets; a rule installed on other gateways/clusters
  is not on this gateway → skipped, not `UNKNOWN`. Cluster install covers all members (CP-INST: it "makes sure that
  it can install the policy on all cluster members").
- UNKNOWN: whether Policy Targets tracks the targets narrowed in an individual install operation; behaviour of a rule
  installed on a gateway outside the package targets.

**3b. API representation.** HIGH for schema, MEDIUM for example content. CP-API (Management API v2.2).
- `AccessRuleReply.install-on` (list): "Which gateway, identified by the name or UID, to install the policy."
- `show-package` `installation-targets`: "Which Gateways identified by the name or UID."
- Vendor `show-access-rulebase` examples with `use-object-dictionary: true`: `install-on` is a list of uid strings and
  `objects-dictionary` contains `"name" : "Policy Targets"`, `"type" : "Global"`.
- UNKNOWN: whether the dictionary is guaranteed to contain every referenced uid (schema silent); whether the Policy
  Targets uid is constant across servers (it repeats in examples, but per identity law it is opaque — recognise by
  dictionary entry, never hard-code).

## 4. Palo Alto: security and NAT

**4a. Flow.** HIGH. PA-NAT (PAN-OS 11.1, updated 17 Sep 2026); PA-DNAT (current NGFW docs).
- "Upon ingress, the firewall inspects the packet and does a route lookup to determine the egress interface and zone."
- Security matches "the original (pre-NAT) source and destination addresses, but the post-NAT zones."
- "the translation of the IP address and port do not occur until the packet leaves the firewall."
- "the destination zone in the security rule is determined after the route lookup of the post-NAT destination IP address." (PA-DNAT)
- Confirms the design §5 PAN order.

**4b. NAT first match.** HIGH. PA-NAT: "Once a packet matches the criteria of a single NAT rule, the packet is not
subjected to additional NAT rules." Also "Static NAT rules do not have precedence over other forms of NAT."

**4c. Pre / local / post / default.** HIGH (security), MEDIUM (NAT). PA-DG (Panorama 11.1), PA-SEC (PAN-OS 11.1).
- The firewall "performs the action defined in the first evaluated rule that matches the traffic and disregards all subsequent rules."
- "Local firewall rules display between the pre-rules and post-rules." Pre-rules "highest to lowest level" (shared
  first); post-rules "lowest to highest level" (shared last).
- "The default rules apply only to the Security rulebase"; they "allow all intrazone traffic (within a zone) and deny all interzone traffic (between zones)."
- "overridden settings at the firewall level take precedence over settings at the device group level"
- MEDIUM: the order is stated for "policy rules" generally, so applied to NAT by implication; NAT fall-through = no translation.
- UNKNOWN: effective default action when not all override levels were collected → design keeps `UNKNOWN`.

**4d. Target / negate (Panorama).** HIGH semantics; XML names UNKNOWN. PA-TGT (11.1 help), PA-SUB (current).
- Negate: "push the policy rule to all managed firewalls associated with the device group except for the selected device(s) and tag(s)."
- "If you do not select firewalls to target, the rule is added to all of the (unchecked) firewalls in the device group."
- Negate with nothing selected: "the rule is not added to any of the firewalls in the device group."
- UNKNOWN from docs: XML element names (`target/devices`, `target/negate`, tags; only a REST example shows
  `"negate"`); vsys-entry combination. Repository parser is the only shape evidence.

## 5. Palo Alto: routing

**5a. Engines.** HIGH. PA-ARE (PAN-OS 10.2+), PA-CLI (current).
- "only one routing engine is in effect at a time."
- "The Advanced Routing Engine supports multiple logical routers (known as virtual routers on the legacy routing engine)."
- RIB `show advanced-routing route`, FIB `show advanced-routing fib`; legacy `show routing route`, `show routing fib virtual-router <name>`.
- UNKNOWN: output of `show routing route` when ARE is enabled (G10 stays ASSUMPTION); how to read the active engine.

**5b. Flags, RIB vs FIB.** HIGH (web UI), MEDIUM (CLI via KB examples).
- PA-RT (10.2): "A S —Active and static S —Inactive (because this route has a higher metric) and static" → the RIB view lists inactive routes.
- "The firewall chooses the best route—from the route table (RIB) toward a destination network—to place in the FIB."
  FIB flag "e —Firewall selected this route using Equal Cost Multipath (ECMP)." Same on PA-LR (logical router).
- PA-KB1 legacy CLI: "flags: A:active, ?:loose, C:connect, H:host, S:static, ~:internal, R:rip, O:ospf, B:bgp,"; its
  example shows a `?B` route without `A`.
- PA-KB2 advanced routing (10.2+): "flags: A:active, E:ecmp, Oi:ospf intra-area, ..."; and a defect where "LR A
  installs the prefix/es on its RIB table; but the same prefix/es never gets installed in its FIB" (fixed 10.2.8/11.0.4/11.1.0).
- So RIB `A` is necessary, not sufficient, for forwarding; FIB / `test routing fib-lookup` (A6) is the forwarding truth.
- UNKNOWN: an ECMP flag in the legacy CLI legend; forwarding use of `?` (loose) routes.

## 6. Palo Alto `application-default`

HIGH meaning, UNKNOWN data.
- Keep Service "application-default to ensure that any applications that the rule allows are allowed only on their standard ports." (PA-CSR, 11.1)
- "The 'application-default' function prevents security evasion by only allowing applications to run on their standard ports." (PA-AD, updated 26 Oct 2026)
- Default ports come from Applipedia / Objects > Applications.
- UNKNOWN: per-application default ports (not collected); matching before App-ID identifies the app; interaction with
  application `any`. D-5 stands; without collected port data the hop is `UNKNOWN`.

## 7. Gaia multipath in `show route`

- CP-ECMP (R81.20, HIGH): "Only OSPF, BGP, and Static routes are able to use paths/routes with more than one \"nexthop\"";
  up to 8 paths, default 2-tuple source/destination hash.
- UNKNOWN: no official example of clish `show route` multipath output. The repository reads Linux
  `ip -4 route show table all` (`InventoryReadPlan.CP_IP_ROUTE_SHOW`), which Check Point does not document; the
  `nexthop` continuation shape (G4) needs a masked real fixture.

## Repository impact

| Item | Topic | What the docs allow |
|---|---|---|
| CP hop order (§5) | 1b, 1c | Pre-NAT access match not established. When a destination NAT matches, evaluate access on both tuples; differing verdicts → `UNKNOWN (CP_ACCESS_NAT_ORDER_UNPROVEN)`. Keep the NAT-side caveat. |
| CP NAT evaluator (A2) | 1a | Rulebase position; manual first match; automatic may yield one source + one destination translation. Fix §5 wording. |
| G1 | 5b | `is_active` from `A` (non-`A` routes do appear). `is_ecmp` from `E` only where the legend has it, else NULL. RIB `A` ≠ forwarding; FIB read needs D-7 approval. |
| G2 | 5a | VR and LR equivalent; record which engine produced `routing_context`. |
| G3 | 4b, 4c | Local mapper must emit NAT (first match) and per-vsys default sections with firewall-level override precedence. |
| G4 | 7 | ECMP real (≤8, hashed): branch over the set. iproute2 shape `UNKNOWN` → fixture. |
| G5, G6, G9 | — | Not A0 topics; remain ASSUMPTION / privacy. |
| G7 | 3a, 3b | Install-on not containing the hop gateway or its cluster → skip (MEDIUM). Policy Targets (dictionary type `Global`) → package `installation-targets` from `show-package`; recognise by dictionary entry, not uid. |
| G8 | 4d | Semantics doc-backed, incl. negate + empty list = no firewall. XML names rest on repository evidence. |
| G10 | 5a | RIB/FIB commands documented; `show routing route` under ARE `UNKNOWN`; engine-state read first (D-7). |
| D-5 | 6 | Meaning confirmed; port data not collected; caveat stays. |

**Hygiene: 29,844 CP rules `UNKNOWN` (install-on absent from objects dictionary).**
- Vendor examples put the install-on uid (Policy Targets, type `Global`) in `objects-dictionary`; absence is not a
  documented vendor state, and completeness is not promised. Vendor behaviour vs collection artifact: `UNKNOWN`.
- Repository caveat: `CheckPointPolicyMapper` stores an absent uid as type `unresolved`/`UNRESOLVED`, but also maps a
  **present** `Global` object to type `unresolved`/`UNSUPPORTED` (`PolicyMappersTest`). Split the count by status and
  native type (counters only) before explaining the number.
- Docs allow: Policy Targets → package installation targets (3a/3b); gateway/cluster uids → collected package targets
  (G7 binding). Rules bound elsewhere can then be skipped instead of `UNKNOWN`.

**Hygiene: 12,650 rules `UNKNOWN` ("earlier sections not analysed").**
- Docs allow: sections are visual only (2c, MEDIUM), so section boundaries inside a layer do not change matching; an
  inline layer is an independent sub-policy entered only via its parent and ends matching within the layer (2b,
  HIGH). The product may scope inline layers by layer identity (`inline-layer` uid) instead of section identity and
  treat a matched parent as terminal for later outer rules.
- Docs do not establish enforcement order inside a layer (list order, `rule-number` undocumented). Dropping the
  "rule numbers must increase" check would substitute API list order for proven order: needs A6 corroboration (e.g.
  SmartConsole Packet Search on PO-chosen samples) or an explicit PO decision. Until then these rules stay `UNKNOWN`.

## Sources

Check Point (R82 Security Management guide base `https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityManagement_AdminGuide/Content/Topics-SECMG/` = `SM82/`):
- CP-NAT `SM82/Configuring-NAT-Policy.htm`; CP-MNAT `SM82/Working_with_Manual_NAT_Rules.htm`; CP-LAY `SM82/Ordered-Layers-and-Inline-Layers.htm`;
  CP-COL `SM82/The-Columns-of-the-Access-Control-Rule-Base.htm`; CP-PKG `SM82/Working-with-Policy-Packages.htm`; CP-INST `SM82/Installing-the-Access-Control-Policy.htm`
- CP-SGW https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityGateway_Guide/Content/Topics-FWG/Security-Policy.htm
- CP-ISP https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityGateway_Guide/Content/Topics-FWG/ISP-Redundancy-Configuration.htm
- CP-R76 https://sc1.checkpoint.com/documents/R76/CP_R76_SecMan_WebAdmin/13114.htm
- CP-SEC https://sc1.checkpoint.com/documents/R80.20_GA/WebAdminGuides/EN/CP_R80.20_SecurityManagement_AdminGuide/136972.htm
- CP-ASP https://sc1.checkpoint.com/documents/R80.10/WebAdminGuides/EN/CP_R80.10_SecurityManagement_AdminGuide/94784.htm
- CP-CME https://sc1.checkpoint.com/documents/IaaS/WebAdminGuides/EN/CP_CME/Content/Topics-CME/Automatic-NAT-and-Access-Rules.htm
- CP-SMB https://sc1.checkpoint.com/documents/SMB_R80.20/AdminGuides/Locally_Managed/EN/Content/Topics/Configuring-Advanced-Settings.htm
- CP-ECMP https://sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_Gaia_Advanced_Routing_AdminGuide/Content/Topics-GARG/Routing-Options-Equal-Cost-Multipath.htm
- CP-API https://sc1.checkpoint.com/documents/latest/APIs/#web/show-access-rulebase~v2.2 (v2.2 schema and examples:
  `.../APIs/data/v2.2/dynamic/apis.json`, `examples.json`)

Palo Alto:
- PA-NAT https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-networking-admin/nat/nat-policy-rules
- PA-DNAT https://docs.paloaltonetworks.com/ngfw/networking/nat/destination-nat-exampleone-to-one-mapping
- PA-DG https://docs.paloaltonetworks.com/panorama/11-1/panorama-admin/panorama-overview/centralized-firewall-configuration-and-update-management/device-groups/device-group-policies
- PA-SEC https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/policy/security-policy
- PA-TGT https://docs.paloaltonetworks.com/ngfw/help/11-1/policies/policies-nat/nat-target-tab
- PA-SUB https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-device-groups/push-a-policy-rule-to-a-subset-of-firewalls
- PA-ARE https://docs.paloaltonetworks.com/pan-os/10-2/pan-os-networking-admin/advanced-routing/enable-advanced-routing
- PA-CLI https://docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/cli-cheat-sheet-networking
- PA-RT https://docs.paloaltonetworks.com/pan-os/10-2/pan-os-web-interface-help/network/network-virtual-routers/more-runtime-stats-for-a-virtual-router/routing-tab
- PA-LR https://docs.paloaltonetworks.com/pan-os/10-2/pan-os-web-interface-help/network/network-virtual-routers/more-runtime-stats-for-a-logical-router/routing-stats-for-a-logical-router
- PA-KB1 https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA10g000000ClZ4CAK (no version; modified 2023)
- PA-KB2 https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA14u0000008XTLCA2 (PAN-OS 10.2+)
- PA-AD https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/app-id/application-default
- PA-CSR https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/policy/security-policy/create-a-security-policy-rule

## Amendment 2026-11-04 — Check Point access-role networks (engineer decision under the PO freeze delegation)

An access role combines users, computers and network locations (R82 Identity Awareness Administration Guide; the
`show-access-role` reply has a `networks` field, "Collection of Network objects ... that can access the system").
Shadow analysis uses a role's networks only as an upper bound of its source, and only to prove that a later rule's
source is disjoint (never to prove cover). With X-Forwarded-For identity detection (not collected), a proxied HTTP
connection can take its identity from the header, so a role may match some packets from outside its networks; it still
cannot match every packet of a later rule, which is all a single-rule NOT_SHADOWED verdict claims. Revisit if A6
evidence contradicts this.

## Amendment 2026-11-13 — shadowing resolution (PO approved)

Negated address cells, Palo Alto service source ports and Check Point ICMP codes for the policy shadowing analysis;
the Check Point comparison port forms stay UNKNOWN. Text, citations and the covering-rule revisit trigger:
`PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_13.md` (FROZEN 2026-11-13, PO approval "Onaylıyorum").
