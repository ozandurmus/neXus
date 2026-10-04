"""SecurityExpert -- OP.0b S8, persistent Expert shell command framing.

The CP preflight battery runs inside ONE persistent Expert shell per member
(`InteractiveSshSession`), not one non-interactive exec channel per read.
A persistent shell has no per-command exit status of its own, so each read is
**framed**: the adapter appends an end marker echoing `$?`, and completion and
exit status are then read explicitly instead of inferred from a prompt match
or a quiet period.

What must hold for that framing to be safe:

  - it is opt-in, so the established REAL_ENV_VALIDATED collection path keeps
    its exact previous behaviour;
  - the marker is read-only shell `echo` -- it never changes device state;
  - the marker never survives into `stdout`, so no parser and no
    `PreflightFact` can ever see it (raw-evidence / value-free law);
  - a non-zero exit status is authoritative and fails closed.
"""
from __future__ import annotations

import inspect

import pytest

from configuration.checkpoint_config_collector import InteractiveSshSession

pytestmark = pytest.mark.configuration


class _Chan:
    """A shell that answers one framed command with fixture output."""

    def __init__(self, output: str = "Cluster Mode: High Availability", status: int = 0):
        self._output = output
        self._status = status
        self._buf = b"[Expert@gw:0]# "
        self._pending = ""
        self.sent: list[str] = []

    def settimeout(self, _t):
        pass

    def send(self, data: str) -> int:
        self._pending += data
        while "\n" in self._pending:
            line, self._pending = self._pending.split("\n", 1)
            line = line.strip()
            if not line:
                self._buf += b"[Expert@gw:0]# "
                continue
            self.sent.append(line)
            body = f"{self._output}\n" if self._output else ""
            # The real shell expands `$?` itself; mirror that faithfully.
            if "; echo " in line:
                marker = line.split("; echo ", 1)[1].replace("$?", str(self._status))
                body += marker + "\n"
            self._buf += (body + "[Expert@gw:0]# ").encode()
        return len(data)

    def recv_ready(self) -> bool:
        return bool(self._buf)

    def recv(self, _n: int) -> bytes:
        out, self._buf = self._buf, b""
        return out

    def recv_stderr_ready(self) -> bool:
        return False

    def close(self):
        pass


class _Ssh:
    def __init__(self, chan):
        self.chan = chan

    def invoke_shell(self, **_kw):
        return self.chan


def _session(chan) -> InteractiveSshSession:
    return InteractiveSshSession(_Ssh(chan), 5)


class TestFramingIsSafe:

    def test_marker_never_reaches_stdout(self):
        chan = _Chan()
        result = _session(chan).run("cphaprob stat", 5, frame=True)
        assert result["success"], result
        assert "Cluster Mode" in result["stdout"]
        # Whatever nonce was used, none of it survives into evidence.
        marker = chan.sent[-1].split("; echo ", 1)[1].replace("$?", "")
        assert marker not in result["stdout"]
        assert "echo" not in result["stdout"]
        assert "$?" not in result["stdout"]

    def test_exit_status_is_read_explicitly(self):
        assert _session(_Chan(status=0)).run("fw stat", 5, frame=True)["exit_status"] == 0

    def test_non_zero_status_fails_closed(self):
        result = _session(_Chan(output="", status=127)).run("cphaprob stat", 5, frame=True)
        assert result["success"] is False
        assert result["exit_status"] == 127

    def test_output_with_a_non_zero_status_is_never_usable_evidence(self):
        """Output plus a failure status is still a failure -- the status wins."""
        result = _session(_Chan(output="Cluster Mode: High Availability", status=1)).run(
            "cphaprob stat", 5, frame=True)
        assert result["success"] is False

    def test_framing_only_appends_a_read_only_echo(self):
        chan = _Chan()
        _session(chan).run("cphaprob stat", 5, frame=True)
        sent = chan.sent[-1]
        assert sent.startswith("cphaprob stat; echo ")
        # Nothing beyond the approved read and a bare `echo` of the marker.
        suffix = sent[len("cphaprob stat"):]
        for forbidden in (">", "<", "|", "&", "rm ", "set ", "save ", "$(", "`"):
            assert forbidden not in suffix, suffix

    def test_approved_command_text_is_never_altered(self):
        chan = _Chan()
        _session(chan).run("clish -c 'show cluster failover'", 5, frame=True)
        assert chan.sent[-1].startswith("clish -c 'show cluster failover';")


class TestFramingIsOptIn:

    def test_default_is_unframed(self):
        """The established collection path must be byte-identical to before."""
        chan = _Chan()
        _session(chan).run("clish -c 'show hostname'", 5)
        assert chan.sent[-1] == "clish -c 'show hostname'", chan.sent
        assert "echo" not in chan.sent[-1]

    def test_frame_is_keyword_only_with_a_false_default(self):
        sig = inspect.signature(InteractiveSshSession.run)
        param = sig.parameters["frame"]
        assert param.kind is inspect.Parameter.KEYWORD_ONLY
        assert param.default is False


class TestOneShellLifecycle:

    def test_one_invoke_shell_per_session(self):
        calls = {"n": 0}

        class _Counting(_Ssh):
            def invoke_shell(self, **kw):
                calls["n"] += 1
                return super().invoke_shell(**kw)

        session = InteractiveSshSession(_Counting(_Chan()), 5)
        for _ in range(5):
            session.run("cphaprob stat", 5, frame=True)
        assert calls["n"] == 1, "the shell is opened once and reused for every read"
