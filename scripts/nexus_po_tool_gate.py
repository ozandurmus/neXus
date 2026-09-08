#!/usr/bin/env python3
"""GOV.PO.1 PreToolUse gate for the Product Owner assistant role (stdlib only).

Usage (as a Claude Code PreToolUse hook):
    python3 scripts/nexus_po_tool_gate.py --form delegated
    python3 scripts/nexus_po_tool_gate.py --form interactive

Reads the hook JSON on stdin, decides allow/deny, prints the documented
`hookSpecificOutput` JSON and exits 0 (allow) or 2 (deny). Every decision is
appended as one JSON line to the log named by $NEXUS_PO_HOOK_LOG (default:
<system temp dir>/nexus_po_hook.log) -- never inside the repository. The log
carries tool name, the first 200 characters of the command/path, the
decision and reason, plus session/agent ids the harness supplies; it never
carries file contents.

Forms (docs/design/GOV_PO_ROLE_MIGRATION.md section 4 and 6.2):
  delegated   -- Phase B subagent: read/search tools, `gh issue view/list`,
                 `gh issue comment` limited to the relay markers, `gh pr
                 view/checks`, read-only git, the session-transfer script.
                 No Edit/Write/NotebookEdit, no Agent, no Git write, no
                 collection, no shell redirection.
  interactive -- Phase A session: the delegated set plus Edit/Write on the
                 governance paths, the `relay/*.json` local relay files
                 (GOV_PO_1_LOCAL_RELAY_PROTOCOL, 2026-09-08 -- see below),
                 or the nexus_po_* scratch pattern, `gh
                 issue create`, `gh issue close` (any inline `--comment`
                 must carry one of the five relay markers, exactly like
                 `gh issue comment`; a close with no comment is allowed
                 unconditionally -- GOV_PO_1_GATE_4, 2026-09-08), Git
                 branch/commit/push/PR only on a `gov/po-*` branch,
                 `git merge origin/<ref>` to sync
                 that branch with a fix landed on `origin/main` or another
                 `origin/*` ref (merge source restricted to `origin/`;
                 no arbitrary remote or URL is ever reachable, since
                 `git remote add` is not allowlisted), and exactly one
                 named Agent exception: a `nexus-council-seat` subagent
                 launch (GOV_PO_1_GATE_3, 2026-09-08) -- every other Agent
                 spawn, in either form, stays denied.

Local relay transport (GOV_PO_1_LOCAL_RELAY_PROTOCOL, 2026-09-08;
docs/design/LOCAL_RELAY_PROTOCOL.md, DRAFT): an additional, same-machine
transport alongside the GitHub-based relay, not a replacement for it.
`relay/*.json` is a new Edit/Write pattern, interactive form only, alongside
-- not weakening -- the existing exact-match `GOVERNANCE_PATHS` list.
`scripts/local_relay.py status`/`validate`/`watch` (read-only) are
allowlisted in both forms, mirroring `gov_session_transfer.py`'s own
treatment -- `watch` (GOV_PO_1_LOCAL_RELAY_WATCH_COMMAND, 2026-09-08) is a
bounded, blocking poll, never a background daemon, and it never writes to
the file it watches. `create`/`append` (the only subcommands that write a
`relay/*.json` file) are interactive-form only, mirroring the existing
`gh issue create` treatment. Delegated-form write access to relay files is
unchanged: still denied.

Worktree management (GOV_PO_1_GATE_5, 2026-09-08): `git worktree add` is
allowed in interactive form only, mirroring the `git checkout -b gov/po-`
write-action precedent (delegated form stays denied via the ordinary
"command prefix not in the PO allowlist" path -- no special-case needed).
Two argument-shape checks apply on top of the prefix match, modeled on
already-shipped restrictions: the base commit-ish (`git worktree add <path>
<commit-ish>`) must start with `origin/`, exactly mirroring the `git merge
origin/<ref>` restriction, so no arbitrary local path, remote, or URL is
ever reachable as a worktree base; and when `-b`/`-B <branch>` is present,
`<branch>` must start with `feature/` -- `gov/po-` stays reserved for the
PO's own governance branch, not for worktrees handed to an engineer. A
worktree add with no `-b`/`-B` (attaching an existing branch) skips the
branch-prefix check. `git worktree list` is read-only and allowed in both
forms, mirroring `git branch --list`. `git worktree remove`/`prune`/`move`
are deliberately never allowlisted -- a worktree, once created, is never
destroyed by the PO role in this movement's scope.

Self-sync capability (GOV_PO_1_GATE_2 correction, 2026-09-08): a fix landed
on `origin/main` (or pushed directly to a `gov/po-*` branch, as happened for
this episode) previously had no way to reach a PO episode's own already-
checked-out working tree -- `git fetch` updates the remote-tracking ref,
never the working tree, and `git merge`/`git pull` were not allowlisted, so
neither the agent nor a human relaying its exact commands could self-serve
the sync; it required a separate engineer session touching the shared
directory by hand. `git merge origin/<ref>` is now allowed, restricted to
the `origin/` remote-tracking namespace so no arbitrary remote or URL is
reachable (`git remote add` stays unavailable).

Orchestrator dispatch (GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN,
section 7): `python3 scripts/orchestrator.py status` (read-only, both forms,
mirroring `local_relay.py status`/`validate`) and, interactive form only,
`... start`/`... stop` (spawning/cancelling a detached engineer process is a
write-ish action, matching the `local_relay.py create`/`append` precedent).
Prefix matching for every `COMMON_PREFIXES`/`INTERACTIVE_EXTRA_PREFIXES`
entry is now boundary-safe (the matched prefix must be followed by a space
or the end of the command string, never merely `cmd.startswith(p)`
unbounded) -- the fix GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md
section 3.1 already required for its own three new prefixes, applied here
across the whole allowlist because these three new prefixes need it and a
second, differently-scoped fix would leave the exact defect open for
whichever prefixes it didn't cover (e.g. a bare `"ls"` prefix previously
also matched `"lsof ..."` under plain `startswith`).

Command-safety design (GOV_PO_1_GATE_1 correction, 2026-09-08): the original
implementation banned the literal substrings "<", ">", ";", "|" etc.
anywhere in the command text. That blocked entirely legitimate quoted data
-- most visibly a `git commit` trailer's `<email>` -- because bash does not
treat those characters as operators when they are embedded in a quoted
argument (single OR double quotes both suppress redirection/pipe/chaining
metacharacters; only `$(` and a backtick keep executing even inside double
quotes, so those two stay banned unconditionally). The fix tokenizes the
command with `shlex.shlex(..., punctuation_chars=True)`, which reproduces
that exact bash distinction: a shell metacharacter embedded in a quoted
token stays part of that one data token, while a real, unquoted operator
becomes its own token regardless of surrounding whitespace (`a>b` and
`a > b` both yield a standalone `>` token). Malformed/unbalanced quoting
fails closed (denied), never silently ignored. A handful of destructive
program names (`rm`, `mv`, `cp`, `sudo`, `chmod`, `curl`, `wget`, `ssh`) and
`main.py` are still denied, now as exact tokens rather than substrings, so
prose mentioning them (a commit message documenting `main.py`, or "removed
the sudo requirement") no longer false-positives; this also denies
`find ... -exec rm ...`, which tokenizes `rm` as its own bare word even
though `find` itself is an allowed prefix. A raw newline byte in the
command is denied outright: it is bash's other statement separator and
Claude Code's own request payload never legitimately needs one (a
multi-line git commit message is built with repeated `-m` flags, each one
one quoted argument).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
from fnmatch import fnmatch

READ_TOOLS = {"Read", "Grep", "Glob", "LS", "ToolSearch", "WebFetch", "WebSearch", "TodoWrite"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}

GOVERNANCE_PATHS = (
    "docs/design/PRODUCT_DIRECTION_RECORD.md",
    "project/roadmap.json",
    "project/backlog.json",
    "project/feature_registry.json",
    "project/build_history.json",
    "docs/history/INDEX.md",
    "CURRENT_STATE.md",
    "AI_HANDOVER.md",
)

# GOV_PO_1_LOCAL_RELAY_PROTOCOL, 2026-09-08 (docs/design/LOCAL_RELAY_PROTOCOL.md,
# DRAFT): a new pattern alongside GOVERNANCE_PATHS above, not a widening of
# it -- the eight exact-match paths there are unchanged. Flat directory only
# (no subdirectories), matching scripts/local_relay.py's own filename
# contract (relay/NXS-LOCAL-<id>-<slug>.json).
LOCAL_RELAY_FILE_PATTERN = "relay/*.json"

# Transient packet-staging scratch pattern (GOV.PO.1 section 4 item (c): "drafted
# SESSION_START packets rendered by scripts/gov_session_transfer.py"). Interactive
# form only; never a permanent artifact, never inside the repository, never
# referenced by product code. Naming is deliberately narrow and auditable.
SCRATCH_NAME_RE = re.compile(r"^nexus_po_[A-Za-z0-9_.-]+\.(?:json|txt)$")

RELAY_MARKERS = ("RELAY_ACK", "RELAY_NOTE", "RELAY_QUESTION", "RELAY_DECISION", "RELAY_CORRECTION")

# Command prefixes allowed in both forms. Matching is on the normalized
# leading tokens of the command; real shell operators and a small set of
# destructive bare program names are rejected as tokens (see module
# docstring) so an allowed prefix cannot smuggle a second command.
COMMON_PREFIXES = (
    "gh issue view", "gh issue list", "gh issue comment",
    "gh pr view", "gh pr checks", "gh pr list",
    "git status", "git log", "git diff", "git show", "git rev-parse",
    "git fetch", "git branch --show-current", "git branch --list",
    "git merge-base", "git cat-file", "git ls-files",
    "git worktree list",
    "cat ", "head ", "tail ", "sed -n", "grep ", "rg ", "ls", "wc ", "find ",
    "python3 scripts/gov_session_transfer.py", "python scripts/gov_session_transfer.py",
    "py scripts/gov_session_transfer.py", ".venv/bin/python scripts/gov_session_transfer.py",
    "python3 scripts/build_history_index.py --check", "py scripts/build_history_index.py --check",
    ".venv/bin/python scripts/build_history_index.py --check",
    "python3 -m pytest", "py -m pytest", ".venv/bin/python -m pytest",
    "python3 scripts/local_relay.py status", "python scripts/local_relay.py status",
    "py scripts/local_relay.py status", ".venv/bin/python scripts/local_relay.py status",
    "python3 scripts/local_relay.py validate", "python scripts/local_relay.py validate",
    "py scripts/local_relay.py validate", ".venv/bin/python scripts/local_relay.py validate",
    "python3 scripts/local_relay.py watch", "python scripts/local_relay.py watch",
    "py scripts/local_relay.py watch", ".venv/bin/python scripts/local_relay.py watch",
    "python3 scripts/orchestrator.py status", "python scripts/orchestrator.py status",
    "py scripts/orchestrator.py status", ".venv/bin/python scripts/orchestrator.py status",
)
INTERACTIVE_EXTRA_PREFIXES = (
    "gh issue create", "gh issue close", "gh pr create",
    "git checkout -b gov/po-", "git switch -c gov/po-",
    "git add ", "git commit", "git push",
    "git merge origin/", "git merge --ff-only origin/",
    "git worktree add",
    "python3 scripts/build_history_index.py", "py scripts/build_history_index.py",
    ".venv/bin/python scripts/build_history_index.py",
    "python3 scripts/local_relay.py create", "python scripts/local_relay.py create",
    "py scripts/local_relay.py create", ".venv/bin/python scripts/local_relay.py create",
    "python3 scripts/local_relay.py append", "python scripts/local_relay.py append",
    "py scripts/local_relay.py append", ".venv/bin/python scripts/local_relay.py append",
    "python3 scripts/orchestrator.py start", "python scripts/orchestrator.py start",
    "py scripts/orchestrator.py start", ".venv/bin/python scripts/orchestrator.py start",
    "python3 scripts/orchestrator.py stop", "python scripts/orchestrator.py stop",
    "py scripts/orchestrator.py stop", ".venv/bin/python scripts/orchestrator.py stop",
)

# Always denied regardless of quoting: both keep executing even inside a
# double-quoted argument, so "it was inside quotes" does not make them safe.
ABSOLUTE_FORBIDDEN_FRAGMENTS = ("`", "$(")

# Real, unquoted shell operators once tokenized (punctuation_chars=True
# yields these as standalone tokens; see module docstring).
SHELL_OPERATOR_TOKENS = {
    ">", ">>", "<", "<<", ";", "|", "||", "&", "&&", "(", ")",
}

# Destructive/escaping program names, denied as exact bare tokens (not
# substrings) so they cannot be invoked directly or via a flag such as
# `find ... -exec rm ...`, while prose mentioning them in a commit message
# or JSON payload is no longer a false positive.
DANGEROUS_BARE_WORDS = {"rm", "mv", "cp", "sudo", "chmod", "curl", "wget", "ssh"}


def _log(entry: dict) -> None:
    path = os.environ.get("NEXUS_PO_HOOK_LOG") or os.path.join(tempfile.gettempdir(), "nexus_po_hook.log")
    try:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=True) + "\n")
    except OSError:
        pass


def _current_branch(cwd: str | None) -> str:
    try:
        out = subprocess.run(["git", "branch", "--show-current"], cwd=cwd or None,
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def _repo_relative(path: str, cwd: str | None) -> str | None:
    """Normalize `path` to a repository-relative, `/`-separated form for
    fnmatch comparison against a path-pattern list. Returns None when an
    absolute path cannot be made relative to `cwd` -- callers treat that as
    "does not match any pattern", never as a free pass."""
    if not path:
        return None
    rel = path
    if cwd and os.path.isabs(path):
        try:
            rel = os.path.relpath(path, cwd)
        except ValueError:
            return None
    rel = rel.replace(os.sep, "/")
    if rel.startswith("./"):
        rel = rel[2:]
    return rel


def _path_is_governance(path: str, cwd: str | None) -> bool:
    rel = _repo_relative(path, cwd)
    return rel is not None and any(fnmatch(rel, pat) for pat in GOVERNANCE_PATHS)


def _path_is_local_relay_file(path: str, cwd: str | None) -> bool:
    rel = _repo_relative(path, cwd)
    if rel is None:
        return False
    # fnmatch's "*" matches "/" too (it has no path-segment concept), so
    # "relay/*.json" alone would also match "relay/sub/x.json" -- the
    # explicit single-"/" count is what actually keeps this flat.
    return rel.count("/") == 1 and fnmatch(rel, LOCAL_RELAY_FILE_PATTERN)


def _scratch_roots() -> list[str]:
    """Every directory a bare "/tmp/..." path or Python's own tempfile
    module could mean on this machine, realpath-resolved (macOS's `/tmp`
    is a symlink to `/private/tmp`, distinct from `tempfile.gettempdir()`'s
    per-user `$TMPDIR`; a model composing a Bash command reaches for the
    conventional `/tmp`, not the Python-specific value)."""
    roots = set()
    try:
        roots.add(os.path.realpath(tempfile.gettempdir()))
    except OSError:
        pass
    for conventional in ("/tmp", "/var/tmp"):
        if os.path.isdir(conventional):
            try:
                roots.add(os.path.realpath(conventional))
            except OSError:
                pass
    return sorted(roots)


def _path_is_po_scratch(path: str, cwd: str | None) -> bool:
    """One ephemeral file directly under a recognized system temp root,
    matching `nexus_po_*.json` / `nexus_po_*.txt`. Never inside the
    repository."""
    if not path:
        return False
    p = path if os.path.isabs(path) else os.path.join(cwd or os.getcwd(), path)
    try:
        p = os.path.realpath(p)
    except OSError:
        return False
    if not any(p == root or p.startswith(root + os.sep) for root in _scratch_roots()):
        return False
    return bool(SCRATCH_NAME_RE.match(os.path.basename(p)))


def _prefix_matches(cmd: str, prefix: str) -> bool:
    """Boundary-safe prefix match (GOV_PO_2 section 3.1's fix, applied
    across the whole allowlist -- see module docstring): `cmd` must equal
    `prefix` exactly, or `prefix` must be followed by a space in `cmd`.
    A prefix already ending in a natural token boundary -- a space (e.g.
    "cat ") or a path separator (e.g. "git merge origin/", deliberately
    meant to glue directly onto a following ref name with no space) --
    needs no further check; adding one would wrongly demand a second
    delimiter right where the prefix already intends none."""
    if not cmd.startswith(prefix):
        return False
    if prefix.endswith(" ") or prefix.endswith("/"):
        return True
    return len(cmd) == len(prefix) or cmd[len(prefix)] == " "


def _tokenize(cmd: str) -> list[str] | None:
    """Shell-aware tokenization distinguishing a real operator from the
    same character embedded in a quoted argument. Returns None on
    unbalanced/malformed quoting -- callers must fail closed on that."""
    try:
        lexer = shlex.shlex(cmd, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        return list(lexer)
    except ValueError:
        return None


# `git worktree add [-f] [--detach] [--checkout] [--lock [--reason <s>]]
#  [--orphan] [(-b | -B) <new-branch>] <path> [<commit-ish>]` (git-worktree(1)).
# Boolean flags take no argument; `--reason` and `-b`/`-B` each consume the
# following token. Whatever tokens remain, in order, are the positionals:
# the first is <path>, the second (if present) is the base <commit-ish>.
_WORKTREE_ADD_BOOLEAN_FLAGS = {
    "-f", "--force", "--no-force", "--detach", "--no-detach",
    "--checkout", "--no-checkout", "--lock", "--no-lock",
    "--orphan", "--no-orphan", "-q", "--quiet", "--no-quiet",
    "--track", "--no-track", "--guess-remote", "--no-guess-remote",
    "--relative-paths", "--no-relative-paths",
}
_WORKTREE_ADD_VALUE_FLAGS = {"--reason"}
_WORKTREE_ADD_BRANCH_FLAGS = {"-b", "-B"}


def _check_worktree_add(tokens: list[str]) -> tuple[bool, str]:
    """AC-2/AC-3: restrict `git worktree add`'s base ref to origin/<ref> and
    any `-b`/`-B` branch name to feature/*. `tokens` is the full tokenized
    command (leading "git worktree add" included)."""
    rest = tokens[3:]
    branch_name = None
    positionals: list[str] = []
    i = 0
    while i < len(rest):
        tok = rest[i]
        if tok in _WORKTREE_ADD_BRANCH_FLAGS:
            if i + 1 >= len(rest):
                return False, f"git worktree add {tok} requires a branch name"
            branch_name = rest[i + 1]
            i += 2
            continue
        if tok in _WORKTREE_ADD_VALUE_FLAGS:
            i += 2
            continue
        if tok in _WORKTREE_ADD_BOOLEAN_FLAGS:
            i += 1
            continue
        positionals.append(tok)
        i += 1
    if not positionals:
        return False, "git worktree add requires a path"
    base_ref = positionals[1] if len(positionals) > 1 else None
    if not base_ref or not base_ref.startswith("origin/"):
        return False, "git worktree add's base commit-ish must start with origin/ (mirrors the git merge origin/<ref> restriction)"
    if branch_name is not None and not branch_name.startswith("feature/"):
        return False, "git worktree add -b/-B branch name must start with feature/ (gov/po- is reserved for the PO's own branch)"
    return True, "worktree add within restrictions"


def decide(payload: dict, form: str, branch_lookup=_current_branch) -> tuple[bool, str]:
    """Return (allowed, reason). Pure except for the branch lookup callable."""
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd")

    if tool in READ_TOOLS:
        return True, "read tool"
    if tool == "Agent":
        if form == "interactive" and tool_input.get("subagent_type") == "nexus-council-seat":
            return True, "nexus-decision-council seat launch (GOV_PO_ROLE_MIGRATION.md section 7/8)"
        return False, "the PO role does not spawn agents except council seats from the nexus-po skill; use the council skill's documented path"
    if tool in EDIT_TOOLS:
        if form != "interactive":
            return False, "delegated PO episodes never edit files"
        target = str(tool_input.get("file_path") or tool_input.get("notebook_path") or "")
        if _path_is_governance(target, cwd):
            return True, "governance path"
        if _path_is_local_relay_file(target, cwd):
            return True, "local relay file (relay/*.json, GOV_PO_1_LOCAL_RELAY_PROTOCOL)"
        if _path_is_po_scratch(target, cwd):
            return True, "scratch path (transient packet staging, GOV.PO.1 section 4 item c)"
        return False, "interactive PO edits are limited to the governance paths, relay/*.json, and the nexus_po_*.json/.txt scratch pattern in GOV.PO.1 section 4"
    if tool == "Bash":
        raw = str(tool_input.get("command", ""))
        if "\n" in raw or "\r" in raw:
            return False, "a raw newline in the command is never allowed (bash treats it as a statement separator; build a multi-line git commit message with repeated -m flags instead)"
        cmd = " ".join(raw.split())
        if not cmd:
            return False, "empty command"
        for frag in ABSOLUTE_FORBIDDEN_FRAGMENTS:
            if frag in cmd:
                return False, f"forbidden shell fragment {frag!r} (executes even inside double quotes)"
        tokens = _tokenize(cmd)
        if tokens is None:
            return False, "command could not be safely parsed (unbalanced or malformed quoting)"
        for tok in tokens:
            if tok in SHELL_OPERATOR_TOKENS:
                return False, f"forbidden shell operator {tok!r}"
            if tok in DANGEROUS_BARE_WORDS:
                return False, f"forbidden bare command {tok!r}"
            if tok == "main.py" or tok.endswith("/main.py"):
                return False, "main.py must not be invoked from the PO role"
        prefixes = COMMON_PREFIXES + (INTERACTIVE_EXTRA_PREFIXES if form == "interactive" else ())
        if not any(_prefix_matches(cmd, p) for p in prefixes):
            return False, "command prefix not in the PO allowlist"
        if "--body-file" in tokens:
            idx = tokens.index("--body-file")
            bf_path = tokens[idx + 1] if idx + 1 < len(tokens) else ""
            if not _path_is_po_scratch(bf_path, cwd):
                return False, "--body-file may reference only the nexus_po_*.json/.txt scratch pattern"
        if cmd.startswith("gh issue comment"):
            if "--body-file" not in tokens and not any(m in cmd for m in RELAY_MARKERS):
                return False, "relay comments must start with one of the five markers"
        if cmd.startswith("gh issue close"):
            if ("--comment" in tokens or "-c" in tokens) and not any(m in cmd for m in RELAY_MARKERS):
                return False, "an inline closing comment must contain one of the five relay markers"
        if form == "interactive" and cmd.startswith(("git add", "git commit", "git push", "gh pr create")):
            branch = branch_lookup(cwd)
            if not branch.startswith("gov/po-"):
                return False, f"Git writes are allowed only on a gov/po-* branch (current: {branch or 'unknown'})"
        if cmd.startswith("git push") and ("--force" in tokens or cmd.startswith("git push -f")):
            return False, "force push is never allowed"
        if cmd.startswith("git worktree add"):
            allowed, reason = _check_worktree_add(tokens)
            if not allowed:
                return False, reason
        return True, "allowlisted command"
    return False, f"tool {tool!r} is not available to the PO role"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--form", choices=("delegated", "interactive"), required=True)
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        payload = {}
    allowed, reason = decide(payload, args.form)
    tool_input = payload.get("tool_input") or {}
    subject = str(tool_input.get("command") or tool_input.get("file_path") or "")[:200]
    _log({
        "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "form": args.form,
        "session_id": payload.get("session_id"),
        "agent_id": payload.get("agent_id"),
        "agent_type": payload.get("agent_type"),
        "tool": payload.get("tool_name"),
        "subject": subject,
        "decision": "allow" if allowed else "deny",
        "reason": reason,
    })
    decision = "allow" if allowed else "deny"
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": decision,
        "permissionDecisionReason": f"nexus-po gate ({args.form}): {reason}",
    }}))
    if not allowed:
        print(f"nexus-po gate ({args.form}) denied {payload.get('tool_name')}: {reason}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
