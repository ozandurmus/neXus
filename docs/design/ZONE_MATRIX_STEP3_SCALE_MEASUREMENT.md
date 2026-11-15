# Zone matrix delivery step 3: synthetic scale and performance measurement, 2026-11-15

## Status

MEASUREMENT RECORD (evidence, not a contract) - 2026-11-15. Delivery step 3 of `docs/design/ZONE_MATRIX_CONTRACT.md`
(sections 5.10, 9 `ZM-12`, 10.1 item 3 and 11.2, FROZEN 2026-11-13) under
`docs/design/PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md`. Automated synthetic scale verification in
`ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/zonematrix/ZoneMatrixScaleMeasurementTest.java`; counts
only, no device or host contact.

## Method

- **Matrix version fixture (`CompiledVersion`)**: 500 synthetic zones (`Z-SYNTH-1` .. `Z-SYNTH-500`), 200,000 disjoint
  IPv4 `/30` blocks (400 blocks per zone in RFC 6598 shared-address space, matching the `MAX_BLOCKS_PER_VERSION` cap of
  200,000), two named service sets (`web`, `db`) and 1,200 explicit matrix cells (`ALLOW_LISTED`, `ALLOW_ANY`,
  `DENY_ALL`) over a `DENY_ALL` (`HIGH`) version default.
- **Ten largest synthetic policy snapshots**: five Check Point packages (ordered access layer + static NAT rulebase,
  `network` and `group` objects, `access-role` identity objects, recurring `time` constraints, IPv6 documentation
  objects) and five Palo Alto Panorama device-group snapshots (`pre-rulebase` + `Pre NAT rules`, `address` and `group`
  objects, `source-user` and recurring `schedule` constraints, destination-NAT translations, IPv6 documentation
  objects), sized from 2,500 up to 16,500 rules (86,500 rules in total). Each policy includes disabled rules,
  deny-type rules, broad early denies that cover later allow rules (`PolicyHygiene` conflict proof for
  `COVERED_BY_EARLIER_DENY`), NAT rules translating destinations into `DENY_ALL` zones, identity-limited rules,
  time-limited rules and IPv6 `NOT_EVALUABLE` rules.
- **Execution**: steady-state JVM run after single-policy JIT warm-up inside `./gradlew :service:test`
  (`ZoneMatrixScaleMeasurementTest`). Full `PolicyHygiene.analyze` runs synchronously inside each policy evaluation
  with the production work budget (2,000,000 snapshot units, 50,000 per-rule units).

## Budget gates (`ZONE_MATRIX_CONTRACT.md` section 9, `ZM-12`)

| Check | Contract ceiling | Measured | Margin |
| --- | --- | --- | --- |
| Compile 500-zone, 200,000-block version + 1,200 cells | part of run budget | 323 ms | — |
| Largest policy (16,500 rules, Check Point) wall-clock | <= 30,000 ms (30 s) | 1,212 ms (1.21 s) | 24.7x under ceiling |
| `PolicyHygiene` pass inside largest 16,500-rule run | within 30 s run budget | 507 ms (0.51 s); budget not reached | — |
| Additional JVM heap during largest 16,500-rule run | <= 512 MB | 173 MB | 2.9x under ceiling |
| All 10 largest policies sequentially (86,500 rules) | <= 180,000 ms (180 s) | 4,059 ms (4.06 s) | 44.3x under ceiling |
| Rules exceeding per-rule work budget (`UNKNOWN`) | reported, never dropped | 0 across 86,500 rules | — |

Timings vary from run to run on a shared workstation; the verdict counts below are deterministic (ZM-9). A second
run of the same test gave: largest policy 1,038 ms, hygiene 333 ms, extra heap 208 MB, all 10 policies 4,558 ms,
compile 217 ms - every figure far inside the ceilings above.

## Per-policy counts (10 synthetic policies)

| # | Vendor | Rules | Total (ms) | Hygiene (ms) | `VIOLATION` | `COMPLIANT` | `NOT_EVALUABLE` | `UNKNOWN` | `NOT_APPLICABLE` | `COVERED_BY_EARLIER_DENY` | `IDENTITY_IGNORED` | `TIME_IGNORED` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | CP | 16,500 | 1,212 | 507 | 9,925 | 3,034 | 359 | 0 | 3,182 | 1,534 | 563 | 357 |
| 2 | PAN | 14,000 | 514 | 250 | 8,278 | 2,686 | 304 | 0 | 2,732 | 1,293 | 360 | 298 |
| 3 | CP | 12,000 | 525 | 231 | 7,174 | 2,195 | 259 | 0 | 2,372 | 1,101 | 406 | 259 |
| 4 | PAN | 10,500 | 361 | 172 | 6,168 | 2,004 | 226 | 0 | 2,102 | 955 | 268 | 222 |
| 5 | CP | 9,000 | 392 | 164 | 5,338 | 1,637 | 193 | 0 | 1,832 | 808 | 303 | 193 |
| 6 | PAN | 7,500 | 228 | 112 | 4,358 | 1,420 | 160 | 0 | 1,562 | 664 | 191 | 159 |
| 7 | CP | 6,000 | 243 | 103 | 3,502 | 1,080 | 126 | 0 | 1,292 | 519 | 200 | 125 |
| 8 | PAN | 5,000 | 158 | 76 | 2,850 | 934 | 104 | 0 | 1,112 | 423 | 124 | 105 |
| 9 | CP | 3,500 | 140 | 58 | 1,988 | 619 | 71 | 0 | 822 | 280 | 113 | 73 |
| 10 | PAN | 2,500 | 272 | 247 | 1,387 | 462 | 51 | 0 | 600 | 190 | 61 | 51 |

Aggregate fleet headline across the 10 synthetic policies: 50,968 distinct violating `(policy source, rule uid)` pairs
across 746 violated zone pairs.

## Section 11.2 findings from step 3

1. **Hygiene reachability within the run budget**: `PolicyHygiene.analyze` completes in 507 ms on the 16,500-rule
   synthetic Check Point policy and 250 ms on the 14,000-rule synthetic Panorama policy without exhausting the
   2,000,000-unit snapshot budget, leaving over 95 % of the 30-second per-policy ceiling unused. No separate hygiene
   cache is required before step 4's durable run pool.
2. **Partition cap (`MAX_BLOCKS_PER_VERSION = 200,000`)**: compiling 200,000 `/30` blocks across 500 zones into merged
   non-overlapping elementary segments takes 323 ms, and binary-search interval intersection adds ~705 ms across 16,500
   rules including NAT dual-view expansion. The 200,000-block cap established in step 1 remains well inside the
   30-second and 512 MB ceilings.
3. **Real-environment boundary**: per `AGENTS.md` ("Automated validation != real-environment validation"), these numbers
   prove algorithmic and heap scaling on synthetic fixtures at the contract's scale ceiling; counts-only execution on
   live stored policies in the pod (including empirical estate frequencies of identity-, time-limited and covered rules)
   remains a real-environment verification step upon deployment.
