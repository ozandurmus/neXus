# PAN-OS below 12: stored zone-set measurement (counts only)

Backlog `policy_collection_gaps`. PO answer 2026-11-23 (`PO-2026-11-23-ayyas-midday`): "PAN-OS below 12 zone sets: measure
first -- counts only, inside the pod, no device command." Zone sets are stored only for a PAN-OS 12 firewall today
(`docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6); 29 of the 38 targeted firewalls are not PAN-OS 12
(`docs/design/POLICY_COLLECTION_GAPS_LIVE_MEASUREMENT_2026_11_17.md` section 4). This harness answers, with counts, whether
a usable zone universe would exist if the stored read were widened. It does not widen anything.

**This lane wrote and self-tested the harness; it has not been run against any environment.** The in-pod run belongs to the
PO session, needs the PO session's go, and runs outside ship/e2e windows because `/app/tmp` is lost on a rollout. Its
result becomes a MEASUREMENT RECORD written by the PO session; no counts are recorded here.

## What it reads

Inside `ui2-service`, database opened read-only with the pod's own env files, no device contact, no new command:

- the enrolled (`ENROLLED`/`DEGRADED`), not disabled Palo Alto gateways, with the bucket of `observed_software_version`
  (major only; `v9`, `v10`, `v11`, `vother`, `vunknown`; PAN-OS 12 is left out and only counted);
- for each, the newest stored effective-running document through `v_latest_effective_running_config` (the join of
  `LatestEffectiveRunningReader`: run, configuration artefact, backup manifest wrapped key, minus removed artefacts),
  decrypted in memory with the artefact-store key, read through at most 64 MiB of decompressed text;
- the vsys-to-zone-name sets of the existing `PaloAltoConfiguredInterfaceParser.parse(InputStream)` (no second zone parser).
  The document is discarded after counting.

## What it prints

`measure.out` in the staged directory, tab-separated, every field `[a-z0-9_.]+`, fixed counter names, bucket tokens and
integers only. No name, address, serial, vsys name, zone name, document text or exception message can reach it; a failure
prints only `fatal<TAB><lowercase exception class>`. `show_safe.sh` drops (and counts) any line with an unsafe field.

- `scope fleet total_pan_gateways|pan_os_12_gateways|measured_not_12_gateways n` -- enrolled, not disabled PAN gateways of
  every version (relate it to the 38 targeted firewalls), those on PAN-OS 12 (not measured), and those measured.
- `device pan_NN version_bucket <token>` and `device pan_NN <counter> <n>`, one block per measured firewall (ordinal in
  device-id order; it maps to nothing).
- `bucket <v9|v10|v11|vother|vunknown|all> <counter> <n>`, the sums of the device lines (the maximum for `max_zones_in_vsys`).

Counters (per device 0/1 flags or counts; a firewall without a document has only `firewalls_enrolled` and
`without_stored_document`):

| counter | counts |
|---|---|
| `firewalls_enrolled` | firewalls measured |
| `with_stored_document` / `without_stored_document` | a stored effective-running run exists / does not |
| `age_le_24h`, `age_le_7d`, `age_older`, `age_unknown` | document age by `collected_at` at measurement time (<= 24 h, <= 7 d, older; unknown = no or future timestamp) |
| `parsed_ok` / `not_parseable` / `skipped_size` / `artefact_unreadable` | exactly one per stored document: read by the parser; not XML or not exactly one device entry; over the 64 MiB cap; the artefact could not be retrieved, decrypted or read |
| `with_vsys` | parsed, at least one readable vsys |
| `vsys_total` | vsys the parser read in full |
| `vsys_with_zone` / `vsys_zero_zone` | of those, with at least one zone / with none |
| `zone_total` | zones summed over vsys (a vsys's zone names are distinct; the same name in two vsys counts twice) |
| `max_zones_in_vsys` | the largest zone count of one vsys |
| `zone_set_candidate_le_24h` | convenience flag, exactly: age <= 24 h and parsed and at least one vsys and no vsys without a zone. It is a count to read, not an approval of the widened read; the decided rule's 24 h applies at analysis time, this age is at measurement time |

## What the existing parser cannot expose

- **No `src="tpl"` counter.** `zoneNamesByVsys` returns names only; the template-pushed marker is dropped before it, and a
  second zone parser (or a parser change) is out of scope. The counter is omitted, not estimated.
- A vsys the parser cannot read in full (a zone entry without a name, two vsys entries of one name, a zone name over 128
  characters) is left out of `zoneNamesByVsys`, so it is in no vsys counter. The harness cannot count such vsys.
- `vother` is every major that is not 9, 10, 11 or 12 (below 9, and 13 or later), per the brief's "not 12".
- A decrypt failure after the read has started is `artefact_unreadable`, as is a retrieve failure; both look the same.

## Self-test (no pod, no database, no device)

Build the classes once, then:

    cd ui2 && ./gradlew :worker:classes :persistence:classes :platform-core:classes :job-engine:classes
    bash scripts/measurement/pan_vsys_zone_sets_pre12/selftest.sh

`selftest.sh` first feeds `show_safe.sh` one unsafe and one safe line (prints `show_safe<TAB>ok` when the unsafe one is
dropped and counted), then compiles the class and runs the exact command

    javac --release 21 -nowarn -cp "<ui2>/{worker,persistence,platform-core,job-engine,capability-registry}/build/classes/java/main:<postgresql 42.x jar>" -d <tmp> PanPre12ZoneSetMeasure.java
    java -cp "<tmp>:<same classpath>" nexusmeasure.PanPre12ZoneSetMeasure --selftest

which must print `selftest<TAB>ok` (inline synthetic XML: two vsys, one with zero zones, a template-pushed zone, a document
that is not XML, a size cap, an unreadable stream, version and age buckets).

## Run (PO session only)

    MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/pan_vsys_zone_sets_pre12/measure_run.sh <tag>

`measure_run.sh` reads the host from the operator's own config file, refuses to start while a deploy e2e runs, compiles the
harness against the pod's own service jar (it compiles only the harness; if the pod's parser is older than this branch's
and lacks `zoneNamesByVsys`, the compile fails and nothing runs), stages `pre12zs-<tag>`, runs `run_in_pod.sh`, waits for
`done`/`fatal`, prints the `show_safe.sh`-filtered output into `pre12zs-<tag>.tsv`, and removes the stage directory.
