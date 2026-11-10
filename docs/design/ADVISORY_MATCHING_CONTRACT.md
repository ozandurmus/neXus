# Advisory matching: storing public security advisories and judging devices by version - contract

## Status

**FROZEN (2026-11-10)** by the engineering session, under the Product Owner's instruction relayed by the PO session
("Record these answers in the design and the decision record. Then build it through the normal lanes and ship
queue."). It implements slice S1 of `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` (a draft, not an authority)
within the decisions D4, D5, D7, D9 and D14 recorded there and in
`PO_DECISION_RECORD_2026_11_10_ADVISORY_INTAKE_PROXY_SOURCES_AND_FIX_RULES.md`.

Terms: an **advisory** is one vendor security bulletin; a **source** is one place advisories are fetched from; a
**train** is a release line of a product (`11.1`, `7.4`, `9.18`); a **verdict** is what neXus says about one device
and one advisory.

**In this contract (part 1):** the advisory store; the outbound fetch workload; parsing of the Palo Alto, Fortinet
and Cisco sources and of the known-exploited catalog; version comparison and verdicts for PAN-OS, FortiOS,
FortiManager and Cisco ASA; the read interface; the Vulnerabilities part of the view.

**Not in this contract** (each needs an amendment here before it is built): Check Point advisories and proposed fix
rules with their confirm action; verdicts that use Live Patch facts; dispositions; the 400-day finding history; the
spreadsheet report; the offline bundle; Infoblox, Broadcom and Radware advisories; notifications.

## 1. Rules that hold everywhere

1. **Match locally.** Only fixed public documents are requested. No request carries a device version, model, serial,
   name or count. The request line, headers and body of every outbound request are constants of the build, apart
   from document addresses that were read from an earlier public document of the same source.
2. **No device contact.** Nothing in this contract sends anything to a managed device or adds a device command.
3. **Vendor first.** A verdict comes from the vendor's advisory. The known-exploited catalog only adds a flag.
4. **Never green by default.** "Nothing matched" is shown with what it was compared against (source, number of
   advisories, oldest and newest advisory date, age of the last load). A source that never loaded, or whose last
   good load is older than 30 days, makes its products `NOT_EVALUATED`, not "no findings".
5. **Strict parsers.** A version, a range or a document form the parser does not fully recognise is not trimmed or
   guessed. The advisory is kept as `NEEDS_REVIEW` and gives `NOT_EVALUABLE` for the products it names.
6. **Version only, said so.** The verdict judges the installed version. It does not know whether the affected
   feature is configured. Every affected verdict is labelled "affected by version; exposure depends on
   configuration".
7. **Untrusted input.** Everything fetched is untrusted: size caps, strict JSON and XML parsing without external
   entities, no HTML ever rendered, links shown only when they are `https` and on the source's host list.

## 2. Sources

| Source key | Host | Documents | Notes |
| --- | --- | --- | --- |
| `paloalto` | `security.paloaltonetworks.com` | `/api/v1/products/PAN-OS/advisories` (every PAN-OS advisory as CVE JSON 5 records, one document) | the product name in the path is a constant; the per-version form of this call is never used |
| `fortinet` | `filestore.fortinet.com` | `/fortiguard/rss/ir.xml`, then the CSAF file each item names in its `csaf:link` (same host, path prefix `/fortiguard/psirt/`) | the feed holds the newest 50 advisories; coverage starts at its oldest item |
| `cisco` | `www.cisco.com` | `/.well-known/csaf/changes.csv`, then the CSAF files it lists (path prefix `/.well-known/csaf/`) that changed inside the fetch window | only files that name the ASA software family are handed on (section 3) |
| `cisa_kev` | `www.cisa.gov` | `/sites/default/files/feeds/known_exploited_vulnerabilities.json` | the exploited flag |

The CVE Program hosts (`cveawg.mitre.org`, `raw.githubusercontent.com` for `CVEProject/cvelistV5`) and the Check
Point hosts (`support.checkpoint.com`, `sc1.checkpoint.com`) are approved and reachable but are not fetched until the
Check Point amendment.

## 3. The fetch workload (`ui2-advisory-fetch`)

A separate, small workload whose only job is to fetch the documents of section 2 and hand them to `ui2-service`. It
exists as its own workload because of a trust boundary (PO decision D4): nothing that can reach the internet may
hold a device credential.

- **Holds nothing.** No neXus key or secret other than its own ingest token; no credential-store key; no database
  account; no service-account token; no access to any device network. It runs as a non-root user with a read-only
  root file system and no added capabilities.
- **Network.** Its network policy allows egress to DNS, to the configured proxy, and to the internal ingest port of
  `ui2-service`; nothing else, and no ingress.
- **Proxy.** Read from the environment (`UI2_ADVISORY_PROXY`, `host:port`), supplied from a ConfigMap that is created
  on the host and is not part of the repository. No proxy authentication. Without the setting the workload does
  nothing and exits with a clear message.
- **TLS.** Certificates are validated against the runtime's public trust store. No custom CA, no trust-all, no
  host-name bypass, on any code path. A validation failure fails that source and is reported in the run.
- **Requests.** `GET` only, no body, `User-Agent: neXus-advisory-fetch/1.0`, at most one request per second per
  host, 60 s timeout, at most 16 MiB per document, redirects not followed. A request is sent only to a host and
  path prefix of section 2, checked in code immediately before sending.
- **Cisco selection.** The first run fetches the files changed in the last `UI2_ADVISORY_CISCO_BACKFILL_DAYS`
  (default 1100) days; later runs the last 7 days. A fetched file is handed on only if its text contains the ASA
  software family name; the others are dropped unread.
- **Hand-over.** Each document goes to the internal endpoint of section 4 with the source key and the address it
  was read from. The workload reads nothing back except the status code.
- **Schedule and switch.** A CronJob, once a day, created suspended; it can also be started by hand. The schedule is
  independent of every collection schedule.

## 4. Ingest (in `ui2-service`)

`POST /internal/advisories/{source}` on the internal port, authenticated by the fetcher's own token
(`ui2-advisory-fetch-token`, compared in constant time; this token authorises nothing else, and no other token
authorises this endpoint). Headers: `X-Advisory-Url` (must be `https`, on the host and path prefix of the source)
and `X-Advisory-Run` (an id the fetcher makes per run). Body: the document, at most 16 MiB.
`POST /internal/advisories/{source}/complete` closes the run with the fetcher's counts and failure reasons.

- The document is parsed in memory by the parser of its source; the parsed advisories replace the stored ones with
  the same id. The raw document is not stored; its SHA-256 and address are.
- A document that fails to parse is counted and logged by form only; it changes nothing that is stored.
- Every run is recorded (`advisory_feed_run`): source, start, end, outcome (`OK`, `PARTIAL`, `FAILED`), documents
  received, advisories stored, advisories needing review, reason.
- Responses carry no stored data: `204`, or an error status with a fixed code word.

## 5. Stored data

Public data only; nothing here is masked for the aiview persona. No audit triggers (nothing is entered by a person).

| Table | One row per | Columns |
| --- | --- | --- |
| `advisory` | advisory | `advisory_id` (`<source>:<vendor id>`, for example `paloalto:CVE-2024-3400`, `fortinet:FG-IR-26-150`, `cisco:cisco-sa-...`), `source`, `title` (at most 300 characters), `severity` (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `NONE`, `UNKNOWN`), `cvss_score`, `cve_ids` (text array), `published_on`, `updated_on`, `source_url`, `parse_state` (`PARSED`, `NEEDS_REVIEW`), `parse_note` (a fixed code word), `content_sha256`, `loaded_at` |
| `advisory_product_rule` | advisory, product and train | `advisory_id`, `product_key`, `train` (or `*`), `rule` (JSON: the normalised range forms of section 6), `fixed_text` (the vendor's fix statement for the train, at most 200 characters, or null) |
| `known_exploited_cve` | CVE | `cve_id`, `date_added`, `ransomware_use` (boolean), `loaded_at` |
| `advisory_feed_run` | run and source | as section 4 |

Verdicts are **not stored** in this part. They are a pure function of these tables and the stored device facts,
computed when the view is read. The daily snapshot for the finding history belongs to a later amendment.

## 6. Products, versions and rules

One comparator per product, each a strict parser of the stored version string.

| Product key | Devices | Stored value (example form) | Parsed as |
| --- | --- | --- | --- |
| `paloalto:pan-os` | Palo Alto firewalls and Panorama | `11.1.10-h7` | train `11.1`, maintenance `10`, hotfix `7` (no hotfix = 0); any other suffix is unparsed |
| `fortinet:fortios` | FortiGate | `v7.4.12 build2902` | train `7.4`, patch `12`; the build is ignored |
| `fortinet:fortimanager` | FortiManager | as FortiOS | as FortiOS |
| `cisco:asa` | Cisco ASA | `9.18(4)50` | the dotted form `9.18.4.50`; train `9.18`; a missing last part is not the same version as `.0` unless the source lists it so |

Every other device has no comparator in this part and reads `NOT_COVERED` with the reason (which source is
missing).

**Palo Alto rule** (CVE JSON 5 `affected[]` entries whose product is `PAN-OS`). Forms seen in the vendor's full set
on 2026-11-10 and their meaning:

- `"<train> All"` / `"All"` / `"<train>.*"` with a status: the whole train (or product) has that status.
  `"<train> None"` with `unaffected`: the train is unaffected.
- `version` with `lessThan` or `lessThanOrEqual`, status `affected`, and `changes[]`: a version of that train is
  affected when it lies in the range, **except** that for a maintenance release with a change point
  (`at: <train>.<m>-h<n>`, `unaffected`) the hotfix decides: below the point affected, at or above it unaffected,
  whatever the range bound says. A bound of the form `<train>*` means the end of the train.
- `version` alone with a status, or with only `changes[]`: affected from that version to the end of the train,
  with the same change-point rule.
- The mirrored forms with status `unaffected` and `changes[]` to `affected` follow the same logic with the statuses
  exchanged.
- Any other form (free text, an empty version, status `unknown`): the advisory is `NEEDS_REVIEW`.
- **Self-check.** 319 of the 359 advisories carry the vendor's own list of affected versions (`x_affectedList`).
  At ingest, every version in that list must come out `AFFECTED` and every change point `unaffected`. One
  disagreement makes the advisory `NEEDS_REVIEW` with the code word `SELF_CHECK_FAILED`.
- Fixed version shown: the change points of the device's train (the hotfix of its own maintenance release first).

**Fortinet rule** (CSAF 2.0). The product tree names ranges as text under a product branch: `>=A|<=B`, `A all
versions`, a single version. `product_status.known_affected` and `known_not_affected` say which apply. Only the
products `FortiOS` and `FortiManager` are kept. The fix statement per train is read from the `vendor_fix`
remediation text (`FortiOS 7.6: Upgrade to 7.6.7 or above`); it is shown as text and is not used to decide. A
product tree without ranges, or a range text in another form, makes the advisory `NEEDS_REVIEW`.

**Cisco rule** (CSAF 2.0). The product tree lists, under the family "Cisco Secure Firewall Adaptive Security
Appliance (ASA) Software", each release train and under it every affected release by its exact dotted version.
A device is affected when its exact version is listed and that release (alone or through an `installed_on`
relationship) is in `known_affected`. The first fixed release is not machine-readable in these files; the view
links the advisory for it.

**Known-exploited flag.** An advisory is "exploited" when one of its CVE ids is in `known_exploited_cve`.

## 7. Verdicts

Per device and advisory, in this order:

| Verdict | When |
| --- | --- |
| `NOT_EVALUABLE` | the device's version is missing or unparsed; or the advisory is `NEEDS_REVIEW` and names the device's product |
| `AFFECTED` | the advisory's rule for the device's product and train says the version is affected |
| `FIXED` | the advisory has an affected range in the device's train and the version lies at or above a point the vendor names unaffected |
| `NOT_AFFECTED` | the advisory names the device's product and train (or the whole product) as unaffected |
| (no verdict) | the advisory does not name the device's product, or names it without the device's train; counted as "not listed", never shown as a finding |

Per device the view shows: affected (and how many of those are exploited, and critical or high), not evaluable,
fixed, and the comparison basis of rule 4. A cluster row shows its worst member. Device state: `EVALUATED`,
`NOT_EVALUATED` (rule 4), `NOT_COVERED` (no comparator), `VERSION_UNKNOWN`.

## 8. Read interface and view

Read access follows the existing Lifecycle view (Devices plane); there is no new write action in this part.

- The Lifecycle payload gains, per device, a `vulnerabilities` object (state, counts, worst severity, exploited
  count, basis) and summary counters: devices with an exploited open advisory, devices affected, devices not
  evaluated, age of the oldest source.
- `GET /lifecycle/advisories`: one row per advisory that has at least one verdict in the estate or needs review:
  id, title, severity, score, CVE ids, exploited flag and date, published and updated dates, source link, parse
  state, counts of devices by verdict.
- `GET /lifecycle/advisories/{id}`: the devices with their verdict, the version compared, the rule that decided,
  and the vendor's fix statement.
- `GET /lifecycle/advisory-sources`: per source the last run, its outcome, advisory count, oldest and newest
  advisory date, and the age colour (amber after 7 days, red after 30).
- The view (`Devices > Lifecycle and security`) gains a Vulnerabilities column and cards on the overview and a
  Vulnerabilities tab with the advisory list, the device list of an advisory, and the source status table.
  Device names follow the existing masked projection; advisory text is public and is shown as plain text.

## 9. Acceptance

- Parser and comparator tests use documents shaped like the real ones of 2026-11-10; the Palo Alto parser must
  classify every entry of the vendor's full set as a known form or `NEEDS_REVIEW` and pass the self-check on the
  rest.
- After the first load on the live system, counts only are reported: advisories per source and parse state,
  devices per state, devices affected, and the comparison with the PO's hand research of 2026-11-10 (design
  section 6.6). A difference is investigated before the view is called correct.
- The fetch workload is shown to hold no secret other than its token and to reach nothing but DNS, the proxy and
  the ingest port.
