# local_relay _next_id reissues an id whose JSON was never committed

status: planned · target: GOV.ORCH successor or scripts/local_relay.py

_next_id globs relay/*.json only. A movement whose relay JSON is never committed frees its id for reuse, while its .lock file, its worktree directory and its orchestrator state record all persist on disk. Observed 2026-09-13: ids 0136 and 0137 were reissued to two new movements and would have collided on the worktree path and merged the usage accounting. Caught before dispatch and the movements were moved to 0138/0139, but the history of the two earlier ones is lost. Fix by committing the relay as part of every movement, or by counting the .lock files _next_id already writes.
