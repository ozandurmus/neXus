"""Offline security pipeline checks: synthetic reports, rendered manifests and fake Jobs only."""
from datetime import date, timedelta
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/delivery"))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "security"))
import security_summary as summary
import security_manifests as manifests
import security_host as host


def row(rule="synthetic-rule", severity="HIGH"):
    return summary.finding("semgrep", rule, "src/Example.java:7", severity)


def config():
    return dict(node_name="synthetic-node", proxy_ip="192.0.2.10", proxy_port=3128,
                registry_ip="192.0.2.11", service_ip="192.0.2.12",
                images={t: manifests.REGISTRY + "/security/" + t + "@sha256:" + "a" * 64
                        for t in ("semgrep", "gitleaks", "trivy", "zap")})


def test_new_fixed_accepted_and_existing_are_independent():
    old = [row("fixed", "LOW"), row("existing", "MEDIUM")]
    current = [row("new"), row("accepted"), row("existing", "MEDIUM")]
    accept = [dict(tool="semgrep", rule="accepted", location="src/Example.java:7")]
    result = summary.summarize(current, old, accept)
    assert result["counts"]["semgrep"]["HIGH"] == dict(new=1, fixed=0, accepted=1, existing=0)
    assert result["counts"]["semgrep"]["LOW"]["fixed"] == 1
    assert result["counts"]["semgrep"]["MEDIUM"]["existing"] == 1
    assert not result["passed"]
    assert "src/" not in json.dumps(result)


@pytest.mark.parametrize("severity,passed", [(s, s not in ("UNKNOWN", "HIGH", "CRITICAL")) for s in summary.SEVERITIES])
def test_threshold_and_yesterday_does_not_auto_accept(severity, passed):
    assert summary.summarize([row(severity=severity)], [], [])["passed"] is passed
    assert summary.summarize([row(severity=severity)], [row(severity=severity)], [])["passed"] is passed


def test_baseline_expiry_commit_and_exact_location(tmp_path):
    path = tmp_path / "baseline.yaml"
    entries = [dict(tool="gitleaks", rule="synthetic", location="src/example.txt", commit="a" * 40,
                    reason="Synthetic acceptance", owner="reviewer", review_date="2026-09-30")]
    path.write_text(json.dumps(dict(accepted=entries)))
    assert summary.load_baseline(path, date(2026, 9, 30)) == entries
    assert summary.load_baseline(path, date(2026, 10, 1)) == []
    f = summary.finding("gitleaks", "synthetic", "src/example.txt", "HIGH", "b" * 40)
    assert not summary.summarize([f], [], entries)["passed"]
    f["commit"] = "a" * 40
    assert summary.summarize([f], [], entries)["passed"]
    f["location"] += ".other"
    assert not summary.summarize([f], [], entries)["passed"]


def trivy_report(*vulns):
    return {"SchemaVersion": 2, "Results": [{"Target": "base", "Vulnerabilities": list(vulns)}]}


def vuln(cve, pkg, **extra):
    return dict(VulnerabilityID=cve, PkgName=pkg, Severity="HIGH", **extra)


def acceptance(rule, location, review_date, tool="trivy"):
    return dict(tool=tool, rule=rule, location=location, reason="Synthetic", owner="reviewer", review_date=review_date)


def test_trivy_projection_keeps_only_a_fix_flag_never_the_version():
    rows = summary.project("trivy", trivy_report(
        vuln("CVE-2026-00001", "a", FixedVersion="9.9.9"), vuln("CVE-2026-00002", "b", Status="fixed"),
        vuln("CVE-2026-00003", "c", Status="affected"), vuln("CVE-2026-00004", "d")), "img")
    assert [r.get("fix_available", False) for r in rows] == [True, True, False, False]
    assert "9.9.9" not in json.dumps(rows)
    # Same CVE/package reported twice: the fix flag survives deduplication whichever row wins.
    dup = [summary.finding("trivy", "CVE-2026-00001", "img: a", "HIGH"),
           summary.finding("trivy", "CVE-2026-00001", "img: a", "MEDIUM", fix_available=True)]
    assert summary.summarize(dup, [], [])["counts"]["trivy"]["HIGH"]["new"] == 1


TODAY = date(2026, 10, 25)


def test_accepted_review_flags_fix_due_soon_and_past_review_with_ids_only():
    loc = "reg/img: java-21-openjdk-headless"
    current = [summary.finding("trivy", "CVE-FIXED", loc, "HIGH", fix_available=True),
               summary.finding("trivy", "CVE-SOON", "reg/img: libbrotli", "HIGH"),
               summary.finding("trivy", "CVE-QUIET", "reg/img: expat", "HIGH")]
    acceptances = [acceptance("CVE-FIXED", loc, "2026-12-31"),
                   acceptance("CVE-FIXED", "other/img: java-21-openjdk-headless", "2026-12-31"),
                   acceptance("CVE-SOON", "reg/img: libbrotli", "2026-10-28"),
                   acceptance("CVE-QUIET", "reg/img: expat", "2026-10-29"),
                   acceptance("CVE-LATE", "reg/img: gnutls", "2026-10-24"),
                   acceptance("CVE-GONE", "reg/img: zlib", "2026-12-31")]
    review = summary.accepted_review(current, acceptances, TODAY)
    assert (review["fix_available"], review["due_soon"], review["past_review"], review["not_reported"]) == (1, 1, 1, 2)
    assert review["attention"] is True
    # One entry per CVE/package, not per image location; ids and package names only.
    assert review["items"] == [
        dict(id="CVE-FIXED", package="java-21-openjdk-headless", status="fix_available"),
        dict(id="CVE-LATE", package="gnutls", status="past_review"),
        dict(id="CVE-SOON", package="libbrotli", status="due_soon")]
    assert "reg/img" not in json.dumps(review)


def test_accepted_review_window_boundaries_and_quiet_state():
    loc = "reg/img: expat"
    current = [summary.finding("trivy", "CVE-X", loc, "HIGH")]
    def review_for(day):
        return summary.accepted_review(current, [acceptance("CVE-X", loc, day)], TODAY)
    assert review_for("2026-10-25")["due_soon"] == 1 and review_for("2026-10-25")["past_review"] == 0
    assert review_for("2026-10-28")["due_soon"] == 1
    assert review_for("2026-10-29")["attention"] is False
    assert review_for("2026-10-24")["past_review"] == 1
    quiet = review_for("2026-12-31")
    assert quiet["attention"] is False and quiet["items"] == []
    # Non-trivy acceptances are date-checked by rule id only; a location (file path) never leaks.
    semgrep = summary.accepted_review([], [acceptance("weak-ssl", "src/Secret.java:9", "2026-10-26", "semgrep")], TODAY)
    assert semgrep["due_soon"] == 1 and "Secret" not in json.dumps(semgrep)


def test_accepted_review_never_fails_the_gate_but_past_review_still_does(tmp_path):
    reports, output = tmp_path / "reports", tmp_path / "output"
    reports.mkdir()
    (reports / "manifest.json").write_text(json.dumps([dict(tool="trivy", file="trivy-fs.json", target="reg/img")]))
    (reports / "trivy-fs.json").write_text(json.dumps(trivy_report(vuln("CVE-2026-00001", "pkg", FixedVersion="2"))))
    (reports / "trivy-fs.json.exit").write_text("0")
    baseline = tmp_path / "baseline.yaml"
    soon = (date.today() + timedelta(days=1)).isoformat()
    baseline.write_text(json.dumps(dict(accepted=[acceptance("CVE-2026-00001", "reg/img: pkg", soon)])))
    args = ["--reports", str(reports), "--baseline", str(baseline), "--output", str(output)]
    assert summary.main(args) == 0
    result = json.loads((output / "summary.json").read_text())
    assert result["passed"] is True and result["blocking"] == 0
    assert result["accepted_review"]["attention"] is True
    assert result["accepted_review"]["fix_available"] == 1 and result["accepted_review"]["due_soon"] == 1
    assert "2" not in {i["id"] for i in result["accepted_review"]["items"]}
    # Past review: the existing expiry rule stops suppressing the finding, so the gate blocks, as before.
    late = (date.today() - timedelta(days=1)).isoformat()
    baseline.write_text(json.dumps(dict(accepted=[acceptance("CVE-2026-00001", "reg/img: pkg", late)])))
    assert summary.main(args) == 1
    result = json.loads((output / "summary.json").read_text())
    assert result["blocking"] == 1 and result["accepted_review"]["past_review"] == 1


def test_gate_summary_validation_ignores_accepted_review():
    result = summary.summarize([], [], [])
    result["accepted_review"] = dict(attention=True, items=[])
    assert "accepted_review" not in host.safe_summary(json.dumps(result))


def test_projection_discards_snippets_and_deduplicates_tree_history():
    leaks = [{"RuleID": "synthetic", "File": "src/example.txt", "Secret": "invented fixture value",
              "Match": "invented fixture value"}]
    findings = summary.project("gitleaks", leaks)
    assert "fixture value" not in json.dumps(findings)
    assert summary.summarize(findings + findings, [], [])["counts"]["gitleaks"]["HIGH"]["new"] == 1
    semgrep = {"results": [{"check_id": "synthetic", "path": "src/example.java", "start": {"line": 7},
                           "extra": {"severity": "ERROR", "lines": "private source", "message": "private source"}}]}
    assert "private source" not in json.dumps(summary.project("semgrep", semgrep))


def test_trivy_and_zap_projections():
    report = {"SchemaVersion": 2, "Results": [{"Target": "package-lock.json", "Vulnerabilities": [
        {"VulnerabilityID": "CVE-2026-00001", "PkgName": "synthetic", "Severity": "HIGH"}]}]}
    assert summary.project("trivy", report)[0]["location"] == "package-lock.json: synthetic"
    zap = {"site": [{"alerts": [{"pluginid": "123", "riskcode": "3", "instances": [{"uri": "private"}]}]}]}
    assert summary.project("zap", zap)[0]["location"] == "internal-ui"


@pytest.mark.parametrize("payload", ["not json", "null", "{}", '{"results":[],"errors":[{}]}'])
def test_malformed_input_is_fail_closed(tmp_path, payload):
    reports, output = tmp_path / "reports", tmp_path / "output"
    reports.mkdir()
    (reports / "manifest.json").write_text(json.dumps([dict(tool="semgrep", file="semgrep.json")]))
    (reports / "semgrep.json").write_text(payload)
    (reports / "semgrep.json.exit").write_text("0")
    baseline = tmp_path / "baseline.yaml"
    baseline.write_text('{"accepted":[]}')
    assert summary.main(["--reports", str(reports), "--baseline", str(baseline), "--output", str(output)]) == 1
    assert json.loads((output / "summary.json").read_text())["scan_errors"] > 0


def test_failed_scan_does_not_report_false_fixes():
    assert summary.summarize([], [row()], [], failures=["semgrep"])["counts"]["semgrep"]["HIGH"]["fixed"] == 0


def test_namespace_manifests_are_restricted_and_digest_pinned():
    items = manifests.manifests(config())["items"]
    assert any(x["kind"] == "Namespace" and x["metadata"]["name"] == "ui2-security" for x in items)
    policy = next(x for x in items if x["kind"] == "NetworkPolicy")
    assert policy["spec"]["policyTypes"] == ["Ingress", "Egress"]
    assert policy["spec"]["ingress"] == [] and len(policy["spec"]["egress"]) == 3
    assert not any(x["kind"] in ("Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding") for x in items)
    crons = [x for x in items if x["kind"] == "CronJob"]
    assert [c["metadata"]["name"] for c in crons] == ["security-daily", "security-dast"]
    assert crons[0]["spec"]["schedule"] == "30 2 * * *"
    for c in crons:
        assert c["spec"]["timeZone"] == "Europe/Istanbul"
    jobs = [c["spec"]["jobTemplate"] for c in crons]
    jobs += [manifests.job(config(), "gate", manifests.REGISTRY + "/app@sha256:" + "b" * 64, "c" * 40)]
    pods = [j["spec"]["template"]["spec"] for j in jobs] + [manifests.loader(config())["spec"]]
    for pod in pods:
        assert pod["automountServiceAccountToken"] is False
        assert pod["securityContext"]["runAsNonRoot"] is True
        assert not pod.get("hostNetwork")
        for container in pod.get("initContainers", []) + pod["containers"]:
            assert "@sha256:" in container["image"]
            security = container["securityContext"]
            assert security["readOnlyRootFilesystem"] is True
            assert security["allowPrivilegeEscalation"] is False
            assert not security.get("privileged")
            assert security["capabilities"]["drop"] == ["ALL"]
            if container["name"] != "loader":
                assert all(m["readOnly"] for m in container["volumeMounts"] if m["name"] == "source")


def test_unpinned_images_and_missing_gate_commit_refused():
    bad = config()
    bad["images"]["trivy"] = "aquasec/trivy:latest"
    with pytest.raises(ValueError):
        manifests.manifests(bad)
    with pytest.raises(ValueError):
        manifests.job(config(), "gate")


@pytest.mark.parametrize("condition,passed,raises", [("Complete", True, False), ("Complete", False, True), ("Failed", True, True)])
def test_gate_fake_job(condition, passed, raises):
    result = summary.summarize([] if passed else [row()], [], [])
    get_job = lambda: {"status": {"conditions": [{"type": condition, "status": "True"}]}}
    if raises:
        with pytest.raises(RuntimeError, match="refused"):
            host.wait_gate(get_job, lambda: json.dumps(result))
    else:
        host.wait_gate(get_job, lambda: json.dumps(result))


def test_gate_timeout_and_missing_summary_refuse():
    with pytest.raises(RuntimeError, match="timed out"):
        host.wait_gate(lambda: {}, lambda: "", timeout=0)
    with pytest.raises(RuntimeError, match="malformed"):
        host.wait_gate(lambda: {"status": {"conditions": [{"type": "Complete", "status": "True"}]}}, lambda: "{}")


def test_gate_rejects_inconsistent_pass_and_strips_unknown_content():
    result = summary.summarize([row()], [], [])
    result.update(passed=True, blocking=0)
    with pytest.raises(RuntimeError, match="malformed"):
        host.safe_summary(json.dumps(result))
    result = summary.summarize([], [], [])
    result["raw_contents"] = "synthetic private content"
    assert "private content" not in json.dumps(host.safe_summary(json.dumps(result)))


def test_finalize_retention_sbom_and_counts_only_notification(tmp_path, monkeypatch, capsys):
    from datetime import datetime, timedelta, timezone
    import os
    import security_run as runner
    root, work, source = (tmp_path / n for n in ("reports", "work", "source"))
    for path in (root, work, source):
        path.mkdir()
    monkeypatch.setattr(runner, "ROOT", root)
    monkeypatch.setattr(runner, "WORK", work)
    monkeypatch.setattr(runner, "SOURCE", source)
    monkeypatch.setenv("SCAN_MODE", "daily")
    commit = "a" * 40
    (source / "current").write_text(commit)
    (source / commit / ".git").mkdir(parents=True)
    (source / commit / "security").mkdir()
    (source / commit / "security/baseline.yaml").write_text('{"accepted":[]}')
    (source / "images.json").write_text(json.dumps(dict(generated_at=datetime.now(timezone.utc).isoformat(),
        images=[manifests.REGISTRY + "/app@sha256:" + "b" * 64])))
    # Tests must not leave the process-wide restrictive umask changed.
    mask = os.umask(0o077)
    try:
        runner.prepare()
        manifest = json.loads((work / "manifest.json").read_text())
        for item in manifest:
            payload = {"results": []} if item["tool"] == "semgrep" else [] if item["tool"] == "gitleaks" else {"SchemaVersion": 2, "Results": []}
            (work / item["file"]).write_text(json.dumps(payload))
            (work / (item["file"] + ".exit")).write_text("0")
        (work / "sbom-0.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
        (work / "sbom-0.json.exit").write_text("0")
        expired = root / (date.today() - timedelta(days=91)).isoformat()
        expired.mkdir()
        assert runner.finish() == 0
        assert not expired.exists()
        notification = next((root / "notifications").glob("*.json"))
        assert notification.stat().st_mode & 0o777 == 0o640
        assert set(json.loads(notification.read_text())) == {"schema_version", "generated_at", "counts", "scan_errors", "blocking", "passed", "accepted_review"}
        assert json.loads(capsys.readouterr().out)["passed"] is True
        # A missing SBOM invalidates an otherwise clean scan.
        (work / "sbom-0.json").unlink()
        assert runner.finish() == 1
    finally:
        os.umask(mask)


def test_mirror_rejects_mutable_reference_before_cluster_contact(monkeypatch):
    import mirror_security_images as mirror
    monkeypatch.setattr(mirror, "command", lambda *a, **kw: pytest.fail("no cluster contact"))
    with pytest.raises(ValueError):
        mirror.mirror("trivy", "aquasec/trivy:latest", "http://192.0.2.10:3128")


@pytest.mark.parametrize("roles,called", [(["role:viewer", "role:replay_viewer"], True), (["role:security_admin"], False)])
def test_dast_uses_read_only_session_and_baseline_without_logging_cookie(tmp_path, monkeypatch, roles, called):
    import io
    import os
    from types import SimpleNamespace
    import security_dast as dast
    monkeypatch.setattr(dast, "WORK", tmp_path)
    monkeypatch.setattr(dast, "CONFIG", tmp_path / "session.properties")
    token = tmp_path / "machine-input"
    token.write_text("synthetic-machine-input")
    monkeypatch.setattr(dast, "TOKEN", token)
    cookie = "a" * 43
    requests, commands = [], []

    class Response(io.BytesIO):
        status = 200
        headers = {"Set-Cookie": "ui2_session=" + cookie + "; HttpOnly"}

    def open_request(request, timeout):
        requests.append(request)
        value = {"csrf_token": "synthetic"} if len(requests) == 1 else {"authenticated": True, "role_tokens": roles}
        return Response(json.dumps(value).encode())

    def scan(command, **kwargs):
        commands.append(command)
        assert command[0] == "zap-baseline.py" and "zap-full-scan.py" not in command
        assert cookie not in json.dumps(command)
        assert "spider.processForm=false" in dast.CONFIG.read_text()
        assert "spider.postForm=false" in dast.CONFIG.read_text()
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(dast.urllib.request, "build_opener", lambda *args: SimpleNamespace(open=open_request))
    monkeypatch.setattr(dast.subprocess, "run", scan)
    mask = os.umask(0o077)
    try:
        assert dast.main() == 0
    finally:
        os.umask(mask)
    assert bool(commands) is called
    assert (tmp_path / "zap.json.exit").read_text() == ("0" if called else "3")
    assert not dast.CONFIG.exists()
    assert requests[0].get_method() == "POST"
    assert dast.NoRedirect().redirect_request(None, None, 302, "", {}, "http://example.invalid") is None


def test_build_gates_before_first_rollout():
    source = (manifests.ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    assert source.index('IMAGE_DIGEST="') < source.index('tools/security/security_host.py" gate') < source.index('python3 "$REPO/tools/delivery/module_deploy.py" --targets')


def test_ship_skip_requires_reason_before_any_git_action(monkeypatch, tmp_path):
    import standalone_orchestrate as sa
    import argparse
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: pytest.fail("Git must not run for an invalid bypass"))
    for reason in ("", "   ", "line\nbreak"):
        with pytest.raises(SystemExit, match="requires"):
            sa.cmd_ship(argparse.Namespace(skip_security=reason, task="synthetic-task", branch=None))


@pytest.mark.parametrize("missing", [False, True])
def test_orchestrator_stops_before_configuration_sync_on_gate_failure(tmp_path, monkeypatch, capsys, missing):
    import standalone_orchestrate as sa
    from types import SimpleNamespace
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    result = summary.summarize([row()], [], [])
    calls = []

    def deployment(argv, **kwargs):
        calls.append(argv)
        assert argv == ["bash", "tools/delivery/hosta_deploy.sh"]
        kwargs["stdout"].write(json.dumps({"snapshot": "20261005T010000000000Z_" + "a" * 12}) + "\n")
        kwargs["stdout"].write("" if missing else json.dumps(host.safe_summary(json.dumps(result))) + "\n")
        return SimpleNamespace(returncode=0 if missing else 5)

    monkeypatch.setattr(sa.subprocess, "run", deployment)
    with pytest.raises(SystemExit, match="security gate success missing" if missing else "deploy failed"):
        sa._deploy()
    assert len(calls) == 1
    if not missing:
        assert json.loads(capsys.readouterr().out.splitlines()[-1])["counts"] == result["counts"]


def test_split_gate_keeps_source_before_digest_and_one_summary():
    name = "security-gate-" + "a" * 12
    pod = manifests.job(config(), "gate", commit="b" * 40, gate_name=name)["spec"]["template"]["spec"]
    assert [c["name"] for c in pod["initContainers"]] == [
        "prepare", "semgrep", "gitleaks", "history", "trivy", "await-image", "trivy-image"]
    assert pod["initContainers"][4]["command"][-1] == "trivy-source"
    assert [c["name"] for c in pod["containers"]] == ["summary"]
    assert pod["automountServiceAccountToken"] is False
    for c in pod["initContainers"] + pod["containers"]:
        scripts = next(m for m in c["volumeMounts"] if m["name"] == "scripts")
        assert scripts["subPath"] == "b" * 40 + "/tools/security" and scripts["readOnly"]
        assert scripts["mountPath"] == "/tools/security"
        assert c["command"][1].startswith(scripts["mountPath"] + "/")


@pytest.mark.parametrize("failure", [None, "semgrep.json", "gitleaks-tree.json", "gitleaks-history.json",
                                    "trivy-fs.json", "trivy-config.json", "trivy-image-0.json", "sbom-0.json"])
def test_split_gate_combines_same_reports_and_fails_closed(tmp_path, monkeypatch, capsys, failure):
    from datetime import datetime, timezone
    import security_run as runner
    root, work, source = (tmp_path / n for n in ("reports", "work", "source"))
    for path in (root, work, source):
        path.mkdir()
    monkeypatch.setattr(runner, "ROOT", root)
    monkeypatch.setattr(runner, "WORK", work)
    monkeypatch.setattr(runner, "SOURCE", source)
    monkeypatch.setattr(runner.os, "umask", lambda mask: None)
    monkeypatch.setenv("SCAN_MODE", "gate")
    monkeypatch.setenv("EXPECTED_COMMIT", "a" * 40)
    monkeypatch.setenv("GATE_IMAGE", manifests.REGISTRY + "/app@sha256:" + "b" * 64)
    (source / "current").write_text("a" * 40)
    (source / ("a" * 40) / ".git").mkdir(parents=True)
    baseline = source / ("a" * 40) / "security/baseline.yaml"
    baseline.parent.mkdir()
    baseline.write_text('{"accepted":[]}')
    image = manifests.REGISTRY + "/app@sha256:" + "b" * 64
    (source / "images.json").write_text(json.dumps(dict(generated_at=datetime.now(timezone.utc).isoformat(), images=[image])))
    runner.prepare()
    original_manifest = (work / "manifest.json").read_text()
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    runner.prepare()
    assert "trivy-image" not in (work / "manifest.json").read_text()
    request = tmp_path / "request"
    request.write_text(json.dumps(dict(commit="a" * 40, image=image)))
    runner.await_image(image_file=request)
    assert (work / "manifest.json").read_text() == original_manifest
    for item in json.loads(original_manifest):
        payload = {"results": []} if item["tool"] == "semgrep" else [] if item["tool"] == "gitleaks" else {"SchemaVersion": 2, "Results": []}
        (work / item["file"]).write_text(json.dumps(payload))
        (work / (item["file"] + ".exit")).write_text("0")
    (work / "sbom-0.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
    (work / "sbom-0.json.exit").write_text("0")
    if failure:
        (work / failure).unlink()
    assert runner.finish() == bool(failure)
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    monkeypatch.delenv("WAIT_FOR_IMAGE")
    runner.prepare()
    assert runner.finish() == bool(failure)
    unsplit = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert set(result) == set(unsplit)
    assert {k: v for k, v in result.items() if k != "generated_at"} == {k: v for k, v in unsplit.items() if k != "generated_at"}
    assert result["passed"] is (failure is None)
    assert not (root / "notifications").exists()
    if failure:
        with pytest.raises(RuntimeError, match="refused"):
            host.wait_gate(lambda: {"status": {"conditions": [{"type": "Failed", "status": "True"}]}}, lambda: json.dumps(result))


def test_split_gate_wait_timeout_and_mismatched_digest_refuse(tmp_path, monkeypatch):
    import security_run as runner
    monkeypatch.setattr(runner, "WORK", tmp_path)
    (tmp_path / "source-start").write_text("0")
    (tmp_path / "source-commit").write_text("a" * 40)
    with pytest.raises(ValueError, match="timed out"):
        runner.await_image(timeout=0, image_file=tmp_path / "missing")
    path = tmp_path / "request"
    path.write_text(json.dumps(dict(commit="b" * 40, image=manifests.REGISTRY + "/app@sha256:" + "c" * 64)))
    with pytest.raises(ValueError, match="mismatch"):
        runner.await_image(image_file=path)


def test_gate_rejects_other_commit_before_publishing_digest(monkeypatch):
    calls = []
    def fake(*args, **kwargs):
        calls.append(args)
        return json.dumps({"metadata": {"annotations": {"nexus/commit": "b" * 40}}})
    monkeypatch.setattr(host, "kubectl", fake)
    with pytest.raises(ValueError, match="mismatch"):
        host.gate(config(), manifests.REGISTRY + "/app@sha256:" + "c" * 64, "a" * 40, "security-gate-" + "d" * 12)
    assert len(calls) == 1 and calls[0][0] == "get"


def test_split_gate_start_and_publish_are_unique_and_digest_is_immutable(monkeypatch, capsys):
    jobs, requests = {}, {}
    def fake(*args, **kwargs):
        if args[0] == "create":
            doc = json.loads(kwargs["input"])
            name = doc["metadata"]["name"]
            if doc["kind"] == "Job":
                doc["metadata"]["uid"] = "synthetic-uid"
                doc["status"] = {"conditions": [{"type": "Complete", "status": "True"}]}
                jobs[name] = doc
            else:
                requests[name] = doc
        elif args[:2] == ("get", "job"):
            return json.dumps(jobs[args[2]])
        elif args[0] == "patch":
            patch = json.loads(args[-1])
            requests[args[2]].update(patch)
        elif args[0] == "logs":
            return json.dumps(summary.summarize([], [], [])) if args[-1] == "summary" else "TIMING security_source 1\nprivate detail\n"
        return ""
    monkeypatch.setattr(host, "kubectl", fake)
    name = host.start_gate(config(), "a" * 40)
    other = host.start_gate(config(), "a" * 40)
    assert name != other
    assert requests[name]["metadata"]["ownerReferences"][0]["uid"] == "synthetic-uid"
    image = manifests.REGISTRY + "/app@sha256:" + "b" * 64
    host.gate(config(), image, "a" * 40, name)
    assert requests[name]["immutable"] is True
    assert json.loads(requests[name]["data"]["request"]) == dict(image=image, commit="a" * 40)
    assert "private detail" not in capsys.readouterr().out


def _trivy_retry(tmp_path, fail_times, attempts=3):
    """Run the shell retry helper of security_scan.sh against a fake `trivy` that fails `fail_times` times."""
    import subprocess
    script = (Path(__file__).resolve().parents[2] / "tools/security/security_scan.sh").read_text()
    helper = script.split("# BEGIN trivy-retry")[1].split("# END trivy-retry")[0]
    counter = tmp_path / "count"
    counter.write_text("0")
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    fake = bin_dir / "trivy"
    fake.write_text(f'#!/bin/sh\nn=$(( $(cat {counter}) + 1 )); echo $n >{counter}\n'
                    f'if [ $n -le {fail_times} ]; then echo "FATAL TOOMANYREQUESTS secret-image-name" >&2; exit 1; fi\n'
                    'echo \'{"SchemaVersion": 2}\'\n')
    fake.chmod(0o755)
    report = tmp_path / "trivy-image-0.json"
    result = subprocess.run(
        ["sh", "-c", helper + f'trivy_try {report} {report}.exit trivy image x'],
        env={"PATH": f"{bin_dir}:/usr/bin:/bin", "TRIVY_ATTEMPTS": str(attempts), "TRIVY_RETRY_SLEEP": "0"},
        capture_output=True, text=True, check=True)
    return result.stdout, int(counter.read_text()), (tmp_path / "trivy-image-0.json.exit").read_text().strip(), report


def test_trivy_scan_retries_transient_failure_then_succeeds(tmp_path):
    out, calls, exit_code, report = _trivy_retry(tmp_path, fail_times=2)
    assert (calls, exit_code) == (3, "0")
    assert json.loads(report.read_text()) == {"SchemaVersion": 2}
    assert out.count("TRIVY_FAILED") == 2 and "class=rate_limit" in out
    assert "secret-image-name" not in out and not Path(str(report) + ".err").exists()


def test_trivy_retry_is_bounded_and_exhaustion_stays_a_scan_error(tmp_path):
    out, calls, exit_code, report = _trivy_retry(tmp_path, fail_times=99)
    assert calls == 3 and exit_code == "1"
    # The recorded failing exit code makes the summary count a scan error: a retry never turns into a pass.
    reports = tmp_path / "reports"
    reports.mkdir()
    (reports / "manifest.json").write_text(json.dumps([dict(tool="trivy", file=report.name, target="x")]))
    (reports / report.name).write_text(report.read_text())
    (reports / (report.name + ".exit")).write_text(exit_code)
    baseline = tmp_path / "baseline.yaml"
    baseline.write_text('{"accepted":[]}')
    assert summary.main(["--reports", str(reports), "--baseline", str(baseline), "--output", str(tmp_path / "out")]) == 1
    assert json.loads((tmp_path / "out/summary.json").read_text())["scan_errors"] == 1


def test_trivy_first_try_success_does_not_retry(tmp_path):
    out, calls, exit_code, _ = _trivy_retry(tmp_path, fail_times=0)
    assert (calls, exit_code, out) == (1, "0", "")


def test_every_trivy_invocation_goes_through_the_retry_helper():
    text = (Path(__file__).resolve().parents[2] / "tools/security/security_scan.sh").read_text()
    body = text.split("# END trivy-retry")[1]
    calls = [l.strip() for l in body.splitlines() if " trivy " in " " + l.strip() + " " and not l.strip().startswith("#")]
    assert len(calls) == 4 and all(l.startswith("trivy_try ") for l in calls)
