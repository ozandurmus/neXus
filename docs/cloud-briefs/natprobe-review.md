Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/natprobe-review-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law, English, UNKNOWN over guessing). No access to the live system. Small task: finish within about 15 minutes.

TASK (review): branch origin/cloud-src/natprobe-1105 (one commit on top of main) adds a one-off Check Point NAT probe by package UID that runs only when the NAT read by name fails with VENDOR_NAME_NOT_UNIQUE. The PO approved this exact literal: `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true`.
Review that commit (`git diff origin/main...origin/cloud-src/natprobe-1105`) for:
- exact-literal fidelity;
- gate migration and fixture consistency;
- UID validation and quoting;
- the probe running only on that failure, at most once per package per run, never retried;
- probe content discarded (raw-evidence law);
- outcome recorded as an enum only;
- tests.
Write docs/design/NAT_UID_PROBE_REVIEW_2026_11_05.md with findings and fixes. If fixes are clear-cut, apply them on cloud/natprobe-review-1105, branched from origin/cloud-src/natprobe-1105 instead of cloud-base. Run `cd ui2 && ./gradlew :worker:test :architecture-tests:test`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/natprobe-review-1105, no PR.