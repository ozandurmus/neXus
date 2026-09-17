"""GOV.ORCH.4-C: Chromium interaction checks, synthetic same-origin responses only.

Runtime: installed Python Playwright and its Chromium; run with
python3 -m pytest -q -p no:cacheprovider tests/test_orchestrator_dashboard_browser.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator_dashboard as dash  # noqa: E402


def row(movement_id, health="healthy", **fields):
    return {"movement_id": movement_id, "objective": "Synthetic " + movement_id,
            "health": health, "provider": "codex", "stage": "coding", **fields}


def board(*rows, archive=()):
    return {"open": list(rows), "archive": list(archive)}


@pytest.fixture(scope="module")
def browser():
    with sync_playwright() as runtime:
        browser = runtime.chromium.launch(headless=True)
        yield browser
        browser.close()


@pytest.fixture
def workbench(browser):
    context = browser.new_context()
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))

    def assets(route):
        path = route.request.url.split("/")[-1]
        if path == "dashboard-theme.css":
            route.fulfill(body=dash.product_theme_css(), content_type="text/css")
        else:
            path = path.split("#")[0] or "index.html"
            asset = ROOT / "scripts" / "dashboard_assets" / path
            assert asset.is_file(), route.request.url
            route.fulfill(path=asset)

    page.route("http://workbench.test/**", assets)
    page.add_init_script("""
      window.synthetic = { board: {open: []}, requests: [], pending: [], held: false };
      window.setInterval = function (callback) { synthetic.poll = callback; return 1; };
      const originalFetch = window.fetch;
      window.fetch = function (path, options) {
        if (!path.startsWith('/api/')) return originalFetch(path, options);
        const request = {path, method: options.method, body: options.body, headers: options.headers};
        synthetic.requests.push(request);
        const reply = (data, status = 200) => new Response(JSON.stringify(data), {status,
          headers: {'Content-Type': 'application/json'}});
        if (path === '/api/config') return Promise.resolve(reply({poll_interval_seconds: 3600, stuck_after_seconds: 900}));
        if (path === '/api/board') return Promise.resolve(reply(synthetic.board));
        return new Promise(resolve => {
          const item = {request, resolve: (data, status) => resolve(reply(data, status))};
          if (options.method === 'POST' || synthetic.held) synthetic.pending.push(item);
          else if (path.endsWith('/traffic')) item.resolve({traffic: []});
          else if (path.includes('/log?')) item.resolve({lines: ['synthetic log']});
          else item.resolve({movement_id: decodeURIComponent(path.split('/').pop()),
            health: 'healthy', process_status: 'running', work_stage: 'coding',
            approved_task: {report: {objective: 'synthetic detail'}}});
        });
      };
    """)

    def load(snapshot=None, token=True):
        page.goto("http://workbench.test/" + ("#t=synthetic-test-token" if token else ""))
        if token:
            poll(page, snapshot or board(row("A"), row("B")))
        return page

    yield page, load
    assert errors == []
    context.close()


def settle(page):
    page.evaluate("async () => { await new Promise(resolve => setTimeout(resolve, 0)); }")


def poll(page, snapshot=None):
    if snapshot is not None:
        page.evaluate("value => synthetic.board = value", snapshot)
    page.evaluate("synthetic.poll()")
    settle(page)


def select(page, movement_id):
    page.get_by_role("button", name=movement_id, exact=True).click()
    settle(page)


def traffic(page):
    pane = page.locator('[data-section="traffic"]')
    if not pane.evaluate("element => element.open"):
        pane.locator("summary").click()


def resolve(page, index, data, status=200):
    page.evaluate("args => synthetic.pending[args[0]].resolve(args[1], args[2])", [index, data, status])
    settle(page)


def test_navigation_classification_counts_and_exact_id_deduplication(workbench):
    page, load = workbench
    load(board(row("A"), row("B", "awaiting_po"), row("C", "failed"), row("D", "silent"),
               row("E", "exited_without_close"), row("F", "handed_over", idle_seconds=99999, last_activity="commit observed"),
               row("G", stage="integration"), row("H", "done"), row("I", "unrecognized"),
               row("J", "awaiting_po", phase="cancelled", process_status="exited", pr_number=1),
               row("K", "failed", terminal_outcome="CANCELLED"), row("01"), row("1"),
               archive=[row("A", archived=True, health="done"), row("L", "failed", relay_status="CLOSED")]))
    expect(page.get_by_role("button", name="Active (10)")).to_have_attribute("aria-pressed", "true")
    expect(page.get_by_role("button", name="Needs you (4)")).to_be_visible()
    expect(page.get_by_role("button", name="Archive (4)")).to_be_visible()
    expect(page.locator("#movement-rows tr")).to_have_count(10)
    expect(page.locator("#summary-strip")).to_contain_text("Stopped 2")
    expect(page.locator("#summary-strip")).to_contain_text("Running 3")
    expect(page.locator("#summary-strip")).to_contain_text("Failed 1")
    expect(page.locator("#movement-rows")).to_contain_text("Handed over")
    expect(page.locator("#movement-rows")).to_contain_text("commit observed")
    expect(page.locator("#movement-rows")).to_contain_text("inconsistent evidence")
    select(page, "B")
    assert page.locator("#detail-panel").bounding_box()["y"] > page.locator("#table-wrap").bounding_box()["y"]
    page.get_by_label("Search movement ID or objective").fill("synthetic a")
    expect(page.locator("#row-count")).to_have_text("1 matching loaded movements; 1 displayed")
    expect(page.locator("#detail-note")).to_contain_text("outside the current filter/search")
    poll(page)
    expect(page.get_by_label("Search movement ID or objective")).to_have_value("synthetic a")
    expect(page.locator("#detail-title")).to_have_text("B")
    page.get_by_label("Search movement ID or objective").fill("[literal]")
    expect(page.locator("#empty-state")).to_contain_text("No movements match this search")
    page.get_by_label("Search movement ID or objective").fill("")
    page.get_by_role("button", name="Needs you (4)").click()
    expect(page.locator("#movement-rows tr")).to_have_count(4)
    page.get_by_role("button", name="Archive (4)").click()
    expect(page.locator("#movement-rows tr")).to_have_count(4)
    expect(page.locator("#movement-rows")).to_contain_text("Done — outcome unavailable")
    assert not any(request["path"] == "/api/movements" for request in page.evaluate("synthetic.requests"))
    poll(page, board())
    expect(page.locator("#empty-state")).to_have_text("No loaded movements in Archive.")
    expect(page.locator("#detail-note")).to_contain_text("no longer available")


def test_draft_caret_focus_survive_polls_filters_close_and_switch(workbench):
    page, load = workbench
    load()
    select(page, "A")
    traffic(page)
    editor = page.get_by_label("Message to selected movement")
    editor.fill("draft A\nsecond line")
    editor.evaluate("element => element.setSelectionRange(3, 7, 'backward')")
    for _ in range(2):
        poll(page)
        expect(editor).to_be_focused()
        expect(editor).to_have_value("draft A\nsecond line")
        assert editor.evaluate("e => [e.selectionStart, e.selectionEnd, e.selectionDirection]") == [3, 7, "backward"]
    page.get_by_role("button", name="Archive (0)").click()
    expect(editor).to_have_value("draft A\nsecond line")
    page.get_by_role("button", name="Close movement details").click()
    expect(page.get_by_role("button", name="Archive (0)")).to_be_focused()
    page.get_by_role("button", name="Active (2)").click()
    select(page, "A")
    expect(editor).to_have_value("draft A\nsecond line")
    assert editor.evaluate("e => [e.selectionStart, e.selectionEnd]") == [3, 7]
    traffic(page)
    page.locator('[data-section="traffic"] summary').click()
    poll(page)
    traffic(page)
    expect(editor).to_have_value("draft A\nsecond line")
    select(page, "B")
    expect(editor).to_have_value("")
    editor.fill("draft B")
    select(page, "A")
    expect(editor).to_have_value("draft A\nsecond line")
    assert editor.evaluate("e => [e.selectionStart, e.selectionEnd]") == [3, 7]
    poll(page, board(row("B")))
    expect(editor).to_have_value("draft A\nsecond line")
    expect(page.locator("#detail-title")).to_have_text("A")
    expect(page.locator("#detail-note")).to_contain_text("Unsent draft retained")


def test_response_ordering_for_selection_close_and_same_movement(workbench):
    page, load = workbench
    load()
    page.evaluate("synthetic.held = true")
    select(page, "A")  # pending 0 detail, 1 traffic, 2 log
    select(page, "B")  # pending 3, 4, 5
    resolve(page, 3, {"movement_id": "B", "health": "healthy", "approved_task": {"report": {"objective": "new B"}}})
    resolve(page, 4, {"traffic": [{"subject": "B traffic"}]})
    resolve(page, 5, {"lines": ["B log"]})
    resolve(page, 0, {"movement_id": "A", "approved_task": {"report": {"objective": "stale A"}}})
    resolve(page, 1, {"error": "stale A error"}, 500)
    resolve(page, 2, {"lines": ["stale A log"]})
    expect(page.locator("#pane-summary")).to_contain_text("new B")
    expect(page.locator("#traffic-entries")).to_contain_text("B traffic")
    expect(page.locator("#pane-log")).to_contain_text("B log")
    poll(page)  # pending 6, 7, 8
    poll(page)  # pending 9, 10, 11
    resolve(page, 9, {"movement_id": "B", "approved_task": {"report": {"objective": "newest B"}}})
    resolve(page, 10, {"traffic": [{"subject": "newest traffic"}]})
    resolve(page, 11, {"lines": ["newest log"]})
    resolve(page, 6, {"error": "old same-movement error"}, 500)
    resolve(page, 7, {"traffic": [{"subject": "old traffic"}]})
    resolve(page, 8, {"error": "old log error"}, 500)
    expect(page.locator("#pane-summary")).to_contain_text("newest B")
    expect(page.locator("#traffic-entries")).to_contain_text("newest traffic")
    expect(page.locator("#pane-log")).to_contain_text("newest log")
    poll(page)  # pending 12, 13, 14
    page.get_by_role("button", name="Close movement details").click()
    resolve(page, 12, {"movement_id": "B", "health": "failed"})
    resolve(page, 13, {"error": "closed error"}, 500)
    resolve(page, 14, {"lines": ["closed log"]})
    expect(page.locator("#detail-panel")).to_be_hidden()
    expect(page.locator("#pane-summary")).to_contain_text("newest B")
    expect(page.locator("#pane-log")).to_contain_text("newest log")


@pytest.mark.parametrize("success", [True, False])
def test_send_isolation_new_edits_and_duplicate_submit(workbench, success):
    page, load = workbench
    load()
    select(page, "A")
    traffic(page)
    editor = page.get_by_label("Message to selected movement")
    editor.fill(" A submitted ")
    page.get_by_role("button", name="Send", exact=True).click()
    page.locator("#msg-send").evaluate("element => element.click()")
    assert page.evaluate("synthetic.pending.length") == 1
    assert json.loads(page.evaluate("synthetic.pending[0].request.body"))["text"] == " A submitted "
    editor.fill("A newer edit")
    select(page, "B")
    editor.fill("B untouched")
    if success:
        resolve(page, 0, {"appended": False, "delivery_state": "saved_not_yet_your_turn"})
    else:
        resolve(page, 0, {"error": "synthetic send failure"}, 500)
    expect(editor).to_have_value("B untouched")
    expect(page.locator("#msg-state")).to_have_text("")
    select(page, "A")
    expect(editor).to_have_value("A newer edit")
    expect(page.locator("#msg-state")).to_contain_text("saved locally" if success else "synthetic send failure")
    page.get_by_role("button", name="Send", exact=True).click()
    select(page, "B")
    resolve(page, 1, {"appended": True, "delivery_state": "saved_queued_for_next_resume"})
    expect(editor).to_have_value("B untouched")
    select(page, "A")
    expect(editor).to_have_value("")
    expect(page.locator("#msg-state")).to_contain_text("saved_queued_for_next_resume")
    expect(page.locator("#msg-state")).to_contain_text("appended to relay")


def test_usage_labels_zeros_and_mixed_totals(workbench):
    page, load = workbench
    usage = {"input_tokens": 0, "cache_read_input_tokens": 0, "output_tokens": 0,
             "total_tokens": 0, "cache_hit_ratio": 0, "model": "synthetic-model",
             "last_event_at": "2026-09-15T12:00:00Z"}
    load(board(row("reported", usage={**usage, "cost_usd": 0, "cost_source": "reported"}),
               row("observed", usage={**usage, "cost_usd": 1.2, "cost_source": "estimated"}),
               row("requested", model_requested="synthetic-model", usage={**usage, "cost_usd": 2.3, "cost_source": "estimated_from_requested_model"}),
               row("absent"), row("unpriced", usage=usage)))
    expect(page.locator("#movement-rows")).to_contain_text("Reported model-token cost: $0.0000")
    expect(page.locator("#movement-rows")).to_contain_text("Estimated model-token cost: $1.2000")
    expect(page.locator("#movement-rows")).to_contain_text("Estimated model-token cost: $2.3000 (requested model)")
    expect(page.locator("#movement-rows")).to_contain_text("Unknown — token/model evidence absent")
    expect(page.locator("#movement-rows")).to_contain_text("Estimate unavailable — model price missing")
    expect(page.locator("#movement-rows")).to_contain_text("0 in / 0 cache-read / 0 out (cache ratio 0%)")
    expect(page.locator("#movement-rows")).to_contain_text("Duration: Unavailable")
    expect(page.locator("#movement-rows")).to_contain_text("Verification: Unavailable")
    expect(page.locator("#summary-strip")).to_contain_text("Reported model-token cost $0.0000")
    expect(page.locator("#summary-strip")).to_contain_text("Estimated model-token cost $3.5000")
    expect(page.locator("#summary-strip")).to_contain_text("2 movements with missing cost")
    expect(page.locator("#summary-strip")).to_contain_text("Movement totals last active today (UTC)")
    select(page, "requested")
    page.evaluate("synthetic.held = true")
    poll(page)
    resolve(page, 0, {"movement_id": "requested", "usage": {**usage, "cost_usd": 2.3, "cost_source": "estimated_from_requested_model", "provider_default_audit_exception": True}})
    expect(page.locator("#pane-usage")).to_contain_text("requested model")
    expect(page.locator("#pane-usage")).to_contain_text("provider_default_audit_exception")


def test_archive_limit_order_and_relay_only_selection(workbench):
    page, load = workbench
    rows = [row(f"terminal-{i:02}", "done", ended_at=f"2026-09-{i + 1:02}T12:00:00Z", archived=True) for i in range(12)]
    rows += [row("missing-time", "done", archived=True)]
    load(board(archive=rows[::-1]))
    page.get_by_role("button", name="Archive (13)").click()
    expect(page.locator("#movement-rows tr")).to_have_count(10)
    expect(page.locator("#row-count")).to_have_text("13 matching loaded movements; 10 displayed")
    assert page.locator(".movement-button").all_text_contents()[0] == "terminal-11"
    page.get_by_role("button", name="Show all", exact=True).click()
    expect(page.locator("#movement-rows tr")).to_have_count(13)
    assert page.locator(".movement-button").all_text_contents()[-1] == "missing-time"
    before = page.evaluate("synthetic.requests.length")
    select(page, "terminal-11")
    assert page.evaluate("synthetic.requests.length") == before
    expect(page.locator("#detail-note")).to_contain_text("Relay-only record")
    expect(page.locator("#msg-box")).to_be_hidden()
    page.get_by_role("button", name="Show fewer").click()
    expect(page.locator("#movement-rows tr")).to_have_count(10)
    page.get_by_label("Search movement ID or objective").fill("terminal-01")
    expect(page.locator("#row-count")).to_have_text("1 matching loaded movements; 1 displayed")
    expect(page.get_by_role("button", name="Archive (13)")).to_be_visible()
    expect(page.locator("#detail-title")).to_have_text("terminal-11")


def test_native_keyboard_focus_and_escaped_synthetic_text(workbench):
    page, load = workbench
    load(board(row('A"<img src=x onerror=alert(1)>', objective="<script>window.pwned=true</script>")))
    page.get_by_role("button", name="Active (1)").focus()
    page.keyboard.press("Tab")
    expect(page.get_by_role("button", name="Needs you (0)")).to_be_focused()
    page.keyboard.press("Space")
    expect(page.get_by_role("button", name="Needs you (0)")).to_have_attribute("aria-pressed", "true")
    page.get_by_role("button", name="Active (1)").focus()
    page.keyboard.press("Enter")
    button = page.locator(".movement-button")
    button.focus()
    page.keyboard.press("Enter")
    settle(page)
    expect(page.locator("#detail-title")).to_have_text('A"<img src=x onerror=alert(1)>')
    assert page.locator("#movement-rows img, #movement-rows script").count() == 0
    summary = page.locator('[data-section="traffic"] summary')
    summary.focus()
    page.keyboard.press("Space")
    expect(page.locator('[data-section="traffic"]')).to_have_attribute("open", "")
    editor = page.get_by_label("Message to selected movement")
    editor.fill("line one")
    page.keyboard.press("Enter")
    expect(editor).to_have_value("line one\n")
    assert page.evaluate("synthetic.pending.length") == 0
    poll(page)
    expect(editor).to_be_focused()
    page.get_by_role("button", name="Close movement details").click()
    expect(button).to_be_focused()
    page.set_viewport_size({"width": 360, "height": 800})
    assert page.locator("#table-wrap").evaluate("e => e.scrollWidth > e.clientWidth")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")


def test_no_token_makes_no_api_requests_and_rejected_token_stops_polling(workbench):
    page, load = workbench
    load(token=False)
    expect(page.get_by_label("Dashboard token")).to_be_visible()
    assert page.evaluate("synthetic.requests.length") == 0
    page.get_by_label("refresh (s)").fill("10")
    page.get_by_label("refresh (s)").dispatch_event("change")
    assert page.evaluate("synthetic.requests.length") == 0
    page.get_by_label("Dashboard token").fill("synthetic-test-token")
    page.get_by_role("button", name="Use this token").click()
    settle(page)
    assert page.evaluate("synthetic.requests.every(r => r.headers.Authorization === 'Bearer synthetic-test-token')")
    page.evaluate("window.fetch = () => Promise.resolve(new Response(JSON.stringify({error:'rejected'}), {status:401}))")
    poll(page)
    expect(page.locator("#token-error")).to_contain_text("rejected")
    assert page.evaluate("localStorage.getItem('nexus_dashboard_token')") == ""


def test_action_responses_are_isolated_and_pending_consumed_ids_stay_disabled(workbench):
    page, load = workbench
    load()
    page.evaluate("synthetic.held = true")
    select(page, "A")
    action_a = {"requested_action": "synthetic approval", "blocker_source": "decision",
                "primary_action_id": "A-approve", "reject_action_id": "A-reject"}
    resolve(page, 0, {"movement_id": "A", "pending_action": action_a})
    page.get_by_role("button", name="Approve & record decision").click()  # pending 3
    page.locator("#exec-primary").evaluate("element => element.click()")
    assert page.evaluate("synthetic.pending.length") == 4
    poll(page)  # pending 4, 5, 6
    resolve(page, 4, {"movement_id": "A", "pending_action": action_a})
    expect(page.locator("#exec-primary")).to_be_disabled()
    expect(page.locator("#exec-reject")).to_be_disabled()
    select(page, "B")  # pending 7, 8, 9
    action_b = {"requested_action": "synthetic route", "blocker_source": "permission",
                "primary_operation": "route_to_engineer", "primary_action_id": "B-route", "reject_action_id": "B-reject"}
    resolve(page, 7, {"movement_id": "B", "pending_action": action_b})
    expect(page.get_by_role("button", name="Route to engineer")).to_be_enabled()
    resolve(page, 3, {"error": "stale A decision"}, 409)
    expect(page.locator("#action-state")).to_have_text("")
    expect(page.get_by_role("button", name="Route to engineer")).to_be_enabled()
    page.get_by_role("button", name="Reject", exact=True).click()  # pending 10
    resolve(page, 10, {"recorded": True})  # refresh pending 11, 12, 13
    resolve(page, 11, {"movement_id": "B", "pending_action": action_b})
    expect(page.get_by_role("button", name="Reject", exact=True)).to_be_disabled()
    expect(page.locator("#action-state")).to_have_text("Decision recorded")
    poll(page)  # pending 14, 15, 16
    resolve(page, 14, {"movement_id": "B", "pending_action": {**action_b, "blocker_source": "platform_policy"}})
    expect(page.locator(".platform-blocked")).to_contain_text("no button here can override")
    expect(page.locator(".exec-card button")).to_have_count(0)
