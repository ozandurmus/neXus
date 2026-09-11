# Remove dormant remote cleanup helper

status: done · target: Local security hardening, before server exposure

utils/cleanup.py is currently unreferenced but can issue remote rm -f commands over SSH. Remove it and add a regression/privacy check rather than carrying dormant write-capable code into the server product. This local task must not contact any device. DONE 2026-08-31: utils/cleanup.py deleted (it connected with the CP collection credential and issued unaudited 'rm -f' commands over SSH, outside the network-device command gate). New tests/test_remove_dormant_remote_cleanup.py (pytest.mark.security) regression-guards both its absence and against any tracked .py file reintroducing a cleanup_all() equivalent. No device contacted; no other source referenced it (confirmed by repo-wide grep before deletion). Full suite 881 passed / 23 skipped / 2 failed (same two pre-existing, unrelated, order-dependent failures re-confirmed passing in isolation); privacy gate PASS/0.
