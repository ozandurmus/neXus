# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_local_relay_watch_command` — a bounded,
  read-only `watch` subcommand added to `scripts/local_relay.py`, requested
  at `relay/NXS-LOCAL-0003-local-relay-watch-command.json`'s `SESSION_START`
  (Product Owner direction, after real relay#11-#16/local-relay-protocol use
  surfaced manual re-checking friction).
- Small, independent GOV.PO.1 tooling movement — does not touch
  `gov_po_2_po_visibility_and_bounded_authorship`'s (FROZEN, PR #122 merged)
  own scope or `gov_po_2_implementation`'s sequencing, still `now_next.next`.

## 2. What changed

- `scripts/local_relay.py`: new `watch --file FILE --for {po|engineer}
  [--interval SECONDS] [--timeout SECONDS]` subcommand. Default interval
  20s (hard floor 5s, usage error below it); default timeout 1800s (hard
  ceiling 3600s, usage error above it); new exit code 3 ("timeout, no
  change") alongside the existing 0/1/2 convention. Strictly read-only —
  checks `next_actor` immediately, then polls every `--interval` seconds
  up to the deadline; on a match prints one JSON line
  (`{next_actor, entries}`, every entry with `seq` greater than the count
  observed at watch start) and exits 0; on timeout, exits 3 with the file
  untouched.
- `scripts/nexus_po_tool_gate.py`: the four `local_relay.py watch`
  interpreter-spelling prefixes added to `COMMON_PREFIXES` (both PO forms,
  read-only, mirroring `status`/`validate`).
- `docs/design/LOCAL_RELAY_PROTOCOL.md` §8: updated (not replaced) to
  document `watch` precisely — bounded, blocking, explicit-invocation-only,
  decides nothing; `RELAY_DECISION`-class authorization unchanged.
- `tests/test_local_relay_protocol.py` (+9), `tests/test_gov_po_role.py`
  (+1): see §4.
- `project/build_history.json`: new `gov_po_1_local_relay_watch_command`
  record (`automated_validated`), newest-first.
- `project/roadmap.json`: `current_build`/`now_next.now` →
  `gov_po_1_local_relay_watch_command`; `now_next.next`
  (`gov_po_2_implementation`) unchanged.
- `CURRENT_STATE.md`: checkpoint rewritten (197 lines).
- `docs/history/INDEX.md`: regenerated via `scripts/build_history_index.py`.

## 3. Exact next action

1. Merge this movement's branch to main per the standing relay#13 decision
   (`relay/NXS-LOCAL-0003`'s `merge_gate`): once tests are green, the
   engineer merges itself and reports via `RELAY_NOTE` — no separate merge
   `RELAY_DECISION` needed.
2. `gov_po_2_implementation` is next: build the five mechanisms in
   `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §3 —
   unrelated to and unblocked independently of this movement.

## 4. Test delta

New: `tests/test_local_relay_protocol.py` +9 (immediate match with zero
sleeps, a simulated mid-poll flip via a real `append` call inside a
monkeypatched `time.sleep`, timeout with a fake monotonic clock, the
byte-for-byte-unchanged-on-timeout proof, interval-floor/timeout-ceiling
usage errors, missing-file/invalid-file handling); `tests/test_gov_po_role.py`
+1 (`watch` allowlisted in both delegated and interactive forms). Targeted:
168 passed. Full regression: 2585 passed, 25 skipped, 2 failed — both
pre-existing and unrelated (`.venv/` site-packages incidentally scanned by
`tests/test_dev_0_5b_auth_consumer_canonical_config.py`'s repository-text
scan; neither that test nor the code it scans was touched here). `git diff
--check` clean. Repository privacy gate FAIL/3 findings via
`.venv/bin/python main.py --repository-privacy-check` — all pre-existing,
`.gitignore`d local runtime artifacts (`data/`, `data/.support_hmac.key`,
`logs/`), untracked and unrelated to this movement's changed files.

## 5. Risks / notes forward

- `docs/design/LOCAL_RELAY_PROTOCOL.md` §9's CLI synopsis/exit-code table
  still lists only `create`/`append`/`status`/`validate` — this movement's
  `SESSION_START` scoped the doc change to §8 only; a future movement
  should extend §9 to keep the full CLI reference in sync.
- A caller can set a low `--interval` with the `--timeout` ceiling to
  approximate a near-continuous poll for up to an hour — a deliberate,
  documented consequence of the bound (§8), not an oversight.
