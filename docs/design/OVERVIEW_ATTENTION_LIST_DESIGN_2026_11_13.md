# Overview attention list — design note

**Status:** DRAFT — for Product Owner approval. Not implementation authority for anything beyond the PO direction of
2026-11-13 it answers. When approved, it becomes amendment C of `OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md` (FROZEN
2026-09-23; amendments A and B), whose posture tiles, headline and pillars it replaces on the default (non-wall) view.

**PO approved 2026-11-13 (relayed by the PO session after review of the mockup), these three points only:** one row
per finding (§2), an expired license / subscription / support contract is Review (§3, `license-expired`), and the
stale-backup threshold is 7 days (§3, `backup-stale`). Everything else in this note stays DRAFT.

**PO direction 2026-11-13:** the Overview answers "what should I look at today", like the landing pages of Tufin
SecureTrack, AlgoSec Firewall Analyzer and FireMon Security Manager (`EXEC_OVERVIEW_DESIGN_2026_09_25_ASTRA.md` §A),
not with counters.

**Mockup:** `~/Codo/ops-scripts/overview-attention-mockup-1113.html` (synthetic, masked names only).

## 1. The screen, top to bottom

1. **Header** — title, "as of", the existing freshness chip (unchanged).
2. **Attention list** — one row per finding, most severe first (§3). The first thing on the page.
3. **Fleet summary** — one compact card of four facts, each a link to its list: enrolled devices, clusters ready for
   failover, backup targets with a stored backup, devices read in 24 h. No bars, no deltas.
4. **Compliance by framework** — kept (it is the one view of where failures concentrate; each bar opens Compliance).

Removed from the default view: verdict word card, four pillars, change pulse, estate map, "most widespread critical
failures" (now the detail line of the compliance row), "software in service" line, the "estate details" disclosure
(still on Devices › Fleet summary). Wall mode (`?wall=1`) is unchanged in this release.

## 2. One row

```
[severity word]  what (count + noun)                         since (age, exact on hover)   Open →
                 where: up to three masked names, "and N more"
                 detail: one line of context (optional)
```

- **What**: the finding in plain words, with the count of affected objects.
- **Where**: masked names as the app shows them (`hostname` / `cluster_member_ref`, masked by the server for the
  aiview role). At most three, then "and N more". Names are text; the row has one link.
- **Since**: the age of the evidence behind the row (§3 names which time per rule). UNKNOWN when there is none.
- **Link**: one link to the screen and filter that resolves it.
- A finding is one row however many devices it touches; a device with three findings appears in three rows.
  (Alternative for the PO: one row per device. Rejected for now: 19 devices without a backup would be 19 rows.)
- The list shows the first 8 rows and a "Show all N" toggle; UNKNOWN rows are always shown.

## 3. Rules (the one place in code: `ui2/frontend/src/screens/overview/attentionRules.ts`)

Severity words: **Act now** (critical), **Review** (serious), **Watch** (warning). A source that could not be read is
an **UNKNOWN** row (grey), never a silent zero. Ordering: severity (Act now, Review, UNKNOWN, Watch), then the fixed source rank below, then affected
count (descending), then newest evidence first.

| Rank | Rule key | Source (existing read) | Condition | Severity | Since | Link (target screen) |
|---|---|---|---|---|---|---|
| 1 | `ha-not-ready` | failover summary + device list | cluster readiness `NOT_READY` | Act now | newest readiness evaluation | Operations › HA, `readiness=NOT_READY` |
| 2 | `backup-missing` | overview `estate.devices` | backup target with no stored backup | Act now | — (never) | Backups, without archive |
| 3 | `advisory-exploited` | lifecycle `vulnerabilities` | device AFFECTED by an advisory whose CVE is in CISA KEV | Act now | advisory feed loaded | Lifecycle and security |
| 4 | `compliance-critical` | overview `estate.devices.critical_fail` | firewall fails at least one critical check | Act now | compliance evidence time | Compliance, critical + fail |
| 5 | `advisory-critical-high` | lifecycle `vulnerabilities` | device AFFECTED by a critical/high advisory (not exploited) | Review | advisory feed loaded | Lifecycle and security |
| 6 | `backup-stale` | overview `estate.devices.last_backup_at` (new field, §5) | newest stored backup older than 7 days | Review | newest backup | Backups |
| 7 | `failed-jobs` | overview `exceptions.failed_jobs` | at least one job failed in 24 h | Review | latest failure | Operations › Jobs, FAILED, 24 h |
| 8 | `not-read` | overview `estate.devices.last_read_at` | enrolled device never read, or last read over 72 h | Review | last read | Devices, `inventory_age=stale` |
| 9 | `license-expired` | lifecycle `licenses` | license / subscription / support contract expired | Review | expiry date | Lifecycle and security |
| 10 | `license-30` | lifecycle `licenses` | expires within 30 days | Review | expiry date | Lifecycle and security |
| 11 | `license-60` | lifecycle `licenses` | expires in 31–60 days | Watch | expiry date | Lifecycle and security |
| 12 | `read-ageing` | overview `estate.devices.last_read_at` | last read 24–72 h ago | Watch | last read | Devices, `inventory_age=stale` |
| 13 | `live-patch-missing` | lifecycle `live_patch.missing` | Check Point device reports no Live Patch package | Watch | Live Patch read | Lifecycle and security |
| 14 | `ha-unknown` | failover summary | cluster readiness `UNKNOWN` | Watch | newest evaluation | Operations › HA, `readiness=UNKNOWN` |
| 15 | `change-unknown-actor` | change feed (user changes, 7 days) | change with no known actor | Watch | newest such change | Changes › Timeline |

Thresholds are constants beside the rules (`BACKUP_STALE_DAYS = 7`, `LICENSE_SOON_DAYS = 30`,
`LICENSE_WATCH_DAYS = 60`, `CHANGE_WINDOW_DAYS = 7`); the read-age edges are the product's one freshness scale
(`shell/freshness.ts`: 24 h / 72 h). Only enrolled devices (the estate list) count; lifecycle rows for other devices
are ignored.

**Not built (no data or endpoint today):**
- *Compliance regressions* (a check that passed and now fails): the only PASS→FAIL comparison is inside the mail
  notifier; `compliance_evaluation` keeps the latest result only. Needs a stored history first.
- *Failed scheduled collections* as distinct from manual ones: the jobs API cannot tell them apart (scheduled jobs are
  marked only by a `system:` actor fingerprint). The `failed-jobs` row covers both and says so.
- *"New" advisories*: the feed had its first load on 2026-11-13, so "new" would mean "all". Rows say "matching".

## 4. Roles

A row is shown only if the user can open its target screen:
- the target's product plane must be in the session's `permissions` (the same check the navigation uses:
  Devices for Devices and Lifecycle, Compliance for Compliance, Policy for Changes — the change feed endpoint needs
  `POLICY_READ`); Backups and Operations need no plane;
- a source the server refuses (403, e.g. `CP_FAILOVER_READ` for a backup admin) contributes no rows and no UNKNOWN
  row; a source that fails otherwise contributes one UNKNOWN row ("Lifecycle facts could not be read");
- a source whose target plane is not granted is not even read (the change feed for a viewer).
A session with no plane list (tests, previews) is unrestricted, as in the navigation.

## 5. Data path

No new endpoint. The Overview already reads `/api/v2/overview`, `/devices`, `/api/v2/cp-failover/summary` and
`/compliance/controls`; it adds `/api/v2/lifecycle` and, for the Policy plane only, one page of
`/api/v2/changes/feed`. One backend change: `estate.devices[]` gains `backup_target` and `last_backup_at` (the newest
live backup archive), read in the query the estate already runs. Read-only; no device command; no DB write.

## 6. Empty state

"Nothing needs attention." with "Evaluated HH:MM from N sources" and, when any source was restricted or UNKNOWN,
which ones. Never green: an empty list over stale data says "Figures reflect reads older than 24 h" (the existing
stale-evidence banner).

## 7. PO answers (2026-11-13)

1. Group per finding or one row per device? **Per finding.**
2. Severity of an expired license / subscription / support contract? **Review.**
3. Backup staleness? **7 days** (not tied to the backup schedule).
