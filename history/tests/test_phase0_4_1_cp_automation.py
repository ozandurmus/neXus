import io
from pathlib import Path

import pytest

from checkpoint import cp_runner

pytestmark = pytest.mark.inventory


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
        "last_marker": None,
    }

    cp_runner._process_collection_output_line("TOTAL_GW=83", state)
    cp_runner._process_collection_output_line(">>> GW: sensitive-device-name (10.1.2.3)", state)
    cp_runner._process_collection_output_line("DONE", state)

    output = capsys.readouterr().out
    assert state["total_gw"] == 83
    assert state["processed_gw"] == 1
    assert state["done_marker_seen"] is True
    assert state["last_marker"] == "DONE"
    assert "sensitive-device-name" not in output
    assert "10.1.2.3" not in output
    assert "[CP 1 / 83]" in output


class FakeChannel:
    """Minimal paramiko.Channel double for _run_remote_collection.

    `stdout_chunks` are handed back one per recv() call while recv_ready()
    still has chunks left -- this exercises the tight-drain loop (multiple
    recv() calls must happen before recv_ready() goes False), the same
    idiom checkpoint/direct_ssh_probe.py already uses.
    """

    def __init__(self, stdout_chunks, exit_status=0, stderr_chunks=()):
        self._stdout_chunks = list(stdout_chunks)
        self._exit_status = exit_status
        self._stderr_chunks = list(stderr_chunks)

    def recv_ready(self):
        return bool(self._stdout_chunks)

    def recv(self, _n):
        return self._stdout_chunks.pop(0)

    def recv_stderr_ready(self):
        return bool(self._stderr_chunks)

    def recv_stderr(self, _n):
        return self._stderr_chunks.pop(0)

    def exit_status_ready(self):
        return True

    def recv_exit_status(self):
        return self._exit_status


class FakeStdout:
    def __init__(self, channel):
        self.channel = channel


def _fake_ssh(channel):
    class _FakeSSH:
        def exec_command(self, _command):
            return None, FakeStdout(channel), None

    return _FakeSSH()


def test_run_remote_collection_drains_multi_chunk_burst_and_sees_done(monkeypatch):
    monkeypatch.setattr(cp_runner, "info", lambda *a, **k: None)
    monkeypatch.setattr(cp_runner, "warn", lambda *a, **k: None)
    channel = FakeChannel([
        b"TOTAL_GW=2\n>>> GW: gw1 (10.0.0.1)\n",
        b">>> GW: gw2 (10.0.0.2)\nDONE\n",
    ])

    state = cp_runner._run_remote_collection(_fake_ssh(channel))

    assert state["done_marker_seen"] is True
    assert state["processed_gw"] == 2
    assert state["total_gw"] == 2
    assert state["exit_status"] == 0


def test_run_remote_collection_missing_done_marker_raises_safe_diagnostics(monkeypatch):
    monkeypatch.setattr(cp_runner, "info", lambda *a, **k: None)
    monkeypatch.setattr(cp_runner, "warn", lambda *a, **k: None)
    channel = FakeChannel([b"TOTAL_GW=1\n>>> GW: gw1 (10.0.0.1)\n"])

    with pytest.raises(RuntimeError) as excinfo:
        cp_runner._run_remote_collection(_fake_ssh(channel))

    message = str(excinfo.value)
    assert "DONE marker" in message
    assert "processed_gw=1" in message
    assert "total_gw=1" in message
    assert "last_marker=GW" in message


def test_run_remote_collection_classifies_stderr_without_leaking_raw_text(monkeypatch):
    monkeypatch.setattr(cp_runner, "info", lambda *a, **k: None)
    monkeypatch.setattr(cp_runner, "warn", lambda *a, **k: None)
    raw = b"bash: /opt/CPshared/5.0/tmp/.CPprofile.sh: No such file or directory\n"
    channel = FakeChannel([b""], stderr_chunks=[raw])

    with pytest.raises(RuntimeError) as excinfo:
        cp_runner._run_remote_collection(_fake_ssh(channel))

    message = str(excinfo.value)
    assert "no_such_file_or_directory" in message
    assert f"stderr_bytes={len(raw)}" in message
    assert ".CPprofile.sh" not in message
    assert "No such file or directory" not in message


def test_classify_stderr_sample_returns_unclassified_for_unknown_text():
    assert cp_runner._classify_stderr_sample("device gw-core-01 rebooted") == ["unclassified"]


def test_classify_stderr_sample_matches_known_safe_categories():
    assert cp_runner._classify_stderr_sample("Permission denied (publickey)") == ["permission_denied"]
    assert cp_runner._classify_stderr_sample(
        "stty: standard input: Inappropriate ioctl for device"
    ) == ["not_a_tty"]
