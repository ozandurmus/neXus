import json
import os

from utils.logger import info


def run_topology(output_root="output"):
    from pathlib import Path
    output_root = Path(output_root)

    info(">>> BUILDING TOPOLOGY")

    data = json.load((output_root / "unified.json").open())

    nodes = []
    edges = []

    ip_map = {}

    ###############################################
    # BUILD NODES + IP MAP
    ###############################################
    for item in data:

        node_id = f"{item['device']}::{item['vs']}"

        nodes.append({
            "id": node_id,
            "label": item["device"],
            "vs": item["vs"],
            "source": item.get("meta", {}).get("source")
        })

        for iface in item.get("interfaces", []):
            for ip in iface.get("ips", []):
                ip_map[ip["ip"]] = node_id

    ###############################################
    # BUILD EDGES
    ###############################################
    for item in data:

        src = f"{item['device']}::{item['vs']}"

        for r in item.get("routes", []):

            nh = r.get("next_hop")

            if not nh:
                continue

            dst = ip_map.get(nh)

            if dst:

                edges.append({
                    "from": src,
                    "to": dst,
                    "label": r.get("network")
                })

    topo = {
        "nodes": nodes,
        "edges": edges
    }

    os.makedirs("output", exist_ok=True)

    json.dump(topo, (output_root / "topology.json").open("w"), indent=2)

    info(f">>> TOPOLOGY DONE ({len(nodes)} nodes / {len(edges)} edges)")