import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getScheduledJobs,
  getSystemHealth,
  listAuditEvents,
  listDevices,
  listLocalIdentities,
  listScriptRuns,
  listScripts,
  listSessions,
} from "../src/auth/adminApi";
import { EstateSystemScreen } from "../src/estate/EstateSystemScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getScheduledJobs: vi.fn(),
    getSystemHealth: vi.fn(),
    listAuditEvents: vi.fn(),
    listDevices: vi.fn(),
    listLocalIdentities: vi.fn(),
    listSessions: vi.fn(),
    listScripts: vi.fn(),
    listScriptRuns: vi.fn(),
  };
});

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const AT = "2026-11-22T08:00:00.000Z";
const SCRIPT_A = "FW-TANGO-04-sweep";
const SCRIPT_B = "FW-TANGO-04-report";

const script = (name: string, id: string) => ({
  script_id: id, name, enabled: true, schedule: "0 3 * * *", kind: "python", current_version: 1,
  next_five_fires: [], timeout_s: 60, egress: [], credential_refs: [], notify: "NEVER", run_as: "svc",
  warnings: [], created_at: AT, updated_at: AT, created_by_actor_fingerprint: "fp", sha256: null, size_bytes: 1,
  secret_findings: {}, egress_device_targets: [],
});

const run = (id: string) => ({
  job_id: `j-${id}`, run_id: id, script_id: "sid-a", version: 1, state: "FINISHED", outcome: "SUCCESS",
  trigger_kind: "SCHEDULED", scheduled_slot: null, run_as: "svc", requested_by: "system", requested_at: AT,
  started_at: AT, finished_at: AT, duration_ms: 10, exit_code: 0, stdout_bytes: 0, stderr_bytes: 0,
  stdout_truncated: false, stderr_truncated: false, has_output: false, output_sha256: null, output_size_bytes: null,
  output_tail: null, egress: [], egress_device_targets: [], credential_refs_used: [], failure_reason: null,
});

function mockOtherReads() {
  vi.mocked(getScheduledJobs).mockResolvedValue({
    master_enabled: true, master_env: "SYNTHETIC_MASTER", can_write: false, masked: true, business_hours: null, families: [],
  } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [] } as never);
  vi.mocked(getSystemHealth).mockRejectedValue(new Error("synthetic"));
  vi.mocked(listAuditEvents).mockResolvedValue({ events: [] } as never);
  vi.mocked(listLocalIdentities).mockResolvedValue({ identities: [] } as never);
  vi.mocked(listSessions).mockResolvedValue({ sessions: [], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600 } as never);
}

/** The What runs card: the heading's enclosing article-like card (the card holding the h2 "What runs"). */
function runsCard(): HTMLElement {
  const heading = screen.getByRole("heading", { name: "What runs" });
  let el: HTMLElement | null = heading;
  while (el && !el.querySelector("dl")) el = el.parentElement;
  if (!el) throw new Error("What runs card not found");
  return el;
}

describe("Estate System: What runs script block", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockOtherReads();
    vi.mocked(listScripts).mockResolvedValue({ scripts: [script(SCRIPT_A, "sid-a"), script(SCRIPT_B, "sid-b")], masked: true } as never);
    vi.mocked(listScriptRuns).mockImplementation(async (id: string) =>
      (id === "sid-a"
        ? { script_id: id, runs: [run("r-1")], masked: true }
        : { script_id: id, runs: [], masked: true }) as never);
  });

  it("shows one row per script with its last run and the standard=1 link, and no run-now, download or edit control", async () => {
    render(<EstateSystemScreen />);

    expect(await screen.findByText(SCRIPT_A)).toBeTruthy();
    expect(screen.getByText(SCRIPT_B)).toBeTruthy();
    expect(await screen.findByText(/1 run in the list as read/)).toBeTruthy();
    expect(await screen.findByText(/No runs/)).toBeTruthy();

    const card = runsCard();
    const link = card.querySelector('a[href="?screen=operations&tab=script-execution&standard=1"]');
    expect(link).toBeTruthy();
    expect(link?.textContent).toContain("Open script execution (standard)");

    const controls = Array.from(card.querySelectorAll("button, input, a"));
    for (const c of controls) {
      expect(c.textContent ?? "").not.toMatch(/run now|run script|download|output|edit|enable|disable/i);
    }
  });

  it("shows UNKNOWN, not OK, for a script whose runs read fails, and states the script list read time", async () => {
    vi.mocked(listScriptRuns).mockRejectedValue(new Error("synthetic"));

    render(<EstateSystemScreen />);

    await waitFor(() => expect(screen.getAllByText(/UNKNOWN: runs not read/).length).toBe(2));
    const card = runsCard();
    expect(card.textContent ?? "").not.toMatch(/\bOK\b/);
    expect(card.textContent ?? "").toContain("Script list read at");
  });

  it("shows UNKNOWN for the script block when the script list read fails", async () => {
    vi.mocked(listScripts).mockRejectedValue(new Error("synthetic"));

    render(<EstateSystemScreen />);

    expect(await screen.findByText("UNKNOWN: script list not read")).toBeTruthy();
    expect(runsCard().textContent ?? "").not.toMatch(/\bOK\b/);
  });
});
