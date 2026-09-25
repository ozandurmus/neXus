# Fable review of Astra's UI effectiveness council (2026-09-25)

Read: Astra's council, both contracts, all 19 screenshots. Verdict in one line: Astra's diagnosis is right on the merge and on ASA, over-built on the Overview, and it missed the thing that most makes the UI "mixed" for a non-engineer: the product counts the same estate seven different ways.

## 1. Astra's claims checked against the screenshots

**Confirmed (I could see it):**

- Two trees with separate detail tabs, empty selection on Config: `05_devices_cluster_interfaces.png`, `10_config_list.png`.
- Members identity table repeated verbatim in Config before the diff: `08_devices_cluster_identity.png` vs `11_config_cluster_top.png`.
- Cluster header claims one model (28000) while members are 28000 and 26000T: `05`, `07_devices_cluster_members.png`. Real defect.
- Raw failure strings on Overview, including a digest: `01_overview.png`.
- Backup totals 110 (Overview, Admin) vs 108 targets / 11 without archive (Backups): `01`, `15_backups.png`, `19_admin_device_registry.png`.
- Full UUIDs in prime columns: `07`, `18_operations_jobs.png`.
- Gateway header chips "Live", "Identity verified", "UNKNOWN" with no dimension named: `09_devices_gateway.png`.
- "Assured compliance 29.2%" with gaps counted against: `13_compliance.png`.
- Control rows with internal IDs and framework chips: `14_compliance_controls.png`.
- Six or seven actions per backup row; V1 / FIRST / CHANGED unexplained: `15`.
- Management Center row "not enrolled" + Delete while Admin shows it Enrolled: `16_backups_bottom.png`, `19`.
- Diff index as an inline link stream: `12_config_cluster_diff.png`.
- Dialog: "You do not have permission to create credentials" beside a "Create credential" link; "pilot allowlist" wording: `20_add_device_dialog.png`.

**Wrong or unverified:**

- Astra's Fact 1 says "do not call enrollment 'Live'". Devices shows 112 devices · 102 Live, Admin shows 110 enrolled. Live is not enrollment; what it is remains UNKNOWN from the screens. Astra's fix is built on an unverified assumption.
- Astra calls the 110/108 gap UNKNOWN. The screens supply a candidate: Admin lists 2 Draft devices, and the Backups filter is labelled "All enrolled · 108". Likely the fleet table excludes drafts and Overview does not. One query settles it.

**Over-engineered:**

- Eight Overview measures each with scope, denominator, timestamp and separate unsupported/never-read splits. That is the current style, restated. The PO said "too technical". A non-engineer needs three sentences, not eight defined ratios.
- A four-part "status strip" (enrollment, identity, inventory age, configuration age) re-creates the chip row in `09` that Astra itself criticises.

**Missed:**

- **Seven denominators for one estate.** 112 enrolled (`05` header), 110 enrolled + 2 draft (`19`), 110 of 112 active (`01`), 102 Live (`05`, `10`), 103 collected / 9 not (`10`), 101 firewalls evaluated (`13`), 108 targets (`15`) vs 110 targets (`01`, `19`). This, more than the two trees, is what "mixed" means to the PO.
- **Overview says every number twice.** The three triage lines at the top of `01` and the six cards below carry the same six figures. The triage block is actually the best thing on the screen; Astra did not credit it or notice the duplication.
- **"Alignment" misused.** `06_devices_cluster_routing.png` column "Cluster alignment / diff" is a member-to-member comparison. AGENTS.md reserves Alignment for expected intent vs actual state. The word must change.
- **The UNKNOWN chip is HA role.** `09` shows "HA role: UNKNOWN" under the Interfaces tab, and every standalone gateway in the tree carries an UNKNOWN chip where clusters carry CLS. "Standalone" and "HA role unknown" cannot both be headline facts. A standalone also gets a "Cluster members" tab (`09`), and only the standalone gets a "Backup" tab; the cluster in `05` does not.
- **Masking inconsistency.** The digest in `01` reads `NaNbedNaNbNfd…` (digits replaced) while the same string in `02_overview_mid.png` is raw hex. The masker rewrote digits in one place and not the other. Harmless for a hash, but it shows the masking is regex-shaped and not one projection.
- **"UNKNOWN" where "never" is known.** `17_operations_ha.png` "Last evaluated: UNKNOWN" with "0 evaluated" in the card. That is a known zero.
- **Job text in the tree.** Device subtitles read "Collection completed · 192.0.2.x · Check P…" (`05`, `09`, `10`). Job state as a device description.
- **Three Collect buttons on one screen** (`05`): Bulk Collect, Collect now per member card, Collect now per member row.
- **The cheap path.** `05` and `11` already show "This cluster in: Inventory · Configuration · Backups · Readiness". The entity-centred navigation exists as links. Merging is turning those links into tabs, not a redesign.

## 2. One device screen: yes

Inventory and configuration remain separate data with separate read jobs, timestamps, and tables. What merges is the selection and the header. The screen, top to bottom:

1. **One tree** (manager → domain → cluster → member, plus virtual systems). Subtitle: vendor · model. Chip: kind (Cluster, Gateway, Manager, VS). No job text, no HA role, no UNKNOWN.
2. **Header**: name, kind, vendor, model (or "2 models"), version. One facts line: "Inventory read 15 min ago · Configuration read 2 h ago · Last backup 27 h ago". One action: "Read now ▾" with Inventory / Configuration. Opaque IDs go to the Identity tab.
3. **Members strip** for clusters: name, role observed, model, per-member read time.
4. **Tabs**: Summary · Inventory (Interfaces, Routing, Virtual systems) · Configuration (Sections; Member differences for clusters) · Backups (this device's archives, target switch) · Identity (serial tokens, IDs, provenance, enrollment) · Activity (this device's jobs, plain reasons). Onboarding progress replaces the tabs while RUNNING.

Navigation:

- **Config** nav item removed. "Collect All" becomes "Read configuration, all devices" in the Devices toolbar. Old links route to the tab.
- **Admin › Device management** stays but as "Registry": enrollment state, credentials, delete, import. It stops being a browser: no tree, no last-collection column. "Add device" lives once, in Devices, and lands on the new device.
- **Backups fleet table** stays. Its question ("which targets lack an archive") is a fleet question. Row actions shrink to "Back up now" plus a menu; the device name links to Devices › Backups tab.
- Compliance and Operations unchanged in navigation.

## 3. Ten changes, ranked by effect on "too technical, too mixed"

1. **One count vocabulary.** Four words, used everywhere, same numbers: Registered, Enrolled, Read in 24 h, Backup targets. Retire "Live", "active", "collected", "shown", "evaluated firewalls" as headline counts. (`01`, `05`, `10`, `13`, `15`, `19`)
2. **Overview first screenful = the three triage lines only**, rewritten plainly, plus the evidence-age line. Delete the six cards beneath. Example: "Act now: 25 reads failed today on 9 devices, mostly could not connect. 12 backup targets have no backup." (`01`)
3. **"Why jobs failed" becomes three plain causes** with device counts: Could not connect · Login refused · Not supported on this model. Raw strings move to Operations › Jobs detail. (`01`, `02`)
4. **Device header rewrite** as in section 2. Cluster with two models says so. Standalone shows "Standalone (no HA)"; if HA was not read, "HA: not read yet". (`05`, `07`, `09`)
5. **Empty states get three distinct words**: "Not read yet" (never attempted), "Read failed · reason" (attempted), "Not applicable" (Infoblox configuration, HA on a standalone). UNKNOWN only for "read, could not be determined". Apply to `09`, `16` (Model UNKNOWN → Not read), `17` (Last evaluated → never), `02` donut UNKNOWN slices.
6. **Label rewrites**: Identity & provenance → Identity; Cluster alignment / diff → Same on both members; Origin LOCAL / MEMBER → Set on this member / Expected to differ; Config diff · 68 → 68 settings differ; Assured compliance → Checks passing; Data gaps → Not yet checked; Terminal reason / error → Result; Re-evaluate → Re-check; Enrol → Add. (`06`, `11`, `12`, `13`, `18`, `20`)
7. **Compliance one framework at a time**, chosen by a control, default the bank's audit framework. Hide control IDs and mapping chips behind the row. "Control families grouped by NIST" appears only under NIST. (`13`, `14`)
8. **Backups row and words**: V1 → Checked, FIRST → First backup, CHANGED → Changed since last; "not enrolled" → "Not a backup target"; policy cards above the table. (`15`, `16`)
9. **Cluster diff index** becomes a section list with counts; keep "Differences only" default. (`12`)
10. **Jobs table**: type as "Check Point · read inventory", job ID and device UUID behind expand, names through the one pseudonymizer. (`18`)

## 4. Onboarding flow: approve with changes

The contract is FROZEN and sound: server-owned, steps stay separate jobs, backup never a step, unsupported steps skipped with reason. Changes, all at the UI and wording level, none touching the frozen semantics:

- **Internal contradiction to resolve.** The acceptance line promises "COMPLETED with … inventory filled", but the flow skips inventory for Panorama and configuration for Infoblox, Radware, Blue Coat. Show "Completed · configuration not available for this vendor". The PO must accept that wording; it cannot be reconciled silently.
- **Dialog** (`20`): remove the credential link when the role lacks permission; replace "pilot allowlist" with the current policy; after submit, land on the device in Devices.
- **STOPPED reason as persistent text**, not hover. Agree with Astra.
- Pre-V77 devices "treated as onboarded" must not display an onboarding badge; they have no history.
- Delivery status: commit 9c4f56e says validated on two devices. Batch import and browser-close persistence are not shown; keep AUTOMATED_VALIDATED until an aiview demonstration covers them.

## 5. Cisco ASA backup: keep the text backup, freeze it, keep the archive out

- **Split the contract.** Reads plus text backup become the FROZEN ASA contract now, delivery status IMPLEMENTED until the measurement run. The archive step stays a separate DRAFT. This resolves the DRAFT-with-implementation-authority contradiction Astra correctly reported.
- **Conditions on the text backup**: label it "Configuration text (running + startup). Certificates, VPN images and profiles are not included"; the running config carries keys, so it is secret-bearing and must never render raw through Contents or Compare; refuse or label single-context when `show mode` reports multiple contexts; keep the privilege-15 stop.
- **The archive is not class 1 as written.** It needs `ssh scopy enable`, a global configuration change, plus a write to flash and a delete. That is a configuration write, outside RB.x recovery writes and outside the 2026-09-22 scheduled-writes amendment. If the PO requires certificate and VPN recovery, it needs its own FROZEN write contract with SCP pre-provisioned by the ASA administrators, cleanup limited to the exact file created, and a separately authorized recovery exercise before any "recoverable" claim. Astra's SEC-3 is right; I would state it harder: neXus never enables SCP.

## 6. Disagreements with Astra

| Topic | Astra | Fable | What settles it |
|---|---|---|---|
| Overview shape | Eight defined measures with denominators | Three plain triage sentences; delete the duplicate cards | PO reads both mockups; picks the one he can explain to the deputy GM |
| "Live" meaning | Treated as enrollment | UNKNOWN; counts (102 vs 110) say it is something else | Definition in the payload builder |
| 110 vs 108 targets | UNKNOWN | Probably the 2 Draft devices | One query: targets by enrollment state |
| Admin device list | Retire as a browser | Keep as Registry, strip browsing columns | Role test: which actions only a security_admin performs there |
| ASA authority | Resolve DRAFT conflict before more work | Split now: freeze reads + text, archive stays DRAFT | PO ratifies the split |
| Archive class | Not class 1 because of `ssh scopy enable` | Same, plus flash write and delete make it a full write contract | Vendor doc confirms `ssh scopy enable` is a config-mode command |
| Onboarding verdict | Conditional consent, acceptance UNKNOWN | Approve with changes; contract needs one wording fix | PO accepts "Completed · plane not available" |
| Biggest "mixed" driver | Duplicate trees | Seven inconsistent estate counts | Count the distinct denominators after change 1 |

Nothing in the repository was changed by this review. No file was written.
