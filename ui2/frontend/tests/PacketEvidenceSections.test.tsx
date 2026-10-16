import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import * as api from "../src/auth/adminApi";
import type { PacketAnalysisReport, PacketRef, PacketTopology } from "../src/auth/adminApi";
import {
  CaptureNotesSection,
  CyclesSection,
  ExportActions,
  IncidentSection,
  MicroburstsSection,
  ProtocolDiagnosticsSection,
  ReferenceCompareSection,
  SessionsSection,
  UdpMediaSection,
} from "../src/screens/packetanalysis/PacketEvidenceSections";
import { PacketCaseWorkspace } from "../src/screens/packetanalysis/PacketCaseWorkspace";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getPacketCaseState: vi.fn(), getPacketCaseTimeseries: vi.fn(), getPacketCaseCorrelation: vi.fn(),
  getPacketCaseFindings: vi.fn(), getPacketCaseEvents: vi.fn(), getPacketCaseFindingsExport: vi.fn(),
  getPacketCaseSessionEvidence: vi.fn(), getPacketCaseReferenceTemplate: vi.fn(), comparePacketCase: vi.fn(),
}));

const CASE = "11111111-2222-4333-8444-555555555555";
const REF: PacketRef = {
  point: "p1", file: "synthetic-a.pcap", capture_id: "c1", frame: 7, display_filter: "frame.number == 7",
  flow_filter: "ip.addr == 192.0.2.10", observed_time: 1700000000.5, corrected_time: 1700000000.5,
};
const FLOW = "ab".repeat(16);

const topology = {
  points: [
    { id: "p1", label: "Edge ingress" }, { id: "p2", label: "Edge egress" },
  ],
  forward: ["p1", "p2"], reverse: ["p2", "p1"], client_cidrs: ["192.0.2.0/24"], nat_mappings: [], min_eligible_ratio: 0.5,
} as unknown as PacketTopology;

/** Mirrors the shape of the Java engine's final report (sections as the reference UI reads them). */
const report = {
  verdict: "Findings present", scope: "2 points", flow_count: 1, findings: [], segments: [
    { id: "forward:p1:p2", label: "Edge ingress → Edge egress", direction: "forward", onset_status: "detected",
      onset_quality_notes: { capture_misses: 1, unknown_events: 2, reasons: ["Capture miss near the onset"] },
      onset_reasons: [{ metric: "latency_p95_ms", reason: "Baseline too short" }] },
  ],
  incident: {
    headline: "Delay occurs between Edge ingress and Edge egress.", quality_note: "Offload mismatch is separate.",
    window: { start: 1700000020, end: 1700000045 },
    loss_groups: [{ hop: "forward:p1:p2", headline: "Original copy missing", counts: { dropped: 3 }, evidence: [REF] }],
    timeline: [
      { start: 1700000020, rx_max_ms: 101.5, tx_max_ms: 0.2, tx_dropped: 3, tx_delayed: 4, retransmissions: 5, evidence: [REF] },
      { start: 1700000099, rx_max_ms: 0.1, tx_max_ms: 0.2, tx_dropped: 0, tx_delayed: 0, retransmissions: 0, evidence: [] },
    ],
    hold_release: [
      { id: "hr-1", hop: "forward:p1:p2", time_range: [1700000021, 1700000022], summary: "Packets were held and released together.",
        tooltip: "Hold and release tooltip", metrics: { wire_spacing_ms: [1, 2], receive_spacing_ms: [0.01, 0.02] }, evidence: [REF] },
    ],
  },
  cycles: {
    note: "A cycle is a client data burst.", scope: "Saved analysis window", stall_ms: 100,
    flow_ports: [{ point: "p1", flow: FLOW, port: 443, cycles: 19, stalled: 2, p95_ms: 45.1, max_ms: 46, before_p95_ms: null,
      event_p95_ms: 90, recovery_p95_ms: null,
      per_hop_added: JSON.stringify([{ hop: "forward:p1:p2", component: "RX", p95_ms: 0.03 }]) }],
    periods: [{ point: "p1", flow: FLOW, period: "event", cycles: 4, p95_ms: 90.5, reason: null }],
    pairs: [{ hop: "forward:p1:p2", component: "RX", added_ms: 0.03, uncertainty_ms: 0.001, reason: null }],
    top: [
      { cycle_id: "c".repeat(32), point: "p1", port: 443, period: "event", cycle_ms: 91.5, turnaround_ms: 90.1, request_first: 1700000024.7, evidence: [REF] },
      { cycle_id: "d".repeat(32), point: "p2", port: 443, period: "before", cycle_ms: 12.5, turnaround_ms: 11.1, request_first: 1700000500, evidence: [] },
    ],
  },
  delivery: { note: "Delay and drop are separate.", series: [
    { point_a: "p1", point_b: "p2", direction: "forward", start: 1700000020, delayed: 4, dropped: 3, capture_misses: 1, unknown: 0, max_delay_ms: 101, max_recovery_ms: null },
  ] },
  host_path: {
    note: "Host path note.", scope: "Saved analysis window", items: [{
      hop: "forward:p1:p2", direction: "forward", headline: "Host boundary delayed", max_ms: 100.5, uncertainty_ms: 0.8,
      delay_share_percent: 98.5, affected_flow_count: 1,
      window: { start: 1700000020, end: 1700000045, recovery_confirmed: true, status: "detected" },
      periods: [{ period: "event", p95_ms: 100, max_ms: 100.5, bytes: 4096 }],
      flow_details: [{ flow: "192.0.2.10:40000 → 192.0.2.20:80", p95_ms: 100, max_ms: 100.5, reason: null, evidence: [REF] }],
      evidence: [REF],
    }],
  },
  sessions: {
    note: "Observed SYN-anchored sessions.", scope: "Full saved analysis window", timeout_seconds: 10,
    points: [
      { point: "p1", label: "Edge ingress", status: "observed", syn_sent: 17, synack_seen: 18, established: 18, closed: 16, tooltip: "t", evidence: [REF] },
      { point: "p2", label: "Edge egress", status: "observed", syn_sent: 18, synack_seen: 18, established: 18, closed: 16, tooltip: "t", evidence: [] },
    ],
    buckets: [
      { point: "p1", bucket: 0, start: 1700000000, end: 1700000001, coverage: "covered", syns: 1, synacks: 1, established: 1, closed: 0, half_open: 0 },
      { point: "p2", bucket: 0, start: 1700000000, end: 1700000001, coverage: "covered", syns: 2, synacks: 2, established: 2, closed: 0, half_open: 0 },
    ],
    failed_handshake_count: 1,
    failed_handshakes: [{ point_a: "p1", point_b: "p2", time: 1700000003, reason: "SYN seen at p1, never at p2", evidence: [REF] }],
  },
  security: { note: "Heuristics describe observed traffic.", thresholds: {}, quality: [
    { point: "p1", status: "unknown", reason: "Clock unverified" }, { point: "p1", status: "unknown", reason: "Clock unverified again" },
  ] },
  microbursts: {
    note: "Capture-time bursts.", window_ms: 10, total: 1,
    points: [{ point: "p1", label: "Edge ingress", peak_observed_mbps: 940.5, baseline_mbps: 12.5, threshold_mbps: 60, burst_count: 3, max_duration_ms: 12, frequency_hz: 0.5, tooltip: "t", evidence: [REF] }],
    items: [{ point: "p1", start: 1700000001, end: 1700000001.01, corrected_start: 1700000001, peak_mbps: 940.5, duration_ms: 12, min_interarrival_us: 8.5,
      tooltip: "t", evidence: [REF], loss_associations: [{ segment: "Edge ingress → Edge egress", ordering: "burst before loss", reason: "Burst 5 ms before loss", evidence: [REF] }] }],
  },
  proxies: {
    note: "Proxy note.", transaction_count: 1, legs: [{ point: "p1", flow: FLOW, packets: 10, retransmissions: 1, resets: 0, loss_by_class: { recovered_loss: 1 } }],
    reset_notes: [{ device: "LB-ALPHA-01", status: "unknown", reason: "Both sides observed RST", evidence: [REF] }],
    transactions: [{ device: "LB-ALPHA-01", kind: "http", request: "GET /health HTTP/1.1", status: "matched", reason: null,
      client_flow: "client-leg", server_flow: "server-leg", request_dwell: { duration_ms: 1.5, uncertainty_ms: 0.1, reason: null },
      response_dwell: { duration_ms: null, uncertainty_ms: 0.1, reason: "Clock unverified" }, evidence: [REF] }],
  },
  tls: [{ status: "unknown", device: "LB-ALPHA-01", point: "p1", sni: "service.example.test", reason: "Certificate chain not visible", evidence: [REF] }],
  f5: { note: "F5 note.", connection_count: 1, pairs: [{ flowid: "f1", peerid: "p1", tmm: "0.1", role: "client", reason: null, client_flow: "cf", server_flow: "sf" }],
    requests: [{ device: "LB-ALPHA-01", request: "GET / HTTP/1.1", request_dwell_ms: 2.5, clock_uncertainty_ms: 1, reason: null, note: "n", evidence: [REF] }],
    resets: [{ device: "LB-ALPHA-01", reason: "TMM reported reset", time: 1700000005, evidence: [REF] }] },
  onsets: { status: "detected", summary: "Onset detected on 1 segment.", items: [{ segment: "forward:p1:p2", metric: "latency", bucket: 1, time: 1700000020, explanation: "Latency rose", evidence: [REF] }],
    directions: { forward: { prime_suspects: [], caveat: "Order is approximate", propagation_order: [{ time: 1700000020, segments: ["forward:p1:p2"] }] } } },
  confirmed_device_drops: [{ device: "FW-TANGO-04", stage: "drop", count: 7 }],
  vendor_device_events: [{ device: "FW-TANGO-04", stage: "drop", status: "unknown", reason: "Counter not provable", evidence: [REF] }],
  sequence_translations: [{ ingress: "p1", egress: "p2", flow: FLOW, status: "learned", reason: null, boundary_forward_offset: 100, boundary_reverse_offset: 200, samples: 5, evidence: [REF] }],
  offload_points: [{ point: "p1", large_frames: 3, note: "Large frames present" }],
  application_status: [{ point: "p1", reason: "Decoder disabled", cached: false }],
  fragment_points: [{ point: "p1", status: "ambiguous", datagrams: 4, physical_fragments: 9 }],
  tunnel_points: [{ point: "p2", selected: 10, unselected: 2, layer: "outer", truncated_inner: 1 }],
  udp: { note: "UDP note.", scope: "Saved window", total: 79, summary: [{ point: "p1", protocol: "ntp", endpoint: "198.51.100.5", requests: 4, answered: 3, unanswered: 1, unknown: 0,
      no_response_expected: 0, p50_ms: 1.5, p95_ms: 2.5, max_ms: 3.5 }],
    items: [{ point: "p1", frame: 3, protocol: "ntp", status: "unanswered", latency_ms: null, hop: "forward:p1:p2", reason: "No response observed", tooltip: "t", evidence: [REF] }] },
  rtp: { note: "RTP note.", scope: "Saved window", streams: [{ point: "p1", flow: "rtp-flow", direction: "forward", ssrc: 12345, packets: 100, gaps: 2, duplicates: 0, reordered: 1,
      jitter_p95_ms: 1.5, jitter_max_ms: 4.5, evidence: [REF] }],
    one_way: [{ point: "p1", seen_direction: "forward", reason: "No reverse stream", stopping_hop: "forward:p1:p2", evidence: [REF] }] },
  sip: { note: "SIP note.", scope: "Saved window", total: 1, comparisons: [{ call_id: "call-1", hop: "forward:p1:p2", status: "rewritten", reason: "Contact changed", media_status: "match",
      changes: { "sip.Contact": { before: "<sip:a@192.0.2.10>", after: "<sip:a@192.0.2.20>" } }, evidence: [REF], tooltip: "t" }],
    one_way: [{ call_id: "call-2", hop: "forward:p1:p2", reason: "Only caller audio", evidence: [REF], tooltip: "t" }] },
} as unknown as PacketAnalysisReport;

function themed(node: React.ReactNode) {
  return render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("Packet Analysis evidence sections", () => {
  it("renders the incident timeline with loss groups and opens frame evidence from a row", () => {
    const onEvidence = vi.fn();
    themed(<IncidentSection report={report} range={null} onEvidence={onEvidence} />);
    const card = screen.getByLabelText("Incident timeline", { selector: "div" });
    expect(within(card).getByText(/Delay occurs between Edge ingress and Edge egress/)).toBeInTheDocument();
    expect(within(card).getByText(/Original copy missing/)).toBeInTheDocument();
    expect(within(card).getByText("101.5")).toBeInTheDocument();
    fireEvent.click(within(card).getByText(/Original copy missing/));
    expect(onEvidence).toHaveBeenCalledWith([REF]);
    fireEvent.click(within(card).getAllByRole("button", { name: "Frames" })[0]!);
    expect(onEvidence).toHaveBeenLastCalledWith([REF]);
  });

  it("limits the timeline to the selected interval and says so when no incident was computed", () => {
    themed(<IncidentSection report={report} range={[1700000090, 1700000100]} onEvidence={vi.fn()} />);
    const table = screen.getByRole("table", { name: "Incident timeline" });
    expect(within(table).queryByText("101.5")).not.toBeInTheDocument();
    expect(within(table).getByText("0.1")).toBeInTheDocument();
    themed(<IncidentSection report={{ ...report, incident: undefined } as PacketAnalysisReport} range={null} onEvidence={vi.fn()} />);
    expect(screen.getByText("No incident series computed.")).toBeInTheDocument();
  });

  it("renders cycles, hold and release, delivery and host path, and hides raw tables from non-raw roles", () => {
    const onEvidence = vi.fn();
    const { unmount } = themed(
      <CyclesSection caseId={CASE} report={report} topology={topology} canRawView range={null} onEvidence={onEvidence} />,
    );
    expect(screen.getByRole("link", { name: "Download CSV tables (ZIP)" }).getAttribute("href"))
      .toBe(`/api/v2/packet-analysis/cases/${CASE}/export/download?format=csv`);
    expect(screen.getByRole("link", { name: "Download XLSX workbook" }).getAttribute("href")).toContain("format=xlsx");
    const flowPorts = screen.getByRole("table", { name: "Cycles per flow and port" });
    expect(within(flowPorts).getByText(`${FLOW.slice(0, 8)} / 443`)).toBeInTheDocument();
    // per_hop_added is stored as JSON text and shown as readable hop components
    expect(within(flowPorts).getByText("forward:p1:p2 RX 0.03 ms")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Cycle added time per boundary" })).toBeInTheDocument();
    expect(screen.getByText(/Packets were held and released together/)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/Packets were held and released together/));
    expect(onEvidence).toHaveBeenCalledWith([REF]);
    expect(within(screen.getByRole("table", { name: "Delivery series" })).getByText("101 / unknown")).toBeInTheDocument();
    expect(screen.getByText("FORWARD · forward:p1:p2")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Open boundary evidence" }));
    expect(onEvidence).toHaveBeenLastCalledWith([REF]);
    unmount();

    themed(<CyclesSection caseId={CASE} report={report} topology={topology} canRawView={false} range={[1700000000, 1700000100]} onEvidence={onEvidence} />);
    expect(screen.queryByRole("link", { name: "Download CSV tables (ZIP)" })).not.toBeInTheDocument();
    const top = screen.getByRole("table", { name: "Top slow cycles" });
    expect(within(top).getByText("91.5")).toBeInTheDocument();
    expect(within(top).queryByText("12.5")).not.toBeInTheDocument(); // outside the selected interval
  });

  it("loads bucket evidence from the server and shows the server's refusal instead of a guess", async () => {
    const onEvidence = vi.fn();
    vi.mocked(api.getPacketCaseSessionEvidence)
      .mockResolvedValueOnce({ evidence: [REF], note: "n" })
      .mockRejectedValueOnce({ body: { reason: "Unknown lifecycle point or no saved analysis" } });
    themed(<SessionsSection caseId={CASE} report={report} onEvidence={onEvidence} />);
    expect(screen.getByRole("table", { name: "TCP lifecycle per point" })).toBeInTheDocument();
    expect(screen.getByText(/1 of 1 shown/)).toBeInTheDocument();
    // duplicate security quality notes must not collapse
    expect(screen.getByText(/Clock unverified again/)).toBeInTheDocument();
    const buckets = screen.getByRole("table", { name: "Half-open occupancy" });
    fireEvent.click(within(buckets).getAllByRole("button")[0]!);
    await waitFor(() => expect(onEvidence).toHaveBeenCalledWith([REF]));
    expect(api.getPacketCaseSessionEvidence).toHaveBeenCalledWith(CASE, { point: "p1", start: 1700000000, end: 1700000001 });
    fireEvent.click(within(screen.getByRole("table", { name: "Half-open occupancy" })).getAllByRole("button")[0]!);
    expect(await screen.findByText("Unknown lifecycle point or no saved analysis")).toBeInTheDocument();
  });

  it("renders microbursts with their loss association and keeps unknown loss causes unknown", () => {
    const onEvidence = vi.fn();
    themed(<MicroburstsSection report={report} onEvidence={onEvidence} />);
    expect(screen.getByText(/Burst 5 ms before loss/)).toBeInTheDocument();
    expect(screen.getByText(/Rolling window: 10 ms. 1 episodes; 1 shown./)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Edge ingress" }));
    expect(onEvidence).toHaveBeenCalledWith([REF]);
    themed(<MicroburstsSection report={{ ...report, microbursts: { ...report.microbursts!, items: [{ ...report.microbursts!.items[0]!, loss_associations: [] }] } } as PacketAnalysisReport} onEvidence={onEvidence} />);
    expect(screen.getByText(/No supported nearby loss onset association; cause unknown./)).toBeInTheDocument();
  });

  it("renders protocol, translation, onset and capture-note details from the engine report", () => {
    const onEvidence = vi.fn();
    themed(
      <>
        <ProtocolDiagnosticsSection report={report} topology={topology} onEvidence={onEvidence} />
        <CaptureNotesSection report={report} />
      </>,
    );
    expect(screen.getByText("FW-TANGO-04 · drop: 7")).toBeInTheDocument();
    expect(screen.getByText(/forward SEQ offset 100, ACK offset 200/)).toBeInTheDocument();
    expect(screen.getByText(/Counter not provable/)).toBeInTheDocument();
    expect(screen.getByText(/Decoder disabled/)).toBeInTheDocument();
    expect(screen.getByText(/GET \/health HTTP\/1.1/)).toBeInTheDocument();
    expect(screen.getByText(/Certificate chain not visible/)).toBeInTheDocument();
    expect(screen.getByText(/TMM reported reset/)).toBeInTheDocument();
    expect(screen.getByText(/Large frames present/)).toBeInTheDocument();
    expect(screen.getByText(/Propagation · forward/)).toBeInTheDocument();
    expect(screen.getByText(/Baseline too short/)).toBeInTheDocument();
    expect(screen.getByText(/4 IP datagrams from 9 fragments — ambiguous/)).toBeInTheDocument();
    expect(screen.getByText(/ERSPAN\/inner payload truncation: 1 observations/)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/Latency rose/));
    expect(onEvidence).toHaveBeenCalledWith([REF]);
  });

  it("renders UDP, SIP and RTP evidence", () => {
    const onEvidence = vi.fn();
    themed(<UdpMediaSection report={report} onEvidence={onEvidence} />);
    expect(screen.getByRole("table", { name: "UDP summary" })).toBeInTheDocument();
    expect(screen.getByText(/79 total|1 of 79 examples shown/)).toBeInTheDocument();
    expect(screen.getByText(/call-1 · forward:p1:p2: rewritten/)).toBeInTheDocument();
    expect(screen.getByText(/sip.Contact: <sip:a@192.0.2.10> → <sip:a@192.0.2.20>/)).toBeInTheDocument();
    expect(screen.getByText(/call-2: one-way audio/)).toBeInTheDocument();
    expect(screen.getByText(/SSRC 12345/)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/SSRC 12345/));
    expect(onEvidence).toHaveBeenCalledWith([REF]);
  });

  it("offers engine file downloads to raw-view roles only, and the masked JSON projection to everyone else", async () => {
    const { unmount } = themed(<ExportActions caseId={CASE} canRawView onError={vi.fn()} />);
    expect(screen.getByRole("link", { name: "Export HTML" }).getAttribute("href")).toContain("format=html");
    expect(screen.getByRole("link", { name: "Export JSON" }).getAttribute("href")).toContain("format=json");
    expect(screen.getByRole("link", { name: "Expert report (XLSX)" }).getAttribute("href"))
      .toBe(`/api/v2/packet-analysis/cases/${CASE}/export/expert-report`);
    unmount();

    const onError = vi.fn();
    vi.mocked(api.getPacketCaseFindingsExport).mockRejectedValueOnce(new Error("boom"));
    themed(<ExportActions caseId={CASE} canRawView={false} onError={onError} />);
    expect(screen.queryByRole("link", { name: "Export HTML" })).not.toBeInTheDocument();
    // the expert workbook is read-grade: the server masks it for every role without raw view
    expect(screen.getByRole("link", { name: "Expert report (XLSX)" }).getAttribute("href"))
      .toBe(`/api/v2/packet-analysis/cases/${CASE}/export/expert-report`);
    fireEvent.click(screen.getByRole("button", { name: "Export JSON" }));
    await waitFor(() => expect(onError).toHaveBeenCalledWith("The findings export could not be prepared."));
  });

  it("builds the expert report link from the selected interval, and ignores an empty or inverted one", () => {
    const { unmount } = themed(<ExportActions caseId={CASE} canRawView={false} onError={vi.fn()} range={[1700000012.5, 1700000030]} />);
    expect(screen.getByRole("link", { name: "Expert report (XLSX)" }).getAttribute("href"))
      .toBe(`/api/v2/packet-analysis/cases/${CASE}/export/expert-report?start=1700000012.5&end=1700000030`);
    unmount();
    themed(<ExportActions caseId={CASE} canRawView onError={vi.fn()} range={[1700000030, 1700000030]} />);
    expect(screen.getByRole("link", { name: "Expert report (XLSX)" }).getAttribute("href"))
      .toBe(`/api/v2/packet-analysis/cases/${CASE}/export/expert-report`);
    expect(api.packetCaseExpertReportHref("a/b", { start: 1, top: 5 }))
      .toBe("/api/v2/packet-analysis/cases/a%2Fb/export/expert-report?start=1&top=5");
  });

  it("compares the saved report with an uploaded reference and a previous run, showing refusals", async () => {
    vi.mocked(api.comparePacketCase)
      .mockResolvedValueOnce({
        counts: { pass: 1, fail: 0 },
        items: [{ id: "i1", description: "Loss on the first hop", metric: "segments[0].loss_percent", expected: 0, value: 0, status: "pass", reason: null }],
        diff: { metrics: [], findings: [], metric_difference_count: 2, truncated: false, note: "Run diff note." },
      })
      .mockRejectedValueOnce({ body: { reason: "Choose reference and/or against" } });
    themed(<ReferenceCompareSection caseId={CASE} />);
    const compare = screen.getByRole("button", { name: "Compare with current saved report" });
    expect(compare).toBeDisabled();
    const file = new File([JSON.stringify({ items: [] })], "reference.json", { type: "application/json" });
    Object.defineProperty(file, "text", { value: () => Promise.resolve(JSON.stringify({ items: [] })) });
    fireEvent.change(screen.getByLabelText("Reference JSON"), { target: { files: [file] } });
    await waitFor(() => expect(compare).toBeEnabled());
    fireEvent.click(compare);
    expect(await screen.findByText("Loss on the first hop")).toBeInTheDocument();
    expect(api.comparePacketCase).toHaveBeenCalledWith(CASE, { reference: { items: [] } });
    expect(screen.getByText(/2 metric differences/)).toBeInTheDocument();
    fireEvent.click(compare);
    expect(await screen.findByText("Choose reference and/or against")).toBeInTheDocument();
  });
});

describe("Packet Analysis workspace wiring", () => {
  it("reaches every engine report section from the overview, the quality tab and the UDP / Media tab", async () => {
    const workspace = {
      version: "0.4.0", project: CASE, captures: [], topology, report: { ...report, flow_count: 1, window: { start: 1700000000, end: 1700000100, common_start: 1700000000, common_end: 1700000100 },
        clocks: {}, coverage: [], quality: [], nat_suggestions: [], limitations: [] },
    } as never;
    vi.mocked(api.getPacketCaseState).mockResolvedValue(workspace);
    vi.mocked(api.getPacketCaseTimeseries).mockResolvedValue(undefined as never);
    vi.mocked(api.getPacketCaseCorrelation).mockResolvedValue(undefined as never);
    themed(<PacketCaseWorkspace caseId={CASE} permissions={{ run: true, raw_view: true, masked: false } as never} onError={vi.fn()} onNotice={vi.fn()} />);

    // Exports sit in the toolbar and the timeline is open by default.
    expect(await screen.findByRole("link", { name: "Export HTML" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Expert report (XLSX)" })).toBeInTheDocument();
    expect(await screen.findByLabelText("Timeline")).toBeInTheDocument();
    expect(screen.getByText(/Delay occurs between Edge ingress and Edge egress/)).toBeInTheDocument();

    // The other sections are collapsed until asked for.
    expect(screen.queryByRole("table", { name: "TCP lifecycle per point" })).not.toBeInTheDocument();
    for (const [title, probe] of [
      ["Cycles, hold and release, host path", () => screen.getByRole("table", { name: "Cycles per flow and port" })],
      ["Protocol, translation and onset details", () => screen.getByText(/Counter not provable/)],
      ["Session and microburst details", () => screen.getByRole("table", { name: "Microbursts per point" })],
      ["Reference and run comparison", () => screen.getByRole("button", { name: "Export reference template" })],
    ] as const) {
      fireEvent.click(within(screen.getByLabelText(title)).getByRole("button", { name: title }));
      expect(probe()).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("tab", { name: "UDP / Media" }));
    expect(await screen.findByRole("table", { name: "UDP summary" })).toBeInTheDocument();
    expect(screen.getByText(/SSRC 12345/)).toBeInTheDocument();
  });
});
