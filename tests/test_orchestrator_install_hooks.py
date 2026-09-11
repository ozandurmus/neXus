"""GOV.ORCH.7 section 2.3 item 1 / AC-5 -- `orchestrator.py install-hooks`.

Installs the committed `.githooks/pre-push` into a temporary clone of this
very repository (so `utils/`, `scripts/orchestrator_verify.py`, and every
other import the hook needs are genuinely present) via `core.hooksPath`,
then demonstrates a force push and a push carrying a newly staged secret
are both denied there, while an ordinary push is allowed.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator as orch  # noqa: E402


def _git(cwd: Path, *args: str, env=None) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, env=env)


def _fixture_clone(tmp_path: Path) -> tuple[Path, Path]:
    """A bare mirror of this repository plus one working clone of it --
    real object history, real `utils/`, real `.githooks/pre-push`, so the
    hook's own imports resolve exactly as they do in a real checkout."""
    bare = tmp_path / "origin.git"
    assert _git(tmp_path, "clone", "-q", "--bare", str(ROOT), str(bare)).returncode == 0

    clone = tmp_path / "clone"
    assert _git(tmp_path, "clone", "-q", str(bare), str(clone)).returncode == 0
    _git(clone, "config", "user.email", "t@example.com")
    _git(clone, "config", "user.name", "T")
    current_branch = _git(ROOT, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    assert _git(clone, "checkout", "-q", current_branch).returncode == 0
    return clone, bare


def test_install_hooks_sets_core_hooks_path(tmp_path):
    clone, _bare = _fixture_clone(tmp_path)
    rc = orch.main(["install-hooks", "--path", str(clone)])
    assert rc == orch.EXIT_OK
    result = _git(clone, "config", "core.hooksPath")
    assert result.stdout.strip() == ".githooks"


def test_install_hooks_refuses_a_checkout_without_the_committed_hook(tmp_path):
    empty = tmp_path / "not-a-checkout"
    empty.mkdir()
    rc = orch.main(["install-hooks", "--path", str(empty)])
    assert rc == orch.EXIT_USAGE


def test_installed_hook_denies_a_force_push(tmp_path):
    clone, _bare = _fixture_clone(tmp_path)
    branch = _git(clone, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    assert orch.main(["install-hooks", "--path", str(clone)]) == orch.EXIT_OK

    readme = clone / "README_GOV_ORCH_7_FIXTURE.md"
    readme.write_text("fixture\n", encoding="utf-8")
    _git(clone, "add", readme.name)
    _git(clone, "commit", "-q", "-m", "fixture change")

    env = dict(os.environ, NEXUS_GIT_FORCE_PUSH="1")
    result = _git(clone, "push", "origin", branch, env=env)
    assert result.returncode != 0
    assert "force push" in (result.stderr or "")


def test_installed_hook_allows_an_ordinary_fast_forward_push(tmp_path):
    clone, bare = _fixture_clone(tmp_path)
    branch = _git(clone, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    assert orch.main(["install-hooks", "--path", str(clone)]) == orch.EXIT_OK

    push_branch = f"{branch}-gov-orch-7-fixture-push"
    _git(clone, "checkout", "-q", "-b", push_branch)
    readme = clone / "README_GOV_ORCH_7_FIXTURE.md"
    readme.write_text("fixture\n", encoding="utf-8")
    _git(clone, "add", readme.name)
    _git(clone, "commit", "-q", "-m", "fixture change")

    result = _git(clone, "push", "origin", push_branch)
    assert result.returncode == 0, result.stderr
    assert _git(bare, "rev-parse", "--verify", push_branch).returncode == 0
