from pathlib import Path

from checkpoint.cp_runner import parse_interfaces, parse_routes, validate
import pytest

pytestmark = pytest.mark.inventory


FIXTURES = Path(__file__).parent / "fixtures" / "cp"


def test_cp_interface_parser_preserves_current_shape_and_network_calculation():
    raw = (FIXTURES / "interfaces.txt").read_text(encoding="utf-8")

    interfaces = parse_interfaces(raw)

    assert interfaces == [
        {
            "name": "eth0",
            "parent": None,
            "type": "physical",
            "state": "up",
            "ips": [
                {
                    "ip": "10.10.10.2",
                    "prefix": 24,
                    "network": "10.10.10.0/24",
                }
            ],
        },
        {
            "name": "eth1",
            "parent": None,
            "type": "physical",
            "state": "down",
            "ips": [],
        },
        {
            "name": "eth2.100",
            "parent": "eth2",
            "type": "vlan",
            "state": "up",
            "ips": [
                {
                    "ip": "172.16.100.1",
                    "prefix": 25,
                    "network": "172.16.100.0/25",
                }
            ],
        },
        {
            "name": "lo",
            "parent": None,
            "type": "loopback",
            "state": "up",
            "ips": [
                {
                    "ip": "127.0.0.1",
                    "prefix": 8,
                    "network": "127.0.0.0/8",
                }
            ],
        },
    ]


def test_cp_route_parser_preserves_default_connected_static_and_blackhole():
    raw = (FIXTURES / "routes.txt").read_text(encoding="utf-8")

    routes = parse_routes(raw)

    assert [route["type"] for route in routes] == [
        "default",
        "connected",
        "static",
        "blackhole",
    ]
    assert routes[0]["network"] == "0.0.0.0/0"
    assert routes[0]["next_hop"] == "10.10.10.1"
    assert routes[0]["interface"] == "eth0"
    assert routes[1]["protocol"] == "kernel"
    assert routes[3]["network"] == "192.0.2.0/24"


def test_cp_validation_keeps_route_and_adds_warning_for_unknown_interface():
    routes = [
        {
            "network": "10.0.0.0/8",
            "next_hop": "192.0.2.1",
            "interface": "eth99",
            "type": "static",
            "protocol": "static",
            "raw": "10.0.0.0/8 via 192.0.2.1 dev eth99",
        }
    ]

    validated = validate(
        "fw01",
        [{"name": "eth0", "ips": []}],
        routes,
    )

    assert validated is routes
    assert validated[0]["warning"] == "interface_not_found:eth99"
