# CP discovery in Java: management-plane candidate enumeration under the frozen discovery contract

status: planned · target: CP_AND_VSX_DISCOVERY_CONTRACT.md; gate lifted 2026-09-13 for discovery only

PARTIAL as of 2026-09-13, status deliberately NOT changed. The discovery DOMAIN CORE is merged: 26 files in ui2/platform-core (com.securityexpert.nexus.ui2.discovery.cp) implementing contract sections 4, 5, 6 and 6.4, with sections 9 checks 8/10/12/13 as real JUnit tests over synthetic fixtures. What remains is the TRANSPORT, contract section 3 (T-1..T-7) and section 7.4's connection-table channel state -- deliberately split out because FB-2's field binding can only be confirmed against the Product Owner's live management server, so every binding entry is currently UNVERIFIED at one isolated site. This item stays open for that transport.

Delivered by movement 0163 (Check Point discovery in Java, management-plane candidate enumeration).
