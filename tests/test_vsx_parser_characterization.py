from pathlib import Path

from checkpoint.vsx_parser import clean_raw, parse_ifconfig, parse_routes


FIXTURES = Path(__file__).parent / "fixtures" / "vsx"


def test_vsx_clean_raw_removes_known_shell_noise_without_removing_payload():
    raw = (FIXTURES / "raw_with_shell_noise.txt").read_text(encoding="utf-8")

    cleaned = clean_raw(raw)

    assert "vsenv 12" not in cleaned
    assert "Context is set" not in cleaned
    assert "[Expert@" not in cleaned
    assert "eth0" in cleaned
    assert "inet addr:10.20.30.2" in cleaned


def test_vsx_ifconfig_parser_preserves_current_interface_and_ip_shape():
    raw = (FIXTURES / "ifconfig.txt").read_text(encoding="utf-8")

    interfaces = parse_ifconfig(raw)

    assert [item["name"] for item in interfaces] == ["eth0", "eth1"]
    assert interfaces[0]["ips"][0]["ip"] == "10.20.30.2"
    assert interfaces[0]["ips"][0]["prefix"] == 24
    assert interfaces[0]["ips"][0]["network"] == "10.20.30.0/24"


def test_vsx_ifconfig_network_should_be_canonical_network_address():
    raw = (FIXTURES / "ifconfig.txt").read_text(encoding="utf-8")

    interfaces = parse_ifconfig(raw)

    assert interfaces[0]["ips"][0]["network"] == "10.20.30.0/24"


def test_vsx_route_parser_preserves_current_default_connected_static_types():
    raw = (FIXTURES / "routes.txt").read_text(encoding="utf-8")

    routes = parse_routes(raw)

    assert routes == [
        {
            "network": "0.0.0.0/0",
            "next_hop": "10.20.30.1",
            "interface": "eth0",
            "type": "default",
        },
        {
            "network": "10.20.30.0/24",
            "next_hop": None,
            "interface": "eth0",
            "type": "connected",
        },
        {
            "network": "192.0.2.0/24",
            "next_hop": "172.16.5.254",
            "interface": "eth1",
            "type": "static",
        },
    ]
