from checkpoint import vsx_runner


class FakeShell:
    def __init__(self):
        self.chunks = []
        self.sent = []

    def send(self, value):
        self.sent.append(value)
        self.chunks = [
            b"route-a\r\n",
            b"route-b\r\n[Expert@FW:1]# ",
        ]

    def recv_ready(self):
        return bool(self.chunks)

    def recv(self, _size):
        return self.chunks.pop(0)


def test_run_cmd_reads_until_expert_prompt(monkeypatch):
    shell = FakeShell()
    monkeypatch.setattr(vsx_runner.time, "sleep", lambda _x: None)

    output = vsx_runner.run_cmd(shell, "ip route", wait=0, max_wait=1, idle_grace=0)

    assert "route-a" in output
    assert "route-b" in output
    assert "[Expert@FW:1]#" in output
