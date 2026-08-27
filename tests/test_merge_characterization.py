import json
from pathlib import Path

from utils import merge


def _write(path: Path, value):
    path.write_text(json.dumps(value), encoding="utf-8")


def test_merge_preserves_current_vendor_shapes_and_legacy_panorama_data(tmp_path, monkeypatch):
    cp_file = tmp_path / "cp.json"
    vsx_file = tmp_path / "vsx.json"
    pan_file = tmp_path / "panorama_runtime.json"
    unified_file = tmp_path / "unified.json"

    _write(
        cp_file,
        [
            {
                "device": "cp-fw",
                "interfaces": [{"name": "eth0", "ips": []}],
                "routes": [{"network": "0.0.0.0/0"}],
            }
        ],
    )
    _write(
        vsx_file,
        [
            {
                "device": "vsx-cluster-1",
                "vsys": "VS-Blue",
                "interfaces": [{"name": "eth1", "ips": []}],
                "routing": [{"network": "10.0.0.0/8"}],
            }
        ],
    )
    _write(
        pan_file,
        [
            {
                "device": "pa-fw",
                "serial": "SERIAL-1",
                "interfaces": [{"name": "ethernet1/1", "ip": "192.0.2.1"}],
                "routes": [{"network": "192.0.2.0/24"}],
                "vr_data": {"default": {"routes": []}},
            }
        ],
    )

    monkeypatch.setattr(merge, "CP_FILE", cp_file)
    monkeypatch.setattr(merge, "VSX_FILE", vsx_file)
    monkeypatch.setattr(merge, "PAN_FILE", pan_file)
    monkeypatch.setattr(merge, "UNIFIED_FILE", unified_file)
    monkeypatch.setattr(merge, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(merge, "info", lambda _msg: None)

    merge.run_merge()

    result = json.loads(unified_file.read_text(encoding="utf-8"))

    assert [item["source"] for item in result] == ["cp", "vsx", "panorama"]
    assert result[0]["vsys"] == "default"
    assert result[1]["cluster"] == "vsx-cluster"
    assert result[1]["routes"] == [{"network": "10.0.0.0/8"}]
    assert result[1]["routing"] == [{"network": "10.0.0.0/8"}]
    assert result[2]["serial"] == "SERIAL-1"
    assert result[2]["vr_data"] == {"default": {"routes": []}}
