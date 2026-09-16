from __future__ import annotations

import json
import stat
from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture()
def ldap_console(tmp_path):
    from fastapi.testclient import TestClient
    from console.app import create_app

    data_root = tmp_path / "data"
    (data_root / "state").mkdir(parents=True)
    runtime_paths = SimpleNamespace(
        repository_root=Path(__file__).resolve().parents[1],
        output_root=tmp_path / "output",
        data_root=data_root,
    )
    token = "test-launch-token"
    app = create_app(
        runtime_paths=runtime_paths,
        launch_token=token,
        bound_origin="http://127.0.0.1:8765",
        job_store=object(),
        runner=object(),
    )
    return SimpleNamespace(
        client=TestClient(app, base_url="http://127.0.0.1:8765"),
        token=token,
        runtime_paths=runtime_paths,
    )


def _headers(ldap_console):
    return {"Authorization": f"Bearer {ldap_console.token}"}


def test_ldap_settings_ui_is_console_only():
    root = Path(__file__).resolve().parents[1]
    console = (root / "templates" / "console.html").read_text(encoding="utf-8")
    report = (root / "templates" / "index.html").read_text(encoding="utf-8")

    assert 'data-module-panel="ldap-settings"' in console
    assert 'name="server_uri"' in console
    assert 'name="base_dn"' in console
    assert 'name="bind_dn"' in console
    assert 'type="password"' not in console
    assert 'data-module-panel="ldap-settings"' not in report


def test_ldap_settings_require_auth_and_persist_without_credentials(ldap_console):
    client = ldap_console.client
    settings = {
        "server_uri": "ldaps://directory.example.com:636",
        "base_dn": "dc=example,dc=com",
        "bind_dn": "cn=service,ou=users,dc=example,dc=com",
    }

    assert client.get("/api/settings/ldap").status_code == 401
    response = client.post("/api/settings/ldap", headers=_headers(ldap_console), json=settings)
    assert response.status_code == 200
    assert response.json() == settings
    assert client.get("/api/settings/ldap", headers=_headers(ldap_console)).json() == settings

    path = ldap_console.runtime_paths.data_root / "state" / "ldap_server.json"
    assert json.loads(path.read_text(encoding="utf-8")) == settings
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_ldap_settings_reject_insecure_uri_and_credentials(ldap_console):
    client = ldap_console.client
    settings = {"server_uri": "ldap://directory.example.com", "base_dn": "dc=example,dc=com", "bind_dn": ""}
    assert client.post("/api/settings/ldap", headers=_headers(ldap_console), json=settings).status_code == 400

    settings["server_uri"] = "ldaps://directory.example.com"
    settings["password"] = "not-accepted"
    assert client.post("/api/settings/ldap", headers=_headers(ldap_console), json=settings).status_code == 400
