"""Offline checks: all host/cluster commands are replaced with local test doubles."""
import os
from pathlib import Path
import shutil
import subprocess

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]


def test_help_and_invalid_mode_never_contact_a_host(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name in ("ssh", "scp", "kubectl"):
        path = bin_dir / name
        path.write_text("#!/bin/sh\necho unexpected-contact >&2\nexit 99\n")
        path.chmod(0o755)
    env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}"}
    for script in ("hosta_e2e.sh", "hosta_integration.sh"):
        result = subprocess.run(["bash", str(ROOT / "scripts" / script), "--help"], env=env, capture_output=True, text=True)
        assert result.returncode == 0
        assert "Usage:" in result.stdout
        assert "unexpected-contact" not in result.stderr
    result = subprocess.run(["bash", str(ROOT / "scripts/hosta_e2e.sh")],
                            env={**env, "NEXUS_E2E_MODE": "typo"}, capture_output=True, text=True)
    assert result.returncode == 2
    assert "unexpected-contact" not in result.stderr


def test_nightly_full_and_ephemeral_isolation_manifests():
    job, cron = list(yaml.safe_load_all((ROOT / "deploy/ui2/70-e2e-job.yaml").read_text()))
    env = lambda spec: {v["name"]: v.get("value") for v in spec["containers"][0]["env"]}
    assert env(job["spec"]["template"]["spec"])["NEXUS_E2E_MODE"] == "quick"
    assert env(cron["spec"]["jobTemplate"]["spec"]["template"]["spec"])["NEXUS_E2E_MODE"] == "full"
    assert cron["spec"]["schedule"] == "0 2 * * *"
    policy, database = list(yaml.safe_load_all((ROOT / "deploy/ui2-image-build/33-integration-postgres.yaml").read_text()))
    assert policy["spec"]["ingress"] == policy["spec"]["egress"] == []
    spec = database["spec"]["template"]["spec"]
    assert "postgres:16@sha256:" in spec["containers"][0]["image"]
    assert not spec["automountServiceAccountToken"]
    assert all("persistentVolumeClaim" not in volume for volume in spec["volumes"])


@pytest.mark.parametrize("failure", ["", "setup", "gradle", "skipped"])
def test_integration_cleanup_and_fail_closed_result(tmp_path, failure):
    checkout = tmp_path / "checkout"
    for directory in ("scripts", "deploy/ui2-image-build", "ui2", "bin"):
        (checkout / directory).mkdir(parents=True)
    for relative in ("scripts/hosta_integration.sh", "deploy/ui2-image-build/33-integration-postgres.yaml"):
        shutil.copyfile(ROOT / relative, checkout / relative)
    calls = tmp_path / "calls"
    kubectl = checkout / "bin/kubectl"
    kubectl.write_text('''#!/usr/bin/env python3
import os,sys,time
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as f: f.write(' '.join(args)+'\\n')
if 'create' in args and os.environ['TEST_FAILURE']=='setup': sys.exit(1)
if 'get' in args: print('synthetic-pod')
if 'port-forward' in args:
    print('Forwarding from 127.0.0.1:15432 -> 5432', flush=True)
    time.sleep(120)
''')
    kubectl.chmod(0o755)
    gradle = checkout / "ui2/gradlew"
    gradle.write_text('''#!/usr/bin/env python3
import os,sys
from pathlib import Path
assert ':integration-tests:test' in sys.argv
assert os.environ['UI2_TEST_JDBC_URL']=='jdbc:postgresql://127.0.0.1:15432/postgres'
assert Path(os.environ['UI2_TEST_DB_PASSWORD_FILE']).is_file()
if os.environ['TEST_FAILURE']=='gradle': sys.exit(1)
p=Path('integration-tests/build/test-results/test'); p.mkdir(parents=True)
skipped=int(os.environ['TEST_FAILURE']=='skipped')
(p/'TEST-synthetic.xml').write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="{skipped}"/>')
''')
    gradle.chmod(0o755)
    env = {**os.environ, "PATH": f"{checkout / 'bin'}:{os.environ['PATH']}",
           "TEST_CALLS": str(calls), "TEST_FAILURE": failure}
    result = subprocess.run(["bash", str(checkout / "scripts/hosta_integration.sh"), "--on-host"],
                            env=env, capture_output=True, text=True, timeout=20)
    assert (result.returncode == 0) == (failure == "")
    assert ("INTEGRATION: PASS" in result.stdout) == (failure == "")
    commands = calls.read_text().splitlines()
    assert any("delete job,networkpolicy,secret ui2-integration-" in command for command in commands)
    assert not any("ui2-database" in command for command in commands)
