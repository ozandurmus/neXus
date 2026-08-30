import json
import zipfile

from utils import support_bundle
import pytest

pytestmark = pytest.mark.inventory


def test_support_bundle_hmac_anonymizes_and_is_deterministic(tmp_path, monkeypatch):
    run_dir = tmp_path / "data" / "runs" / "20260101_000000_deadbeef"
    stage = run_dir / "stage"
    raw = run_dir / "raw"
    stage.mkdir(parents=True)
    raw.mkdir(parents=True)

    cp = [{
        "source": "cp",
        "device": "FW-REAL-01",
        "interfaces": [{"name": "eth0", "ips": [{"ip": "10.1.2.3", "prefix": 24, "network": "10.1.2.0/24"}]}],
        "routes": [{"network": "0.0.0.0/0", "next_hop": "10.1.2.1", "interface": "eth0", "type": "default"}],
    }]
    (stage / "cp.json").write_text(json.dumps(cp), encoding="utf-8")
    (stage / "vsx.json").write_text("[]", encoding="utf-8")
    (stage / "panorama_runtime.json").write_text("[]", encoding="utf-8")
    (stage / "unified.json").write_text(json.dumps(cp), encoding="utf-8")
    (run_dir / "verification.json").write_text(json.dumps({"run_status": "success", "sources": {}}), encoding="utf-8")
    (run_dir / "manifest.json").write_text(json.dumps({
        "status": "failed",
        "error": "C:/Users/REALUSER/project failed for FW-REAL-01",
        "path": "C:/Users/REALUSER/project/output.json",
    }), encoding="utf-8")
    (raw / "vsx_raw.json").write_text("[]", encoding="utf-8")

    monkeypatch.setattr(support_bundle, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(support_bundle, "SUPPORT_KEY_FILE", tmp_path / "data" / ".support_hmac.key")

    first = support_bundle.run_support_bundle(run_dir)
    assert first.exists()

    with zipfile.ZipFile(first) as zf:
        texts = "\n".join(zf.read(name).decode("utf-8") for name in zf.namelist())
        assert "FW-REAL-01" not in texts
        assert "10.1.2.3" not in texts
        assert "10.1.2.0/24" not in texts
        assert "REALUSER" not in texts
        assert "DEV_" in texts or "ENTITY_" in texts

    first_summary = json.loads((run_dir / "support" / "summary.json").read_text(encoding="utf-8"))
    support_bundle.run_support_bundle(run_dir)
    second_summary = json.loads((run_dir / "support" / "summary.json").read_text(encoding="utf-8"))
    assert first_summary["run"] == second_summary["run"]
