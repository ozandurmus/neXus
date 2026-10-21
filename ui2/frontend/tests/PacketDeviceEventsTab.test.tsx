import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PacketDeviceEventsTab, resultTone, resultWord } from "../src/screens/packetanalysis/PacketDeviceEventsTab";
import type { PacketDeviceEventsView } from "../src/auth/adminApi";

/** Synthetic, masked-shaped data only: pseudonyms, opaque ids, no estate value. */
const CASE = "11111111-2222-4333-8444-555555555555";
const view: PacketDeviceEventsView = {
  case_id: CASE, masked: true, window_seconds: 30,
  findings: [
    { id: "quality:p3", type: "capture_quality", severity: "quality", hop: null, hop_label: null, start: null },
    { id: "forward:p1:p2:impactful_loss", type: "impactful_loss", severity: "high", hop: "forward:p1:p2", hop_label: "PT-ALPHA → PT-BRAVO", start: "2023-11-14T22:13:20.500Z" },
  ],
  finding: {
    id: "forward:p1:p2:impactful_loss", type: "impactful_loss", severity: "high", confidence: "supported", hop: "forward:p1:p2",
    hop_label: "PT-ALPHA → PT-BRAVO", direction: "forward", start: "2023-11-14T22:13:20.500Z", end: "2023-11-14T22:13:21Z",
    figures: { event_count: 3, max_recovery_ms: 420, p95_ms: 180.5, max_ms: 900, loss_percent: 1.2 },
  },
  hop: { id: "forward:p1:p2", label: "PT-ALPHA → PT-BRAVO", point_a: "p1", point_b: "p2", direction: "forward", location: "device", reason: null },
  window: { from: "2023-11-14T22:12:50.500Z", to: "2023-11-14T22:13:50.500Z", start: "2023-11-14T22:13:20.500Z", half_seconds: 30 },
  devices: [
    {
      label: "FW-LIMA-01", kind: "Firewall", on_hop: true,
      points: [{ id: "p1", label: "PT-ALPHA", side: "ingress", on_hop: true }, { id: "p2", label: "PT-BRAVO", side: "egress", on_hop: true }],
      link: { state: "LINKED", reason: "inventory MATCH", device_id: "dev-cp-1", device_name: "FW-TANGO-04", vendor: "check_point", correlation_status: "MATCH" },
      result: "EVENTS", result_reason: null, event_count: 2, coverage: "COVERED",
      events: [
        { kind: "CRASH_ALERT", source: "cp_crash_listing", time: "2023-11-14T22:13:25Z", time_basis: "DEVICE_MTIME", device_time: null, text: "crash/dump files listed: fwk × 2" },
        { kind: "CPWD_LINE", source: "cp_cpwd_elg", time: "2023-11-14T22:13:30Z", time_basis: "DEVICE_LOCAL", device_time: "15 Nov 01:13:30", text: "cpwd: Process cpd restarted by watchdog" },
      ],
      sources: [
        { source_key: "cp_cpwd_elg", last_outcome: "COLLECTED", last_run_at: null, chunks_overlapping: 0, lines_in_overlapping_chunks: 0, chunks_without_line_time: 0 },
        { source_key: "cp_messages", last_outcome: "COLLECTED", last_run_at: null, chunks_overlapping: 1, lines_in_overlapping_chunks: 120, chunks_without_line_time: 0 },
      ],
    },
    {
      label: "SW-KILO-02", kind: "Router/Switch", on_hop: false, points: [{ id: "p3", label: "PT-CHARLIE", side: "both", on_hop: false }],
      link: { state: "NOT_LINKED", reason: "log not collected: Router/Switch is not a managed firewall; device not linked: NO_MATCH", device_id: null, device_name: null, vendor: null, correlation_status: "NO_MATCH" },
      result: "NOT_COLLECTED", result_reason: "log not collected: Router/Switch is not a managed firewall; device not linked: NO_MATCH", event_count: null, coverage: "NONE", events: [], sources: [],
    },
    {
      label: "FW-MIKE-03", kind: "Firewall", on_hop: false, points: [{ id: "p4", label: "PT-DELTA", side: "both", on_hop: false }],
      link: { state: "LINKED", reason: "inventory MATCH", device_id: "dev-cp-2", device_name: "FW-JULIET-06", vendor: "check_point", correlation_status: "MATCH" },
      result: "UNKNOWN", result_reason: "SYSTEM_LOG_STORE_UNAVAILABLE: no artefact store mounted", event_count: null, coverage: "COVERED", events: [], sources: [],
    },
    {
      label: "FW-NOVEMBER-04", kind: "Firewall", on_hop: false, points: [{ id: "p5", label: "PT-ECHO", side: "both", on_hop: false }],
      link: { state: "LINKED", reason: "inventory MATCH", device_id: "dev-cp-3", device_name: "FW-BRAVO-02", vendor: "check_point", correlation_status: "MATCH" },
      result: "CLEAN", result_reason: null, event_count: 0, coverage: "COVERED", events: [], sources: [],
    },
  ],
  notes: ["Finding times are the capture clock (UTC).", "Events are stored lines and rows only."],
};

const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
function stub(answer: (url: string) => Response) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    return answer(url);
  }));
  return calls;
}
function mount() {
  return render(<ThemeProvider theme={m3Theme}><PacketDeviceEventsTab caseId={CASE} /></ThemeProvider>);
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("PacketDeviceEventsTab", () => {
  it("shows the finding sheet, the window and one row per device with its events, line counts and result word", async () => {
    const calls = stub(() => response(view));
    mount();
    expect(screen.getByRole("status")).toHaveTextContent("Loading device events…");
    const sheet = await screen.findByLabelText("Finding sheet");
    expect(within(sheet).getByText("impactful_loss")).toBeInTheDocument();
    expect(within(sheet).getByText("PT-ALPHA → PT-BRAVO")).toBeInTheDocument();
    expect(within(sheet).getByText("180.5 ms")).toBeInTheDocument();
    expect(within(sheet).getByText("1.2 %")).toBeInTheDocument();
    expect(within(sheet).getByText(/start ± 30 s/)).toBeInTheDocument();
    expect(within(sheet).getByText(/masked view/)).toBeInTheDocument();
    expect(calls).toEqual([`/api/v2/packet-analysis/cases/${CASE}/device-events`]);

    const table = screen.getByRole("table", { name: "Device events per device" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(4);
    const linked = rows[0]!;
    expect(within(linked).getByRole("link", { name: "FW-TANGO-04" })).toHaveAttribute("href", "?screen=inventory&device_id=dev-cp-1&tab=logs");
    expect(within(linked).getByText("On the finding's hop")).toBeInTheDocument();
    expect(within(linked).getByText("cpwd: Process cpd restarted by watchdog")).toBeInTheDocument();
    expect(within(linked).getByText("15 Nov 01:13:30")).toBeInTheDocument();
    expect(within(linked).getByText("device clock")).toBeInTheDocument();
    expect(within(linked).getByText("messages · 1 chunk · 120 lines")).toBeInTheDocument();
    expect(linked.querySelector('[data-fact="device-events:EVENTS"]')).toHaveTextContent("Events · 2");

    const unlinked = rows[1]!;
    expect(within(unlinked).queryByRole("link")).toBeNull();
    expect(within(unlinked).getByText("SW-KILO-02")).toBeInTheDocument();
    expect(within(unlinked).getByText(/device not linked: NO_MATCH/)).toBeInTheDocument();
    expect(unlinked.querySelector('[data-fact="device-events:NOT_COLLECTED"]')).toHaveTextContent("Not collected");
    expect(rows[2]!.querySelector('[data-fact="device-events:UNKNOWN"]')).toHaveTextContent("UNKNOWN");
    expect(within(rows[2]!).getByText(/SYSTEM_LOG_STORE_UNAVAILABLE/)).toBeInTheDocument();
    expect(rows[3]!.querySelector('[data-fact="device-events:CLEAN"]')).toHaveTextContent("Clean");
    expect(within(rows[3]!).getByText("None stored")).toBeInTheDocument();

    expect(screen.getByRole("status", { name: "Device events notes" })).toHaveTextContent("Events are stored lines and rows only.");
  });

  it("asks for another finding and window and refuses a window outside 5..600 seconds", async () => {
    const calls = stub(() => response(view));
    mount();
    await screen.findByLabelText("Finding sheet");
    const windowField = screen.getByLabelText("Window ± seconds");
    fireEvent.change(windowField, { target: { value: "601" } });
    expect(screen.getByRole("button", { name: "Show device events" })).toBeDisabled();
    expect(screen.getByText("5 to 600 seconds")).toBeInTheDocument();
    fireEvent.change(windowField, { target: { value: "120" } });
    fireEvent.click(screen.getByRole("button", { name: "Show device events" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toBe(`/api/v2/packet-analysis/cases/${CASE}/device-events?finding=forward%3Ap1%3Ap2%3Aimpactful_loss&window_seconds=120`);
  });

  it("says a report without a timed finding as an empty state, never as Clean", async () => {
    stub(() => response({ ...view, finding: null, window: null, devices: [], notes: ["The stored report holds no finding."] }));
    mount();
    const empty = await screen.findByText("No finding with a start time", { selector: "p" });
    expect(empty.closest('[role="status"]')).toHaveTextContent("The stored report holds no finding.");
    expect(screen.queryByText("Clean")).toBeNull();
  });

  it("shows a refusal as a status notice with its code, a 409 as not evaluated and a 403 as restricted", async () => {
    stub(() => response({ code: "FINDING_NOT_FOUND", error: "FINDING_NOT_FOUND", message: "No such finding in the stored report" }, 404));
    const first = mount();
    const failed = await screen.findByText("Could not load the device events");
    expect(failed.closest('[role="status"]')).toHaveTextContent("FINDING_NOT_FOUND");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    first.unmount();

    stub(() => response({ code: "ANALYSIS_RUNNING", message: "Case is currently being analysed" }, 409));
    const second = mount();
    const pending = await screen.findByText("Device events need a completed analysis");
    expect(pending.closest('[data-state="not_evaluated"]')).toHaveTextContent("ANALYSIS_RUNNING");
    second.unmount();

    stub(() => response({ error: "ACTION_REFUSED" }, 403));
    mount();
    expect((await screen.findByText("Device events")).closest('[data-state="restricted"]')).not.toBeNull();
  });

  it("maps each result word to its tone", () => {
    expect(resultTone("CLEAN")).toBe("ok");
    expect(resultTone("EVENTS")).toBe("warn");
    expect(resultTone("UNKNOWN")).toBe("unknown");
    expect(resultTone("NOT_COLLECTED")).toBe("neutral");
    expect(resultWord("NOT_PERMITTED")).toBe("Not permitted");
  });
});
