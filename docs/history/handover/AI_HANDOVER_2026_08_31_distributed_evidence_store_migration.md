# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline: `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- Engineering baseline: `DEV.1` complete; `DEV.2.1`, `DEV.2.2`, `DEV.3.1`,
  `DEV.3.2` all AUTOMATED_VALIDATED; **`DEV.3.3`
  (`distributed_evidence_store_migration`) AUTOMATED_VALIDATED this session.**
- Branch: `claude/unmerged-github-branches-bundao`, based on `origin/main` at
  `7124ee4`. Carries two predecessor builds from earlier in this branch's life
  (`html_render_optimization`, `render_harness_happydom_pin`) plus this
  session's four DEV.3.3 commits.
- Full suite: **788 passed / 3 skipped / 2 failed** with a live PostgreSQL
  available; **763 / 11 / 2** without one. The 2 failures are the documented
  pre-existing unrelated ones — zero regressions.
- Repository privacy gate: **PASS / 0**.
- **Toolchain note (unchanged from the previous session):** this sandbox has no
  preinstalled Python matching the repo baseline. Recreate with
  `python3 -m venv /tmp/venv && /tmp/venv/bin/pip install -q -r requirements.txt pytest`,
  plus `-r requirements-postgres.txt` for the Postgres-backed tests.
- **A real PostgreSQL 16 was run in-sandbox this session**, which is what made
  the DEV.3.3 Postgres tests real rather than skipped:
  `su postgres -c "/usr/lib/postgresql/16/bin/initdb -D /tmp/pgdata -U securityexpert --auth=trust"`,
  then `pg_ctl -D /tmp/pgdata -o '-p 5432 -k /tmp/pgrun -c listen_addresses=127.0.0.1' start`,
  then `CREATE DATABASE securityexpert_test`. Tests read
  `SECURITYEXPERT_TEST_POSTGRES_DSN` and `skipif` it is absent. It does not
  survive between sessions.

## 2. What this session did

**`distributed_evidence_store_migration` (DEV.3.3)** — the evidence-integrity
half split out of DEV.3.2. One build, contract-first, four commits:

1. **Contract** (`docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md`)
   — audit of the four stores, design decisions D1–D7, and one open
   product-owner decision.
2. **Contract freeze** — E1 answered by the product owner (**Option 1, full
   identity fidelity**); `PRIVACY_AND_DATA_HANDLING.md` gains a "Distributed
   evidence store" section naming the Postgres instance as a CLASS 2
   identity-bearing asset with its operational requirements.
3. **Implementation** — `utils/evidence_backend.py` (four backends ×
   filesystem/Postgres), the five in-scope modules routed through it,
   `main.py` startup preflight, `.env.example` + `requirements-postgres.txt`.
4. **Tests** — 17 new tests against a real PostgreSQL 16, plus the A9 fix that
   the concurrency test uncovered.

Default behavior is unchanged: `SECURITYEXPERT_EVIDENCE_BACKEND` unset means
the same per-container files as before, and the Postgres driver is never
imported. Content-addressed payload blobs never move.

### Contract amendments A1–A9 — read these before touching the design

Recorded in the phase doc rather than silently absorbed. Two are substantive:

- **A1** — per-entity rows alone do *not* fix the lost-update race between
  containers; the caller's load-mutate-save-whole-map pattern reproduces it
  against the table. `build_failure_aware_snapshot` therefore reads/writes
  each entity individually, and `LastKnownGoodBackend.commit()` exists so the
  filesystem backend can keep its single whole-file write per run.
- **A9** — `CREATE TABLE IF NOT EXISTS` does not serialize against a
  concurrent identical `CREATE`; two containers starting together could crash
  one. Schema creation now runs under a transaction-level advisory lock. Found
  only because AC-3 demanded real subprocesses.

The rest: A2/A5 (backends are dumb primitives, all validation stays with the
callers — also a circular-import constraint), A3 ("latest" orders by
`snapshot_id`, not `collected_at`), A4 (`metadata_json` is the only read path;
promoted columns are for ops SQL only), A6 (`SnapshotResult.directory` has no
Postgres equivalent — synthetic pointer), A7 (idempotent snapshot insert), A8
(`compliance_trend_reconstruction` was a sixth affected module the contract's
list missed).

## 3. Next work

- **`RB.3` (CP Gaia backup)** — still blocked on `D3` alone (product-owner
  decision on the `operational-write` command class) plus that command's own
  gate review. The P0 CP audit is **closed** — do not re-cite it as open.
- **`RB.5` (Recovery UI module)** — next natural RECOVER step; contract frozen
  in `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §6. Touches `templates/`/
  `app.js` → **mandatory render-harness run** + `tests/fixtures/uitest/`.
- **PAN `RB.2` configuration-XML export** (contract §7.2) — not yet built.
- **CE.2** (`compliance_check_engine_primitives`) — needs its own contract.
- **DEV.3.3 follow-ups**, none blocking: a one-shot importer for existing
  filesystem history into Postgres (deliberately out of scope here), and the
  multi-container real-environment evidence below.

## 3b. NOT YET DONE — real-environment validation

- **`DEV.3.3`** — no multi-container run. Owed before `DONE`: last-known-good
  state for a fleet split across two containers must match what a
  single-container run over the same fleet produces. Everything so far is
  single-host, albeit against a real PostgreSQL with real subprocesses.
- **`DEV.3.2`** — real Postgres evidence exists, but not an actual
  multi-container deployment against a real MDS.
- **PAN device-state export (`RB.2`)** — implemented, never run against a real
  firewall.
- Everything under `on_hardware_real_env_validation` in `project/backlog.json`.

## 4. Open risks / debt carried forward

- **Mixed-backend fleet** — containers running different
  `SECURITYEXPERT_EVIDENCE_BACKEND` values silently fragment evidence across
  two stores with no error. Documented loudly in `.env.example`; it is an
  operator-configuration invariant, not something the code can detect.
- **The two knobs do not imply each other** — evidence-backend Postgres gives
  every container the same scheduler *state*, but only DEV.3.2's coordinator
  backend gives mutual *exclusion* over it. A multi-container deployment that
  schedules collection wants both.
- **No backfill** — switching an existing fleet to the Postgres backend makes
  old filesystem history invisible to the new reader (not deleted).
- `D1`/`D3`/`D4`/`D5`/`D6`/`D7` (backup architecture §13) still open.
- PAN `software_version` unresolved; limits `RB.4` V3 usefulness for PAN.
- `DEV.3.2` lock-key stability depends on `data/.support_hmac.key` persisting.
- The regex safety linter is best-effort; the eval-time timeout is the backstop.
- A test run writes gitignored `data/` + `logs/` into the repo dir — delete
  before running the privacy gate.

## 5. Exact next action

DEV.3.3 is complete and pushed on
`claude/unmerged-github-branches-bundao`. **No PR has been opened** — that is
the user's call, as is any merge to `main`.

**Fresh chat recommended.** The next objective (`RB.3`/`D3`, `RB.5`, CE.2) is
distinct and deserves its own contract and clean context.

## 6. main merge decision + Git dispatch

- Branch `claude/unmerged-github-branches-bundao` is pushed and current.
- **Merge to `main`: blocked pending the user's decision** — this repo's
  convention is human-controlled push/merge, and DEV.3.3's multi-container
  real-environment evidence is still owed (though `AUTOMATED_VALIDATED` does
  not require it, `DONE` does).
- Dispatch if a PR is wanted:
  `gh pr create --base main --head claude/unmerged-github-branches-bundao`
  (or the GitHub MCP equivalent in a Claude session).

## 7. Next movement / model

- `RB.3` (if `D3` is answered): `IMPLEMENTATION` at **Sonnet 5, normal**.
- `RB.5`: `IMPLEMENTATION` at **Sonnet 5, normal** — contract already frozen.
- CE.2 contract: **Sonnet 5, extended thinking** only for the contract itself
  (new check primitives touch the device-command gate); normal for the build.
- A DEV.3.3 filesystem→Postgres importer, if wanted: **Sonnet 5, normal** —
  the schema and backends already exist.

## 8. Continue or fresh chat

**Fresh chat.** This session opened, froze and implemented one complete build;
the next objective is unrelated.

## 9. main.py / UI effect

- **No UI change.** DEV.3.3 is backend-only: a normal `py .\\main.py`
  checkpoint run looks and behaves exactly as before, and
  `templates/index.html` / `static/app.js` / `static/style.css` were not
  touched.
- The only visible difference on the default backend is none at all. With
  `SECURITYEXPERT_EVIDENCE_BACKEND=postgres` set, startup prints one extra
  line (`>>> EVIDENCE BACKEND: postgres (DEV.3.3)`), and a misconfigured
  backend stops with a clean `parser.error` instead of a traceback.
