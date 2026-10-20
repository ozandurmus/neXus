import { act, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { COMPUTING_POLL_MS, SystemHealthPanel, logsHref, reasonWords, sparklineWords } from "../src/screens/SystemHealthPanel";
import type { SystemHealth, SystemHealthDevice } from "../src/auth/adminApi";

// Synthetic data only: pseudonym device names, opaque ids, invented counters.
const NOW = new Date("2026-10-20T06:30:00Z");

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

function device(over: Partial<SystemHealthDevice> & { readonly device_id: string }): SystemHealthDevice {
  return {
    hostname: null, vendor_hint: "check_point", role: "gateway", cluster_member_ref: null, monitored: true, state: "NORMAL", state_reason: null,
    cpu: { level: "NORMAL", reason: null, threshold_percent: 85, newest_p95_percent: 41, days_over_threshold: 0, longest_run_over_threshold: 0,
      days: [{ day: "2026-10-18", status: "PARSED", p95_percent: 40, max_percent: 60, samples: 144, cpus: 8, unparsed_rows: 0 },
             { day: "2026-10-19", status: "PARSED", p95_percent: 41, max_percent: 62, samples: 144, cpus: 8, unparsed_rows: 0 }],
      day_basis: "device mtime of the sar file, calendar day in Europe/Istanbul" },
    memory: { level: "EVIDENCED", reason: null, newest_used_p95_percent: 55.5 },
    restarts: { level: "NORMAL", reason: null, count: 0, max_in_one_hour: 0, termination_lines: 0, busiest_hour: null, rule: "cpwd-restart-v1" },
    crash: { level: "NORMAL", reason: null, new_alerts: 0, newest_alert_at: null },
    collection: { state: "OK", reason: null, last_job_at: "2026-10-20T03:05:00Z", last_job_id: "job-1", outcomes: { cp_messages: "COLLECTED" } },
    logs_day: "2026-10-19", ...over,
  };
}

const HOT = device({ device_id: "d-hot", hostname: "FW-TANGO-04", state: "CRITICAL", state_reason: "CPU_P95_OVER_THRESHOLD_3_DAYS_IN_A_ROW",
  cpu: { level: "CRITICAL", reason: "CPU_P95_OVER_THRESHOLD_3_DAYS_IN_A_ROW", threshold_percent: 85, newest_p95_percent: 92.4, days_over_threshold: 3, longest_run_over_threshold: 3,
    days: [{ day: "2026-10-17", status: "PARSED", p95_percent: 90, max_percent: 99, samples: 144, cpus: 16, unparsed_rows: 0 },
           { day: "2026-10-18", status: "PARSED", p95_percent: 91, max_percent: 99, samples: 144, cpus: 16, unparsed_rows: 0 },
           { day: "2026-10-19", status: "PARSED", p95_percent: 92.4, max_percent: 100, samples: 144, cpus: 16, unparsed_rows: 0 }], day_basis: "x" },
  restarts: { level: "ATTENTION", reason: "RESTARTS_4_IN_ONE_HOUR", count: 6, max_in_one_hour: 4, termination_lines: 2, busiest_hour: "Oct 19 05:00 device time", rule: "cpwd-restart-v1" },
  crash: { level: "CRITICAL", reason: "NEW_CRASH_OR_DUMP_FILE_ALERT_1", new_alerts: 1, newest_alert_at: "2026-10-20T03:10:00Z" } });
const FRESH = device({ device_id: "d-new", hostname: "FW-JULIET-06", state: "UNKNOWN", state_reason: "NO_SAR_DAY_STORED",
  cpu: { level: "UNKNOWN", reason: "NO_SAR_DAY_STORED", threshold_percent: 85, newest_p95_percent: null, days_over_threshold: 0, longest_run_over_threshold: 0, days: [], day_basis: "x" },
  memory: { level: "UNKNOWN", reason: "NO_SAR_DAY_STORED", newest_used_p95_percent: null },
  restarts: { level: "UNKNOWN", reason: "CPWD_NOT_COLLECTED", count: null, max_in_one_hour: null, termination_lines: null, busiest_hour: null, rule: "cpwd-restart-v1" },
  crash: { level: "UNKNOWN", reason: "CRASH_LISTING_NOT_RUN", new_alerts: 0, newest_alert_at: null },
  collection: { state: "NEVER", reason: "NO_COLLECTION_RUN_RECORDED", last_job_at: null, last_job_id: null, outcomes: {} }, logs_day: null });
const PAN = device({ device_id: "d-pan", hostname: "FW-BRAVO-02", vendor_hint: "palo_alto", state: "UNKNOWN", state_reason: "NO_APPLICABLE_FACT",
  cpu: { level: "NOT_APPLICABLE", reason: null, threshold_percent: 85, newest_p95_percent: null, days_over_threshold: 0, longest_run_over_threshold: 0, days: [], day_basis: "x" },
  memory: { level: "NOT_APPLICABLE", reason: null, newest_used_p95_percent: null },
  restarts: { level: "NOT_APPLICABLE", reason: null, count: null, max_in_one_hour: null, termination_lines: null, busiest_hour: null, rule: "cpwd-restart-v1" },
  crash: { level: "NOT_APPLICABLE", reason: null, new_alerts: null, newest_alert_at: null },
  collection: { state: "FAILED", reason: "pan_config:UNKNOWN", last_job_at: "2026-10-20T03:00:00Z", last_job_id: "job-p", outcomes: { pan_system: "COLLECTED", pan_config: "UNKNOWN" } } });
const CALM = device({ device_id: "d-calm", hostname: "FW-ROMEO-01-M1", cluster_member_ref: "CLS-ROMEO-01" });

function body(over: Partial<SystemHealth> = {}): SystemHealth {
  return {
    computation: { state: "READY", computed_at: "2026-10-20T06:20:00Z", duration_ms: 1234, snapshot_ttl_seconds: 600, store_available: true },
    window_days: 7, cpu_p95_threshold_percent: 85, time_basis: "x",
    rules: { critical: ["a new crash or dump file alert", "CPU p95 above 85 % on 3 days in a row"], attention: ["CPU p95 above 85 % on 1 or 2 days", "3 or more restarts in one hour"],
      normal: "every applicable fact evidenced", unknown: "an unevidenced fact outranks Normal", state_order: ["CRITICAL", "ATTENTION", "UNKNOWN", "NORMAL"], p95: "nearest rank",
      restart_rule: { id: "cpwd-restart-v1", status: "PROVISIONAL", text: "a line with the word restart" } },
    kpis: { monitored: { devices: 4, by_vendor: { check_point: 3, palo_alto: 1 }, not_collected_listed: 0 },
      crash_alerts: { devices: 1, alerts: 1, truncated: false },
      cpu_over_threshold: { devices: 1, evidenced: 2, unknown: 1, not_applicable: 1, basis: "x" },
      collection_not_ok: { devices: 2, by_state: { FAILED: 1, NEVER: 1 } } },
    devices: [HOT, CALM, FRESH, PAN], ...over,
  };
}

function stub(answer: () => { status: number; body: unknown }) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    calls.push(String(input));
    const a = answer();
    return Promise.resolve(new Response(JSON.stringify(a.body), { status: a.status }));
  }));
  return calls;
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe("SystemHealthPanel", () => {
  it("shows the four KPIs, the rule text and one row per device, worst first as the server sorted them", async () => {
    stub(() => ({ status: 200, body: body() }));
    render(withTheme(<SystemHealthPanel now={NOW} />));
    const table = await screen.findByRole("table", { name: "System health devices" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual(["FW-TANGO-04", "FW-ROMEO-01-M1", "FW-JULIET-06", "FW-BRAVO-02"]);

    const kpis = screen.getByLabelText("System health summary");
    expect(within(kpis).getByText("Monitored devices")).toBeInTheDocument();
    expect(within(kpis).getByText("Check Point 3 · Palo Alto 1")).toBeInTheDocument();
    expect(within(kpis).getByText("CPU p95 above 85 %")).toBeInTheDocument();
    expect(within(kpis).getByText("of 2 evidenced · 1 UNKNOWN · 1 no source")).toBeInTheDocument();
    expect(within(kpis).getByText("1 collection failed · 1 not collected yet")).toBeInTheDocument();
    expect(within(kpis).getByText("Review")).toBeInTheDocument();
    expect(screen.getByTestId("health-rules").textContent).toContain("cpwd-restart-v1 (PROVISIONAL)");
    expect(screen.getByTestId("health-rules").textContent).toContain("CRITICAL › ATTENTION › UNKNOWN › NORMAL");

    // The hot row: state word with its reason on hover, the sparkline with words, restarts as a warn pill, crash as a bad pill.
    const hot = rows[0];
    expect(within(hot).getByText("Critical")).toBeInTheDocument();
    expect(within(hot).getByRole("img", { name: /CPU p95 over 7 days, 3 stored: 2026-10-17 90 %, 2026-10-18 91 %, 2026-10-19 92.4 %; threshold 85 %; 3 of 3 days over/ })).toBeInTheDocument();
    expect(within(hot).getByText("92 %")).toBeInTheDocument();
    expect(within(hot).getByText("4 in 1 h")).toBeInTheDocument();
    expect(within(hot).getByText("New")).toBeInTheDocument();
    expect(within(hot).getByRole("link", { name: "FW-TANGO-04" })).toHaveAttribute("href", "?screen=inventory&device_id=d-hot&tab=logs&log_day=2026-10-19");
  });

  it("writes UNKNOWN for a fact without evidence and No source for a vendor without one; never a 0 or a dash", async () => {
    stub(() => ({ status: 200, body: body() }));
    render(withTheme(<SystemHealthPanel now={NOW} />));
    const table = await screen.findByRole("table", { name: "System health devices" });
    const rows = within(table).getAllByRole("row").slice(1);
    const fresh = rows[2];
    const cells = within(fresh).getAllByRole("cell").map((c) => c.textContent);
    expect(cells[2]).toBe("UNKNOWN");
    expect(cells[3]).toContain("UNKNOWN");
    expect(cells[4]).toBe("UNKNOWN");
    expect(cells[5]).toBe("UNKNOWN");
    expect(cells[6]).toBe("UNKNOWN");
    expect(cells[7]).toBe("Not collected yet");
    expect(within(fresh).getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(5);
    const pan = rows[3];
    const panCells = within(pan).getAllByRole("cell").map((c) => c.textContent);
    expect(panCells[1]).toBe("Palo Alto");
    expect(panCells[3]).toBe("No source");
    expect(panCells[4]).toBe("No source");
    expect(panCells[6]).toBe("No source");
    expect(panCells[7]).toContain("Collection failed");
    expect(screen.queryByText("—")).toBeNull();
    const calm = rows[1];
    expect(within(calm).getByText("Normal")).toBeInTheDocument();
    expect(within(calm).getByText("0")).toBeInTheDocument(); // an evidenced zero restarts is a zero
    expect(within(calm).getByText("None")).toBeInTheDocument();
  });

  it("while the first snapshot is computed it says so as a status notice, shows no figure and asks again", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let computing = true;
    const calls = stub(() => ({ status: 200, body: computing
      ? body({ computation: { state: "COMPUTING", computed_at: null, duration_ms: null, snapshot_ttl_seconds: 600, store_available: true },
               kpis: { monitored: null, crash_alerts: null, cpu_over_threshold: null, collection_not_ok: null }, devices: [] })
      : body() }));
    render(withTheme(<SystemHealthPanel now={NOW} />));
    const title = await screen.findByText("First computation running");
    expect(title.closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    const kpis = screen.getByLabelText("System health summary");
    expect(within(kpis).getAllByText("Not evaluated")).toHaveLength(4);
    expect(within(kpis).queryByText("0")).toBeNull();
    computing = false;
    await act(async () => { await vi.advanceTimersByTimeAsync(COMPUTING_POLL_MS + 50); });
    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();
    expect(screen.queryByText("First computation running")).toBeNull();
  });

  it("a refused read is a restriction, a failed read an error, both announced as status", async () => {
    stub(() => ({ status: 403, body: { code: "ACTION_REFUSED" } }));
    const { unmount } = render(withTheme(<SystemHealthPanel now={NOW} />));
    expect(await screen.findByText("System health is not available for this role")).toBeInTheDocument();
    expect(screen.getByRole("status")).toBeInTheDocument();
    unmount();
    vi.unstubAllGlobals();
    stub(() => ({ status: 500, body: { code: "SYSTEM_HEALTH_READ_FAILED", message: "The system health read failed." } }));
    render(withTheme(<SystemHealthPanel now={NOW} />));
    expect(await screen.findByText("System health could not be read")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("helpers: the deep link, the reason words and the sparkline words", () => {
    expect(logsHref(FRESH)).toBe("?screen=inventory&device_id=d-new&tab=logs");
    expect(reasonWords("NO_SAR_DAY_STORED")).toBe("no sar text of the window is stored");
    expect(reasonWords("RESTARTS_3_IN_ONE_HOUR")).toContain("three or more");
    expect(reasonWords(null)).toBeNull();
    expect(reasonWords("SOMETHING_ELSE")).toBeNull();
    expect(sparklineWords(FRESH, 7)).toBe("CPU p95 over 7 days, 0 stored: ; threshold 85 %; 0 of 0 days over");
  });
});
