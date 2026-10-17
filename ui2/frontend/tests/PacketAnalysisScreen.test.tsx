import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PacketAnalysisScreen, packetBytes, packetError, packetFailureText, packetWindowText } from "../src/screens/PacketAnalysisScreen";
import * as api from "../src/auth/adminApi";
import type {
  PacketCaseDetail,
  PacketCaseList,
  PacketCaseWorkspaceState,
  PacketNexusCorrelation,
  PacketRef,
} from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  listPacketCases: vi.fn(), getPacketCase: vi.fn(), createPacketCase: vi.fn(), analyzePacketCase: vi.fn(),
  getPacketReport: vi.fn(), deletePacketCase: vi.fn(), uploadPacketCapture: vi.fn(),
  getPacketCaseState: vi.fn(), getPacketCaseCorrelation: vi.fn(), getPacketCaseFlows: vi.fn(), getPacketCaseFrames: vi.fn(),
  getPacketCaseDiagnosis: vi.fn(), getPacketCaseCharts: vi.fn(), getPacketCaseLadder: vi.fn(),
  getPacketCaseWaterfall: vi.fn(), getPacketCaseTimeseries: vi.fn(), getPacketCaseTopologyOverlay: vi.fn(),
  getPacketCaseTopologyInspect: vi.fn(), getPacketCaseFindings: vi.fn(), getPacketCaseEvents: vi.fn(),
  getPacketCaseDns: vi.fn(), getPacketCaseFieldDiff: vi.fn(), savePacketCaseTopology: vi.fn(),
}));

const CASE = "11111111-2222-4333-8444-555555555555";
const JOB = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
const stages = ["ingest", "core_flow_loss"].map(stage => ({ stage, verdict: "unknown", reason: "engine not yet ported" }));
const summary = { verdict: "unknown", reason: "engine not yet ported", stages, capture_count: 1 };
const operator = { run: true, raw_view: false, masked: false };
const aiview = { run: false, raw_view: false, masked: true };

const SYNTHETIC_REF: PacketRef = {
  point: "p1",
  file: "synthetic-a.pcap",
  capture_id: "c1",
  frame: 4,
  display_filter: "frame.number == 4",
  flow_filter: "ip.addr == 192.0.2.10 && ip.addr == 198.51.100.20",
  observed_time: 1700000000.05,
  corrected_time: 1700000000.05,
};

function syntheticWorkspaceState(): PacketCaseWorkspaceState {
  return {
    version: "0.4.0",
    project: CASE,
    captures: [
      {
        id: "c1",
        path: "synthetic-a.pcap",
        name: "synthetic-a.pcap",
        state: "ready",
        checkpoint: 5,
        error: null,
        inventory: {
          timestamps_validated: true,
          start: 1700000000.0,
          end: 1700000001.0,
          duration: 1.0,
          packet_count: 5,
          truncated: 0,
          possible_offload: 0,
          ifdrop: 0,
          osdrop: 0,
          format: "pcap",
        },
      },
      {
        id: "c2",
        path: "synthetic-b.pcap",
        name: "synthetic-b.pcap",
        state: "ready",
        checkpoint: 4,
        error: null,
        inventory: {
          timestamps_validated: true,
          start: 1700000000.002,
          end: 1700000001.002,
          duration: 1.0,
          packet_count: 4,
          truncated: 0,
          possible_offload: 0,
          ifdrop: 0,
          osdrop: 0,
          format: "pcap",
        },
      },
    ],
    topology: {
      points: [
        {
          id: "p1",
          label: "FW-TANGO-04 ingress",
          device: "FW-TANGO-04",
          kind: "Firewall",
          side: "ingress",
          capture_id: "c1",
          interface: null,
          source_cidr: null,
          translation: "none",
          x: 80,
          y: 120,
        },
        {
          id: "p2",
          label: "FW-TANGO-04 egress",
          device: "FW-TANGO-04",
          kind: "Firewall",
          side: "egress",
          capture_id: "c2",
          interface: null,
          source_cidr: null,
          translation: "none",
          x: 340,
          y: 120,
        },
      ],
      forward: ["p1", "p2"],
      reverse: ["p2", "p1"],
      client_cidrs: ["192.0.2.0/24", "2001:db8::/32"],
      nat_mappings: [],
      min_eligible_ratio: 0.5,
    },
    report: {
      verdict: "findings_present",
      scope: "2 capture points · 1 canonical conversation",
      flow_count: 1,
      window: {
        start: 1700000000.0,
        end: 1700000001.0,
        common_start: 1700000000.002,
        common_end: 1700000001.0,
      },
      clocks: {
        c1: {
          offset: 0,
          drift_ppm: 0,
          epoch: 1700000000.0,
          uncertainty: 0.2,
          confidence: "high",
          reason: "Reference capture",
          samples: 4,
          evidence: [SYNTHETIC_REF],
        },
      },
      coverage: [
        { point: "p1", start: 1700000000.0, end: 1700000001.0 },
        { point: "p2", start: 1700000000.002, end: 1700000001.002 },
      ],
      quality: [
        {
          point: "p1",
          file: "synthetic-a.pcap",
          ifdrop: 0,
          osdrop: 0,
          packets: 5,
          excluded: 0,
          prefilter_excluded: 0,
          truncated: 0,
          possible_offload: 0,
          unknown_direction: 0,
          evidence: [SYNTHETIC_REF],
        },
      ],
      nat_suggestions: [],
      segments: [
        {
          id: "forward:p1:p2",
          point_a: "p1",
          point_b: "p2",
          label: "FW-TANGO-04 ingress → FW-TANGO-04 egress",
          location: "device",
          direction: "forward",
          reason: null,
          matched: 2,
          p50_ms: 2.0,
          p95_ms: 2.1,
          max_ms: 2.2,
          loss_percent: 33.3,
          eligible_packets: 3,
          eligible_ratio: 1.0,
          excluded_counts: {},
          classes: { recovered_loss: 1, impactful_loss: 1 },
          offset_uncertainty_ms: 0.2,
          loss_suspect: true,
        },
      ],
      findings: [
        {
          id: "f-loss-1",
          type: "recovered_loss",
          severity: "high",
          hop: "forward:p1:p2",
          direction: "forward",
          time_range: [1700000000.05, 1700000000.25],
          confidence: "high",
          summary: "1 packet disappeared between FW-TANGO-04 ingress and FW-TANGO-04 egress and was recovered by retransmission",
          headline: "Packet loss recovered by retransmission at FW-TANGO-04",
          evidence: [SYNTHETIC_REF],
          evidence_note: "Observed at p1 frame #4, missing at p2",
          metrics: { count: 1 },
        },
      ],
      limitations: ["Timestamps have ±0.200 ms clock uncertainty."],
    },
  };
}

function listing(permissions = operator): PacketCaseList {
  return {
    cases: [{ case_id: CASE, title: permissions.masked ? "OBJ-TANGO-04" : "Synthetic loss case", created_at: "2026-10-15T08:00:00Z",
      expires_at: "2026-10-22T08:00:00Z", state: "ANALYZED", total_bytes: 3 * 1024 * 1024, capture_count: 1,
      payload_zeroing: false, latest_job_id: JOB }],
    permissions, limits: { max_file_bytes: 2 * 1024 ** 3, max_store_bytes: 100 * 1024 ** 3, used_bytes: 3 * 1024 * 1024, retention_days: 7 },
  };
}
function detail(permissions = operator): PacketCaseDetail {
  return { ...listing(permissions).cases[0], permissions,
    captures: [{ capture_id: "66666666-7777-4888-9999-aaaaaaaaaaaa", display_name: permissions.masked ? "OBJ-JULIET-06" : "synthetic-a.pcap",
      format: "pcap", size_bytes: 3 * 1024 * 1024, uploaded_at: "2026-10-15T08:05:00Z", ...(permissions.masked ? {} : { sha256: "a".repeat(64) }) }],
    runs: [{ job_id: JOB, case_id: CASE, state: "COMPLETED", requested_at: "2026-10-15T08:06:00Z", started_at: "2026-10-15T08:06:05Z",
      finished_at: "2026-10-15T08:06:09Z", engine_version: "stub-0", failure_reason: null, summary }] };
}
function themed() { return render(<ThemeProvider theme={m3Theme}><PacketAnalysisScreen /></ThemeProvider>); }

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.listPacketCases).mockResolvedValue(listing());
  vi.mocked(api.getPacketCase).mockResolvedValue(detail());
});

describe("Packet Analysis screen", () => {
  it("lists cases with their expiry date and the store limits", async () => {
    themed();
    const table = await screen.findByRole("table", { name: "Packet analysis cases" });
    expect(within(table).getByText("Synthetic loss case")).toBeInTheDocument();
    expect(within(table).getByText("2026-10-22")).toBeInTheDocument();
    expect(within(table).getByText("ANALYZED")).toBeInTheDocument();
    expect(screen.getByText(/at most 2.0 GB per capture/)).toBeInTheDocument();
    expect(screen.queryByText(/still being ported/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText(/Results are evidence-led: a stage that cannot be established is reported as unknown, with the reason/)).toBeInTheDocument();
  });

  it("explains a run that stopped at the memory budget and names the window the budget chose", async () => {
    vi.mocked(api.getPacketCase).mockResolvedValue({ ...detail(), runs: [
      { job_id: JOB, case_id: CASE, state: "FAILED", requested_at: "2026-10-15T08:06:00Z", started_at: "2026-10-15T08:06:05Z",
        finished_at: "2026-10-15T08:06:09Z", engine_version: null, failure_reason: "ANALYSIS_MEMORY_LIMIT", summary: null },
      { job_id: "cccccccc-cccc-4ddd-8eee-ffffffffffff", case_id: CASE, state: "COMPLETED", requested_at: "2026-10-15T08:07:00Z",
        started_at: "2026-10-15T08:07:05Z", finished_at: "2026-10-15T08:07:09Z", engine_version: "0.3.10", failure_reason: null,
        summary: { ...summary, analysis_window: { mode: "guarded", reason: "budget", start: 1700000000, end: 1700000060, seconds: 60,
          max_packets_per_capture: 150000 } } },
    ] });
    themed();
    fireEvent.click(await screen.findByText("Synthetic loss case"));
    expect(await screen.findByText("ANALYSIS_MEMORY_LIMIT")).toBeInTheDocument();
    expect(screen.getByText(/Narrow the analysis window/)).toBeInTheDocument();
    expect(screen.getByText(/Analysed window: 2023-11-14T22:13:20Z \+ 60 s, reason: budget/)).toBeInTheDocument();
  });

  it("has a message for each resource reason and none for others", () => {
    expect(packetFailureText("ANALYSIS_MEMORY_LIMIT")).toMatch(/Narrow the analysis window/);
    expect(packetFailureText("ANALYSIS_WINDOW_REQUIRED")).toMatch(/Choose an analysis window/);
    expect(packetFailureText("ENGINE_FAILED")).toBeNull();
    expect(packetFailureText(null)).toBeNull();
    expect(packetWindowText({ mode: "full" })).toBeNull();
    expect(packetWindowText(undefined)).toBeNull();
  });

  it("creates a case with the payload zeroing option an operator chose", async () => {
    vi.mocked(api.createPacketCase).mockResolvedValue({ ...listing().cases[0], case_id: CASE, state: "OPEN" });
    themed();
    fireEvent.change(await screen.findByLabelText("Case title"), { target: { value: "  New synthetic case " } });
    fireEvent.click(screen.getByLabelText(/Payload zeroing/));
    fireEvent.click(screen.getByRole("button", { name: "Create case" }));
    await waitFor(() => expect(api.createPacketCase).toHaveBeenCalledWith("New synthetic case", true));
  });

  it("uploads with progress, analyses and shows verdict, unknown reasons and the JSON", async () => {
    let finish: () => void = () => undefined;
    vi.mocked(api.uploadPacketCapture).mockImplementation((_id, _file, onProgress) => new Promise(resolve => {
      onProgress(512, 1024);
      finish = () => resolve({ capture_id: "c", size_bytes: 1024, format: "pcap" });
    }));
    vi.mocked(api.analyzePacketCase).mockResolvedValue({ job_id: JOB, state: "QUEUED" });
    vi.mocked(api.getPacketReport).mockResolvedValue({ job_id: JOB, case_id: CASE, projection: "full",
      report: { engine: "stub", ...summary } });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    const card = await screen.findByLabelText("Case detail");
    expect(within(card).getByText("synthetic-a.pcap")).toBeInTheDocument();
    const file = new File([new Uint8Array(1024)], "synthetic-b.pcapng");
    fireEvent.change(screen.getByLabelText("Capture file"), { target: { files: [file] } });
    const progress = await screen.findByLabelText("Upload progress");
    expect(within(progress).getByText("Uploading synthetic-b.pcapng: 512 B of 1.0 KB")).toBeInTheDocument();
    expect(within(progress).getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
    finish();
    expect(await screen.findByText("Capture uploaded.")).toBeInTheDocument();
    expect(api.uploadPacketCapture).toHaveBeenCalledWith(CASE, file, expect.any(Function));

    fireEvent.click(screen.getByRole("button", { name: "Analyze" }));
    await waitFor(() => expect(api.analyzePacketCase).toHaveBeenCalledWith(CASE));
    fireEvent.click(screen.getByRole("button", { name: "View result" }));
    const result = await screen.findByLabelText("Analysis result");
    expect(within(result).getByText("Verdict: unknown")).toBeInTheDocument();
    expect(within(result).getAllByText("engine not yet ported").length).toBeGreaterThan(1);
    expect(within(result).getByText("core_flow_loss")).toBeInTheDocument();
    expect(within(result).getByLabelText("Result JSON").textContent).toContain('"engine": "stub"');
  });

  it("refuses a file over the server's per-file limit before sending a byte", async () => {
    vi.mocked(api.listPacketCases).mockResolvedValue({ ...listing(), limits: { ...listing().limits, max_file_bytes: 100 } });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case detail");
    fireEvent.change(screen.getByLabelText("Capture file"), { target: { files: [new File([new Uint8Array(200)], "big.pcap")] } });
    expect(await screen.findByText("A capture may be at most 100 B.")).toBeInTheDocument();
    expect(api.uploadPacketCapture).not.toHaveBeenCalled();
  });

  it("renders the masked aiview projection with no write or raw affordance", async () => {
    vi.mocked(api.listPacketCases).mockResolvedValue(listing(aiview));
    vi.mocked(api.getPacketCase).mockResolvedValue(detail(aiview));
    vi.mocked(api.getPacketReport).mockResolvedValue({ job_id: JOB, case_id: CASE, projection: "masked", summary });
    themed();
    // The top bar already says the session is masked; the page does not repeat it, and shows the refused action instead.
    expect(await screen.findByRole("button", { name: "New case" })).toBeDisabled();
    expect(screen.queryByText("Masked view")).toBeNull();
    expect(screen.queryByLabelText("Case title")).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "OBJ-TANGO-04" }));
    const card = await screen.findByLabelText("Case detail");
    expect(within(card).getByText("OBJ-JULIET-06")).toBeInTheDocument();
    expect(within(card).queryByText(/sha256/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Upload capture" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Analyze" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Delete case" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Download" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "View result" }));
    expect(await screen.findByText("Masked summary")).toBeInTheDocument();
  });

  it("offers the ledgered download only when the server grants raw view", async () => {
    const admin = { run: true, raw_view: true, masked: false };
    vi.mocked(api.listPacketCases).mockResolvedValue(listing(admin));
    vi.mocked(api.getPacketCase).mockResolvedValue(detail(admin));
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    const link = await screen.findByRole("link", { name: "Download" });
    expect(link).toHaveAttribute("href", "/api/v2/packet-analysis/captures/66666666-7777-4888-9999-aaaaaaaaaaaa/download");
  });

  it("shows the server's refusal reason and never a guess", () => {
    expect(packetError({ status: 507, body: { error: "STORE_FULL", reason: "The analysis store would pass its cap" } }, "x"))
      .toBe("The analysis store would pass its cap");
    expect(packetError({ status: 403, body: {} }, "x")).toBe("Your role does not allow this action.");
    expect(packetBytes(2 * 1024 ** 3)).toBe("2.0 GB");
    expect(packetBytes(-1)).toBe("UNKNOWN");
  });

  it("renders case overview, ranked findings with frame evidence modal, hop-by-hop segments, and capture quality", async () => {
    vi.mocked(api.getPacketCaseState).mockResolvedValue(syntheticWorkspaceState());
    vi.mocked(api.getPacketCaseEvents).mockResolvedValue({
      total: 1,
      items: [
        {
          id: "ev-1",
          kind: "recovered_loss",
          reason: "Observed at p1, missing at p2, recovered 200 ms later",
          ts: 1700000000.05,
          recovery_ms: 200.0,
          evidence: [SYNTHETIC_REF],
        },
      ],
    });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));

    const resultCard = await screen.findByLabelText("Investigation result");
    expect(within(resultCard).getByText("Case verdict: findings_present")).toBeInTheDocument();
    expect(within(resultCard).getByText(/Observed finding at FW-TANGO-04 ingress → FW-TANGO-04 egress/)).toBeInTheDocument();

    const findingsCard = screen.getByLabelText("Ranked findings");
    expect(within(findingsCard).getByText("Packet loss recovered by retransmission at FW-TANGO-04")).toBeInTheDocument();

    fireEvent.click(within(findingsCard).getByText("Packet loss recovered by retransmission at FW-TANGO-04"));
    const modal = await screen.findByRole("dialog", { name: "Frame evidence" });
    expect(within(modal).getByText("synthetic-a.pcap")).toBeInTheDocument();
    expect(within(modal).getByText("#4")).toBeInTheDocument();
    expect(within(modal).getByText("frame.number == 4")).toBeInTheDocument();
    fireEvent.click(within(modal).getByRole("button", { name: "Close" }));

    const segTable = screen.getByRole("table", { name: "Hop-by-hop segments" });
    expect(within(segTable).getByText("33.3%")).toBeInTheDocument();
    fireEvent.click(within(segTable).getByText("FW-TANGO-04 ingress → FW-TANGO-04 egress"));

    const eventsCard = await screen.findByLabelText("Segment events");
    expect(within(eventsCard).getByText(/Observed at p1, missing at p2, recovered 200 ms later/)).toBeInTheDocument();

    const qualityCard = screen.getByLabelText("Capture quality");
    expect(within(qualityCard).getByText("Drops: 0 / 0")).toBeInTheDocument();
  });

  it("renders topology canvas, overlay metrics, node inspection, and saves topology changes", async () => {
    const ws = syntheticWorkspaceState();
    vi.mocked(api.getPacketCaseState).mockResolvedValue(ws);
    vi.mocked(api.getPacketCaseTopologyOverlay).mockResolvedValue({
      segments: [
        {
          id: "forward:p1:p2",
          loss_percent: 33.3,
          p95_ms: 2.1,
          retrans_observations: 1,
          reason: null,
          classes: { recovered_loss: 1 },
        },
      ],
      nodes: [
        {
          device: "FW-TANGO-04",
          observations: 9,
          retrans_observations: 1,
          supported_loss_events: 1,
          confirmed_device_drops: 0,
          findings: 1,
        },
      ],
      note: "Overlay across full window",
    });
    vi.mocked(api.getPacketCaseTopologyInspect).mockResolvedValue({
      findings: ws.report!.findings,
      flows: {
        total: 1,
        items: [
          {
            flow: "f1",
            tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
            start: 1700000000.0,
            end: 1700000000.5,
            bytes: 400,
            points: 2,
            confirmed_device_drop: 0,
            impactful_loss: 0,
            recovered_loss: 1,
            unrecovered_loss: 0,
            handshake_blocked: 0,
            unknown_events: 0,
            capture_miss: 0,
            retrans_observations: 1,
            max_stall_ms: 200.0,
            max_rtt_ms: 15.0,
            has_reset: false,
            handshake_incomplete: false,
          },
        ],
      },
    });
    vi.mocked(api.savePacketCaseTopology).mockResolvedValue(ws);

    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case investigation workspace");

    fireEvent.click(screen.getByRole("tab", { name: "Topology" }));
    const topoCard = await screen.findByLabelText("Topology workspace");
    expect(within(topoCard).getByLabelText("Topology canvas")).toBeInTheDocument();

    fireEvent.click(within(topoCard).getByRole("button", { name: "Inspect FW-TANGO-04 ingress" }));
    const inspectCard = await screen.findByLabelText("Selected device or arrow");
    expect(within(inspectCard).getByText("1 associated conversations")).toBeInTheDocument();

    fireEvent.click(within(topoCard).getByRole("button", { name: "Save topology" }));
    await waitFor(() => expect(api.savePacketCaseTopology).toHaveBeenCalledWith(CASE, expect.objectContaining({
      client_cidrs: ["192.0.2.0/24", "2001:db8::/32"],
    })));
    expect(await screen.findByText("Topology saved and re-analyzed.")).toBeInTheDocument();
  });

  it("renders flows, diagnosis, waterfall, stream charts, multi-hop ladder, and raw field diff when authorized", async () => {
    const admin = { run: true, raw_view: true, masked: false };
    vi.mocked(api.listPacketCases).mockResolvedValue(listing(admin));
    vi.mocked(api.getPacketCase).mockResolvedValue(detail(admin));
    vi.mocked(api.getPacketCaseState).mockResolvedValue(syntheticWorkspaceState());
    vi.mocked(api.getPacketCaseFlows).mockResolvedValue({
      total: 1,
      items: [
        {
          flow: "f1",
          tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
          start: 1700000000.0,
          end: 1700000000.5,
          bytes: 400,
          points: 2,
          confirmed_device_drop: 0,
          impactful_loss: 0,
          recovered_loss: 1,
          unrecovered_loss: 0,
          handshake_blocked: 0,
          unknown_events: 0,
          capture_miss: 0,
          retrans_observations: 1,
          max_stall_ms: 200.0,
          max_rtt_ms: 15.0,
          has_reset: false,
          handshake_incomplete: false,
          handshake_rtt_ms: 10.0,
        },
      ],
    });
    vi.mocked(api.getPacketCaseDiagnosis).mockResolvedValue({
      items: [
        {
          flow: "f1",
          direction: "forward",
          classification: "network_path_loss",
          reason: "Supported loss between FW-TANGO-04 ingress and egress",
          tooltip: "Evidence-backed loss",
          evidence: [SYNTHETIC_REF],
          metrics: {
            max_receive_window: 64240,
            max_bytes_in_flight: 200,
            zero_windows: 0,
            window_full: 0,
            window_shrinks: 0,
            ack_delay_p95_ms: 1.5,
            path_p95_ms: 2.1,
            service_ms: 5.0,
            loss_count: 1,
          },
        },
      ],
      reason: null,
      retransmissions: {
        points: [
          {
            flow: "f1",
            point: "p1",
            direction: "forward",
            tooltip: "Retransmissions at p1",
            reason: null,
            rto: 1,
            fast: 0,
            spurious: 0,
            sack: 0,
            unknown: 0,
            duplicate_acks: 0,
          },
        ],
        segments: [],
        events: [],
        reordering: [],
        note: "Retransmission classification by point",
      },
    });
    vi.mocked(api.getPacketCaseWaterfall).mockResolvedValue({
      items: [
        {
          kind: "handshake",
          title: "TCP handshake",
          bars: [
            {
              label: "Client SYN → Server SYN/ACK",
              kind: "transit",
              duration_ms: 10.0,
              start_ms: 0.0,
              uncertainty_ms: 0.2,
              reason: null,
              note: "Measured at p1",
              evidence: [SYNTHETIC_REF],
            },
          ],
        },
      ],
      requests: [],
      reason: null,
    });
    vi.mocked(api.getPacketCaseCharts).mockResolvedValue({
      point: "p1",
      direction: "forward",
      aligned: true,
      points: [{ point: "p1", label: "FW-TANGO-04 ingress" }],
      packets: [
        { frame: 4, time: 1700000000.05, kind: "data", sequence: 1, length: 100, window_edge: null, retransmission: false },
      ],
      buckets: [
        { time: 1700000000.0, throughput_bps: 1600, rtt_ms: 10.0, data_frame: 4, rtt_frame: 4, coverage: "capturing" },
      ],
      reason: null,
      clock_uncertainty_ms: 0.2,
    });
    vi.mocked(api.getPacketCaseLadder).mockResolvedValue({
      total: 1,
      flow_filters: [
        {
          capture_id: "c1",
          file: "synthetic-a.pcap",
          display_filter: "ip.addr == 192.0.2.10 && ip.addr == 198.51.100.20",
        },
      ],
      local_metrics: [
        {
          point: "p1",
          tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
          retransmissions: 1,
          zero_windows: 0,
          resets: 0,
          max_rtt_ms: 15.0,
          handshake: { syn_frame: 1, synack_frame: 2, syn_to_synack_ms: 10.0, synack_to_ack_ms: 1.0 },
        },
      ],
      items: [
        {
          packet_key: "pkt-1",
          ts: 1700000000.05,
          retrans: false,
          seq: 1,
          ack: 1,
          flags: 24,
          length: 100,
          direction: "forward",
          evidence: [SYNTHETIC_REF],
        },
      ],
      events: [{ point_b: "p2", kind: "recovered_loss", ts: 1700000000.05, packet_key: "pkt-1" }],
    });
    vi.mocked(api.getPacketCaseFieldDiff).mockResolvedValue({
      note: "Header comparison across matched points",
      items: [
        {
          location: "p1 → p2",
          tooltip: "TTL decremented",
          packet_key: "pkt-1",
          fields: {
            ttl: { before: 64, after: 63, status: "modified" },
          },
          evidence: [SYNTHETIC_REF],
        },
      ],
    });

    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case investigation workspace");

    fireEvent.click(screen.getByRole("tab", { name: "Flows & Ladder" }));
    const convTable = await screen.findByRole("table", { name: "Conversations table" });
    expect(within(convTable).getByText('["tcp","192.0.2.10",50000,"198.51.100.20",443]')).toBeInTheDocument();

    fireEvent.click(within(convTable).getByText('["tcp","192.0.2.10",50000,"198.51.100.20",443]'));

    const diagCard = await screen.findByLabelText("Flow diagnosis");
    expect(within(diagCard).getByText("forward · network_path_loss")).toBeInTheDocument();

    const waterfallCard = await screen.findByLabelText("Flow waterfall");
    expect(within(waterfallCard).getByText("TCP handshake")).toBeInTheDocument();
    expect(within(waterfallCard).getByText("Client SYN → Server SYN/ACK")).toBeInTheDocument();

    const chartsCard = await screen.findByLabelText("Flow stream charts");
    expect(within(chartsCard).getByText("TCP sequence, RTT and throughput")).toBeInTheDocument();

    const ladderCard = await screen.findByLabelText("Multi-hop packet ladder");
    expect(within(ladderCard).getByText("p1 #4")).toBeInTheDocument();

    fireEvent.click(within(ladderCard).getByRole("button", { name: "Field diff" }));
    const diffCard = await screen.findByLabelText("Matched packet field differences");
    expect(within(diffCard).getByText("modified")).toBeInTheDocument();
  });

  it("renders timeseries heatmap and DNS turnaround views", async () => {
    vi.mocked(api.getPacketCaseState).mockResolvedValue(syntheticWorkspaceState());
    vi.mocked(api.getPacketCaseTimeseries).mockResolvedValue({
      start: 1700000000.0,
      end: 1700000002.0,
      bucket_seconds: 1.0,
      bucket_count: 2,
      metrics: {
        loss_percent: { label: "Supported loss", unit: "%", tooltip: "Loss percentage per bucket" },
      },
      items: [
        {
          segment: "forward:p1:p2",
          direction: "forward",
          bucket: 0,
          start: 1700000000.0,
          end: 1700000001.0,
          coverage: "capturing",
          reason: null,
          loss_percent: 33.3,
        },
      ],
    });
    vi.mocked(api.getPacketCaseDns).mockResolvedValue({
      items: [
        {
          point: "p1",
          frame: 10,
          question: "api.example.invalid",
          resolver: "198.51.100.53",
          status: "answered",
          reason: "NoError response in 4.2 ms",
          tooltip: "Same-point DNS query/response",
          latency_ms: 4.2,
          evidence: [SYNTHETIC_REF],
          corrected: 1700000000.1,
        },
      ],
      total: 1,
      summary: [
        {
          point: "p1",
          resolver: "198.51.100.53",
          queries: 1,
          matchable_queries: 1,
          answered: 1,
          timed_out_queries: 0,
          unknown_queries: 0,
          p50_ms: 4.2,
          p95_ms: 4.2,
          max_ms: 4.2,
        },
      ],
      points: [{ point: "p1", label: "FW-TANGO-04 ingress", kind: "Firewall" }],
      timeout_ms: 2000,
      tooltip: "DNS turnaround",
      reason: null,
    });

    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case investigation workspace");

    fireEvent.click(screen.getByRole("tab", { name: "Timeseries & Quality" }));
    const heatmapCard = await screen.findByLabelText("Timeseries heatmap");
    expect(within(heatmapCard).getByText("Path × time")).toBeInTheDocument();
    expect(within(heatmapCard).getByRole("img", { name: "Path by time heatmap" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "DNS" }));
    const dnsCard = await screen.findByLabelText("DNS analysis");
    expect(within(dnsCard).getByText(/api\.example\.invalid · 198\.51\.100\.53/)).toBeInTheDocument();
  });

  it("renders neXus hop and policy correlation with evidence chips, candidate lists on AMBIGUOUS, and explicit separation of capture vs configured state", async () => {
    const correlation: PacketNexusCorrelation = {
      case_id: CASE,
      correlated_at: "2026-10-16T08:00:00Z",
      inventory_status: "AVAILABLE",
      inventory_reason: "Evaluated against 2 device(s), 3 interface(s) and 2 policy snapshot(s).",
      policy_snapshot_count: 2,
      evidence_separation_note:
        "The capture proves observed frames, addresses, MACs and 5-tuples on the wire; configured inventory and policy state what neXus collected from device configuration and policy snapshots.",
      hops: [
        {
          point_id: "p1",
          point_label: "FW-TANGO-04 ingress",
          capture_id: "c1",
          capture_name: "synthetic-a.pcap",
          side: "ingress",
          status: "MATCH",
          reason: "Matched 1 interface on FW-TANGO-04 (ethernet1/1) via [mac_equality, interface_subnet_equality].",
          evidence: ["mac_equality", "interface_subnet_equality"],
          device_id: "aaaaaaaa-1111-4222-8333-444444444444",
          hostname: "FW-TANGO-04",
          interface_id: "bbbbbbbb-1111-4222-8333-444444444444",
          interface_name: "ethernet1/1",
          zone: "trust",
          mac_address: "00:1b:17:00:01:11",
          interface_addresses: ["192.0.2.1/24", "2001:db8:1::1/64"],
          matched_addresses: ["192.0.2.10"],
          capture_proves: {
            point_id: "p1",
            point_label: "FW-TANGO-04 ingress",
            capture_id: "c1",
            capture_name: "synthetic-a.pcap",
            side: "ingress",
            vendor_interface: "ethernet1/1",
            observed_macs: ["00:1b:17:00:01:11"],
            observed_addresses: ["192.0.2.10", "198.51.100.20"],
          },
          configured_inventory_says: {
            device_id: "aaaaaaaa-1111-4222-8333-444444444444",
            hostname: "FW-TANGO-04",
            interface_name: "ethernet1/1",
            zone: "trust",
          },
          candidates: [],
        },
        {
          point_id: "p2",
          point_label: "FW-TANGO-04 egress",
          capture_id: "c2",
          capture_name: "synthetic-b.pcap",
          side: "egress",
          status: "AMBIGUOUS",
          reason: "2 candidate interfaces matched on [interface_subnet_equality]; refusing to guess between them.",
          evidence: ["interface_subnet_equality"],
          capture_proves: {
            point_id: "p2",
            point_label: "FW-TANGO-04 egress",
            capture_id: "c2",
            capture_name: "synthetic-b.pcap",
            side: "egress",
            vendor_interface: null,
            observed_macs: ["00:1b:17:99:99:99"],
            observed_addresses: ["198.51.100.2", "198.51.100.20"],
          },
          configured_inventory_says: null,
          candidates: [
            {
              device_id: "aaaaaaaa-1111-4222-8333-444444444444",
              hostname: "FW-TANGO-04",
              interface_id: "cccccccc-1111-4222-8333-444444444444",
              interface_name: "ethernet1/2",
              zone: "untrust",
              mac_address: "00:1b:17:00:01:12",
              interface_addresses: ["198.51.100.1/24"],
              evidence: ["interface_subnet_equality"],
              matched_addresses: ["198.51.100.2", "198.51.100.20"],
              reason: "Matched interface FW-TANGO-04 / ethernet1/2",
            },
            {
              device_id: "dddddddd-1111-4222-8333-444444444444",
              hostname: "FW-JULIET-06",
              interface_id: "eeeeeeee-1111-4222-8333-444444444444",
              interface_name: "ethernet1/2",
              zone: "untrust-peer",
              mac_address: "00:1b:17:00:02:12",
              interface_addresses: ["198.51.100.254/24"],
              evidence: ["interface_subnet_equality"],
              matched_addresses: ["198.51.100.2", "198.51.100.20"],
              reason: "Matched interface FW-JULIET-06 / ethernet1/2",
            },
          ],
        },
      ],
      flows: [
        {
          flow: "f1",
          tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
          proto: "tcp",
          src: "198.51.100.2",
          sport: 60000,
          dst: "198.51.100.20",
          dport: 443,
          nat_confirmed: true,
          pre_nat_src: "192.0.2.10",
          pre_nat_sport: 50000,
          pre_nat_dst: "198.51.100.20",
          pre_nat_dport: 443,
          post_nat_src: "198.51.100.2",
          post_nat_sport: 60000,
          post_nat_dst: "198.51.100.20",
          post_nat_dport: 443,
          status: "MATCH",
          reason: "First-match rule #20 (Allow-Web-Post-NAT) in policy Standard on FW-TANGO-04 contains the confirmed post-NAT 5-tuple.",
          evidence: ["rule_5tuple_containment"],
          device_id: "aaaaaaaa-1111-4222-8333-444444444444",
          hostname: "FW-TANGO-04",
          ingress_interface_name: "ethernet1/1",
          source_zone: "trust",
          egress_interface_name: "ethernet1/2",
          destination_zone: "untrust",
          policy_id: "ffffffff-1111-4222-8333-444444444444",
          policy_name: "Standard",
          policy_status: "COMPLETE",
          policy_stale: false,
          policy_incomplete: false,
          rule_id: "r-allow-post-nat",
          rule_number: 20,
          rule_name: "Allow-Web-Post-NAT",
          action: "ALLOW",
          capture_proves: {
            flow: "f1",
            tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
            proto: "tcp",
            pre_nat_src: "192.0.2.10",
            pre_nat_sport: 50000,
            pre_nat_dst: "198.51.100.20",
            pre_nat_dport: 443,
            nat_confirmed: true,
            post_nat_src: "198.51.100.2",
            post_nat_sport: 60000,
            post_nat_dst: "198.51.100.20",
            post_nat_dport: 443,
            observed_point_ids: ["p1", "p2"],
          },
          configured_policy_says: {
            device_id: "aaaaaaaa-1111-4222-8333-444444444444",
            hostname: "FW-TANGO-04",
            policy_name: "Standard",
            rule_number: 20,
            rule_name: "Allow-Web-Post-NAT",
            action: "ALLOW",
          },
          candidates: [],
          firewall_matches: [],
        },
      ],
    };

    vi.mocked(api.getPacketCaseState).mockResolvedValue(syntheticWorkspaceState());
    vi.mocked(api.getPacketCaseCorrelation).mockResolvedValue(correlation);
    vi.mocked(api.getPacketCaseFlows).mockResolvedValue({
      total: 1,
      items: [
        {
          flow: "f1",
          tuple: '["tcp","192.0.2.10",50000,"198.51.100.20",443]',
          start: 1700000000.0,
          end: 1700000000.5,
          bytes: 400,
          points: 2,
          confirmed_device_drop: 0,
          impactful_loss: 0,
          recovered_loss: 1,
          unrecovered_loss: 0,
          handshake_blocked: 0,
          unknown_events: 0,
          capture_miss: 0,
          retrans_observations: 1,
          max_stall_ms: 200.0,
          max_rtt_ms: 15.0,
          has_reset: false,
          handshake_incomplete: false,
        },
      ],
    });

    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case investigation workspace");

    fireEvent.click(screen.getByRole("tab", { name: "Topology" }));
    const corrCard = await screen.findByLabelText("neXus hop & policy correlation");
    expect(within(corrCard).getByText(/The capture proves observed frames/)).toBeInTheDocument();

    const hop1 = within(corrCard).getByLabelText("Hop correlation p1");
    expect(within(hop1).getByText("MATCH")).toBeInTheDocument();
    expect(within(hop1).getByText("mac_equality")).toBeInTheDocument();
    expect(within(hop1).getByText("interface_subnet_equality")).toBeInTheDocument();
    expect(within(within(hop1).getByLabelText("Capture proves p1")).getByText(/00:1b:17:00:01:11/)).toBeInTheDocument();
    expect(within(within(hop1).getByLabelText("Configured inventory says p1")).getByText("ethernet1/1")).toBeInTheDocument();
    expect(within(within(hop1).getByLabelText("Configured inventory says p1")).getByText("trust")).toBeInTheDocument();

    const hop2 = within(corrCard).getByLabelText("Hop correlation p2");
    expect(within(hop2).getByText("AMBIGUOUS")).toBeInTheDocument();
    expect(within(hop2).getByText(/FW-TANGO-04 · ethernet1\/2 · Zone: untrust/)).toBeInTheDocument();
    expect(within(hop2).getByText(/FW-JULIET-06 · ethernet1\/2 · Zone: untrust-peer/)).toBeInTheDocument();

    const flowCorrOnTopo = within(corrCard).getByLabelText("Flow correlation f1");
    expect(within(flowCorrOnTopo).getByText("rule_5tuple_containment")).toBeInTheDocument();
    expect(within(within(flowCorrOnTopo).getByLabelText("Capture proves flow f1")).getByText(/Confirmed post-NAT: 198\.51\.100\.2:60000/)).toBeInTheDocument();
    expect(within(within(flowCorrOnTopo).getByLabelText("Configured policy says flow f1")).getByText(/#20 Allow-Web-Post-NAT/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Flows & Ladder" }));
    const convTable = await screen.findByRole("table", { name: "Conversations table" });
    expect(within(convTable).getByText("neXus: MATCH")).toBeInTheDocument();
    fireEvent.click(within(convTable).getByText('["tcp","192.0.2.10",50000,"198.51.100.20",443]'));

    const flowCorrCard = await screen.findByLabelText("Flow neXus correlation");
    expect(within(within(flowCorrCard).getByLabelText("Selected flow capture proves")).getByText(/Pre-NAT 5-tuple: 192\.0\.2\.10:50000/)).toBeInTheDocument();
    expect(within(within(flowCorrCard).getByLabelText("Selected flow configured policy says")).getByText(/#20 Allow-Web-Post-NAT/)).toBeInTheDocument();
  });
});

describe("uploadPacketCapture", () => {
  it("sends one multipart file with the CSRF token and reports progress", async () => {
    const original = await vi.importActual<typeof import("../src/auth/adminApi")>("../src/auth/adminApi");
    globalThis.fetch = vi.fn().mockResolvedValue({ json: async () => ({ csrf_token: "csrf-synthetic" }) }) as unknown as typeof fetch;
    const headers: Record<string, string> = {};
    let sent: FormData | null = null;
    const fake = {
      upload: {} as { onprogress?: (event: { lengthComputable: boolean; loaded: number; total: number }) => void },
      open: vi.fn(), setRequestHeader: (name: string, value: string) => { headers[name] = value; },
      withCredentials: false, status: 0, responseText: "",
      onload: null as null | (() => void), onerror: null as null | (() => void),
      send(body: FormData) { sent = body; this.upload.onprogress?.({ lengthComputable: true, loaded: 5, total: 10 });
        this.status = 201; this.responseText = '{"capture_id":"c-1","size_bytes":10,"format":"pcap"}'; this.onload?.(); },
    };
    const progress: number[] = [];
    const result = await original.uploadPacketCapture(CASE, new File([new Uint8Array(10)], "a.pcap"),
      (loaded) => progress.push(loaded), () => fake as unknown as XMLHttpRequest);
    expect(result.capture_id).toBe("c-1");
    expect(fake.open).toHaveBeenCalledWith("POST", `/api/v2/packet-analysis/cases/${CASE}/captures`);
    expect(headers["X-CSRF-Token"]).toBe("csrf-synthetic");
    expect(fake.withCredentials).toBe(true);
    expect(progress).toEqual([5]);
    expect((sent as unknown as FormData).get("file")).toBeInstanceOf(File);
  });
});
