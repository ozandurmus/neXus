import io
from pathlib import Path

import pytest

from checkpoint import cp_runner


class FakeSFTP:
    def __init__(self):
        self.files = {
            cp_runner.REMOTE_COLLECTION_META: b"old-marker",
            cp_runner.REMOTE_COLLECTION_STATUS: b"old-status",
        }

    def put(self, local_path, remote_path):
        self.files[remote_path] = Path(local_path).read_bytes()

    def file(self, remote_path, _mode="r"):
        return io.BytesIO(self.files[remote_path])

    def remove(self, remote_path):
        if remote_path not in self.files:
            raise OSError("not found")
        del self.files[remote_path]


def test_cp_bundled_collector_is_uploaded_and_hash_verified():
    sftp = FakeSFTP()
    result = cp_runner._deploy_collection_script(sftp)

    assert result["upload_verified"] is True
    assert result["local_sha256"] == result["remote_sha256"]
    assert sftp.files[cp_runner.REMOTE_COLLECTION_SCRIPT] == cp_runner.LOCAL_COLLECTION_SCRIPT.read_bytes()
    assert cp_runner.REMOTE_COLLECTION_META not in sftp.files
    assert cp_runner.REMOTE_COLLECTION_STATUS not in sftp.files


def test_cp_collection_marker_must_be_new_and_structurally_valid():
    assert cp_runner._validate_new_collection_marker({
        "started_epoch": 100,
        "completed_epoch": 110,
        "discovered": 83,
    }) is True

    with pytest.raises(RuntimeError):
        cp_runner._validate_new_collection_marker({})

    with pytest.raises(RuntimeError):
        cp_runner._validate_new_collection_marker({
            "started_epoch": 110,
            "completed_epoch": 100,
            "discovered": 83,
        })


def test_cp_output_progress_parser_does_not_need_device_identity(capsys):
    state = {
        "total_gw": None,
        "processed_gw": 0,
        "done_marker_seen": False,
    }

    cp_runner._process_collection_output_line("TOTAL_GW=83", state)
    cp_runner._process_collection_output_line(">>> GW: sensitive-device-name (10.1.2.3)", state)
    cp_runner._process_collection_output_line("DONE", state)

    output = capsys.readouterr().out
    assert state["total_gw"] == 83
    assert state["processed_gw"] == 1
    assert state["done_marker_seen"] is True
    assert "sensitive-device-name" not in output
    assert "10.1.2.3" not in output
    assert "[CP 1 / 83]" in output
