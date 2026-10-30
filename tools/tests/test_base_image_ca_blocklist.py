"""eMudhra (emSign) trust-store blocklist and the time-boxed base-image CVE acceptances.

PO decision 2026-10-23, extended by PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md.
"""
from datetime import date
from pathlib import Path
import re
import shutil
import subprocess

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTAINERFILES = (ROOT / "ui2/Containerfile", ROOT / "ui2/Containerfile.packet-analysis")
BUNDLE = "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem"
BLOCKLIST = "/etc/pki/ca-trust/source/blocklist"
EXPIRY = date(2026, 11, 14)
ACCEPTED = {
    "CVE-2026-91415": "java-21-openjdk-headless",
}
# The 2026-10-22 runtime base ships the fixed ca-certificates: the acceptance is gone, the blocklist step stays.
RESOLVED = ("CVE-2026-114406",)


def runtime_stage(path):
    return path.read_text(encoding="utf-8").rsplit("\nFROM ", 1)[1]


def blocklist_run(path):
    runs = re.findall(r"^RUN mkdir -p " + BLOCKLIST + r".*?(?=\n\n|\Z)", runtime_stage(path), flags=re.M | re.S)
    assert len(runs) == 1, f"{path.name}: expected exactly one blocklist RUN"
    return runs[0]


@pytest.mark.parametrize("path", CONTAINERFILES, ids=lambda p: p.name)
def test_containerfile_blocklists_emsign_and_fails_if_any_stays_trusted(path):
    run = blocklist_run(path)
    stage = runtime_stage(path)
    assert BUNDLE in run and BLOCKLIST + "/emsign-" in run
    # The roots come from the p11-kit store (every purpose), not from the TLS bundle: the bundle lacks the
    # email-only emSign roots (C2, G2), which `trust list` still reports as anchors (build failure 2026-10-23).
    assert "trust dump --filter=ca-anchors" in run
    assert run.index("trust dump") < run.index("awk ") < run.index("update-ca-trust extract")
    after = run.split("update-ca-trust extract", 1)[1]
    # Post-checks: the regenerated bundle, `trust list` and the Java store must all be free of emSign, and each
    # check must prove it looked at a populated store (a missing tool or an empty listing never passes).
    assert "! grep -qi '^# emsign' " + BUNDLE in after
    assert "test -z \"$(trust list --filter=ca-anchors | grep -i emsign)\"" in after
    assert "trust list --filter=ca-anchors | grep -c '^pkcs11:')\" -gt 50" in after
    assert "trust list --filter=blocklist | grep -ci emsign)\" -ge 1" in after
    assert "test -z \"$(keytool -list -keystore /etc/pki/java/cacerts -storepass changeit | grep -i emsign)\"" in after
    assert "grep -c trustedCertEntry)\" -gt 50" in after
    assert "command -v" not in run and "2>/dev/null" not in run, "no check may be skippable or hide an error"
    assert run.count("&&") >= 10 and "||" not in run, "the checks must be chained so a hit fails the build"
    # Root for the step, non-root afterwards, nothing else about the user changes.
    assert stage.index("USER 0\n") < stage.index(run) < stage.rindex("USER 185\n")
    assert re.findall(r"^USER +(\S+)", path.read_text(), re.M)[-1] == "185"


@pytest.mark.skipif(shutil.which("awk") is None, reason="awk not available")
@pytest.mark.parametrize("path", CONTAINERFILES, ids=lambda p: p.name)
def test_awk_extracts_only_emsign_certificates_from_the_trust_dump(path, tmp_path):
    program = re.search(r"awk '(.*?)' \\\n", blocklist_run(path), flags=re.S).group(1)
    program = program.replace(BLOCKLIST, str(tmp_path))

    def obj(label, body, trusted="true", distrusted="false"):  # the `trust dump` object layout: attributes, then the PEM
        return (f'[p11-kit-object-v1]\nprivate: false\nlabel: "{label}"\ntrusted: {trusted}\n'
                f"x-distrusted: {distrusted}\n"
                f"-----BEGIN CERTIFICATE-----\n{body}\n-----END CERTIFICATE-----\n\n\n# pkcs11:id=%00;type=cert\n")
    dump = tmp_path / "dump.p11"
    dump.write_text(obj("ISRG Root X1", "AAAA") + obj("emSign Root CA - G1", "BBBB") + obj("Entrust Root", "CCCC")
                    + obj("emSign ECC Root CA - G3", "DDDD") + obj("emSign Root CA - C2", "EEEE")
                    + obj("Zeta emSign", "FFFF")
                    # A re-run sees the blocklist entry of an earlier run beside the original: only the trusted one counts.
                    + obj("emSign Root CA - G1", "BBBB", trusted="false", distrusted="true"))
    subprocess.run(["awk", program, str(dump)], check=True)
    out = {p.name: p.read_text() for p in tmp_path.glob("emsign-*.pem")}
    assert sorted(out) == ["emsign-01.pem", "emsign-02.pem", "emsign-03.pem"], "already-blocklisted copies are skipped"
    assert "BBBB" in out["emsign-01.pem"] and "DDDD" in out["emsign-02.pem"] and "EEEE" in out["emsign-03.pem"]
    assert all(out[n].count("BEGIN CERTIFICATE") == 1 and out[n].count("END CERTIFICATE") == 1 for n in out)
    assert not any(x in "".join(out.values()) for x in ("AAAA", "CCCC", "FFFF", "p11-kit", "label"))


def test_baseline_pins_the_base_image_findings_until_2026_11_14():
    entries = yaml.safe_load((ROOT / "security/baseline.yaml").read_text())["accepted"]
    for rule, package in ACCEPTED.items():
        rows = [e for e in entries if e["rule"] == rule]
        assert {e["location"] for e in rows} == {
            "registry.kube-system.svc.cluster.local/nexus-ui2-service: " + package,
            "ui2/Containerfile runtime image: " + package,
            "ui2/Containerfile.packet-analysis runtime image: " + package,
        }
        for e in rows:
            assert e["tool"] == "trivy" and e["owner"] == "PO"
            assert date.fromisoformat(e["review_date"]) == EXPIRY, "time-boxed: expires 2026-11-14, never later"
            assert "PO decision 2026-10-23" in e["reason"] and "no fixed package" in e["reason"]
            assert "PO decision 2026-10-30" in e["reason"]
    assert not {e["rule"] for e in entries} & set(RESOLVED), "a finding the base no longer reports stays removed"
    fonts = next(e for e in entries if e["rule"] == "CVE-2026-91415")
    assert "AWT" in fonts["reason"]
