import paramiko
import os
import json
import time
import ipaddress
import hashlib
import re
import shlex
from datetime import datetime, timezone
from pathlib import Path

from utils.cp_ssh_trust import CpSshStrictPreflightError, apply_strict_host_key_policy
from utils.logger import info, warn, err
from utils.inventory_exclusions import (
    checkpoint_transport_value,
    load_inventory_exclusions,
)

REMOTE_RAW_DIR = "/home/admin/cp_raw"
LOCAL_RAW_DIR = "output/cp_raw"
TELEMETRY_OUT = "output/cp_telemetry.json"
REMOTE_COLLECTION_META = f"{REMOTE_RAW_DIR}/.collection_meta"
REMOTE_COLLECTION_STATUS = f"{REMOTE_RAW_DIR}/.collection_status.tsv"
LOCAL_COLLECTION_SCRIPT = Path(__file__).resolve().parent / "scripts" / "cp_inventory.sh"
REMOTE_COLLECTION_SCRIPT = "/home/admin/cp_inventory.sh"


###############################################
# HELPERS
###############################################
def safe_read(path):
    if not os.path.exists(path):
        return ""
    with open(path, encoding="utf-8", errors="ignore") as f:
        return f.read()


def derive_network(ip, prefix):
    try:
        return str(ipaddress.ip_interface(f"{ip}/{prefix}").network)
    except ValueError:
        return None


def _read_remote_text(sftp, path):
    try:
        with sftp.file(path, "r") as f:
            data = f.read()
            if isinstance(data, bytes):
                return data.decode("utf-8", errors="ignore")
            return str(data)
    except Exception:
        return ""


def _parse_collection_meta(raw):
    meta = {}
    for line in raw.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key in {
            "started_epoch", "completed_epoch", "discovered", "processed",
            "attempted", "successful", "partial", "failed",
            "management_up", "management_down", "management_unknown",
            "retried", "recovered_after_retry", "parallelism", "first_timeout_seconds", "retry_timeout_seconds",
            "max_retries",
        }:
            try:
                meta[key] = int(value)
                continue
            except ValueError:
                pass
        meta[key] = value
    return meta


def _as_int(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _parse_collection_status(raw):
    """Parse both the legacy 3-column and Phase 0.4.2 extended status TSV."""
    rows = []
    for line in raw.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue

        row = {
            "device": parts[0],
            "interface_rc": _as_int(parts[1], parts[1]),
            "route_rc": _as_int(parts[2], parts[2]),
        }

        # Phase 0.4.2 appends retry and failure-classification evidence while
        # keeping the first three fields backward compatible.
        if len(parts) >= 11:
            row.update({
                "interface_attempts": _as_int(parts[3]),
                "route_attempts": _as_int(parts[4]),
                "interface_first_rc": _as_int(parts[5], parts[5]),
                "route_first_rc": _as_int(parts[6], parts[6]),
                "interface_error": parts[7],
                "route_error": parts[8],
                "interface_first_error": parts[9],
                "route_first_error": parts[10],
            })
        if len(parts) >= 13:
            row["management_state"] = parts[11] or "unknown"
            row["collection_outcome"] = parts[12] or "unknown"
        # Phase 0.5.1 keeps the management target locally so a failed CPRID
        # collection can be tested through a direct read-only SSH fallback.
        # Support bundles HMAC-tokenize this value; it is never logged.
        if len(parts) >= 14:
            row["management_ip"] = parts[13] or None
        if len(parts) >= 15:
            row["cma"] = parts[14] or None
        # Phase 0.5.2 adds object classification plus non-blocking ClusterXL
        # virtual-interface probe telemetry after the legacy columns.
        if len(parts) >= 18:
            row["object_type"] = parts[15] or "unknown"
            row["vsx_cluster_member"] = parts[16] or "false"
            row["vs_cluster_member"] = parts[17] or "false"
        if len(parts) >= 21:
            row["cluster_probe_rc"] = _as_int(parts[18], parts[18])
            row["cluster_probe_attempts"] = _as_int(parts[19])
            row["cluster_probe_error"] = parts[20] or "unknown"

        rows.append(row)
    return rows


def _management_down(row):
    return row.get("collection_outcome") == "management_down" or row.get("interface_error") == "management_down"


def _command_failed(row):
    if _management_down(row):
        return False
    if row.get("interface_error") not in (None, "", "none"):
        return True
    if row.get("route_error") not in (None, "", "none"):
        return True
    return row.get("interface_rc") not in (0, "0") or row.get("route_rc") not in (0, "0")


def _command_retried(row):
    return (row.get("interface_attempts") or 0) > 1 or (row.get("route_attempts") or 0) > 1


def _recovered_after_retry(row):
    if not _command_retried(row):
        return False
    return not _command_failed(row)


def _sha256_bytes(data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _remote_bytes(sftp, path):
    with sftp.file(path, "r") as f:
        data = f.read()
    if isinstance(data, str):
        return data.encode("utf-8")
    return data


def _remove_remote_if_exists(sftp, path):
    try:
        sftp.remove(path)
    except OSError:
        pass


def _deploy_collection_script(sftp):
    """Upload the bundled collector and verify byte-for-byte integrity.

    This restores the original automated CP orchestration behavior while
    keeping the Phase 0.4 raw collection method unchanged.
    """
    if not LOCAL_COLLECTION_SCRIPT.exists():
        raise RuntimeError(f"CP collection script not found: {LOCAL_COLLECTION_SCRIPT}")

    local_bytes = LOCAL_COLLECTION_SCRIPT.read_bytes()
    local_sha256 = _sha256_bytes(local_bytes)

    sftp.put(os.fspath(LOCAL_COLLECTION_SCRIPT), REMOTE_COLLECTION_SCRIPT)
    remote_sha256 = _sha256_bytes(_remote_bytes(sftp, REMOTE_COLLECTION_SCRIPT))

    if remote_sha256 != local_sha256:
        raise RuntimeError("CP collection script upload verification failed")

    # Remove only the previous run markers before execution. If the remote
    # script fails before creating new markers, stale RAW is never accepted as
    # belonging to this run.
    _remove_remote_if_exists(sftp, REMOTE_COLLECTION_META)
    _remove_remote_if_exists(sftp, REMOTE_COLLECTION_STATUS)

    return {
        "local_sha256": local_sha256,
        "remote_sha256": remote_sha256,
        "upload_verified": True,
        "remote_path": REMOTE_COLLECTION_SCRIPT,
    }


def _process_collection_output_line(line, state):
    line = line.strip()
    if not line:
        return

    if line.startswith("TOTAL_GW="):
        try:
            state["total_gw"] = int(line.split("=", 1)[1])
        except ValueError:
            pass
        return

    if line.startswith(">>> GW:"):
        state["processed_gw"] += 1
        total = state.get("total_gw")
        if total:
            print(f"\r[CP {state['processed_gw']} / {total}] collecting live data...", end="", flush=True)
        else:
            print(f"\r[CP {state['processed_gw']}] collecting live data...", end="", flush=True)
        return

    if line == "DONE":
        state["done_marker_seen"] = True


def _remote_collection_command(*, exclude_vsx=False, excluded_device_names=()):
    exclusion_value = checkpoint_transport_value(excluded_device_names)
    assignments = [
        f"SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES={shlex.quote(exclusion_value)}"
    ]
    if exclude_vsx:
        assignments.append("SECURITYEXPERT_CP_EXCLUDE_VSX=1")
    return f"{' '.join(assignments)} bash -l {REMOTE_COLLECTION_SCRIPT}"


def _run_remote_collection(ssh, *, exclude_vsx=False, excluded_device_names=()):
    """Execute the uploaded collector and wait for it to really finish."""
    scope = "physical-non-vsx" if exclude_vsx else "baseline-all-managed-cp"
    info(f">>> CP REMOTE LIVE COLLECTION START (scope={scope})")
    command = _remote_collection_command(
        exclude_vsx=exclude_vsx,
        excluded_device_names=excluded_device_names,
    )
    _stdin, stdout, _stderr = ssh.exec_command(command)
    channel = stdout.channel

    state = {
        "total_gw": None,
        "processed_gw": 0,
        "done_marker_seen": False,
        "stderr_bytes": 0,
    }
    pending = ""

    while True:
        if channel.recv_ready():
            chunk = channel.recv(4096).decode("utf-8", errors="ignore")
            pending += chunk
            while "\n" in pending:
                line, pending = pending.split("\n", 1)
                _process_collection_output_line(line, state)

        if channel.recv_stderr_ready():
            chunk = channel.recv_stderr(4096)
            state["stderr_bytes"] += len(chunk)

        if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
            break

        time.sleep(0.05)

    if pending:
        _process_collection_output_line(pending, state)

    exit_status = channel.recv_exit_status()
    if state["processed_gw"]:
        print()

    state["exit_status"] = exit_status
    state["scope"] = scope
    state["inventory_exclusions"] = len(tuple(excluded_device_names))

    if exit_status != 0:
        raise RuntimeError(f"CP remote collection failed with exit status {exit_status}")
    if not state["done_marker_seen"]:
        raise RuntimeError("CP remote collection ended without DONE marker")

    if state["stderr_bytes"]:
        warn(f">>> CP collector emitted {state['stderr_bytes']} stderr bytes; telemetry/verification will determine data completeness")

    total = state.get("total_gw")
    info(f">>> CP REMOTE LIVE COLLECTION DONE ({state['processed_gw']}/{total if total is not None else '?'})")
    return state


def _validate_new_collection_marker(collection_meta):
    if not collection_meta:
        raise RuntimeError("CP remote collection produced no .collection_meta marker")

    started = collection_meta.get("started_epoch")
    completed = collection_meta.get("completed_epoch")
    discovered = collection_meta.get("discovered")

    if not isinstance(started, int) or not isinstance(completed, int):
        raise RuntimeError("CP collection marker is missing valid start/completion timestamps")
    if completed < started:
        raise RuntimeError("CP collection marker completion time is before start time")
    if not isinstance(discovered, int) or discovered < 0:
        raise RuntimeError("CP collection marker is missing a valid discovered gateway count")

    return True


###############################################
# INTERFACE PARSER
###############################################
def parse_interfaces(raw):

    interfaces = []
    current = None

    for line in raw.splitlines():
        line = line.strip()

        ###############################################
        # INTERFACE HEADER
        ###############################################
        if line and line[0].isdigit() and ":" in line:
            raw_name = line.split(":")[1].strip()

            name = raw_name.split("@")[0]
            parent = raw_name.split("@")[1] if "@" in raw_name else None

            # TYPE DETECTION
            if name == "lo":
                iface_type = "loopback"
            elif "." in name:
                iface_type = "vlan"
            elif parent:
                iface_type = "subinterface"
            else:
                iface_type = "physical"

            current = {
                "name": name,
                "parent": parent,
                "type": iface_type,
                "state": "up" if "UP" in line else "down",
                "ips": []
            }

            interfaces.append(current)
            continue

        ###############################################
        # IP PARSE
        ###############################################
        if "inet " in line and current:
            parts = line.split()
            ip_cidr = parts[1]

            if "/" not in ip_cidr:
                continue

            ip, prefix = ip_cidr.split("/")

            current["ips"].append({
                "ip": ip,
                "prefix": int(prefix),
                "network": derive_network(ip, prefix)
            })

    return interfaces


###############################################
# ROUTE PARSER (HARDENED)
###############################################
def parse_routes(raw):

    routes = []

    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue

        parts = line.split()

        ###############################################
        # STRONG FILTER
        ###############################################
        if "table" in parts and "local" in parts:
            continue

        if parts[0] in ["broadcast", "local"]:
            continue

        if "127." in line:
            continue

        route = {
            "network": None,
            "next_hop": None,
            "interface": None,
            "type": None,
            "protocol": None,
            "raw": line
        }

        ###############################################
        # DESTINATION
        ###############################################
        if parts[0] == "default":
            route["network"] = "0.0.0.0/0"
            route["type"] = "default"
        elif parts[0] == "blackhole":
            route["network"] = parts[1]
            route["type"] = "blackhole"
        else:
            route["network"] = parts[0]

        ###############################################
        # TOKENS
        ###############################################
        if "via" in parts:
            route["next_hop"] = parts[parts.index("via") + 1]

        if "dev" in parts:
            iface = parts[parts.index("dev") + 1]
            iface = iface.split("@")[0]
            route["interface"] = iface

        if "proto" in parts:
            route["protocol"] = parts[parts.index("proto") + 1]

        ###############################################
        # TYPE LOGIC
        ###############################################
        if not route["type"]:

            if route["network"] == "0.0.0.0/0":
                route["type"] = "default"

            elif route["next_hop"]:
                route["type"] = "static"

            elif route["protocol"] == "kernel":
                route["type"] = "connected"

            elif route["interface"] and not route["next_hop"]:
                route["type"] = "connected"

            else:
                route["type"] = "unknown"

        if route["network"]:
            routes.append(route)

    return routes


###############################################
# CLUSTERXL VIRTUAL INTERFACE PARSER (PHASE 0.5.2)
###############################################
def parse_cluster_virtual_interfaces(raw):
    """Parse read-only `cphaprob -a -m if` virtual interface evidence.

    The command explicitly reports the ClusterXL virtual interfaces and VIPs.
    We intentionally parse only that section; member IPs continue to come from
    the existing live `ip -details -4 addr show` collector.
    """
    rows = []
    in_virtual = False
    ip_pattern = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

    for raw_line in raw.splitlines():
        line = raw_line.strip()
        if not line:
            continue

        if line.lower().startswith("virtual cluster interfaces:"):
            in_virtual = True
            continue

        if not in_virtual:
            continue

        lowered = line.lower()
        if (
            lowered.startswith("no vlans")
            or lowered.startswith("vlan")
            or lowered.startswith("interface name:")
            or line.startswith("[")
        ):
            if rows:
                break
            continue

        match = ip_pattern.search(line)
        if not match:
            continue

        interface = line.split()[0]
        ip = match.group(0)
        try:
            ipaddress.ip_address(ip)
        except ValueError:
            continue

        rows.append({
            "name": interface,
            "ip": ip,
            "role": "cluster_virtual",
            "source": "cphaprob -a -m if",
        })

    return rows


def _member_base_name(name):
    # Some management objects in the real estate end with an extra separator
    # (for example NAME-1_ / NAME-2_).  Treat that cosmetic suffix exactly
    # like NAME-1 / NAME-2 for display-name inference only.  Runtime VIP
    # fingerprinting remains the authoritative cluster grouping key.
    text = str(name or "").strip()
    match = re.match(r"^(.*?)([-_.])([1-5])(?:[-_.])?$", text)
    return match.group(1) if match else ""


def _cluster_display_name(members):
    """Human-friendly label only; cluster identity never depends on this.

    Exact cluster identity is the live VIP fingerprint + CMA. If conventional
    member suffixes are present we offer the familiar `-CLS` display label but
    mark it as inferred so it cannot be confused with authoritative management
    object data.
    """
    bases = {_member_base_name(name) for name in members}
    bases.discard("")
    if len(bases) == 1:
        return next(iter(bases)) + "-CLS", "inferred_member_pattern"
    return "ClusterXL: " + " / ".join(sorted(members)), "runtime_vip_group"


def enrich_cluster_topology(results, collection_status):
    status_by_device = {row.get("device"): row for row in collection_status}
    groups = {}

    for item in results:
        virtuals = item.pop("_cluster_virtual_interfaces", [])
        if not virtuals:
            continue

        status = status_by_device.get(item.get("device"), {})
        cma = status.get("cma") or ""
        fingerprint_rows = sorted(
            (row.get("name") or "", row.get("ip") or "")
            for row in virtuals
            if row.get("ip")
        )
        if not fingerprint_rows:
            continue

        digest_source = json.dumps([cma, fingerprint_rows], separators=(",", ":"))
        group_id = hashlib.sha256(digest_source.encode("utf-8")).hexdigest()[:16]
        group = groups.setdefault(group_id, {
            "group_id": group_id,
            "cma": cma,
            "members": [],
            "virtual_interfaces": virtuals,
        })
        group["members"].append(item.get("device"))

    for group in groups.values():
        group["members"] = sorted(set(group["members"]))
        display_name, name_source = _cluster_display_name(group["members"])
        group["display_name"] = display_name
        group["name_source"] = name_source

    # Attach groups by matching the member name. One member belongs to at most
    # one classic ClusterXL group in this runtime inventory.
    by_member = {}
    for group in groups.values():
        for member in group["members"]:
            by_member[member] = group

    for item in results:
        group = by_member.get(item.get("device"))
        if group:
            item["cluster_topology"] = dict(group)

    return groups


###############################################
# VALIDATION (NEW 🔥)
###############################################
def validate(device, interfaces, routes):

    iface_names = {i["name"] for i in interfaces}

    for r in routes:
        iface = r.get("interface")

        if iface and iface not in iface_names:
            r["warning"] = f"interface_not_found:{iface}"

    return routes


###############################################
# MAIN
###############################################
def run_cp(cfg, *, exclude_vsx=False):

    info(">>> CP AUTOMATED LIVE COLLECTION + RAW PARSER MODE")

    output_root = Path(cfg.runtime_paths.output_root)
    local_raw_dir = output_root / "cp_raw"
    telemetry_out = output_root / "cp_telemetry.json"

    # Load and validate local-only exclusion policy before any network access.
    # A malformed policy must never broaden collection by being ignored.
    exclusion_policy = load_inventory_exclusions(cfg.runtime_paths.data_root)
    excluded_device_names = exclusion_policy.identities_for("checkpoint")
    if excluded_device_names:
        info(f">>> CP INVENTORY EXCLUSIONS ACTIVE (count={len(excluded_device_names)} source=runtime-policy)")
    else:
        warn(
            ">>> CP INVENTORY EXCLUSIONS NOT CONFIGURED (count=0); "
            "runtime data/state/inventory_exclusions.json has no active Check Point entries"
        )

    strict_host_key = os.getenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "").strip().lower() not in {"", "0", "false", "no", "off", "disabled"}
    ssh = paramiko.SSHClient()
    apply_strict_host_key_policy(ssh, strict_host_key)
    if not strict_host_key:
        warn(">>> CP MDS host-key verification is compatibility mode (AutoAddPolicy); set SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1 for production hardening")
    ssh.connect(cfg.mds_ip, **{"username": cfg.auth.principal, "password": cfg.auth.secret})

    ###############################################
    # DEPLOY + RUN THE BUNDLED LIVE COLLECTOR
    ###############################################
    deploy_sftp = ssh.open_sftp()
    try:
        collector_script = _deploy_collection_script(deploy_sftp)
    finally:
        deploy_sftp.close()

    collection_execution = _run_remote_collection(
        ssh,
        exclude_vsx=exclude_vsx,
        excluded_device_names=excluded_device_names,
    )

    ###############################################
    # DOWNLOAD RAW FILES
    ###############################################
    os.makedirs(local_raw_dir, exist_ok=True)

    sftp = ssh.open_sftp()

    try:
        sftp.chdir(REMOTE_RAW_DIR)
    except OSError:
        err("RAW directory not found — run collection first")
        sftp.close()
        ssh.close()
        return

    files = sftp.listdir()
    now_epoch = time.time()
    downloaded_at = datetime.now(timezone.utc).isoformat()
    file_telemetry = []

    collection_meta = _parse_collection_meta(
        _read_remote_text(sftp, REMOTE_COLLECTION_META)
    )
    collection_status = _parse_collection_status(
        _read_remote_text(sftp, REMOTE_COLLECTION_STATUS)
    )

    # The old marker was removed before the remote execution. Therefore a
    # valid marker here is proof that this invocation created the RAW set.
    _validate_new_collection_marker(collection_meta)

    devices = {}

    ###############################################
    # GROUP FILES
    ###############################################
    for f in files:

        if not f.endswith(".txt"):
            continue

        local_path = str(local_raw_dir / f)
        remote_path = f"{REMOTE_RAW_DIR}/{f}"

        try:
            stat = sftp.stat(remote_path)
            file_telemetry.append({
                "file": f,
                "size_bytes": stat.st_size,
                "mtime_epoch": int(stat.st_mtime),
                "age_seconds_at_download": max(0, int(now_epoch - stat.st_mtime)),
            })
        except Exception as exc:
            file_telemetry.append({"file": f, "stat_error": str(exc)})

        sftp.get(remote_path, local_path)

        if f.endswith("_interfaces.txt"):
            name = f[:-len("_interfaces.txt")]
            kind = "interfaces"
        elif f.endswith("_routes.txt"):
            name = f[:-len("_routes.txt")]
            kind = "routes"
        elif f.endswith("_cluster_if.txt"):
            name = f[:-len("_cluster_if.txt")]
            kind = "cluster_if"
        else:
            continue

        devices.setdefault(name, {})
        devices[name][f"{kind}_raw"] = safe_read(local_path)

    sftp.close()

    ###############################################
    # PARSE
    ###############################################
    results = []

    for device, data in devices.items():

        interfaces = parse_interfaces(data.get("interfaces_raw", ""))
        routes = parse_routes(data.get("routes_raw", ""))
        cluster_virtuals = parse_cluster_virtual_interfaces(data.get("cluster_if_raw", ""))

        routes = validate(device, interfaces, routes)

        results.append({
            "source": "cp",
            "device": device,
            "interfaces": interfaces,
            "routes": routes,
            "_cluster_virtual_interfaces": cluster_virtuals,
        })

    cluster_groups = enrich_cluster_topology(results, collection_status)
    for item in results:
        item.pop("_cluster_virtual_interfaces", None)

    ###############################################
    # SAVE
    ###############################################
    os.makedirs(output_root, exist_ok=True)

    with (output_root / "cp.json").open("w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    ages = [x.get("age_seconds_at_download") for x in file_telemetry if isinstance(x.get("age_seconds_at_download"), int)]
    failed_rows = [row for row in collection_status if _command_failed(row)]
    retried_rows = [row for row in collection_status if _command_retried(row)]
    recovered_rows = [row for row in collection_status if _recovered_after_retry(row)]
    management_down_rows = [row for row in collection_status if _management_down(row)]
    partial_rows = [row for row in collection_status if row.get("collection_outcome") == "partial"]
    attempted_rows = [row for row in collection_status if not _management_down(row)]

    # Phase 0.5.1: do not assume a CPRID failure means the appliance itself
    # is unreachable. Probe direct SSH capability only for failed/partial
    # devices. This is observe-only and does not alter cp.json yet.
    from checkpoint.direct_ssh_probe import probe_direct_ssh_fallback
    direct_ssh_probe = probe_direct_ssh_fallback(cfg, collection_status)

    cp_telemetry = {
        "downloaded_at": downloaded_at,
        "collector_script": {
            **collector_script,
            "remote_exit_status": collection_execution.get("exit_status"),
            "done_marker_seen": collection_execution.get("done_marker_seen"),
            "reported_total_gw": collection_execution.get("total_gw"),
            "processed_gw": collection_execution.get("processed_gw"),
            "stderr_bytes": collection_execution.get("stderr_bytes"),
            "inventory_exclusions": collection_execution.get("inventory_exclusions", 0),
        },
        "remote_collection_marker": {
            "available": bool(collection_meta),
            **collection_meta,
        },
        "remote_command_status": collection_status,
        # Kept local only. The shareable bundle HMAC-tokenizes device identity.
        "failed_devices": failed_rows,
        "management_down_devices": management_down_rows,
        "remote_files": file_telemetry,
        "summary": {
            "devices": len(results),
            "raw_txt_files": len(file_telemetry),
            "oldest_file_age_seconds": max(ages) if ages else None,
            "newest_file_age_seconds": min(ages) if ages else None,
            "command_status": "known" if collection_status else "unknown",
            "command_failures": len(failed_rows) if collection_status else None,
            "attempted_devices": len(attempted_rows) if collection_status else None,
            "successful_devices": collection_meta.get("successful") if collection_status else None,
            "partial_devices": len(partial_rows) if collection_status else None,
            "failed_devices": len(failed_rows) if collection_status else None,
            "management_down_devices": len(management_down_rows) if collection_status else None,
            "management_up_devices": collection_meta.get("management_up"),
            "management_unknown_devices": collection_meta.get("management_unknown"),
            "retried_devices": len(retried_rows) if collection_status else None,
            "recovered_after_retry": len(recovered_rows) if collection_status else None,
            "parallelism": collection_meta.get("parallelism"),
            "collection_mode": collection_meta.get("collection_mode"),
            "first_timeout_seconds": collection_meta.get("first_timeout_seconds"),
            "retry_timeout_seconds": collection_meta.get("retry_timeout_seconds"),
            "max_retries": collection_meta.get("max_retries"),
            "scope": "physical-non-vsx" if exclude_vsx else "baseline-all-managed-cp",
            "inventory_exclusions": collection_execution.get("inventory_exclusions", 0),
            "direct_ssh_probe_candidates": (direct_ssh_probe.get("summary") or {}).get("candidates"),
            "direct_ssh_reachable": (direct_ssh_probe.get("summary") or {}).get("ssh_reachable"),
            "direct_ssh_authenticated": (direct_ssh_probe.get("summary") or {}).get("authenticated"),
            "direct_ssh_inventory_cli_capable": (direct_ssh_probe.get("summary") or {}).get("inventory_cli_capable"),
            "direct_ssh_spark_hints": (direct_ssh_probe.get("summary") or {}).get("spark_hints"),
            "clusterxl_groups": len(cluster_groups),
            "clusterxl_members": sum(len(group.get("members", [])) for group in cluster_groups.values()),
            "clusterxl_virtual_interfaces": sum(len(group.get("virtual_interfaces", [])) for group in cluster_groups.values()),
        },
        "direct_ssh_probe_summary": direct_ssh_probe.get("summary") or {},
    }
    with telemetry_out.open("w", encoding="utf-8") as f:
        json.dump(cp_telemetry, f, indent=2)

    info(f">>> CP DONE ({len(results)} devices)")
    if failed_rows or management_down_rows or partial_rows:
        warn(
            ">>> CP COLLECTION DEGRADED "
            f"(success={collection_meta.get('successful')} partial={len(partial_rows)} "
            f"failed={len(failed_rows)} management_down={len(management_down_rows)} / "
            f"{len(collection_status)} discovered; retried={len(retried_rows)} "
            f"recovered={len(recovered_rows)})"
        )
    else:
        info(
            ">>> CP TELEMETRY DONE "
            f"(parallelism={collection_meta.get('parallelism')} retried={len(retried_rows)} recovered={len(recovered_rows)})"
        )

    ssh.close()
    return cp_telemetry
