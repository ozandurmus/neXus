You work in a fresh clone of github.com/ozandurmus/neXus (neXus: an on-prem firewall operations platform, Java 21/Spring/jOOQ/Gradle backend under ui2/, React/TS/MUI frontend in ui2/frontend).
Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/policy-unknown-analysis-1105 origin/cloud-base/1105. Read AGENTS.md first: it is binding (masking law: fixtures and previews only use masked names like FW-TANGO-04 and 192.0.2.x addresses; never real hostnames or IPs; English repo artifacts; UNKNOWN over guessing).
You have no access to the live system, HOST-A, the VPN or devices; don't try. There are no device commands in this task.
Tests: backend `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test` (plus :worker:test if you touch the worker); frontend `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`. All must pass; never weaken a test to pass.
Finish: commit on cloud/policy-unknown-analysis-1105 with English messages ending with "Co-Authored-By: Claude", then `git push origin cloud/policy-unknown-analysis-1105`. Do NOT open a PR and do NOT merge or push to main. End with a short report: what changed, the test counts, open issues.

TASK (analysis plus a design document; code only if trivially safe): neXus shadow/redundancy analysis returns UNKNOWN 'earlier rule has unsupported constraints' for about 35,700 Check Point and 22,000 Palo Alto rules (live counts, 2026-11-05).
The top blocking classes among earlier rules are:
- PAN 'unresolved object' (4,475);
- PAN 'unsupported rule constraint' (2,484);
- CP 'dynamic or unsupported object type' (2,000);
- PAN 'unsupported address representation' (1,578);
- CP 'non-terminal or unknown action' (1,335);
- CP 'empty or dynamic group' (272);
- CP 'unsupported port expression' (168);
- CP 'missing service values' (160).
Study ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java, PolicyNormalization.java, the oracles (LegacyPolicyHygiene, PartialPriorOracle) and the vendor semantics documents docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS.md and PO_DECISION_RECORD_2026_11_03_CP_LAYER_SEMANTICS.md.
For each class, decide which part can be made decidable SAFELY: sound, fail-closed, with each claim backed by official vendor documentation (cite URLs) or by existing frozen repo semantics. A typical safe technique is 'bounds used only to prove disjointness'. Mark what must stay UNKNOWN.
Write docs/design/POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md (status DRAFT) with:
- per class: the semantics, the safe extension, the expected effect (qualitative), tests and oracle changes, and the risk;
- a ranked implementation plan.
Optionally implement the top one or two extensions with differential and oracle tests, if you can make them provably sound; otherwise code nothing.