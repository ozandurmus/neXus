# PO decision record - 2026-11-14: ACI APIC, Citrix ADC and F5 BIG-IP as read-only topology sources

## Status

RATIFIED - 2026-11-14, PO answers and PO-session decisions relayed by the PO session. This record amends
`PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` by closing its open item D-9.

Design it decides on: `TOPOLOGY_SOURCES_DESIGN_2026_11_14.md` (DRAFT; its section 7 questions Q1-Q13). The design stays
DRAFT: this record decides its open questions, it does not freeze its vendor semantics (step T1) and it approves no
exact device or API literal (each exact literal comes back for approval after the T3 measurement; AGENTS.md "Network
action taxonomy", 2026-09-26 amendment; decision D-7 of the 2026-11-03 record).

## 1. Decision

Cisco ACI APIC, Citrix ADC (NetScaler) and F5 BIG-IP become **topology sources** for path drawing (Trace / path
analysis). neXus does not manage them and keeps no inventory of them. It reads only routing, interface address, VIP
(virtual server -> pool / backend) and ACI service-graph / PBR tables so that a computed path crosses the fabric
redirect and the load balancers correctly. No backup, configuration plane, compliance, lifecycle, system log, HA,
failover or write path exists for them.

**D-9 closed.** D-9 of the 2026-11-03 record ("Phase B load-balancer vendor and Phase C APIC read-only account") is
answered: the load balancers are F5 BIG-IP and Citrix ADC, and the APIC is read with a dedicated read-only account,
all three as read-only topology sources under this record. The managed-device integration proposed in
`LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` (inventory, backup, configuration, compliance) is not pursued.

**Next-hop principle (PO, 2026-11-14).** "ACI may send traffic anywhere; the next hop comes from IP, routes and
contracts." The path engine derives every fabric next hop and every redirect target from the read addresses, routes,
contracts and service-graph / PBR policies. It never takes a redirect target or a next hop from a configured,
typed or uploaded list; such a list, if the team supplies one, is only a corroboration recorded as `MATCH` /
`MISMATCH`.

## 2. PO answers (2026-11-14)

| # | Question (design section 7) | Answer |
| --- | --- | --- |
| Q13 | Approve the literal tables 3.1-3.3 in principle | Approved in principle, verbatim: "Prensipte onay" ("approval in principle"). Exact literals come back for approval after measurement (T3). |
| Q2 | Credentials | The bank opens a separate read-only account per APIC cluster and per load-balancer pair: APIC `read-all`, F5 Auditor, Citrix `read-only` command policy. Password login first. |
| Q4 | TLS trust | Certificates are validated against the bank CA (not trust on first use). |
| Q3 | First sources | One APIC and one load-balancer pair per vendor (one F5 pair, one Citrix pair). |
| Q12 | Endpoint security groups, preferred group, multi-site, DSR, Citrix cluster (CLIP) | Unknown; the PO asks the network team. Until the team confirms, each is treated as possibly in use and every path it could affect is `UNKNOWN`. |

## 3. PO-session decisions (2026-11-14, recorded as such)

| # | Question | Decision |
| --- | --- | --- |
| Q1 | One generic class or APIC-specific tables | One generic topology-source class for all three kinds; it replaces the APIC-specific tables and rights of `ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` §6.4-6.5. |
| Q5 | Read frequency and staleness | Daily in the 06:00 admission window plus manual runs; data stale 24 hours after a missed scheduled read. |
| Q6 | Load-balancer HA pairs | One unit per pair is read; every hop through the pair carries the caveat that sync state was not read. |
| Q7 | Object names | Names are masked: a keyed hash for joins plus a masked display label. Raw names are not stored. |
| Q8 | Retention | The last full (COMPLETE) read per object class is kept; read-run records 90 days. |
| Q9 | aiview | aiview may start topology-source read runs; it sees masked data only. |
| Q10 | Configuration-only routes | Accepted on load-balancer and fabric hops with a caveat (`CONFIG_DERIVED`); revisited after real-environment validation (T9). |
| Q11 | Software versions | The network team reports the APIC, BIG-IP and ADC versions; neXus does not read them. |

## 4. Consequences

- Delivery follows the design's section 6: T1 (vendor-semantics freeze and path-design amendment) and later steps.
  Implementation lanes go to Argon through `scripts/argon_task.py` from a worktree at `origin/main`; the engineering
  session reviews and tests their output (`PO_DECISION_RECORD_2026_10_15_ARGON_WORKER.md`).
- The bank CA certificate (or chain) used for Q4 is an input the network team provides before T3.
- Nothing in this record authorizes a request to any APIC, ADC or BIG-IP. The first request is the T3 measurement run,
  after the PO approves its exact literals.
