# Cisco ASA and FortiGate policy shapes: step-0 measurement, 2026-11-14

## Status

MEASUREMENT RECORD (evidence, not a contract) - 2026-11-14. Step 0 of `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md`
(section 7), run under `PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` items 1-2 ("Evet, başla"). No
device contact and no new command: the harness read only the stored configuration artefacts. No configuration text
was read by a person or an agent; the only output is counters whose every field matches `[a-z0-9_.]+`.

## 1. What ran

- **Harness:** `scripts/measurement/asa_fgt_policy_shape/` (`AsaFgtShapeMeasure.java`, `measure_run.sh`,
  `run_in_pod.sh`, `show_safe.sh`, and Cisco's port and protocol literal tables transcribed from the 9.22 guide). It is
  compiled against the pod's own `service.jar` and run in `ui2-service` with the pod's artefact-store key.
- **Input:** the latest `device_configuration_run` with `read_kind = show_configuration` for every enrolled, enabled
  ASA and FortiGate gateway (12 ASA, 9 FortiGate; artefacts collected 2026-11-13), decrypted in memory and discarded.
  Devices became ordinals (`asa_01` ...), VDOMs `v0`, `v1` ...; names and addresses never left the JVM. Port literals
  were emitted only when they are in Cisco's Table 7.
- **Runs:** r1, then r2 (fix: the address after `host`, `object`, `object-group`, `interface`, `user-group` or
  `time-range` was read as a mask - r1's 6,084 "non-contiguous masks" were this bug; new: ACE counts inside bound ACLs,
  ACL references outside `access-group`, per-family pair comparison), then r3 (remarks and `same-security-traffic` as
  their own families). Every run ended `done`, `filtered_lines 0`, no unsafe field dropped. No configuration collection
  ran between the runs (database check).
- Engineer-side tests: the harness was first run locally on synthetic configurations (documentation addresses only).

## 2. Cisco ASA (12 units: 9.18 x8, 9.22 x4)

| Fact | Count |
|---|---|
| Configuration lines | 2,955 - 44,025 per unit (264,677 total) |
| ACL names | 1,827 (26 - 567 per unit) |
| Extended ACEs, all ACLs | 114,395 (permit 113,999, deny 396); standard 3 |
| ACL remarks | 47,473 |
| `access-group ... in interface` | 67 (2 - 10 per unit); `out` 0; `global` on 3 units; `control-plane` 0 |
| ACLs bound by `access-group` | 67; bound to more than one interface: 3 |
| **ACEs in bound ACLs** | **80,431** (largest single bound ACL 9,072 ACEs) |
| Bound ACEs with `user-group` / `user` (identity firewall) | 6,592 (8.2 %) on 12 of 12 units / 3 |
| Bound ACEs with `time-range` | 1,271 (1.6 %) on 10 units |
| Bound ACEs `inactive` | 1 |
| Bound ACEs with `object-group` / `object` | 20,655 / 4,348 |
| Bound ACE protocols | tcp 71,745; udp 5,396; ip 1,097; icmp 681; gre 42; object-group 1,463; object 7 |
| Port tokens in bound ACEs | numeric 35,603; Cisco literal in Table 7 42,893; **word not in Table 7: 96 (3 units)** |
| Masks in bound ACEs | contiguous 24,307; **non-contiguous 15** (3 units) |
| ACLs used elsewhere | `crypto map ... match address` 1,020 (5 units); `vpn-filter` 94; split tunnel 77; `class-map` 30 |
| ACLs referenced by none of the scanned uses | 539 names, 8,007 ACEs (scan covers access-group, crypto map, vpn-filter, split tunnel, class-map, route-map, capture, webvpn filter, aaa; other uses are not excluded) |
| Network objects | host 5,078; range 874; subnet 502; **fqdn 208 (4 units)** |
| Object groups | network 4,606; service 784 (legacy tcp 179, udp 34, tcp-udp 18); nesting depth max 2; cycles 0; undefined references 0 |
| Object-group children | network-object 36,983; service-object 4,019; port-object 973; group-object 67 |
| `names` enabled | 9 units; `name` lines 633 on 3 units |
| NAT section 1 (manual, before auto) | 5,471: static 5,348, dynamic 3,546 (lines may count both), twice with `destination` 5,048, `no-proxy-arp` 943, interface PAT 677, `any` interface 252, with `service` 376 |
| NAT section 2 (object NAT) | 307 on 8 units: static 164, dynamic 143 |
| NAT section 3 (`after-auto`) | 0 |
| Interfaces with `nameif` | 77 (2 - 12 per unit); distinct security levels 65; interfaces sharing a level with another: 24 on 10 units |
| `same-security-traffic permit` | intra-interface on 10 units, inter-interface on 3 |
| Transparent mode, ASA traffic zones, IPv6 interface addresses, legacy `ipv6 access-list` | 0 |
| HA pairs (5) | every policy family identical on both members (ACLs, access groups, objects, object groups, NAT, names, interfaces, same-security); one pair differs **only in ACL remark lines** |

Notes:
- r1's whole-text hash (all families in file order, remarks included) reported two pairs as different; r3's per-family
  comparison shows one of them differs only in remarks and the other in nothing per family. The cause of r1's second
  difference was not isolated (cross-family order is the remaining candidate). The pair rule should compare the
  policy families, not the whole text, and ignore remarks.
- "Child lines outside tracked blocks" (17,494) are children of non-policy blocks (crypto, VPN, policy maps); not a
  parser gap.

## 3. FortiGate (9 units, FortiOS 7.4; 13 VDOMs: 7 single-VDOM units, 2 units with 3 VDOMs)

| Fact | Count |
|---|---|
| Configuration lines | 14,443 - 80,796 per unit (259,143 total) |
| Firewall policies | 4,426 in 11 VDOMs (2 VDOMs have none); max 1,399 in one VDOM |
| `uuid` present | 4,426 of 4,426 |
| `set action` absent (default deny, A0-FG-5) | 36 in 9 VDOMs; accept 4,390; explicit deny 0 |
| `status disable` | 0 |
| Printed order vs policy id | **not ascending in 11 of 11 VDOMs with policies**: printed order is not id order (supports A0-FG-1, sequence order) |
| `srcintf any` / `dstintf any` | 4,310 / 4,306 policies in 4 VDOMs |
| Identity criteria (`groups`/`users`/`fsso-groups`) | 672 policies in 4 VDOMs |
| `schedule` not `always` | 40 in 4 VDOMs |
| Negation, ISDB, application criteria, `nat enable`, `ippool` | 0 |
| `match-vip` set | `disable` on 28 policies (8 VDOMs) |
| VIPs, VIP groups, IP pools, central SNAT rules, zones | 0 |
| Local-in, proxy, security (NGFW policy-based), DoS, legacy policy6 tables | 0; multicast policies 19 (out of scope) |
| Addresses | 6,573: type absent (default ipmask) 5,502; iprange 1,017; dynamic 36 (3 per VDOM); fqdn 18; others 0 |
| ipmask without `subnet` (default `0.0.0.0 0.0.0.0`, A0-FG-24) | 39 = 3 per VDOM (the predefined objects) |
| Masks | contiguous 5,463; non-contiguous 0 |
| Address groups | 100; nesting depth max 2; cycles 0; IPv6 addresses 27 |
| Custom services | 2,255: protocol absent (default TCP/UDP/SCTP) 2,142; IP 43; ICMP 43; ICMP6 14; ALL 13 (1 per VDOM) |
| Port range forms | `dst` 2,065; `dst-dst` 410; `dst:src` 59; other 0; multiple ranges in one service 130 |
| Service groups | 74 |
| Services referenced by policies but not defined | 0 (predefined services are stored entries) |
| Modes in stored `system settings` | none of `central-nat enable`, `ngfw-mode policy-based`, `inspection-mode proxy`, `opmode transparent` in any VDOM |
| HA | 1 unit carries a pair claim; its peer is not enrolled |

## 4. Consequences for the build (carry into the parser and mapper briefs)

1. **No VIP, central NAT, policy NAT, zone or ISDB handling is needed for the current FortiGate estate.** The VIP
   UNKNOWNs (A0-FG-18/19) have no exposure today; the parser still recognises and counts those blocks and marks a
   snapshot `UNSUPPORTED` for them if they appear.
2. **FortiGate defaults matter in a small, cited set:** absent `action` (36 policies, deny), absent `type` (ipmask),
   absent `protocol` (TCP/UDP/SCTP), the predefined subnet-less objects (`0.0.0.0 0.0.0.0`). All four are covered by
   cited 7.4 defaults.
3. **FortiGate rule order = printed order** (never sort by id).
4. **ASA identity firewall is significant:** 8.2 % of bound ACEs carry `user-group`. They stay UNKNOWN (A0-ASA-16)
   unless a later amendment allows the address cells as an upper bound for "passing over" (as the CP access-role
   amendment did). Candidate PO question, not decided here.
5. **ASA ACLs outside `access-group` dominate by name** (1,760 of 1,827) but not by size of bound policy (80,431 ACEs
   bound). Only bound ACLs enter the access model; crypto/VPN/split-tunnel ACLs are listed, not evaluated; 539 ACLs
   referenced by none of the scanned uses are hygiene candidates (`ACL_NOT_REFERENCED`), worded as "no reference found
   by the scanned uses".
6. **ASA NAT is mostly manual twice NAT in section 1** (5,048 with a destination clause); section 3 is unused;
   section 2 is small. The section-2 order rule (A0-ASA-17) still has to be implemented.
7. **Small UNSUPPORTED residue:** 15 non-contiguous masks and 96 port words outside Table 7 in bound ACEs; 208 FQDN
   objects (DYNAMIC).
8. **Scale:** the largest bound ACL has 9,072 ACEs and one unit 16,972 bound ACEs; the parser and resolver must be
   linear, and the lookup must not expand object groups per query.
9. **HA de-duplication (decision record item 6):** compare per policy family, ignore remarks; all 5 pairs then match.
