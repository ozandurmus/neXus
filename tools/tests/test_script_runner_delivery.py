"""Script Execution delivery (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b)): the broker's rollout keeps the
execution image apart from the service image, re-applies its Role and policies each ship, and its credentials tool
never puts a secret on a command line. Local doubles only; secret-shaped values are built at run time."""
import base64
import hashlib
import json
from pathlib import Path
import sys

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/delivery"))
import module_deploy as deploy  # noqa: E402
import script_runner_db_login as login  # noqa: E402

SERVICE = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "a" * 64
EXEC = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "b" * 64
LIVE_EXEC = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "c" * 64


def _docs(text):
    return [d for d in yaml.safe_load_all(text) if d]


def _broker(rendered):
    return next(d for d in _docs(rendered) if d["kind"] == "Deployment")


def _exec_env(deployment):
    return next(e["value"] for e in deployment["spec"]["template"]["spec"]["containers"][0]["env"]
                if e["name"] == "UI2_SCRIPT_EXEC_IMAGE")


def test_render_sets_the_service_image_on_the_broker_and_the_exec_image_in_its_env():
    rendered = deploy.render_script_runner(SERVICE, EXEC)
    broker = _broker(rendered)
    assert broker["spec"]["template"]["spec"]["containers"][0]["image"] == SERVICE
    assert _exec_env(broker) == EXEC
    others = deploy.render_script_runner(SERVICE, EXEC, deployment=False)
    kinds = sorted(d["kind"] for d in _docs(others))
    assert kinds == ["NetworkPolicy", "NetworkPolicy", "Role", "RoleBinding", "ServiceAccount", "ServiceAccount"]


def test_exec_image_comes_from_the_flag_else_the_live_broker_never_the_service_image(monkeypatch):
    live = {"spec": {"template": {"spec": {"containers": [{"image": SERVICE, "env": [
        {"name": "UI2_SCRIPT_EXEC_IMAGE", "value": LIVE_EXEC}]}]}}}}
    monkeypatch.setattr(deploy, "run", lambda *a, **kw: json.dumps(live))
    monkeypatch.setattr(deploy, "SCRIPT_EXEC_IMAGE", None)
    assert deploy.script_exec_image(SERVICE) == LIVE_EXEC
    monkeypatch.setattr(deploy, "SCRIPT_EXEC_IMAGE", EXEC)
    assert deploy.script_exec_image(SERVICE) == EXEC
    with pytest.raises(deploy.Blocked):
        deploy.script_exec_image(EXEC)  # equal to the service image
    monkeypatch.setattr(deploy, "SCRIPT_EXEC_IMAGE", None)
    monkeypatch.setattr(deploy, "run", lambda *a, **kw: "")
    with pytest.raises(deploy.Blocked, match="--script-exec-image"):
        deploy.script_exec_image(SERVICE)


def test_every_release_reapplies_the_role_and_policies_but_not_the_deployment_before_the_drain(monkeypatch):
    live = {"spec": {"template": {"spec": {"containers": [{"image": SERVICE.replace("a" * 64, "d" * 64), "env": [
        {"name": "UI2_SCRIPT_EXEC_IMAGE", "value": LIVE_EXEC}]}]}}}}
    calls = []

    def run(*args, **kw):
        calls.append((args, kw.get("input")))
        return json.dumps(live) if args[4:6] == ("deployment", "ui2-script-runner") else ""

    monkeypatch.setattr(deploy, "run", run)
    monkeypatch.setattr(deploy, "SCRIPT_EXEC_IMAGE", EXEC)
    old = deploy.ensure_script_runner(SERVICE)
    assert old == live["spec"]["template"]["spec"]["containers"][0]["image"]
    applied = [inp for args, inp in calls if args[-3:] == ("apply", "-f", "-")]
    assert len(applied) == 1
    assert {d["kind"] for d in _docs(applied[0])} == {"ServiceAccount", "Role", "RoleBinding", "NetworkPolicy"}

    calls.clear()
    deploy.replace_image("script-runner", SERVICE, sync_manifest=True)
    (applied,) = [inp for args, inp in calls if args[-3:] == ("apply", "-f", "-")]
    broker = _broker(applied)
    assert broker["spec"]["template"]["spec"]["containers"][0]["image"] == SERVICE
    assert _exec_env(broker) == EXEC


def test_main_refuses_an_exec_image_that_is_the_service_image(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["module_deploy.py", "--targets", "script-runner", "--image", SERVICE,
                                      "--script-exec-image", SERVICE, "--snapshot", "/nonexistent"])
    with pytest.raises(SystemExit) as stop:
        deploy.main()
    assert stop.value.code == 5
    assert "script execution image must differ from the main service image" in capsys.readouterr().out


def test_scram_verifier_matches_postgres_format_and_never_contains_the_password():
    password = "pw-" + "x" * 20
    verifier = login.scram_verifier(password, salt=b"0123456789abcdef")
    head, keys = verifier.split("$", 1)[1].split("$")
    assert verifier.startswith("SCRAM-SHA-256$4096:")
    assert base64.b64decode(head.split(":")[1]) == b"0123456789abcdef"
    stored, server = keys.split(":")
    assert len(base64.b64decode(stored)) == 32 and len(base64.b64decode(server)) == 32
    assert password not in verifier
    # RFC 7677 reference vector shape check: deterministic for a fixed salt.
    assert login.scram_verifier(password, salt=b"0123456789abcdef") == verifier


def test_db_login_sends_secrets_on_stdin_only():
    calls = []

    class Result:
        returncode = 0
        stdout = ""
        stderr = ""

    def runner(args, input=None, **kw):
        calls.append((args, input))
        return Result()

    password = "pw-" + "q" * 30
    written = login.db_login(runner=runner, password=password)
    assert written == ["ui2-db-script-runner", "role ui2_script_runner"]
    for args, _ in calls:
        assert password not in " ".join(args), "never on a command line"
    sql = calls[0][1]
    assert "ALTER ROLE ui2_script_runner LOGIN PASSWORD 'SCRAM-SHA-256$" in sql and password not in sql
    assert "to_regprocedure('ui2_apply_script_runner_role_grants(text)') IS NOT NULL" in sql
    assert "ui2_apply_script_runner_role_grants()" in sql
    secret = json.loads(calls[1][1])
    assert secret["metadata"]["name"] == "ui2-db-script-runner"
    assert base64.b64decode(secret["data"]["password"]).decode() == password

    calls.clear()
    token = "tok-" + "z" * 30
    assert login.token(runner=runner, value=token) == ["ui2-script-runner-token"]
    doc = json.loads(calls[0][1])
    assert base64.b64decode(doc["data"]["sha256"]).decode() == hashlib.sha256(token.encode()).hexdigest()
    assert token not in " ".join(calls[0][0])


def test_first_ship_defers_broker_deployment_until_broker_secrets_exist(monkeypatch):
    calls = []
    secrets_present = set()

    def run(*args, **kw):
        calls.append((args, kw.get("input")))
        if args[4:6] == ("deployment", "ui2-script-runner"):
            return ""
        if args[4:5] == ("secret",) and len(args) > 5 and args[5] in secrets_present:
            return args[5]
        return ""

    monkeypatch.setattr(deploy, "run", run)
    monkeypatch.setattr(deploy, "SCRIPT_EXEC_IMAGE", EXEC)

    # 1. Before script_runner_db_login.py runs: applies RBAC/NetworkPolicies only, defers Deployment (no deadlock).
    out = deploy.replace("script-runner", SERVICE, "snap-1", defer_e2e=True)
    assert out["ready"] is False
    applied = [inp for args, inp in calls if args[-3:] == ("apply", "-f", "-")]
    assert len(applied) == 1
    assert {d["kind"] for d in _docs(applied[0])} == {"ServiceAccount", "Role", "RoleBinding", "NetworkPolicy"}

    # 2. Once both Secrets exist, ensure_script_runner applies the full manifest including the Deployment.
    calls.clear()
    secrets_present.update({"ui2-db-script-runner", "ui2-script-runner-token"})
    assert deploy.ensure_script_runner(SERVICE) is None
    (full_applied,) = [inp for args, inp in calls if args[-3:] == ("apply", "-f", "-")]
    assert "Deployment" in {d["kind"] for d in _docs(full_applied)}
