import { test, expect, type Page, type Route } from "@playwright/test";

// VSX chassis failover (PO 2026-11-23: VS0 and every VS are independent units), offline browser proof: every API answer is a synthetic projection served from this file, so no
// service, session, device or credential is involved (the live suite's fixtures need a booted service and never click an
// action). Names are masked pseudonyms; addresses are never shown. Run:
//   node_modules/.bin/playwright test --config playwright.chassis-local.config.ts
// (Operations > HA and readiness > open the chassis > its Failover panel; a session that may start failovers.)

type Json = Record<string, unknown>;
const CLUSTER = "CLS-TANGO-04", CHASSIS_ID = "chassis-opaque", MEMBERS = [
  { device_id: "member-1", hostname: "FW-TANGO-04", ha_role: "ACTIVE" }, { device_id: "member-2", hostname: "FW-TANGO-05", ha_role: "STANDBY" }];
const iso = (offsetMs = 0) => new Date(Date.now() + offsetMs).toISOString();

const checks = (phase: string, vs: "PASS" | "FAIL" | null) => [ // vs: a historical check 16 row (no VS0 run records one since 2026-11-23)
  ...MEMBERS.flatMap(m => [{ phase, checkNo: 1, title: "Cluster state", member: m.hostname, device_id: m.device_id, status: "PASS", result: "PASS",
    summary: "Role confirmed", blocking: true, observedAt: iso(), derived: JSON.stringify({ role: m.ha_role.toLowerCase() }) }]),
  ...(vs ? MEMBERS.map(m => ({ phase, checkNo: 16, title: "Virtual systems moved", member: m.hostname, device_id: m.device_id, status: vs, result: vs,
    summary: vs === "PASS" ? "All 3 virtual systems follow the chassis" : "1 of 3 virtual systems did not move", blocking: true, observedAt: iso(), derived: "{}" })) : []),
];
const run = (over: Json = {}) => ({ runId: "run-opaque", approvalId: "approval-opaque", scheduledFor: "", state: "PRECHECK", step: "PRECHECK", outcome: null,
  failedCheck: null, message: null, steps: [], checks: [], kind: "FAILOVER", mutationPossible: false, ...over });
const resume = (over: Json = {}) => ({ eligible: true, refusal: null, canResume: true, businessHoursInside: false, businessHours: "08:00-18:00",
  formerActiveRef: "member-1", peerRef: "member-2", ...over });

/** The mocked service: what the unit list, the approvals and the runs answer, plus the writes the page made. */
async function serve(page: Page, unitOver: Json = {}) {
  const state = { unit: { unitId: CHASSIS_ID, clusterId: CHASSIS_ID, cluster_member_ref: CLUSTER, maskedName: CLUSTER, vendor: "check_point",
    unitKind: "VSX_CHASSIS", chassisMode: "HA", chassisAction: "FAILOVER", chassisStartRefusal: null,
    sequenceAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS", sequenceStartRefusal: null, canApprove: true, canStart: true, canSchedule: false,
    masked: false, mode: "HA", recoveryMode: "ACTIVE_UP", readinessObservedAt: iso(-60_000), members: MEMBERS, ...unitOver } as Json,
    approvals: [] as Json[], runDetail: null as Json | null, posts: [] as { path: string; body: unknown }[], refuse: null as null | { path: RegExp; status: number; body: Json },
    unknown: [] as string[], sequence: null as Json | null };
  await page.route("**/*", async (route: Route) => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    if (new URL(page.url() === "about:blank" ? url.href : page.url()).origin !== url.origin) { await route.abort("blockedbyclient"); return; }
    if (!(path.startsWith("/api/") || ["/session/status", "/notifications", "/project-plan", "/devices"].includes(path))) { await route.continue(); return; }
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", headers: { "X-Nexus-Masked": "true" }, body: JSON.stringify(body) });
    if (method !== "GET") {
      const body = request.postDataJSON() as unknown;
      state.posts.push({ path, body });
      if (state.refuse?.path.test(path)) { await json(state.refuse.body, state.refuse.status); return; }
      if (path.endsWith("/approvals")) {
        const b = body as { requestId: string; windowFrom: string; windowUntil: string; reason: string };
        state.approvals = [{ approvalId: "approval-opaque", requestId: b.requestId, revision: 1, policy: "ADMIN_SINGLE", approved: true, executionNonce: "nonce-opaque",
          canStart: true, windowFrom: b.windowFrom, windowUntil: b.windowUntil, reason: b.reason, revokedAt: null }];
        await json(state.approvals[0]); return;
      }
      if (path.endsWith("/cp-failover/runs")) { state.runDetail = run(); await json({ runId: "run-opaque" }); return; }
      if (path.endsWith("/resume-return")) { await json({ runId: "run-opaque", resumeId: "resume-opaque", state: "QUEUED" }); return; }
      await json({}); return;
    }
    if (path === "/session/status") await json({ authenticated: true, role_tokens: ["role:security_admin"], permissions: ["Admin", "Operations"], csrf_token: `csrf-${Date.now()}` });
    else if (path === "/devices") await json({ devices: MEMBERS.map(m => ({ ...m, vendor_hint: "check_point", enrollment_state: "ENROLLED", model: null, software_version: null, cluster_member_ref: CLUSTER })) });
    else if (path === "/api/v2/cp-failover/summary") await json([{ ...state.unit, activeWindow: false, lastRunState: null, lastRunOutcome: null, lastRunAt: null, canRunReadiness: true, readiness: null }]);
    else if (path === "/api/v2/cp-failover/units") await json([state.unit]);
    else if (path === "/api/v2/cp-failover/approvals") await json(state.approvals);
    else if (path === "/api/v2/cp-failover/runs") await json(state.runDetail ? [{ runId: "run-opaque", state: state.runDetail.state, kind: "FAILOVER" }] : []);
    else if (path.startsWith("/api/v2/cp-failover/runs/")) await json(state.runDetail ?? {});
    // A session that approves and starts alone (ADMIN_SINGLE) is the one the server offers the incident release.
    else if (path === "/api/v2/cp-failover/incidents") await json({ canRelease: true, roleNote: null, incidents: [] });
    else if (path === "/api/v2/job-window") await json({ open: true, server_time: iso(), window_end: iso(3_600_000), next_window_start: iso(86_400_000), admin_window_bypass: true,
      business_hours: { start: "08:00", end: "18:00", inside: false } });
    else if (path === "/api/v2/jobs/stats") await json({ total_24h: 0, completed_24h: 0, failed_24h: 0, running: 0 });
    else if (path === "/api/v2/system/pods" || path === "/api/v2/ssh-trust") await json({});
    else if (path === "/notifications") await json({ notifications: [] });
    else if (path === "/project-plan") await json({});
    else { state.unknown.push(path); await json({}); }
  });
  return state;
}

/** `expand: false` leaves the unit closed: its failover action is on the row and needs no expand step first. */
async function openChassis(page: Page, label = "VSX chassis · CLS-TANGO-04", expand = true) {
  await page.goto(`/?screen=operations&cluster_ref=${CLUSTER}`);
  const detail = page.getByRole("dialog", { name: "HA readiness detail" });
  if (expand) await detail.getByRole("button", { name: label }).click();
  else await expect(detail.getByRole("button", { name: label })).toBeVisible();
  return detail;
}
const NOTE = "change CHG-123 planned failover";
/** PO 2026-11-23: one dialog for an administrator -- no date field, a work log note, then the start. */
async function startFailover(page: Page, button: string) {
  await page.getByRole("button", { name: button, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: button });
  await expect(dialog.getByText("Valid for 60 minutes.")).toBeVisible();
  await expect(dialog.locator("input[type='datetime-local']")).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "Fail over now" })).toBeDisabled();
  await dialog.getByLabel("Work log note").fill(NOTE);
  await dialog.getByRole("button", { name: "Fail over now" }).click();
  await expect(dialog).toBeHidden();
}

test("VS0 run (HA mode): approve, start, the return completes and only then it is verified, on VS0 alone", async ({ page }) => {
  const mock = await serve(page);
  const detail = await openChassis(page, undefined, false);
  await expect(detail.getByText(/VS0 is its own failover unit \(VSX HA mode\)/)).toBeVisible();
  await expect(detail.getByRole("button", { name: "Fail over all virtual systems", exact: true })).toBeVisible();
  await startFailover(page, "Fail over VS0");
  expect(mock.posts.map(p => p.path)).toEqual(["/api/v2/cp-failover/approvals", "/api/v2/cp-failover/runs"]);
  const sent = mock.posts[0].body as { windowFrom: string; windowUntil: string; reason: string };
  expect(sent.reason).toBe(NOTE);
  expect(sent).not.toHaveProperty("operation");
  expect(Date.parse(sent.windowUntil) - Date.parse(sent.windowFrom)).toBe(60 * 60_000);
  expect(mock.posts[1].body).toMatchObject({ unitId: CHASSIS_ID, requestId: expect.any(String), revision: 1, executionNonce: "nonce-opaque", warningConfirmed: true });
  await expect(detail.getByRole("status").filter({ hasText: "Checking readiness" })).toBeVisible();
  mock.runDetail = run({ state: "POSTCHECK", step: "POSTCHECK", checks: [...checks("pre", null), ...checks("post", null)] });
  await expect(detail.getByRole("status").filter({ hasText: "Checking traffic and cluster after switch" })).toBeVisible();
  await expect(detail.getByText("Virtual systems moved")).toHaveCount(0);
  await expect(detail.getByText("Failover verified", { exact: true })).toHaveCount(1); // the stepper label only, not the status line
  mock.runDetail = run({ state: "RETURNING", step: "RETURNING", checks: [...checks("pre", null), ...checks("post", null)] });
  await expect(detail.getByRole("status").filter({ hasText: "Returning former active to standby" })).toBeVisible();
  mock.runDetail = run({ state: "DONE", step: "DONE", outcome: "SUCCEEDED", checks: [...checks("pre", null), ...checks("post", null), ...checks("post_return", null)] });
  await expect(detail.getByRole("status").filter({ hasText: "Failover verified" })).toBeVisible();
  await expect(detail.getByText(/Final post-checks: Recorded/)).toBeVisible();
  expect(mock.unknown).toEqual([]);
});

test("VS0 run (VSLS mode) stopped after the write: never 'Failover verified', the return is offered", async ({ page }) => {
  const mock = await serve(page, { chassisMode: "VSLS", mode: "VSLS" });
  const detail = await openChassis(page);
  await expect(detail.getByText(/VS0 is its own failover unit \(VSLS mode\)/)).toBeVisible();
  await startFailover(page, "Fail over VS0");
  mock.runDetail = run({ state: "STOPPED", step: "POSTCHECK", outcome: "STOPPED", message: "RETURN_TIMEOUT", failedCheck: "1", mutationPossible: true,
    incidentRef: "incident-opaque", lastConfirmedRoles: { "member-1": "DOWN", "member-2": "ACTIVE" }, checks: [...checks("pre", null), ...checks("post", null)],
    resume: resume() });
  const status = detail.getByRole("status").filter({ hasText: "Stopped after mutation" });
  await expect(status).toBeVisible();
  await expect(detail.getByRole("status").filter({ hasText: /^Failover verified$/ })).toHaveCount(0);
  await expect(detail.getByText(/Evidence coverage/)).toHaveCount(0);
  // The return is offered and nothing is resent without the operator's request.
  const offer = detail.getByRole("button", { name: "Resume return step" });
  await expect(offer).toBeVisible();
  expect(mock.posts.filter(p => p.path.endsWith("/resume-return"))).toHaveLength(0);
  await offer.click();
  const dialog = page.getByRole("dialog", { name: "Resume the return step of this failover run?" });
  await dialog.getByLabel("Work log note").fill("Return the former active");
  await dialog.getByRole("button", { name: "Resume return step" }).click();
  await expect.poll(() => mock.posts.filter(p => p.path.endsWith("/resume-return")).length).toBe(1);
  expect(mock.posts.find(p => p.path.endsWith("/resume-return"))!.body).toMatchObject({ expectedIncidentId: "incident-opaque", reason: "Return the former active" });
});

test("optional 'Fail over all virtual systems' runs the VSs one after another and stops at the first failed one", async ({ page }) => {
  const sequence = (over: Json = {}) => ({ sequenceId: "seq-opaque", state: "RUNNING", outcome: null, total: 3, started: 1, vsIds: ["1", "2", "3"],
    currentRunId: "run-opaque", failedVsId: null, requestedAt: iso(-60_000), finishedAt: null, ...over });
  const mock = await serve(page, { chassisMode: "VSLS", mode: "VSLS" });
  await openChassis(page);
  await expect(page.locator("[data-failover-action='FAILOVER_ALL_VIRTUAL_SYSTEMS']")).toHaveAttribute("title", /one after another/);
  await page.getByRole("button", { name: "Fail over all virtual systems", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Fail over all virtual systems" });
  await expect(dialog.getByText(/stops at the first virtual system that does not succeed/)).toBeVisible();
  await dialog.getByLabel("Work log note").fill(NOTE);
  await dialog.getByRole("button", { name: "Fail over now" }).click();
  await expect(dialog).toBeHidden();
  expect(mock.posts[0].body).toMatchObject({ operation: "FAILOVER_ALL_VIRTUAL_SYSTEMS" });
  // The server now reports the running sequence on the unit; the panel re-reads it (the unit list refreshes with the run).
  mock.unit = { ...mock.unit, sequence: sequence() };
  await page.reload();
  await page.getByRole("dialog", { name: "HA readiness detail" }).getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }).click();
  const running = page.getByRole("status").filter({ hasText: "virtual system 1 of 3 running" });
  await expect(running).toHaveAttribute("data-sequence-state", "RUNNING");
  await expect(page.getByRole("button", { name: "Fail over all virtual systems", exact: true })).toBeDisabled();
  mock.unit = { ...mock.unit, sequence: sequence({ started: 2, state: "RUNNING" }) };
  await page.reload();
  await page.getByRole("dialog", { name: "HA readiness detail" }).getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }).click();
  await expect(page.getByRole("status").filter({ hasText: "virtual system 2 of 3 running" })).toBeVisible();
  // The second virtual system fails: the sequence ends there and names it; the third is never started and the button is back
  // (each virtual system's run belongs to its own unit, so the chassis unit lists none).
  mock.runDetail = null; mock.approvals = []; // the spent approval is gone, so the remembered draft starts nothing on reload
  mock.unit = { ...mock.unit, sequence: sequence({ started: 2, state: "STOPPED", outcome: "VIRTUAL_SYSTEM_RUN_NOT_SUCCEEDED", failedVsId: "2", finishedAt: iso() }) };
  await page.reload();
  await page.getByRole("dialog", { name: "HA readiness detail" }).getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }).click();
  const stopped = page.getByRole("status").filter({ hasText: "Stopped: a virtual system run did not succeed (VSID 2)" });
  await expect(stopped).toHaveAttribute("data-sequence-state", "STOPPED");
  await expect(page.getByText(/virtual system 3 of 3/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Fail over all virtual systems", exact: true })).toBeEnabled();
  expect(mock.posts.filter(p => p.path.endsWith("/cp-failover/runs"))).toHaveLength(1);
});

test("refusal codes are shown in plain words, with the code kept on hover or beside the words", async ({ page }) => {
  // A VS not ready refuses only the sequence; the VS0 run stays available (they are independent).
  const mock = await serve(page, { sequenceStartRefusal: "VIRTUAL_SYSTEM_NOT_READY" });
  let detail = await openChassis(page);
  await expect(detail.getByText(/All virtual systems not now: A virtual system of this chassis is not ready/)).toBeVisible();
  await expect(detail.locator("[data-sequence-start-refusal]")).toHaveAttribute("title", "VIRTUAL_SYSTEM_NOT_READY");
  await expect(detail.getByRole("button", { name: "Fail over all virtual systems", exact: true })).toBeDisabled();
  await expect(detail.getByRole("button", { name: "Fail over VS0", exact: true })).toBeEnabled();
  // A VS0 mode the server cannot establish offers no VS0 run and says why; the sequence stays.
  mock.unit = { ...mock.unit, sequenceStartRefusal: null, chassisMode: "UNKNOWN", chassisAction: null, failoverRefusal: "VSX_CHASSIS_MODE_UNKNOWN" };
  await page.reload();
  detail = page.getByRole("dialog", { name: "HA readiness detail" });
  await detail.getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }).click();
  await expect(detail.getByText(/VSX chassis mode not known: run the chassis pre-checks/)).toBeVisible();
  await expect(detail.locator("[data-failover-refusal]")).toHaveAttribute("title", "VSX_CHASSIS_MODE_UNKNOWN");
  await expect(detail.getByRole("button", { name: /Fail over VS0|Start failover/ })).toHaveCount(0);
  await expect(detail.getByRole("button", { name: "Fail over all virtual systems", exact: true })).toBeEnabled();
  // A request the server refuses after the operator confirmed: the reason reads in words and the code and status stay.
  mock.unit = { ...mock.unit, chassisMode: "HA", chassisAction: "FAILOVER", failoverRefusal: null };
  mock.refuse = { path: /\/approvals$/, status: 409, body: { code: "VSX_CHASSIS_MODE_UNKNOWN" } };
  await page.reload();
  detail = page.getByRole("dialog", { name: "HA readiness detail" });
  await detail.getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }).click();
  await page.getByRole("button", { name: "Fail over VS0", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Fail over VS0" });
  await dialog.getByLabel("Work log note").fill(NOTE);
  await dialog.getByRole("button", { name: "Fail over now" }).click();
  await expect(dialog.getByRole("alert")).toHaveText("VSX chassis mode not known: run the chassis pre-checks (VSX_CHASSIS_MODE_UNKNOWN (HTTP 409))");
  expect(mock.posts.filter(p => p.path.endsWith("/cp-failover/runs"))).toHaveLength(0);
});
