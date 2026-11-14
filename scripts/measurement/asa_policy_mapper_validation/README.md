# ASA policy parser/mapper validation over stored configurations

Runs this branch's `AsaConfigPolicyParser` and `AsaPolicyMapper` inside `ui2-service` over the latest stored ASA
`show_configuration` artefacts and prints counters only (every field `[a-z0-9_.]+`; devices are ordinals). No device
contact. Diagnostic keys use closed vocabularies: never emit a token that comes from the configuration (an ACL, object
or group name) even when it would pass the character filter.

    VALIDATE_WORK_DIR=<scratch dir> bash scripts/measurement/asa_policy_mapper_validation/validate_run.sh <tag>

Result of the first runs: `docs/design/ASA_POLICY_MAPPER_VALIDATION_2026_11_14.md`.
