import json
import re
import os
import ipaddress
from pathlib import Path
from utils.logger import info


###############################################
# CLEAN RAW
###############################################
def clean_raw(raw):

    cleaned = []

    for line in raw.splitlines():

        line = line.strip()

        if not line:
            continue

        # ❌ skip junk
        if line.startswith("vsenv"):
            continue

        if "Context is set" in line:
            continue

        if line.startswith("[Expert@"):
            continue

        cleaned.append(line)

    return "\n".join(cleaned)


###############################################
# PARSE IFCONFIG
###############################################
def parse_ifconfig(raw):

    import re

    interfaces = []
    current = None

    for line in raw.splitlines():

        line = line.rstrip()

        # skip empty
        if not line.strip():
            continue

        # 🔥 SADECE GERÇEK INTERFACE
        if "Link encap" in line:

            name = line.split()[0]

            if name == "lo":
                current = None
                continue

            current = {
                "name": name,
                "ips": []
            }

            interfaces.append(current)
            continue

        # 🔥 IP SATIRI
        if "inet addr:" in line and current:

            ip = re.search(r"inet addr:([0-9\.]+)", line)
            mask = re.search(r"Mask:([0-9\.]+)", line)

            if ip and mask:

                ip_val = ip.group(1)
                mask_val = mask.group(1)

                prefix = sum(bin(int(x)).count("1") for x in mask_val.split("."))
                network = str(ipaddress.ip_network(f"{ip_val}/{prefix}", strict=False))

                current["ips"].append({
                    "ip": ip_val,
                    "prefix": prefix,
                    "network": network
                })

    return interfaces


###############################################
# PARSE ROUTES
###############################################
def parse_routes(raw):

    routes = []

    for line in raw.splitlines():

        line = line.strip()

        if not line:
            continue

        # default
        if line.startswith("default"):
            parts = line.split()
            routes.append({
                "network": "0.0.0.0/0",
                "next_hop": parts[2],
                "interface": parts[4],
                "type": "default"
            })
            continue

        # connected
        if "scope link" in line:
            parts = line.split()
            routes.append({
                "network": parts[0],
                "next_hop": None,
                "interface": parts[2],
                "type": "connected"
            })
            continue

        # static
        if "via" in line:
            parts = line.split()
            routes.append({
                "network": parts[0],
                "next_hop": parts[2],
                "interface": parts[4],
                "type": "static"
            })

    return routes


###############################################
# MAIN PARSER
###############################################
def run_vsx_parse(output_root=Path("output")):
    output_root = Path(output_root)

    info(">>> VSX PARSER START")

    raw_data = json.load((output_root / "vsx_raw.json").open())

    parsed = []

    for item in raw_data:

        clean_if = clean_raw(item["interfaces_raw"])
        clean_rt = clean_raw(item["routes_raw"])

        interfaces = parse_ifconfig(clean_if)
        routes = parse_routes(clean_rt)

        parsed.append({
            "source": "vsx",
            "device": item["device"],
            "device_ip": item["device_ip"],
            "vsys": item["vsys"],
            "vs_id": item["vs_id"],
            "interfaces": interfaces,
            "routing": routes
        })

    os.makedirs("output", exist_ok=True)

    json.dump(parsed, (output_root / "vsx.json").open("w"), indent=2)

    info(f">>> VSX PARSED ({len(parsed)})")