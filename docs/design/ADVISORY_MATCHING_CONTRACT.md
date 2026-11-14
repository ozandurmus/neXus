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
- **Requests.** `GET` only, no body, a fixed `User-Agent` that names the software (`neXus-advisory-fetch/1.0`) and
  nothing about the estate, at most one request per second per host, 60 s timeout, at most 16 MiB per document,
  redirects not followed. A request is sent only to a host and
  path prefix of section 2, checked in code immediately before sending.
- **Cisco selection.** A run fetches the files the change list names as changed in the last
  `UI2_ADVISORY_CISCO_DAYS` days (default 7). The workload keeps no state, so the first load is one run started by
  hand with a larger value (1100). A fetched file is handed on only if its text contains the ASA software family
  name; the others are dropped unread.
- **Hand-over.** Each document goes to the internal endpoint of section 4 with the source key and the address it
  was read from. The workload reads nothing back except the status code.
- **Schedule and switch.** A CronJob, once a day, created suspended; it can also be started by hand. The schedule is
  independent of every collection schedule. It runs the service image with its own role (`advisory-fetch`), and a
  service release re-applies it with the new image without changing whether it is suspended.
- **Code fence.** The workload's code lives in a package of `platform-core` that imports JDK classes only, so it
  cannot reach a repository, a credential or a device transport; a test enforces it.

## 4. Ingest (in `ui2-service`)

`POST /internal/advisories/{source}` on the internal port, authenticated by the fetcher's own token (header
`X-Nexus-Advisory-Token`, Secret `ui2-advisory-fetch-token`, compared in constant time; this token authorises nothing else, and no other token
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
- **Upper bound of an affected range (measured on the vendor's full set, 2026-11-10).** The vendor writes the
  first fix as the bound and the backported fixes of later maintenance releases as change points. A release between
  them that has no point of its own stays affected, so the range ends at its highest unaffected change point, not at
  the written bound. The vendor's own lists of affected versions agree in every consistent case.
- `version` alone with a status: affected from that version to the end of the train. With only `changes[]`: the
  same change-point rule, and the range ends at its highest unaffected change point (the vendor's lists never name
  a release above it as affected); without any change point it runs to the end of the train.
- The mirrored forms with status `unaffected` and `changes[]` to `affected` follow the same logic with the statuses
  exchanged.
- Any other form (free text, an empty version, status `unknown`): the advisory is `NEEDS_REVIEW`.
- Blank padding around a version, bound or change point in the vendor's record is removed; nothing else is.
  A device's own version is never altered.
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
| `NOT_EVALUABLE` | the advisory names the device's product and the device's version is missing or unparsed; or the advisory is `NEEDS_REVIEW` and names the device's train (or the product without a readable train); or the train is named but no rule covers the version |
| `AFFECTED` | the advisory's rule for the device's product and train says the version is affected |
| `FIXED` | the advisory has an affected range in the device's train and the version lies at or above a point the vendor names unaffected |
| `NOT_AFFECTED` | the advisory names the device's product and train (or the whole product) as unaffected |
| (no verdict) | the advisory does not name the device's product, or names it without the device's train; counted as "not listed", never shown as a finding |

A `NEEDS_REVIEW` advisory keeps the trains it names, including those of entries that could not be read, so that it
counts as an open question only for devices of those trains.

Device names in the read interface travel under the key `hostname` next to `device_id`, which is what the masked
projection pseudonymizes; every advisory row and every device row of an advisory carries `advisory_id`, and only on
such rows are the version, title and fix text exempt from address masking (a four-part version is not an address).
A link is returned only when it is `https` on the source's own host or its advisory site (`fortiguard.fortinet.com`,
`www.fortiguard.com`, `sec.cloudapps.cisco.com`, `tools.cisco.com`).

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

## Amendment A-2026-11-13: Check Point advisories

**Status: FROZEN (2026-11-14)** by the engineering session under the Product Owner's instruction relayed by the PO
session ("FROZEN yap"), with the answers to the questions of A.12 recorded there. Drafted 2026-11-13. It lifts "Check
Point advisories" out of the "Not in this contract" list and defines the proposed fix rules of PO decision 2026-11-10
question 4 (`PO_DECISION_RECORD_2026_11_10_ADVISORY_INTAKE_PROXY_SOURCES_AND_FIX_RULES.md`) for Check Point. The confirm
action of those rules is specified here (A.9) and built in a second step; until then a proposal stays pending. Verdicts
that use Live Patch facts stay out of this contract (A.10). A.1 to A.8 and A.10 are built with this amendment; the
confirm action of A.9 is the next step (PO 2026-11-14).

**Correction C-2026-11-14 (fail closed).** An independent review of the first build (`ARGON_CPADV_REVIEW_1114`, kept by
the PO session) found that a failed or changed page, a missing CVE record or an unread advisory could leave a Check Point
device shown as judged ("None affected"). On the PO session's instruction ("fix every confirmed one fail-closed") the
rules below were corrected before the first ship: page acceptance (A.4), per-train usability (A.5), the run outcome
(A.3), and the trains an open question covers (A.7, A.8). Nothing in the corrections makes a verdict less strict.

### A.1 What Check Point publishes (measured 2026-11-13)

Plain public `GET` requests from a workstation, without cookies or estate data, on 2026-11-13:

- **No machine-readable advisory feed on an approved host.** `support.checkpoint.com/security-advisories` is a script
  page; its list comes from an undocumented JSON interface on `iapi-services-ucs.checkpoint.com`, a host that is not
  approved (question Q1). The sk articles are readable pages, but nothing lists which sk articles are advisories.
- **CPAI advisories** (`advisories.checkpoint.com`) are IPS protections for other vendors' products. They are not
  advisories about Check Point products and are not used.
- **Jumbo Hotfix Accumulator "All Resolved Issues" pages** on `sc1.checkpoint.com` are static pages, one per Gaia
  train. Each take is a section `id="All_Resolved_Issues__Take_<n>"` with a table of three columns (ID, Product,
  Description). The Description names CVE ids, for example "Resolved CVE-... Refer to sk...". The four pages of the
  trains below named 61 distinct CVE ids. The older addresses (`.../R82/R82.00/R82-List-of-all-Resolved-Issues.htm`
  and alike) are redirect pages and are not used; the R81.10 page has an older layout and is not read.
- **CVE records (CVE JSON 5)** of Check Point as CVE Numbering Authority carry `affected[].versions[].version` as free
  text in three forms seen: `"R81.20 with Jumbo Hotfix Take 165 or below"` (one entry per train, records of 2026),
  a bare train, and a sentence `"<products> versions R81.20, R81.10, R81 and <products> versions ..."` (records of
  2024). None uses `lessThan`, `versionType` or `changes`. `references[]` is the sk article. Listing records by
  assigner needs CNA credentials, so the record of a CVE is reached only through its id.

### A.2 Source (section 2)

| Source key | Hosts | Documents | Notes |
| --- | --- | --- | --- |
| `checkpoint` | `sc1.checkpoint.com`; `raw.githubusercontent.com` | the four fixed pages `/documents/Jumbo_HFA/<train>/All_Resolved_Issues.html` for `R81.20`, `R82`, `R82.10`, `R82.20`; then, for every CVE id those pages name, `/CVEProject/cvelistV5/main/cves/<year>/<number div 1000>xxx/CVE-<year>-<number>.json` | coverage: the CVE ids the resolved-issues pages of these trains name (A.5) |

Rule 1 holds: the page addresses are constants of the build; a record address is built from a CVE id read from one of
those pages and carries nothing about the estate. `cveawg.mitre.org` stays approved and unused (the same record is
served by the public list). `support.checkpoint.com` is a link host only (the sk article of an advisory); it is not
fetched.

### A.3 Fetch (section 3)

The four pages are fetched and handed over first, in the order above; then the record of every CVE id they name,
without repeats, at most 1000 per run. A page that fails is counted and the run goes on; a certificate failure stops
the source (PO stop rule). With no page read the run ends with `NO_PAGE_READ`. A run in which no page was **accepted**
(A.4) closes `FAILED` (`NO_PAGE_ACCEPTED`) whatever else it received, so it never counts as a load. Pacing (one request per second) is per
host. The default source list of the workload includes `checkpoint`; no manifest change is needed.

### A.4 Ingest and storage (sections 4 and 5)

- **A page** yields no advisory. Each table line whose Description names a CVE id becomes one statement per CVE id:
  train, take, CVE id, kind (A.6), the Product cell, the sk article the line refers to, and the line itself (public
  text, at most 300 characters). The statements of a train are replaced as a set only when its page is **accepted**:
  at least one take section and one statement, every CVE line read (a line of another shape is counted as rejected and
  not guessed), every statement storable, and every take of the stored set still present (an accumulator's history
  only grows; a page that lost a take was cut short or changed form). A page that is not accepted changes nothing: the
  last good statements stay, and the train ages towards `NOT_EVALUATED` (A.5). Each accepted page records the time of
  its train's last good load (`checkpoint_jumbo_page_load`).
- **A record** yields one advisory `checkpoint:<CVE id>`: title (`cna.title`, else the English description), severity
  and score (CVSS 4.0, else 3.1, else 3.0 of `cna.metrics`), published and updated dates of `cveMetadata`, link = the
  record's sk article, else the sk article of a resolved-issues line. Its rules are built from the record and the
  stored statements of the same CVE id (A.7). A record that is not `PUBLISHED` or not JSON is rejected.
- Storage: migration `V213` adds `checkpoint` to the source checks of `advisory` and `advisory_feed_run` and the table
  `checkpoint_jumbo_statement` (train, take, CVE id, kind, product text, sk article, line, page address, page SHA-256,
  load time) and `checkpoint_jumbo_page_load` (train, time of the last accepted page, statement count, page address and
  SHA-256). Public data only, no audit trigger, as V205.

### A.5 Products and the comparison basis (sections 6 and 7, rule 4)

| Product key | Devices | Compared version (example) | Parsed as |
| --- | --- | --- | --- |
| `checkpoint:gaia-gateway` | Check Point devices with role `gateway` (appliances, open servers, ClusterXL and VSX members) | `R81.20 Jumbo Take 119` | train `R81.20`, take 119 |
| `checkpoint:gaia-management` | Check Point devices with role `management_server` (Security Management, Multi-Domain Server) | `R82 Jumbo Take 127` | train `R82`, take 127 |

- The compared version joins two values neXus already stores: the software version (`R81.20`) and the hotfix level
  read from `cpinfo -y all` (`R81.20 Jumbo Take 119`). It is used only when the hotfix level names the same train.
  No hotfix level: `VERSION_UNKNOWN` with `JUMBO_TAKE_MISSING` (never "take 0"). Another train: `VERSION_UNKNOWN` with
  `JUMBO_TRAIN_MISMATCH`. A hotfix level in another form: `VERSION_UNKNOWN` with `VERSION_UNPARSED`. Coverage
  (`NOT_COVERED`) is decided by the software version alone.
- Takes compare as whole numbers inside one train; two trains are never compared. A Jumbo Hotfix Accumulator take
  contains every earlier take of its train, so a take at or above the take whose resolved issues list a CVE carries
  that resolution.
- **Usability per train (rule 4 per train).** A Check Point device with a readable version is judged only when its
  own train is usable: its page was accepted in the last 30 days, and every CVE id that page names has a stored
  advisory. Otherwise the device is `NOT_EVALUATED` with `TRAIN_NEVER_LOADED`, `TRAIN_STALE` or
  `TRAIN_RECORDS_MISSING`; it is never "nothing matched". The source table lists each train's state.
- `NOT_COVERED` with a reason: a Quantum Spark firmware (`R81.10.15`, three parts): `SPARK_NOT_COVERED`; a Gaia train
  without a page read here (`R81.10`, `R81`, ...): `TRAIN_NOT_COVERED`; a Check Point device without a known role: no
  product.
- **Comparison basis shown (rule 4):** "Check Point: N advisories from the CVE records named by the resolved-issues
  pages of R81.20, R82, R82.10 and R82.20", with oldest and newest advisory date and the age of the last good load.
  **Coverage limit, stated where the basis is shown:** a Check Point CVE that no resolved-issues page of these trains
  names yet (published but not yet resolved in a Jumbo take, or resolved by a separate hotfix only) is not seen by
  this source (question Q1).

### A.6 Kinds of resolved-issues lines

- `FIX` when the Description contains `Resolved CVE-`, `to fix CVE-` or `to fix: CVE-`, or starts with
  `CVE-<id> - ` (optionally after `UPDATE: `).
- `MENTION` for any other line naming a CVE id, for example "Check Point response to CVE-...", or a defence described
  in other words. A mention is not read as a resolution.

### A.7 Rules (per product key and train)

| What the record says for the product and train | Rule | Verdicts |
| --- | --- | --- |
| A Check Point record: `"<train> with Jumbo Hotfix Take M or below"` | affected range up to take M | take <= M `AFFECTED` (`IN_RANGE`); above M `FIXED`. Self-check: a `FIX` line of the same train at a take <= M contradicts it: the advisory is `NEEDS_REVIEW` (`SELF_CHECK_FAILED`) |
| A Check Point record: the train without a take | proposal from the `FIX` line | take >= the resolving take `FIXED` (`JUMBO_FIX_INCLUDED`); below it pending (`FIX_RULE_UNCONFIRMED`), **never `AFFECTED`**; several `FIX` takes: at or above the highest `FIXED`, below pending (`MULTIPLE_FIX_TAKES`); only mentions: pending (`FIX_STATEMENT_UNCLEAR`); no line: `NOT_EVALUABLE` (`NO_FIX_TAKE_PUBLISHED`) |
| A Check Point record naming only part of the gateways (Maestro, scalable platforms, CloudGuard Network) | as the two rows above, without an affected range | above take M, or at or above the resolving take, `FIXED`; otherwise `NOT_EVALUABLE` (`PRODUCT_SUBSET_UNKNOWN`): neXus cannot tell these gateways apart |
| A record of another CVE authority (a third-party component Check Point resolved) | proposal from the `FIX` line, for both product keys, only on the trains whose page names the CVE | as the proposal row; parse note `THIRD_PARTY_CVE` |

- The product names of a Check Point record are matched exactly against a closed table (gateway: Quantum (Security)
  Gateway(s), Security Gateway(s), Quantum Appliances, ClusterXL; management: (Quantum) Security Management,
  Multi-Domain Security Management, Multi-Domain Server; both: Gaia, Gaia OS; part of the gateways: Quantum Maestro,
  Quantum Scalable Chassis, Scalable Platforms, CloudGuard Network; not covered: Spark, Spark Gateway, Quantum Spark).
  A text that is not a known name is split at commas and "and" outside parentheses and each part matched; one
  unknown part makes the advisory `NEEDS_REVIEW`.
- In the sentence form each group's own product names decide its trains. A product class the entry's `product` field
  names but no group names (a field listing management while the sentence speaks of gateways) gets the sentence's
  trains as a train named without a take (proposal row, never `AFFECTED`) when the sentence has one group; with several
  groups the advisory is `NEEDS_REVIEW` (`RECORD_CONFLICT`).
- The record decides which products are named (rule 3, vendor first). A train a resolved-issues page names but a
  parsed Check Point record does not name for one of its products gets the proposal row for that product (never "not
  listed"). A Check Point record that names no Gaia product (only Spark, for example) for a CVE a Gaia page names is a
  contradiction: `NEEDS_REVIEW` (`RECORD_CONFLICT`).
- Product names are compared by the product key they stand for (gateway, management): a subset name and a full gateway
  name both stand for gateways.
- The fix statement shown is "<train> Jumbo Take <n>: <the resolved-issues line>", or "Affected up to <train> Jumbo
  Take <M> (vendor CVE record)".
- Rule 6 holds: every affected verdict is "affected by version; exposure depends on configuration" (many Check Point
  advisories concern one blade, such as Remote Access or Mobile Access).

### A.8 Parse states and what stays open

| Code word | State | Effect |
| --- | --- | --- |
| (none) | `PARSED` | rules as A.7 |
| `THIRD_PARTY_CVE` | `PARSED` | proposals only (A.7 last row) |
| `UNKNOWN_PRODUCT`, `UNKNOWN_VERSION_FORM`, `UNKNOWN_STATUS`, `NO_AFFECTED`, `RECORD_CONFLICT`, `SELF_CHECK_FAILED` | `NEEDS_REVIEW` | `NOT_EVALUABLE` for both product keys on every train named anywhere in the record's version texts (read or not) or in its resolved-issues lines; also the whole product when the record has no readable `affected` list or a version text names no train |

### A.9 Pending proposals and their confirmation

- A verdict whose basis is `FIX_RULE_UNCONFIRMED`, `MULTIPLE_FIX_TAKES` or `FIX_STATEMENT_UNCLEAR` is **pending**: it
  is shown as "Pending confirmation" with the proposed take and the line it was read from, and it is counted only in
  its own `pending` count (per device and per advisory). It is not in `affected`, not in `not_evaluable` and not in
  any summary counter, as PO decision question 4 requires.
- **Confirm action (second step, not built yet).** A `security_admin` confirms or rejects one proposal: advisory,
  product key, train, proposed take and the SHA-256 of the page line it was read from. Confirmed: below that take the
  verdict is `AFFECTED` (`CONFIRMED_FIX_RULE`), at or above it `FIXED`. Rejected: the verdict stays `NOT_EVALUABLE`
  with `FIX_RULE_REJECTED` and leaves the pending count. A confirmation is bound to the line it was given for: when
  the stored line or take changes, the proposal is pending again. Each decision is audited (actor, time, the four
  values), has a CLI equivalent, and is a new action in the action registry (security_admin only).

### A.10 Live Patch

Unchanged: no verdict uses a Live Patch fact. On the device list of a Check Point advisory, a device whose stored
`cplp list` rows name one of the advisory's CVE ids or its sk article shows a label "Live Patch <id>: <status> (shown
only; not used in the verdict)". `FIXED_BY_LIVE_PATCH` and the 12-week deadline of the design stay for their own
amendment.

### A.11 Acceptance

- Parser, comparator and flow tests on synthetic documents in the shapes of A.1: every line kind, every record form,
  the self-check, each `NEEDS_REVIEW` code word, pending counting, `NOT_COVERED` reasons, and the Live Patch label
  that leaves the verdict unchanged.
- After the first live load, counts only: statements per train and kind; advisories per parse state and note;
  Check Point devices per state and reason; devices affected and pending. Three advisories are checked by hand
  against their sk articles before the Check Point column is called correct.

### A.12 Questions for the Product Owner, with the answers

- **Q1. A new host for full coverage.** `iapi-services-ucs.checkpoint.com` serves Check Point's own list of its
  security advisories (161 on 2026-11-13, with CVE, sk article and affected take per train, including advisories not
  yet resolved in a Jumbo take). It is the interface behind the vendor's advisory page, but undocumented. Approve it
  as a second Check Point document host (one fixed address, same rules as A.2), or keep the coverage limit of A.5?
  **Answer (PO 2026-11-14): "Hayır".** The host is not added; the coverage limit of A.5 stays and is stated on screen.
- **Q2. Take statements without confirmation.** This draft treats a Check Point record's `"<train> with Jumbo Hotfix
  Take M or below"`, checked against the resolved-issues page, as the vendor's machine-readable statement: such
  verdicts are final without an administrator's confirmation (as for the other vendors). Agree, or should these also
  wait for confirmation? **Answer (PO 2026-11-14): agreed.** A take statement consistent with the resolved-issues page
  is final without review.
- **Q3. Order.** Build the confirm action (A.9) as the next step, before the first live load is called correct?
  **Answer (PO 2026-11-14): the confirm action is the next step, not part of this change.**
