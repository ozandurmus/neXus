from pathlib import Path

from lxml import etree

from panorama.panorama_runtime_runner import parse_interfaces, parse_routes
import pytest

pytestmark = pytest.mark.inventory


FIXTURES = Path(__file__).parent / "fixtures" / "panorama"


def _xml(name: str):
    return etree.fromstring((FIXTURES / name).read_bytes())


def test_panorama_interface_parser_preserves_current_runtime_shape():
    interfaces = parse_interfaces(_xml("interfaces.xml"))

    assert interfaces == [
        {
            "name": "ethernet1/1",
            "type": "physical",
            "vr": "default",
            "vsys": "vsys1",
            "zone": "trust",
            "ip": "10.50.0.1",
            "prefix": 24,
            "network": "10.50.0.0/24",
        },
        {
            "name": "ethernet1/2.100",
            "type": "vlan",
            "vr": "VR-DMZ",
            "vsys": "vsys2",
            "zone": "dmz",
            "ip": "192.0.2.10",
            "prefix": 27,
            "network": "192.0.2.0/27",
        },
    ]


def test_panorama_route_parser_preserves_current_filters_and_fields():
    routes = parse_routes(_xml("routes.xml"))

    assert len(routes) == 3
    assert routes[0]["type"] == "connected"
    assert routes[0]["next_hop"] is None
    assert routes[1]["type"] == "static"
    assert routes[1]["interface"] == "ethernet1/1"
    assert routes[2]["network"] == "0.0.0.0/0"
    assert routes[2]["type"] == "default"


def test_panorama_default_route_should_have_default_type():
    routes = parse_routes(_xml("routes.xml"))

    default_route = next(route for route in routes if route["network"] == "0.0.0.0/0")
    assert default_route["type"] == "default"
