**Recommendation: make Overview an executive assurance screen, led by where known failures and evidence gaps are concentrated.** Give it one dominant visual, clear recovery evidence, and progressively smaller supporting charts. Do not publish an overall security score yet.

This is a **DRAFT design proposal**, using four analytical perspectives—not an independently convened external-model panel. Screenshot figures below are supplied observations, not a new live assessment.

## Panel perspectives

### 1. Enterprise dashboard / data-visualisation designer

The first attempt changes the presentation more than the information hierarchy. Four equal rings give four unequal measures the same authority: archive possession, control results, member differences, and version distribution. Their percentages invite comparisons that have no valid meaning.

Use Material 3 surfaces and typography, with Tufte/Few-style restraint: aligned position and length for comparison, direct labels, quiet backgrounds, and colour reserved for meaning. The dominant visual should answer **“Where are the known problems and blind spots?”** Rings, decorative topology, and hardware donuts do not answer that question efficiently.

### 2. Deputy general manager, network security

The screen must distinguish:

- What is demonstrably failing?
- How widely does it affect the estate?
- What evidence supports recovery?
- How much of the assessment remains unknown?
- Is the position improving?

The last question needs history that neXus does not yet retain. Do not manufacture it. Likewise, a “financial baseline” must not be labelled “BDDK compliant” without an approved regulatory mapping, applicability assessment, and evidence requirements.

### 3. Network security director / manager

Every executive figure needs a useful next click: affected targets, failed controls, missing archives, or the compared configuration sections.

A cluster difference is a review signal, not proof of failed redundancy. A configuration change is an observation, not proof of an unauthorised change. The director needs those distinctions preserved so the dashboard creates useful work instead of false escalations.

### 4. Market analyst

The transferable market pattern is **summary → concentration → prioritised exceptions → filtered evidence**. Trends become valuable when their history and scope are trustworthy.

Do not copy threat maps, attack counts, business-risk scores, or predicted outages from platforms that ingest telemetry neXus does not have. Product dashboards also vary by version, module, and licence; the comparison below describes documented capabilities, not a universal layout.

## A. What sector leaders show—and what neXus should borrow

| Product | Documented landing/dashboard pattern | Take for neXus | Avoid / qualification |
|---|---|---|---|
| **Tufin SecureTrack** | Opening dashboard covers critical policy violations, cleanup, audit, changes, and trends; widgets open filtered detail. [Official documentation](https://forum.tufin.com/support/kc/tos5/Content/Suite/dashboard.htm) | Exception concentration, clear scope, direct drill-down, later daily trends. | Unused-rule, permissiveness, and authorised-change measures require evidence beyond today’s list. |
| **AlgoSec Firewall Analyzer** | Default summary includes devices with the most severe risks and rating trends; framework dashboards show compliance distributions and trends. [Official documentation](https://techdocs.algosec.com/en/asms/a33.00/asms-help/content/afa-ug/working-with-dashboards.htm) | Show affected populations and where failures concentrate. | Do not relabel configuration-control failures as measured attack exposure. |
| **FireMon Security Manager** | Enterprise Overview presents device/group performance, updates, and rule metrics; Policy, Compliance, and Change have their own dashboards. [Official documentation](https://docs.firemon.com/feature/Content/SECURITY_MANAGER/DASHBOARD/Overview/About_the_Overview_Dashboard.htm) | Executive overview with specialised destinations; preserve domain boundaries. | Do not bring the entire operational console onto the landing page. |
| **Skybox** | Historical material emphasised compliance and exposure visibility. Treat it as a historical comparator: Tufin reports Skybox ceased operations on 24 February 2025. [Official notice](https://www.tufin.com/tufin-expresspath-program) | The separation of exposure, compliance, and remediation is useful. | I could not verify a current supported landing page; do not present Skybox as a current UI benchmark. |
| **BackBox** | Backup/recovery documentation emphasises verification and backup dashboard widgets. [Official solution brief](https://backbox.com/wp-content/uploads/BackBox_SolutionBrief_BackupAndRestore.pdf) | Archive coverage, age, validation evidence, missing protection. | I have not verified its current default widget arrangement. Archive presence alone does not establish recovery success. |
| **Check Point Infinity / SmartConsole** | Check Point AIOps documents monitored assets and alerts; SmartConsole documents gateway monitoring and operational statistics. [AIOps](https://sc1.checkpoint.com/documents/Infinity_Portal/WebAdminGuides/EN/Events-Admin-Guide/Events_AIOps/Admin-Guide/Topics/infinity-aiops/aiops-introduction.html), [SmartConsole guide](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_Multi-DomainSecurityManagement_AdminGuide/CP_R81_MultiDomain_SecurityManagement_AdminGuide.pdf) | Scope-aware summaries and evidence-linked exceptions. | “Infinity” encompasses multiple products; there is no single layout I can honestly claim represents them all. neXus lacks equivalent threat telemetry. |
| **Palo Alto Strata Cloud Manager / AIOps** | Command Center combines health, security, traffic/application context, and contextual navigation. Features depend on subscriptions and telemetry. [Official documentation](https://docs.paloaltonetworks.com/strata-cloud-manager/getting-started/command-center) | Strong visual hierarchy, visible time context, focused drill-down. | Do not copy traffic flows, threat protection, or experience metrics without their underlying data. |
| **FortiManager / FortiAnalyzer** | FortiManager exposes management, configuration, connectivity, and firmware widgets; FortiAnalyzer offers log-driven security dashboards and trends. [FortiManager](https://docs.fortinet.com/document/fortimanager/latest/administration-guide/880941/dashboard), [FortiAnalyzer](https://www.fortinet.com/products/management/fortianalyzer) | Separate estate/configuration oversight from security-event monitoring. | Manager CPU, log throughput, and job execution belong in Operations. |
| **ServiceNow SecOps / IRM** | Executive views combine security domains and risk/compliance reporting; aggregation rests on underlying records and defined scoring. [SecOps](https://www.servicenow.com/docs/r/security-management/vr-unified-CISO-dashboard.html), [Risk and Compliance](https://www.servicenow.com/docs/r/security-management/grc-ced-risk-compliance-db-reports.html) | Traceability, explicit scope, eventually ownership and remediation progress. | Business-service exposure, accepted risk, ownership, and overdue remediation require **NEW DATA**. |

**Convergence:** borrow the decision structure and evidence navigation. The visual design should express what neXus can prove.

## B. Proposed screen, top to bottom

### Seven blocks at 1440 × 900

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ 1  OVERVIEW · scope · evidence age distribution · refresh status         │
├───────────────────────────────────────────────┬─────────────────────────┤
│ 2  CONTROL FAILURES AND EVIDENCE GAPS           │ 3  BACKUP EVIDENCE      │
│    Dominant comparison by firewall vendor     │    98 / 110 archived   │
│    Critical failures | unavailable | coverage  │    12 without archive  │
│    Clearly separate affected-target counts    │    Age + validation    │
├────────────────────────────────────┬──────────┴─────────────────────────┤
│ 4  FRAMEWORK RESULTS               │ 5  MOST WIDESPREAD CRITICAL        │
│    Four pass/fail/unavailable bars │    CONTROL FAILURES                │
│    Real counts + assessed coverage │    Ranked affected-target bars    │
├────────────────────────────────────┼────────────────────────────────────┤
│ 6  CONFIGURATION CHANGES           │ 7  CLUSTER MEMBER COMPARISONS       │
│    Changed / unchanged / unknown   │    Differences / none / unknown    │
│    Since each target's prior read │    Configuration evidence only     │
└────────────────────────────────────┴────────────────────────────────────┘
```

Reserve approximately 56 px for application chrome, then 76 / 232 / 224 / 184 px for these four rows, with 16 px gaps. This leaves breathing room without shrinking labels into dashboard wallpaper.

### Shared metric contract

- **Snapshot time `t`:** all cards use one coherent stored-data snapshot. Browser refresh time is separate from evidence observation time.
- **Scope:** use existing opaque entity IDs. Devices, firewall targets, virtual systems, clusters, and backup targets are distinct populations; never add them together.
- **Time:** show each card’s source observation range, not only the newest record. Missing timestamps display `UNKNOWN`. Existing job metadata may supply provenance without exposing job details.
- **Unknowns:** missing evidence is never a pass, an unchanged configuration, or a healthy cluster. Zero denominators display `N/A`.
- **Colour:** red = evidenced critical failure or explicit protection gap; amber = warning requiring a defined basis; green = demonstrated pass within the named measure; blue = neutral observation; grey with hatching = unavailable evidence.
- **Interaction:** every chart segment opens the corresponding filtered list with the same scope and snapshot context. Names remain masked.
- **No hidden thresholds:** absent an approved target, display counts and distributions without an invented red/amber/green score.

### Block 1 — Scope and evidence currency

**Visual:** compact heading plus a thin, directly labelled inventory-age distribution.

**Definition:** for enrolled devices expected to receive inventory reads, let `D` be the count at `t`. Partition by latest successful inventory observation:

- `<24 h`
- `24–<72 h`
- `≥72 h`
- `never`

Each segment is `devices in bucket / D`. Unknown or invalid timestamps form a separate unknown category. Registered but unenrolled devices appear as a separate count.

**Source:** Devices and Inventory.

**Time:** age is calculated against `t`; display the dashboard snapshot time explicitly.

**Colour:** blue for recent; amber for ageing; dark amber for older; hatched grey for never/unknown. These are evidence-age categories, not device availability states.

**Click:** Devices filtered by onboarding state or inventory-age bucket.

Display, for example, “112 registered devices · 40 clusters” only when those are the snapshot’s counts. The repository notes and supplied screens contain different counts; do not hard-code any of them.

### Block 2 — Control failures and evidence gaps

**Visual:** the dominant panel: aligned horizontal count bars and numeric columns, grouped by firewall vendor. Begin with Check Point and Palo Alto; show other vendors as **“Not assessed”** when appropriate.

For each vendor group `v`, using the declared compliance-target population:

- `Nᵥ`: eligible firewall targets.
- `Kᵥ`: distinct targets with at least one critical failed control.
- `Gᵥ`: distinct targets with at least one unavailable applicable control.
- `Eᵥ / Tᵥ`: evaluated control–target pairs / applicable control–target pairs, where `Eᵥ = pass + fail`.

Display columns:

```text
Vendor        Critical failures       Evidence gaps       Checks evaluated
Check Point   Kcp / Ncp targets       Gcp / Ncp targets   Ecp / Tcp
Palo Alto     Kpan / Npan targets     Gpan / Npan targets Epan / Tpan
```

Use a common zero-based count scale for the affected-target bars, with fractions printed alongside.

**Source:** Devices + Compliance.

**Time:** latest stored assessment per applicable control–target pair at `t`; show the source observation range.

**Colour:** critical failures red; unavailable evidence grey/hatching; assessed coverage neutral blue. A zero critical-failure count does not make an incompletely assessed row green.

**Click:** Compliance filtered to vendor and either critical failures or unavailable evidence.

**Important:** `Kᵥ` and `Gᵥ` overlap. They must not become a stacked bar or be summed. This panel shows concentration of known findings, not a business-risk ranking.

### Block 3 — Backup evidence

**Visual:** prominent **“98 of 110 targets have an archive”**, a coverage bar, then compact archive-age and validation breakdowns.

**Definitions:**

- `B`: current backup targets.
- `A`: targets with at least one stored archive.
- Archive coverage = `A / B`.
- Missing archive = `B − A`.
- Archive-age buckets partition `A` using each target’s latest archive timestamp.
- Validation categories partition `A` using the latest archive’s actual recorded validation level; missing levels remain unknown.

The supplied example is `98/110 = 89.1%`, with 12 missing archives.

**Source:** Backups + backup-target flag.

**Time:** archive age at `t`; separately identify when archive metadata was observed.

**Colour:** archive presence blue; missing archive red; age categories neutral unless an explicit policy makes them overdue. Use green only for a named, actually satisfied validation criterion.

**Click:** Backups filtered to missing archives, age bucket, or validation level.

Keep the title **“Backup evidence.”** Neither archive coverage nor a generic validation flag should be named “Recoverability.”

### Block 4 — Framework results

**Visual:** four horizontal 100% stacked bars with directly labelled pass/fail/unavailable counts.

For framework `f`:

```text
T_f = P_f + F_f + U_f
Demonstrated pass = P_f / T_f
Assessment coverage = (P_f + F_f) / T_f
```

`T_f` contains applicable control–target evaluations. Explicitly non-applicable checks are excluded; unexplained missing results remain unavailable.

**Source:** Compliance framework totals.

**Time:** latest stored results at `t`, with evidence observation range.

**Colour:** green pass, red fail, hatched grey unavailable.

**Click:** the framework and selected result state in Compliance.

Label the panel **“Framework results,”** with a visible note: “Technical checks; not an audit certification.” Do not total framework rows: the same underlying control can map to multiple frameworks.

### Block 5 — Most widespread critical control failures

**Visual:** five ranked horizontal bars showing **affected-target counts**, rather than five identical full-width “100% failed” bars.

For each canonical control and vendor:

- Numerator: distinct applicable targets whose latest result is `FAIL`.
- Evaluated denominator: targets with `PASS` or `FAIL`.
- Applicable denominator: evaluated targets plus unavailable results.

Example label:

```text
Account lockout · Check Point
62 failed / 62 evaluated · 0 unavailable
```

**Source:** Compliance controls, severity, outcomes, and affected targets.

**Time:** latest stored results at `t`; disclose the relevant observation range.

**Colour:** red bars; unavailable counts grey. Bar length measures failed-target count on one common scale.

**Click:** the exact vendor/control result and affected-target list.

Rank by failed-target count, then a stable control ID. Do not call this “highest business risk”: business criticality and exposure are absent.

### Block 6 — Configuration changes

**Visual:** one stacked population bar and a compact headline; no change donut or section-count leaderboard.

Let:

- `Q`: targets expected to have configuration evidence.
- `C`: targets with a valid comparison against their previous successful read.
- `X`: comparable targets with at least one changed section.

Partition the bar into:

```text
Changed: X
Unchanged: C − X
Not comparable: Q − C
```

Show `X/C` as the comparison result, and `C/Q` as comparison coverage.

**Source:** Configuration + device scope.

**Time:** **“Latest read versus previous successful read per target.”** The comparison intervals vary; show their range in details. Do not label this “changes today.”

**Colour:** changed blue; unchanged pale neutral; not comparable hatched grey.

**Click:** Configuration filtered to changed, unchanged, or not comparable; individual rows expose both observation times and changed sections.

The supplied `42/103` can be displayed only if all 103 genuinely belong to the comparable population.

### Block 7 — Cluster member comparisons

**Visual:** a three-state comparison bar, with a small list of clusters having the largest recorded differences.

Let:

- `H`: clusters in scope.
- `J`: clusters with sufficient member evidence for the existing comparison.
- `M`: comparable clusters with at least one reported setting difference.

Partition into:

```text
Differences observed: M
No differences in compared settings: J − M
Insufficient comparison evidence: H − J
```

**Source:** Cluster membership + Configuration member comparisons.

**Time:** observation times for the compared members. Do not imply simultaneous observation where collection times differ.

**Colour:** differences blue; no differences neutral; insufficient evidence hatched grey.

**Click:** Configuration → cluster comparison, retaining masked cluster identity.

The supporting list sorts by recorded setting count and names the compared sections. It is not a health ranking. Expected member-specific settings must remain distinguishable from settings governed by an equality requirement.

### Below the fold

Keep software/model distributions, CP policy-install age, and detailed evidence-age tables under **“Estate details.”** Policy age must retain both the device-reported installation time and the nightly observation time; an old installation is not inherently overdue.

The old screen already contains useful supporting material here: software/hardware and policy information in [old_02_overview_mid.png](/private/tmp/claude-502/-Users-OzanDur-Codo/897d9615-044b-4f41-a731-4ee5aabc4caf/scratchpad/exec/old_02_overview_mid.png), and inventory-age information in [old_03_overview_bottom.png](/private/tmp/claude-502/-Users-OzanDur-Codo/897d9615-044b-4f41-a731-4ee5aabc4caf/scratchpad/exec/old_03_overview_bottom.png).

**NEW DATA required for these seven blocks: none.** They require aggregation and explicit metric definitions. If a required timestamp or applicability relation is absent in implementation, show `UNKNOWN`; do not reconstruct it by guesswork.

## C. One posture score?

**No overall headline score in the first release.**

A weighted average of compliance, backups, freshness, and cluster differences would introduce unsupported trade-offs. Extra archives cannot compensate for critical control failures. Missing assessments must not improve a score. Cluster differences do not belong on a “good versus bad” scale without expected-state evidence.

Retain honest, named measures:

```text
Framework demonstrated pass = P / (P + F + U)
Framework assessment coverage = (P + F) / (P + F + U)
Observed pass among evaluated checks = P / (P + F)
Archive coverage = targets with an archive / backup targets
```

Show the first two together. The third belongs in detail because excluding unavailable results can make an incompletely assessed estate look better.

**Do not automatically promote 29.2% into an estate-wide measure.** The old screen shows CIS at `708/2424`, which rounds to 29.2%; that alone does not establish an aggregate across frameworks. [old_01_overview.png](/private/tmp/claude-502/-Users-OzanDur-Codo/897d9615-044b-4f41-a731-4ee5aabc4caf/scratchpad/exec/old_01_overview.png)

## D. Correcting attempt (b)

The following issues are visible in [01_overview_dashboard_top.png](/private/tmp/claude-502/-Users-OzanDur-Codo/897d9615-044b-4f41-a731-4ee5aabc4caf/scratchpad/exec/01_overview_dashboard_top.png):

| Defect | Required correction |
|---|---|
| **“292 / 1000” compliance** | Remove the fabricated-looking denominator. Use the actual passed/applicable evaluation counts and name the framework or proven aggregate scope. |
| **Repeated account-lockout title** | Label vendor and control identity. Keep vendor-specific controls separate unless a reviewed semantic mapping establishes equivalence. Never merge by title text. |
| **172 critical findings versus the displayed device bars** | Resolve the unit before publication. Four distinct rows each showing 62 failing devices imply at least **248 control–device failures** if they share a snapshot and scope. “172” may use another unit; that needs an explicit explanation. |
| **“Recoverability 89.1%”** | Rename to archive coverage. Show age and validation separately. |
| **“Cluster consistency 5.1%” in red** | Replace with the neutral comparison distribution. Differences alone do not prove unhealthy HA or configuration drift. |
| **“Latest evidence” green indicator** | Show population freshness. One recent successful read cannot establish that the estate is current. |

The change/cluster bars and version panel in [02_overview_dashboard_bottom.png](/private/tmp/claude-502/-Users-OzanDur-Codo/897d9615-044b-4f41-a731-4ee5aabc4caf/scratchpad/exec/02_overview_dashboard_bottom.png) also need two corrections: sort lists according to their declared ranking, and stop assigning risk meaning to arbitrary section counts or fleet-relative versions.

### Replace “patch currency” now

With today’s data, show **“Observed software distribution”** in estate details:

- Vendor, software family, version/take, device count, unknown count.
- Latest observation time.
- Neutral categorical colours.
- Click through to matching devices.

Remove “86 devices behind” and the 7.5% alarm. The newest version observed locally is not a recommended target. Palo Alto explicitly distinguishes preferred releases, and Check Point distinguishes recommended Jumbo takes. [Palo Alto guidance](https://docs.paloaltonetworks.com/ngfw/help/11-2/panorama-web-interface/panorama-software/manage-panorama-software-updates), [Check Point documentation](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/Central-Deployment-of-Software-Packages.htm)

| Future capability | **NEW DATA** | Cheapest defensible collection |
|---|---|---|
| Vendor-recommended release alignment | Recommendation by product, model, software branch and relevant constraints; source URL; checked/effective dates. | A small reviewed reference table populated from official guidance; periodic manual refresh before adding an automated feed. No device commands. |
| Bank-approved release alignment | Approved target set, applicability, approval date, exceptions and expiry. | A maintained baseline record imported through the existing administrative path. Keep bank approval separate from vendor recommendation. |
| Software/hardware support horizon | Model/release support milestones and applicable support terms. | Import official lifecycle tables and review changes monthly. Hardware and software support are separate dimensions. [PAN lifecycle source](https://www.paloaltonetworks.com/services/support/end-of-life-announcements/hardware-end-of-life-dates) |
| Security patch exposure | Advisories, affected-version rules, fixes and applicability conditions. | Begin with a curated advisory register. A general CVE feed without applicability logic is insufficient. |

Later, display **aligned / differs / unknown** against a named baseline. Use red only where an evidenced security or lifecycle condition justifies it. Unknown, stale, or inapplicable reference data must remain unknown.

## E. Wall-display variant

- `?wall=1` keeps the same metrics and snapshot; it cannot change authorisation or bypass `aiview`.
- Remove navigation, search, account chrome, and inline actions. Increase principal figures to approximately 40–48 px and supporting labels to 20–24 px.
- Keep scope, timezone, snapshot time, source ages, and masking status permanently visible.
- Reduce ranked lists from five entries to three. Show vendor aggregates instead of device names where possible.
- Keep the layout stationary: no carousels, scrolling tickers, flashing alarms, or animated chart sweeps.
- Refresh the stored-data endpoint, for example every 60 seconds. This must never trigger collection or device commands.
- If refresh fails, preserve the last snapshot and show **“Display update unavailable · last snapshot …”**. Evidence age continues increasing.
- Apply the same semantic colours in light and dark themes, with labels/patterns so colour is never the only signal.

“Live” means **the latest available stored evidence, visibly dated**. It does not mean continuous knowledge of device state.

## F. Delivery sequence

### First release: a bounded one-week candidate

**Days 1–2:** settle denominators, applicability, control identity, timestamp provenance, and the 172-versus-device-count discrepancy. Record the approved metric contract.

**Days 2–4:** build the seven blocks using existing projections, React/MUI, and small SVG bars. Use one coherent read-only overview response; cache aggregates after evidence changes if necessary. No chart library or new collection mechanism is needed.

**Day 5:** validate reconciliation and usability:

- Counts match filtered destinations; stacked categories reconcile with their denominators.
- Unavailable, unsupported, zero-population and partial-evidence cases remain explicit.
- Vendor-specific controls and framework mappings do not create accidental duplicates.
- AIView masking, keyboard access, both themes, and 1440×900/wall layouts work.
- Measure **p95 navigation-to-meaningful-display under one second** on the agreed bank workstation/LAN, including cold and warm paths.
- Run the applicable repository render, regression, privacy and state-consistency gates, followed by PO visual acceptance.

This is a delivery target, not a claim that these checks have already passed.

### Second release: honest trends

**NEW DATA — daily snapshots.** Add a small nightly snapshot table populated entirely from stored evidence. Collection cost: one local database aggregation daily, no device traffic.

Store:

- Snapshot time, scope and metric-definition version.
- Numerators, denominators, unknown counts and evidence-age summaries.
- Vendor/framework dimensions.
- Stable opaque target/control keys only where historical drill-down or transitions are required.

Introduce three compact time-series views:

1. **Critical control failures:** failed evaluation count and distinct affected targets, with assessment coverage.
2. **Backup evidence:** targets without archives, plus age/validation distributions.
3. **Assessment coverage:** evaluated/applicable checks and inventory freshness.

Start history on the first actual snapshot date. Leave missed days as gaps. Mark scope or definition changes; do not draw a continuous “improvement” line through incompatible populations.

Daily totals support trends. **“Newly failing,” “resolved,” and “reopened” require per-control–target state snapshots**, not aggregate subtraction. Likewise, daily configuration-change counts do not reconstruct every intervening change.

Owners, due dates, accepted exceptions, business-service criticality, and approved BDDK mappings are further **NEW DATA**. The cheapest starting point is a small reviewed register or import, not a new device collector.

**SESSION CLOSE — Design proposal delivered.** Source, configuration, Git, deployment, devices and production data were unchanged. No implementation or validation results are claimed. Durable state and `AI_HANDOVER.md` were not updated in this read-only environment. Next movement: approve the metric contract, then bounded UI implementation with normal-strong reasoning; no merge or deployment is authorised by this report.

