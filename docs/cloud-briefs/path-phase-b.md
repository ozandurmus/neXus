Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/path-phase-b-architecture-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: vendor semantics law, network-device command gate, masking law, UNKNOWN over guessing). No access to the live system or devices. Document only.

TASK: an architecture design for path analysis Phase B.
Read docs/design/PATH_ANALYSIS_DESIGN.md, PATH_ANALYSIS_VENDOR_SEMANTICS.md (both frozen for Phase A), the A6 proposal and the engine under ui2/service/.../pathanalysis.
Write docs/design/PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md (DRAFT) proposing the next phase:
- UDP and ICMP queries;
- the return-path symmetry check;
- chained NAT across multiple firewalls;
- VRF/VSX context selection with an ingress hint UX;
- load balancer hops (see D-9: F5 and Citrix);
- 'what-if' rule changes (offline, never pushed);
- path results as evidence in reports;
- batch queries (CSV of flows) with rate limits;
- performance and caching.
For each item give the semantics needed (cite vendor docs or mark UNKNOWN), data that already exists vs needs new collection, command-gate needs, effort, risk, and order.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/path-phase-b-architecture-1105, no PR.