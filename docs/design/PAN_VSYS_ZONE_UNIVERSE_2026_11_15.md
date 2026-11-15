# Palo Alto vsys zone universe for the policy analysis: what the "481" need

## Status

DECIDED - 2026-11-15. The Product Owner approved Option A in chat on 2026-11-15 (about 12:15): "build it as
recommended". This note was drafted by the `policy_collection_gaps` lane (source reading, and the counts already
published in the 2026-11-13 measurement, `scripts/measurement/policy_collection_gaps/README.md` on branch
`feat/policy-collection-gaps-1113`, commit b8cfcf88); no stored customer data was read. Sections 1-5 below are that
draft, kept as written; section 6 is the decision and what was built. The decision is recorded in
`project/backlog.json` history (`docs/history/backlog/policy_collection_gaps.md`).

## 1. What the 481 are

Of 22,020 rules that both the production legacy analysis and the reference analyser leave UNKNOWN on the ten largest
stored policies, 481 are tagged `zone_list` (P07 455, P03 26; 13 of them also carry the application-ports gap). The tag
means the first reported reason was "zone or install-on coverage is unproven" (or "zone evidence not collected" on the
reference side) on a Palo Alto rule.

The cause is one function, `PolicyNormalization.zoneHolds(outer, inner)`: between an earlier rule that lists zone names
and a later rule that is a lone `any` (or the reverse), the answer is `null` (undecided) because "the zones of the
virtual system are not collected, so a list of names may well be every zone". Same-name lists and name-vs-name lists
already decide. So the 481 are rules whose coverage hinges on "does this named list equal every zone of the target
vsys", which cannot be answered without the zone set of that vsys.

## 2. Is the zone set already stored? No

- `PolicySnapshot` (Panorama policy reads, `PanPolicyGates`) carries rules, objects and device-group targets
  (`Metadata.targets`, `target-vsys`), but no zone definitions: zones live in templates and in each firewall's own
  `vsys/entry/zone`, neither of which the policy reads fetch.
- The configuration index (`PaloAltoConfigStreamProcessor`) stores only a count of `zone` entries per vsys context and
  source (`tpl`/`dg`/`shared`/`local`), no names.
- `PaloAltoConfiguredInterfaceParser` does parse `devices/entry/vsys/entry/zone/entry/network` from the inventory's
  effective-running read, but only to attribute interfaces to zones; the result is `device_interface.zone` (V148). That
  column cannot be a zone universe: NULL means both "no zone" and "not reported", a zone with no interface member never
  appears, and on the latest inventory run only 1,200 of 1,989 interface rows of the 38 firewalls carry a zone
  (2026-11-13 count). Using it would turn a missing zone into a false "provably not covered", which is the opposite of
  "never guess".

Therefore the 481 cannot be closed from what is stored today, and no mapper or normalisation change alone is sound.

## 3. What would close it (needs approval; no device command either way)

Option A, persist the zone set from the read neXus already issues. The inventory already receives the firewall's
`effective-running` document (it contains every vsys zone entry, including template-pushed ones with `src="tpl"`).
Extend the parse scope (as V148 did, "no new command") to keep, per (firewall device, vsys context), the set of zone
entry names and the time of the read. Needs: a migration (new table, dry-run in BEGIN/ROLLBACK on the live DB per the
V64 rule), a repository read in the service, and a policy-plane join.

Option B, read the zones from stored configuration backups at analysis time. No new table, but a backup read in the
policy request path (size, age, encryption, version skew against the snapshot), so it is a worse fit than A.

Either option also needs the engine rule below, plus a decision on the open points.

Engine rule (sound form). Let U(t) be the zone set of target t = (firewall, vsys), known only when its read is complete
and not older than a stated bound. For a Panorama rule the universe is the intersection question over all targets:
- "outer list holds lone any" is TRUE only if every target's U is a subset of the list;
- "outer list holds lone any" is FALSE only if some target's U has a zone not in the list;
- any target without a known U, any unrecognised target shape (`target-unrecognized`), a rule with `target-tags`
  selectors that are not resolved to devices, or a missing/stale read keeps the result `null` (UNKNOWN).

## 4. Open points for the PO / council

1. Whether a zone with no interface member counts for "the later rule matches traffic in that zone". Today's name-vs-name
   FALSE already assumes yes; the universe must then include empty zones (it would, under A).
2. Maximum age of the zone read relative to the policy snapshot, and what the screen says when the read is stale.
3. Panorama-only estates: firewalls that are not inventoried (no device row) keep UNKNOWN; the count of such targets is
   unknown until measured.
4. Expected gain is not measurable from stored data without reading configuration text. After A, a counters-only harness
   (as in `policy_collection_gaps`) would state how many of the 481 become decided, and in which direction.

## 5. Recommendation

Approve Option A as one lane: parse-scope extension + migration + service join + `zoneHolds` universe argument, with
tests on synthetic multi-vsys configs (two vsys with different universes, a template-pushed zone, an empty zone, a
target without a read). Until then the 481 stay UNKNOWN, which is correct.

## 6. Decision (PO, 2026-11-15) and what was built

Approved: Option A, as section 5 recommends, with these answers to the open points of section 4:

1. A zone with no interface member counts as a zone. The stored set holds every `vsys/entry/zone/entry` name.
2. The stored zone read may be at most 24 hours old, measured from the analysis time to the time the effective-running
   configuration was collected (not the inventory run that parsed it). An older read is UNKNOWN, the same as a missing one.
3. Firewalls Panorama manages but neXus does not inventory have no stored set, so the rule stays UNKNOWN.
4. The gain is stated after the ship by a counts-only measurement (see the PR description / lane report).

The truth table of `PolicyNormalization.zoneHolds(outer list of names, inner lone any)`, over the targets of the policy
snapshot (each a firewall and a vsys):

| every target has a fresh, non-empty stored set | some target has a zone the list lacks | result |
|---|---|---|
| yes | no (the list holds every zone of every target) | TRUE |
| yes | yes | FALSE |
| no: a target has no set, a set older than 24 hours, a Panorama target without a vsys context, an unmatched or non-inventoried firewall, or the snapshot has no target | - | UNKNOWN (as before) |

An unknown target wins over a FALSE the other targets would give. Everything else in `zoneHolds` is unchanged: an earlier
lone any holds everything, equal sets hold, names against names are decided, a set mixing any with names is never
interpreted. A stored set with no zone at all is not a universe a rule can be judged against, and counts as unknown.

Built:

- `V229__pan_vsys_zone_set.sql`: table `pan_vsys_zone_set` (device, vsys entry name, sorted zone names, `read_at`), cascade
  with the device, audit trigger, `vsys` and `zone_names` tier-2 redacted.
- `PaloAltoConfiguredInterfaceParser` keeps the zone names per vsys from the document it already parses. The inventory run
  stores them (`PanVsysZoneRepository.replace`, by difference) next to the interfaces. No new device command and no new API
  call: the document is the stored effective-running configuration the run already opens.
- `ZoneUniverse` joins the stored sets to the snapshot's targets (`PolicyQueryService`) and the hygiene engine reads it
  (`PolicyNormalization.zoneHolds/zonesCover/zonesCannotCover`, `PolicyHygiene` zone index).

Limits, stated so the gain is not over-read: the inventory run opens the stored effective-running document only for a
PAN-OS 12 firewall whose interfaces are replaced from it (PO 2026-10-24, `replace_12`); a firewall on an earlier version
gets no zone set from this change and stays UNKNOWN, and so does any firewall whose configuration was collected more than
24 hours before the analysis. Reading the stored document for every firewall would widen what the inventory run does and
is not part of this decision. A Panorama target that names no vsys is UNKNOWN (no guess from "the firewall has one vsys").
