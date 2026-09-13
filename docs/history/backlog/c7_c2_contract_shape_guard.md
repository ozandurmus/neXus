# Contract-shape guard for C7 §5.3 and C2 §6 admission batteries

status: planned · target: successor to the test PR #180 carried

tests/ pins contract STATUSES but nothing pins the SHAPE of a frozen contract's clause list. PR #180 carried tests/test_ui2_d1_restore_admission_contract.py which did exactly that -- asserting C7 section 5.3 has seven checks in a fixed order and C2 section 6 six claim-time checks. Run against main's contracts on 2026-09-13, all four of its assertions FAIL: it pins the exact wording of an earlier generation of those contracts. Deliberately NOT imported. Write the equivalent against main's current text. Value: a frozen contract can currently drift in content while its status line still reads FROZEN.
