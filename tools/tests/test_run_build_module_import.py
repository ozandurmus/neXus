"""Offline entry-point checks from outside a synthetic checkout; no host access."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def checkout(tmp_path):
    repo = tmp_path / "nexus"
    for relative in (
        "deploy/ui2-image-build/run_build.sh", "tools/delivery/hosta_deploy.sh",
        "tools/delivery/module_deploy.py", "tools/delivery/release_snapshot.py",
        "tools/delivery/release_snapshot.sh", "tools/delivery/rollback.sh",
        "tools/e2e/hosta_e2e_image.py", "tools/security/security_host.py",
        "tools/security/security_manifests.py",
    ):
        destination = repo / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, destination)
    (repo / "project").mkdir()
    return repo


def outside_run(argv, **kwargs):
    env = dict(os.environ)
    env.pop("PYTHONPATH", None)
    env.update(kwargs.pop("env", {}))
    return subprocess.run(argv, cwd="/", env=env, capture_output=True, text=True,
                          timeout=10, **kwargs)


@pytest.mark.parametrize("script,variable", [
    ("deploy/ui2-image-build/run_build.sh", "NEXUS_DEPLOY_TARGETS"),
    ("tools/delivery/hosta_deploy.sh", "TARGETS"),
])
@pytest.mark.parametrize("targets,code", [("service,worker policy", 0), ("policy policy", 5), ("unknown", 5)])
def test_actual_validation_invocations_import_from_an_unrelated_cwd(checkout, script, variable, targets, code):
    source = (checkout / script).read_text()
    command = next(line for line in source.splitlines() if "--validate-targets" in line).split(" ||")[0]
    result = outside_run(["bash", "-c", command], env={"REPO": str(checkout), variable: targets})
    assert result.returncode == code, result.stderr
    assert "ModuleNotFoundError" not in result.stderr


def test_all_build_python_scripts_and_rollback_wrappers_import_outside_checkout(checkout):
    source = (checkout / "deploy/ui2-image-build/run_build.sh").read_text()
    paths = set(re.findall(r'python3 "\$REPO/([^"\n]+\.py)"', source))
    assert "python3 tools/" not in source and "from tools." not in source
    for path in paths:
        result = outside_run([sys.executable, str(checkout / path), "--help"])
        assert result.returncode == 0, result.stderr
    for path in ("tools/delivery/release_snapshot.sh", "tools/delivery/rollback.sh"):
        result = outside_run(["bash", str(checkout / path), "--help"])
        assert result.returncode == 0, result.stderr
    # Exercise the rollback's sibling import, beyond argparse's early --help exit.
    result = outside_run([sys.executable, str(checkout / "tools/delivery/module_deploy.py"),
                          "--targets", "service", "--rollback", "--snapshot", str(checkout / "absent")])
    assert result.returncode != 0
    assert "load_snapshot" in result.stderr and "ModuleNotFoundError" not in result.stderr


@pytest.mark.parametrize("failure,apply_files,events_expected", [
    ("targets", "", ["git"] * 3), ("git", "", ["git"]),
    ("snapshot", "synthetic.yaml", ["git"] * 4 + ["snapshot"]),
    ("apply", "synthetic.yaml", ["git"] * 4 + ["snapshot", "apply"]),
    ("apply", "", ["git"] * 4 + ["snapshot", "apply"]),
])
@pytest.mark.parametrize("repo_override", [False, True])
def test_build_failure_reports_no_changes_only_before_mutation(checkout, tmp_path, failure, apply_files, events_expected, repo_override):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    events = tmp_path / "events"
    for name, body in {
        "git": 'echo git >> "$EVENTS"; [ "$FAILURE" != git ] || exit 19; echo synthetic-commit',
        "kubectl": 'echo apply >> "$EVENTS"; exit 23',
    }.items():
        path = bin_dir / name
        path.write_text("#!/bin/bash\n" + body + "\n")
        path.chmod(0o755)
    (bin_dir / "python3").symlink_to(sys.executable)
    (checkout / "tools/delivery/release_snapshot.sh").write_text(
        '#!/bin/bash\necho snapshot >> "$EVENTS"\n[ "$FAILURE" != snapshot ] || exit 17\n'
        'echo \'{"snapshot": "synthetic-snapshot"}\'\n')
    result = outside_run(["bash", str(checkout / "deploy/ui2-image-build/run_build.sh")], env={
        "HOME": str(tmp_path / "unrelated-home" if repo_override else tmp_path),
        "REPO": str(checkout) if repo_override else "",
        "PATH": str(bin_dir) + os.pathsep + os.defpath,
        "EVENTS": str(events), "FAILURE": failure,
        "NEXUS_DEPLOY_TARGETS": "unknown" if failure == "targets" else "service",
        "NEXUS_DEPLOY_APPLY_FILES": apply_files, "NEXUS_SKIP_SECURITY_REASON": "synthetic test",
    })
    assert result.returncode != 0
    assert ("no changes applied" in result.stderr) == (failure != "apply")
    assert (events.read_text().splitlines() if events.exists() else []) == events_expected


def test_build_snapshot_id_python_invocation_is_cwd_independent(checkout):
    source = (checkout / "deploy/ui2-image-build/run_build.sh").read_text()
    assignment = next(line for line in source.splitlines() if line.startswith("SNAPSHOT_ID="))
    result = outside_run(["bash", "-c", assignment + '\nprintf "%s" "$SNAPSHOT_ID"'],
                         env={"RELEASE_SNAPSHOT": '{"snapshot": "synthetic-snapshot"}'})
    assert result.returncode == 0 and result.stdout == "synthetic-snapshot"
