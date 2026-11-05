Repo github.com/ozandurmus/neXus, branch: git fetch origin cloud-base/1105 && git checkout -b cloud/path-unknown-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law with synthetic 192.0.2.x data only, UNKNOWN/fail-closed law, vendor semantics law). No access to the live system.

CONTEXT: Path analysis (docs/design/PATH_ANALYSIS_DESIGN.md and PATH_ANALYSIS_VENDOR_SEMANTICS.md, both frozen; engine under ui2/service/.../pathanalysis). On the live estate, all 9 computed test queries between interface addresses of different segments returned verdict UNKNOWN: 4 EXITS_KNOWN_TOPOLOGY, 2 NO_ROUTE, 2 AMBIGUOUS_INGRESS, 1 UNKNOWN_HOP.

TASK:
1. From the code, find each mechanism that produces these reasons and the likely real-estate causes (default route treated as leaving the topology, connected networks not attached, VSX VS attachments, ingress candidates on shared segments, unresolvable next hops).
2. Write docs/design/PATH_UNKNOWN_REDUCTION_PLAN_2026_11_05.md (DRAFT): per reason, the cause, a safe improvement consistent with the frozen semantics (never invent certainty), the tests, and the expected effect.
3. Implement the clearly sound improvements, with golden-scenario and engine tests.
4. Run `cd ui2 && ./gradlew :service:test :architecture-tests:test`.
Commit (messages end with 'Co-Authored-By: Claude'), git push origin cloud/path-unknown-1105, no PR.