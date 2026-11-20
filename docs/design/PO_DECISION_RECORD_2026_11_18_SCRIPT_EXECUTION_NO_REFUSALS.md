# PO Decision Record — 2026-11-18 — Script Execution: no upload refusals for secret findings or device egress; encrypted write-only script bodies; declared credential-store references

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-11-18.** Amends `SCRIPT_EXECUTION_CONTRACT.md` (§2, §3.1, §3.3, §3.6, §5, §6).

## Product Owner quotes (2026-11-18, verbatim)

- "saçmalama niye reddediyorsun ya ... Gizli veri taraması ... Bir cihaz adresine çıkış izni isteyen script reddediliyor ... ayrı bir pod istiyorum dedik"
- "bu script hashli tutulmalı kimse bakamamalı"
- "kodun içine nexustaki credential storedan bir credential çağırma yetkinliği vermeliyiz"

## 1. Secrets in a script are a warning, never an upload refusal; script bodies are encrypted at rest and write-only

1. **Privacy-gate scan produces a warning, not a refusal.** Every uploaded script version is scanned with the repository privacy-gate secret patterns (`PRIVATE_KEY_MATERIAL` and `CREDENTIAL_LITERAL` from `tools/privacy/repository_privacy.py`). Matches are **not** refused; instead, the upload succeeds and returns a structured warning containing the finding count, line numbers, pattern classes (never the matched values), and a recommendation to use declared credential-store references (`credential_refs`) instead of inline credentials. The findings metadata (`count` and per-finding `line` + `pattern_class`) is persisted on `script_version.secret_findings`.
2. **Envelope encryption at rest (`script_body`).** Because a script body may contain credentials, all script bodies (both text and binary kinds) are stored encrypted at rest using the artefact store's AES-256-GCM envelope encryption (artefact class `script_body`, fresh random 256-bit data key wrapped under the artefact-store master key). The `script_version` database row stores only `sha256`, `size_bytes`, `artefact_ref`, `key_id`, `wrapped_data_key`, and `secret_findings` — never plaintext script text or bytes.
3. **Write-only / no read-back ("bu script hashli tutulmalı kimse bakamamalı").** Script bodies are never written to application logs, audit rows, or job transcripts. No API endpoint, CLI command, or UI route ever returns a stored script body — not even to `role:security_admin`. Read routes return metadata only (`script_id`, `name`, `kind`, `current_version`, `sha256`, `size_bytes`, `secret_findings`, `credential_refs`, `egress`, `egress_device_targets`, schedule, and settings). Only the isolated `ui2-script-runner` pod (slice 2) decrypts a version into its ephemeral per-run scratch volume to execute it. Updating or editing a script always means uploading a new version.

## 2. Egress to an enrolled device address is a warning, never a refusal

1. **Any `host:port` may be declared in `egress`.** A script or version is never refused because an entry in `egress` matches an enrolled device's management address.
2. **Stored-data device correlation (`egress_device_targets`).** When a declared `host:port` has a host part that equals an enrolled device's management address (checked against stored endpoint data only, without DNS or network lookups), the script and version record those opaque device identifiers in `egress_device_targets` and the API/UI/CLI surfaces a warning stating that the script declares a direct network path to enrolled device(s) outside neXus command gates. In slice 2, each `script_run` record will list `egress_device_targets` on the run ledger.
3. **Gated CLI writes unchanged.** Device writes invoked through `nexus-cli` continue to be governed by `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md`.

## 3. `.bat` and `.cmd` remain refused (technical impossibility on Linux)

`.bat` and `.cmd` uploads continue to be refused with the message `"cannot run on the Linux runner; use PowerShell (.ps1)"`. This is a runtime platform constraint of the Linux runner container, not a policy refusal.

## 4. Dedicated runner pod (unchanged)

Scripts execute in the dedicated `ui2-script-runner` Deployment (`SCRIPT_EXECUTION_CONTRACT.md` §3.2, delivered in slice 2).

## 5. Declared credential-store access from inside a script (`credential_refs`)

1. **Declaration and validation on the record side (slice 1).** A script version (and script record) declares `credential_refs`: an array of credential-store reference IDs (`credential_references.credential_reference_id`) selected by a `role:security_admin` and validated at write time to exist in `credential_references`. Credential values are never accepted or returned on script routes. Both the REST API and `nexus-cli` (`script-add`, `script-set`) support setting `--credential-refs` / `credential_refs`.
2. **Resolution, injection, and output masking at run time (slice 2 design).**
   - At run start, the slice 2 runner resolves **only** the `credential_refs` declared on the executing `script_version`. A running script has no mechanism or token to query arbitrary credentials from the credential store; any undeclared credential is unreachable.
   - Each resolved credential reference is exposed to the child process either as environment variables (`NEXUS_CRED_<ALIAS>_USERNAME` and `NEXUS_CRED_<ALIAS>_SECRET`, where `<ALIAS>` is the uppercased, non-alphanumeric-to-underscore normalized reference ID or ordinal) or as a mode `0600` file inside the run's ephemeral scratch directory, wiped immediately when the run ends.
   - The runner feeds every resolved secret value into the per-run stdout/stderr masker before any output is truncated, stored as `script_output`, or forwarded to notifications, ensuring resolved secret values never appear in captured output.
   - The run record (`script_run`, slice 2) records the exact list of `credential_refs` resolved for that execution.

## 6. Follow-up 2026-11-18 (b) -- independent review and broker/execution split

The independent review of slice 2 (`ops-scripts/reviews/script-s2-review-1118.md`, 2026-11-18, verdict DO NOT SHIP,
findings 1-13) found that scripts ran under the runner JVM's own uid, so a script could read the runner's database,
artefact-store and credential-store keys from `/proc/1/environ`; that per-script egress was not enforced; and that runs
were not isolated from one another. The orchestrator session decided the fix the same day, recorded as
`SCRIPT_EXECUTION_CONTRACT.md` "Amendment 2026-11-18 (b)":

- §4 above ("dedicated runner pod") now means a dedicated **broker** pod plus one short-lived execution pod per run;
  the execution pod has no service-account token, no secret in its environment and no key of any store.
- §5.2's "environment variables or a `0600` scratch file": the declared credentials are delivered as a mode-`0400`
  scratch file (and the same `NEXUS_CRED_<ALIAS>_*` variables, built by the launcher inside the execution pod from
  that file), never through a Kubernetes `secretKeyRef`.
- The decisions of §1-§3 and §5 are unchanged: no refusal for secret findings or device egress (alias collisions are
  disambiguated and warned, not refused), bodies encrypted and never readable back, only declared refs injected and
  masked. The one new upload refusal is technical, like `.bat`: `.ps1` until PowerShell 7 is in the execution image.
