# Vendor semantics amendment to A0 — shadowing resolution (2026-11-13)

Status: FROZEN — 2026-11-13, PO approval relayed by the PO session (2026-11-13 ~15:20), verbatim: "Onaylıyorum"
("I approve"). Amendment to `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (A0, FROZEN 2026-11-04). Everything the sources below
do not establish stays UNKNOWN.

Scope: the policy shadowing analysis (`PolicyHygiene`, `PolicyNormalization.Resolver.shadowing`) only. Lookup, NAT and
path analysis keep the plain resolver and are unchanged.

Sources read 2026-11-13 (official pages only; community threads and login-only sk articles not used).

| Id | Claim | Source | Quote | Grade | Used as |
|---|---|---|---|---|---|
| A0-N1 | Check Point: a negated Source/Destination cell matches every address except the cell's objects | R80.30 Identity Awareness Admin Guide, "Using Identity Awareness in the Firewall Rule Base" (31 Dec 2020), sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_IdentityAwareness_AdminGuide/138676.htm | "When you negate a Source or Destination in a rule, it means that a given rule applies to all Sources/Destinations ... except for the specified Source/Destination object." | MEDIUM (older page; R82 pages and the API's `source-negate` say nothing more) | complement of the resolved cell |
| A0-N2 | Palo Alto: Negate on source/destination address applies the rule to every address of the zone except the listed ones | PAN-OS 11.1 Web Interface Help, "Building Blocks in a Security Policy Rule" (updated 25 Jun 2026); same on 10.0-10.2 | "Selecting the Negate option will apply the rule to source addresses from the specified zone except for the addresses specified." | HIGH | complement of the resolved cell; zones are compared separately |
| A0-S1 | Palo Alto service source port: a service with a source port matches only packets whose source port is in it; absent = any | PAN-OS 11.1 Web Interface Help, "Objects > Services" (updated 25 Jun 2026) | "Enter the source port number (0 to 65535) or range of port numbers (port1-port2) used by the service." / "The source port is optional." | MEDIUM (matching implied, not stated) | source-port family, exact two-dimensional containment |
| A0-I1 | Check Point ICMP service: type and code as in RFC 792; `icmp-code` defaults to 0 when not given | Management API v2.2 `add-service-icmp` / `add-service-icmp6` (`icmp-code`: default-value 0, not required); SmartConsole R80.30 help "ICMP" | "ICMP Type and Code, as listed in RFC 792." | HIGH for the default; UNKNOWN how a packet's code is matched beyond equality | resolved only when the stored object carries `icmp-code`; without it UNKNOWN (the stored object does not say which code it has) |
| A0-P1 | Check Point TCP/UDP service port: a single port or a range N-M | Management API v2.2 `add-service-tcp` `port`; SmartConsole R80.30 help "TCP, UDP, and SCTP Services - General" | "To specify a port range, place a hyphen between the lowest and highest port numbers, for example 44-55." | HIGH | unchanged (already implemented) |
| A0-P2 | Check Point comparison port forms `>N` / `<N` | not found on any official page read (API v2.2 field data, SmartConsole help, sc1 search) | - | UNKNOWN | stays unresolved |

Engine notes (not vendor claims): a negated IPv4 cell also matches every IPv6 address, since the engine resolves no
IPv6 object (A0-N1/N2 say "all ... except"); a negated `any` matches nothing and stays unresolved; a negated service or
application cell stays unresolved (the protocol universe is not modelled).

Unchanged, with a revisit trigger (PO session decision 2026-11-13): the covering rule the engine reports for a Palo
Alto rule stays the earliest *compatible* (same placement key) prior; an earlier zone-superset prior is consulted only
when no compatible prior covers. Strict first match would report the earlier superset prior, which can turn REDUNDANT
into CONFLICT or the reverse. No such case was observed on the 10 largest stored policies (2026-11-13 comparison with
the independent reference analyser). **Revisit trigger:** the first real-data case where the reference analyser (or a
user) shows an earlier zone-superset rule covering a rule whose reported covering rule is a later compatible one.

Verification: `PolicyHygieneExtendedResolutionTest` (brute force over the full packet space, 300 seeded policies:
exact status and earliest covering rule); the 10 largest stored policies compared with the independent reference
analyser (contradictions 0, counts in the branch's commit messages).
