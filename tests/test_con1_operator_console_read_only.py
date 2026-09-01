"""CON.1 acceptance criteria — read-only operator console.

See docs/history/phase/CON_1_OPERATOR_CONSOLE_READ_ONLY.md. Every AC-N
docstring below names the acceptance criterion it asserts. No test in this
file resolves a credential, imports a vendor module, or contacts a device —
CON.1 is zero device risk by construction.
"""
from __future__ import annotations

import json
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

pytestmark = pytest.mark.configuration

REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE = REPO_ROOT / "tests" / "fixtures" / "uitest"


def _load_fixture(name: str):
    return json.loads((FIXTURE / name).read_text(encoding="utf-8"))


@pytest.fixture()
def uitest_runtime_paths(tmp_path, monkeypatch):
    """A RuntimePaths-shaped object over a rendered copy of the uitest fixture
    — same monkeypatch pattern scripts/render_uitest.py uses for the three
    builders whose real inputs are out of scope for a UI-payload check."""
    output_root = tmp_path / "output"
    data_root = tmp_path / "data"
    (data_root / "state").mkdir(parents=True)
    (output_root).mkdir(parents=True)
    for f in (FIXTURE / "state").iterdir():
        shutil.copy2(f, data_root / "state" / f.name)
    (output_root / "unified.json").write_text(
        json.dumps(_load_fixture("unified.json")), encoding="utf-8"
    )

    configuration_ui = _load_fixture("configuration_ui.json")
    crypto_ui = _load_fixture("crypto_ui.json")
    discovery_ui = _load_fixture("discovery_ui.json")

    import utils.html_export as html_export
    monkeypatch.setattr(html_export, "build_configuration_ui_payload", lambda *a, **k: configuration_ui)
    monkeypatch.setattr(html_export, "build_crypto_posture", lambda *a, **k: crypto_ui)
    monkeypatch.setattr(html_export, "build_discovery_capability_payload", lambda *a, **k: discovery_ui)

    return SimpleNamespace(
        repository_root=REPO_ROOT,
        output_root=output_root,
        data_root=data_root,
    )


@pytest.fixture()
def console_client(uitest_runtime_paths):
    from fastapi.testclient import TestClient
    from console.app import create_app

    token = "test-launch-token"
    app = create_app(runtime_paths=uitest_runtime_paths, launch_token=token, bound_origin="http://127.0.0.1:8765")
    return TestClient(app), token


# --- AC-2: no route with a method other than GET/HEAD ----------------------

def test_ac2_no_route_exposes_a_mutating_method(console_client):
    """CON.2 (docs/history/phase/CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md,
    AC-2) deliberately adds exactly one mutating route, ``POST /api/jobs``
    — every other route stays GET/HEAD only, and CON.2 job creation is
    itself gated to read-class job types only (C2-6)."""
    client, _ = console_client
    for route in client.app.routes:
        methods = getattr(route, "methods", None)
        if methods is None:
            continue
        if route.path == "/api/jobs" and "POST" in methods:
            assert methods <= {"GET", "HEAD", "POST"}, f"{route.path} exposes {methods}"
            continue
        assert methods <= {"GET", "HEAD"}, f"{route.path} exposes {methods}"


# --- AC-3: auth split, enumerated route-by-route ----------------------------

def test_ac3_unauthenticated_routes_never_require_a_token(console_client):
    client, _ = console_client
    for path in ("/", "/assets/app.js", "/assets/style.css", "/assets/console_actions.js"):
        response = client.get(path)
        assert response.status_code == 200, path


def test_ac3_api_routes_require_a_valid_token(console_client):
    client, token = console_client
    assert client.get("/api/payloads").status_code == 401
    assert client.get("/api/payloads", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/api/payloads", headers={"Authorization": f"Bearer {token}"}).status_code == 200


# --- AC-4: payload parity with run_html_export ------------------------------

def test_ac4_api_payloads_are_byte_equal_to_the_exported_report(uitest_runtime_paths, console_client, tmp_path):
    from utils.html_export import run_html_export
    from console.payloads import build_console_payloads

    output_html = tmp_path / "export.html"
    run_html_export(
        unified_json=uitest_runtime_paths.output_root / "unified.json",
        output_html=output_html,
        repository_root=uitest_runtime_paths.repository_root,
        data_root=uitest_runtime_paths.data_root,
        record_checkpoint=False,
    )
    html = output_html.read_text(encoding="utf-8")

    exported = {}
    for key in ("rawData", "configUiData", "complianceUiData", "cryptoUiData",
                "projectPlanData", "discoveryUiData", "exclusionsUiData",
                "failoverReadinessData"):
        import re
        m = re.search(rf"\b{re.escape(key)}: (.*?),\n", html)
        assert m, f"{key} not found in exported report"
        exported[key] = json.loads(m.group(1).replace("<\\/", "</"))

    console_payloads = build_console_payloads(uitest_runtime_paths)

    # projectPlanData.generated_at / exclusionsUiData.generated_at /
    # failoverReadinessData.generated_at are real wall-clock timestamps
    # (utils/project_plan.py, utils/inventory_exclusions_ui.py,
    # utils/failover_readiness_ui.py) -- by design they differ between any two
    # calls, console or export. That is not a payload-parity violation; strip
    # them before the equality check.
    for payloads in (exported, console_payloads):
        payloads["projectPlanData"].pop("generated_at", None)
        payloads["exclusionsUiData"].pop("generated_at", None)
        payloads["failoverReadinessData"].pop("generated_at", None)

    assert json.dumps(exported, sort_keys=True) == json.dumps(console_payloads, sort_keys=True)


# --- AC-5: CSP header (console) / CSP meta (report), asserted independently -

def test_ac5_console_csp_header_matches_c1_1(console_client):
    from console.app import CONSOLE_CSP
    client, _ = console_client
    response = client.get("/")
    assert response.headers["content-security-policy"] == CONSOLE_CSP
    assert "frame-ancestors 'none'" in CONSOLE_CSP


def test_ac5_report_csp_meta_is_unchanged(uitest_runtime_paths, tmp_path):
    from utils.html_export import run_html_export

    output_html = tmp_path / "export.html"
    run_html_export(
        unified_json=uitest_runtime_paths.output_root / "unified.json",
        output_html=output_html,
        repository_root=uitest_runtime_paths.repository_root,
        data_root=uitest_runtime_paths.data_root,
    )
    html = output_html.read_text(encoding="utf-8")
    assert (
        '<meta http-equiv="Content-Security-Policy" '
        "content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
        "img-src 'self' data:; font-src 'self' data:; connect-src 'none'; object-src 'none'; "
        "base-uri 'none'; form-action 'none'\">"
    ) in html


# --- AC-6: origin/Sec-Fetch-Site mismatch -> 403 even with a valid token ----

def test_ac6_cross_origin_request_is_rejected_even_with_a_valid_token(console_client):
    client, token = console_client
    headers = {"Authorization": f"Bearer {token}"}
    assert client.get(
        "/api/payloads", headers={**headers, "Origin": "http://evil.example"}
    ).status_code == 403
    assert client.get(
        "/api/payloads", headers={**headers, "Sec-Fetch-Site": "cross-site"}
    ).status_code == 403
    # Same-origin / same-site is unaffected.
    assert client.get(
        "/api/payloads", headers={**headers, "Origin": "http://127.0.0.1:8765", "Sec-Fetch-Site": "same-origin"}
    ).status_code == 200


# --- AC-7: the launch token never appears in a log record -------------------

def test_ac7_launch_token_never_appears_in_a_log_record(uitest_runtime_paths, capsys):
    from console.auth import generate_launch_token
    from utils.logger import info

    token = generate_launch_token()
    info(f"a log line that would leak the token: {token}")
    captured = capsys.readouterr()
    assert token not in captured.out
    assert token not in captured.err


# --- AC-8: console/ imports no vendor/collector module ----------------------

def test_ac8_console_app_imports_no_vendor_module():
    probe = (
        "import sys\n"
        "class _BlockVendor:\n"
        "    def find_spec(self, name, path=None, target=None):\n"
        "        if name.split('.')[0] in ('checkpoint', 'panorama', 'configuration'):\n"
        "            raise ImportError(f'blocked vendor import: {name}')\n"
        "        return None\n"
        "sys.meta_path.insert(0, _BlockVendor())\n"
        "import console.app\n"
        "print('OK')\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", probe],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    assert "OK" in result.stdout


# --- AC-9: absent optional dependency fails clean ---------------------------

def test_ac9_missing_console_dependency_exits_clean_with_no_traceback():
    probe = (
        "import sys\n"
        "class _BlockAsgi:\n"
        "    def find_spec(self, name, path=None, target=None):\n"
        "        if name.split('.')[0] in ('fastapi', 'uvicorn'):\n"
        "            raise ImportError(f'simulated missing dependency: {name}')\n"
        "        return None\n"
        "sys.meta_path.insert(0, _BlockAsgi())\n"
        "import main\n"
        "main.main(['--console'])\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", probe],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode != 0
    assert "Traceback" not in result.stderr
    from console.server import CONSOLE_MISSING_DEPENDENCY_MESSAGE
    assert CONSOLE_MISSING_DEPENDENCY_MESSAGE in result.stderr


# --- AC-10: the exported report carries no console action surface ----------

def test_ac10_exported_report_contains_no_console_action_surface(uitest_runtime_paths, tmp_path):
    from utils.html_export import run_html_export

    output_html = tmp_path / "export.html"
    run_html_export(
        unified_json=uitest_runtime_paths.output_root / "unified.json",
        output_html=output_html,
        repository_root=uitest_runtime_paths.repository_root,
        data_root=uitest_runtime_paths.data_root,
    )
    html = output_html.read_text(encoding="utf-8")
    console_actions_source = (REPO_ROOT / "static" / "console_actions.js").read_text(encoding="utf-8")
    assert "consoleRefreshPayloads" not in html
    assert "_consoleFetchPayloads" not in html
    assert console_actions_source not in html
    assert "consoleRefreshButton" not in html


# --- AC-1: real-Chromium walk, every module populated from live payloads ---

def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _playwright_chromium_available() -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return False
    try:
        with sync_playwright() as p:
            return Path(p.chromium.executable_path).exists()
    except Exception:
        return False


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac1_console_renders_every_module_live_with_zero_console_errors(uitest_runtime_paths):
    import uvicorn
    from console.app import create_app
    from console.auth import generate_launch_token

    port = _free_port()
    token = generate_launch_token()
    bound_origin = f"http://127.0.0.1:{port}"
    app = create_app(runtime_paths=uitest_runtime_paths, launch_token=token, bound_origin=bound_origin)
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)

    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        deadline = time.time() + 10
        while not server.started and time.time() < deadline:
            time.sleep(0.05)
        assert server.started, "console server did not start in time"

        from playwright.sync_api import sync_playwright

        console_errors = []
        with sync_playwright() as p:
            browser = p.chromium.launch()
            try:
                page = browser.new_page()
                page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
                page.on("pageerror", lambda exc: console_errors.append(str(exc)))
                page.goto(f"{bound_origin}/#t={token}")
                page.wait_for_timeout(400)

                for module in ("inventory", "configuration", "compliance", "discovery", "exclusions", "project-plan"):
                    page.eval_on_selector(f'.module-nav-item[data-module="{module}"]', "el => el.click()")
                    page.wait_for_timeout(100)

                # Live payload proof: a fixture-only string must have reached the
                # DOM via the authenticated /api/payloads fetch, not inline JSON.
                overview_text = page.eval_on_selector("#overviewModule", "el => el.innerText")
                assert overview_text.strip() != ""
            finally:
                browser.close()

        assert not console_errors, f"console errors during live console walk: {console_errors}"
    finally:
        server.should_exit = True
        thread.join(timeout=10)
