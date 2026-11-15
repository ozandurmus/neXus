"""hosta_export_all.sh exports the ui2 Secrets and ConfigMaps, and from ui2-build only a NAMED list of Secrets."""
import os, re, stat, subprocess, textwrap
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "hosta_export_all.sh"

STUB = textwrap.dedent("""\
    #!/bin/sh
    echo "$*" >> "$STUB_LOG"
    case "$*" in
      *"-n ui2 get pods"*) ;;
      *"pg_restore --list"*) echo "1; TABLE DATA public t u" ;;
      *"-n ui2-build get secrets"*) printf 'build-reg\nbuild-tok\nbuild-named\n' ;;
      *"-n ui2-build get secret build-missing"*) exit 1 ;;
      *"-n ui2 get secrets"*) printf 'ui2-main\n' ;;
      *"get secret "*) echo "data: {k: SECRETVALUE}" ;;
      *"get configmaps"*) echo "items: []" ;;
    esac
""")


def remote_part_until_step3(build_secrets=None):
    text = SCRIPT.read_text()
    body = text.split("<<'REMOTE'\n", 1)[1].split("\nREMOTE", 1)[0]
    body = body.split('echo "3/6', 1)[0]
    if build_secrets is not None:
        assert "\nBUILD_SECRETS=()\n" in body, "the named list is one assignment line"
        body = body.replace("\nBUILD_SECRETS=()\n", "\nBUILD_SECRETS=(" + " ".join(build_secrets) + ")\n")
    return body


def run(tmp_path, build_secrets=None):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    k = bindir / "kubectl"
    k.write_text(STUB)
    k.chmod(0o755)
    log = tmp_path / "calls.log"
    dest = tmp_path / "out"
    env = {"PATH": f"{bindir}:/usr/bin:/bin", "HOME": str(tmp_path), "STUB_LOG": str(log), "DIR": str(dest)}
    r = subprocess.run(["bash", "-c", remote_part_until_step3(build_secrets)], env=env, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    return r, log.read_text(), dest


def test_the_shipped_list_is_empty_and_no_build_secret_is_listed_or_exported(tmp_path):
    r, calls, dest = run(tmp_path)
    assert "-n ui2-build get secret" not in calls, "the namespace's Secrets are never listed or read"
    assert not list((dest / "secrets/build").iterdir())
    assert (dest / "secrets/ui2-main.yaml").is_file()
    assert stat.S_IMODE((dest / "secrets/ui2-main.yaml").stat().st_mode) == 0o600
    assert "-n ui2-build get configmaps" in calls
    assert (dest / "configmaps-build.yaml").is_file()
    assert stat.S_IMODE((dest / "secrets/build").stat().st_mode) == 0o700
    assert "SECRETVALUE" not in r.stdout + r.stderr
    assert re.search(r"1 secrets \(\s*0 from ui2-build\)", r.stdout)


def test_only_the_named_build_secrets_are_exported_and_a_missing_one_only_warns(tmp_path):
    r, calls, dest = run(tmp_path, ["build-named", "build-missing"])
    assert "-n ui2-build get secrets" not in calls, "no listing: others are never read"
    assert "-n ui2-build get secret build-named" in calls
    assert sorted(p.name for p in (dest / "secrets/build").iterdir()) == ["build-named.yaml"]
    assert stat.S_IMODE((dest / "secrets/build/build-named.yaml").stat().st_mode) == 0o600
    assert "WARNING: ui2-build Secret 'build-missing'" in r.stdout
    assert "build-reg" not in calls and "build-tok" not in calls
    assert "SECRETVALUE" not in r.stdout + r.stderr
    assert re.search(r"2 secrets \(\s*1 from ui2-build\)", r.stdout)


# --- platform backup script, recovery design step S1 (section 7.1) ---
PB_SCRIPT = Path(__file__).resolve().parents[2] / "deploy" / "hosta" / "nexus-platform-backup.sh"
PB_TEXT = PB_SCRIPT.read_text(encoding="utf-8")
PB_SECRETS = ["ui2-db", "ui2-credential-store-key", "ui2-role-binding-key", "ui2-artefact-store-key",
              "ui2-hostname-fingerprint-key", "ui2-privacy-hmac-key"]


def test_pb_script_is_valid_bash():
    subprocess.run(["bash", "-n", str(PB_SCRIPT)], check=True)


def test_pb_fail_closed_and_private_work_dir():
    assert "set -euo pipefail" in PB_TEXT and "umask 077" in PB_TEXT
    assert "trap 'rm -rf \"$W\"' EXIT" in PB_TEXT


def test_pb_globals_dump_included():
    assert "pg_dumpall" in PB_TEXT and "--globals-only" in PB_TEXT


def test_pb_dump_verified_before_encryption():
    assert PB_TEXT.index("pg_restore --list") < PB_TEXT.index("age -R")
    assert "TABLE DATA" in PB_TEXT and "exit 1" in PB_TEXT


def test_pb_all_six_secrets_exported_inside_the_archive():
    for name in PB_SECRETS:
        assert name in PB_TEXT
    assert '"$W/secrets' in PB_TEXT


def test_pb_ssh_host_keys_and_k3s_manifests_included():
    assert "/etc/ssh/ssh_host_" in PB_TEXT
    assert '"$K3S/manifests"' in PB_TEXT


def test_pb_only_recipients_file_never_an_identity_and_no_secret_echo():
    assert "/etc/nexus/backup/age-recipient.txt" in PB_TEXT
    assert not re.search(r"age\s+(-d|--decrypt)|-i\s+\S*identity|age-identity", PB_TEXT)
    for line in PB_TEXT.splitlines():
        if line.lstrip().startswith("echo") and "backup aborted" not in line:
            assert "secret" not in line.lower() or "$S" not in line
