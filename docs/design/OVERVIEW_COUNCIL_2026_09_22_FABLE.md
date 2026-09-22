# Overview design panel — neXus home screen

## A. Top five questions per seat

**UI/UX designer**
1. What needs a human today? The first row must be a short, sortable exceptions strip, not counts of things that exist.
2. How old is what I am looking at? Every figure carries the evidence timestamp, or the screen is not trustworthy.
3. What changed since the last collection? Delta is the only reason to return to a home screen daily.
4. Where does a click take me? Every number is a filtered list, never a dead label.
5. Is neXus itself working? If collection is broken, every other card is stale and the screen must say so first.

**Deputy General Manager (GMY)**
1. What is the assured share per framework, and how much is unverifiable? A BDDK finding cites the gap, not the score.
2. How many devices have no evidence at all this week? Unmanaged scope is the audit exposure.
3. How many devices lack a backup inside policy? Recoverability is a regulator question, not an ops one.
4. Which controls carry the most critical deficiencies? Board reporting is by control, not by device.
5. Did posture move since last month? Without a stored prior assessment, only "no trend evidenced" is honest.

**Network Security Manager (Mudur)**
1. What failed in the last 24 hours, and grouped by which reason? Reasons drive work assignment.
2. Which clusters have a member difference? That is the queue of drift tickets.
3. Which devices changed configuration since the previous collection? Unplanned change is the incident signal.
4. What is the patch spread? Six jumbo takes is a project, and the count per take sizes it.
5. Which clusters are not ready for failover? That gates maintenance windows.

**Firewall operator**
1. Which collections and backups failed overnight, with the reason text? First coffee, first fix.
2. Which cluster members changed HA role since the last pass, or show two actives? A silent failover happened.
3. Which devices rebooted recently? Uptime under 24 hours with no known change is a question.
4. Which devices have a config section diff between members? Point me at the section, not the device.
5. Whose backup is stale or missing? Before I touch a device I want a fresh archive.

## B. Converged layout

Every row is a stored-evidence query. Each metric shows its evidence timestamp. "Evidenced" means a job with a successful outcome exists for that device and plane. Unknown is rendered as `UNKNOWN`, never as zero.

**1. Attention strip (top, five tiles, always visible)**

| Tile | Definition | Source | Click |
|---|---|---|---|
| Failed jobs, 24h | jobs with outcome failed, completed in last 24h, grouped by reason | Jobs | Jobs list filtered to failed, 24h, grouped by reason |
| Stale devices | devices whose last successful inventory job is older than 24h, over 105 | Jobs, Inventory | Device list sorted by evidence age |
| Clusters with member DIFF | clusters where the latest configuration collection has any differing section, over 39 | Configuration cluster DIFF | Cluster list, then the differing section |
| Config changed since last pass | devices where change state is "changed" between the two latest collections | Configuration change state | Per-device section diff |
| No backup in policy | devices with no stored backup, plus devices whose newest archive is older than their retention/schedule window, over 105 | Backups | Backup targets filtered to missing or stale |

Tiles turn to a neutral state at zero. Never green; zero failures and zero evidence look the same without the timestamp.

**2. Evidence freshness bar**
One stacked bar: devices by age of last successful inventory collection in four buckets, under 24h, 24 to 72h, over 72h, never. Denominator 105. Source: Jobs. Click: device list for that bucket. This is the GMY "unmanaged scope" number and the operator "is collection alive" number in one row.

**3. HA and clusters**
- Clusters with a blocking preflight finding, over 39, from the HA readiness assessment. Verdict wording is the assessment's own, never a "safe" label.
- Clusters where a member's HA role differs from the previous collection, or where both members report the same role. Source: Inventory HA role per member, two latest passes.
- List, masked pseudonyms, one line each: `CLS-ROMEO-01`, finding, evidence age. Click: the cluster's readiness detail.

**4. Configuration change**
List of devices with change state "changed", newest first, with the count of changed sections. Source: Configuration change state. Click: section diff. Members of the same cluster are grouped so a symmetric change reads as one row.

**5. Backup posture**
- Devices with a backup inside policy, over 105, from Backups schedule and retention.
- Devices with a stored backup but outside window, and devices with none, as two counts.
- Backup jobs failed in 24h with reason. Source: Jobs, job type backup.
Click: archive listing for that device.

**6. Compliance**
- Per framework, one row each: assured share, evidence coverage, critical deficiencies, data gaps. Source: latest compliance assessment. Denominators as the assessment defines them.
- Top five controls by critical deficiency count, across frameworks. Click: the control's failing devices.
- Trend column shows the delta against the previous stored assessment run, or `UNKNOWN` if only one run is stored.

**7. Platform spread**
- Check Point jumbo hotfix take histogram: devices per take, six bars today. Source: platform identity.
- Palo Alto content versions: devices per version for app, threat, AV, WildFire, URL, compared to the newest version seen in the fleet, not to a vendor feed. Devices behind the fleet newest are counted.
- Devices with uptime under 24h. Source: uptime at last collection. Click: device, with the change row if one exists.

**8. neXus health (bottom, small)**
Jobs completed in 24h, jobs running now, oldest running job age, last successful collection timestamp per vendor. Source: Jobs. Click: job queue.

Performance: sections 1 through 8 read from one summary record rebuilt at every job completion. No per-request joins across 105 devices. The masked persona receives the same record with pseudonyms substituted before serialization.

## C. Five metrics not to show

1. **Total devices with inventory, configuration, or backup as hero counts.** They barely move and they answer nothing. Coverage belongs in a freshness bar with a denominator.
2. **Jobs on record.** The ~1500 lifetime count is a database size, not a state. Only the 24h window matters.
3. **A composite health or risk score.** It blends collection failure, drift, and deficiency into one number with no evidence semantics. The constitution's UNKNOWN rule cannot survive a weighted average.
4. **Compliance assured share without data gaps beside it.** The ~29% alone invites a wrong conclusion in either direction. Coverage and gaps must sit in the same row.
5. **Reachability or "devices online".** The screen issues no device command, and a stale last job is not a down device. Show evidence age instead.

Also excluded: average uptime, collection speed, and any SLA percentage, since no SLA target is stored.

## D. Data not yet collected, and the first release

**Not collected today, needed for the full layout**
- Compliance assessment history. Section 6 trend needs prior runs retained with their date. If only the latest is stored, trend is `UNKNOWN`.
- Acknowledgement state on findings and failed jobs. The manager's "assigned or not" cannot be answered. Needs a small owner and ack field on job and finding, no device data.
- A per-device collection schedule. Stale thresholds are fixed at 24h until the scheduler records an expected interval per target. Backups already have one, collection does not.
- Prior HA role per member kept alongside the current one. Section 3's role-change row needs the previous pass retained, not just the newest.
- Change intent. Whether a detected change was planned needs a ticket or maintenance window reference. Not collected, and out of scope until Script Execution records intent.
- Vendor latest versions. Deliberately not collected. The fleet-newest comparison is the honest substitute.

**Minimal first release**
1. Attention strip, all five tiles, from Jobs, Configuration DIFF, change state, and Backups. All evidenced today.
2. Evidence freshness bar with the fixed 24h threshold.
3. Compliance per framework with coverage and gaps in the same row, and the top five controls. Trend column omitted.
4. Failed jobs in 24h grouped by reason, with the reason text.
5. Jumbo take histogram.
6. neXus health footer.

Deferred to the second release: HA role change detection, backup policy window computation, Palo Alto content spread, acknowledgement, trend.

The first release replaces the four count cards and two text panels with nothing the Product Owner can call meaningless, because every figure has a denominator, a timestamp, and a filtered list behind it.
