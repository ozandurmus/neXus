"""hosta_export_all.sh must export the ui2-build namespace like ui2 (secrets 0600, configmaps)."""
import os, re, stat, subprocess, textwrap
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "hosta_export_all.sh"

STUB = textwrap.dedent("""\\
    #!/bin/sh
    echo "$*" >> "$STUB_LOG"
    case "$*" in
      *"-n ui2 get pods"*) ;;
      *"pg_restore --list"*) echo "1; TABLE DATA public t u" ;;
      *"-n ui2-build get secrets"*) printf 'build-reg\nbuild-tok\n' ;;
      *"-n ui2 get secrets"*) printf 'ui2-main\n' ;;
      *"get secret "*) echo "data: {k: SECRETVALUE}" ;;
      *"get configmaps"*) echo "items: []" ;;
    esac
""")


def remote_part_until_step3():
    text = SCRIPT.read_text()
    body = text.split("<<'REMOTE'\n", 1)[1].split("\nREMOTE", 1)[0]
    return body.split('echo "3/6', 1)[0]


def test_build_namespace_exported(tmp_path):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    k = bindir / "kubectl"
    k.write_text(STUB)
    k.chmod(0o755)
    log = tmp_path / "calls.log"
    dest = tmp_path / "out"
    env = {"PATH": f"{bindir}:/usr/bin:/bin", "HOME": str(tmp_path), "STUB_LOG": str(log), "DIR": str(dest)}
    r = subprocess.run(["bash", "-c", remote_part_until_step3()], env=env, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    calls = log.read_text()
    assert "-n ui2-build get secrets" in calls
    assert "-n ui2-build get configmaps" in calls
    assert "type!=kubernetes.io/service-account-token" in calls
    for f in ("secrets/ui2-main.yaml", "secrets/build/build-reg.yaml", "secrets/build/build-tok.yaml"):
        p = dest / f
        assert p.is_file(), f
        assert stat.S_IMODE(p.stat().st_mode) == 0o600
    assert (dest / "configmaps-build.yaml").is_file()
    assert stat.S_IMODE((dest / "secrets/build").stat().st_mode) == 0o700
    assert "SECRETVALUE" not in r.stdout + r.stderr
    assert re.search(r"3 secrets \(\s*2 from ui2-build\)", r.stdout)
