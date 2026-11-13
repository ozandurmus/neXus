import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ScheduledJobsPanel, savedSentence, untilText } from "../src/screens/ScheduledJobsPanel";
import { setBusinessHoursConfirm, type ScheduledJobFamily, type ScheduledJobsView } from "../src/auth/adminApi";

const NOW = new Date("2026-11-10T18:30:00Z"); // 21:30 GMT+3
const LABELS = ["Discovery refresh", "Inventory collection", "Configuration refresh", "HA readiness", "Scheduled backup", "System log collection"];
const KEYS = ["discovery_refresh", "nightly_inventory", "configuration_refresh", "ha_readiness", "scheduled_backup", "system_log_collection"];

function family(index: number, over: Partial<ScheduledJobFamily> = {}): ScheduledJobFamily {
  return {
    family: KEYS[index], label: LABELS[index], description: `Synthetic description of ${LABELS[index]}.`,
    cadence: "Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3", enabled: true, state: "ON", lock_reason: null, note: null,
    window_open_now: false, next_window_at: "2026-11-11T00:00:00+03:00", last_run_at: "2026-11-10T15:00:01Z",
    changed_by: null, changed_at: null, reason: null, ...over,
    // The server sets will_run: true only when ON and nothing else stops the family.
    will_run: over.will_run ?? (over.state ?? "ON") === "ON",
  };
}

function view(over: Partial<ScheduledJobsView> = {}, families?: ScheduledJobFamily[]): ScheduledJobsView {
  return {
    master_enabled: true, master_env: "UI2_SCHEDULING_ENABLED", can_write: true, masked: false,
    business_hours: { start: "08:00", end: "20:00", zone: "Europe/Istanbul", inside_now: false },
    families: families ?? [
      family(0, { changed_by: "admin-user", changed_at: "2026-11-10T18:05:00Z", reason: "PO: start data collection" }),
      family(1, { next_window_at: "2026-11-11T02:00:00+03:00" }),
      family(2, { enabled: false, state: "OFF", changed_by: "admin-user", changed_at: "2026-11-09T09:00:00Z", reason: "Maintenance on the management servers" }),
      family(3),
      family(4, { will_run: false, note: "Backup policy schedule is off (Backups screen)", last_run_at: null }),
      family(5),
    ],
    ...over,
  };
}

type Sent = { url: string; method: string; body: unknown; ack: string | null };
type PutAnswer = (sent: Sent) => { status: number; body: unknown };

/** A routed fetch: the GET answers from `state.view` (so a re-read sees the server's new truth), the PUT from `put`. */
function server(state: { view: ScheduledJobsView }, put?: PutAnswer) {
  const sent: Sent[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
    if (url === "/session/status") return json({ csrf_token: "csrf-synthetic" });
    const method = init?.method ?? "GET";
    const call: Sent = { url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined,
      ack: (init?.headers as Record<string, string> | undefined)?.["X-Nexus-Business-Hours-Ack"] ?? null };
    sent.push(call);
    if (method === "GET" && url === "/api/v2/config/scheduled-jobs") return json(state.view);
    if (method === "PUT" && put) { const answer = put(call); return json(answer.body, answer.status); }
    return json({ code: "NOT_MOCKED" }, 404);
  }));
  return sent;
}

const show = () => render(<ThemeProvider theme={m3Theme}><ScheduledJobsPanel now={NOW} /></ThemeProvider>);
const row = (key: string) => within(screen.getByTestId(`scheduled-family-${key}`));

afterEach(() => { setBusinessHoursConfirm(null); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("ScheduledJobsPanel", () => {
  it("lists every family with its state as a word, cadence, last run, next window and last change", async () => {
    server({ view: view() });
    show();
    await screen.findByRole("list", { name: "Scheduled job families" });
    for (const [i, key] of KEYS.entries()) {
      expect(row(key).getByRole("heading", { name: LABELS[i] })).toBeInTheDocument();
      expect(row(key).getByText("Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3")).toBeInTheDocument();
      expect(screen.getByTestId(`scheduled-family-${key}`).querySelector("[data-fact='scheduled-family-state']"))
        .toHaveTextContent(key === "configuration_refresh" ? "OFF" : key === "scheduled_backup" ? "ON, but idle" : "ON");
    }
    // What runs next, at a glance: the OFF family is not counted and the earliest ON window is named.
    const summary = screen.getByTestId("scheduled-jobs-summary");
    expect(summary).toHaveTextContent("4 of 6 job families will run");
    expect(summary).toHaveTextContent("ON, but idle: Scheduled backup");
    expect(summary).toHaveTextContent(/Next to run: Discovery refresh, HA readiness, System log collection · 2026-11-11 00:00 GMT\+3 · in 3 h/);
    expect(row("discovery_refresh").getByText(/admin-user/)).toBeInTheDocument();
    expect(row("discovery_refresh").getByTestId("scheduled-family-reason")).toHaveTextContent("Reason: PO: start data collection");
    expect(row("discovery_refresh").getByText(/2026-11-10 18:00 GMT\+3/)).toBeInTheDocument();
    expect(row("configuration_refresh").getByText("Will not run while OFF")).toBeInTheDocument();
    expect(row("nightly_inventory").getByText("Not changed since installation")).toBeInTheDocument();
    expect(row("scheduled_backup").getByText("No run recorded")).toBeInTheDocument();
    // ON with will_run false reads as idle with the reason, never as running.
    expect(row("scheduled_backup").getByTestId("scheduled-family-note")).toHaveTextContent("Idle: Backup policy schedule is off (Backups screen)");
    expect(row("scheduled_backup").getByText("Will not run while idle")).toBeInTheDocument();
    expect(screen.getByLabelText("Scheduled backup: scheduled jobs on")).toBeChecked();
    expect(screen.getAllByRole("switch")).toHaveLength(6);
    expect(screen.getByLabelText("Configuration refresh: scheduled jobs on")).not.toBeChecked();
    expect(screen.queryByTestId("scheduled-jobs-master-off")).toBeNull();
    expect(screen.queryByTestId("scheduled-jobs-read-only")).toBeNull();
  });

  it("shows the deployment banner and locked, disabled switches when the master switch is off", async () => {
    const lock = "Deployment switch UI2_SCHEDULING_ENABLED=false";
    server({ view: view({ master_enabled: false }, KEYS.map((_, i) => family(i, { state: "LOCKED", lock_reason: lock }))) });
    show();
    const banner = await screen.findByTestId("scheduled-jobs-master-off");
    expect(banner).toHaveTextContent("Scheduling is switched off by the deployment (UI2_SCHEDULING_ENABLED=false)");
    expect(banner).toHaveTextContent(/needs a deployment change/);
    // A state, not a fault: the banner is a status, never an error alert.
    expect(banner).toHaveAttribute("role", "status");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByTestId("scheduled-jobs-summary")).toHaveTextContent("0 of 6 job families will run");
    expect(screen.getByTestId("scheduled-jobs-summary")).toHaveTextContent("Nothing is scheduled to run.");
    for (const key of KEYS) {
      expect(screen.getByTestId(`scheduled-family-${key}`).querySelector("[data-fact='scheduled-family-state']")).toHaveTextContent("LOCKED");
      expect(row(key).getByTestId("scheduled-family-lock")).toHaveTextContent(`Locked: ${lock}`);
      expect(row(key).getByText("Will not run while LOCKED")).toBeInTheDocument();
      expect(row(key).getByRole("switch")).toBeDisabled();
    }
  });

  it("offers a read-only role no switch and no write control, only the facts and who may change them", async () => {
    server({ view: view({ can_write: false }) });
    show();
    expect(await screen.findByTestId("scheduled-jobs-read-only"))
      .toHaveTextContent("Read-only: turning a job family on or off needs the Security Admin or Operation Admin role.");
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(screen.getAllByRole("button").map((b) => b.textContent)).toEqual(["Refresh"]);
    expect(row("configuration_refresh").getByText("Will not run while OFF")).toBeInTheDocument();
  });

  it("a read-only role still reads why a family is LOCKED", async () => {
    server({ view: view({ can_write: false, master_enabled: false },
      KEYS.map((_, i) => family(i, { state: "LOCKED", lock_reason: "Deployment switch UI2_SCHEDULING_ENABLED=false" }))) });
    show();
    await screen.findByTestId("scheduled-jobs-master-off");
    expect(screen.getAllByTestId("scheduled-family-lock")).toHaveLength(6);
    expect(screen.queryByRole("switch")).toBeNull();
  });

  it("flipping a switch saves nothing until a valid reason is given; Save sends {enabled, reason} and reports the cancelled jobs", async () => {
    const state = { view: view() };
    const sent = server(state, (call) => {
      const off = family(0, { enabled: false, state: "OFF", changed_by: "admin-user", changed_at: "2026-11-10T18:31:00Z", reason: (call.body as { reason: string }).reason });
      state.view = view({}, [off, ...state.view.families.slice(1)]);
      return { status: 200, body: { ...off, cancelled_jobs: 3, closed_runs: 1 } };
    });
    show();
    fireEvent.click(await screen.findByLabelText("Discovery refresh: scheduled jobs on"));
    const dialog = screen.getByRole("dialog", { name: "Turn OFF Discovery refresh?" });
    expect(dialog).toHaveTextContent("No new Discovery refresh jobs will be submitted from the next tick. Scheduled Discovery refresh jobs still queued are cancelled; jobs already running finish; manually started jobs are not touched.");
    // The switch itself has not moved and nothing was sent.
    expect(screen.getByLabelText("Discovery refresh: scheduled jobs on", { selector: "input" })).toBeChecked();
    expect(sent.filter((s) => s.method === "PUT")).toHaveLength(0);
    const save = within(dialog).getByRole("button", { name: "Save" });
    const reason = within(dialog).getByLabelText(/Reason/);
    expect(save).toBeDisabled();
    fireEvent.change(reason, { target: { value: "  abc   " } });
    expect(save).toBeDisabled();
    fireEvent.change(reason, { target: { value: "x".repeat(501) } });
    expect(save).toBeDisabled();
    fireEvent.change(reason, { target: { value: "  Vendor maintenance tonight  " } });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    expect(await screen.findByTestId("scheduled-jobs-notice"))
      .toHaveTextContent("Discovery refresh is OFF. 3 queued jobs were cancelled and 1 open run was closed.");
    const puts = sent.filter((s) => s.method === "PUT");
    expect(puts).toHaveLength(1);
    expect(puts[0].url).toBe("/api/v2/config/scheduled-jobs/discovery_refresh");
    expect(puts[0].body).toEqual({ enabled: false, reason: "Vendor maintenance tonight" });
    expect(screen.queryByRole("dialog")).toBeNull();
    // The list is read again after the save and shows the server's state.
    await waitFor(() => expect(sent.filter((s) => s.method === "GET" && s.url === "/api/v2/config/scheduled-jobs")).toHaveLength(2));
    await waitFor(() => expect(screen.getByLabelText("Discovery refresh: scheduled jobs on")).not.toBeChecked());
    expect(screen.getByTestId("scheduled-jobs-summary")).toHaveTextContent("3 of 6 job families will run");
    expect(row("discovery_refresh").getByTestId("scheduled-family-reason")).toHaveTextContent("Reason: Vendor maintenance tonight");
  });

  it("turning a family ON names the next window, and Cancel sends nothing", async () => {
    const state = { view: view() };
    const sent = server(state, () => ({ status: 200, body: { ...family(2), cancelled_jobs: 0, closed_runs: 0 } }));
    show();
    fireEvent.click(await screen.findByLabelText("Configuration refresh: scheduled jobs on"));
    let dialog = screen.getByRole("dialog", { name: "Turn ON Configuration refresh?" });
    expect(dialog).toHaveTextContent("Configuration refresh jobs start again at the next window: 2026-11-11 00:00 GMT+3.");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(sent.filter((s) => s.method === "PUT")).toHaveLength(0);

    fireEvent.click(screen.getByLabelText("Configuration refresh: scheduled jobs on"));
    dialog = screen.getByRole("dialog", { name: "Turn ON Configuration refresh?" });
    fireEvent.change(within(dialog).getByLabelText(/Reason/), { target: { value: "Maintenance finished" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await screen.findByTestId("scheduled-jobs-notice"))
      .toHaveTextContent("Configuration refresh is ON. Configuration refresh jobs start again at the next window: 2026-11-11 00:00 GMT+3.");
    expect(sent.find((s) => s.method === "PUT")!.body).toEqual({ enabled: true, reason: "Maintenance finished" });
  });

  it("shows the server's message when the family is locked by the deployment, keeps the dialog and reads the list again", async () => {
    const message = "Scheduling is locked by the deployment switch UI2_SCHEDULING_ENABLED=false.";
    const sent = server({ view: view() }, () => ({ status: 409, body: { code: "SCHEDULING_LOCKED_BY_DEPLOYMENT", message } }));
    show();
    fireEvent.click(await screen.findByLabelText("HA readiness: scheduled jobs on"));
    const dialog = screen.getByRole("dialog", { name: "Turn OFF HA readiness?" });
    fireEvent.change(within(dialog).getByLabelText(/Reason/), { target: { value: "Pause before the change window" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    const refusal = await within(dialog).findByText("Not saved", { exact: false });
    expect(refusal.closest("[data-state='error']")).toHaveTextContent(message);
    expect(refusal.closest("[data-state='error']")).toHaveTextContent("SCHEDULING_LOCKED_BY_DEPLOYMENT");
    expect(screen.queryByTestId("scheduled-jobs-notice")).toBeNull();
    await waitFor(() => expect(sent.filter((s) => s.method === "GET" && s.url === "/api/v2/config/scheduled-jobs")).toHaveLength(2));
    // The reason is kept so the admin can retry or cancel.
    await waitFor(() => expect(within(dialog).getByLabelText(/Reason/)).toHaveValue("Pause before the change window"));
  });

  it("goes through the shared business-hours confirmation and repeats the request once with the acknowledgement", async () => {
    const confirmText = "Business hours (08:00-20:00 GMT+3). This changes which scheduled jobs run. Continue?";
    const off = family(3, { enabled: false, state: "OFF" });
    const sent = server({ view: view({ business_hours: { start: "08:00", end: "20:00", zone: "Europe/Istanbul", inside_now: true } }) },
      (call) => call.ack === "true" ? { status: 200, body: { ...off, cancelled_jobs: 0, closed_runs: 0 } }
        : { status: 409, body: { code: "BUSINESS_HOURS_CONFIRM_REQUIRED", message: confirmText } });
    const ask = vi.fn(() => Promise.resolve(true));
    setBusinessHoursConfirm(ask);
    show();
    fireEvent.click(await screen.findByLabelText("HA readiness: scheduled jobs on"));
    const dialog = screen.getByRole("dialog", { name: "Turn OFF HA readiness?" });
    expect(dialog).toHaveTextContent("It is business hours (08:00 to 20:00 GMT+3), so you will be asked to confirm once more after Save.");
    fireEvent.change(within(dialog).getByLabelText(/Reason/), { target: { value: "Pause for the audit" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await screen.findByTestId("scheduled-jobs-notice")).toHaveTextContent("HA readiness is OFF. No queued job needed cancelling.");
    expect(ask).toHaveBeenCalledWith(confirmText);
    expect(sent.filter((s) => s.method === "PUT").map((s) => s.ack)).toEqual([null, "true"]);
  });

  it("says a family that admits work all through an open window may start within a minute when switched ON", async () => {
    const live = family(5, { window_open_now: true, resumes_in_open_window: true, enabled: false, state: "OFF" });
    server({ view: view({}, [family(0), family(1), family(2), family(3), family(4), live]) });
    show();
    fireEvent.click(await screen.findByLabelText("System log collection: scheduled jobs on"));
    expect(screen.getByTestId("scheduled-jobs-effect")).toHaveTextContent("System log collection jobs may start within a minute: the window is open now.");
    expect(savedSentence({ ...family(5, { resumes_in_open_window: true }), cancelled_jobs: 0, closed_runs: 0 }))
      .toContain("may start within a minute");
    expect(savedSentence({ ...family(0), cancelled_jobs: 0, closed_runs: 0 })).toContain("jobs start again at the next window");
  });

  it("master switch off with an independent family: the banner does not claim that nothing runs", async () => {
    const lock = "Deployment switch UI2_SCHEDULING_ENABLED=false";
    const families = KEYS.map((_, i) => i === 5 ? family(i) : family(i, { state: "LOCKED", lock_reason: lock }));
    server({ view: view({ master_enabled: false }, families) });
    show();
    const banner = await screen.findByTestId("scheduled-jobs-master-off");
    expect(banner).toHaveTextContent("system log collection runs on its own timer");
    expect(banner).not.toHaveTextContent("No scheduled job family runs");
  });

  it("the masked view shows the actor token as given and withholds the reason", async () => {
    server({ view: view({ can_write: false, masked: true }, [
      family(0, { changed_by: "USR-0a1b2c3d4e", changed_at: "2026-11-10T18:05:00Z", reason: null }),
      ...KEYS.slice(1).map((_, i) => family(i + 1)),
    ]) });
    show();
    await screen.findByRole("list", { name: "Scheduled job families" });
    expect(row("discovery_refresh").getByText(/USR-0a1b2c3d4e/)).toBeInTheDocument();
    expect(row("discovery_refresh").getByTestId("scheduled-family-reason")).toHaveTextContent("Reason withheld (masked view)");
    expect(screen.getByTestId("scheduled-jobs-read-only")).toHaveTextContent(/Masked view: accounts are shown as tokens and reasons are withheld/);
    expect(screen.queryByRole("switch")).toBeNull();
  });

  it("a failed read is an error with Retry, and an empty list is said plainly; neither is an endless loader", async () => {
    const state = { view: view({}, []) };
    let fail = true;
    vi.stubGlobal("fetch", vi.fn(() => {
      const failing = fail;
      return Promise.resolve(new Response(JSON.stringify(failing ? { code: "INTERNAL", message: "The scheduler state is unavailable." } : state.view), { status: failing ? 500 : 200 }));
    }));
    show();
    const error = await screen.findByText("The scheduled jobs list is unavailable");
    expect(error.closest("[data-state='error']")).toHaveTextContent("The scheduler state is unavailable.");
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No scheduled job family is defined")).toBeInTheDocument();
    expect(screen.queryByText("Loading scheduled jobs…")).toBeNull();
  });

  it("a malformed body ({}) becomes the error state with Retry instead of crashing, and nothing of the body is logged", async () => {
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const state = { view: { sentinel: "synthetic-body-marker" } as unknown as ScheduledJobsView };
    server(state);
    show();
    const error = await screen.findByText("The scheduled jobs list is unavailable");
    expect(error.closest("[data-state='error']")).toHaveTextContent("MALFORMED_RESPONSE");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    expect(log.mock.calls.map((c) => c.map(String).join(" ")).join("\n")).not.toContain("synthetic-body-marker");
    // Retry reads again and shows the list once the server answers properly.
    state.view = view();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("list", { name: "Scheduled job families" })).toBeInTheDocument();
  });

  it("a malformed re-read keeps the last good list and says it was not refreshed", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const state = { view: view() };
    server(state);
    show();
    await screen.findByRole("list", { name: "Scheduled job families" });
    state.view = {} as ScheduledJobsView;
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await screen.findByTestId("scheduled-jobs-stale")).toHaveTextContent("The list was not refreshed");
    expect(screen.getByRole("list", { name: "Scheduled job families" })).toBeInTheDocument();
  });

  it("a malformed save answer ({}) is a 'Not saved' problem, not a crash, and the list is read again", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const state = { view: view() };
    const sent = server(state, () => ({ status: 200, body: {} }));
    show();
    fireEvent.click(await screen.findByLabelText("Discovery refresh: scheduled jobs on"));
    const dialog = screen.getByRole("dialog", { name: "Turn OFF Discovery refresh?" });
    fireEvent.change(within(dialog).getByLabelText(/Reason/), { target: { value: "Vendor maintenance tonight" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("Not saved")).toBeInTheDocument();
    expect(screen.queryByTestId("scheduled-jobs-notice")).toBeNull();
    await waitFor(() => expect(sent.filter((s) => s.method === "GET" && s.url === "/api/v2/config/scheduled-jobs")).toHaveLength(2));
  });

  it("words the time ahead and the saved sentence", () => {
    expect(untilText("2026-11-10T18:42:00Z", NOW)).toBe("in 12 min");
    expect(untilText("2026-11-13T18:30:00Z", NOW)).toBe("in 3 d");
    expect(untilText("2026-11-10T18:00:00Z", NOW)).toBeNull();
    expect(untilText(null, NOW)).toBeNull();
    expect(savedSentence({ ...family(0, { enabled: false, state: "OFF" }), cancelled_jobs: 1, closed_runs: 0 }))
      .toBe("Discovery refresh is OFF. 1 queued job was cancelled.");
    expect(savedSentence({ ...family(4, { will_run: false, note: "Backup policy schedule is off (Backups screen)" }), cancelled_jobs: 0, closed_runs: 0 }))
      .toBe("Scheduled backup is ON, but idle: Backup policy schedule is off (Backups screen). No job runs until that is resolved.");
  });
});
