from checkpoint.cp_runner import parse_cluster_virtual_interfaces, enrich_cluster_topology


def test_parse_clusterxl_virtual_interfaces():
    raw = """
CCP mode: Manual (Unicast)
Required interfaces: 4
Virtual cluster interfaces: 3
eth0 192.168.3.247
eth2 44.55.66.247 VMAC address: 00:1C:7F:00:36:70
bond1 77.88.99.247
No VLANs are monitored on the member
"""
    rows = parse_cluster_virtual_interfaces(raw)
    assert rows == [
        {"name": "eth0", "ip": "192.168.3.247", "role": "cluster_virtual", "source": "cphaprob -a -m if"},
        {"name": "eth2", "ip": "44.55.66.247", "role": "cluster_virtual", "source": "cphaprob -a -m if"},
        {"name": "bond1", "ip": "77.88.99.247", "role": "cluster_virtual", "source": "cphaprob -a -m if"},
    ]


def test_cluster_group_identity_comes_from_vips_not_member_name():
    virtuals = [{"name": "eth0", "ip": "10.0.0.254", "role": "cluster_virtual", "source": "cphaprob -a -m if"}]
    results = [
        {"device": "FW-TEST-1", "_cluster_virtual_interfaces": list(virtuals), "interfaces": [], "routes": []},
        {"device": "FW-TEST-2", "_cluster_virtual_interfaces": list(virtuals), "interfaces": [], "routes": []},
    ]
    status = [
        {"device": "FW-TEST-1", "cma": "CMA1"},
        {"device": "FW-TEST-2", "cma": "CMA1"},
    ]
    groups = enrich_cluster_topology(results, status)
    assert len(groups) == 1
    group = next(iter(groups.values()))
    assert group["members"] == ["FW-TEST-1", "FW-TEST-2"]
    assert group["display_name"] == "FW-TEST-CLS"
    assert group["name_source"] == "inferred_member_pattern"
    assert results[0]["cluster_topology"]["group_id"] == results[1]["cluster_topology"]["group_id"]


def test_ui_contains_cp_cluster_aggregate_contract():
    from pathlib import Path
    text = (Path(__file__).resolve().parents[1] / "static" / "app.js").read_text(encoding="utf-8")
    assert "aggregateCpClusters" in text
    assert 'entityType = "cp_cluster"' in text
    assert 'addressRole: "cluster_virtual"' in text
    assert "Cluster VIP" in text
