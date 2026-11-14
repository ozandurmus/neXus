"""No tracked build or delivery file may switch off TLS verification for npm or Node.

The UI 2.0 image build runs behind a TLS-inspecting network. The supported answer is trust, not silence:
corporate CA anchors go into `ui2/.ca/*.pem` and the frontend stage points npm and Node at the
system-extracted bundle (see `ui2/Containerfile` and docs/operations/HOST_A_RUNBOOK.md).
"""
from pathlib import Path
import re
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]
THIS = Path(__file__).resolve()
SUFFIXES = {".sh", ".py", ".yaml", ".yml", ".template", ".json", ".mjs", ".js", ".ts", ".gradle", ".kts", ".toml", ".npmrc"}
NAMES = ("Containerfile", "Dockerfile", ".npmrc", "Makefile")
# strict-ssl set to a false-like value, `--no-strict-ssl`, NODE_TLS_REJECT_UNAUTHORIZED set to 0/false.
FORBIDDEN = (
    re.compile(r"strict-ssl\s*(?:=|\s)\s*[\"']?(?:false|0|no|off)\b", re.I),
    re.compile(r"--no-strict-ssl", re.I),
    re.compile(r"NODE_TLS_REJECT_UNAUTHORIZED\s*(?:=|\s)\s*[\"']?(?:0|false)\b", re.I),
    re.compile(r"NODE_TLS_REJECT_UNAUTHORIZED[\"']\s*\]\s*=\s*[\"']?(?:0|false)", re.I),
)


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout
    for raw in out.split(b"\0"):
        if not raw:
            continue
        path = ROOT / raw.decode("utf-8")
        if path == THIS or not path.is_file():
            continue
        if path.suffix in SUFFIXES or path.name in NAMES or path.name.startswith(("Containerfile", "Dockerfile")):
            yield path


def offenders(text):
    return [i for i, line in enumerate(text.splitlines(), 1)
            if not line.lstrip().startswith("#") and any(p.search(line) for p in FORBIDDEN)]


@pytest.mark.parametrize("sample", [
    "RUN npm config set strict-ssl false && npm ci",
    "npm ci --no-strict-ssl",
    "strict-ssl=false",
    "ENV NODE_TLS_REJECT_UNAUTHORIZED=0",
    "NODE_TLS_REJECT_UNAUTHORIZED=0 npm ci",
    "process.env['NODE_TLS_REJECT_UNAUTHORIZED'] = '0'",
])
def test_detector_flags_known_bad_forms(sample):
    assert offenders(sample)


@pytest.mark.parametrize("sample", [
    "RUN npm config set strict-ssl true",
    "ENV NODE_EXTRA_CA_CERTS=/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    "# never npm config set strict-ssl false",
])
def test_detector_passes_safe_forms(sample):
    assert not offenders(sample)


def test_no_tracked_file_disables_npm_or_node_tls_verification():
    bad = []
    for path in tracked_files():
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        bad += [f"{path.relative_to(ROOT)}:{n}" for n in offenders(text)]
    assert not bad, "TLS verification must stay on; trust the corporate CA instead: " + ", ".join(bad)


def test_frontend_stage_trusts_system_bundle_and_keeps_strict_ssl():
    text = (ROOT / "ui2/Containerfile").read_text(encoding="utf-8")
    stage = text.split("AS frontend", 1)[1].split("\nFROM ", 1)[0]
    bundle = "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem"
    assert f"ENV NODE_EXTRA_CA_CERTS={bundle}" in stage
    assert 'npm config set cafile "$NODE_EXTRA_CA_CERTS"' in stage
    assert "npm config set strict-ssl true" in stage
    assert stage.index("update-ca-trust") < stage.index("NODE_EXTRA_CA_CERTS") < stage.index("npm ci")
    assert 'test -s "$NODE_EXTRA_CA_CERTS"' in stage
