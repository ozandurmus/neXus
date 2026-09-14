# roles/WORKER.md — orchestrated worker brief template

This is the fixed template `scripts/orchestrator.py::render_worker_md` reads
at dispatch time (parsed, not a Python string literal) to build each
movement's own `.nexus/WORKER.md` — the file an orchestrated worker actually
reads. `.nexus/WORKER.md` is this template's rendered instance for one
movement: the movement id, objective, scope, acceptance criteria,
invariants, risks, validation commands and git lane are filled in from the
approved `SESSION_START`; the two sections below are carried verbatim into
every rendering, unchanged, and are the only content this file supplies.

## Relay closeout

Immediately before opening your PR, re-read the canonical relay file
(NEXUS_RELAY_FILE) first and act on any RELAY_CORRECTION or
RELAY_DECISION entries appended after dispatch. Write your SESSION_CLOSE
report as JSON to a file first -- `--report` takes a file path (or `-` for
stdin); the report JSON is never passed inline on the command line. Then
close with:

```
python3 scripts/local_relay.py append --file $NEXUS_RELAY_FILE --role engineer --marker SESSION_CLOSE --report /path/to/session_close_report.json --outcome DONE
```

## Standing rules

Smallest diff that satisfies every acceptance criterion; no scope
expansion. Do not scan the repository beyond the files this brief
names; do not read docs/history/**, project/*.json, Graphify, device/
deployment/collection code, or secrets unless this brief names them.
Pull open work from project/QUEUE.md instead. Never treat
a long-running validation command -- the full pytest regression in
particular -- as backgroundable: run it as a foreground, awaited Bash
command and wait for it to actually finish before acting on its
result. A relay-tool error is reported, not treated as a blocker. When this brief or the approved task
names a mechanical detail -- a field name, a path, a method signature, an
enum value, a column -- and the code says otherwise, the code is
authoritative: verify it, follow it, and name the divergence in the
SESSION_CLOSE. Raise a RELAY_QUESTION and stop only when a decision is in
conflict: authority, scope, semantics, or a clause of a frozen contract. A
wrong field name is not a contract conflict.
Before every commit run `git diff --check origin/main` yourself and fix
what it names; trailing whitespace on an added line fails verification after
the work is already correct, and it has cost two movements a cycle each.
Leave the worktree clean: write reports and scratch files outside the
repository, or remove them before you close, because verification's first
step is that nothing is untracked.
Never end your turn on a chat message without a SESSION_CLOSE.
