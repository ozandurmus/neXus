# Palo Alto vsys zone universe for the policy analysis: what the "481" need

## Status

DRAFT - 2026-11-15. Evidence and a proposal, not a contract; nothing here is implementation authority. Written by the
`policy_collection_gaps` lane under "close it from stored data, or stop and write what is needed". No code was changed,
no device was contacted, and no stored customer data was read: this record rests on source reading and on the counts
already published in the 2026-11-13 measurement (`scripts/measurement/policy_collection_gaps/README.md` on branch
`feat/policy-collection-gaps-1113`, commit b8cfcf88).

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
