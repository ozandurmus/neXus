# Vendor semantics amendment to A0 - Cisco ASA and FortiGate policy (2026-11-14)

## Status

FROZEN - 2026-11-14. PO approval relayed by the PO session (2026-11-14), verbatim for Q2: the amendment "becomes
authority after you re-verify the high-confidence quotes on the source pages themselves"; recorded in
`PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` item 3. Amendment to `PATH_ANALYSIS_VENDOR_SEMANTICS.md`
(A0, FROZEN 2026-11-04). Official vendor documentation only (cisco.com, docs.fortinet.com), read 2026-11-14; no device
contacted, no customer data read. Everything the sources do not establish stays UNKNOWN.

**Quote verification (2026-11-14).** Every HIGH quote was re-checked as an exact, whitespace-normalised substring of
the page text itself: Fortinet pages from their raw HTML, Cisco pages (which refuse non-browser clients) from the
rendered page text in a browser. All matched; two Fortinet quotes were sentence fragments and are now quoted to their
full sentence (A0-FG-9, A0-FG-10). The same check upgraded A0-ASA-15 (port literal table found), the static-before-
dynamic order in A0-ASA-17, the `match-vip` default in A0-FG-20 and the address `subnet` default in A0-FG-24. MEDIUM
and LOW rows were not re-verified and carry no decided semantics beyond their stated use.
Grades are A0's: **HIGH** stated directly on a current page; **MEDIUM** older page, direct implication, or vendor
example only; **LOW** indirect; **UNKNOWN** not found.

PO session decision (same record, item 4): the uncertain ASA default behaviour (A0-ASA-4b, A0-ASA-4c) stays UNKNOWN in
verdicts; only A0-ASA-4a is a decided answer.

Estate scope these claims are needed for (counts from the live database, 2026-11-14; design section 3): Cisco ASA
9.18 and 9.22, single context, routed mode, IPv4 interfaces only; FortiOS 7.4, 7 single-VDOM and 2 multi-VDOM units,
none with central NAT, NGFW policy-based mode, zones, proxy inspection or transparent mode in the stored settings.

Source keys (full URLs in "Sources"): ASA-AR (access rules 9.22), ASA-ACL (ACLs 9.20), ASA-NAT (NAT 9.20),
ASA-NATREF (NAT reference 9.22), ASA-OBJ (objects 9.22), ASA-CTX (multiple context 9.22), ASA-FW (firewall mode 9.22),
ASA-INTRO (introduction 9.23), ASA-S (command reference S), ASA-CLI (command reference CLI usage), ASA-PF82 (8.2
packet flow, Doc ID 113396), ASA-PORTS (addresses, protocols and ports 9.22); FG-POL (firewall policy admin 7.4.7), FG-CLIPOL (CLI `config firewall policy` 7.4.7),
FG-SNAT (central SNAT 7.4.3), FG-DNAT (central DNAT 7.4.4), FG-PPP (life of a packet 7.6.0 and 6.4.0), FG-ZONE (CLI
zone 7.4.0 and zone admin 7.4.4), FG-VDOM (VDOM overview 7.4.0), FG-NGFW (NGFW policy 7.4.0), FG-ADDR (CLI address
7.4.4), FG-SVC (CLI service custom 7.4.0), FG-CONS (consolidated policy 6.4.0), FG-LOCAL (local-in 7.4.3), FG-SDWAN
(SD-WAN zones 7.4.4).

## 8. Cisco ASA: access rules

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-ASA-1 | An ACL is evaluated top-down; the first matching ACE decides; an implicit deny ends the list | ASA-AR, ASA-ACL | "After a match is found, no more rules are checked." / "ACLs have an implicit deny at the end of the list, so unless you explicitly permit it, traffic cannot pass." | HIGH | first match per ACL; fall-through deny (but see A0-ASA-3) |
| A0-ASA-2 | `access-group <acl> in\|out interface <nameif>` binds one ACL per interface per direction; a `global` access group applies to inbound traffic only | ASA-AR | "The in keyword applies the ACL to inbound traffic. The out keyword applies the ACL to the outbound traffic." / "Global access rules apply only to inbound traffic." / "You can configure one access-group command per ACL type per interface per direction" | HIGH | section per binding; an ACL with no `access-group` is not enforced as an access rule |
| A0-ASA-3 | Order: ingress interface rules, then (bridge groups only) BVI rules, then global rules, then implicit deny; with a global ACL the implicit deny comes after the global rules, not after the interface ACL | ASA-AR | "the specific inbound interface access rules are always processed before the general global access rules." / "If you configure a global access rule, then the implicit deny comes after the global rule is processed." | HIGH | inbound chain = interface-in ACL ++ global ACL ++ deny |
| A0-ASA-4a | Without access rules, unicast traffic from a higher to a lower security level is allowed | ASA-AR | "Unicast IPv4 and IPv6 traffic from a higher security interface to a lower security interface is allowed through by default." | HIGH | default when no inbound ACL and no global ACL applies |
| A0-ASA-4b | Lower to higher is denied without an access rule | ASA-AR (no direct sentence found) | - | LOW | fall-through = UNKNOWN unless A0-ASA-1's implicit deny applies (an ACL is bound); PO session: stays UNKNOWN |
| A0-ASA-4c | Whether binding an inbound ACL on an interface removes the implicit higher-to-lower permit for that interface | not found | - | **UNKNOWN** | with an inbound ACL bound, fall-through follows A0-ASA-1/3 (implicit deny of the bound list, HIGH); the higher-to-lower permit is used only on an interface with no inbound ACL and no global ACL. Any verdict that would depend on the open part is UNKNOWN (`ASA_SECURITY_LEVEL_DEFAULT_UNPROVEN`, PO session decision) until A6 corroboration (`packet-tracer`, design section 6) |
| A0-ASA-5 | Same security level: denied between different interfaces and on the same interface unless `same-security-traffic permit inter-interface` / `intra-interface` is configured; the check runs before access rules | ASA-S, ASA-AR | "This command is disabled by default." / "lets traffic enter and exit the same interface, which is normally not allowed." / "security level checking is performed before access rules are considered." | HIGH | a same-level path without the permit is DENIED before any ACL |
| A0-ASA-6 | Access rules match the real (untranslated) addresses, even with NAT | ASA-AR | "Access rules always use the real IP addresses when determining an access rule match, even if you configure NAT." | HIGH | ACL evaluated on the real tuple: destination after un-translation, source before translation |
| A0-ASA-7 | Return traffic of an allowed connection is permitted statefully | ASA-AR | "the ASA allows all returning traffic for established, bidirectional connections." | HIGH | Trace evaluates the forward direction only, as for CP/PAN |
| A0-ASA-8 | `inactive` disables an ACE; `time-range` limits an ACE to a time range | ASA-ACL | "Use the inactive option to disable the ACE without deleting it." / "The time-range time_range_name option specifies a time range object" | HIGH (inactive), MEDIUM (time-range) | `enabled=false`; time-ranged ACE -> UNKNOWN (time constraint, as CP `time`) |
| A0-ASA-9 | To-the-box traffic matching no control-plane (management) rule is then evaluated by the regular access rules; management rule lists have no implicit deny | ASA-AR | "Unlike regular access rules, there is no implicit deny at the end of a set of management rules for an interface." / "any connection that does not match a management access rule is then evaluated by regular access control rules." | HIGH | `control-plane` access groups are out of the transit model; listed, not evaluated |
| A0-ASA-10 | Transparent mode uses access rules for L3 and EtherType rules for L2 on bridge groups | ASA-AR, ASA-ACL, ASA-FW | "In transparent mode, you can use both access rules (for Layer 3 traffic) and EtherType rules (for Layer 2 traffic)." | HIGH | not present in the estate (0 of 12); a transparent unit -> `UNSUPPORTED` |
| A0-ASA-11 | `any4` / `any6` are IPv4-only / IPv6-only; `any` covers both (9.0+) | ASA-AR, ASA-ACL | "The any4 and any6 keywords were added to represent IPv4-only and IPv6-only traffic, respectively." | MEDIUM | `any4` -> IPv4 universe; `any6` -> IPv6 (engine IPv4-only: unresolved for IPv6 queries) |

## 9. Cisco ASA: objects and services

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-ASA-12 | Network object forms: host, subnet (address + mask after a space), range (no mask), FQDN; network groups nest with `group-object` | ASA-OBJ | "For IPv4 subnets, include the mask after a space." / "Do not include masks or prefixes." (range) / "group-object object_group_name" | MEDIUM | host/subnet/range -> RESOLVED ranges; dotted mask converted only when contiguous, else UNSUPPORTED; nesting expanded by the existing resolver (depth 32, cycle check) |
| A0-ASA-13 | FQDN object addresses are resolved at run time | ASA-OBJ only contrasts with network-service objects | "Unlike fully-qualified domain name objects, network-service domain specifications are snooped immediately." | UNKNOWN (run-time resolution not stated) | `DYNAMIC` (as PAN EDL), never resolved from the name |
| A0-ASA-14 | Service objects/groups mix protocols; `service-object {tcp\|udp\|tcp-udp\|sctp}` with `eq`, `neq`, `lt`, `gt`, `range`; legacy port/protocol/ICMP groups are equivalent to service groups except ICMP groups lack ICMP6 and codes | ASA-OBJ | "A service object group includes a mix of protocols, if desired." | MEDIUM | `neq N` -> two ranges; `lt N` -> 1..N-1; `gt N` -> N+1..65535; `tcp-udp` -> both families; `port-object` shape not found on an official page: parse only after the measurement shows it, else UNSUPPORTED |
| A0-ASA-15 | Port and protocol literals (`www`, `https`, `domain`, `esp` ...) map to the numbers in Cisco's tables; `domain` (not `dns`) is DNS | ASA-PORTS ("Table 6. Protocol Literal Values", "Table 7. Port Literal Values") | "The following table lists the protocol literal values and port numbers; either can be entered in ASA commands." / "To assign a port for DNS access, use the domain literal value, not dns." | HIGH | the parser embeds both tables, transcribed from the page with its URL and read date; a literal not in the tables is unresolved and counted |
| A0-ASA-16 | Identity firewall users/groups and TrustSec security groups in ACEs | ASA-OBJ (groups listed: network, service, user, security) | - | MEDIUM (existence) | user / security-group criteria -> UNKNOWN (as CP access roles without an amendment) |

## 10. Cisco ASA: NAT and packet order

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-ASA-17 | NAT has three sections: section 1 manual NAT, first match in configuration order; section 2 network-object NAT ordered by quantity of real addresses (smallest first), then IP number (lowest first), then object name (alphabetical); section 3 manual rules (`after-auto`), first match | ASA-NAT | "Applied on a first match basis, in the order they appear in the configuration." / "Quantity of real IP addresses-From smallest to largest." / "the IP address number is used, from lowest to highest" / "the name of the network object is used, in alphabetical order." | HIGH, including "section 2 rules are applied in the following order: static rules. dynamic rules." (verified 2026-11-14) | NAT evaluator orders section 2 static before dynamic, then by these keys |
| A0-ASA-18 | Order of ACL check, NAT un-translation and route lookup for a new connection on 9.x | ASA-INTRO lists tasks without an order; ASA-NATREF says "the ASA untranslates the destination address according to the NAT rule"; ASA-PF82 (8.2, pre-8.3 NAT model) gives ACL then translation then route | - | **UNKNOWN** (9.x) | A0-ASA-6 makes the ACL independent of the NAT order for matching (real addresses); egress interface choice after un-translation stays ASSUMPTION; corroborate with `packet-tracer` (A6) |

## 11. Cisco ASA: contexts and configuration text

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-ASA-19 | Each context has its own configuration; `changeto context <name>` moves between them; the system configuration has no network interfaces or settings of its own; the mode is not stored in the configuration file | ASA-CTX | "For each context, the ASA includes a configuration that identifies the security policy, interfaces" / "The system configuration does not include any network interfaces or network settings for itself" / "The context mode (single or multiple) is not stored in the configuration file" | HIGH | a multiple-context unit's stored text is not its policy; the existing collector refuses it; estate count 0 |
| A0-ASA-20 | `show running-config` omits defaults (`all` shows them); `more system:running-config` shows passwords in plain or encrypted form | ASA-CLI | "If you specify all, then all default settings are shown as well." / "Passwords are shown in plain text or in encrypted form if you have a master passphrase enabled." | HIGH | an absent command means its default (e.g. no `same-security-traffic` = disabled, A0-ASA-5); the parser keeps only policy fields, never secret lines |

## 12. FortiGate: firewall policy

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-FG-1 | Policies are checked in sequence (list) order, not policy-ID order; `move` reorders by ID | FG-POL | "By Sequence displays policies in the order that they are checked for matching traffic without any grouping." / "Policies can then be moved by their policy ID before or after another specified policy ID." | MEDIUM | rule order = position in the stored `config firewall policy` block; that `show` prints sequence order is UNKNOWN (no page) -> measured, see design 7.2 |
| A0-FG-2 | First match wins | not stated verbatim | - | MEDIUM (implied by A0-FG-1 and the implicit deny wording) | first match, as PAN; revisit with A6 |
| A0-FG-3 | Traffic matching no policy hits the implicit deny and is dropped | FG-POL | "If no IPv4 policy matches the traffic, then it will match the implicit deny policy and will be dropped." | HIGH | fixed default section with one DENY rule per VDOM; "policy ID 0" naming is UNKNOWN and not used |
| A0-FG-4 | `srcaddr-negate` / `dstaddr-negate` / `service-negate` invert the cell; default disable | FG-CLIPOL | "When enabled srcaddr specifies what the source address must NOT be." | HIGH | `Cell.negated` (same treatment as A0-N1/N2: complement, shadowing only) |
| A0-FG-5 | Defaults: `action` deny, `status` enable, `nat` disable, `*-negate` disable; `schedule` has no default | FG-CLIPOL (7.4.7) | "Policy action (accept/deny/ipsec)." (default deny) | HIGH (7.4.7 page) | an absent `set action` = deny, absent `set status` = enabled; valid only for FortiOS 7.4 (estate) and only if `show` omits defaults (A0-FG-6) |
| A0-FG-6 | `show` (not `show full-configuration`) omits settings at their default value | not found on an official page; repository measurement 2026-09-27 (FORTINET_CONTRACT.md; `FortiGateConfigProcessor` comment: full `system interface` prints settings `show` omits) | - | MEDIUM (repository evidence, one section) | defaults table per FortiOS minor applied to absent lines; an absent setting without a cited default -> UNKNOWN for that rule |
| A0-FG-7 | `action ipsec` | FG-CLIPOL lists it | - | MEDIUM | non-terminal for this model -> rule UNKNOWN |
| A0-FG-8 | With `internet-service enable`, destination address and service are not used | FG-CLIPOL | "If enabled, destination address and service are not used." | HIGH | ISDB rule -> destination/service `DYNAMIC` (no ISDB data collected) |
| A0-FG-9 | srcintf / dstintf are the ingress / egress interface; zones and SD-WAN zones are used as source and destination interfaces; SD-WAN members cannot be used directly | FG-CLIPOL, FG-SDWAN | "Incoming (ingress) interface." / "Zones are used in policies as source and destination interfaces" / "Individual SD-WAN member interfaces cannot be used directly in policies, but they can be moved between SD-WAN zones at any time." | HIGH | `extras.from`/`extras.to` = interface or zone names; a zone matches its member interfaces |
| A0-FG-10 | Zone `intrazone` default deny: traffic between interfaces of one zone needs a policy | FG-ZONE | "Allow or deny traffic routing between different interfaces in the same zone." (default deny) / "The intra-zone blocking acts as a default deny rule and you have to specifically override it by creating a policy within the zone." | HIGH | intrazone allow -> implicit ALLOW for that zone pair; estate count 0 zones |
| A0-FG-11 | Traffic entering and leaving the same interface | not found | - | UNKNOWN | evaluated as an ordinary policy lookup with from = to; differing result -> UNKNOWN |
| A0-FG-12 | Users/groups criteria (`groups`, `users`) authenticate the policy | FG-CLIPOL | "Names of user groups that can authenticate with this policy." | MEDIUM | identity criteria -> UNKNOWN |
| A0-FG-13 | Local-in policies govern traffic to the FortiGate itself and have no implicit deny | FG-LOCAL | "local-in policies control inbound traffic that is going to a FortiGate interface." / "Unlike IPv4 policies, there is no default implicit deny policy." | HIGH | out of the transit model; listed only |
| A0-FG-14 | Proxy policies are separate (`config firewall proxy-policy`) | FG-CLI proxy-policy page (snippet only) | - | LOW | out of scope; counted by the measurement |
| A0-FG-15 | NGFW policy-based mode (per VDOM): traffic matches the SSL Inspection & Authentication policy first, then `config firewall security-policy` | FG-NGFW | "Traffic will match the SSL Inspection & Authentication policy first." | MEDIUM | a VDOM in policy-based mode -> `UNSUPPORTED` for now; estate count 0 |
| A0-FG-16 | IPv4/IPv6 policies are one table since 6.4 (`srcaddr6`/`dstaddr6`); the IP version of sources and destinations must match | FG-CONS (6.4.0) | "IPv4 and IPv6 policy configuration are consolidated in both NGFW profile-based and NGFW policy-based modes." | MEDIUM | one section per VDOM; `*addr6` cells kept, unresolved by the IPv4 engine |

## 13. FortiGate: NAT and packet order

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-FG-17 | Packet order: DNAT before routing, policy lookup on the first packet, SNAT on the exit path | FG-PPP (7.6.0 and 6.4.0 life-of-a-packet guides; no 7.4 page) | "DNAT must take place before routing so that the FortiGate can route packets to the correct destination." | MEDIUM | VIP (DNAT) first, route on the mapped destination, then policy, then SNAT |
| A0-FG-18 | Policies with VIPs have priority over ordinary policies | FG-POL | "Policies with VIPs applied have priority over typical firewall policies." | MEDIUM (meaning for order not explained) | **UNKNOWN for evaluation**: whether a later VIP policy is matched before an earlier ordinary policy covering the same packet is not stated; a lookup where an earlier non-VIP rule and a later VIP rule both match -> UNKNOWN (`FGT_VIP_PRIORITY_UNPROVEN`) |
| A0-FG-19 | A policy whose dstaddr is a VIP matches traffic to the VIP's external address (pre-DNAT) | not stated verbatim | - | UNKNOWN | lookup evaluates the VIP cell as its `extip` (pre-DNAT) and the mapped address in parallel; differing verdicts -> UNKNOWN (same pattern as `CP_ACCESS_NAT_ORDER_UNPROVEN`) |
| A0-FG-20 | `match-vip` (default enable per the 7.4.7 CLI table) lets a policy match packets whose destination a VIP changed; `match-vip-only` restricts to those | FG-CLIPOL | "Enable to match packets that have had their destination addresses changed by a VIP." | HIGH for the default (`enable`, 7.4.7 CLI table, verified 2026-11-14); MEDIUM for the meaning (an unverified 7.4 search snippet says it applies to deny policies only) | a deny policy with `match-vip` -> its destination also matches the mapped address; an accept policy's `match-vip` is not used; contradiction -> UNKNOWN |
| A0-FG-21 | Central NAT: VIPs are not configured in policies; SNAT through `central-snat-map`, top-down, after the security policy | FG-SNAT, FG-DNAT | "When central NAT is enabled, virtual IPs (VIPs) are not configured in the firewall policy." / "FortiGate reads the NAT rules from the top down until it hits a matching rule for the incoming address." / "NAT policies are applied to network traffic after a security policy." | MEDIUM | not used in the estate (0 VDOMs); a central-NAT VDOM -> NAT `UNSUPPORTED` until measured |
| A0-FG-22 | Policy-mode SNAT: `nat enable` with `ippool`/`poolname` | FG-CLIPOL | "Enable to use IP Pools for source NAT." | MEDIUM | NAT extras on the rule; SNAT to the egress interface address when no pool: UNKNOWN (not stated) |

## 14. FortiGate: objects and services

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-FG-23 | Address types: ipmask (default), iprange, fqdn, geography, wildcard, dynamic, interface-subnet, mac, route-tag | FG-ADDR (7.4.4) | "Standard IPv4 address with subnet mask." / "Range of IPv4 addresses between two specified addresses (inclusive)." | HIGH | ipmask/iprange -> RESOLVED; fqdn, geography, dynamic, route-tag -> DYNAMIC; wildcard (non-contiguous) and mac -> UNSUPPORTED; interface-subnet -> resolved from the interface's configured address, MEDIUM |
| A0-FG-24 | `type` defaults to `ipmask`; `subnet` ("IP address and subnet mask of address") defaults to `0.0.0.0 0.0.0.0`, so an ipmask address with no `set subnet` line covers every IPv4 address | FG-ADDR (7.4.4 CLI table, verified 2026-11-14) | "IP address and subnet mask of address." (default `0.0.0.0 0.0.0.0`) | HIGH (defaults), MEDIUM (any-address meaning of a zero mask) | a subnet-less ipmask object resolves to the IPv4 universe on FortiOS 7.4 only, counted by the measurement (the predefined `all` object is the expected case) |
| A0-FG-25 | Group nesting, `none` meaning | not found | - | UNKNOWN | nested groups expanded by the resolver; `all` is resolved through its stored definition (A0-FG-24), never by its name; `none` unresolved |
| A0-FG-26 | Custom services: `tcp-portrange` / `udp-portrange` / `sctp-portrange` (multiple ranges), `protocol` (TCP/UDP/SCTP default, ICMP, ICMP6, IP), `protocol-number`, `icmptype`, `icmpcode` | FG-SVC (7.4.0) | "Multiple TCP port ranges." / "IP protocol number." | MEDIUM | the `dst[-dst][:src[-src]]` range syntax is not on the page: parse only forms the measurement shows, cross-checked against A0-S1 semantics; others UNSUPPORTED |
| A0-FG-27 | Predefined services (`ALL`, `HTTP`, ...) and service groups | not found | - | UNKNOWN | a referenced service with no stored definition -> unresolved; the measurement counts them |

## Sources

Cisco (all cisco.com, pages updated 2026-07 to 2026-10):
- ASA-AR https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/firewall/asa-922-firewall-config/access-rules.html
- ASA-ACL https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/firewall/asa-920-firewall-config/access-acls.html
- ASA-NAT https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/firewall/asa-920-firewall-config/nat-basics.html
- ASA-NATREF https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/firewall/asa-922-firewall-config/nat-reference.html
- ASA-OBJ https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/firewall/asa-922-firewall-config/access-objects.html
- ASA-CTX https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/general/asa-922-general-config/ha-contexts.html
- ASA-FW https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/general/asa-922-general-config/interface-routed-tfw.html
- ASA-INTRO https://www.cisco.com/c/en/us/td/docs/security/asa/asa923/configuration/general/asa-923-general-config/intro-intro.html
- ASA-S https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/S/asa-command-ref-S/sa-shov-commands.html
- ASA-CLI https://www.cisco.com/c/en/us/td/docs/security/asa/asa-cli-reference/A-H/asa-command-ref-A-H/cli-usage.html
- ASA-PF82 https://www.cisco.com/c/en/us/support/docs/security/asa-5500-x-series-next-generation-firewalls/113396-asa-packet-flow-00.html
- ASA-PORTS https://www.cisco.com/c/en/us/td/docs/security/asa/asa922/configuration/general/asa-922-general-config/ref-ports.html

Fortinet (all docs.fortinet.com):
- FG-POL https://docs.fortinet.com/document/fortigate/7.4.7/administration-guide/656084
- FG-CLIPOL https://docs.fortinet.com/document/fortigate/7.4.7/cli-reference/333889629/config-firewall-policy
- FG-SNAT https://docs.fortinet.com/document/fortigate/7.4.3/administration-guide/421028/central-snat
- FG-DNAT https://docs.fortinet.com/document/fortigate/7.4.4/administration-guide/448790/central-dnat
- FG-PPP https://docs.fortinet.com/document/fortigate/7.6.0/parallel-path-processing-life-of-a-packet/364543/parallel-path-processing
  and https://docs.fortinet.com/document/fortigate/6.4.0/parallel-path-processing-life-of-a-packet/86811/packet-flow-ingress-and-egress-fortigates-without-network-processor-offloading
- FG-ZONE https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference/67620/config-system-zone and
  https://docs.fortinet.com/document/fortigate/7.4.4/administration-guide/116821/zone
- FG-VDOM https://docs.fortinet.com/document/fortigate/7.4.0/administration-guide/597696/vdom-overview
- FG-NGFW https://docs.fortinet.com/document/fortigate/7.4.0/administration-guide/243446/ngfw-policy
- FG-ADDR https://docs.fortinet.com/document/fortigate/7.4.4/cli-reference/306021697/config-firewall-address
- FG-SVC https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference/236620/config-firewall-service-custom
- FG-CONS https://docs.fortinet.com/document/fortigate/6.4.0/new-features/815846/consolidated-ipv4-and-ipv6-policy-configuration
- FG-LOCAL https://docs.fortinet.com/document/fortigate/7.4.3/administration-guide/363127/local-in-policy
- FG-SDWAN https://docs.fortinet.com/document/fortigate/7.4.4/administration-guide/942095/sd-wan-members-and-zones
