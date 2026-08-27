import json

from utils.verification import run_verification


def test_verification_is_observe_only_and_counts_sources(tmp_path):
    cp = [{
        "source": "cp",
        "device": "cp1",
        "interfaces": [{"name": "eth0"}],
        "routes": [{"network": "10.0.0.0/24", "interface": "eth0"}],
    }]
    vsx = [{
        "source": "vsx",
        "device": "vsx1",
        "vsys": "VS1",
        "vs_id": "1",
        "interfaces": [{"name": "eth1"}],
        "routing": [{"network": "0.0.0.0/0", "interface": "eth9"}],
    }]
    panorama = [{
        "source": "panorama",
        "device": "pan1",
        "interfaces": [],
        "routes": [],
    }]
    unified = cp + [{**vsx[0], "routes": vsx[0]["routing"]}] + panorama

    for name, data in {
        "cp.json": cp,
        "vsx.json": vsx,
        "panorama_runtime.json": panorama,
        "unified.json": unified,
    }.items():
        (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")

    report = run_verification(tmp_path)

    assert report["mode"] == "observe_only"
    assert report["publish_blocking"] is False
    assert report["sources"]["cp"]["status"] == "success"
    assert report["sources"]["vsx"]["status"] == "warning"
    assert report["sources"]["panorama"]["empty_objects"] == 1
    assert report["merge"]["count_match"] is True
    assert (tmp_path / "verification.json").exists()
