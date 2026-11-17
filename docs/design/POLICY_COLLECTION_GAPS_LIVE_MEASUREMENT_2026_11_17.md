# Policy collection gaps: live measurement of the three fixes, 2026-11-17

## Status

MEASUREMENT RECORD (evidence, not a contract) - 2026-11-17. Counts only, read-only SQL on the live `ui2` database
(snapshots collected 2026-11-16 and 2026-11-17, after batch 14). It measures the INPUT side of the three fixes of
backlog item `policy_collection_gaps` (CP gateway objects, PO decision D-1; PAN application default ports, V221 gate;
PAN vsys zone sets, V229, `PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` DECIDED). It does NOT state how many rules the
analysis now decides: that verdict is computed by the Java engine at request time and is stored nowhere (section 5).
It authorises nothing.

## 1. Method

- The earlier attribution (branch `feat/policy-collection-gaps-1113`, commit b8cfcf88, `README.md` there) ran the engine
  in a pod over the 10 largest stored policies and found 22,020 rules that both analysers left UNKNOWN: CP gateway or
  cluster objects 13,945, PAN application default ports 4,928, PAN vsys zone lists 481, none of the three 2,679.
- A rule's verdict is produced by `PolicyHygiene.analyze` (shadowing, upper-bound legacy mode) on a snapshot read from
  `policy_snapshot`; no table stores a verdict. This lane was limited to SELECT through `psql` on `ui2-db-0`, with no pod
  execution, so the engine was not run. What SQL can state exactly is the data each fix needs, and from the code
  (section 2) which rules a fix can possibly change.
- All numbers come from the SQL in section 6, run 2026-11-17 between 08:30 and 08:45 UTC (`now()` 08:41 UTC in Z1),
  each batch under `SET statement_timeout = '60s'`, output counts only. Policy ids are never printed; the one id form in
  this record is the first 8 hex characters of `md5(policy_id)`.
- Two scopes: "fleet" (all 99 stored snapshots: 54 PAN, 45 CP) and "top 10" (the 10 largest by rule count, the earlier
  measurement's scope: 7 CP and 3 PAN, ids `f3765a8e 2533aa7a ec130f83 f5e42f22 8cc9f0d5 3506d339 181658a1 81f92fdd
  19ef7184 17f19268`). The earlier run skipped checkpoints with failures; this one does not (the 14 largest snapshots have no
  collection failures; the rest were not checked).
- The JSON of a snapshot is read at known paths only (`metadata.targets`, `sections[].rules[]`, `objects` through one
  `jsonb_each`); no recursive walk.

## 2. How a rule ends up undecided, and what each fix changed

1. CP gateway objects. A rule whose source or destination names a gateway-class object (stored type `gateway`) is
   rejected by `Resolver#object` ("dynamic or unsupported object type"), so it is a partial prior with a null address
   cell and every later rule it might shadow reads "earlier rule has unsupported constraints". With D-1,
   `PolicyNormalization.partial` resolves that cell through `Resolver#staticBound` / `gatewayBound` to the object's
   management addresses (the mapper's `gateway-ipv4` values), but only when the object is RESOLVED, has no members and
   carries the mapper's `gateway-addresses: complete` marker (`CheckPointPolicyMapper`, field
   `nexus-gateway-addresses-complete`). The bound may only prove disjointness (a partial prior never covers), so a later
   rule is released only when the proof succeeds.
2. PAN application default ports. A rule with service `application-default` compares by opaque token and `holds`
   returns null. With V221 the collector stores the predefined App-ID catalogue as `application-defaults` objects
   (`default/port: <member>` values), `PolicyNormalization.defaultPortsHold` decides an application-default pair against
   explicit ports when every application involved has known standard ports (`Resolver#defaultPorts`: a value of
   protocol tcp or udp, no `default/ident-by-*`, no `UNPARSED` member). It runs only in the legacy (upper-bound) analysis;
   the sound analysis still returns null.
3. PAN vsys zone lists. `PolicyNormalization.zoneHolds(outer names, inner lone any)` was null. With V229
   `ZoneUniverse.of(snapshot, stored, now)` is known only when every target (firewall + vsys) of the snapshot has a
   stored zone set read at most 24 hours before the analysis and non-empty; otherwise `UNKNOWN` and the answer is exactly
   what it was before. Zone sets come only from the stored effective-running document of a PAN-OS 12 firewall (design
   section 6, "Limits").

## 3. Results

"Before" is the value recorded in the earlier documents or follows from the code without the fix; "now" is measured.

### 3.1 PAN vsys zone lists (481: P07 455, P03 26)

| Measure | Before | Now (fleet) | Now (top 10) |
|---|---:|---:|---:|
| PAN policy snapshots whose zone universe is known | 0 of 54 | **8 of 54** | **0 of 3** |
| Targets (firewall + vsys) with a fresh, non-empty zone set | 0 of 128 | 28 of 128 | 1 of 12 |
| Exposure, loose: later enabled rules that are a lone `any` on from or to, with an earlier enabled universal rule listing names on that side | - | 26,841 | 11,365 |
| of which in a snapshot with a known universe (the fix can act) | 0 | **4,057** | **0** |
| Exposure, lower bound: the earlier names-list rule has identical source, destination, service and application references | - | 11 | 4 |
| of which in a snapshot with a known universe | 0 | **2** | **0** |

Input behind it (Z1, Z2, Z4): 38 enrolled PAN firewalls are targeted by the PAN policies; 9 are PAN-OS 12 and have a
stored zone set, 29 are on another major version and have none. 32 zone-set rows exist for 9 devices, all read about 12
hours before the measurement (so every one of them goes stale at about 21:01 UTC today unless the inventory run stores
a newer read); 4 rows hold no zone, and one PAN policy has such a target (an empty set is not a universe). 36 of 54 PAN
snapshots have no target with a stored row at all (two have no target).

Reading. In the 10 largest policies, which contain the 481 (P07, P03 of the earlier run), no PAN policy has a known
universe, so by the code in section 2 no `zoneHolds` answer changed there: those rules stay undecided. Fleet-wide the fix
can reach at most 4,057 of the 26,841 loosely exposed rules (15%), in 8 policies; the exposure is an upper bound and
the true number of rules that were undecided for this cause is far smaller (481 on the top 10 earlier), so the
decided gain is somewhere between 0 and the part of that in the 8 policies; it was not measured.

### 3.2 PAN application default ports (4,928 both-UNKNOWN rules, P03 4,917)

| Measure | Before | Now (fleet) | Now (top 10) |
|---|---:|---:|---:|
| Enabled security rules with service `application-default` | 1,451 | 1,451 | 278 |
| of them with every application's standard ports known | 0 (no catalogue) | **51** (3.5%) | **2** |
| of them with application any | - | 40 | 5 |
| of them with an application group (not resolved in SQL) | - | 1 | 1 |
| of them with some application's ports unknown | 1,451 | 1,359 | 270 |
| Catalogue entries stored in the snapshots (`application-defaults` objects, per snapshot) | 0 | 334 | 76 |
| of them usable (tcp or udp port values only) | 0 | 144 (43%) | 59 |
| Application objects referenced: predefined with usable ports / entry but not usable / no entry of that name | n/a | 144 / 190 / 50 | 59 / 17 / 28 |
| Custom applications with usable default ports (stored `default/port`) | 0 | 326 of 327 | 18 of 18 |
| PAN snapshots that carry catalogue entries | 0 | 49 of 54 | 3 of 3 |

Why a catalogue entry is not usable (A2, 334 entries): 66 are identified by IP protocol, 58 by ICMP type (the engine
reads those as unknown ports), 58 carry a `default/port` member outside the collector's closed shape (stored as
`UNPARSED`, 109 values), 8 have no value at all. The 50 referenced applications with no catalogue entry of that name are
not explained by the data in the snapshot (custom filters or names the catalogue does not list: not determined).

Reading. The catalogue arrived (49 of 54 snapshots) but a rule needs every one of its applications known, and 96.5% of
the application-default rules still have at least one application without usable standard ports. At most 51 rules
(plus the explicit-port rules that follow them, not counted) can contribute a decided pair; the earlier 4,928 undecided
rules are therefore reduced at best by the pairs those 51 rules take part in. Not measured: how many.

### 3.3 Check Point gateway objects (13,945: own 26, behind a gateway blocker 13,919)

| Measure | Before | Now (fleet) | Now (top 10) |
|---|---:|---:|---:|
| Gateway-class objects (per snapshot) | 23 (counted per package, 7 packages) | 138 in 44 of 45 CP snapshots | 28 in 7 of 7 |
| of them with a usable bound (RESOLVED, no members, `gateway-addresses: complete`, an IPv4 value) | 0 (D-1 not built) | **84** (61%) | **19** (68%) |
| Gateway objects without the completeness marker (stay unresolved, fail-closed) | 23 | 54 | 9 |
| Enabled security rules naming a gateway object directly in source or destination | 26 (own) | 82 in 18 snapshots | 24 in 6 |
| of them with every named gateway object usable | 0 | **28** | **10** |
| of them with some gateway object not usable | 26 | 54 | 14 |
| Enabled rules after the first such rule in the same snapshot (pool a gateway rule can block, upper bound) | 13,919 blocked (measured) | 44,011 | 33,505 |

Reading. D-1 can release later rules only behind the 28 rules (10 in the top 10) whose gateway objects are all
usable; behind the other 54 rules (14 in the top 10) nothing changed because a gateway object lacks the marker. The
"pool" row is a position count, not a count of blocked rules: it says how many rules sit after a gateway rule, and
is useful only as an upper bound. Groups containing gateway objects are not expanded here (so the rule counts
undercount). Why 54 objects lack the marker is not determined in SQL; every snapshot is from 2026-11-16/17 (C1), so it
is not staleness. The earlier record notes that simple gateways, clusters and members did not have their interface list
recorded.

## 4. Still undecided, and why

- Zones: 29 of the 38 targeted firewalls are not PAN-OS 12 and have no zone set; the design deliberately did not widen
  the stored-document read. The two largest PAN policies (6,754 and 3,374 rules) and the third (3,364) each contain such
  a target; one more policy has an empty zone set. The 24-hour freshness bound makes every known universe expire at
  about 21:01 UTC today unless a newer inventory read is stored.
- Application default ports: 1,359 of 1,451 application-default rules have an application with no usable standard
  ports (identification by IP protocol or ICMP type, `UNPARSED` members, no catalogue entry); rules with application any
  (40) are by design unknown.
- Gateway objects: 54 of 138 gateway objects carry no completeness marker; the remainder is bounded only to prove
  disjointness, never coverage.
- The 2,679 rules the earlier run attributed to none of the three causes are untouched by these fixes.

## 5. What could not be measured

- The number of rules now decided versus still undecided per group. The verdict exists only inside `PolicyHygiene` at
  request time. Reproducing it in SQL means re-implementing shadowing (selector containment over groups, ranges,
  zones), which this lane did not attempt, and running the engine means executing a harness in the pod, which this
  lane's rules excluded. The earlier measurement's harness (`scripts/measurement/policy_collection_gaps/` on branch
  `feat/policy-collection-gaps-1113`, run through `gap_run.sh`) is the way to get the engine counts; it needs a PO ok
  for pod execution and no e2e running. Then the 481, 4,928 and 13,945 can be restated directly.
- The Z3b self-join over all earlier rules (same-cells with flat reference superset) exceeded the 60 second limit and
  was stopped; section 6 holds the cheaper identical-cells variant that ran.
- Rules reaching a gateway or an application through a group are not expanded (gateway rules, application groups).
- Scope differences from the earlier run: the ranking is by current rule count (the 10 chosen match 7 CP + 3 PAN as
  before), all snapshots have been recollected since, and failures are not skipped.

## 6. SQL, verbatim

Run through `ssh ... kubectl -n ui2 exec -i ui2-db-0 -- psql -U postgres -d ui2 -At` with
`SET statement_timeout = '60s';` first. The token `PARTITION` is replaced with the scope predicate: `true` for the
fleet, and for the top 10:

```
policy_id in (select policy_id from (select policy_id, (select sum(jsonb_array_length(s->'rules')) from jsonb_array_elements(snapshot->'sections') s) n from policy_snapshot order by 2 desc limit 10) t)
```

### Z1 PAN firewalls and stored zone sets

```sql
-- Z1: PAN firewalls and the stored zone sets (input side of the zone fix)
select 'pan_devices_enrolled' k, count(*)::text v from devices where vendor_hint='palo_alto' and enrollment_state='ENROLLED' and not disabled
union all select 'pan_gateways_enrolled', count(*)::text from devices where vendor_hint='palo_alto' and role='gateway' and enrollment_state='ENROLLED' and not disabled
union all select 'pan_gateways_major_12', count(*)::text from devices where vendor_hint='palo_alto' and role='gateway' and enrollment_state='ENROLLED' and not disabled and split_part(observed_software_version,'.',1)='12'
union all select 'pan_gateways_major_other', count(*)::text from devices where vendor_hint='palo_alto' and role='gateway' and enrollment_state='ENROLLED' and not disabled and split_part(observed_software_version,'.',1)<>'12'
union all select 'zone_set_rows', count(*)::text from pan_vsys_zone_set
union all select 'zone_set_devices', count(distinct device_id)::text from pan_vsys_zone_set
union all select 'zone_set_rows_fresh_24h', count(*)::text from pan_vsys_zone_set where read_at > now() - interval '24 hours'
union all select 'zone_set_rows_empty', count(*)::text from pan_vsys_zone_set where cardinality(zone_names)=0
union all select 'zone_set_oldest_read_age_h', round(extract(epoch from now()-min(read_at))/3600)::text from pan_vsys_zone_set
union all select 'zone_set_newest_read_age_h', round(extract(epoch from now()-max(read_at))/3600)::text from pan_vsys_zone_set
union all select 'now_utc', to_char(now() at time zone 'utc','YYYY-MM-DD HH24:MI');
```

### Z2 zone universe known per PAN snapshot

```sql
-- Z2: per PAN policy snapshot, is the zone universe known (every target has a fresh, non-empty stored set)?
with pol as (select policy_id, jsonb_array_length(metadata->'targets') n from policy_snapshot where metadata->>'vendor'='PAN'),
tt as (
  select p.policy_id, x.t->>'deviceId' dev, nullif(x.t->>'context','') ctx
  from policy_snapshot p, lateral jsonb_array_elements(p.metadata->'targets') x(t) where p.metadata->>'vendor'='PAN'),
j as (
  select tt.policy_id, tt.ctx, z.read_at, cardinality(z.zone_names) zc
  from tt left join pan_vsys_zone_set z on z.device_id = tt.dev and z.vsys = tt.ctx),
agg as (
  select pol.policy_id, pol.n,
    count(j.policy_id) filter (where j.ctx is not null and j.read_at > now() - interval '24 hours' and j.zc > 0) ok,
    count(j.read_at) with_row, count(j.policy_id) filter (where j.zc = 0) empty_rows
  from pol left join j on j.policy_id = pol.policy_id group by pol.policy_id, pol.n)
select 'pan_policies' k, count(*)::text v from agg
union all select 'pan_policies_no_targets', count(*)::text from agg where n = 0
union all select 'pan_policies_universe_known', count(*)::text from agg where n > 0 and ok = n
union all select 'pan_policies_some_target_row_but_unknown', count(*)::text from agg where not (n > 0 and ok = n) and with_row > 0
union all select 'pan_policies_no_target_row', count(*)::text from agg where with_row = 0
union all select 'pan_policies_with_empty_zone_set_target', count(*)::text from agg where empty_rows > 0
union all select 'pan_targets_total', coalesce(sum(n),0)::text from agg
union all select 'pan_targets_with_fresh_nonempty_set', coalesce(sum(ok),0)::text from agg;
```

### Z3 rules the zone fix can reach, loose (PARTITION)

```sql
-- Z3: rules whose zone question the fix can reach, per PAN policy snapshot. Exposure only, not the engine verdict.
-- scope placeholder :PARTITION is replaced by a left(md5(policy_id),1) predicate (partition run) or by true.
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='PAN' and PARTITION),
known as (
  select p.policy_id from policy_snapshot p where p.metadata->>'vendor'='PAN' and PARTITION
    and jsonb_array_length(p.metadata->'targets') > 0
    and not exists (
      select 1 from jsonb_array_elements(p.metadata->'targets') x(t)
      left join pan_vsys_zone_set z on z.device_id = x.t->>'deviceId' and z.vsys = nullif(x.t->>'context','')
      where z.read_at is null or z.read_at <= now() - interval '24 hours' or cardinality(z.zone_names) = 0)),
r as (
  select p.policy_id, s.ord * 1000000 + r.ord ord, r.rl,
    case when r.rl->'extras'->'from' = '["any"]'::jsonb then 'any' when jsonb_array_length(coalesce(r.rl->'extras'->'from','[]'::jsonb)) = 0 then 'none'
         when r.rl->'extras'->'from' ? 'any' then 'mixed' else 'list' end fk,
    case when r.rl->'extras'->'to' = '["any"]'::jsonb then 'any' when jsonb_array_length(coalesce(r.rl->'extras'->'to','[]'::jsonb)) = 0 then 'none'
         when r.rl->'extras'->'to' ? 'any' then 'mixed' else 'list' end tk,
    coalesce(r.rl->'extras'->'rule-type','["universal"]'::jsonb) = '["universal"]'::jsonb universal
  from pol p, jsonb_array_elements(p.snapshot->'sections') with ordinality s(s, ord), jsonb_array_elements(s->'rules') with ordinality r(rl, ord)
  where s->>'name' not in ('Pre NAT rules','Post NAT rules','Pre default rules','Post default rules','Local NAT rules','Local default rules')
    and coalesce(r.rl->>'enabled','true') <> 'false'),
w as (
  select r.*,
    count(*) filter (where universal and fk = 'list') over (partition by policy_id order by ord rows between unbounded preceding and 1 preceding) earlier_from_lists,
    count(*) filter (where universal and tk = 'list') over (partition by policy_id order by ord rows between unbounded preceding and 1 preceding) earlier_to_lists
  from r)
select 'security_rules_enabled' k, count(*)::text v from w
union all select 'security_rules_enabled_in_universe_known_policies', count(*)::text from w where policy_id in (select policy_id from known)
union all select 'rules_missing_from_or_to', count(*)::text from w where fk = 'none' or tk = 'none'
union all select 'rules_lone_any_from_or_to', count(*)::text from w where fk = 'any' or tk = 'any'
union all select 'exposed_loose (lone any on a side, an earlier universal rule lists names on that side)', count(*)::text from w where (fk = 'any' and earlier_from_lists > 0) or (tk = 'any' and earlier_to_lists > 0)
union all select 'exposed_loose_in_universe_known_policies', count(*)::text from w where ((fk = 'any' and earlier_from_lists > 0) or (tk = 'any' and earlier_to_lists > 0)) and policy_id in (select policy_id from known)
union all select 'exposed_loose_in_universe_unknown_policies', count(*)::text from w where ((fk = 'any' and earlier_from_lists > 0) or (tk = 'any' and earlier_to_lists > 0)) and policy_id not in (select policy_id from known);
```

### Z3b rules the zone fix can reach, identical cells (PARTITION)

```sql
-- Z3b: lower-bound exposure: the earlier universal rule that lists zone names on a side has the very same source,
-- destination, service and application references (and none negated) as the later rule that is a lone any on that side.
-- Exposure only, not the engine verdict; group expansion and subset cells are not looked at (so this undercounts).
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='PAN' and PARTITION),
known as (
  select p.policy_id from policy_snapshot p where p.metadata->>'vendor'='PAN' and PARTITION
    and jsonb_array_length(p.metadata->'targets') > 0
    and not exists (
      select 1 from jsonb_array_elements(p.metadata->'targets') x(t)
      left join pan_vsys_zone_set z on z.device_id = x.t->>'deviceId' and z.vsys = nullif(x.t->>'context','')
      where z.read_at is null or z.read_at <= now() - interval '24 hours' or cardinality(z.zone_names) = 0)),
r as (
  select p.policy_id, s.ord * 1000000 + r.ord ord,
    case when r.rl->'extras'->'from' = '["any"]'::jsonb then 'any' when r.rl->'extras'->'from' ? 'any' then 'mixed' else 'list' end fk,
    case when r.rl->'extras'->'to' = '["any"]'::jsonb then 'any' when r.rl->'extras'->'to' ? 'any' then 'mixed' else 'list' end tk,
    coalesce(r.rl->'extras'->'rule-type','["universal"]'::jsonb) = '["universal"]'::jsonb universal,
    case when (r.rl->'source'->>'negated')::bool or (r.rl->'destination'->>'negated')::bool or (r.rl->'service'->>'negated')::bool or (r.rl->'application'->>'negated')::bool then null
         else md5(concat_ws('|', r.rl->'source'->'refs', r.rl->'destination'->'refs', r.rl->'service'->'refs', r.rl->'application'->'refs')) end cells
  from pol p, jsonb_array_elements(p.snapshot->'sections') with ordinality s(s, ord), jsonb_array_elements(s->'rules') with ordinality r(rl, ord)
  where s->>'name' not in ('Pre NAT rules','Post NAT rules','Pre default rules','Post default rules','Local NAT rules','Local default rules')
    and coalesce(r.rl->>'enabled','true') <> 'false'),
w as (
  select r.*,
    count(*) filter (where universal and fk = 'list') over (partition by policy_id, cells order by ord rows between unbounded preceding and 1 preceding) ef,
    count(*) filter (where universal and tk = 'list') over (partition by policy_id, cells order by ord rows between unbounded preceding and 1 preceding) et
  from r where cells is not null),
h as (select policy_id, ((fk = 'any' and ef > 0) or (tk = 'any' and et > 0)) hit from w)
select 'exposed_same_cells' k, count(*) filter (where hit)::text v from h
union all select 'exposed_same_cells_in_universe_known_policies', count(*) filter (where hit and policy_id in (select policy_id from known))::text from h
union all select 'exposed_same_cells_in_universe_unknown_policies', count(*) filter (where hit and policy_id not in (select policy_id from known))::text from h;
```

### Z4 targeted firewalls by what gives or blocks a zone set

```sql
-- Z4: the distinct firewalls the PAN policy snapshots target, by what blocks or gives their zone set
with t as (
  select distinct x.t->>'deviceId' dev from policy_snapshot p, lateral jsonb_array_elements(p.metadata->'targets') x(t) where p.metadata->>'vendor'='PAN')
select case when t.dev is null then 'target_without_device_id'
            when d.device_id is null then 'target_not_an_enrolled_device'
            when exists (select 1 from pan_vsys_zone_set z where z.device_id = d.device_id) then 'has_zone_set'
            when split_part(d.observed_software_version,'.',1) = '12' then 'major_12_no_zone_set'
            else 'other_major_no_zone_set' end k, count(*)
from t left join devices d on d.device_id = t.dev group by 1 order by 1;
```

### A1 application-default rules and known standard ports (PARTITION)

```sql
-- A1: PAN application-default rules and whether the standard ports of their applications are known (input side of the V221 fix).
-- "known" mirrors Resolver.defaultPorts: only default/port values of protocol tcp or udp, no default/ident-by-*.
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='PAN' and PARTITION),
obj as materialized (select p.policy_id, o.key id, o.value->>'type' typ, o.value->>'status' st, o.value->>'name' nm, o.value->'values' vals, o.value->'members' mem
        from pol p, jsonb_each(p.snapshot->'objects') o
        where o.value->>'type' in ('any','application-group','unresolved','application','application-defaults')
           or (o.value->>'type' = 'service' and o.value->>'name' = 'application-default')),
okports as materialized (
  select o.policy_id, o.id, o.typ, o.nm,
    (count(*) filter (where v like 'default/port: %') > 0
     and count(*) filter (where v like 'default/ident-by-%') = 0
     and count(*) filter (where v like 'default/port: %' and v !~ '^default/port: (tcp|udp)/[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$') = 0) ok
  from obj o left join lateral jsonb_array_elements_text(o.vals) v on true
  where o.typ in ('application-defaults','application') and o.st = 'RESOLVED' and o.mem = '[]'::jsonb
  group by o.policy_id, o.id, o.typ, o.nm),
cls as materialized (
  select o.policy_id, o.id,
    case when o.typ = 'any' then 'any'
         when o.typ = 'application-group' then 'group'
         when o.typ = 'unresolved' and o.st = 'UNRESOLVED' and o.vals = '[]'::jsonb and o.mem = '[]'::jsonb then
           case when exists (select 1 from okports c where c.policy_id = o.policy_id and c.typ = 'application-defaults' and c.nm = o.nm and c.ok) then 'predefined_ports_known'
                when exists (select 1 from okports c where c.policy_id = o.policy_id and c.typ = 'application-defaults' and c.nm = o.nm) then 'predefined_catalogue_entry_ports_not_usable'
                else 'predefined_not_in_catalogue' end
         when o.typ = 'application' and o.st = 'RESOLVED' and o.mem = '[]'::jsonb then
           case when exists (select 1 from okports c where c.policy_id = o.policy_id and c.id = o.id and c.ok) then 'custom_ports_known' else 'custom_ports_unknown' end
         else 'other' end c
  from obj o),
ad as (select policy_id, id from obj where typ = 'service' and nm = 'application-default' and st = 'RESOLVED' and vals = '[]'::jsonb and mem = '[]'::jsonb),
r as (
  select p.policy_id, r.rl
  from pol p, jsonb_array_elements(p.snapshot->'sections') s, jsonb_array_elements(s->'rules') r(rl)
  where s->>'name' not in ('Pre NAT rules','Post NAT rules','Pre default rules','Post default rules','Local NAT rules','Local default rules')
    and coalesce(r.rl->>'enabled','true') <> 'false'),
adr as (
  select r.policy_id, r.rl from r join ad on ad.policy_id = r.policy_id
  where jsonb_array_length(r.rl->'service'->'refs') = 1 and r.rl->'service'->'refs'->>0 = ad.id and not (r.rl->'service'->>'negated')::bool),
rc as (
  select a.policy_id,
    case when jsonb_array_length(a.rl->'application'->'refs') = 0 or (a.rl->'application'->>'negated')::bool then 'empty_or_negated'
         else (select case when bool_or(c.c is null or c.c = 'other') then 'other'
                           when bool_or(c.c = 'any') then 'application_any'
                           when bool_or(c.c = 'group') then 'application_group_not_resolved_here'
                           when bool_and(c.c in ('predefined_ports_known','custom_ports_known')) then 'all_ports_known'
                           else 'some_ports_unknown' end
               from jsonb_array_elements_text(a.rl->'application'->'refs') ref(id) left join cls c on c.policy_id = a.policy_id and c.id = ref.id) end k
  from adr a),
nm as (
  select r.policy_id, r.rl,
    (select case when bool_or(c.c is null or c.c in ('other','any','group')) then 'not_plain_named' when bool_and(c.c in ('predefined_ports_known','custom_ports_known')) then 'ports_known' else 'ports_unknown' end
     from jsonb_array_elements_text(r.rl->'application'->'refs') ref(id) left join cls c on c.policy_id = r.policy_id and c.id = ref.id) k
  from r where jsonb_array_length(r.rl->'application'->'refs') > 0 and not (r.rl->'application'->>'negated')::bool)
select 'pan_security_rules_enabled' k, count(*)::text v from r
union all select 'rules_service_application_default', count(*)::text from adr
union all select 'ad_rules: ' || k, count(*)::text from rc group by k
union all select 'rules_with_named_applications: ' || k, count(*)::text from nm group by k
union all select 'catalogue_objects_application_defaults', count(*)::text from obj where typ = 'application-defaults'
union all select 'catalogue_objects_usable_ports', count(*)::text from okports where typ = 'application-defaults' and ok
union all select 'application_objects_by_class: ' || c, count(*)::text from cls where c like 'predefined%' or c like 'custom%' group by c
union all select 'policies_with_catalogue', count(distinct policy_id)::text from obj where typ = 'application-defaults'
union all select 'pan_policies', count(*)::text from pol;
```

### A2 shape of catalogue entries

```sql
-- A2: shape of the catalogue entries kept in the PAN snapshots (value classes only, per policy object, no names)
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='PAN'),
obj as materialized (select p.policy_id, o.key id, o.value->'values' vals from pol p, jsonb_each(p.snapshot->'objects') o where o.value->>'type' = 'application-defaults'),
sh as (
  select o.id, o.policy_id,
    bool_or(v ~ '^default/port: (tcp|udp)/[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$') port_tcp_udp,
    bool_or(v like 'default/ident-by-ip-protocol%') ident_ip,
    bool_or(v like 'default/ident-by-icmp%') ident_icmp,
    bool_or(v like 'default/port: %' and v !~ '^default/port: (tcp|udp)/[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$') port_other_shape,
    count(v) nvals
  from obj o left join lateral jsonb_array_elements_text(o.vals) v on true group by o.id, o.policy_id)
select 'entries' k, count(*)::text v from sh
union all select 'entries_without_any_value', count(*)::text from sh where nvals = 0
union all select 'entries_with_ident_by_ip_protocol', count(*)::text from sh where ident_ip
union all select 'entries_with_ident_by_icmp', count(*)::text from sh where ident_icmp
union all select 'entries_with_port_value_of_other_shape', count(*)::text from sh where port_other_shape
union all select 'entries_with_tcp_udp_port_value', count(*)::text from sh where port_tcp_udp
union all select 'entries_usable (tcp/udp port, no ident-by, no other shape)', count(*)::text from sh where port_tcp_udp and not ident_ip and not ident_icmp and not port_other_shape;
```

### G1 gateway-class objects (PARTITION)

```sql
-- G1: Check Point gateway-class objects in the stored snapshots (per policy snapshot, as the earlier measurement counted)
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='CP' and PARTITION),
g as (select p.policy_id, o.value v from pol p, jsonb_each(p.snapshot->'objects') o where o.value->>'type' = 'gateway')
select 'cp_policies' k, count(*)::text v from pol
union all select 'cp_policies_with_gateway_objects', count(distinct policy_id)::text from g
union all select 'gateway_objects_per_policy_sum', count(*)::text from g
union all select 'gateway_objects_resolved', count(*)::text from g where v->>'status' = 'RESOLVED'
union all select 'gateway_objects_complete_marker', count(*)::text from g where (v->'values') ? 'gateway-addresses: complete'
union all select 'gateway_objects_usable_bound (resolved, no members, complete marker, at least one ipv4 value)', count(*)::text
  from g where v->>'status' = 'RESOLVED' and v->'members' = '[]'::jsonb and (v->'values') ? 'gateway-addresses: complete'
    and exists (select 1 from jsonb_array_elements_text(v->'values') x where x like 'gateway-ipv4: %' or x like 'ipv4-address: %')
union all select 'gateway_objects_without_marker', count(*)::text from g where not ((v->'values') ? 'gateway-addresses: complete')
union all select 'gateway_objects_with_members', count(*)::text from g where v->'members' <> '[]'::jsonb;
```

### G2 rules naming gateway objects (PARTITION)

```sql
-- G2: Check Point security rules that name a gateway-class object directly in source or destination (groups not expanded),
-- and the rules that follow the first such rule in the same policy snapshot (the pool a gateway rule can block).
with pol as (select policy_id, snapshot from policy_snapshot where metadata->>'vendor'='CP' and PARTITION),
gw as materialized (
  select p.policy_id, o.key id, ((o.value->'values') ? 'gateway-addresses: complete' and o.value->>'status' = 'RESOLVED' and o.value->'members' = '[]'::jsonb
    and exists (select 1 from jsonb_array_elements_text(o.value->'values') x where x like 'gateway-ipv4: %' or x like 'ipv4-address: %')) usable
  from pol p, jsonb_each(p.snapshot->'objects') o where o.value->>'type' = 'gateway'),
r as materialized (
  select p.policy_id, s.ord * 1000000 + r.ord ord,
    (select count(*) from (select jsonb_array_elements_text(r.rl->'source'->'refs') id union all select jsonb_array_elements_text(r.rl->'destination'->'refs')) q join gw on gw.policy_id = p.policy_id and gw.id = q.id) ngw,
    (select count(*) from (select jsonb_array_elements_text(r.rl->'source'->'refs') id union all select jsonb_array_elements_text(r.rl->'destination'->'refs')) q join gw on gw.policy_id = p.policy_id and gw.id = q.id and not gw.usable) nbad,
    coalesce(r.rl->>'enabled','true') <> 'false' enabled
  from pol p, jsonb_array_elements(p.snapshot->'sections') with ordinality s(s, ord), jsonb_array_elements(s->'rules') with ordinality r(rl, ord)
  where s->>'source' is distinct from 'CP NAT rulebase'),
f as (select policy_id, min(ord) first_gw from r where enabled and ngw > 0 group by policy_id)
select 'cp_security_rules' k, count(*)::text v from r
union all select 'rules_naming_gateway_object_enabled', count(*)::text from r where enabled and ngw > 0
union all select 'rules_naming_gateway_object_all_usable', count(*)::text from r where enabled and ngw > 0 and nbad = 0
union all select 'rules_naming_gateway_object_some_not_usable', count(*)::text from r where enabled and ngw > 0 and nbad > 0
union all select 'policies_with_such_rules', count(*)::text from f
union all select 'enabled_rules_after_first_gateway_rule_in_policy', count(*)::text from r join f on f.policy_id = r.policy_id where r.enabled and r.ord > f.first_gw;
```

### C1 snapshot age

```sql
-- C1: age of the stored snapshots the numbers above were read from
select metadata->>'vendor' vendor, count(*) snapshots, min(collected_at)::date oldest, max(collected_at)::date newest,
  count(*) filter (where collected_at < now() - interval '48 hours') older_than_48h
from policy_snapshot group by 1 order by 1;
```

The ranking used to name the top 10 (output ids are `left(md5(policy_id),8)`):

```sql
select left(md5(policy_id),8), metadata->>'vendor', (select sum(jsonb_array_length(s->'rules')) from jsonb_array_elements(snapshot->'sections') s) n from policy_snapshot order by 3 desc limit 10;
```
