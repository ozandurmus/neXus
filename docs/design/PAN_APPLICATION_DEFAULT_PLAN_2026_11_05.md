# Palo Alto `application-default` — plan to prove disjointness (2026-11-05)

**Status: DRAFT — DO NOT FREEZE.** Document only. It authorizes no implementation, and
the new command in section 3 needs Product Owner approval (Network action taxonomy,
"PO Amendment 2026-09-26") and a network-device command gate entry before any use.
No live-system access was used; every repository claim below was read from source.

## 1. What `application-default` means (official documentation)

- Security policy rule *Service* column: `application-default` "allows the application
  only on its standard (default) ports", i.e. the ports PAN-OS defines for that App-ID
  in the content release. It "prevents security evasion by only allowing applications to
  run on their standard ports." Sources (PAN-OS 11.1 admin guide, to be re-fetched and
  quoted verbatim at freeze time; not fetched in this session):
  - https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/policy/security-policy/security-policy-rule-options
  - https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/app-id/use-application-objects-in-policy
  - https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/policy/policy-objects/services (predefined services; already cited in `PanoramaPolicyMapper.PREDEFINED_SERVICES`)
- Already frozen in-repo: `PATH_ANALYSIS_VENDOR_SEMANTICS.md` §6 (FROZEN) quotes this and
  leaves UNKNOWN: per-application default ports (not collected), matching before App-ID
  identifies the app, and interaction with `application any`.
- Not established by documentation (stay UNKNOWN): the port set of *application any*
  with application-default; behaviour of dependent/implicit applications (an app that
  needs a parent such as ssl or web-browsing); applications without TCP/UDP ports
  (IP-protocol, ICMP type); apps whose default is "dynamic".

## 2. Is the data already collected?

Found in source:

| Source | Finding |
|---|---|
| `PanoramaPolicyMapper.objectValues` (case `application`) | A **configured** (custom) application entry stores `default/port` values. These are the only default ports neXus holds. |
| `PanoramaPolicyMapper` class comment | Predefined App-IDs are "content-release data" and are **not** returned by the approved reads; they are not given a definition. Rules naming them stay UNRESOLVED/`PREDEFINED` (`PolicyRuleMetrics.PREDEFINED`). |
| `HopPolicyEvaluator.defaultPorts` | Path analysis already consumes stored `default/port: tcp\|udp/...` values and returns `null` (reason `APP_DEFAULT_PORTS_NOT_STORED`) when any application lacks them. |
| `PolicyNormalization.disjoint` / `holds` | Treat `application-default` as an opaque token: never disjoint from explicit ports, disjoint from another application-default only via the application dimension. This yields `DEFAULT_PORTS_UNKNOWN` in `PolicyHygiene` (the ~2,447 rules). |

Conclusion: the ports of **predefined** App-IDs — the vast majority of what the rules
name — are **not available** from collected data. Custom applications are (unverified
how many of the 2,447 rules reference only those; measure with a count-only query, no
values, per the AIView law).

**New read-only command needed (requires PO approval, flagged):** a PAN-OS XML API
operational read of the predefined application definitions, expected shape
`type=op` with `<show><predefined><xpath>/predefined/application</xpath></predefined></show>`
(exact syntax, size, and output fields UNKNOWN until the PAN route gate entry and
official docs are checked; do not infer from memory). Gate-entry fields to fill per
`PAN_*_API_ROUTE_GATE_ENTRIES.md`: vendor PAN; read class CLASS 0 read; target the
managing Panorama or one firewall (content version identical across a managed fleet is
**not** assumed); timeout; one call per content version; no retry storm; response
large → parse in memory to `(app name, protocol, port ranges, complete flag)` and
discard raw (Raw-evidence law). Alternative with no new command: a PO-supplied
documented list — rejected, it would be content-version drift with no provenance.
Collected content version (`show system info` app-version) must be stored with the
table; it may already be collected — verify before adding anything.

## 3. Fail-closed use: prove disjointness only

Use the table in exactly one place, `PolicyNormalization.disjoint` on the service
dimension, and never in `holds` (never to prove containment/shadowing TRUE; the
absence of overlap is the only thing the default ports can safely establish).

Define `D(rule)` = union of default port sets (protocol-qualified) of the rule's
applications, or **absent** (→ unchanged, not disjoint) if any of these holds:
1. application is `any`, negated, an application-filter, external/dynamic, or an
   unresolved or group member without a table row;
2. any app row is missing, flagged incomplete, "dynamic", non-TCP/UDP, or marked as
   depending on another app (implicit-use) — dependencies widen the effective ports;
3. the table's content version differs from the snapshot's, or is unknown;
4. the snapshot is not `RESOLVED` for the rule's application cell.

Then, with `S` the other rule's explicit service ranges (or `D(other)` if it is also
application-default): selectors are disjoint iff for each protocol family the
ranges do not intersect (reuse the existing merged-range sweep). Everything else keeps
today's answer (not disjoint → `DEFAULT_PORTS_UNKNOWN`). Result vocabulary unchanged:
a proof moves a pair from UNKNOWN to NOT_SHADOWED only; a failure stays UNKNOWN —
never to SHADOWED, never to a guessed port set. Ports are compared as opaque ranges,
no identifier normalization. Display stays masked.

Residual risk, stated plainly: a rule can match traffic before App-ID identifies the
app (documented UNKNOWN in §6). Disjointness therefore holds only for rules that both
require App-ID; if the freeze review cannot establish that pre-identification traffic
cannot match the explicit-port rule *and* the app-default rule, the feature must stay
off. This is the load-bearing question for the freeze.

## 4. Tests (all fixture-based; fixtures prove the parser, not the vendor)

Extend `PolicyHygieneApplicationDefaultTest` and `PolicyNormalizationDisjointTest`-style
unit tests (synthetic `192.0.2.x`, masked names):
1. app-default (app with D={tcp/443}) vs explicit tcp/80 → disjoint → NOT_SHADOWED.
2. app-default (D={tcp/443}) vs explicit tcp/443 → overlap → stays UNKNOWN.
3. app-default vs explicit **udp/443** with D={tcp/443} → disjoint (families differ).
4. each fail-closed condition in §3 (any, filter, missing row, incomplete, dynamic,
   dependency, version mismatch/unknown, unresolved) → unchanged UNKNOWN.
5. app-default vs app-default with disjoint D but overlapping app identities → disjoint;
   overlapping D → UNKNOWN.
6. Never SHADOWED from table data: property test that no input reaches TRUE in `holds`
   through the table (differential vs `PartialPriorOracle`).
7. Snapshots without a table answer exactly as today (golden regression of the
   existing `PolicyHygieneDifferentialTest`).
8. Parser test for the new op response with synthetic XML: field enumeration only,
   raw response not persisted, content version recorded, oversized/malformed → UNKNOWN.
9. Architecture fence: the table is read-only data, no Browser → device path; gate
   entry present for the new command (`Ui2ArchitectureTest` pattern).
10. Real-environment validation is a separate gate: count (not list) how many of the
    2,447 rules move out of UNKNOWN on the AIView persona before any `DONE`.

## 5. Decisions needed from the Product Owner
1. Approve (or refuse) the predefined-application read command in §2.
2. Rule on the pre-identification question in §3 before any freeze.
3. Then: freeze a contract, gate entry, implement, validate.
