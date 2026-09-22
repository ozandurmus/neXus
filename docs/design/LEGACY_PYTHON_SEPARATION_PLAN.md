# Separating the legacy Python product from the neXus repository — plan

## Status

**DRAFT — awaiting Product Owner decision** (PO, 2026-09-23: "the old Python code was not useful to us;
maybe we separate it and split the repository. We now work for the neXus product, not SecurityExpert;
everything must serve it."). Nothing has been moved or deleted. Backlog `legacy_python_separation`.

## 1. What is in the repository today

| Group | What | Size (tracked files) | Serves neXus? |
|---|---|---|---|
| **neXus product** | `ui2/` (Java/Spring service, worker, CLI, React frontend, migrations), `deploy/` | ~1 100 | yes — this is the product |
| **Governance tooling (Python)** | `scripts/project_queue.py`, `scripts/repository_privacy_check.py` (the push gate), `scripts/gov_session_transfer.py`, `scripts/build_history_index.py`, `scripts/consult_*.py`, orchestrator scripts, `tests/test_architecture_convergence.py` | ~50 | yes — plan, privacy gate, consultation law |
| **Project state and law** | `AGENTS.md`, `CLAUDE.md`, `project/`, `CURRENT_STATE.md`, `docs/design/`, `docs/history/`, `relay/`, `roles/` | many | yes |
| **Legacy Python product** | `utils/`, `application/`, `checkpoint/`, `configuration/`, `console/`, `panorama/`, `replay/`, `signal_intake/`, `plugins/`, `migrations/`, `templates/`, `static/`, `main.py`, `config.py`, `Dockerfile`, `docker-compose*.yml`, `requirements*.txt`, most of `tests/` (≈195 files) | ≈350 | no — historical; UI2 replaced it |
| **Root clutter** | one-off `patch_*.py`, `precise_fix*.py`, `fix_*.py`, `_realenv_*.py`, `_write_r0x_policy.py`, `create_relays.py`, `Test*.java/.class`, `run-02xx.log`, `reply_0280.json`, `*.patch`, `SESSION_START*.json` | ≈40 | no |

## 2. What blocks a plain removal

1. **AGENTS.md names legacy modules as law.** "Network action taxonomy": `utils/action_taxonomy.py` is
   "the single source of truth for what the product may execute". "Architectural invariants":
   `utils/failover/` contents are enforced by `tests/test_architecture_convergence.py`. Removing the code
   without amending these makes the constitution point at nothing.
2. **The convergence test imports legacy code** (`utils.project_plan`, `utils.failover`), and
   `scripts/project_queue.py` depends on `utils/project_plan.py`.
3. **History.** Build records and design documents cite legacy paths; they stay valid only as history.

## 3. Proposed steps (each reversible; nothing leaves Git history)

1. **Root clutter** — delete the ≈40 one-off files in one commit. No reader, no test, no document depends
   on them (to be re-verified by grep at execution).
2. **Move the taxonomy into the product.** The Java `ActionClass` / action registry becomes the single
   source of truth; `AGENTS.md` "Network action taxonomy" and "Architectural invariants" are amended to
   name it (a PO amendment, like the 2026-09-19 and 2026-09-22 ones). The failover invariant is restated
   for UI2 (no class 2 action in `ActionRegistry`, test-enforced in `ui2/architecture-tests`).
3. **Keep the governance tooling** under `tools/governance/` (or `scripts/` unchanged), with the pieces of
   `utils/project_plan.py` it needs moved next to it.
4. **Archive the legacy product** into a new repository, `neXus-legacy-python`, **private**, with its full
   history (`git subtree split` / `git filter-repo` over the legacy paths), then remove those paths from
   neXus in one commit that names the archive.
5. **README, AI_START_HERE, CURRENT_STATE** describe neXus only; "SecurityExpert" remains only where a
   historical record quotes it.

## 4. Decisions needed from the Product Owner

| # | Question | Recommendation |
|---|---|---|
| 1 | Delete the root clutter now? | Yes — no dependency, pure noise. |
| 2 | Archive the legacy product to a separate repository, or only move it under `legacy/` here? | Separate **private** repository; neXus stays product-only. |
| 3 | Amend AGENTS.md so the Java action registry is the taxonomy's source of truth? | Yes — it already is in practice. |
| 4 | The neXus repository is **public** on GitHub today. Make it private? | Yes, before anything else — it holds a bank's internal design, host names in history and deployment details. This is a GitHub setting only the owner changes. |

## 5. Risk

Moving code breaks nothing in UI2 (it imports no Python). The risk is in governance tooling that reads
legacy helpers; step 3 exists for that, and the convergence test is re-run after each step.
