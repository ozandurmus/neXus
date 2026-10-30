"""Packet Analysis engine image: Containerfile shape, its build Job and its gate scan. Local doubles only."""
from datetime import datetime, timezone
import copy
import json
from pathlib import Path
import re
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/delivery"))
sys.path.insert(0, str(ROOT / "tools/e2e"))
sys.path.insert(0, str(ROOT / "tools/security"))
import hosta_packet_analysis_image as pa  # noqa: E402
import security_host as host  # noqa: E402
import security_manifests as manifests  # noqa: E402

COMMIT = "a" * 40
MAIN = pa.REPOSITORY + "@sha256:" + "b" * 64
PA = pa.REPOSITORY + "@sha256:" + "c" * 64
CONTAINERFILE = ROOT / "ui2/Containerfile.packet-analysis"
DESIGN = ROOT / "docs/design/PACKET_ANALYSIS_ENGINE_IMAGE.md"
INSTRUCTIONS = {"FROM", "ARG", "RUN", "COPY", "ENV", "USER", "WORKDIR", "LABEL", "ENTRYPOINT", "CMD", "EXPOSE"}


def instructions(text):
    """(keyword, rest) per instruction: comments dropped, backslash continuations joined."""
    out, pending = [], ""
    for line in text.splitlines():
        if not pending and (not line.strip() or line.lstrip().startswith("#")):
            continue
        pending += line.rstrip()
        if pending.endswith("\\"):
            pending = pending[:-1] + " "
            continue
        keyword, _, rest = pending.strip().partition(" ")
        out.append((keyword, rest.strip()))
        pending = ""
    assert not pending, "dangling line continuation"
    return out


# --- the Containerfile ------------------------------------------------------------------------------------------


def test_containerfile_parses_into_known_instructions_and_named_stages():
    steps = instructions(CONTAINERFILE.read_text())
    assert {k for k, _ in steps} <= INSTRUCTIONS
    froms = [rest for k, rest in steps if k == "FROM"]
    assert len(froms) == 2
    assert re.fullmatch(r"quay\.io/rockylinux/rockylinux:[0-9.]+@sha256:[a-f0-9]{64} AS tshark-build", froms[0])
    # The final stage is the main image by digest, passed in; checked inside the stage as well.
    assert froms[1] == "${SERVICE_IMAGE}"
    assert steps[0] == ("ARG", "SERVICE_IMAGE")
    stages = {rest.split(" AS ")[1] for rest in froms if " AS " in rest}
    for k, rest in steps:
        if k == "COPY" and rest.startswith("--from="):
            assert rest.split()[0][len("--from="):] in stages
    final = steps[[i for i, (k, _) in enumerate(steps) if k == "FROM"][-1]:]
    assert ("ARG", "SERVICE_IMAGE") in final
    assert any("registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:*) ;;" in rest for k, rest in final if k == "RUN")


def test_every_download_is_pinned_and_nothing_moves():
    text = CONTAINERFILE.read_text()
    assert "latest" not in text.lower()
    version = re.search(r"WIRESHARK_VERSION=(\S+)", text)[1]
    digest = re.search(r"WIRESHARK_SHA256=([a-f0-9]{64})", text)[1]
    assert version == "4.6.9"
    assert 'sha256sum -c -' in text and "${WIRESHARK_SHA256}" in text
    # Each RPM fetched by URL has its SHA-256 next to it, and is signature checked.
    rpms = re.findall(r"/Packages/./([A-Za-z0-9_.+-]+\.rpm)", text)
    assert sorted(rpms) == ["c-ares-1.19.1-2.el9_4.x86_64.rpm", "libbrotli-1.0.9-10.el9_8.1.x86_64.rpm"]
    for rpm in rpms:
        assert re.search(r"'[a-f0-9]{64}  " + re.escape(rpm) + "'", text)
    assert "rpm -K" in text
    # Build packages: the frozen vault only, every top-level package by exact version.
    assert "rm -f /etc/yum.repos.d/*.repo" in text and "/vault/rocky/9.7/" in text and "gpgcheck=1" in text
    install = re.search(r"dnf -y [^\n]*install \\\n(.*?)\n \&\& dnf clean all", text, re.S)[1]
    packages = install.replace("\\", " ").split()
    assert packages and all(re.fullmatch(r"[a-z0-9+_-]+-[0-9][^-\s]*-[0-9][^-\s]*", p) for p in packages), packages
    # The doc records the same pins the build uses.
    design = DESIGN.read_text()
    assert version in design and digest in design


def test_tshark_only_without_capture_privilege_or_package_manager_in_the_final_stage():
    text = CONTAINERFILE.read_text()
    assert "-DBUILD_dumpcap=OFF" in text and "-DBUILD_tshark=ON" in text and "-DBUILD_wireshark=OFF" in text
    assert "-DENABLE_PCAP=OFF" in text and "-DENABLE_CAP=OFF" in text
    commands = "\n".join(rest for _, rest in instructions(text))
    assert not re.search(r"\bsetcap\b|chmod [^\n]*[ugo]*\+s|chmod [0-7]?[4-7][0-7]{3}\b", commands)
    assert text.count('test -z "$(find /opt/wireshark -perm /6000)"') == 2
    final = text.rsplit("\nFROM ", 1)[1]
    assert not re.search(r"\b(?:dnf|microdnf|yum|curl)\b", final)
    assert "rpm -i --nosignature /tmp/pa-rpms/*.rpm" in final and "rm -rf /tmp/pa-rpms" in final
    # FS-6 and the closed writable set of the main image are kept.
    assert re.findall(r"^USER +(\S+)", text, re.M)[-1] == "185"
    assert "chmod 0770 /app/tmp /app/home" in final
    assert "ENTRYPOINT" not in final and "CMD" not in final
    assert "/usr/local/bin/tshark" in final


def test_tshark_never_enters_the_main_image():
    main = (ROOT / "ui2/Containerfile").read_text().lower()
    assert "tshark" not in main and "wireshark" not in main


def test_containerfile_carries_no_token_the_ui2_architecture_scan_forbids():
    # Ui2ArchitectureTest.dir10 scans every non-.md file under ui2/ for these (case-sensitive) tokens.
    text = CONTAINERFILE.read_text()
    for token in ("main." + "py", "pyth" + "on", "conso" + "le/", "_runner" + ".py", "_collector" + ".py"):
        assert token not in text
    assert not re.search(r"docker |podman |buildah|nerdctl|/var/run/docker\.sock", text)


# --- the build Job ----------------------------------------------------------------------------------------------


def template():
    return dict(metadata=dict(name="ui2-image-build"), spec=dict(backoffLimit=0, template=dict(metadata={}, spec=dict(
        priorityClassName="system-cluster-critical",
        containers=[dict(name="builder", args=[
            "--context=dir:///workspace", "--dockerfile=/workspace/ui2/Containerfile",
            "--destination=" + pa.REPOSITORY + ":$(UI2_IMAGE_TAG)", "--digest-file=/workspace/image-digest.txt",
            "--insecure", "--skip-tls-verify", "--cache=false", "--image-download-retry=5",
            "--cache-repo=" + pa.CACHE_REPO, "--cache-copy-layers", "--snapshot-mode=redo"],
            env=[dict(name="UI2_IMAGE_TAG", valueFrom={}), dict(name="HTTPS_PROXY", value="http://192.0.2.10:3128")],
            resources={"limits": {"cpu": "3", "memory": "4Gi"}},
            volumeMounts=[dict(name="context", mountPath="/workspace"), dict(name="corp-ca", mountPath="/kaniko/ssl/certs")])],
        volumes=[dict(name="context", persistentVolumeClaim=dict(claimName="ui2-build-context")),
                 dict(name="corp-ca", configMap=dict(name="corp-ca"))]))))


def fake_cluster(monkeypatch, context_commit=COMMIT, existing=None):
    calls, created = [], []

    def kubectl(*args, **kwargs):
        calls.append(args)
        if args[:2] == ("get", "configmap"):
            return json.dumps(dict(data=dict(commit=context_commit)))
        if "--dry-run=client" in args:
            assert str(args[args.index("-f") + 1]).endswith("build-job-proxy.yaml")
            return json.dumps(template())
        if args[0] == "create":
            created.append(json.loads(kwargs["input"]))
        if args[:2] == ("get", "job"):
            return json.dumps(existing() if callable(existing) else existing or {})
        return ""
    monkeypatch.setattr(pa.builds, "kubectl", kubectl)
    return calls, created


def test_job_inherits_host_proxy_and_ca_and_builds_the_sibling_file_from_the_main_digest(monkeypatch):
    calls, created = fake_cluster(monkeypatch)
    pa.start(COMMIT, ROOT, MAIN)
    job = created[0]
    assert job["metadata"]["name"] == pa.NAME
    assert job["metadata"]["annotations"] == {"nexus/commit": COMMIT, "nexus/service-image": MAIN}
    pod = job["spec"]["template"]["spec"]
    assert pod["volumes"] == template()["spec"]["template"]["spec"]["volumes"]
    builder = pod["containers"][0]
    assert builder["volumeMounts"] == template()["spec"]["template"]["spec"]["containers"][0]["volumeMounts"]
    assert builder["env"] == [dict(name="HTTPS_PROXY", value="http://192.0.2.10:3128"),
                              dict(name="UI2_IMAGE_TAG", value=COMMIT[:12])]
    args = builder["args"]
    for kept in ("--context=dir:///workspace", "--insecure", "--skip-tls-verify", "--cache-copy-layers",
                 "--cache-repo=" + pa.CACHE_REPO):
        assert kept in args
    assert "--dockerfile=/workspace/ui2/Containerfile.packet-analysis" in args
    assert "--destination=" + pa.REPOSITORY + ":" + COMMIT[:12] + "-pa" in args
    assert "--build-arg=SERVICE_IMAGE=" + MAIN in args
    assert "--cache=true" in args and "--cache=false" not in args
    assert args[-1] == "--digest-file=/dev/termination-log"
    for owned in ("--dockerfile=", "--destination=", "--digest-file=", "--cache="):
        assert sum(a.startswith(owned) for a in args) == 1, owned
    assert builder["resources"]["limits"]["memory"] == "8Gi"
    assert builder["terminationMessagePolicy"] == "File"
    assert ("delete", "job", pa.NAME, "--ignore-not-found", "--wait=true") in calls


def test_a_template_without_a_cache_repository_gets_the_build_cache():
    args = pa.builder_args(["--context=dir:///workspace", "--cache=false"], COMMIT, MAIN)
    assert "--cache-repo=" + pa.CACHE_REPO in args


@pytest.mark.parametrize("base", ["", pa.REPOSITORY + ":latest", pa.REPOSITORY + ":" + COMMIT[:12],
                                  "quay.io/other/image@sha256:" + "b" * 64, MAIN + "\n"])
def test_a_moving_or_foreign_base_is_refused_before_any_cluster_call(monkeypatch, base):
    calls, _ = fake_cluster(monkeypatch)
    with pytest.raises(ValueError):
        pa.start(COMMIT, ROOT, base)
    with pytest.raises(ValueError):
        pa.build(COMMIT, ROOT, base)
    assert calls == []


def test_context_of_another_commit_is_refused_before_build_mutation(monkeypatch):
    calls, created = fake_cluster(monkeypatch, context_commit="d" * 40)
    with pytest.raises(ValueError, match="context"):
        pa.start(COMMIT, ROOT, MAIN)
    assert created == [] and len(calls) == 1


def completed(base=MAIN, commit=COMMIT, state="Complete"):
    return dict(metadata=dict(uid="synthetic-job", annotations={"nexus/commit": commit, "nexus/service-image": base},
                              creationTimestamp=datetime.now(timezone.utc).isoformat()),
                status=dict(conditions=[dict(type=state, status="True")]),
                spec=dict(template=dict(spec=dict(containers=[dict(name="builder")]))))


@pytest.mark.parametrize("kind", ["same", "other-base", "other-commit", "failed", "missing"])
def test_build_reuses_only_a_fresh_success_for_this_commit_and_base(monkeypatch, kind):
    existing = {"same": completed(), "other-base": completed(base=pa.REPOSITORY + "@sha256:" + "e" * 64),
                "other-commit": completed(commit="f" * 40), "failed": completed(state="Failed"), "missing": {}}[kind]
    state = {"job": existing}
    rebuilt = []
    monkeypatch.setattr(pa, "start", lambda commit, repo, base: (rebuilt.append(base), state.update(job=completed())))
    monkeypatch.setattr(pa.builds, "kubectl", lambda *args, **kwargs: json.dumps(
        state["job"] if args[:2] == ("get", "job") else dict(items=[dict(
            metadata=dict(ownerReferences=[dict(uid="synthetic-job")]),
            status=dict(containerStatuses=[dict(name="builder", state=dict(terminated=dict(
                exitCode=0, message=PA.split("@")[1])))]))])))
    assert pa.build(COMMIT, ROOT, MAIN) == PA.split("@")[1]
    assert rebuilt == ([] if kind == "same" else [MAIN])


@pytest.mark.parametrize("kind", ["failed", "replaced", "timeout"])
def test_build_never_returns_a_digest_for_a_failed_replaced_or_unfinished_job(monkeypatch, kind):
    job = {"failed": completed(state="Failed"), "replaced": completed(base=pa.REPOSITORY + "@sha256:" + "e" * 64),
           "timeout": dict(completed(), status={})}[kind]
    monkeypatch.setattr(pa, "start", lambda *args: None)
    monkeypatch.setattr(pa.builds, "kubectl", lambda *args, **kwargs: json.dumps(job))
    elapsed = [0]
    with pytest.raises(RuntimeError):
        pa.build(COMMIT, ROOT, MAIN, timeout=30, sleep=lambda s: elapsed.__setitem__(0, elapsed[0] + s),
                 monotonic=lambda: elapsed[0])


# --- the gate scans both images ---------------------------------------------------------------------------------


def config():
    return dict(node_name="synthetic-node", proxy_ip="192.0.2.10", proxy_port=3128,
                registry_ip="192.0.2.11", service_ip="192.0.2.12",
                images={t: manifests.REGISTRY + "/security/" + t + "@sha256:" + "a" * 64
                        for t in ("semgrep", "gitleaks", "trivy", "zap")})


def test_split_gate_publishes_every_image_and_keeps_the_single_image_request_shape(monkeypatch):
    requests = {}

    def kubectl(*args, **kwargs):
        if args[:2] == ("get", "job"):
            return json.dumps({"metadata": {"annotations": {"nexus/commit": COMMIT}},
                               "status": {"conditions": [{"type": "Complete", "status": "True"}]}})
        if args[0] == "patch":
            requests.update(json.loads(json.loads(args[-1])["data"]["request"]))
        if args[0] == "logs":
            import security_summary as summary
            return json.dumps(summary.summarize([], [], [])) if args[-1] == "summary" else ""
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    name = "security-gate-" + "d" * 12
    host.gate(config(), [MAIN, PA], COMMIT, name)
    assert requests == dict(image=MAIN, images=[MAIN, PA], commit=COMMIT)
    requests.clear()
    host.gate(config(), MAIN, COMMIT, name)
    assert requests == dict(image=MAIN, commit=COMMIT)
    with pytest.raises(ValueError):
        host.gate(config(), [MAIN, PA.split("@")[0] + ":latest"], COMMIT, name)


def prepared_work(tmp_path, monkeypatch):
    import security_run as runner
    root, work, source = (tmp_path / n for n in ("reports", "work", "source"))
    for path in (root, work, source):
        path.mkdir()
    for name, value in (("ROOT", root), ("WORK", work), ("SOURCE", source)):
        monkeypatch.setattr(runner, name, value)
    monkeypatch.setattr(runner.os, "umask", lambda mask: None)
    monkeypatch.setenv("SCAN_MODE", "gate")
    monkeypatch.setenv("EXPECTED_COMMIT", COMMIT)
    (source / "current").write_text(COMMIT)
    (source / COMMIT / ".git").mkdir(parents=True)
    (source / "images.json").write_text(json.dumps(dict(generated_at=datetime.now(timezone.utc).isoformat(), images=[MAIN])))
    return runner, work


def test_await_image_scans_and_sboms_every_requested_image_in_prepare_order(tmp_path, monkeypatch):
    runner, work = prepared_work(tmp_path, monkeypatch)
    monkeypatch.setenv("GATE_IMAGE", " ".join([PA, MAIN]))
    runner.prepare()
    unsplit_manifest, unsplit_images = (work / "manifest.json").read_text(), (work / "images").read_text()
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    runner.prepare()
    request = tmp_path / "request"
    request.write_text(json.dumps(dict(commit=COMMIT, image=MAIN, images=[MAIN, PA])))
    runner.await_image(image_file=request)
    assert (work / "manifest.json").read_text() == unsplit_manifest
    assert (work / "images").read_text() == unsplit_images == "\n".join(sorted([MAIN, PA])) + "\n"
    files = [m["file"] for m in json.loads(unsplit_manifest) if m["file"].startswith("trivy-image-")]
    assert files == ["trivy-image-0.json", "trivy-image-1.json"]


@pytest.mark.parametrize("request_body", [
    dict(commit=COMMIT, image=MAIN, images=[PA, MAIN]),            # first entry is not the main image
    dict(commit=COMMIT, image=MAIN, images=[MAIN, MAIN]),          # duplicate
    dict(commit=COMMIT, image=MAIN, images=[MAIN, pa.REPOSITORY + ":latest"]),
    dict(commit=COMMIT, image=MAIN, images=[]),
    dict(commit=COMMIT, image=MAIN, images=MAIN),
    dict(commit="e" * 40, image=MAIN, images=[MAIN, PA]),
])
def test_await_image_refuses_a_malformed_multi_image_request(tmp_path, monkeypatch, request_body):
    runner, work = prepared_work(tmp_path, monkeypatch)
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    runner.prepare()
    request = tmp_path / "request"
    request.write_text(json.dumps(request_body))
    with pytest.raises(ValueError):
        runner.await_image(image_file=request)


def test_scan_shell_scans_and_sboms_both_lines(tmp_path):
    import os
    import subprocess
    work, source, cache, tools = (tmp_path / p for p in ("work", "source", "cache", "bin"))
    for p in (work, source, cache, tools):
        p.mkdir()
    (source / COMMIT).mkdir()
    (work / "source-commit").write_text(COMMIT)
    (work / "images").write_text(MAIN + "\n" + PA + "\n")
    stub = tools / "trivy"
    stub.write_text('#!/bin/sh\nprintf "%s %s\\n" "$1" "$(eval echo \\${$#})" >> "$TEST_CALLS"\nprintf "{}\\n"\n')
    stub.chmod(0o755)
    script = (ROOT / "tools/security/security_scan.sh").read_text()
    for old, new in (("/work", work), ("/source", source), ("/cache", cache)):
        script = script.replace(old, str(new))
    calls = tmp_path / "calls"
    result = subprocess.run(["sh", "-c", script, "scan", "trivy-image"], capture_output=True, text=True,
                            env={**os.environ, "PATH": str(tools) + ":" + os.environ["PATH"], "TEST_CALLS": str(calls)})
    assert result.returncode == 0, result.stderr
    assert calls.read_text().splitlines() == ["image " + MAIN, "image " + MAIN, "image " + PA, "image " + PA]
    for i in (0, 1):
        assert (work / f"trivy-image-{i}.json").exists() and (work / f"sbom-{i}.json").exists()


def test_build_script_builds_the_analysis_image_from_the_main_digest_before_the_gate():
    source = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    digest = source.index('IMAGE_DIGEST="')
    build = source.index('hosta_packet_analysis_image.py" build')
    gate = source.index('tools/security/security_host.py" gate')
    rollout = source.index('python3 "$REPO/tools/delivery/module_deploy.py" --targets')
    assert digest < build < gate < rollout
    assert '--service-image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST"' in source
    assert '${PA_IMAGE:+--image "$PA_IMAGE"}' in source
    observer = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    # The deploy observer watches the main build pods only; the analysis build Job is named apart from them.
    assert not pa.NAME.startswith("ui2-image-build-")
    assert 'export NEXUS_BUILD_PACKET_ANALYSIS_IMAGE="$PA_BUILD"' in observer
