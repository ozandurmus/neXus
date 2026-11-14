# ASA policy evaluation measurement (hygiene and lookup over stored configurations)

Runs this branch's ASA parser/mapper and policy engine (PolicyNormalization, PolicyLookup, PolicyHygiene) inside
`ui2-service` over the latest stored ASA `show_configuration` artefacts and prints counters only (every field
`[a-z0-9_.]+`, closed vocabularies: statuses, fixed reason sets, verdicts). Lookup queries are built in memory from each
policy's own resolved host destinations and tcp ports; no address or name is printed. No device contact.

    EVAL_WORK_DIR=<scratch dir holding podjar/ (the pod's service.jar unpacked)> bash evaluation_run.sh <tag>

Result: `docs/design/ASA_POLICY_EVALUATION_MEASUREMENT_2026_11_14.md`.
