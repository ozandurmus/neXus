"""2026-11-23 ship deploy speed (PO 2026-11-23: cut the deploy time): side images built at the same time, the security
source snapshot beside the build, a one-second loader grace and faster side-image build polls. Module rollout is
unchanged (one module per step; the parallel rollout was removed after the Neon review, PO 2026-11-23). Local doubles
only: no cluster, no host, no device."""
import io
import json
from pathlib import Path
import re
import sys
import threading

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import hosta_packet_analysis_image as pa  # noqa: E402
import hosta_script_exec_image as sx  # noqa: E402
import hosta_side_images as side  # noqa: E402
import module_deploy as deploy  # noqa: E402

COMMIT = "a" * 40
MAIN = side.REPOSITORY + "@sha256:" + "b" * 64
PA_DIGEST = "sha256:" + "c" * 64
SX_DIGEST = "sha256:" + "d" * 64
TIMING = re.compile(r"TIMING [a-z_0-9]+ [0-9]+")   # what hosta_deploy.sh forwards (^TIMING [a-z_0-9]+ [0-9]+$)


# ------------------------------------------------------------------------------------------------- side images

def builders(pa_result=PA_DIGEST, sx_result=SX_DIGEST, together=True):
    """Two fake builders. With `together`, each waits for the other to have started: they can only both finish when
    they run at the same time (a sequential build would break the barrier and fail the test)."""
    barrier = threading.Barrier(2, timeout=5)
    calls = []

    def make(result, name):
        def build(commit, repo, base):
            calls.append((name, commit, base))
            if together:
                barrier.wait()
            if isinstance(result, BaseException):
                raise result
            return result
        return build
    return {"PA_IMAGE": make(pa_result, "pa"), "SX_IMAGE": make(sx_result, "sx")}, calls


def run_side(argv, fakes):
    out, err = io.StringIO(), io.StringIO()
    code = side.main(argv, builders=fakes, out=out, err=err)
    return code, out.getvalue().splitlines(), err.getvalue().splitlines()


BOTH = ["build", "--commit", COMMIT, "--service-image", MAIN, "--packet-analysis", "--script-exec"]


def test_side_images_build_at_the_same_time_from_the_main_digest_and_report_both():
    fakes, calls = builders()
    code, out, err = run_side(BOTH, fakes)
    assert code == 0
    assert out == ["PA_IMAGE=" + side.REPOSITORY + "@" + PA_DIGEST, "SX_IMAGE=" + side.REPOSITORY + "@" + SX_DIGEST]
    assert sorted(calls) == [("pa", COMMIT, MAIN), ("sx", COMMIT, MAIN)]
    # Every step keeps its TIMING line, plus one for the parallel phase; all in the forwarded shape.
    names = [line.split()[1] for line in err if line.startswith("TIMING ")]
    assert sorted(names) == ["packet_analysis_image", "script_exec_image", "side_images"]
    assert all(TIMING.fullmatch(line) for line in err if line.startswith("TIMING "))


def test_a_failed_packet_analysis_image_fails_the_deploy_after_the_other_build_ended():
    fakes, calls = builders(pa_result=RuntimeError("synthetic build failure"))
    code, out, err = run_side(BOTH, fakes)
    assert code == 1                                         # run_build.sh stops (set -e), as for the sequential build
    assert not any(line.startswith("PA_IMAGE=") for line in out)
    assert len(calls) == 2 and any(line.startswith("TIMING script_exec_image ") for line in err)
    assert any(line.startswith("PACKET ANALYSIS IMAGE: build failed or unavailable") for line in err)


def test_a_failed_script_execution_image_is_optional_as_before():
    fakes, _ = builders(sx_result=OSError("synthetic CDN outage"))
    code, out, err = run_side(BOTH, fakes)
    assert code == 0
    assert out == ["PA_IMAGE=" + side.REPOSITORY + "@" + PA_DIGEST]       # no SX_IMAGE= line: run_build.sh drops it
    assert any(line.startswith("SCRIPT EXEC IMAGE: build failed or unavailable") for line in err)


@pytest.mark.parametrize("bad", [None, "sha256:short", "md5:" + "c" * 32, MAIN.split("@")[1]])
def test_a_digest_that_is_not_a_new_pinned_image_counts_as_a_failure(bad):
    fakes, _ = builders(pa_result=bad, together=False)
    code, out, _ = run_side(["build", "--commit", COMMIT, "--service-image", MAIN, "--packet-analysis"], fakes)
    assert code == 1 and out == []


def test_only_the_selected_images_are_built():
    fakes, calls = builders(together=False)
    code, out, err = run_side(["build", "--commit", COMMIT, "--service-image", MAIN, "--script-exec"], fakes)
    assert code == 0 and [c[0] for c in calls] == ["sx"] and out == ["SX_IMAGE=" + side.REPOSITORY + "@" + SX_DIGEST]
    assert not any(line.startswith("TIMING packet_analysis_image") for line in err)
    assert run_side(["build", "--commit", COMMIT, "--service-image", MAIN], fakes)[0] == 64


def test_side_images_forwards_repo_to_both_builders(tmp_path):
    seen = []

    def make(result, name):
        def build(commit, repo, base):
            seen.append((name, repo))
            return result
        return build

    fakes = {"PA_IMAGE": make(PA_DIGEST, "pa"), "SX_IMAGE": make(SX_DIGEST, "sx")}
    code, _, _ = run_side(BOTH, fakes)
    assert code == 0 and sorted(seen) == [("pa", Path("/opt/nexus")), ("sx", Path("/opt/nexus"))]
    seen.clear()
    custom = tmp_path / "custom-repo"
    code, _, _ = run_side(BOTH + ["--repo", str(custom)], fakes)
    assert code == 0 and sorted(seen) == [("pa", custom), ("sx", custom)]


@pytest.mark.parametrize("commit,base", [("abc", MAIN), (COMMIT, side.REPOSITORY + ":latest"),
                                         (COMMIT, "registry.example.invalid/other@sha256:" + "b" * 64)])
def test_invalid_inputs_build_nothing(commit, base):
    fakes, calls = builders(together=False)
    code, out, _ = run_side(["build", "--commit", commit, "--service-image", base, "--packet-analysis"], fakes)
    assert code == 1 and out == [] and calls == []


def test_the_real_builders_are_the_unchanged_tools_and_poll_every_three_seconds(monkeypatch):
    assert pa.BUILD_POLL_S == sx.BUILD_POLL_S == 3
    for tool in (pa, sx):
        job = {"metadata": {"annotations": {"nexus/commit": COMMIT, "nexus/service-image": MAIN}},
               "status": {"conditions": []}}
        monkeypatch.setattr(tool, "start", lambda *args: None)
        monkeypatch.setattr(tool.builds, "kubectl", lambda *args, **kwargs: json.dumps(job))
        monkeypatch.setattr(tool.builds, "matching", lambda job, commit: True)
        slept, now = [], [0]
        with pytest.raises(RuntimeError):
            tool.build(COMMIT, ROOT, MAIN, timeout=10, sleep=lambda s: (slept.append(s), now.__setitem__(0, now[0] + s)),
                       monotonic=lambda: now[0])
        assert slept and set(slept) == {3}


# -------------------------------------------------------------------------------------------------- run_build.sh

def build_script():
    return (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()


def test_the_security_source_snapshot_runs_beside_the_build_and_is_joined_before_the_gate():
    source = build_script()
    background = source.index("SECURITY_PID=$!")
    snapshot = source.index('security_host.py" snapshot')
    start = source.index('security_host.py" start')
    join = source.index('if ! wait "$SECURITY_PID"; then')
    assert snapshot < start < background < source.index("Starting loader...") < source.index('hosta_side_images.py" build')
    assert source.index('hosta_side_images.py" build') < join < source.index('security_host.py" gate')
    assert join < source.index('module_deploy.py" --targets')
    # Same commands and arguments as in the foreground; the job name is read only after the join.
    assert '--rules "$HOME/.config/nexus/security-rules"' in source[snapshot:start]
    assert 'SECURITY_JOB="$(cat "$SECURITY_JOB_FILE")"' in source[join:source.index('security_host.py" gate')]
    # A deploy that stops early waits for the snapshot instead of leaving it half done.
    trap = source[source.index("report_failure() {"):source.index("trap report_failure EXIT")]
    assert 'wait "$SECURITY_PID"' in trap
    # ... and removes the gate Job name file on every exit (Neon finding 9: it leaked on a failed deploy).
    assert 'rm -f "$SECURITY_JOB_FILE"' in trap and source.index('SECURITY_JOB_FILE=""') < source.index("report_failure() {")
    assert "timing security_snapshot_wait" in source and source.count("timing source_snapshot") == 2


def test_the_context_loader_is_deleted_with_a_one_second_grace():
    source = build_script()
    assert "kubectl delete -f deploy/ui2-image-build/20-context-loader.yaml --grace-period=1\n" in source
    assert "--grace-period=1 --force" not in source      # the delete still waits until the pod is gone
    loader = (ROOT / "deploy/ui2-image-build/20-context-loader.yaml").read_text()
    assert 'command: ["sleep", "infinity"]' in loader     # the reason: PID 1 sleep ignores SIGTERM


def test_new_timing_names_are_forwarded_by_the_deploy_observer():
    source = build_script()
    names = re.findall(r"timing ([a-z_0-9]+) ", source) + ["side_images", "packet_analysis_image", "script_exec_image"]
    observer = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    forwarded = re.search(r"grep -E '\^TIMING (\[[^]]+\]\+) \[0-9\]\+\$' /tmp/nexus_build.log", observer)
    assert forwarded, "the observer must forward the deploy's TIMING lines"
    assert "e2e_image_ready" in names                     # a digit: dropped by the observer until 2026-11-23
    assert all(re.fullmatch(forwarded.group(1), name) for name in names), names
    ship = (ROOT / "tools/delivery/standalone_orchestrate.py").read_text()
    assert 're.fullmatch(r"TIMING [a-z_0-9]+ [0-9]+", line)' in ship


# ------------------------------------------------------------------------------------------ module rollout

def test_module_rollout_stays_one_module_per_step():
    """PO 2026-11-23 (Neon review BLOCK): no parallel rollout of self-owned modules. Only the barrier-free
    configuration and compliance share a step, as before; every module with a drain barrier keeps its own step, its
    own fence on a failed e2e and its own module e2e."""
    full = ["service", "worker", "configuration", "compliance", "policy", "backup", "script-runner", "packet-analysis"]
    assert deploy.rollout_steps(full, True) == [
        ["service"], ["worker"], ["configuration", "compliance"], ["policy"], ["backup"], ["script-runner"],
        ["packet-analysis"]]
    assert deploy.CONCURRENT_SAFE == ("configuration", "compliance")
    assert not hasattr(deploy, "PARALLEL_DEDICATED")
    for path in ("tools/delivery/module_deploy.py", "deploy/ui2-image-build/run_build.sh",
                 "tools/delivery/hosta_deploy.sh"):
        text = (ROOT / path).read_text()
        assert "NEXUS_PARALLEL_MODULES" not in text and "parallel-modules" not in text, path
