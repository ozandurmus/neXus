import requests
from lxml import etree
import json
import os
import time
import ipaddress
from datetime import datetime, timezone
from pathlib import Path

from utils.logger import info, err, register_sensitive_value

requests.packages.urllib3.disable_warnings()
TELEMETRY_OUT = "output/panorama_telemetry.json"


###############################################
# HELPERS
###############################################
def derive_network(ip):
    try:
        return str(ipaddress.ip_interface(ip).network)
    except:
        return None


def fix_host(host):
    host = host.rstrip("/")
    if not host.startswith("http"):
        host = f"https://{host}"
    return host


def _parse_xml_response(response, operation):
    response.raise_for_status()
    root = etree.fromstring(response.content)
    if root.get("status") != "success":
        message = " ".join(
            text.strip()
            for text in root.xpath("//msg//text()")
            if text and text.strip()
        )
        raise RuntimeError(f"{operation} failed: {message or 'API status=error'}")
    return root


###############################################
# API
###############################################
def get_api_key(cfg, host):

    r = requests.get(f"{host}/api/", params={
        "type": "keygen",
        "user": cfg.auth.principal,
        "password": cfg.auth.secret
    }, verify=False, timeout=10)

    root = _parse_xml_response(r, "Panorama key generation")
    key = root.findtext(".//key")
    if not key:
        raise RuntimeError("Panorama key generation returned success without a key")
    return key


def op_cmd(host, key, cmd, target):

    params = {
        "type": "op",
        "cmd": cmd,
        "target": target,
        "key": key
    }

    try:
        r = requests.get(
            f"{host}/api/",
            params=params,
            verify=False,
            timeout=10
        )
        return _parse_xml_response(r, f"Operational command target={target}")

    except Exception as e:
        raise Exception(f"API error: {e}")


###############################################
# DEVICE LIST
###############################################
def get_devices(host, key):

    r = requests.get(f"{host}/api/", params={
        "type": "op",
        "cmd": "<show><devices><all></all></devices></show>",
        "key": key
    }, verify=False, timeout=10)

    tree = _parse_xml_response(r, "Panorama managed device discovery")

    devices = []

    for d in tree.xpath("//devices/entry"):
        serial = d.findtext("serial")
        hostname = d.findtext("hostname")
        connected = (d.findtext("connected") or "").strip().lower()
        management_ip = (d.findtext("ip-address") or "").strip()

        if serial:
            devices.append({
                "serial": serial,
                "hostname": hostname or serial,
                "connected": connected,
                "management_ip": management_ip or None,
            })

    return devices


###############################################
# INTERFACE PARSER (FINAL)
###############################################
def parse_interfaces(xml):

    interfaces = []

    for entry in xml.xpath("//result//ifnet/entry"):

        name = entry.findtext("name")

        if not name:
            continue

        ip = entry.findtext("ip")
        vsys = entry.findtext("vsys")
        zone = entry.findtext("zone")
        fwd = entry.findtext("fwd")

        # VR extract
        vr = None
        if fwd and "vr:" in fwd:
            vr = fwd.split("vr:")[-1]

        # FILTER
        if not ip or ip == "N/A":
            continue

        # TYPE
        if "." in name:
            iface_type = "vlan"
        elif name.startswith("ethernet"):
            iface_type = "physical"
        elif "ha" in name:
            iface_type = "ha"
        else:
            iface_type = "other"

        interfaces.append({
            "name": name,
            "type": iface_type,
            "vr": vr,
            "vsys": vsys,
            "zone": zone,
            "ip": ip.split("/")[0],
            "prefix": int(ip.split("/")[1]),
            "network": derive_network(ip)
        })

    return interfaces


###############################################
# ROUTE PARSER (FINAL)
###############################################
def parse_routes(xml):

    routes = []

    for r in xml.xpath("//result//entry"):

        dest = r.findtext("destination")
        nh = r.findtext("nexthop")
        iface = r.findtext("interface")
        vr = r.findtext("virtual-router")
        flags = r.findtext("flags") or ""

        if not dest:
            continue

        # FILTER HOST ROUTES
        if "/32" in dest and "H" in flags:
            continue

        route = {
            "network": dest,
            "next_hop": None if nh == "0.0.0.0" else nh,
            "interface": iface.split("@")[0] if iface else None,
            "vr": vr,
            "type": None,
            "raw_flags": flags
        }

        # TYPE FROM FLAGS - existing behavior preserved in Phase 0.4.
        if "S" in flags:
            route["type"] = "static"
        elif "C" in flags:
            route["type"] = "connected"
        elif dest == "0.0.0.0/0":
            route["type"] = "default"
        else:
            route["type"] = "unknown"

        routes.append(route)

    return routes


###############################################
# MAIN
###############################################
def run_panorama_runtime(cfg):

    info(">>> PANORAMA RUNTIME COLLECTION")
    output_root = Path(cfg.runtime_paths.output_root)
    raw_root = output_root / "panorama_raw"

    host = fix_host(cfg.panorama_ip)
    key = get_api_key(cfg, host)
    register_sensitive_value(key, "[API_KEY:REDACTED]")

    devices = get_devices(host, key)

    info(f">>> FOUND {len(devices)} DEVICES")

    os.makedirs(raw_root, exist_ok=True)

    results = []
    telemetry = []

    for i, d in enumerate(devices, 1):

        serial = d["serial"]
        name = d["hostname"]
        row = {
            "device": name,
            "serial": serial,
            "connected": d.get("connected"),
            "management_ip": d.get("management_ip"),
            "started_at": datetime.now(timezone.utc).isoformat(),
            "interfaces": {"status": "pending"},
            "routes": {"status": "pending"},
        }

        print(f"[{i}/{len(devices)}] {name}")

        ###############################################
        # INTERFACES
        ###############################################
        try:
            started = time.monotonic()
            int_xml = op_cmd(
                host,
                key,
                "<show><interface>all</interface></show>",
                serial
            )

            with (raw_root / f"{serial}_interfaces.xml").open("wb") as f:
                f.write(etree.tostring(int_xml))

            interfaces = parse_interfaces(int_xml)
            row["interfaces"] = {
                "status": "success",
                "duration_ms": int((time.monotonic() - started) * 1000),
                "parsed": len(interfaces),
            }

        except Exception as e:
            row["interfaces"] = {"status": "failed", "error": str(e)}
            row["completed_at"] = datetime.now(timezone.utc).isoformat()
            telemetry.append(row)
            err(f"{name} interface error: {e}")
            continue

        ###############################################
        # ROUTES
        ###############################################
        try:
            started = time.monotonic()
            route_xml = op_cmd(
                host,
                key,
                "<show><routing><route></route></routing></show>",
                serial
            )

            with (raw_root / f"{serial}_routes.xml").open("wb") as f:
                f.write(etree.tostring(route_xml))

            routes = parse_routes(route_xml)
            row["routes"] = {
                "status": "success",
                "duration_ms": int((time.monotonic() - started) * 1000),
                "parsed": len(routes),
            }

        except Exception as e:
            row["routes"] = {"status": "failed", "error": str(e)}
            row["completed_at"] = datetime.now(timezone.utc).isoformat()
            telemetry.append(row)
            err(f"{name} route error: {e}")
            continue

        ###############################################
        # BUILD
        ###############################################
        results.append({
            "source": "panorama",
            "device": name,
            "serial": serial,
            "management_ip": d.get("management_ip"),
            "interfaces": interfaces,
            "routes": routes
        })

        row["completed_at"] = datetime.now(timezone.utc).isoformat()
        telemetry.append(row)
        time.sleep(0.2)

    ###############################################
    # SAVE
    ###############################################
    os.makedirs(output_root, exist_ok=True)

    with (output_root / "panorama_runtime.json").open("w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    telemetry_payload = {
        "discovered": len(devices),
        "connected_yes": sum(1 for d in devices if d.get("connected") == "yes"),
        "connected_no": sum(1 for d in devices if d.get("connected") == "no"),
        "successful": len(results),
        "failed": len(devices) - len(results),
        "devices": telemetry,
    }
    with (output_root / "panorama_telemetry.json").open("w", encoding="utf-8") as f:
        json.dump(telemetry_payload, f, indent=2)

    info(f">>> PANORAMA RUNTIME DONE ({len(results)} devices)")
    info(
        ">>> PANORAMA TELEMETRY DONE "
        f"(discovered={len(devices)} success={len(results)} failed={len(devices) - len(results)})"
    )
