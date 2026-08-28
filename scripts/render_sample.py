"""Render the SecurityExpert UI from a synthetic inventory — a no-device local
sanity check.

Real collection needs an MDS / Panorama and credentials. This produces a
populated `index.html` from a small hand-built `unified.json` so the UI shell
and the mature Network Inventory / Overview / Project Plan modules can be
eyeballed on a laptop. Configuration / Compliance / Discovery render their
correct "no evidence collected" empty states — this script does not fabricate
configuration or compliance evidence.

    py -V:3.12 scripts/render_sample.py
    # then open the printed index.html path in a browser

Nothing is collected, no network access, no credentials. Output goes to a temp
directory (or --out) that is outside the repository.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))


def _status(data_state: str, availability: str, *, fresh: bool, reason: str | None = None) -> dict:
    now = datetime.now(timezone.utc).isoformat()
    return {
        "fresh": fresh,
        "data_state": data_state,
        "availability_state": availability,
        "current_run": "sample-render",
        "current_run_observed": True,
        "collected_at": now if fresh else None,
        "last_successful_collection": now,
        "stale_reason": reason,
    }


def _iface(name, ip, prefix, *, itype="physical", state="up", vr=None, vsys=None, zone=None):
    net = f"{'.'.join(ip.split('.')[:3])}.0/{prefix}"
    row = {"name": name, "type": itype, "state": state,
           "ips": [{"ip": ip, "prefix": prefix, "network": net}]}
    if vr is not None:
        row.update({"vr": vr, "vsys": vsys, "zone": zone, "ip": ip, "prefix": prefix, "network": net})
    return row


def _route(network, next_hop, interface, rtype="static", **extra):
    return {"network": network, "next_hop": next_hop, "interface": interface, "type": rtype, **extra}


def _sample_unified() -> list[dict]:
    live = _status("live", "available", fresh=True)
    lkg = _status("last_known_good", "communicating", fresh=False, reason="collection_failed")
    return [
        {
            "source": "cp", "device": "cp-edge-a", "vsys": "default",
            "interfaces": [_iface("eth0", "192.0.2.1", 24), _iface("eth1", "198.51.100.1", 24)],
            "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default"),
                       _route("10.0.0.0/8", "198.51.100.254", "eth1")],
            "inventory_status": live,
        },
        {
            "source": "cp", "device": "cp-cls-1", "vsys": "default", "cluster": "cp-cls",
            "cluster_topology": {"group_id": "sample01", "display_name": "cp-cls-CLS",
                                 "name_source": "inferred_member_pattern",
                                 "members": ["cp-cls-1", "cp-cls-2"], "cma": "CMA-A",
                                 "virtual_interfaces": [{"name": "eth0", "ip": "192.0.2.10", "role": "cluster_virtual"}]},
            "interfaces": [_iface("eth0", "192.0.2.11", 24), _iface("eth1", "203.0.113.11", 24)],
            "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default")],
            "inventory_status": live,
        },
        {
            "source": "cp", "device": "cp-cls-2", "vsys": "default", "cluster": "cp-cls",
            "interfaces": [_iface("eth0", "192.0.2.12", 24), _iface("eth1", "203.0.113.12", 24)],
            "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default")],
            "inventory_status": lkg,
        },
        {
            "source": "vsx", "device": "vsx-host-1", "vsys": "VS-PAYMENTS", "vs_id": "2", "cluster": "vsx-host",
            "interfaces": [_iface("wrp64", "198.51.100.20", 24), _iface("eth2.100", "203.0.113.20", 28, itype="vlan")],
            "routes": [_route("0.0.0.0/0", "198.51.100.254", "wrp64", "default"),
                       _route("172.16.0.0/12", "203.0.113.30", "eth2.100")],
            "inventory_status": live,
        },
        {
            "source": "panorama", "device": "pan-fw-01", "serial": "SAMPLE0000001",
            "management_ip": "192.0.2.40",
            "interfaces": [_iface("ethernet1/1", "203.0.113.41", 24, vr="default", vsys="vsys1", zone="untrust"),
                           _iface("ethernet1/2", "198.51.100.41", 24, vr="default", vsys="vsys1", zone="trust")],
            "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default"),
                       _route("10.10.0.0/16", "198.51.100.254", "ethernet1/2", vr="default")],
            "inventory_status": live,
        },
        {
            "source": "panorama", "device": "pan-fw-02", "serial": "SAMPLE0000002",
            "management_ip": "192.0.2.41",
            "interfaces": [_iface("ethernet1/1", "203.0.113.42", 24, vr="default", vsys="vsys1", zone="untrust")],
            "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default")],
            "inventory_status": _status("no_data", "disconnected", fresh=False, reason="management_disconnected"),
        },
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", help="output directory (default: a temp dir outside the repo)")
    args = parser.parse_args()

    out_root = Path(args.out).expanduser().resolve() if args.out else Path(
        tempfile.mkdtemp(prefix="securityexpert_sample_render_"))
    (out_root / "output").mkdir(parents=True, exist_ok=True)
    unified = out_root / "output" / "unified.json"
    unified.write_text(json.dumps(_sample_unified(), indent=2), encoding="utf-8")

    from utils.collection_executor import RuntimeCollectionServices
    from utils.html_export import run_html_export

    services = RuntimeCollectionServices()
    index_html = out_root / "output" / "index.html"
    run_html_export(
        unified_json=unified,
        output_html=index_html,
        config_result=None,           # no configuration evidence — honest empty state
        checkpoint_config_result=None,
        workflow_context={"mode": "sample-render", "label": "Sample render",
                          "checkpoint": False, "mixed_cycle": True},
        repository_root=REPO,
        lifecycle_store=services.lifecycle_store,
        capability_store=services.capability_store,
        coordinator=services.coordinator,
        scheduler_policy=services.scheduler_policy,
    )

    print()
    print("Sample UI rendered (synthetic inventory, no devices, no network).")
    print(f"  unified.json : {unified}")
    print(f"  index.html   : {index_html}")
    print()
    print("Open index.html in a browser. Populated: Overview, Network Inventory,")
    print("Project Plan. Empty-but-valid: Configuration, Compliance, Discovery")
    print("(no configuration/collection evidence exists in this render).")


if __name__ == "__main__":
    main()
