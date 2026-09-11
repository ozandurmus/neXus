# 0.6.6A — CP/PAN parser correctness hardening

status: automated_validated · target: 0.6.6A

STATUS CORRECTED 2026-08-30: this item was left 'planned' even though its own sub-items (vsx_network_canonicalization, pan_default_route) were separately recorded as AUTOMATED_VALIDATED 2026-08-27 -- a stale parent status, not open work. Verified against current code this session: checkpoint/vsx_parser.py computes canonical network = ip_network(ip/prefix) (AC-1); panorama/panorama_runtime_runner.py classifies dest == "0.0.0.0/0" as type=default before other flags (AC-2); no strict xfail remains for either case in tests/ (AC-3/AC-4). Contract: docs/history/phase/PHASE0_6_6A_PARSER_CORRECTNESS_HARDENING.md (status line there still needs a manual DONE stamp by whoever owns that doc -- left untouched here since it is an archived agreement record, not a living tracker).
