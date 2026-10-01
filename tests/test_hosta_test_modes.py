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
    database = yaml.safe_load((ROOT / "deploy/ui2-image-build/33-integration-postgres.yaml").read_text())
    spec = database["spec"]["template"]["spec"]
    assert "postgres:16@sha256:" in spec["initContainers"][0]["image"]
    assert not spec["automountServiceAccountToken"]
    assert all("persistentVolumeClaim" not in volume for volume in spec["volumes"])


@pytest.mark.parametrize("failure", ["", "setup", "apply", "archive", "gradle", "skipped", "missing", "duplicate", "inconsistent", "cleanup"])
def test_integration_cleanup_and_fail_closed_result(tmp_path, failure):
    checkout = tmp_path / "checkout"
    (checkout / "scripts").mkdir(parents=True)
    for name in ("hosta_integration.sh", "hosta_integration_remote.py"):
        shutil.copyfile(ROOT / "scripts" / name, checkout / "scripts" / name)
    config = tmp_path / "home/.config/nexus"
    config.mkdir(parents=True)
    (config / "hosta").write_text("synthetic-host\n")
    remote = tmp_path / "remote home"
    (remote / "nexus/project").mkdir(parents=True)
    (remote / "nexus/project/deploy_info.json").write_text('{"commit":"synthetic-commit"}')
    build = {"spec": {"template": {"spec": {"containers": [{"env": [
        {"name": "HTTP_PROXY", "value": "http://192.0.2.10:8080"},
        {"name": "HTTPS_PROXY", "value": "http://192.0.2.10:8080"},
        {"name": "GRADLE_OPTS", "value": "-Dhttp.proxyHost=192.0.2.10 -Dhttp.proxyPort=8080"}]}]}}}}
    (remote / "build-job-proxy.yaml").write_text(yaml.safe_dump(build))
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    stubs = {
        "ssh": r'''#!/usr/bin/env python3
import os,sys
assert '-L' not in sys.argv and 'synthetic-host' in sys.argv
assert 'export KUBECONFIG=$HOME/.kube/config;' in sys.argv[-1]
os.execve('/bin/sh', ['sh','-c',sys.argv[-1]], {**os.environ, 'HOME':os.environ['TEST_REMOTE_HOME']})
''',
        "git": r'''#!/usr/bin/env python3
import os,sys
from pathlib import Path
if sys.argv[1]=='rev-parse': print('synthetic-commit')
elif sys.argv[1]=='show': print(Path(os.environ['TEST_MANIFEST']).read_text())
elif sys.argv[1]=='archive':
    assert sys.argv[2:] == ['synthetic-commit','ui2','scripts/integration_runner.sh','scripts/IntegrationSummary.java']
    if os.environ['TEST_FAILURE']=='archive': sys.exit(1)
    print('synthetic archive')
else: sys.exit(99)
''',
        "kubectl": r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
import yaml
args=sys.argv[1:]; failure=os.environ['TEST_FAILURE']
assert os.environ['KUBECONFIG']==str(Path.home()/'.kube/config')
assert 'port-forward' not in args
with open(os.environ['TEST_CALLS'],'a') as f: f.write(' '.join(args)+'\n')
if 'create' in args and 'secret' in args:
    value=sys.stdin.read(); assert len(value)==48 and value not in ' '.join(args)
    if failure=='setup': sys.exit(1)
elif '--dry-run=client' in args:
    src=args[args.index('-f')+1]
    print(json.dumps(yaml.safe_load(sys.stdin.read() if src=='-' else Path(src).read_text())))
elif 'apply' in args:
    doc=json.load(sys.stdin)
    if doc['kind']=='NetworkPolicy':
        assert doc['spec']['ingress']==[]
        assert doc['spec']['egress'][0]['to']==[{'ipBlock':{'cidr':'192.0.2.10/32'}}]
        assert doc['spec']['egress'][-1]['ports']==[{'protocol':p,'port':53} for p in ('UDP','TCP')]
    else:
        assert any(e['name']=='GRADLE_OPTS' for e in doc['spec']['template']['spec']['containers'][0]['env'])
    if failure=='apply': sys.exit(1)
elif 'get' in args:
    if 'pods' in args: print(json.dumps({'items':[{'metadata':{'name':'synthetic-pod'},'status':{'containerStatuses':[{'name':'runner','state':{'running':{}}}]}}]}))
    else: print(json.dumps({'status':{'conditions':[{'type':'Failed' if failure in ('gradle','skipped','missing','inconsistent') else 'Complete','status':'True'}]}}))
elif 'exec' in args:
    if '-i' in args: assert sys.stdin.read()=='synthetic archive\n'
elif 'logs' in args:
    if failure=='missing': print('INTEGRATION: ERROR (missing reports)')
    elif failure in ('gradle','skipped'): print('INTEGRATION: FAIL (tests=3; failures=1; errors=0; skipped=0)\nFAILED SyntheticTest.example')
    else: print('INTEGRATION: PASS (tests=3; skipped=0)')
    if failure=='duplicate': print('INTEGRATION: PASS (tests=3; skipped=0)')
    print('raw diagnostics must never be shown')
elif 'delete' in args and failure=='cleanup': sys.exit(1)
'''}
    for name, text in stubs.items():
        path = bin_dir / name
        path.write_text(text)
        path.chmod(0o755)
    calls = tmp_path / "calls"
    env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "HOME": str(tmp_path / "home"),
           "TEST_REMOTE_HOME": str(remote), "TEST_CALLS": str(calls), "TEST_FAILURE": failure,
           "TEST_MANIFEST": str(ROOT / "deploy/ui2-image-build/33-integration-postgres.yaml")}
    result = subprocess.run(["bash", str(checkout / "scripts/hosta_integration.sh")], env=env,
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == (0 if not failure else 1 if failure in ("gradle", "skipped") else 2), result.stdout
    assert ("INTEGRATION: PASS" in result.stdout) == (failure == "")
    assert "raw diagnostics" not in result.stdout
    assert result.stderr == ""
    assert "delete job,networkpolicy,secret ui2-integration-" in calls.read_text()


def test_integration_runner_has_no_forwarding_or_local_gradle():
    source = (ROOT / "scripts/hosta_integration.sh").read_text()
    assert "trap cleanup EXIT" in source
    for forbidden in ("-L", "port-forward", "mkfifo", "./gradlew"):
        assert forbidden not in source
    for line in source.splitlines():
        if " kubectl " in line or "python3 - $run" in line:
            assert "export KUBECONFIG=\\$HOME/.kube/config;" in line


@pytest.mark.parametrize("kind,expected", [("pass",0),("failure",1),("skipped",1),("missing",2),("malformed",2),("gradle",1)])
def test_junit_summary_is_bounded_and_sanitized(tmp_path, kind, expected):
    if kind != "missing":
        cases = ''.join(f'<testcase classname="example.SyntheticTest" name="test{i}()"><failure message="private detail"/></testcase>' for i in range(25)) if kind == "failure" else ''
        xml = f'<testsuite tests="25" failures="{25 if kind == "failure" else 0}" errors="0" skipped="{1 if kind == "skipped" else 0}">{cases}</testsuite>'
        (tmp_path / "TEST-synthetic.xml").write_text('invalid' if kind == "malformed" else xml)
    result = subprocess.run(["java", str(ROOT / "scripts/IntegrationSummary.java"), "1" if kind == "gradle" else "0", str(tmp_path)], capture_output=True, text=True)
    assert result.returncode == expected, result.stderr
    assert result.stdout.count("INTEGRATION:") == 1
    assert "private detail" not in result.stdout
    assert sum(line.startswith("FAILED ") for line in result.stdout.splitlines()) == (20 if kind == "failure" else 0)
