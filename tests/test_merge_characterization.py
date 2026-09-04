import json
from pathlib import Path

from utils import merge
import pytest

pytestmark = pytest.mark.inventory


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
    # OP.0b S9: no cp.json row named "vsx-cluster-1" carries a
    # cluster_topology, and no explicit "parent" field is present, so no
    # cluster identity is guessed from the hostname (the retired
    # `NAME-1`/`NAME-2` -> `NAME` heuristic).
    assert result[1]["cluster"] == ""
    assert result[1]["routes"] == [{"network": "10.0.0.0/8"}]
    assert result[1]["routing"] == [{"network": "10.0.0.0/8"}]
    assert result[2]["serial"] == "SERIAL-1"
    assert result[2]["vr_data"] == {"default": {"routes": []}}


def test_merge_propagates_canonical_cp_cluster_topology_onto_matching_vsx_row(tmp_path, monkeypatch):
    """OP.0b S9: a VSX physical host's cluster identity comes from the
    canonical `cluster_topology` (`checkpoint/cp_runner.py::
    enrich_cluster_topology`'s runtime VIP fingerprint) already attached to
    that same device's own `cp.json` row -- never a hostname-suffix guess."""
    cp_file = tmp_path / "cp.json"
    vsx_file = tmp_path / "vsx.json"
    pan_file = tmp_path / "panorama_runtime.json"
    unified_file = tmp_path / "unified.json"

    topology = {
        "group_id": "abc123",
        "display_name": "FW-CKP-VSX-CLS",
        "name_source": "inferred_member_pattern",
        "members": ["FW-CKP-VSX-1", "FW-CKP-VSX-2"],
    }
    _write(
        cp_file,
        [
            {"device": "FW-CKP-VSX-1", "cluster_topology": topology, "interfaces": [], "routes": []},
            {"device": "FW-CKP-VSX-2", "cluster_topology": topology, "interfaces": [], "routes": []},
        ],
    )
    _write(
        vsx_file,
        [
            {"device": "FW-CKP-VSX-1", "vsys": "VS-Blue", "interfaces": [], "routing": []},
        ],
    )
    _write(pan_file, [])

    monkeypatch.setattr(merge, "CP_FILE", cp_file)
    monkeypatch.setattr(merge, "VSX_FILE", vsx_file)
    monkeypatch.setattr(merge, "PAN_FILE", pan_file)
    monkeypatch.setattr(merge, "UNIFIED_FILE", unified_file)
    monkeypatch.setattr(merge, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(merge, "info", lambda _msg: None)

    merge.run_merge()

    result = json.loads(unified_file.read_text(encoding="utf-8"))
    vsx_row = next(item for item in result if item["source"] == "vsx")

    assert vsx_row["cluster"] == "FW-CKP-VSX-CLS"
    assert vsx_row["cluster_topology"] == topology


def test_merge_does_not_guess_vsx_cluster_from_hostname_ordinal_suffix(tmp_path, monkeypatch):
    """A device name that merely looks like an ordinal pair member
    (`-1`/`-2`) is never enough on its own -- the retired heuristic this
    regression guards against."""
    cp_file = tmp_path / "cp.json"
    vsx_file = tmp_path / "vsx.json"
    pan_file = tmp_path / "panorama_runtime.json"
    unified_file = tmp_path / "unified.json"

    _write(cp_file, [])
    _write(
        vsx_file,
        [{"device": "FW-STANDALONE-1", "vsys": "VS-A", "interfaces": [], "routing": []}],
    )
    _write(pan_file, [])

    monkeypatch.setattr(merge, "CP_FILE", cp_file)
    monkeypatch.setattr(merge, "VSX_FILE", vsx_file)
    monkeypatch.setattr(merge, "PAN_FILE", pan_file)
    monkeypatch.setattr(merge, "UNIFIED_FILE", unified_file)
    monkeypatch.setattr(merge, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(merge, "info", lambda _msg: None)

    merge.run_merge()

    result = json.loads(unified_file.read_text(encoding="utf-8"))
    vsx_row = next(item for item in result if item["source"] == "vsx")

    assert vsx_row["cluster"] == ""
    assert "cluster_topology" not in vsx_row
