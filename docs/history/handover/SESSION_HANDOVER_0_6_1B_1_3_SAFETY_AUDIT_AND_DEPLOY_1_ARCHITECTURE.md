# Session Handover — 0.6.1B.1.3 Safety Audit + DEPLOY.1 Architecture

Date: 2026-08-24
Source chat: SecurityExpert operational handover v2 (0.6.1B.1.2) → this session

## 1. What this session did

1. Read-only source audit of CP device interaction (no code changes) — see section 2.
2. Three approved, isolated safety fixes — see section 3. All verified with the
   real project pytest suite on the user's Windows environment.
3. Architecture design conversation for moving the platform onto a dedicated
   Ubuntu + Docker server, with GitHub/git migration as the top priority once
   the server exists — see section 4.
4. `project/roadmap.json` updated with a new `DEPLOY.1` upcoming item capturing
   the architecture decision (see roadmap.json diff, included in this package).

## 2. CP Device Interaction Safety Audit — findings (0.6.1B.1.3)

Files reviewed: `checkpoint/cp_runner.py`, `checkpoint/scripts/cp_inventory.sh`,
`checkpoint/direct_ssh_probe.py`, `checkpoint/vsx_runner.py`,
`configuration/checkpoint_config_probe.py`,
`configuration/checkpoint_config_collector.py`, `config.py`, `main.py`.

**Top finding:** in a full run, the same physical CP device is contacted by up
to 4 independent mechanisms with no shared coordination:
1. `cp_runner.py` — management-mediated via MDS `cprid_util rexec`
2. `direct_ssh_probe.py` — direct SSH fallback for failed/partial CPRID candidates
3. `vsx_runner.py` — nested SSH through MDS (least mature module; password sent
   as literal shell text; had no connect timeout)
4. `checkpoint_config_collector.py` — direct SSH, fleet-wide interactive Clish

Stages run sequentially (not threaded together) but back-to-back with no
cooldown between them — a plausible explanation for CURRENT_STATE.md's
"devices observed temporarily down around runs" note.

Other findings (not yet fixed, tracked for later):
- Standby ClusterXL members in `vsx_runner.py` still get a full login before
  the code learns they're standby and skips them — deferred to **0.6.1C
  Discovery Lifecycle**, since fixing it properly means sharing HA-role data
  from `cp_runner.py`'s cprid pass into `vsx_runner.py` (cross-module design,
  not a small patch).
- `vsx_runner.py` sends the device password as literal typed shell text inside
  a nested SSH session rather than through paramiko's standard auth — flagged,
  not fixed; fixing it means bypassing the MDS nested-SSH pattern entirely,
  an architectural change requiring explicit sign-off.

## 3. Fixes applied and verified this session

All three fixes are **bounded, additive, env-overridable, and do not change
host-key policy, worker counts, or identity-gate logic** (per CLAUDE.md
constraints). Modified files are included in `full_files/` in this package.

| # | File | Fix | Verification |
|---|------|-----|---------------|
| 1 | `checkpoint/vsx_runner.py` | `connect()` had no timeout at all; added bounded connect/banner/auth timeout, default 10s, env `FBUDDY_VSX_SSH_CONNECT_TIMEOUT_SECONDS` (2–60s) | User's pytest run: 20 passed, 1 xfailed (pre-existing, unrelated) |
| 2 | `configuration/checkpoint_config_probe.py` | `_connect()` (shared by both the probe and the fleet-wide collector) had zero connect-level retry; added 1 bounded retry with fixed backoff, **only** on reachability/timeout-class errors (`socket.timeout`, `TimeoutError`, `paramiko.SSHException`, `OSError`). Auth and host-key failures are never retried. Env: `SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_RETRIES` (0–2), `..._CONNECT_RETRY_BACKOFF_SECONDS` (1–10s) | User's pytest run: 12 passed |
| 3 | `checkpoint/direct_ssh_probe.py` | Same retry pattern as #2, applied to `_probe_one()`'s connect step. Env: `FBUDDY_CP_DIRECT_SSH_CONNECT_RETRIES`, `..._CONNECT_RETRY_BACKOFF_SECONDS` | User's pytest run (combined with #1/#2 scope): 41 passed, 1 xfailed |

Test commands used:
```powershell
python -m pytest tests/ -k vsx -v
python -m pytest tests/ -k "config_probe or config_collector or coverage_device" -v
python -m pytest tests/ -k "direct_ssh or vsx or config_probe or config_collector or coverage_device or evidence_probe" -v
```

## 4. DEPLOY.1 — architecture decision summary

Target: Ubuntu host + Docker Compose. Components (see architecture diagram
shared in chat):

- **Nginx** — TLS termination + IP allowlist now; LDAP/RBAC layer later. Never
  exposed to the internet.
- **App + scheduler** — persistent service replacing the current on-demand
  `main.py` invocation model. Must include a **per-device job lock** so
  scheduled jobs (inventory, config collection, and eventually deployment)
  never hit the same physical device concurrently — this directly extends the
  0.6.1B.1.3 audit finding above into the scheduled-execution world.
- **Postgres** (containerized) — metadata, job state, content-addressed
  evidence/fingerprints only. **Never** raw credentials.
- **Secrets vault** (separate component) — device credentials, **encrypted**
  (reversible), not hashed. Hashing was proposed and corrected during this
  session: a hash cannot be reversed to authenticate to a remote firewall, so
  credentials need encryption + a proper secrets manager, not a password-style
  hash.
- **Backup / policy-package store** — a separate volume from Postgres, per the
  project's own existing distinction that "configuration evidence is not a
  recovery backup." Versioned, ideally append-only/immutable.

Governance constraint carried forward from the project's own roadmap: write/
change automation to firewalls stays outside the roadmap gate until SEE,
VERIFY, TRACE and RECOVER are sufficiently mature. DEPLOY.1 is infrastructure
only — it must not introduce any firewall-write code path or UI affordance.

## 5. Git/GitHub migration plan (requested next priority once server exists)

1. **Repo host choice:** internal Bitbucket DC (already available per
   CURRENT_STATE.md) is preferred over public GitHub for this codebase, given
   it handles real firewall topology/config data even after redaction. If
   GitHub is used regardless, private repo only, with org-level SSO enforced
   if available.
2. **Before the first commit/push — mandatory secret scan:**
   - Search the full working tree for literal IPs, hostnames, usernames, and
     passwords in test fixtures, docs, and scripts (`grep -rniE` for IPv4
     patterns, known lab hostnames, etc.).
   - Confirm `.gitignore` excludes `data/`, `output/`, `logs/`, `*.env`, and
     any local credential files — the project's existing `.gitignore` should
     already cover most of this; verify it before first `git add .`.
   - Do not `git add -A` blindly on the first commit — review `git status`
     output file-by-file for the initial commit.
3. **First push:** `git init` → verify `.gitignore` → `git add` reviewed files
   → `git commit` → `git remote add origin <internal-repo-url>` → `git push`.
4. **Going forward:** point Claude Code (desktop or terminal) at the cloned
   repo instead of re-uploading zips to this chat interface — `CLAUDE.md`
   already at the repo root is read automatically by Claude Code.

## 6. New-chat starter convention (also filed to memory)

For any new chat picking up this project, open with:

> SecurityExpert projesi devam ediyor. Önce CLAUDE.md, CURRENT_STATE.md ve
> project/roadmap.json dosyalarını oku. [Konu/görev] üzerinde çalışacağız.

And check `/areas/securityexpert-project.md` in Claude's memory for the
latest status before assuming anything is missing.
