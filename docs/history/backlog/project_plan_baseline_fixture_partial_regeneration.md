# Regression: project_plan_payload_baseline.json current_build not regenerated with roadmap.json, test_project_plan_render_equivalence fails

status: planned · target: 

REGRESSION from this branch (55 commits ahead of origin/main). Proof: tests/test_project_plan_render_equivalence.py passes on the origin/main tree (git archive origin/main extracted to scratch: '2 passed in 0.03s') and fails on this branch.

Failure:
tests/test_project_plan_render_equivalence.py:58: in test_live_payload_matches_baseline_except_archive_split
    assert live[key] == baseline[key], key
E   AssertionError: current_build
E   assert 'ui2_d1_restore_c7_c2_amendments' == 'ui2_b1_01_skeleton_ci_docker'

Cause: commits 5342065 / ae66024 / ad70706 advanced project/roadmap.json current_build from 'ui2_b1_01_skeleton_ci_docker' to 'ui2_d1_restore_c7_c2_amendments' and only PARTIALLY regenerated tests/fixtures/project_plan_payload_baseline.json (build_count 191->194 and three build_ids appended) -- the fixture's own current_build field was left at the old value.
Live: utils.project_plan.build_project_plan_payload()['current_build'] == 'ui2_d1_restore_c7_c2_amendments'
Fixture: tests/fixtures/project_plan_payload_baseline.json current_build == 'ui2_b1_01_skeleton_ci_docker'

Fix direction (NOT applied -- reporting only, per task scope): regenerate the baseline fixture from the live payload rather than hand-patching selected keys.

Resolved 2026-09-12, but NOT by regenerating the fixture. The fixture's value is that it predates the GOV.ORCH.8 move; regenerating it from the live payload would compare the payload to itself. The real defect was in the test: it asserted equality on current_build, backlog_counts, the progress percentages, track progress, completed_feature_ids and backlog_ids against a frozen pre-move capture, i.e. it asserted that project state never changes -- which scripts/project_queue.py exists to change. Measured before changing anything: no baseline build id was lost (194 baseline ids, all reachable across live plus archive), and the two new ids are today's recorded builds. The test now pins the permanent invariant instead -- nothing recorded before the move may become unreachable, the archive must account for itself, and backlog/feature ids only accrue -- and was proven to fail by name when a lost id is injected.
