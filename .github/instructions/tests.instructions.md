---
applyTo: "tests/**"
---

# SecurityExpert Test Contract

Tests committed to source control must use synthetic/repository-safe data —
never real production hostnames, management IPs, serial numbers, internal
domains, usernames, credentials or topology identities. Use the
documentation ranges and synthetic names in `PRIVACY_AND_DATA_HANDLING.md`
"Source-code hygiene" (the three RFC 5737 blocks and names such as
`CP-SPARK-TEST-01`, `PAN-FW-TEST-01`, `example.invalid`) rather than
inventing new ones.

Preserve known expected xfails unless the build explicitly fixes them — see
`CURRENT_STATE.md` "Known xfails" for the current list. Do not turn
regressions into new xfails merely to make tests pass.
