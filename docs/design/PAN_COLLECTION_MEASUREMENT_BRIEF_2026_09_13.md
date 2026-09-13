# Palo Alto collection — the question set and the measured-method run list

## Status

**DRAFT — MEASUREMENT BRIEF. NOT AUTHORITY, NOT COMMAND APPROVAL.**

This document authorizes nothing and approves no command. It is the question
set and the exact, ordered, read-only XML API request list the Product Owner
will run **once**, against **one** Palo Alto firewall and **one** Panorama, so
that a per-vendor Palo Alto **collection** contract can be written from
measurement instead of from the existing Python or from general product
knowledge. It is the Palo Alto twin of
`CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md` (DRAFT),
written *before* the measurement rather than after it — that document records
what was found; this one states what to ask and how to run it, and records
nothing yet. It contains no implementation, contacts nothing itself, and
approves no route: every request below is a **candidate to measure**, not a
network-device command gate entry. Gate entries are a separate act, one per
route, under `docs/AI_DEVELOPMENT_PROTOCOL.md`, after this measurement is read.

## 1. Scope and authority

**What this brief is for.** `PO_DECISION_RECORD_2026_09_13F_COLLECTION_
TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §1 and §6 fix, for Palo Alto,
that collection travels over the HTTPS XML API and lifts the collection gate
for two collection types — *inventory* and *configuration* — across three
method classes, and for nothing else:

1. **Direct-firewall API reads** — to the firewall's own management address,
   for actual/effective evidence.
2. **Panorama `target=<serial>` reads** — per-device runtime data taken
   through Panorama, where the estate prefers it.
3. **Panorama's own configuration read** (no `target`) — for Template,
   Template Stack and Device Group provenance. `13F` §1 and §6 record this as
   the read the Product Owner **declined for discovery** on 2026-09-13 and
   **accepts here for configuration**.

Every concrete route inside these three classes still needs its own
network-device command gate row before it is implemented (`13F` §6, second
paragraph) — the lift is at the method-class level, not the route level.
`13F` §6 also fixes, for Palo Alto, what stays closed regardless of what this
measurement finds: no commit, no push, no write of any class; no backup
route (`RB.x` class 1 stays under its own contract); no failover or HA
state change; and **no direct SSH to a Palo Alto firewall** — `PO_DECISION_
RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md` §4a SB-11 gives the
measured reason: `set cli config-output-format xml` applies to configuration
commands only, not to operational commands, so a shell transport would mean
parsing a human-formatted table where the API returns named elements —
`AGENTS.md` "Vendor semantics law" holds a column heading is even less of a
contract than a field name.

**What this brief is not.** It is not the collection contract. It is not an
implementation. It does not run anything, and running it is the Product
Owner's own act (`13F` cross-reference chain; `AGENTS.md` "Authority
hierarchy" item 7 — chat is never authoritative, so the run and its findings
must land in a follow-on findings document, not only in a session).

## 2. The question set

Each question is phrased so its answer is observed behaviour, never opinion.
Numbered `Q-1`…`Q-16` so section 3's requests can cite them.

**Session and transport**

- **Q-1.** How is a session key obtained (`keygen`), and how long is it valid?
  Can it be sent only in a header, or does the API also accept it as a query
  parameter — and if the latter exists, is it ever the only accepted form for
  some route?
- **Q-2.** What does a failure look like: an HTTP status code, an XML error
  element on an otherwise-200 response, or a success envelope (`status=
  "success"`) that itself carries an error in a nested field?

**Identity**

- **Q-3.** Which route returns the identity facts — serial, hostname, model,
  software version, HA state — and under which element names, at the exact
  nesting path?

**Interfaces and addresses**

- **Q-4.** Which route enumerates interfaces **with every address on them**?
- **Q-5.** How is a high-availability floating (virtual/cluster) address
  distinguished from an interface's own address in that same response — a
  separate element, a flag, or not distinguished at all?

**Routing**

- **Q-6.** Which route returns the routing table?
- **Q-7.** Is that response paginated, and is it truncated on a large table —
  and if so, at what count or byte size, and how would truncation be
  detected from the response itself (a count element, a continuation token,
  silence)?

**Configuration — asked as three separate questions, never assumed equivalent**

- **Q-8.** What does `effective-running` return, and how large is it on a
  typical device?
- **Q-9.** How does `effective-running` differ from the **active**
  configuration (`xpath=/config`) — in content, not only in name?
- **Q-10.** How does `effective-running` differ from **`merged`** — in
  content, not only in name?

**Virtual-system scope**

- **Q-11.** Does a virtual-system-scoped read exist for each of Q-4, Q-6 and
  Q-8–Q-10, and if so, how is the virtual system selected in the request (a
  `vsys` parameter, a different xpath, a different target form)? Ask this
  once per read — do not assume a positive answer for one implies it for
  the others.

**Panorama plane**

- **Q-12.** Which of the reads above are available through Panorama with
  `target=<serial>`, and does the response differ in shape from the same
  read taken directly against the firewall?
- **Q-13.** What does Panorama's own configuration read (no `target`) return
  for Template, Template Stack and Device Group assignment, and under which
  element paths?

**Cross-plane and secrecy**

- **Q-14.** Where the same fact is available on both planes (e.g. HA state,
  software version), does it ever disagree between a direct read and a
  Panorama-proxied read?
- **Q-15.** Can any response in this set carry a credential, API key, or
  other secret value in its body?

**Configuration retention (feeds section 4)**

- **Q-16.** Are two consecutive `effective-running` reads of an unchanged
  device byte-identical?

## 3. The request list

**Reminder, stated once, before any request: record element names, nesting
paths, response shape, counts and sizes only. Do not paste back an address,
hostname, serial, certificate subject or any configuration content into any
record of this measurement** (`AGENTS.md` "Sensitive identity reporting
law": compare locally, report the relationship, never the value; "Raw-
evidence law": parse to the minimum needed and discard the raw response).

Credentials are shown only in a POST body or a header form, never in a URL,
because the existing Python's older runtime path sends the username,
password and every session key as URL query parameters, and `PAN_AUTH_
TRANSPORT_CONVERGENCE_AUDIT.md` AC-3 calls that pattern an **urgent, live
security exposure** — this brief does not repeat it even as a measurement
form.

Each request is annotated with the question it answers and its action class.
`<placeholder>` values stand for estate values decided at run time; none is
filled in here.

```
# R-1 — direct firewall: obtain a session key. Answers Q-1. Read-only, class 0.
#       Credentials in the POST body, never the URL.
POST https://<firewall-mgmt-address>/api/
  Content-Type: application/x-www-form-urlencoded
  Body: type=keygen&user=<user>&password=<password>
# RECORD: element path carrying the key; whether an expiry is stated anywhere
# in the response or only inferred from a later 401/403; response size.

# R-2 — Panorama: obtain a session key. Answers Q-1. Read-only, class 0.
#       Same form as R-1, against Panorama's own management address.
POST https://<panorama-mgmt-address>/api/
  Content-Type: application/x-www-form-urlencoded
  Body: type=keygen&user=<user>&password=<password>
# RECORD: same as R-1; whether the two keys (firewall vs Panorama) differ in
# format or length.

# R-3 — direct firewall: identity. Answers Q-3. Read-only, class 0.
#       Session key in a header, never the URL.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><system><info/></system></show>
# RECORD: element names/paths for serial, hostname, model, software version,
# HA state; response size.

# R-4 — direct firewall: interfaces with addresses. Answers Q-4, Q-5.
#       Read-only, class 0.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><interface>all</interface></show>
# RECORD: element path per interface; element path per address; whether a
# floating/HA address is a separate element, a flag on the same element, or
# not distinguished; count of interfaces and count of addresses returned.

# R-5 — direct firewall: routing table. Answers Q-6, Q-7. Read-only, class 0.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><routing><route></route></routing></show>
# RECORD: row count; presence of any count/continuation element; response
# size; whether the count looks improbably round (a truncation hint).

# R-6 — direct firewall: active configuration. Answers Q-9. Read-only, class 0.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=config&action=show&xpath=/config
# RECORD: response size; top-level element categories present (by category
# name only, e.g. "address objects", "device settings" — never an example
# value).

# R-7 — direct firewall: effective-running configuration. Answers Q-8, Q-16.
#       Read-only, class 0. Run TWICE, with an interval between the two
#       reads and no change made to the device in between, to answer Q-16.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><config><effective-running></effective-running></config></show>
# RECORD: response size on each read; a digest of each read (e.g. its byte
# length and a locally computed hash — the hash value itself is not
# sensitive and may be recorded for comparison) and whether the two match;
# top-level element categories present, by category.

# R-8 — direct firewall: merged configuration. Answers Q-10. Read-only, class 0.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><config><merged></merged></config></show>
# RECORD: response size; top-level element categories present, by category;
# a one-line diff-shape note against R-6 and R-7 (more/fewer/same categories
# — categories named, not their content).

# R-9 — direct firewall: virtual-system-scoped interfaces. Answers Q-11
#       (for Q-4). Read-only, class 0. Run only if the firewall has more
#       than one virtual system.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><interface>all</interface></show>&vsys=<vsys-id>
# RECORD: whether the vsys parameter is accepted on this route at all; if
# accepted, whether the response is scoped or identical to R-4.

# R-10 — direct firewall: virtual-system-scoped routing. Answers Q-11
#        (for Q-6). Read-only, class 0. Same condition as R-9.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><routing><route></route></routing></show>&vsys=<vsys-id>
# RECORD: same shape as R-9, for the routing table.

# R-11 — direct firewall: virtual-system-scoped effective-running. Answers
#        Q-11 (for Q-8-Q-10). Read-only, class 0. Same condition as R-9.
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-1>
  Body: type=op&cmd=<show><config><effective-running></effective-running></config></show>&vsys=<vsys-id>
# RECORD: whether vsys is accepted; if accepted, response size relative to
# R-7 (smaller, scoped, or identical).

# R-12 — Panorama: managed-device enumeration, to select a target serial for
#        R-13-R-16. Answers no question directly; a precondition for
#        target=<serial> reads. Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=op&cmd=<show><devices><all></all></devices></show>
# RECORD: nothing new — PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md
# already records this route's shape in full. Use it only to obtain a
# serial to place in <target-serial> below; do not re-record its fields.

# R-13 — Panorama, target=<serial>: identity, proxied. Answers Q-12 (for
#        Q-3). Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=op&cmd=<show><system><info/></system></show>&target=<target-serial>
# RECORD: whether this route is even accepted with a target parameter; if
# so, whether the element paths match R-3 exactly.

# R-14 — Panorama, target=<serial>: interfaces, proxied. Answers Q-12 (for
#        Q-4). Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=op&cmd=<show><interface>all</interface></show>&target=<target-serial>
# RECORD: element-path match against R-4; count match.

# R-15 — Panorama, target=<serial>: routing, proxied. Answers Q-12 (for
#        Q-6). Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=op&cmd=<show><routing><route></route></routing></show>&target=<target-serial>
# RECORD: element-path match against R-5; row-count match.

# R-16 — Panorama, target=<serial>: HA state, proxied. Answers Q-3 (HA
#        state specifically), Q-12, Q-14. Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=op&cmd=<show><high-availability><state></state></high-availability></show>&target=<target-serial>
# RECORD: element path for HA state; whether it matches the HA-state value
# already visible in R-3 or R-12's enumeration (MATCH/MISMATCH, never the
# value).

# R-17 — Panorama, target=<serial>: active configuration, proxied. Answers
#        Q-12 (for Q-9), Q-14. Read-only, class 0.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=config&action=show&xpath=/config&target=<target-serial>
# RECORD: response size relative to R-6; category-level shape comparison.

# R-18 — Panorama's own configuration, no target: Template / Template Stack
#        / Device Group provenance. Answers Q-13. Read-only, class 0. This
#        is the read 13F S1/S6 says was declined for discovery and is
#        accepted here for configuration.
POST https://<panorama-mgmt-address>/api/
  Header: X-PAN-KEY: <key-from-R-2>
  Body: type=config&action=show&xpath=/config
# RECORD: element paths under which Template, Template Stack and Device
# Group assignment appear; response size; whether the assignment for
# <target-serial> can be located in this response without a second call.

# R-19 — deliberate failure probe: an expired/invalid key. Answers Q-2.
#        Read-only, class 0 (no state is changed; the request is rejected).
POST https://<firewall-mgmt-address>/api/
  Header: X-PAN-KEY: <deliberately-invalid-value>
  Body: type=op&cmd=<show><system><info/></system></show>
# RECORD: HTTP status code; whether the XML root carries status="success" or
# status="error"; the shape of the error element (not its message text, if
# the message text could ever echo a submitted value).
```

Nineteen requests. `R-9`–`R-11` run only where the firewall being measured is
multi-virtual-system; recording "not applicable, single-vsys firewall" is
itself the answer to Q-11 for that route and is not a gap.

## 4. What must be recorded about the configuration reads specifically

`13F` §3 (CF-1, CF-2, CF-3) requires one read and two outputs — a sanitized
view copy and an untouched encrypted backup copy whose digest is compared
for change. Recorded here, from R-7's two runs and R-6/R-8:

- **The size of `effective-running` on the device measured** (R-7), stated as
  a byte count, not the content.
- **Whether two consecutive `effective-running` reads of the unchanged
  device are byte-identical** (R-7's digest comparison, Q-16) — this is the
  question CF-2's "digest recorded, later differing read reported as a
  change" mechanism depends on; if two reads of a genuinely unchanged device
  are *not* byte-identical (a timestamp element, a rotating nonce, ordering
  that is not stable), the digest-based change-detection design in `13F`
  CF-2 needs a normalization step before it can be trusted, and that is a
  contract-level finding for the collection contract to carry, not something
  this brief resolves.
- **Which element categories would have to be removed for the sanitized view
  copy** — described by category, never by example: at minimum, whatever
  category carries credential material, shared-secret or pre-shared-key
  values, and certificate private-key material, if any such category is
  present in `effective-running` at all. State plainly if none is found, as
  a finding in its own right (Q-15 answered for this route).

## 5. The Panorama-side questions, kept separate

`13F` §1 gives the direct-firewall and Panorama-proxied planes different
evidence roles — `AGENTS.md` "Palo Alto": Panorama is intent and provenance,
the direct firewall is actual, primary configuration evidence is
`effective-running`. Kept separate here rather than folded into section 3's
per-route entries:

- **What `target=<serial>` reads are for.** R-13–R-17 test whether the
  runtime facts a direct read supplies (identity, interfaces, routes, HA
  state, active configuration) are also reachable through Panorama without
  contacting the firewall directly, and whether the estate would ever prefer
  the proxied form (e.g. a firewall unreachable from wherever collection
  runs, but reachable from Panorama).
- **What Panorama's own configuration read is for.** R-18 is not a proxied
  version of R-6 — it is Panorama's *own* configuration document, the one
  that carries Template, Template Stack and Device Group assignment, facts
  that do not exist on the firewall's own configuration at all.
- **Whether a fact appearing in both planes ever disagrees** (Q-14). Record
  every cross-plane pair checked (R-3 vs R-13, R-4 vs R-14, R-5 vs R-15, R-3
  or R-12 vs R-16, R-6 vs R-17) as `MATCH` / `MISMATCH` / `NOT_EVALUABLE`
  — never the two values side by side (`AGENTS.md` "Sensitive identity
  reporting law"). A `MISMATCH` here is exactly the kind of management-plane-
  observation-versus-direct-device-runtime-truth divergence `AGENTS.md`
  "Evidence laws" names as a distinct fact from either read being simply
  wrong, and is why `AGENTS.md` "Palo Alto" fixes `effective-running` as
  primary evidence rather than letting either plane win by default.

## 6. What this brief deliberately does not ask, and why

- **No commit, push or write of any class.** `13F` §6 lifts collection
  reads only; every request above is `type=op`/`type=config&action=show` —
  no `action=set`, `action=edit`, `action=delete`, `commit`, or
  `commit-all`.
- **No backup command.** `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`
  (`C7`, FROZEN) owns the backup artefact's own read, encryption and
  custody; this brief's R-7 records what `effective-running` looks like as
  input to that later design, not the backup mechanism itself.
- **No failover or HA state change.** R-16 *reads* HA state; nothing here
  requests a failover, a suspend, or a preemption.
- **No direct SSH to a firewall.** Ruled out at the method-class level by
  `13F` §6 and `13C` §4a SB-11 (§1 above); every request in section 3 is
  HTTPS XML API only.
- **No Check Point question.** Out of scope for this movement; the Check
  Point twin already exists (`CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_
  HANDOVER_2026_09_13.md`).
- **No request whose purpose is to test reachability rather than to read a
  fact.** Every request in section 3 answers a specific question in section
  2; none is a bare connectivity probe. R-19 is the one apparent exception
  and it is included precisely because Q-2 needs a deliberately-invalid
  key to observe the failure shape, not to test whether the firewall is
  reachable — the well-formed requests already establish reachability as a
  side effect.

## 7. The acceptance shape of the answer

A complete measurement record — the follow-on findings document, mirroring
`PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md`'s and `CP_DISCOVERY_
MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md`'s shape — should carry:

1. **A method table**: one row per request (R-1…R-19), the question it
   answered, and whether the request was run, skipped as not-applicable
   (e.g. single-vsys), or failed.
2. **The request forms actually used**, with any deviation from section 3
   noted and reasoned (e.g. a parameter name that section 3 guessed and the
   live API rejected).
3. **An element-binding table**: for each fact this brief asked about
   (identity fields, interface/address fields, route fields, configuration
   categories, HA state, Template/Template Stack/Device Group provenance),
   the concrete XML element path that carries it, exactly as
   `PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` §3b did for discovery.
4. **The Q-16 digest finding**, stated as a fact about the device measured,
   with its consequence for `13F` CF-2 noted if the reads were not
   byte-identical.
5. **Every cross-plane comparison from section 5**, as `MATCH` / `MISMATCH`
   / `NOT_EVALUABLE`, per pair.
6. **An open-verification-items list**, in the shape of `CP_DISCOVERY_
   MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md` §7: anything this
   brief asked that the one run could not settle (e.g. Q-7's truncation
   behaviour, which needs a route with an unusually large table to observe
   directly), carried forward rather than guessed at.

That findings document, plus this brief, are the two inputs the Palo Alto
collection contract is written from — never the existing Python, never
general product knowledge (`13F` §7: `UI2_0_B1_05` is superseded by exactly
such a per-vendor collection contract written from measurement).

## 8. Cross-references

- `PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_
  DECISIONS.md` (FROZEN) — §1, §3, §4, §6, §7: the transport, the
  configuration-retention rule this brief's §4 serves, the inventory scope,
  the method-class lift, and the collection-contract consequence.
- `PO_DECISION_RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md` (FROZEN)
  — §4a SB-11: the measured reason Palo Alto collection is XML API only,
  never a shell transport.
- `PO_DECISION_RECORD_2026_09_13E_PRODUCT_SEQUENCE_AND_SERVICE_
  INDEPENDENCE.md` (FROZEN) — §1: collection (step 3) stays behind its own
  gate until this measurement and its contract exist.
- `CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md` (DRAFT)
  — the Check Point twin this document's shape follows.
- `PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` (DRAFT) — the Palo Alto
  discovery measurement; provenance for the managed-device enumeration this
  brief's R-12 reuses without re-measuring.
- `PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md` (AUDIT ONLY) — the urgent
  credentials-in-URL finding that fixes every request in section 3 to a
  POST body / header form only.
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` §5.1–§5.4 —
  the existing Python's Palo Alto routes and sessions, cited as know-how for
  which questions to ask, never copied as the measured answer.
- `AGENTS.md` — Evidence laws, Identity law, Sensitive identity reporting
  law, Raw-evidence law, Vendor semantics law, Network action taxonomy,
  Palo Alto section.
