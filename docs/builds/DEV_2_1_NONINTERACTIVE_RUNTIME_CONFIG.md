# DEV.2.1 — Non-Interactive Runtime Configuration

**Status:** IMPLEMENTED — automated validation pending (pytest deps not installed
on the dev machine; local `resolve_value` + `_build_runtime_config` smoke green).
**Movement:** IMPLEMENTATION · **Track:** DEV.2 Server Runtime Foundation
**Backlog:** `noninteractive_runtime_config` (P0) · **Roadmap step:** DEV.2.1

Active build contracts live in `docs/builds/`; on build close this document moves
to `docs/history/phase/`.

---

## 1. Problem

`main.py::_build_runtime_config` is the only path that constructs a `Config`, and
it always calls `input()` (login, endpoints via `_prompt_management_endpoint`)
and `getpass.getpass()` (secret). An unattended process cannot answer a prompt;
under a non-TTY stdin `input()` raises `EOFError` deep in startup.

`--scheduler-once` does not escape this: `_run_scheduler_once` re-invokes `main()`
with `--only cp` / `--cp-config-collect`, which re-enters `_build_runtime_config`
and prompts again.

This blocks **every** unattended/container deployment (DEV.2.2, DEV.2.3, all of
DEV.3). It is the single P0 readiness item that can be done now, without the
server.

### Existing partial precedent

`configuration/checkpoint_config_collector.py:1621` and
`configuration/checkpoint_config_probe.py:845` already do
`os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal` for
the CP-config SSH path. DEV.2.1 generalizes the same idea to the primary
acquisition so `cfg.auth` and the endpoints can be populated without a TTY.

---

## 2. Frozen scope

### In

- Source `principal`, `secret`, the Check Point Management endpoint and the
  Panorama endpoint from, in per-value precedence order:
  1. `<VAR>_FILE` — path whose UTF-8 contents (trailing whitespace/newline
     trimmed) are the value. Docker / Kubernetes secret-mount convention.
  2. `<VAR>` — environment variable value.
  3. Interactive prompt — **only when `sys.stdin.isatty()` is true**.
- When stdin is not a TTY and a required value is still missing after (1)+(2):
  raise `RuntimeConfigError` immediately, before any collector import or network
  access, with a value-free message naming the missing variable(s).
- Keep the existing `require_cp` / `require_panorama` logic: only the endpoints
  needed for the selected `--only` mode are required; `principal` + `secret` are
  always required when collection is requested.
- New pure helper module `utils/runtime_config_source.py` (unit-testable without
  driving `main()`).
- `.env.example` documenting the variable names (documentation only — see Out).

### Variable names (public contract — compose files, secret mounts)

| Value | Variable | Secret-file variant |
| --- | --- | --- |
| Login / principal | `SECURITYEXPERT_PRINCIPAL` | `SECURITYEXPERT_PRINCIPAL_FILE` |
| Authentication secret | `SECURITYEXPERT_SECRET` | `SECURITYEXPERT_SECRET_FILE` |
| Check Point Management endpoint | `SECURITYEXPERT_CP_MDS_ENDPOINT` | `SECURITYEXPERT_CP_MDS_ENDPOINT_FILE` |
| Palo Alto Panorama endpoint | `SECURITYEXPERT_PANORAMA_ENDPOINT` | `SECURITYEXPERT_PANORAMA_ENDPOINT_FILE` |

`SECURITYEXPERT_PRINCIPAL` / `SECURITYEXPERT_SECRET` naming matches the existing
`RuntimeAuth(principal, secret)` boundary; the endpoint names align with the
existing `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY` / `SECURITYEXPERT_PAN_*` vars.

### Out

- **No `.env` auto-loading.** That needs `python-dotenv` (a dependency addition,
  which needs approval) or a hand-rolled parser (scope creep). Env injection is
  the container runtime's job (Compose `env_file:` / `environment:`,
  Kubernetes `envFrom`). `.env.example` is reference only; nothing reads it.
- No change to the downstream credential *content* path: `Config`, `RuntimeAuth`,
  `register_sensitive_value`, `clear_credentials`, and every collector are
  untouched.
- No change to `SECURITYEXPERT_CP_CONFIG_SSH_USERNAME/PASSWORD` — they remain a
  path-specific override layered on top of `cfg.auth` and continue to work.
- No collector, network, device-command, scheduler, concurrency, CAS, storage or
  UI change.
- No enterprise vault / secrets-manager client (that is `credential_profiles`,
  later).
- Endpoint values are still not registered for log redaction (unchanged
  behavior; endpoints are not secrets and appear in diagnostics today).

---

## 3. Contract detail

### `utils/runtime_config_source.py`

```python
class RuntimeConfigError(RuntimeError): ...

def resolve_value(name: str, *, environ: Mapping[str, str] = os.environ) -> str | None:
    """Return the resolved value for `name`, or None if neither <name>_FILE
    nor <name> is set. <name>_FILE wins; its file is read as UTF-8 and
    stripped of trailing whitespace/newlines. A set-but-unreadable/empty
    <name>_FILE raises RuntimeConfigError (fail closed — do not silently fall
    through to the plain var or a prompt)."""
```

### `main.py::_build_runtime_config` (rewritten)

- Build a small ordered plan: `[(env_name, label, required)]` where `required`
  reflects `require_cp` / `require_panorama` for the two endpoints and is always
  true for principal/secret.
- For each: `resolve_value(env_name)`; if `None` and required:
  - `sys.stdin.isatty()` → prompt exactly as today
    (`_prompt_management_endpoint` for endpoints, `input()` / `getpass` for
    principal / secret).
  - else → collect the name into a `missing` list.
- If `missing` is non-empty: raise
  `RuntimeConfigError("non-interactive runtime configuration incomplete: set "
  + ", ".join(f"{n} (or {n}_FILE)" for n in missing))`.
- `register_sensitive_value(principal, ...)` / `register_sensitive_value(secret,
  ...)` exactly as today, regardless of source.
- Construct `Config(...)` as today; null out the locals.
- `_prompt_management_endpoint` keeps its interactive loop for the TTY path.

`RuntimeConfigError` is raised before the lazy collector imports in `main()`, so
a misconfigured container fails fast with a clear message and no network/import
side effects.

---

## 4. Definition of Done

- Interactive local run (`py .\main.py`, real TTY) is byte-for-byte unchanged in
  behavior: same prompts, same order, same messages.
- `py -B main.py --only cp` with `SECURITYEXPERT_PRINCIPAL`,
  `SECURITYEXPERT_SECRET`, `SECURITYEXPERT_CP_MDS_ENDPOINT` set and stdin not a
  TTY reaches collector admission with **no prompt** (then fails at SSH connect —
  expected, proves the credential path is non-interactive).
- Same run with `SECURITYEXPERT_SECRET` unset and non-TTY exits with
  `RuntimeConfigError` naming `SECURITYEXPERT_SECRET (or SECURITYEXPERT_SECRET_FILE)`,
  before any collector import, with no traceback noise about `EOFError`.
- `*_FILE` variant reads and trims the file; `*_FILE` beats the plain var.
- No secret value in any log line or exception message.
- Full `pytest` baseline preserved (`227 passed / 2 xfail`, plus the new tests).
- `--repository-privacy-check` clean.

## 5. Tests — `tests/test_dev_2_1_noninteractive_runtime_config.py`

1. `resolve_value` returns `None` when neither var is set.
2. plain env var resolves.
3. `*_FILE` resolves and trims a trailing newline / spaces.
4. `*_FILE` takes precedence over the plain var.
5. `*_FILE` set to a missing/empty path raises `RuntimeConfigError` (fail closed).
6. `_build_runtime_config` with all four env vars + `isatty` monkeypatched
   `False` returns a `Config` with the expected `auth.principal`, `auth.secret`,
   `mds_ip`, `panorama_ip`, and never calls `input` / `getpass`.
7. `_build_runtime_config`, non-TTY, `SECURITYEXPERT_SECRET` unset → raises
   `RuntimeConfigError` whose message contains `SECURITYEXPERT_SECRET`.
8. `_build_runtime_config`, `require_panorama=False`, Panorama vars unset,
   non-TTY → succeeds (endpoint not required for the mode).
9. `_build_runtime_config` with `isatty` `True` and `input` / `getpass`
   monkeypatched → still prompts (interactive path unbroken).
10. principal and secret are passed to `register_sensitive_value` regardless of
    source (monkeypatch the logger and assert).

## 6. Validation

- **Automated only.** No new network behavior, no new device command, downstream
  credential content path unchanged.
- Optional local smoke (documented, not gated): set the three CP vars, run
  `py -B main.py --only cp --runtime-root <tmp>` with stdin redirected from
  `/dev/null`; confirm it prompts nothing and stops at the SSH layer.

## 7. Rollback

Single feature branch, ~2 source files + 1 test file + `.env.example`. Revert the
branch; the interactive path is untouched, so `main` is unaffected.

## 8. main.py / UI effect

None. No operator-visible change on an interactive run; no UI change. The only
new externally observable behavior is that an unattended run now starts (or fails
with a clear message) instead of hanging / `EOFError`.

## 9. Open decisions for human confirmation

1. **Variable names** (§2) — these become a public contract used by compose
   files and secret mounts. Confirm `SECURITYEXPERT_PRINCIPAL` /
   `SECURITYEXPERT_SECRET` / `SECURITYEXPERT_CP_MDS_ENDPOINT` /
   `SECURITYEXPERT_PANORAMA_ENDPOINT`.
2. **`SECURITYEXPERT_CP_CONFIG_SSH_USERNAME/PASSWORD`** — leave as-is (default),
   or also give them the `*_FILE` variant now for consistency (small, +2 lines
   in the resolver call sites). Recommendation: leave as-is; fold into a later
   cleanup so DEV.2.1 stays minimal.
3. **`.env` auto-loading** — confirm the deliberate boundary: not in scope,
   runtime injects env.
4. **Branch coordination** — the `noninteractive_runtime_config` backlog item and
   the `DEV.2.1` roadmap step live on `chore/deploy-containerization-roadmap`,
   not `main`. Either merge the two `chore/*` branches to `main` first, or this
   build's close-out state updates rebase onto them.

   Resolved: `feature/dev-2-1-noninteractive-config` was rebased onto
   `chore/deploy-containerization-roadmap`. Merge order to `main`:
   `chore/ai-onboarding-restructure` → `chore/deploy-containerization-roadmap`
   → `feature/dev-2-1-noninteractive-config`.

---

## 10. Implementation record

Landed on `feature/dev-2-1-noninteractive-config`:

- `utils/runtime_config_source.py` (new) — `RuntimeConfigError`,
  `resolve_value(name, *, environ=None)`: `<name>_FILE` (read+strip, fail closed
  on unreadable/empty) → `<name>` (strip) → `None`.
- `main.py` — `import sys`; import `RuntimeConfigError, resolve_value`;
  `_PRINCIPAL_VAR` / `_SECRET_VAR` / `_CP_MDS_ENDPOINT_VAR` /
  `_PANORAMA_ENDPOINT_VAR` constants; new `_resolve_or_prompt(...)` helper;
  `_build_runtime_config` rewritten around it (prompt only when
  `sys.stdin.isatty()`, else accumulate missing required vars and raise
  `RuntimeConfigError` naming all of them, before the lazy collector imports);
  new `_runtime_config(...)` closure in `main()` that maps `RuntimeConfigError`
  to `parser.error(...)` (clean `SystemExit(2)`, no traceback); the three
  `_build_runtime_config` call sites now go through it.
- `.env.example` (new) — documents the four `SECURITYEXPERT_*` vars and their
  `_FILE` variants; states nothing auto-loads it.
- `tests/test_dev_2_1_noninteractive_runtime_config.py` (new) — 12 tests.
- `tests/test_dev_0_1_runtime_endpoint_decoupling.py`,
  `tests/test_dev_0_5a_runtime_auth_boundary.py` — the four prompt-path tests now
  `_force_interactive(monkeypatch)` (set `sys.stdin.isatty()` true, clear the
  four vars) since the default under pytest is now non-interactive.

Local evidence (pytest unavailable — deps not installed):

Expected `py -m pytest -q`: prior baseline + 12 new.

- `resolve_value` smoke: `None`-when-unset, plain var, `*_FILE` read+strip,
  `*_FILE` beats plain var, unreadable/empty `*_FILE` → `RuntimeConfigError`
  (message names the var, never its content) — all pass.
- `_build_runtime_config` smoke (lxml/paramiko stubbed): env-only no prompts,
  secret-file precedence+trim, missing-required lists every var + value-free,
  endpoints-not-required-for-mode, interactive path still prompts, wrapper →
  `SystemExit(2)` with an argparse-style stderr line and no traceback — all pass.

Pending: `py -m pytest -q` (expected baseline + 13 new) and
`py -B main.py --repository-privacy-check` in a deps-installed environment.
