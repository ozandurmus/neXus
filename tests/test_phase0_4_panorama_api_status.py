import pytest

from panorama.panorama_runtime_runner import _parse_xml_response

pytestmark = pytest.mark.inventory


class FakeResponse:
    def __init__(self, content, status_code=200):
        self.content = content
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


def test_panorama_api_error_is_not_treated_as_empty_inventory():
    response = FakeResponse(
        b'<response status="error"><msg><line>Target firewall is not connected</line></msg></response>'
    )
    with pytest.raises(RuntimeError, match="not connected"):
        _parse_xml_response(response, "test operation")


def test_panorama_api_success_is_returned_for_parser():
    response = FakeResponse(b'<response status="success"><result><ok>1</ok></result></response>')
    root = _parse_xml_response(response, "test operation")
    assert root.get("status") == "success"
