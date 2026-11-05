# PO decision record - 2026-11-05: one-off Check Point NAT rulebase probe by package UID on VENDOR_NAME_NOT_UNIQUE

**Status: RATIFIED - PO DECISION (chat, 2026-11-05).** Backed by automated validation; real-environment probe outcome recorded as enum only.

## Context
Check Point policy collection reads each package's NAT rulebase by package name using:

`mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true`

On the live Multi-Domain Server, two packages fail this read with `generic_err_object_field_not_unique` (`VENDOR_NAME_NOT_UNIQUE`), leaving their NAT sections `UNKNOWN`. The documented Check Point Management API specifies the `package` parameter by name only, so it is unknown whether `show-nat-rulebase` accepts a package UID (discovered from `show-packages`) when the package name is not unique.

## PO answer (verbatim)
"NAT için komut ok deneyebilirsin" ("Command is OK for NAT, you can try it").

## Decision
This single read-only literal is approved as a `CLASS_0` (`read`) gate (`cp_policy_nat_rulebase_uid_probe`, `check_point`, `cp_multi_domain_server`, `expert`, `SSH_EXEC`, `60` s timeout, `none` retry), seeded `SIGNED_OFF` by `V191__cp_nat_uid_probe_gate.sql` and mirrored in `gate_registry_fixture.yaml`:

`mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true`

### Scope and execution bounds
- **Trigger condition:** issued only after a package's NAT rulebase read by name fails with `VENDOR_NAME_NOT_UNIQUE` and the package's UID is known from `show-packages`. Never issued for any other failure code or when the by-name NAT read succeeds.
- **Frequency:** at most once per package per collection run, in the same interactive SSH session as the failed by-name NAT read; never retried and never polled.
- **Purpose:** solely to learn whether the Check Point Management API accepts a package UID in `show-nat-rulebase package '<PACKAGE_UID>'` when the package name is not unique.
- **No broader command approval:** approval of this literal (`limit 1 offset 0`) does not approve any other command or a full paged NAT read by UID. A full paged read by UID requires a separate PO approval after evaluating the probe outcome.

### Recording and raw-evidence rules
- **Enum-only outcome:** each probe is classified in memory into one of three enum values:
  - `UID_ACCEPTED`: the reply is a well-formed NAT rulebase page;
  - `UID_REJECTED`: the reply is a vendor API error object;
  - `NOT_EVALUABLE`: a transport failure, non-zero exit without a vendor error object, or an unexpected response shape.
- **Raw-evidence law:** the probe's rule and object content is discarded immediately in memory and is never mapped, persisted, logged, or transcribed. The package's NAT section remains `UNKNOWN` (`VENDOR_NAME_NOT_UNIQUE`) exactly as before.
- **Telemetry and metadata:** the collector records a counts/enum-only summary note in the job transcript (`[CP_NAT_UID_PROBE] packages=<total> accepted=<N> rejected=<M> not_evaluable=<K>`) and attaches the opaque enum outcome (`natUidProbe`) to the package's `VENDOR_NAME_NOT_UNIQUE` failure entry in snapshot metadata, with no package, domain, or rule names.

## Real-environment outcome (2026-11-05)
One FULL Check Point policy collection (started as aiview, 34 minutes, `COMPLETED`, partial snapshot as before) issued
the probe for the 2 packages whose by-name NAT read fails with `VENDOR_NAME_NOT_UNIQUE`. Both outcomes are
`UID_ACCEPTED` (counted from the opaque `natUidProbe` enum on the snapshot failure entries; no content read). The
two NAT sections stay `UNKNOWN` (`VENDOR_NAME_NOT_UNIQUE`), as this record requires.

Consequence: the Management API accepts a package UID in `show-nat-rulebase package '<PACKAGE_UID>'` on this MDS.
Replacing the failing by-name read with a full paged read by UID needs a separate PO approval of that literal
(`... show-nat-rulebase package '<PACKAGE_UID>' limit 500 offset '<N>' ...`), as stated above.

## Cross-references
- `docs/design/PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md`
- `docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md` §3d
