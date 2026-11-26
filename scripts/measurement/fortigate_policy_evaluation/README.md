# FortiGate policy hygiene and lookup evaluation measurement (counts only)

Backlog `asa_fortigate_policy_model`. Controlling authorities:
`docs/design/PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` (`RATIFIED`, items 1-3) and
`docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (`FROZEN`, `A0-FG-1..27`, specifically
`A0-FG-18` `FGT_VIP_PRIORITY_UNPROVEN` and `A0-FG-20` `FGT_MATCH_VIP_UNPROVEN`). Closes the unmeasured FortiGate lookup and
VIP-published service `UNKNOWN` count gap recorded in `docs/design/P1_COUNTS_ONLY_RUN_SHEET_2026_11_26.md` section 6.

**This lane wrote and offline self-tested the harness; it has not been run against any live environment.** The in-pod run
belongs to the PO session, needs the PO session's go, and runs outside ship/e2e windows because `/app/tmp` is lost on a
rollout. No live counts are recorded in the repository by this lane.

## What it reads and evaluates

Inside `ui2-service`, one read-only database connection (`conn.setReadOnly(true)`,
`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, `SET statement_timeout = '120s'`), the pod's own secret files and
artefact store, zero network-device contact, zero new commands, zero writes:

- Queries the latest stored `show_configuration` artefact per non-disabled `ENROLLED`/`DEGRADED` FortiGate gateway
  (`d.vendor_hint = 'fortinet' and d.role in ('gateway', 'firewall')`) via the same `device_configuration_run` +
  `configuration_artefact` + `backup_artefact` read-only join as `AsaEvaluationMeasure.java`.
- Decrypts each artefact in memory via `FileArtefactStore`, parses with `FortiGateConfigPolicyParser.parse(...)` (using the
  stored `#config-version=` header or `observed_software_version` fallback), maps all VDOMs with
  `FortiGatePolicyMapper.map("dev", "masked", "2026-11-14T00:00:00Z", "artefact", parsed)`, and discards raw configuration
  text immediately.
- For each mapped VDOM `PolicySnapshot`:
  - Records rule, security-rule, object, VIP, and collection-failure counts (`failure.<slug>`, e.g. `fgt_unsupported_mode`,
    `fgt_defaults_unproven`).
  - Runs `PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-11-14T12:00:00Z"), PolicyHygiene.TOTAL_WORK)` and counts
    `hygiene_ms`, `hygiene_budget_reached`, `shadow.<slug>`, `shadow_reason.<slug>`, and `finding.<slug>`.
  - Runs bounded in-memory `PolicyLookup.prepare(snapshot, 250_000)` over up to 200 deterministic queries per VDOM
    (`new Random(7)`):
    1. **General rule-derived queries** (`lookup_queries`, `lookup_ms`, `verdict.<slug>`, `unknown_reason.<slug>`): built
       in memory from resolved IPv4 host/subnet/range samples and TCP/UDP ports of enabled rules across VDOM interfaces.
    2. **VIP-targeted queries** (`vip_queries`, `vip_ms`, `vip_verdict.<slug>`, `vip_unknown_reason.<slug>`): built in
       memory from parsed `VipObject` external (`extip`) and mapped (`mappedip`) IPv4 destinations in that VDOM so the PO
       session directly measures how many VIP-published service lookups return `UNKNOWN` (`fgt_vip_priority_unproven`,
       `fgt_match_vip_unproven`, `unsupported_rule_constraint`, etc.) versus concrete `ALLOWED`/`DENIED` verdicts.

## What it prints

`evaluation.out` in the staged directory: tab-separated lines whose every field matches `[a-z0-9_.]+`. Devices are ordinalized
as `fgt_01`, `fgt_02`, ..., VDOMs within each device as `fgt_01.v01`, `fgt_01.v02`, ..., and every status, verdict, failure,
and reason slug comes from a closed vocabulary (`other` for anything outside the closed sets). No device ID, hostname, VDOM
name, rule name, VIP name, interface name, IPv4/IPv6 address, or exception message can reach the output; a top-level failure
prints only `fatal<TAB><lowercase exception class>`. `show_safe.sh` drops (and counts as `filtered_lines`) any line with an
unsafe field.

| line | meaning |
|---|---|
| `fgt_NN vdoms <n>` | mapped VDOM count for device ordinal `fgt_NN` (`0` plus `fgt_NN error.<class> 1` if artefact read failed) |
| `fgt_NN.vMM vdoms 1`, `rules <n>`, `security_rules <n>`, `objects <n>`, `vips <n>`, `failures <n>` | per-VDOM structural and collection-failure counts |
| `fgt_NN.vMM failure.<slug> <n>` | closed failure code counts (`fgt_unsupported_mode`, `fgt_defaults_unproven`, `other`) |
| `fgt_NN.vMM hygiene_ms <ms>`, `hygiene_budget_reached <0\|1>` | hygiene runtime and whether `PolicyHygiene.TOTAL_WORK` was reached |
| `fgt_NN.vMM shadow.<slug> <n>`, `shadow_reason.<slug> <n>`, `finding.<slug> <n>` | hygiene shadow statuses (`not_shadowed`, `redundant`, `conflict`, `unknown`), closed `PolicyHygiene.REASONS` slugs, and `PolicyHygiene.CLASSES` counts |
| `fgt_NN.vMM lookup_queries <n>`, `lookup_ms <ms>`, `verdict.<slug> <n>`, `unknown_reason.<slug> <n>` | general rule-derived lookup counts, verdicts (`allowed`, `denied`, `unknown`, `partial`), and closed `UNKNOWN` reason attribution |
| `fgt_NN.vMM vip_queries <n>`, `vip_ms <ms>`, `vip_verdict.<slug> <n>`, `vip_unknown_reason.<slug> <n>` | VIP-targeted (`extip` and `mappedip`) lookup counts, verdicts, and closed `UNKNOWN` reason attribution (`fgt_vip_priority_unproven`, `fgt_match_vip_unproven`, `unsupported_rule_constraint`, ...) |
| `summary devices <n>`, `summary vdoms <n>`, `summary heap_retained_mb <mb>`, `summary <counter> <n>` | fleet-wide totals and retained heap across all mapped snapshots |
| `done` | terminal completion token |

## Self-test (no pod, no database, no device)

Build the module classes once, then run the offline self-test:

    cd ui2 && ./gradlew --no-daemon :platform-core:classes :persistence:classes :service:classes
    bash scripts/measurement/fortigate_policy_evaluation/selftest.sh

`selftest.sh` verifies `show_safe.sh` on a safe line plus an unsafe `FW-TANGO-04` line (`show_safe<TAB>ok`), compiles
`FortiGateEvaluationMeasure.java` against local `:service`, `:persistence`, and `:platform-core` classes plus cached jars,
runs `--selftest` over inline synthetic FortiOS 7.4 configurations (covering traditional-NAT VIP `A0-FG-18`
`FGT_VIP_PRIORITY_UNPROVEN`, `A0-FG-20` `FGT_MATCH_VIP_UNPROVEN`, shadowed/conflicting hygiene rules, `ngfw-mode policy-based`
and `opmode transparent` `FGT_UNSUPPORTED_MODE` VDOMs, and planted `SYNTH`/`CANARY`/`FW-TANGO-04`/RFC 5737 canaries), and
passes the generated `evaluation.out` through `show_safe.sh` asserting `filtered_lines<TAB>0`.

## Run (PO session only)

    MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/fortigate_policy_evaluation/measure_run.sh <tag>

`measure_run.sh` reads the operator host from `~/.config/nexus/hosta`, refuses to start while `hosta_e2e.sh` runs, copies and
verifies `/app/service.jar`, compiles only `FortiGateEvaluationMeasure.java` against it, stages `fgteval-<tag>` (with
`run_in_pod.sh` and `show_safe.sh`) into `/app/tmp`, executes `run_in_pod.sh` (`java -Xmx2g` via `PropertiesLauncher`,
1800 s timeout), waits for `done` or `fatal` in `evaluation.out`, writes the `show_safe.sh`-filtered output to
`fgteval-<tag>.tsv`, and removes `/app/tmp/fgteval-<tag>`.
