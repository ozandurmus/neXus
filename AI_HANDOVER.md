# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-09. `ui2_b0_baseline_directory` (`UI2 B0-8`) — new
  `docs/design/UI2_0_BASELINE_DIRECTORY.md` (DRAFT — FOR PRODUCT OWNER
  FREEZE), written under `UI2_0_BASELINE_CONTRACT.md` (FROZEN) and
  `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B0-8.
- Own worktree/branch (`feature/ui2-b0-baseline-directory`), standing
  `relay#13` merge authorization. PR #172 merged (commit `23df8f9d`).
- New: `docs/design/UI2_0_BASELINE_DIRECTORY.md` only. No `ui2/` source, no
  Line-1 code change, no device contact.
- **PO closure (same day, after PR #172/#173):** `NXS-LOCAL-0058` and
  `NXS-LOCAL-0059` both `CLOSED` by `RELAY_DECISION`; `ui2_b0_baseline_
  directory` and `ui2_b0_extraction_tooling` are `done` in
  `project/backlog.json`. **B0 is complete, 9/9.** The M3 mockup sources
  the baseline directory cites are now in the repository:
  `docs/design/ui2_mockups/*.dc.html` (+ `canvas.json`) and
  `docs/design/UI2_0_MOCKUP_REFERENCE_NOTES.md` (private IP literals in the
  mock data remapped to `192.0.2.0/24` for the DLP gate). The neXus logo
  files are NOT in the repository — the Product Owner holds them.

## 2. What changed

- A single index cataloguing owner document/section/version for the six
  B0-8 baseline concerns: navigation (§2, adopts `NAVIGATION_INFORMATION_
  ARCHITECTURE.md`'s frozen `D-NAV` principles + `C3`'s RBAC
  visible-but-refused mechanism, without carrying over Line-1's own
  six-root baseline as UI 2.0's root set); shared screen states (§3, the
  mockup's own eight-term vocabulary and severity rule adopted verbatim);
  feature-contribution contract (§4, new minimal text tying `C4`'s
  registry/`produces_facts` to `C2`'s `job_type`, gated on `CAP-RELEASED`);
  alarm lifecycle contract (§5, confirmed genuinely new — no existing
  ALARM/ALERT owner found — kept schema-level against `C1`'s audit/
  data-class pattern and `C2`'s job-state-machine/`OUTCOME_UNKNOWN`
  pattern); log/audit data classes (§6, cites `C1` §4 directly); SNMP
  status exposure (§7, restates the read-vs-poll/status-vs-trap-out
  distinction from workflow B2-5b).
- §8: 11 acceptance criteria for `B1-9`/`B2-1`/`B2-3`/`B2-5b`. §9: no
  contradiction with frozen authority found; three open items for the PO
  (UI 2.0's own root set not yet fixed; alarm severity enum left to
  `B2-3`; SNMP status exposure's exact `utils.action_taxonomy` framing
  left to `B2-5b`).
- Mid-session: the concurrent extraction-tooling movement (`ui2_b0_
  extraction_tooling`, PR #171) merged into `main` first. Merged
  `origin/main`, resolved a conflict in `project/backlog.json` and
  `project/build_history.json` (kept both movements' build records;
  `roadmap.json`/`CURRENT_STATE.md`/`docs/history/INDEX.md` auto-merged
  clean), re-ran the full regression and the privacy gate against the new
  base before merging.
- `project/backlog.json`, `project/build_history.json`,
  `project/roadmap.json`: active build advanced to this movement; `C7`
  (already PO-accepted, PR #170) moved from `now` to `upcoming`.
  `docs/history/INDEX.md` regenerated via `scripts/build_history_index.py`.
- `RELAY_NOTE` appended to `relay/NXS-LOCAL-0058-ui2-b0-baseline-directory.json`
  (self-check against `AC-1`..`AC-8`); relay now `AWAITING_PO`.

## 3. Exact next action

1. Product Owner review/freeze decision for `docs/design/UI2_0_BASELINE_
   DIRECTORY.md`, most naturally alongside the C1–C7 freeze decision it
   was sequenced to precede (`UI2_0_BASELINE_CONTRACT.md` §6: once the
   baseline directory and extraction tooling both merge, "the Product
   Owner can move to the actual freeze decision for C1-C7 as a whole, then
   to B1"). Both B0-8 items (this movement and extraction tooling) are now
   merged — B0 is complete.
2. §9's three open items (UI 2.0's own root set; alarm severity enum;
   SNMP status exposure's taxonomy framing) are each explicitly deferred
   to a later movement (`B1`/`B2`), not blocking this document's freeze.
3. No other movement is blocked by this one; `gov_po_2_implementation`
   remains `now_next.next`, untouched.
4. **Both freezes are MADE (2026-09-09, commit 2911661).** Two movements
   were dispatched and then HELD before producing anything (credit
   conservation): `NXS-LOCAL-0060` (taxonomy decision document, packet
   ready, tier medium) and `NXS-LOCAL-0061` (B1-1 skeleton contract,
   packet ready, tier low). Resume them first, from their relay files, in
   whichever tool has budget (§6). Direction: contracts before code.
5. **Queue after those**, in order: (a) `ui2_taxonomy_device_write_
   class_and_step_kind` (P0, HIGH SCRUTINY — a DECIDE-level review before
   any dispatch; it opens a device-write action class and a `C4` step kind;
   never fold it into routine dispatch); (b) B1 per
   `UI2_0_DEVELOPMENT_WORKFLOW.md` §5, starting at B1-1, FIRST-CAPABILITY =
   the CP inventory narrow subset; (c) `ui2_c5_followup_ladder_sweep`
   (small DOCS, any time). Standing dispatch rule: at most 2 concurrent
   movements; default tier `Fable 5.1, low effort` (credit conservation,
   PO 2026-09-09), `medium` only for freeze/security-boundary decisions.

## 4. Test delta

- No new test files (documentation-only movement).
- Full regression (`python -m pytest -q -n auto --dist worksteal`, against
  the final merged base): 3226 passed, 27 skipped, 2 failed — both
  pre-existing DLP-token-collision findings in
  `tests/test_dev_0_5b_auth_consumer_canonical_config.py`
  (`relay/NXS-LOCAL-0030-credential-profiles-reference-model.json`),
  confirmed unrelated and unchanged by this diff, matching the baseline
  `CURRENT_STATE.md` already documents.
- `git diff --check`: clean.
- Repository privacy gate against `origin/main` baseline
  (`--privacy-baseline-ref origin/main`): PASS, 0 new findings (6
  pre-existing, unchanged).
- CI `validate` gate on PR #172: pass (`full-regression` is by-design
  skipped on `pull_request`, per `.github/workflows/validation.yml`).

## 5. New risks

- None new. This is a documentation-only catalogue movement; it authorizes
  no implementation, no schema, and no device execution — the risks it
  surfaces are the three open items in §3 above, each already named as a
  later movement's own decision, not a defect in this one.

## 6. Continuing in another tool (Codex / Copilot Enterprise) if Claude credit runs out

Everything that carries authority is tool-neutral and in the repository;
only the *automation* around it is Claude-specific. Nothing below needs a
Claude session.

**Tool-neutral (use as-is):** `AGENTS.md` (Codex reads it natively; Copilot
via `.github/copilot-instructions.md` + `.github/instructions/*`),
`AI_START_HERE.md` reading order and SESSION START/CLOSE schemas,
`docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` packets
(`scripts/gov_session_transfer.py render|validate`), the relay files under
`relay/` and `scripts/local_relay.py` (plain Python, any agent can `append
--role engineer`), `project/*.json`, the test suite, the DLP gate
(`main.py --repository-privacy-check --privacy-baseline-ref origin/main`),
and every `docs/design/UI2_0_*` document.

**Claude-specific (do not expect elsewhere):** `scripts/orchestrator.py`
(spawns `claude -p` headless engineers in worktrees), `.claude/*`
settings/skills (`nexus-po`, `nexus-decision-council`, engineer tool gate),
`CLAUDE.md`, and the "standing relay#13 self-merge" authorization as granted
inside Claude sessions. `scripts/orchestrator.py status` output is stale
history in that case — the relay file is the truth.

**Procedure per movement without Claude:**

1. PO picks the backlog id, writes the SESSION_START packet (same content
   the last nine `NXS-LOCAL-0051..0059` packets used: objective,
   `baseline.authority`, scope in/out, invariants, acceptance criteria,
   `movement_type`, `deployment_direction: local validation only`),
   validates it with `scripts/gov_session_transfer.py`, and creates the
   relay: `python scripts/local_relay.py create --start <packet.json>
   --role po --slug <slug>`.
2. Paste the packet into the Codex / Copilot agent chat with the
   instruction to follow `AI_START_HERE.md`, work on
   `feature/<slug>`, append progress via `scripts/local_relay.py append
   --role engineer --marker RELAY_NOTE ...`, and end with a SESSION_CLOSE
   packet. State the merge authorization explicitly in the packet (green
   tests + clean `git diff --check` + DLP gate 0 new findings → open PR;
   say whether self-merge is allowed) — the Claude-era standing relay#13
   authorization does not carry over by itself.
3. PO reviews the self-check, closes with `scripts/local_relay.py append
   --role po --marker RELAY_DECISION --close ...`, flips the backlog item,
   commits. Keep the 2-concurrent limit by hand.
4. On macOS the interpreter is `.venv/bin/python` (the Copilot delta's `py`
   is the Windows profile). Never bootstrap an environment; report a real
   failure and stop.

When Claude credit returns, `scripts/orchestrator.py` resumes from the same
relay files; nothing needs to be migrated back.
