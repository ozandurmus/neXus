Astra's document and all five screenshots are read. Here is the final specification.

# 1. Verdict on Astra

- **Right:** no blended posture score; no "patch currency" against the fleet's newest build; count K (affected firewalls) and G (firewalls with gaps) overlap and must never be stacked or summed; every figure carries its observation time; "not comparable" and "unavailable" are their own hatched category; cluster differences are a review signal, not a health verdict; "live" means latest stored evidence, visibly dated.
- **Wrong:** the dominant panel is a text table with bars, which is exactly the spreadsheet look the Product Owner rejected. Seven near-equal blocks give the screen no focal point. Refusing any headline number leaves an executive with nothing to remember.
- **Generic:** the vendor comparison table adds nothing the engineer can build from. The delivery calendar is filler.
- **Over-engineered:** four inventory-age buckets in the header, and evidence ranges on every card, belong in tooltips, not on the surface.
- **Missed:** the screen needs one striking visual that shows the whole estate at once. Sector leaders open with a concentration view. With today's data the honest version of that is a device map coloured by evidenced condition.

# 2. Final screen specification

**Global rules**

- One request, `GET /api/overview/executive`, returns a single snapshot with `snapshot_at` and every block's aggregates, computed server-side from stored projections, cached for 60 seconds. No device command. Target p95 under 1 s.
- Every block carries `observed_from` and `observed_to` in a tooltip on its title, and shows `UNKNOWN` rather than a computed value when a timestamp or population is missing. Zero denominators render `n/a`.
- Populations are never mixed: devices (112), compliance targets (firewalls, 101), backup targets (110), clusters (40).
- Colour has one meaning across the screen and both themes:

| Colour | Meaning | Only used for |
|---|---|---|
| Red | Evidenced critical failure or missing protection | Critical failing checks, backup targets without an archive |
| Amber | Evidence is ageing | Last read 24 h to 72 h ago, or older |
| Green | Demonstrated pass | Passed checks; devices with every critical check evaluated and none failing |
| Blue | Neutral observation | Changed configuration, cluster differences, software versions |
| Grey, hatched | Not evidenced | Unavailable, not assessed, never read, not comparable |

- Layout at 1440 × 900: app chrome 56 px, header 64, hero 300, row C 236, row D 190, 16 px gaps. Below the fold: Estate details.
- Names are masked pseudonyms throughout. All charts are inline SVG. No chart library.

**Header**

- Title `Overview`, subtitle `Estate assurance · 112 devices · 40 clusters · as of 2026-09-25 13:34 GMT+3` built from the snapshot, never hard-coded.
- Right pill: `107 of 110 active devices read in the last 24 h · 1 never read`. Neutral colour. Click: Devices filtered by inventory age. Astra's four-bucket bar moves to Estate details.

**Hero, left 60 %: Estate map**

- Chart: waffle grid, one 16 px square per device, grouped in columns by vendor in the vendor's own terms (`Check Point 63`, `Palo Alto Networks 40`, then Infoblox, Radware, Symantec, Cisco ASA, Fortinet as they exist). Within a group, cells are sorted by condition so colours form blocks. Hover shows masked name, vendor, model, condition and the three evidence times.
- Cell condition, first matching rule wins:

```
1 Critical finding   red      compliance target with >= 1 critical check FAIL in latest result
2 No archive         red ring backup target with no stored archive (drawn as ring so it combines with 1)
3 Evidence ageing    amber    latest successful read older than 24 h, or never
4 Partly assessed    hatched  compliance target with >= 1 critical check unavailable, none failed
5 Not assessed       hatched  not a compliance target (vendors without controls)
6 Clear              green    all applicable critical checks evaluated, none failed, archive present if a backup target, read < 24 h
```

- Legend under the grid with counts per condition. Each legend entry and each cell is a click target: Devices list filtered by that condition, or the device page for a cell.
- Empty state: no devices → `No devices are registered yet`. Missing compliance data for a vendor → those cells fall to rule 5, never to green.

**Hero, right 40 %: Three facts**

Three stacked rows, 44 px numeral, one proportional bar each, plain sentence underneath. `▲ n` / `▼ n since yesterday` shown only where the stored delta exists.

| Fact | Numeral | Bar | Sentence | Source | Click |
|---|---|---|---|---|---|
| Critical failures | K of N firewalls | red K / N | `firewalls with at least one critical check failing` | Compliance, latest result per control × target | Compliance, filter critical + fail |
| Backups | A of B targets | red for B − A, blue for A | `12 backup targets have no stored archive` | Backups | Backups, filter no archive |
| Evidence | R of D devices | amber for D − R | `read in the last 24 h · 1 never read` | Inventory last-read | Devices, filter age |

- The old headline `172 critical failing checks` is not shown until its unit is reconciled. Four controls each failing on 62 firewalls already exceed 172 control × firewall pairs, so the stored figure is either distinct controls or something else. The engineer resolves this in the API and the headline stays K of N, which is unambiguous.
- UNKNOWN: if N or B is zero, the row shows `n/a` and the sentence `No firewalls are assessed yet`.

**Row C, left 50 %: Compliance by framework**

- Chart: four horizontal 100 % stacked bars, green pass, red fail, hatched unavailable, direct counts printed at the bar end. Right label per row: `29.2 % demonstrated pass · 83 % assessed`.
- Definitions per framework f, applicable control × target evaluations only:

```
T = P + F + U
demonstrated pass    = P / T
assessed             = (P + F) / T
```

- Rows are not totalled. Footnote: `Technical checks. Not an audit certification.` The financial baseline row is labelled `Financial baseline`, never a regulator's name.
- Click: Compliance filtered to framework and segment state.
- UNKNOWN: a framework with T = 0 renders one hatched bar and `Not assessed`.

**Row C, right 50 %: Most widespread critical failures**

- Chart: five ranked horizontal bars, red, on one absolute scale whose maximum is the largest vendor firewall population. Each row: control title, vendor chip in vendor colour, right label `62 of 62 checked · 0 unavailable`.
- Identity key is `(vendor, control_id)`. Two vendors with the same title are two rows with vendor chips, never merged by title. Rank by failed count, then vendor, then control id.
- Source: latest result per control × target, severity critical.
- Click: the vendor + control result page with its affected-target list.
- Wall mode shows three rows.
- Empty state: `No critical check is failing` in green text, only if assessed > 0.

**Row D, three equal columns**

1. **Configuration changed since the previous read.** One stacked population bar: changed X (blue), unchanged C − X (pale), not comparable Q − C (hatched), counts printed. Under it the three devices with the most changed sections, masked name, `44 sections`, comparison time. Title tooltip: `Latest read versus previous successful read per device; intervals vary.` Click: Configuration filtered by segment; a row opens that device's section diff. UNKNOWN: C = 0 → single hatched bar, `No comparable reads yet`.
2. **Cluster members compared.** Three-state bar: differences M (blue), none J − M (pale), insufficient evidence H − J (hatched). Under it the three clusters with the largest setting count, masked cluster name, `68 settings`, and the section names. Footnote: `Differences are a review signal, not a fault. Interface addresses and physical settings excluded.` Click: Configuration cluster comparison. UNKNOWN: J = 0 → `No cluster has two compared members yet`.
3. **Software in service.** Per vendor, in the vendor's terms, 100 % stacked bars with neutral categorical colours and a hatched unknown segment: Check Point `Major version` and `Jumbo Hotfix take`; Palo Alto `PAN-OS version`; other vendors one bar each when versions exist. Legend with counts. Muted line: `Vendor-recommended build: not configured`. No behind or on-newest language anywhere. Click: Devices filtered by vendor + version. UNKNOWN: version missing counts into the hatched segment.

**Below the fold: Estate details**

Existing components, unchanged in meaning: Policy installed (Check Point, age buckets with device-reported install time and nightly read time), Hardware models per vendor, Inventory evidence age with four buckets. Jobs and failure reasons do not appear on this screen at all.

**Wall mode `?wall=1`**

Header, hero and row C only. No navigation, search or account chrome. Numerals 64 px, labels 22 px. Ranked list cut to three. Footer permanently shows `aiview · names masked · as of <snapshot> · times GMT+3`. Re-fetch the same endpoint every 60 s. On fetch failure keep the last snapshot and show `Display update unavailable · showing snapshot of <time>`. No animation, ticker or carousel.

# 3. Ships now versus NEW DATA

| Item | Status | NEW DATA and cost |
|---|---|---|
| Header, Estate map, Three facts, Compliance by framework, Most widespread failures, Configuration changed, Cluster members compared, Software in service, Estate details | Ships now | None. Aggregation over stored projections plus the K versus 172 reconciliation. |
| `since yesterday` deltas on the Three facts | Ships now, partial | Shown only where the existing delta exists. |
| 30-day trend lines for critical failures, backup coverage, evidence freshness | NEW DATA | Nightly snapshot table of numerators, denominators, unknown counts, vendor and framework dimensions, metric-version tag. One local aggregation per night, no device traffic. History starts on the first snapshot date. |
| Newly failing, resolved, reopened checks | NEW DATA | Per control × target state snapshot nightly. Same job, larger table. |
| Vendor-recommended build alignment, aligned / differs / unknown | NEW DATA | Reviewed reference table per product, model and branch with source URL and check date, refreshed manually monthly. No device commands. |
| Bank-approved build baseline | NEW DATA | Small register imported through the admin path, kept separate from vendor recommendation. |
| End of support, hardware and software | NEW DATA | Imported official lifecycle tables, reviewed monthly. Two separate dimensions. |
| Control family heat map, family × vendor | NEW DATA, cheap | Static mapping of control id to family, one reviewed YAML. Would replace the ranked list as a stronger visual later. |
| Owners, due dates, accepted exceptions, regulator mapping | NEW DATA | Register or import; out of scope for this build. |

# 4. Copy

Header and facts:

```
Overview
Estate assurance · {devices} devices · {clusters} clusters · as of {snapshot} GMT+3
{r} of {d} active devices read in the last 24 h · {never} never read

Estate map
Every device, coloured by the worst condition neXus can evidence
Legend: Critical finding · No archive · Evidence ageing · Partly assessed · Not assessed · Clear

Critical failures
{k} of {n} firewalls
firewalls with at least one critical check failing

Backups
{a} of {b} targets
{b-a} backup targets have no stored archive

Evidence
{r} of {d} devices
read in the last 24 h · {never} never read
```

Row C and D:

```
Compliance by framework
{pass} % demonstrated pass · {assessed} % assessed
Legend: Pass · Fail · Not evidenced
Technical checks. Not an audit certification.
Rows: CIS Benchmark · PCI DSS 4.0.1 · NIST SP 800-53 · Financial baseline

Most widespread critical failures
{failed} of {checked} checked · {unavailable} not evidenced
No critical check is failing

Configuration changed since the previous read
Changed · Unchanged · Not comparable
{n} sections
No comparable reads yet

Cluster members compared
Differences · No differences · Insufficient evidence
{n} settings
Differences are a review signal, not a fault. Interface addresses and physical settings excluded.
No cluster has two compared members yet

Software in service
Check Point: Major version · Jumbo Hotfix take
Palo Alto Networks: PAN-OS version
Unknown
Vendor-recommended build: not configured

Estate details
Policy installed · Hardware models · Inventory evidence age
```

Wall footer and failure:

```
aiview · names masked · as of {snapshot} · times GMT+3
Display update unavailable · showing snapshot of {snapshot}
```

Recap: Astra's metric discipline stands, its table-led layout does not. The final screen is a vendor-grouped device map as the single dominant visual, three large facts beside it, framework bars and ranked critical failures in the second row, and change, cluster and software distributions in the third. Everything ships from today's data except trends, recommended builds and end of support, which are listed with their collection cost. The one open engineering item is reconciling the unit behind the stored figure of 172 before any check count is shown as a headline.
