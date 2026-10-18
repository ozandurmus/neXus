import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyChangesTab } from "../src/screens/PolicyChangesTab";
import { PolicyHygieneTab } from "../src/screens/PolicyHygieneTab";
import { PolicyContainerPrompt } from "../src/screens/PolicyContainerPrompt";
import { PolicyLookupTab } from "../src/screens/PolicyLookupTab";
import { GlobalSearch, matchingDeviceCount } from "../src/shell/GlobalSearch";
import { SystemStatusPanel } from "../src/screens/SystemStatusPanel";
import { ProjectPlanPanel } from "../src/screens/ProjectPlanPanel";
import { JobLogsPanel } from "../src/screens/JobLogsPanel";
import { DiagnosticPanel } from "../src/screens/DiagnosticPanel";
import { PACKET_WORKFLOW_SENTENCES, PacketAnalysisScreen } from "../src/screens/PacketAnalysisScreen";
import type { ContainerSummary } from "../src/screens/PolicyScreen";
import type { DeviceSummary, PolicyChangesPage, PolicyMetadata } from "../src/auth/adminApi";

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const themed = (node: React.ReactNode) => <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;

describe("counters say Loading… then a number or UNKNOWN, never a dash (UI review 2026-10-18, F-10)", () => {
  const page: PolicyChangesPage = {
    rows: [], summary: { total: 2, actors: 1, rules: 1, counts: { added: 1, modified: 1, removed: 0 } }, nextCursor: "",
    rangeStart: "2026-09-09T12:00:00Z", rangeEnd: "2026-10-09T12:00:00Z",
  };

  it("Policy Changes: Loading… while the read is in flight, then the numbers", async () => {
    let release!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { release = resolve; })));
    render(themed(<PolicyChangesTab policies={[]} devices={[]} openRule={vi.fn()} />));
    expect(screen.getAllByText("Loading…").length).toBeGreaterThanOrEqual(6);
    expect(screen.queryByText("—")).toBeNull();
    release(json(page));
    await waitFor(() => expect(screen.queryByText("Loading…")).toBeNull());
    expect(screen.getByText("Changes in period").parentElement).toHaveTextContent("2");
  });

  it("Policy Changes: UNKNOWN when the read failed", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({}, 500)));
    render(themed(<PolicyChangesTab policies={[]} devices={[]} openRule={vi.fn()} />));
    await screen.findByRole("alert");
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(6);
    expect(screen.queryByText("—")).toBeNull();
  });

  it("Policy Changes: the date pickers are in GMT+3, not UTC", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(page)));
    render(themed(<PolicyChangesTab policies={[]} devices={[]} openRule={vi.fn()} />));
    expect(screen.getByLabelText("From (GMT+3)")).toBeInTheDocument();
    expect(screen.getByLabelText("Until (GMT+3, exclusive)")).toBeInTheDocument();
    expect(screen.queryByLabelText(/UTC\)/)).toBeNull();
  });

  it("Policy Hygiene: class counters say Loading…, then the number, and UNKNOWN on a failed read", async () => {
    let release!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { release = resolve; })));
    const first = render(themed(<PolicyHygieneTab policy="p1" openRule={vi.fn()} openObject={vi.fn()} />));
    expect(screen.getByText("shadowed: Loading…")).toBeInTheDocument();
    release(json({ rows: [], counts: { shadowed: 0, conflict: 3, disabled: 0, unused: 0, expired: 0, broad: 0, unknown: 0 }, total: 0, page: 0, pageSize: 200, budgetReached: false, days: 90 }));
    expect(await screen.findByText("conflict: 3")).toBeInTheDocument();
    expect(screen.getByText("shadowed: 0")).toBeInTheDocument();
    first.unmount();
    vi.stubGlobal("fetch", vi.fn(async () => json({}, 500)));
    render(themed(<PolicyHygieneTab policy="p1" openRule={vi.fn()} openObject={vi.fn()} />));
    expect(await screen.findByText("shadowed: UNKNOWN")).toBeInTheDocument();
  });
});

describe("the shared Select a container card", () => {
  const row = (n: number): ContainerSummary => ({ key: `k${n}`, sourceId: "s", sourceName: "MGR-ALPHA-01", vendor: "PAN", containerId: `c${n}`,
    containerName: `DG-ALPHA-0${n}`, policyIds: ["p"], rules: 12 * n, gateways: ["FW-TANGO-04"], collectedAt: "2026-10-18T03:00:00Z" });

  it.each([["Objects", "Select a policy container to see its objects"], ["Installation", "Select a policy container to see where its packages are installed"],
    ["Hygiene", "Select a policy container to see its hygiene findings"]] as const)("%s names what it needs and counts the containers", (tab, title) => {
    const open = vi.fn();
    render(themed(<PolicyContainerPrompt tab={tab} now={new Date("2026-10-18T04:00:00Z")} rows={[row(1), row(2), row(3)]} open={open} />));
    expect(screen.getByText(title)).toBeInTheDocument();
    expect(screen.getByText("3 stored containers.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Open DG-ALPHA-02" }));
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ containerName: "DG-ALPHA-02" }));
  });

  it("says plainly that nothing is stored when there is no container", () => {
    render(themed(<PolicyContainerPrompt tab="Hygiene" now={new Date()} rows={[]} open={vi.fn()} />));
    expect(screen.getByText(/No stored policy container matches yet/)).toBeInTheDocument();
  });
});

describe("Policy lookup (UI review 2026-10-18, F-10)", () => {
  const policy: PolicyMetadata = { id: "p1", sourceId: "s1", sourceName: "MGR-ALPHA", vendor: "PAN", containerId: "c1", containerName: "DOM-ALPHA", name: "POL-ALPHA", collectedAt: "2026-10-09T12:00:00Z", artefactRef: "a1", targets: [] };

  it("has short labels and a real disabled button that says what it is waiting for", () => {
    render(themed(<PolicyLookupTab policies={[policy]} policy="p1" selectPolicy={vi.fn()} openRule={vi.fn()} />));
    for (const label of ["Lookup policy", "Source", "Destination", "Service", "Source zone", "Destination zone", "Install-on target", "Device context"]) {
      const field = screen.getAllByText(label).find((node) => node.tagName === "LABEL");
      expect(field, label).toBeDefined();
      expect(label.length).toBeLessThanOrEqual(18);
    }
    expect(screen.getAllByText("IPv4 or CIDR").length).toBe(2);
    const button = screen.getByRole("button", { name: "Look up policy intent · Needs a source, a destination" });
    expect(button).toBeDisabled();
    expect(button).toHaveClass("MuiButton-contained");
    fireEvent.change(screen.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.8" } });
    expect(screen.getByRole("button", { name: "Look up policy intent · Needs a destination" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.8" } });
    expect(screen.getByRole("button", { name: "Look up policy intent" })).toBeEnabled();
  });

  it("names the missing policy first", () => {
    render(themed(<PolicyLookupTab policies={[policy]} policy="" selectPolicy={vi.fn()} openRule={vi.fn()} />));
    expect(screen.getByRole("button", { name: /Look up policy intent · Needs a policy, a source, a destination$/ })).toBeDisabled();
  });
});

describe("Packet Analysis empty state", () => {
  it("explains the workflow in two sentences instead of a dead 'No case yet' row", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.startsWith("/api/v2/packet-analysis/cases")
      ? json({ cases: [], permissions: { run: false, raw_view: false, masked: true }, limits: { retention_days: 7, max_file_bytes: 1024, max_store_bytes: 4096, used_bytes: 0, max_telemetry_bytes: 1024 } })
      : json({})));
    render(themed(<PacketAnalysisScreen />));
    const card = await screen.findByLabelText("How Packet Analysis works");
    expect(PACKET_WORKFLOW_SENTENCES).toHaveLength(2);
    for (const sentence of PACKET_WORKFLOW_SENTENCES) expect(card).toHaveTextContent(sentence);
    expect(screen.queryByRole("table", { name: "Packet analysis cases" })).toBeNull();
  });
});

describe("global search", () => {
  const devices = ["FW-APOLLO-01-M1", "FW-APOLLO-01-M2", "FW-APOLLO-02", "FW-APOLLO-03"].map((hostname, i) => ({
    device_id: `opaque-${i}`, hostname, vendor_hint: "check_point", enrollment_state: "ENROLLED", model: "Model A", software_version: "R81.20",
    ha_role: null, cluster_member_ref: null, serial_number: null, management_ip: null, platform_family: null,
  } as DeviceSummary));

  function search(remote: Response) {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input) === "/devices" ? json({ devices }) : remote));
    render(<GlobalSearch />);
    const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "APOLLO" } });
  }

  it("counts the matching devices", () => {
    expect(matchingDeviceCount(devices, "apollo")).toBe(4);
    expect(matchingDeviceCount(devices, "")).toBe(0);
    expect(matchingDeviceCount(devices, "nothing")).toBe(0);
  });

  it("offers See all N devices and carries the term to the Devices screen", async () => {
    search(json({ error: "ACTION_REFUSED" }, 403));
    const link = await screen.findByText("See all 4 devices");
    expect(link).toHaveAttribute("href", "?screen=inventory&device_search=APOLLO");
  });

  it("names what a refused search cannot find instead of a bare 'restricted'", async () => {
    search(json({ error: "ACTION_REFUSED" }, 403));
    expect(await screen.findByText("Routes, addresses and policy are not searchable for the Viewer role")).toBeInTheDocument();
    expect(screen.queryByText("Search results are restricted")).toBeNull();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });
});

describe("Administration System and Delivery plan", () => {
  it("shows storage first and keeps the pod table behind a disclosure", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.includes("/system/pods")
      ? json({ available: true, read_at: "2026-10-18T03:00:00Z", pods: [{ name: "ui2-service-abc", state: "Running", ready: "1/1", restarts: 0, started_at: "2026-10-17T03:00:00Z",
        cpu_millicores: 4, cpu_limit_millicores: 1000, memory_bytes: 1048576, memory_limit_bytes: 2097152, image_digest: "digest-synthetic-1" }], modules: [] })
      : json({ read_at: "2026-10-18T03:00:00Z", backups: [{ vendor: "bluecoat", class: "system_log", artefacts: 3, devices: 1, stored_bytes: 1024, original_bytes: 2048, oldest: "2026-10-01T03:00:00Z", newest: "2026-10-17T03:00:00Z" }],
        top_devices: [], database_bytes: 10, configuration: { stored_bytes: 1, artefacts: 1, text_in_database_bytes: 1 }, artefact_volume: {} })));
    render(themed(<SystemStatusPanel />));
    const storage = await screen.findByText("What the evidence takes");
    const pods = screen.getByText(/^Service pods \(1\)$/);
    expect(storage.compareDocumentPosition(pods) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText("ui2-service-abc")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show service pods (1)" }));
    expect(await screen.findByText("ui2-service-abc")).toBeInTheDocument();
    // display names, not raw ids, in the per-vendor archive table
    expect(screen.getByText("Blue Coat")).toBeInTheDocument();
    expect(screen.getByText("System log")).toBeInTheDocument();
    expect(screen.queryByText("bluecoat")).toBeNull();
    expect(screen.getByText(/observed \d{4}-\d{2}-\d{2} \d{2}:\d{2} GMT\+3/)).toBeInTheDocument();
  });

  it("gives the Delivery plan a section index that names every section", async () => {
    const plan = { schema_version: "1.0", generated_at: "2026-10-18T00:00:00Z", current_build: null, current_track: null, progress_contract: null, overall_progress_percent: 0,
      current_track_progress_percent: 0, tracks: [], now_next: {}, roadmap_notes: [], backlog: [], backlog_counts: {}, completed_features: [], build_history: [],
      archived_build_count: 0, metadata_warnings: [] };
    vi.stubGlobal("fetch", vi.fn(async () => json(plan)));
    render(themed(<ProjectPlanPanel />));
    const index = await screen.findByRole("navigation", { name: "Delivery plan sections" });
    for (const title of ["Java UI2 product roadmap", "Now / Next / Upcoming", "Major tracks", "Java feature backlog", "Completed features", "Java product build history"]) {
      expect(within(index).getByRole("button", { name: title })).toBeInTheDocument();
      expect(document.getElementById(`plan-section-${title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}`)).not.toBeNull();
    }
    expect(index).toHaveStyle({ position: "sticky" });
  });
});

describe("Operations Jobs (UI review 2026-10-18, F-11)", () => {
  const items = [{ job_id: "job-1", target_device_id: "opaque-001", job_type: "pan_failover_readiness", state: "COMPLETED", duration_ms: 8038,
    submitted_at: "2026-10-18T03:00:00Z", finished_at: "2026-10-18T03:01:00Z", has_transcript: false }];
  function jobs() {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/v2/jobs/facets")) return json({ states: ["COMPLETED"], job_types: ["pan_failover_readiness"] });
      if (url.startsWith("/api/v2/jobs")) return json({ items, page: 1, page_size: 50, total: 1 });
      if (url === "/devices") return json({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04", vendor_hint: "palo_alto" }] });
      return json({}, 404);
    }));
  }

  const jobRowCell = () => waitFor(() => {
    const cell = screen.getAllByText("FW-TANGO-04").find((node) => node.tagName === "TD");
    if (!cell) throw new Error("job row not rendered yet");
    return cell;
  });

  it("shows the plain job type with the raw capability on hover, and the state as a word", async () => {
    jobs();
    render(<JobLogsPanel />);
    const type = (await screen.findAllByText("Palo Alto readiness check")).find((node) => node.tagName === "SPAN")!;
    expect(type).toHaveAttribute("data-raw", "pan_failover_readiness");
    expect(screen.getAllByText("Completed").length).toBeGreaterThan(0);
    expect(screen.queryByText("COMPLETED")).toBeNull();
    const option = screen.getByRole("option", { name: "Palo Alto readiness check · pan_failover_readiness" });
    expect(option).toHaveValue("pan_failover_readiness");
  });

  it("writes one outcome line in the row detail", async () => {
    jobs();
    render(<JobLogsPanel />);
    fireEvent.click((await jobRowCell()).closest("tr")!);
    const line = document.querySelector("[data-outcome-line]") as HTMLElement;
    expect(line.textContent).toMatch(/^Completed · 8\.0 s · (\d{2}:\d{2}|\d{4}-\d{2}-\d{2} \d{2}:\d{2} GMT\+3)/);
    expect(line).toHaveTextContent("pan_failover_readiness");
  });

  it("renders Live polling as a labelled switch, not a primary button", async () => {
    jobs();
    render(<JobLogsPanel />);
    await jobRowCell();
    const toggle = screen.getByRole("checkbox", { name: "Live polling" });
    expect(toggle).toBeChecked();
    expect(screen.queryByRole("button", { name: /Live polling/ })).toBeNull();
    fireEvent.click(toggle);
    expect(toggle).not.toBeChecked();
    // Refresh and Export CSV stay the buttons
    expect(screen.getByRole("button", { name: "Refresh" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeInTheDocument();
  });

  it("labels the time filters with the display zone", async () => {
    jobs();
    render(<JobLogsPanel />);
    await jobRowCell();
    expect(screen.getByLabelText("From (GMT+3)")).toBeInTheDocument();
    expect(screen.getByLabelText("To (GMT+3)")).toBeInTheDocument();
  });
});

describe("Diagnostics (UI review 2026-10-18, F-11)", () => {
  it("shows vendor display names and the one timestamp format, not raw ids or a US locale date", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") return json({ csrf_token: "synthetic-csrf" });
      if (url === "/api/v2/diagnostics/targets") return json({ canExecute: false, targets: [
        { deviceId: "d1", target: "FW-FOXTROT-08", vendor: "cisco_asa", virtualSystems: [], commands: [] },
        { deviceId: "d2", target: "FW-TANGO-04", vendor: "fortinet", virtualSystems: [], commands: [] }] });
      if (url.startsWith("/api/v2/diagnostics/history")) return json({ runs: [{ jobId: "j1", targetDeviceId: "d1", target: "FW-FOXTROT-08", command: "show version",
        description: "Version", state: "COMPLETED", actor: "ACTOR-01", submittedAt: "2026-10-17T00:16:36Z", durationMs: 1500 }] });
      return json({});
    }));
    render(themed(<DiagnosticPanel />));
    await screen.findByRole("button", { name: "FW-FOXTROT-08" });
    expect(screen.getByRole("button", { name: "Cisco ASA" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Fortinet" })).toBeInTheDocument();
    expect(screen.queryByText("cisco_asa")).toBeNull();
    const history = await screen.findByRole("table", { name: "Execution history" });
    expect(within(history).getByText("2026-10-17 03:16 GMT+3")).toBeInTheDocument();
    expect(within(history).queryByText(/\d{1,2}\/\d{1,2}\/\d{4}/)).toBeNull();
    expect(within(history).getByText("Completed")).toBeInTheDocument();
  });
});

describe("the search term a 'See all devices' link carries", () => {
  afterEach(() => { window.history.replaceState(null, "", "/"); vi.resetModules(); });

  it("fills the box on Devices only", async () => {
    const { renderHook } = await import("@testing-library/react");
    window.history.replaceState(null, "", "/?screen=inventory&device_search=APOLLO");
    vi.resetModules();
    const devicesPage = await import("../src/shell/listSearch");
    expect(renderHook(() => devicesPage.useListSearchState()).result.current.term).toBe("APOLLO");

    window.history.replaceState(null, "", "/?screen=compliance&device_search=APOLLO");
    vi.resetModules();
    const elsewhere = await import("../src/shell/listSearch");
    expect(renderHook(() => elsewhere.useListSearchState()).result.current.term).toBe("");
  });
});
