import paramiko
import os
import json
import time
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock
from utils.cp_ssh_trust import apply_strict_host_key_policy
from utils.logger import info, warn, err
from pathlib import Path

OUT = "output/vsx_raw.json"
TELEMETRY_OUT = "output/vsx_telemetry.json"
_TELEMETRY = []
_TELEMETRY_LOCK = Lock()


DISCOVERY_SCRIPT = """#!/bin/bash
. /opt/CPshared/5.0/tmp/.CPprofile.sh
for CMA in $($MDSVERUTIL AllCMAs); do
    mdsenv "$CMA" >/dev/null 2>&1
    cpmiquerybin attr "" network_objects \
    "type='cluster_member' & vsx_cluster_member='true'" \
    -a __name__,ipaddr
done
"""


def _env_int(name: str, default: int, *, minimum: int = 1, maximum: int = 120) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


# 0.6.1B.1.3 safety audit finding: this connection previously had no timeout,
# so an unreachable/slow MDS could block a worker indefinitely. Bounded and
# env-overridable for consistency with direct_ssh_probe.py / checkpoint_config_probe.py.
CONNECT_TIMEOUT_SECONDS = _env_int("FBUDDY_VSX_SSH_CONNECT_TIMEOUT_SECONDS", 10, minimum=2, maximum=60)


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}


def connect(host, user, pwd, *, timeout=None, strict_host_key: bool = False):
    ssh = paramiko.SSHClient()
    apply_strict_host_key_policy(ssh, strict_host_key)
    connect_timeout = CONNECT_TIMEOUT_SECONDS if timeout is None else timeout
    connect_args = {
        "username": user,
        "password": pwd,
        "timeout": connect_timeout,
        "banner_timeout": connect_timeout,
        "auth_timeout": connect_timeout,
    }
    ssh.connect(host, **connect_args)
    return ssh


PROMPT_RE = re.compile(r"\[Expert@[^\]]+\]#\s*$", re.MULTILINE)


def _drain_ready(shell):
    chunks = []
    while shell.recv_ready():
        chunks.append(shell.recv(65535).decode(errors="ignore"))
    return "".join(chunks)


def run_cmd(shell, cmd, wait=1.5, max_wait=30.0, idle_grace=1.0, telemetry=None):
    """Run a command on the existing interactive shell without changing the SSH method.

    The old implementation slept for a fixed period and then read only what was
    already buffered. Large VSX contexts can still be producing output after that
    point, which makes the result timing-dependent and can truncate interface or
    route output. This version waits for the Expert prompt, with an idle fallback
    and a hard upper bound.
    """
    _drain_ready(shell)
    shell.send(cmd + "\n")

    start = time.monotonic()
    timed_out = False
    last_data = start
    out = ""
    first_data_seen = False

    while time.monotonic() - start < max_wait:
        if shell.recv_ready():
            chunk = _drain_ready(shell)
            if chunk:
                out += chunk
                first_data_seen = True
                last_data = time.monotonic()
                if PROMPT_RE.search(out):
                    if telemetry is not None:
                        telemetry.update({
                            "duration_ms": int((time.monotonic() - start) * 1000),
                            "bytes_read": len(out.encode("utf-8", errors="ignore")),
                            "lines_read": len(out.splitlines()),
                            "prompt_seen": True,
                            "timeout": False,
                        })
                    return out
            continue

        elapsed = time.monotonic() - start
        idle = time.monotonic() - last_data

        # Preserve login/password handshake behavior where an Expert prompt may
        # not exist yet, while allowing normal commands to complete by prompt.
        if first_data_seen and elapsed >= wait and idle >= idle_grace:
            if telemetry is not None:
                telemetry.update({
                    "duration_ms": int((time.monotonic() - start) * 1000),
                    "bytes_read": len(out.encode("utf-8", errors="ignore")),
                    "lines_read": len(out.splitlines()),
                    "prompt_seen": bool(PROMPT_RE.search(out)),
                    "timeout": False,
                })
            return out

        time.sleep(0.1)

    timed_out = True
    err(f"VSX command read timeout after {max_wait:.0f}s: {cmd.split(';')[0]}")
    out += _drain_ready(shell)
    if telemetry is not None:
        telemetry.update({
            "duration_ms": int((time.monotonic() - start) * 1000),
            "bytes_read": len(out.encode("utf-8", errors="ignore")),
            "lines_read": len(out.splitlines()),
            "prompt_seen": bool(PROMPT_RE.search(out)),
            "timeout": timed_out,
        })
    return out


def discover_vsx(ssh):

    path = "/var/tmp/vsx_disc.sh"
    sftp = ssh.open_sftp()
    with sftp.file(path, "w") as f:
        f.write(DISCOVERY_SCRIPT)
    sftp.close()

    ssh.exec_command(f"chmod +x {path}")
    _, stdout, _ = ssh.exec_command(f"bash {path}")

    out = stdout.read().decode()

    gws = []

    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue

        name, ip = parts[0], parts[1]

        # real cluster members only (NAME-1 / NAME-2)
        if re.search(r"-[12]$", name):
            gws.append({"name": name, "ip": ip})

    return gws


def get_vs(shell):

    out = run_cmd(shell, "vsx stat -v", 2)

    vs = []

    for l in out.splitlines():
        p = [x.strip() for x in l.split("|")]
        if len(p) >= 2 and p[0].isdigit():
            vs.append({
                "id": p[0],
                "name": p[1].split(" ", 1)[-1]
            })

    return vs


def worker(gw, cfg):

    name, ip = gw["name"], gw["ip"]
    results = []

    try:
        ssh = connect(cfg.mds_ip, cfg.auth.principal, cfg.auth.secret,
                      strict_host_key=_env_bool("FBUDDY_VSX_SSH_STRICT_HOST_KEY", False))
        sh = ssh.invoke_shell()
        time.sleep(1)

        # nested ssh: MDS shell -> gateway
        out = run_cmd(sh, f"ssh {cfg.auth.principal}@{ip}", 2)

        if "yes/no" in out:
            run_cmd(sh, "yes")

        if "assword" in out:
            run_cmd(sh, cfg.auth.secret)

        # active-member check: skip standby
        ha = run_cmd(sh, "cphaprob stat", 2)
        if "Standby" in ha:
            print(f"{name} SKIPPED (standby)")
            return []

        vs_list = get_vs(sh)

        for v in vs_list:

            if_meta = {
                "device": name, "context": v["name"], "vs_id": v["id"], "command": "interfaces"
            }
            raw_if = run_cmd(
                sh,
                f"vsenv {v['id']}; fw ctl set int vsid {v['id']}; ifconfig",
                2, max_wait=90, idle_grace=1.5, telemetry=if_meta
            )

            rt_meta = {
                "device": name, "context": v["name"], "vs_id": v["id"], "command": "routes"
            }
            raw_rt = run_cmd(
                sh,
                f"vsenv {v['id']}; fw ctl set int vsid {v['id']}; ip route",
                2, max_wait=120, idle_grace=2.0, telemetry=rt_meta
            )

            with _TELEMETRY_LOCK:
                _TELEMETRY.extend([if_meta, rt_meta])

            results.append({
                "source": "vsx",
                "device": name,
                "device_ip": ip,
                "vsys": v["name"],
                "vs_id": v["id"],
                "interfaces_raw": raw_if,
                "routes_raw": raw_rt
            })

        ssh.close()

    except Exception as e:
        err(f"{name} failed: {e}")

    return results


def run_vsx(cfg):

    info(">>> VSX RUNNER (RAW FINAL)")
    output_root = Path(cfg.runtime_paths.output_root)
    with _TELEMETRY_LOCK:
        _TELEMETRY.clear()

    strict_host_key = _env_bool("FBUDDY_VSX_SSH_STRICT_HOST_KEY", False)
    if not strict_host_key:
        warn(">>> VSX MDS host-key verification is compatibility mode (AutoAddPolicy); set FBUDDY_VSX_SSH_STRICT_HOST_KEY=1 for production hardening")
    ssh = connect(cfg.mds_ip, cfg.auth.principal, cfg.auth.secret, strict_host_key=strict_host_key)
    gws = discover_vsx(ssh)
    ssh.close()

    results = []

    with ThreadPoolExecutor(max_workers=3) as ex:
        futures = [ex.submit(worker, g, cfg) for g in gws]
        for f in as_completed(futures):
            results.extend(f.result())

    os.makedirs(output_root, exist_ok=True)
    json.dump(results, (output_root / "vsx_raw.json").open("w"), indent=2)

    with _TELEMETRY_LOCK:
        telemetry_snapshot = list(_TELEMETRY)
    json.dump(telemetry_snapshot, (output_root / "vsx_telemetry.json").open("w"), indent=2)

    info(f">>> VSX RAW DONE ({len(results)})")
    info(f">>> VSX TELEMETRY DONE ({len(telemetry_snapshot)} command samples)")