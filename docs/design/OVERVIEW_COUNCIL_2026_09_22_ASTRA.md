# A. Four-seat panel

## 1. UI/UX designer — enterprise dashboards, Material 3

1. **What needs attention now?** Replace passive totals with exception cards that answer “where should I click first?”
2. **How trustworthy and current is each figure?** Show an evidence timestamp per plane and `UNKNOWN` or `READ FAILED`, never a misleading zero.
3. **What changed since the last collection?** Configuration changes, member DIFFs, failures, and missing backups matter more than static inventory totals.
4. **Am I looking at devices, clusters, or virtual systems?** Keep these denominators visibly separate; 105 devices and 39 clusters are not interchangeable.
5. **Where does each number lead?** Every card must open the relevant screen with the displayed filter already applied.

## 2. Network Security Deputy General Manager — GMY

1. **What is our evidenced exposure today?** The board needs critical deficiencies and evidence gaps, not an unexplained framework percentage.
2. **How much of the estate is actually assessed?** Assurance must always appear beside evidence coverage and evaluated-estate coverage.
3. **Can the estate be recovered?** Report protected backup targets, missing/overdue targets, and policy exceptions.
4. **Which HA units are not demonstrably ready?** Separate not-ready from unknown; neither permits or initiates failover.
5. **What can honestly be reported to BDDK?** A financial baseline is not a BDDK attestation without an explicit, governed control mapping.

## 3. Network Security Manager — Müdür

1. **What failed or is still running from the last 24 hours?** This is today’s operational workload and possible SLA exposure.
2. **Which devices lack current evidence?** Collection gaps must become a bounded worklist with reasons.
3. **Which configurations changed or disagree across cluster members?** These require validation before they become incidents.
4. **Which backup targets missed their policy?** Separate never-backed-up, overdue, failed, and retention exceptions.
5. **Which clusters require manual readiness review?** Show the latest stored preflight verdict and its age, without suggesting automatic action.

## 4. Firewall administrator / operator

1. **What failed overnight, on which masked target, and why?** I need job type, terminal reason, and time—not a lifetime job count.
2. **What configuration changed?** Show masked device or cluster, section, change state, and collection time.
3. **Which cluster members differ?** Surface the section-level DIFF and open the comparison directly.
4. **Which backups are missing or overdue?** Take me directly to the affected target and its stored archive history.
5. **Which version or identity facts are exceptional or unknown?** Show version/take/content distributions, but do not call anything outdated without an approved baseline.

# B. Converged Overview layout

**Decision:** Overview becomes an exception-and-evidence screen, not a census. Use a Material 3 attention strip followed by compact tables; no gauges or decorative charts.

| Order | Section and exact content | Definition and window | Stored source | Click action |
|---|---|---|---|---|
| 1 | **Evidence context bar** — latest stored timestamp for Inventory, Configuration, Compliance, Backup, Jobs, and HA; `aiview` badge | Maximum persisted evidence timestamp for each plane. No denominator. Missing evidence is `UNKNOWN`; read failure is `READ FAILED`. | Collection/job timestamps and latest stored assessment records | Open Jobs filtered to that collection type |
| 2 | **Needs attention** — `172 critical deficiencies`; `428 data gaps`; failed jobs in 24h; `19 backup targets without an artifact`; HA not-ready/unknown out of 39 | Critical: latest `CRITICAL + FAIL` evaluations / all assigned critical evaluations. Gaps: `DATA_UNAVAILABLE` / all assigned evaluations. Jobs: `FAILED` / all jobs submitted in rolling 24h. Backup: targets without any stored artifact / 102 configured targets, currently `19/102` (`83/102` protected). HA: latest exception or missing verdict / 39 clusters. | Compliance results; job state/outcome/reason; backup target and archive records; latest HA preflights | Open the corresponding screen with severity/state/status filter applied |
| 3 | **Morning exceptions** — four lists, maximum five rows each: failed jobs; configuration changes/member DIFFs; backup exceptions; HA exceptions | Jobs: newest failures in rolling 24h. Configuration: latest change state per device and latest comparable member DIFF per cluster. Backup: never-backed-up or overdue according to its stored schedule. HA: latest non-ready or missing preflight. Each row shows masked entity, status, reason, and evidence time. | Jobs; configuration collections and section DIFFs; schedules and archive listing; HA readiness | Open the exact job, configuration section comparison, backup history, or cluster readiness detail |
| 4 | **Compliance and evidence** — assurance ≈29%; coverage 82%; evaluated devices `E/105`; 172 critical deficiencies; 428 gaps; framework table for CIS, PCI-DSS 4.0.1, NIST 800-53, Financial Baseline | Device assurance = `PASS / assigned controls`, including unavailable controls in the denominator; fleet value is the mean across evaluated devices. Coverage = `(PASS + FAIL) / assigned controls`. Each framework row shows `PASS / FAIL / DATA_UNAVAILABLE` and `PASS / total mapped evaluations`. Latest evaluation only. | Stored compliance evaluations and framework mappings | Open Compliance filtered by framework; deficiency/gap counts open the matching control list |
| 5 | **Recovery and resilience** — `83/102` backup targets protected; 19 missing; overdue count; retention exceptions; HA verdict distribution; cluster member DIFF coverage | Protected = distinct configured targets with ≥1 stored artifact / 102. Overdue = no successful backup covering the latest scheduled due time / scheduled targets. HA = latest verdict counts / 39 clusters. DIFF = clusters with ≥1 unequal setting / clusters with two comparable member snapshots; non-comparable clusters remain `UNKNOWN`. | Backup schedules, retention policies, archive listings and jobs; HA preflights; configuration member comparisons | Open Backup filtered to missing/overdue, HA filtered to verdict, or Configuration filtered to cluster DIFF |
| 6 | **Fleet and platform evidence** — 105 devices, 39 clusters; 65 Check Point and 40 Palo Alto; virtual-system count; model/software buckets; each Check Point jumbo take bucket; PAN app/threat/AV/WildFire/URL version buckets; unknown-field counts | Device/vendor counts use active stored device records. Clusters use stored operational-unit identities, never inferred names. Version distributions use the latest stored identity record per physical device. No time range; show the evidence timestamp. | Inventory and platform identity | Open Inventory with vendor/model/version/take/content filter applied |

Performance boundary: load only aggregates and the bounded exception rows from stored tables, in parallel. Do not load raw configurations, archive contents, or per-device compliance details until clicked. The screen performs no collection and issues no device command.

# C. Five metrics not to show

1. **“~1,500 jobs on record”** — a lifetime stock count says nothing about current workload; show running and failed-in-24h instead.
2. **“83 of 105 devices backed up”** — 105 is the wrong denominator; only 102 are configured backup targets.
3. **Observed compliance percentage alone** — excluding unavailable evidence makes missing data appear healthier; assurance and coverage must stay paired.
4. **A single fleet health score or traffic-light** — it hides whether the problem is compliance, evidence, backup, HA, or collection failure.
5. **“Outdated devices” or average uptime** — neither is defensible without approved version/content baselines, lifecycle dates, and maintenance policy.

# D. Missing data and minimal first release

| Desired answer | Data neXus does not yet collect |
|---|---|
| Team workload, SLA breach, MTTA/MTTR | Owner/team, priority, acknowledgement time, due time, escalation policy |
| Authorized change versus unexpected change | Change-ticket ID, approval state, maintenance window, implementing actor |
| BDDK posture or attestation | Governed BDDK requirement mapping, applicability, reporting period, evidence acceptance, sign-off |
| “Outdated” software, hotfix, or PAN content | Approved target baselines, exceptions/grace periods, and supported/EOL lifecycle feed |
| Incident or service impact | Device-to-service dependency map, service criticality, incident linkage |
| Board trend | Persisted comparable compliance, backup, and HA summary snapshots with stable definitions |

**Minimal first release:** ship sections 1–6 using existing stored evidence, but omit SLA, ownership, BDDK claims, trends, incident impact, and “outdated” judgments. Keep all names masked, cap each exception list at five rows, and lazy-load detail after click.

SESSION CLOSE — Design completed read-only. No source, configuration, project state, Git, deployment, device, or production database was changed; no live system was contacted.

