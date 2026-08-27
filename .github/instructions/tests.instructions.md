---
applyTo: "tests/**"
---

# SecurityExpert Test Contract

Tests committed to source control must use synthetic/repository-safe data.

Do not introduce real:

- production hostname,
- management IP,
- serial number,
- internal domain,
- username,
- credential,
- topology identity.

Prefer documentation ranges:

192.0.2.0/24
198.51.100.0/24
203.0.113.0/24

and synthetic names such as:

CP-GW-TEST-01
PAN-FW-TEST-01
example.invalid

Preserve known expected xfails unless the build explicitly fixes them:

- VSX network canonicalization
- PAN default-route classification

Do not turn regressions into new xfails merely to make tests pass.