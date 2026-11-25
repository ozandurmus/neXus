# Policy collection gaps: engine attribution and Check Point gateway completeness (counts only)

Backlog `policy_collection_gaps`. After the three gap fixes (D-1 gateway bound, App-ID `defaultPortsHold`, `ZoneUniverse`)
the number of rules the hygiene engine now decides, versus still leaves UNKNOWN per group, exists only inside
`PolicyHygiene` at request time (`docs/design/POLICY_COLLECTION_GAPS_LIVE_MEASUREMENT_2026_11_17.md` section 5), and why 54 of
138 Check Point gateway objects lack the completeness marker is not determined in SQL (section 3.3). This harness runs the
real engine over the stored data inside the pod and prints counts. PO answer 2026-11-23 (section 1, answer 5): counts-only
in-pod measurements, no device command (`docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md`).

It replaces the earlier harness of branch `feat/policy-collection-gaps-1113` (`GapMeasure`, `gap_run.sh`), which depended on
other unmerged branches and on the pre-fix engine. This one depends on nothing outside `origin/main`: it is compiled against
the pod's own service jar and reaches the package-private engine classes (`PolicyHygiene.analyze`, `PolicyLegacyView.of`,
`ZoneUniverse`) by reflection.

**This lane wrote and self-tested the harness; it has not been run against any environment.** The in-pod run belongs to the
PO session, needs the PO session's go, and runs outside ship/e2e windows because `/app/tmp` is lost on a rollout. The result
becomes a MEASUREMENT RECORD written by the PO session; no counts are recorded here.

## What it reads

Inside `ui2-service`, one read-only database connection per stage (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`,
`SET statement_timeout = '120s'`), the pod's own env files, no device contact, no new command, no write:

- `policy_snapshot`: every stored snapshot, one at a time, in policy-id order (the id is never printed). A snapshot with
  collection failures (a checkpoint) is not analysed; it only counts.
- `pan_vsys_zone_set`: the stored zone sets, joined to each Palo Alto snapshot's targets by `ZoneUniverse.of(snapshot, stored,
  now)`, exactly as `PolicyQueryService.zoneUniverse` does (24 h freshness included).
- `cp_policy_object_inventory` rows of `object_type = 'gateways-and-servers'`, streamed item by item; chunked rows are
  read from `cp_policy_json_chunk` (gzip, kind `INVENTORY`) in order. Only the item's native type, its stored gateway
  projection (kept addresses, completeness flag) and the text of its main address leave the parse, in memory.

Each analysed snapshot is run twice, with its zone universe:

- `sound`: `PolicyHygiene.analyze(snapshot, 90, now, TOTAL_WORK, objects, zones)`, what the policy screen shows.
- `legacy`: the `PolicyLegacyView` of the snapshot through `PolicyHygiene.analyze(..., nonContainment=true, upperBound=true,
  zones)`, the upper-bound analysis in which `defaultPortsHold` acts. (`analyzeLegacy` itself passes no zone universe; the
  nine-argument form it delegates to takes one.) D-1 gateway bounds act in both modes.

Not applied: the Check Point domain-inventory merge of `PolicyQueryService.breadthObjects`. It only feeds the permissiveness
verdict, never shadowing, so it cannot change a verdict counted here.

## What it prints

`measure.out` in the staged directory: tab-separated, four fields per line `kind id key value`, every field `[a-z0-9_.]+`.
Ordinals (`p01`..`p10`, largest first by enabled plus disabled security rules, ties in policy-id order), vendor tokens
(`cp`, `pan`, `other`), mode tokens, fixed counter and reason slugs, integers. No policy id, hash, device id, uid, name,
address, zone name or exception text can reach it; a failure prints only `fatal<TAB><lowercase exception class>`. A pod older
than the three fixes (no `ZoneUniverse`) ends in `fatal exceptionininitializererror`. `show_safe.sh` drops (and counts as
`filtered_lines`) any line with an unsafe field. The run ends with a `done` line.

| line | meaning |
|---|---|
| `scope fleet <counter> n` | `snapshots_total`, `snapshots_parse_failed`, `snapshots_with_failures_skipped`, `snapshots_analysed` (and `_cp`, `_pan`, `_other`), `snapshots_analysis_failed`, `pan_zone_universe_known_snapshots` / `pan_zone_universe_unknown_snapshots` (analysed Palo Alto snapshots whose universe is known / not), `zone_set_rows`, `zone_set_rows_invalid`, `zone_sets_unreadable`, `inventory_gateway_rows`, `inventory_unreadable`, `cp_snapshots_gateway_census`, `cp_snapshots_with_gateway_objects` |
| `package pNN vendor <token>`, `package pNN security_rules n` | the ten largest analysed snapshots, in rank order |
| `engine <label>.<mode> <counter> n` | the verdict and attribution counters below; `label` is `p01`..`p10`, `fleet.cp`, `fleet.pan`, `fleet.other` or `fleet.all` (every analysed snapshot); `mode` is `sound` or `legacy` |
| `reason <label>.<mode> <slug> n` | UNKNOWN rules by the engine's closed reason (`PolicyHygiene.REASONS`, lower-cased, non-alphanumerics to `_`; `none` when a rule has no assessment, `other` for anything outside the set) |
| `gateway <population>.<slug> <reason> n` | gateway-class objects by native type slug and completeness reason, below |
| `gateway <population>.all <reason\|total> n` | the same summed over types; every closed reason is printed, zero included |

### Engine counters (per mode)

Counted over the rules of security sections only (enabled and disabled; Check Point NAT and Palo Alto NAT/default sections
are outside security evaluation and excluded).

| counter | counts |
|---|---|
| `rules_evaluated` | rules in security sections |
| `not_shadowed`, `redundant`, `conflict` | rules with that decided verdict |
| `unknown_total` | rules whose verdict is UNKNOWN (`rules_evaluated` = the four verdict counters) |
| `gateway_own_usable` / `gateway_own_unmarked` | UNKNOWN rules naming a gateway-class object (type `gateway`, directly or through groups, source or destination) where every such object is usable / at least one is not. Usable = RESOLVED, no members, `gateway-addresses: complete`, at least one IPv4 value, i.e. what `Resolver.gatewayBound` needs |
| `gateway_blocker_usable` / `gateway_blocker_unmarked` | the same test on the rule `blockingRuleId` names (set only for "earlier rule has unsupported constraints") |
| `gateway_any` | UNKNOWN rules counted in at least one of the four gateway counters (not double counted) |
| `app_default_ports` | UNKNOWN with the reason "application default ports are unknown" |
| `zone_list_universe_known` / `zone_list_universe_unknown` | Palo Alto UNKNOWN with the reason "zone or install-on coverage is unproven", split by whether the snapshot's zone universe is known |
| `unknown_attributed` | UNKNOWN rules carrying at least one tag above |
| `other_unknown` | UNKNOWN rules carrying none (`unknown_total` = `unknown_attributed` + `other_unknown`) |

The tags can overlap (a rule may be its own gateway case and have a gateway blocker), so the tag counters do not sum to
`unknown_total`; `unknown_attributed` and `other_unknown` do. The legacy-versus-sound difference of `redundant`,
`conflict`, `not_shadowed` and `unknown_total` per label is the gain of the legacy analysis (`defaultPortsHold`, the same
zone universe); the sound mode alone shows what the screen decides.

### Gateway completeness reasons

`population` is `snapshot` (the `gateway`-type objects of every stored Check Point snapshot, failures included, as the
2026-11-17 SQL counted them; type slug and reason joined from the inventory by the object reference) or `inventory`
(the gateway-class items of the stored `gateways-and-servers` inventories). Type slugs: `simple_gateway`, `simple_cluster`,
`cpmi_gateway_cluster`, `vsx_gateway` (`CpmiVsxNetobj`), `vsx_cluster` (`CpmiVsxClusterNetobj`), `vs_cluster_netobj`
(`CpmiVsClusterNetobj`), `other` (every other gateway-class type, and a snapshot object with no inventory item).

The first eight reasons mirror `CpObjectInventoryParser.gatewayAddresses` condition by condition:

| reason | meaning |
|---|---|
| `complete` | usable: the marker, no members, an IPv4 value |
| `not_in_inventory_or_stale` | no inventory item, no stored gateway projection, or the inventory item is complete but the snapshot's object does not carry the marker (the snapshot is older than the inventory) |
| `missing_or_invalid_main_ipv4` | `ipv4-address` present and not a valid IPv4 text (an absent main address alone is not a failure in the parser) |
| `missing_interfaces` | no `interfaces` list, or an empty one |
| `interface_missing_ip` | an interface that is not an object, carries an invalid IPv4, or carries neither an IPv4 nor an IPv6 address |
| `cluster_missing_members` | a classic cluster type (`simple-cluster`, `CpmiGatewayCluster`) without `cluster-members` |
| `member_missing_ip_or_interfaces` | a cluster member that is not an object, has an invalid address, has neither an address nor interfaces, or has bad interfaces |
| `empty_kept_addresses` | otherwise complete, but no IPv4 address was kept (for example IPv6 interfaces only) |

When several conditions hold the first in this order is the reason: main address, interfaces, interface address, cluster
members, member, nothing kept.

**What the stored data can and cannot say.** The collector sees the raw object and the parser keeps only the kept
addresses and the completeness flag (the raw reply is never persisted, per the raw-evidence law). In a live run the
reasons it can recover from the stored projection are `complete`, `not_in_inventory_or_stale`, `missing_or_invalid_main_ipv4`
(the stored main-address text is not a valid IPv4) and `empty_kept_addresses` (nothing kept). An incomplete object that kept
some address cannot be placed in `missing_interfaces`, `interface_missing_ip`, `cluster_missing_members` or
`member_missing_ip_or_interfaces`: it is counted under two reasons that exist only for stored data,
`incomplete_not_attributable` (kept an address, completeness false) and `marker_present_unusable` (the snapshot object
carries the marker but is not usable: not RESOLVED, has members, or has no IPv4 value). The four raw-only reasons therefore
read 0 in a live run; their size is the `incomplete_not_attributable` count, and splitting it needs a field-presence read of
the gateways-and-servers reply, which is a device read under the PO's ad hoc parser-diagnostics rule and is not part of this
harness. The raw classifier is in the harness (`rawReason`) and is proven in the self-test against synthetic nodes for every
reason and against the real parser's complete/incomplete outcome.

## Self-test (no pod, no database, no device)

Build the module classes once, then run the self-test:

    cd ui2 && ./gradlew :service:classes :persistence:classes :platform-core:classes :worker:classes
    bash scripts/measurement/policy_collection_gaps/selftest.sh

`selftest.sh` first feeds `show_safe.sh` one unsafe and one safe line (prints `show_safe<TAB>ok` when the unsafe one is dropped
and counted), then compiles the class against the local module outputs and the Gradle-cached Jackson 2 and PostgreSQL jars and
runs `java -cp "<tmp>:<classpath>" nexusmeasure.GapEngineMeasure --selftest`, which must print `selftest<TAB>ok` after
asserting exact counters (both modes) for inline synthetic snapshots:

- Check Point: a rule naming a usable gateway object, the rule inside its addresses that it blocks, a rule naming an unmarked
  gateway, the rule that one blocks, one decided rule, and a NAT-rulebase rule that must not be counted;
- Palo Alto application default: known ports (decided in `legacy`, UNKNOWN in `sound`) against `UNPARSED` ports (UNKNOWN in both);
- Palo Alto zones: a lone `any` rule decided against a known universe, left unproven with an unknown universe, left unproven
  by an `any` mixed with names under a known universe, and against a stale (30 h) zone set;
- gateway inventory: raw nodes for each of the eight mirrored reasons (with the parser comparison), stored items written through
  the real `CpObjectInventory` JSON, and the snapshot join (every reason, a stale object, a marker without an address);
- the whole report: every emitted line matches `^[a-z0-9_.]+(\t[a-z0-9_.]+)*$`, ranks and totals add up, and the unsafe-field guard
  drops a line carrying a name.

## Run (PO session only)

    MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/policy_collection_gaps/measure_run.sh <tag>

`measure_run.sh` reads the host from the operator's own config file, refuses to start while a deploy e2e runs, copies the
pod's own service jar (size-checked), compiles only the harness against it, stages `gapeng-<tag>`, runs `run_in_pod.sh`
(`java -Xmx2g` through `PropertiesLauncher`, 30 minute timeout), waits for `done`/`fatal` (up to 30 minutes), prints the
`show_safe.sh`-filtered output into `gapeng-<tag>.tsv`, and removes the stage directory. It needs the PO session's go; no
agent lane runs it.
