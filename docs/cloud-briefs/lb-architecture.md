Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/lb-architecture-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: vendor semantics law, network-device command gate, masking law, UNKNOWN over guessing). No access to the live system or devices. Document only.

TASK: an architecture design for adding load balancers to neXus. PO decision D-9 (2026-11-05): the estate has BOTH F5 BIG-IP and Citrix ADC (NetScaler).
Study how existing vendors are integrated:
- capability registry, gate_registry_fixture.yaml and gate migrations;
- inventory/backup/configuration collectors for Check Point, Palo Alto, Fortinet, Cisco ASA, Radware;
- device registry, compliance and path analysis.
Write docs/design/LOAD_BALANCER_ARCHITECTURE_2026_11_05.md (DRAFT) covering:
- the read-only APIs and commands to propose per vendor: F5 iControl REST / tmsh, Citrix NITRO. Cite official docs; every command is PROPOSED and needs PO approval through the command gate;
- the evidence model: virtual servers, pools and members, SNAT/NAT, VLANs/self-IPs, HA state, config backup (UCS / ns.conf) with a sensitive-data handling plan;
- how LBs enter path analysis as hops (VIP to pool translation as a NAT-like hop, with UNKNOWN when persistence or iRules decide);
- compliance hooks;
- phasing (L1 inventory+backup, L2 configuration, L3 path hop);
- risks and open questions.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/lb-architecture-1105, no PR.