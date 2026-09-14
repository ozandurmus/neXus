# Orchestrator: a worker that parks a RELAY_QUESTION and exits is recorded failed (relay_not_closed) and cannot be resumed after the RELAY_DECISION; run refuses because the worktree exists

status: planned · target: scripts/orchestrator.py decide_start -- add a resume path when the relay's last marker is RELAY_DECISION and next_actor is engineer (GOV.ORCH successor); observed on NXS-LOCAL-0147, 2026-09-13

GOV.ORCH.12 implemented by NXS-LOCAL-0166: answered relay questions resume once per newer Product Owner answer.
