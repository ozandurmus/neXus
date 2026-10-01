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


@pytest.mark.parametrize("failure", ["", "setup", "apply", "ready", "tunnel", "gradle", "skipped", "missing", "signal", "cleanup"])
def test_integration_cleanup_and_fail_closed_result(tmp_path, failure):
    checkout = tmp_path / "checkout"
    for directory in ("scripts", "deploy/ui2-image-build", "ui2", "bin"):
        (checkout / directory).mkdir(parents=True)
    for relative in ("scripts/hosta_integration.sh", "deploy/ui2-image-build/33-integration-postgres.yaml"):
        shutil.copyfile(ROOT / relative, checkout / relative)
    calls = tmp_path / "calls"
    config = tmp_path / "home/.config/nexus"
    config.mkdir(parents=True)
    (config / "hosta").write_text("synthetic-host\n")
    ssh = checkout / "bin/ssh"
    ssh.write_text('''#!/usr/bin/env python3
import os,sys
from pathlib import Path
args=sys.argv[1:]
assert 'synthetic-host' in args
command=args[-1]
assert 'gradlew' not in command and 'git archive' not in command
if '-L' in args:
    binding=args[args.index('-L')+1].split(':')
    assert binding[0] == binding[2] == '127.0.0.1'
    assert 'ExitOnForwardFailure=yes' in args
    Path(os.environ['TEST_PORT']).write_text(binding[1])
    if os.environ['TEST_FAILURE']=='tunnel': sys.exit(1)
os.execve('/bin/sh', ['sh', '-c', command], {**os.environ, 'TEST_REMOTE': '1', 'HOME': os.environ['TEST_REMOTE_HOME'], 'KUBECONFIG': '/synthetic/inherited-config'})
''')
    ssh.chmod(0o755)
    kubectl = checkout / "bin/kubectl"
    kubectl.write_text('''#!/usr/bin/env python3
import os,sys,time,signal
from pathlib import Path
assert os.environ['TEST_REMOTE']=='1'
assert os.environ.get('KUBECONFIG') == str(Path(os.environ['HOME']) / '.kube/config')
args=sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as f: f.write(' '.join(args)+'\\n')
if 'create' in args:
    assert '--from-file=' + 'password' + '=/dev/stdin' in args
    secret_value=sys.stdin.read()
    assert len(secret_value)==48 and secret_value not in ' '.join(args)
    if os.environ['TEST_FAILURE']=='setup': sys.exit(1)
if 'apply' in args:
    assert sys.stdin.read().startswith('# Only synthetic test data.')
    if os.environ['TEST_FAILURE']=='apply': sys.exit(1)
if 'wait' in args and os.environ['TEST_FAILURE']=='ready': sys.exit(1)
if 'delete' in args and os.environ['TEST_FAILURE']=='cleanup': sys.exit(1)
if 'get' in args: print('synthetic-pod')
if 'port-forward' in args:
    assert '--address=127.0.0.1' in args
    port=args[-1].split(':')[0]
    def stopped(*_):
        with open(os.environ['TEST_CALLS'], 'a') as f: f.write('forward-stopped\\n')
        sys.exit(0)
    signal.signal(signal.SIGTERM, stopped)
    print(f'Forwarding from 127.0.0.1:{port} -> 5432', flush=True)
    time.sleep(120)
''')
    kubectl.chmod(0o755)
    gradle = checkout / "ui2/gradlew"
    gradle.write_text('''#!/usr/bin/env python3
import os,sys,signal
from pathlib import Path
assert ':integration-tests:test' in sys.argv
assert 'TEST_REMOTE' not in os.environ
port=Path(os.environ['TEST_PORT']).read_text()
assert os.environ['UI2_TEST_JDBC_URL']==f'jdbc:postgresql://127.0.0.1:{port}/postgres'
secret_file=Path(os.environ['UI2_TEST_DB_PASSWORD_FILE'])
assert secret_file.stat().st_mode & 0o777 == 0o600
assert len(secret_file.read_text())==48
Path(os.environ['TEST_PASSWORD_PATH']).write_text(str(secret_file))
if os.environ['TEST_FAILURE']=='gradle': sys.exit(1)
if os.environ['TEST_FAILURE']=='missing': sys.exit(0)
if os.environ['TEST_FAILURE']=='signal':
    os.kill(os.getppid(), signal.SIGTERM)
    sys.exit(0)
p=Path('integration-tests/build/test-results/test'); p.mkdir(parents=True)
skipped=int(os.environ['TEST_FAILURE']=='skipped')
(p/'TEST-synthetic.xml').write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="{skipped}"/>')
''')
    gradle.chmod(0o755)
    env = {**os.environ, "PATH": f"{checkout / 'bin'}:{os.environ['PATH']}",
           "TEST_REMOTE_HOME": str(tmp_path / "remote home"),
           "TEST_CALLS": str(calls), "TEST_FAILURE": failure, "HOME": str(tmp_path / "home"),
           "TEST_PORT": str(tmp_path / "port"), "TEST_PASSWORD_PATH": str(tmp_path / "password_path")}
    result = subprocess.run(["bash", str(checkout / "scripts/hosta_integration.sh")],
                            env=env, capture_output=True, text=True, timeout=20)
    assert result.returncode == (0 if not failure else 1 if failure == "gradle" else 2), result.stdout + result.stderr
    assert ("INTEGRATION: PASS" in result.stdout) == (failure == "")
    assert result.stderr == ""
    commands = calls.read_text().splitlines()
    assert any("delete job,networkpolicy,secret ui2-integration-" in command for command in commands)
    assert not any("ui2-database" in command for command in commands)
    if failure in ("", "gradle", "skipped", "missing", "signal", "cleanup"):
        assert "forward-stopped" in commands
        assert not Path((tmp_path / "password_path").read_text()).parent.exists()


def test_integration_runner_has_cleanup_and_no_remote_build_mode():
    source = (ROOT / "scripts/hosta_integration.sh").read_text()
    assert "trap cleanup EXIT" in source
    assert "trap cleanup_forward EXIT" in source
    assert "--on-host" not in source
    assert "git archive" not in source


def test_integration_remote_kubectl_exports_kubeconfig():
    source = (ROOT / "scripts/hosta_integration.sh").read_text()
    kubectl_lines = [line.strip() for line in source.splitlines() if " kubectl " in line]
    assert len(kubectl_lines) == 2  # Shared SSH helper and the tunnel shell.
    assert 'export KUBECONFIG=\\$HOME/.kube/config; kubectl $1' in kubectl_lines[0]
    tunnel = source.split('"bash -c', 1)[1]
    assert tunnel.index('export KUBECONFIG=\\$HOME/.kube/config') < tunnel.index('kubectl ')
