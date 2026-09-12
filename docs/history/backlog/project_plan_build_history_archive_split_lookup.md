# Pre-existing: build_history archive split removed build 0.6.1B.1.1 from the live payload, test_project_plan_payload_is_data_driven_and_percentages_are_bounded fails

status: planned · target: 

PRE-EXISTING (not from this branch). Proof: fails identically on the origin/main tree.

Failure:
tests/test_phase0_6_1b_1_2_interactive_project_plan.py:119: in test_project_plan_payload_is_data_driven_and_percentages_are_bounded
    assert any(item["build"] == "0.6.1B.1.1" for item in payload["build_history"])
E   assert False

Cause: build 0.6.1B.1.1 is no longer in the live payload's build_history -- it now lives in project/archive/build_history_2026.json (payload reports archived_build_count 163). The assertion predates the build_history archive split and was never reconciled with it. Correct resolution is a contract question (should the payload expose archived records, or should the assertion target a live record?) -- this is NOT to be resolved by editing the assertion to pass. Reporting only.
