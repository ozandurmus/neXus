import json
import pytest
from pathlib import Path
from replay.exporter import PrivateReplayExporter

def test_exporter_schema_and_validation(tmp_path):
    key = b"test_key_123456"
    exporter = PrivateReplayExporter(key)
    
    synthetic_data = {
        "device": "fw-1",
        "ip": "192.168.1.1",
        "interfaces": [
            {"name": "eth0", "status": "up"}
        ]
    }
    
    out_file = tmp_path / "package.json"
    exporter.export(synthetic_data, out_file)
    
    assert out_file.exists()
    
    with open(out_file, "r") as f:
        data = json.load(f)
        
    assert "device" in data
    assert data["device"] != "fw-1"
    assert data["device"].startswith("DEVICE_")
    
    assert "ip" in data
    assert data["ip"] != "192.168.1.1"
    # shape preservation
    assert len(data["ip"].split(".")) == 4
    
    assert exporter.validate_package(out_file) is True
