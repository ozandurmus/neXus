# Lifecycle catalog: end-of-sale and end-of-support dates from vendor publications - contract

## Status

**FROZEN - PO DECISION (2026-11-14).** Frozen by the Product Owner's decision of 2026-11-14 on D-L1 to D-L4 (section 8),
relayed by the PO session: "İkisini ekle, FROZEN". The engineering session wrote this contract on 2026-11-13 under the
Product Owner's approval to build the lifecycle catalog and had marked it frozen itself; that marking was not valid
(engineering sessions do not freeze contracts) and is replaced by this status. It fills the empty end-of-support
catalog of the Lifecycle and security view (V144 `lifecycle_catalog`, shown as `No catalog match / NO_LIFECYCLE_DATA`
for every device) from the vendors' own lifecycle publications.

Terms: a **publication** is one vendor document that states lifecycle dates (a Cisco End-of-Sale and End-of-Life
bulletin, the Palo Alto end-of-life pages, the Check Point Support Life Cycle Policy page). A **vendor entry** is one
product (a hardware model or a software train) as one publication lists it. An **operator row** is a V144 catalog row
typed or imported as CSV by a `security_admin`. A **key** is the normalised product name a device and a catalog row are
compared by (section 5).

## 1. Rules that hold everywhere

1. **Vendor dates only, never guessed.** A date is stored only when the publication states it in a form section 4.4
   lists. Anything else makes the entry `NEEDS_REVIEW` with a code word and no dates. A date given only as a month is
   stored as the first day of that month and is always shown as the month.
2. **Never green by default.** A device part with nothing current to compare with is `NOT_EVALUATED`, not "unknown"
   and not "no data": no source loaded for its vendor, or the only matching entry is older than its age limit
   (section 6).
3. **Same trust boundary as advisories.** The only workload that reaches the internet is `ui2-advisory-fetch`
   (`ADVISORY_MATCHING_CONTRACT.md` section 3). It holds no secret but its ingest token, sends `GET` requests without a
   body to a compiled host and path list through the approved proxy, validates certificates against the public trust
   store, and hands each document to `ui2-service` on the internal port. No request carries a device model, version,
   serial, name or count.
4. **Untrusted input.** Every fetched or uploaded page is parsed in memory with size caps, without running scripts or
   resolving anything external, and dropped; its SHA-256 and address are stored. No vendor text is rendered as HTML.
5. **No device contact.** Nothing here sends anything to a managed device or adds a device command.

## 2. Storage (V219)

| Table | One row per | Notes |
| --- | --- | --- |
| `lifecycle_vendor_entry` | vendor entry | origin (`FEED` online, `OPERATOR_PAGE` uploaded), source key, vendor, kind, key, the product as published, end of sale, end of engineering, end of support, end of security support (Cisco), end of extended support (Palo Alto), the month-only fields, parse state and code word, publication reference, link, document hash, `confirmed_at` |
| `lifecycle_catalog_upload` | operator page upload | page form, vendor, document hash, counts, uploader, time. **Audited** (`fn_audit_capture`, action `lifecycle_catalog_write`); the uploader is redaction tier 2, as in `lifecycle_catalog` |
| `lifecycle_feed_run` | online run and source | as `advisory_feed_run`, plus documents ignored |

`lifecycle_vendor_entry` has no audit trigger: `FEED` rows are machine-written (the V205 rule) and every `OPERATOR_PAGE`
row is tied to its audited upload row written in the same transaction. A fetch or upload replaces the stored entries of
exactly the publications the document stands for, so a product the vendor removed from a publication leaves the store;
`confirmed_at` is set again each time a publication is read. The V144 table is unchanged.

## 3. Sources

### 3.1 Online sources

All three ride the daily `ui2-advisory-fetch` CronJob, go to `POST /internal/lifecycle-sources/<source>` and close the
run with `.../complete`, on the internal port, with the fetcher's own token (the token also authorises the advisory
ingest endpoints, which equally only take public documents in; nothing else).

| Source | Host | Exact documents | Decision |
| --- | --- | --- | --- |
| `cisco_eol` | `www.cisco.com` | the two listing pages, then the bulletins they link (below) | host approved 2026-11-10 |
| `checkpoint_lifecycle` | `www.checkpoint.com` | `/support-services/support-life-cycle-policy/` only | D-L1, 2026-11-14 |
| `paloalto_eol` | `www.paloaltonetworks.com` | `/services/support/end-of-life-announcements/end-of-life-summary` and `/services/support/end-of-life-announcements/hardware-end-of-life-dates` only | D-L2, 2026-11-14 |

The path allowlist is enforced by the fetcher (exact paths, checked immediately before each request) and again by the
ingest endpoint; the proxy egress policy stays address-based. A Check Point or Palo Alto page that no longer has the
tables of section 4 is a rejected document: the run is `PARTIAL` with a reason, nothing is replaced, and the entries
already stored age by the rule of section 6.

**Cisco.** Host `www.cisco.com`, approved for the proxy on 2026-11-10 (`PO_DECISION_RECORD_2026_11_10_ADVISORY_INTAKE_PROXY_SOURCES_AND_FIX_RULES.md`
section 5). The run reads two fixed listing pages,
`/c/en/us/products/security/asa-firepower-services/eos-eol-notice-listing.html` and
`/c/en/us/products/security/firepower-ngfw/eos-eol-notice-listing.html`, then every English bulletin they link under
`/c/en/us/products/collateral/security/` (the French copies, `-fr.html`, are not read; about 85 pages, one request per
second). Bulletins go to `POST /internal/lifecycle-sources/cisco_eol` and the run closes with `.../complete`, on the
internal port, with the fetcher's own token (the token also authorises the advisory ingest endpoints, which equally only
take public documents in; nothing else). A listing that names no bulletin is a run failure reason (`NO_DOCUMENTS_NAMED`),
so a moved page never looks like a fresh source.

### 3.2 Operator upload of a saved vendor page

The fallback when an online run cannot reach a vendor page (a proxy refusal, a network without internet access): a
`security_admin` saves the vendor page from a browser and uploads it (`POST /api/v2/lifecycle/catalog/vendor-page`, action `lifecycle_catalog_write`, at most 8 MiB). The page goes
through the same parser the online path would use; an upload whose page is not the chosen form stores nothing and says
so. Accepted page forms: `checkpoint_lifecycle`, `paloalto_software_eol`, `paloalto_hardware_eol`, and
`cisco_eol_bulletin` (one bulletin). The aiview persona cannot upload. When an online entry and an uploaded entry of the
same product agree, the online one is shown; when they disagree, the product is `NEEDS_REVIEW`.

### 3.3 Operator rows

The V144 catalog stays: a row typed in the view or a CSV import. Since this contract, a row of a vendor with key rules
must give a key (an import names the expected form and stores nothing when one row is wrong), and a second spelling of
a stored product is refused as a conflict. Fortinet dates come only this way (section 8, D-L3): the Fortinet product life
cycle is published only inside the FortiCloud support portal.

## 4. Parsers

### 4.1 Cisco bulletin

The page title must contain "End-of-Sale and End-of-Life Announcement"; otherwise the page is ignored. The publication
reference is the bulletin id (`EOL\d{4,6}`) from the page text, or the page name when there is none. Milestone table
(header `Milestone | ... | Date`): End-of-Sale Date (end of sale), End of SW Maintenance Releases Date (end of
engineering), End of Vulnerability/Security Support (end of security support), Last Date of Support (end of support).
Products: an ASA train named in the title (`ASA Release 9.18(x)`, `(ASA) 9.17.(x)`, `ASA 9.0 and 9.3`; ASAv, ASDM and
FTD trains are not ASA trains) as SOFTWARE; a Firepower or ASA appliance named by a part number of the product table
(`FPR-4115-K9`, `FPR4110-ASA-K9`, `ASA5516-FPWR-K9`; not licences `L-...`, kits, spares of other parts) as HARDWARE.
A milestone row carries its scope (`: HW`, `: App SW`); a software entry uses the SW rows and a hardware entry the HW
rows. Two different dates for one milestone → `MILESTONE_CONFLICT`; no Last Date of Support → `MILESTONE_MISSING`.
A bulletin with no ASA train and no appliance model (subscriptions, licences, FTD-only) is ignored.

### 4.2 Palo Alto

Software (`/services/support/end-of-life-announcements/end-of-life-summary`): the `PAN-OS & Panorama` table; one entry
per plain train (`11.1`) with Standard Support as end of support and Extended Support kept beside it; qualified rows
(`9.0-XFR (VM-Series only)`) are skipped and counted. Hardware (`.../hardware-end-of-life-dates`): one entry per model
the product cell names (`PAN-PA-440`, `PA-820-ZTP`, `PAN-PA-5450-AC-SYS`); a series name, a card or module part number
(`PAN-PA-5400-DPC-A`, `PA-7050-SMC`) and rows of other product lines (LightCyber, Prisma SD-WAN ION) are not models; the
page's `(EOL)` marker after a passed date is removed.

### 4.3 Check Point

Software: the tables under `Security Gateway & Management` and `Check Point Spark Firewall Security Gateways`; one entry
per version in the Affected Versions cell (`R81.20`, `R82`, `R81.10.xx`), Support Until as end of support (a month).
Hardware: every table whose header starts `Appliance Product/Model` and names End of Sale, End of Engineering Support
and End of Support; one entry per row whose model has a key; rows without one (open servers, legacy families) are
skipped and counted. Endpoint, cloud-service and announcement-summary tables are not read.

A key listed twice in one page with different dates becomes one `NEEDS_REVIEW` entry (`CONFLICTING_ROWS`).

### 4.4 Date forms

Day forms: `November 18, 2025`, `Mar 22, 2027`, `July 30th, 2026`, `July 31 2025`, `31-Oct-2014`, `31 December 2013`,
`2026-11-13`. Month forms: `Oct-2026`, `September 2030`, `Feb 2030`. No date: empty, `N/A`, `NA`, `-`, `TBD`, `TBA`,
`Latest`. One trailing footnote mark (`^`, `*`, `†`, `+`) is removed. Everything else is unreadable (`DATE_UNREADABLE`),
explicitly including two-digit years (`Oct-28`, the Spark table's form) and numeric day/month orders (`06/30/2028`).

## 5. Keys

One function turns a device's stored model or version and a catalog product into a key; equal keys match. Each rule is a
whole-text pattern; a form it does not name gives no key and the device part reads `NO_LIFECYCLE_DATA` with "form not
recognised", never a near match. This is a representation rule over public product names, not an identity rule: no
serial or device identifier is ever keyed.

| Vendor | Hardware key | Software key |
| --- | --- | --- |
| Check Point | `6600` from `Check Point 6600`, `6600 Appliance`; `1595R`; `SMART-1 6000-L` | `R81.20`, `R82` (Gaia, gateways and management); `R81.10.XX` from `R81.10.05` (Quantum Spark firmware) |
| Palo Alto | `PA-3220` from `PA-3220`, `PAN-PA-3220-ZTP`; `M-600` | `PAN-OS 11.1` from `11.1.10-h7`, `12.1.10` (firewalls and Panorama share the table) |
| Cisco ASA | `FPR4115` from `FPR-4115`, `Firepower 4115`, `Secure Firewall 4115`; `ASA5516` from `ASA5516-X` | `ASA 9.18` from `9.18(4)50` |
| Fortinet | `FG600E` from `FortiGate-600E`, `FGT600E`; `FMG400G` | `FortiOS 7.4` (FortiGate) or `FortiManager 7.4` (a management server, or a FortiManager model), from `v7.4.12 build2902` |
| Others | the stored text, trimmed (the V144 behaviour) | the same |

## 6. Matching and states

Per device and part (hardware model, software version), in this order:

| State | When |
| --- | --- |
| `NO_LIFECYCLE_DATA` | no key (model or version missing, or form not recognised); or two operator rows with the key; or a fresh source covers the vendor and kind but lists no such product (for Cisco: no end-of-life bulletin names it) |
| `MATCHED` (operator row) | one operator row with the key, entered or saved within 180 days; it is used before any vendor entry |
| `NOT_EVALUATED` | the operator row is older than 180 days; or every vendor entry with the key is older than its limit (30 days since an online run confirmed it, 180 days since an upload); or nothing at all is loaded for the vendor and kind |
| `NEEDS_REVIEW` | a fresh vendor entry with the key is `NEEDS_REVIEW`; or fresh vendor entries with the key disagree on a date |
| `MATCHED` (vendor) | fresh vendor entries with the key agree; the online one is shown when both exist |

The device status is `MATCHED` when both parts are, else `NEEDS_REVIEW`, else `NOT_EVALUATED`, else
`NO_LIFECYCLE_DATA`. Risk is unchanged (EXPIRED, HIGH under 180 days, MEDIUM under 365, LOW only with both support
dates), except that a device whose risk would be UNKNOWN and that has a part not evaluated or needing review reads
`NOT_EVALUATED`. Every matched part carries its basis: key rule, origin, publication and age.

## 7. Read interface and view

- `GET /api/v2/lifecycle` and `GET /devices/{id}/lifecycle`: unchanged shape; a part's `status` may now be
  `NOT_EVALUATED` or `NEEDS_REVIEW`, its `catalog` may be a vendor entry with `origin`, `age_days`, `month_only`,
  `source_url`; milestones carry `precision`; the summary adds `lifecycle_not_evaluated` and `lifecycle_needs_review`.
- `GET /api/v2/lifecycle/catalog-sources` (Devices read): each online source with last run, last good load, age colour
  (amber after 7 days, red after 30 or never), entry counts; the uploadable page forms with the page address; the last
  uploads (uploader masked for the aiview persona); the entries that need review.
- The Catalog tab shows the sources, the upload control (security_admin), the review list and the operator catalog; the
  fleet table shows `NOT_EVALUATED` / `NEEDS_REVIEW` in the support columns and dates given as a month as the month.

## 8. Product Owner decisions (2026-11-14)

Asked by the PO session on 2026-11-14; answer (verbatim): "İkisini ekle, FROZEN".

| # | Question | Decision |
| --- | --- | --- |
| D-L1 | Add `www.checkpoint.com` for the life cycle policy page | **Approved**: exactly `/support-services/support-life-cycle-policy/`; the fetcher enforces the path, the proxy egress policy stays address-based. Uploads stay as the fallback |
| D-L2 | Add `www.paloaltonetworks.com` for the two end-of-life pages | **Approved**: exactly the two paths of section 3.1, enforced the same way |
| D-L3 | Fortinet: portal account or operator rows | **Operator rows** (no portal account, no new host) |
| D-L4 | Age limits | **30 days** for an online source, **180 days** for an upload or an operator row |

The two hosts must also be allowed by the corporate proxy; a refusal there shows as a failed run of that source
(`HTTP_403` or a connection failure), never as fresh data.

## 9. Acceptance

- Parser and fetcher tests on synthetic pages shaped like the vendors' pages of 2026-11-13; on that day the parsers were also run
  locally against the public pages (not stored in the repository): Cisco 9.18 bulletin 1 entry, Firepower 4100 bulletin
  4 entries; Palo Alto software 24 trains, hardware 38 models; Check Point 150 entries, 6 needing review (the Spark
  table's two-digit years and three legacy versions listed twice), 0 guessed dates.
- After the first live run, counts only: entries per source and state, devices per part state, and a spot check of the
  estate's trains (ASA 9.18 and 9.22, PAN-OS 11.1 and 12.1, R81.20, R82) against the vendor pages.

## Cross-references

- `docs/design/LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` (DRAFT; the view, D4 and D8)
- `docs/design/ADVISORY_MATCHING_CONTRACT.md` (the fetch workload and ingest pattern this reuses)
- `docs/design/PO_DECISION_RECORD_2026_11_10_ADVISORY_INTAKE_PROXY_SOURCES_AND_FIX_RULES.md` (approved hosts, TLS rule)
