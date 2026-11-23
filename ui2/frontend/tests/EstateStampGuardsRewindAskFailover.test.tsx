// Estate Rewind, Ask and Failover: evidence-stamp and UNKNOWN-never-OK guards.
// Test-only lane. Mirrors the structure of tests/EstateScreenStampGuards.test.tsx for the three screens that
// no stamp guard covered yet. Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x, 198.51.100.x).
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import type { CpFailoverSummary, EstateAsOfView } from "../src/auth/adminApi";
import { EstateAskScreen } from "../src/estate/EstateAskScreen";
import { EstateFailoverScreen } from "../src/estate/EstateFailoverScreen";
import { EstateRewindScreen } from "../src/estate/EstateRewindScreen";
import { useEstateData } from "../src/estate/useEstateData";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getEstateAsOf: vi.fn(),
    queryPath: vi.fn(),
    listCutoverSegments: vi.fn(),
    listPolicies: vi.fn(),
    listOfflineAnalyses: vi.fn(),
    listFailoverIncidents: vi.fn(),
  };
});

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: vi.fn(),
}));

// Count-free UNKNOWN guard: an UNKNOWN mark must never sit in a row that also reads OK or Fine.
const OK_WORD_RE = /^(OK|Fine)$/;

// ---------------------------------------------------------------------------------------------------------------
// A. Rewind
// ---------------------------------------------------------------------------------------------------------------

const LONG_AGO = new Date(Date.now() - 400 * 86_400_000).toISOString();

/** A moment before any stored history: every unit reads UNKNOWN with no evidence time. */
function beforeHistoryView(at: string): EstateAsOfView {
  const unit = (id: string, name: string) => ({
    device_id: id,
    hostname: name,
    failover: { verdict: "UNKNOWN", evidence_at: null, reason: "NO_RUN_AT_MOMENT" },
    backup: { verdict: "UNKNOWN", evidence_at: null },
    compliance: { state: "UNKNOWN", pass_count: 0, fail_count: 0, critical_fail_count: 0, evidence_at: null },
  });
  return {
    as_of: at,
    generated_at: at,
    warnings: [],
    units: [unit("dev-tango-04", "FW-TANGO-04"), unit("dev-sierra-02", "FW-SIERRA-02")],
  } as unknown as EstateAsOfView;
}

describe("Estate Rewind stamp guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminApi.getEstateAsOf).mockImplementation(async (at: string) => beforeHistoryView(at));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("A: before history, figures read UNKNOWN and never a last-known OK", async () => {
    const { container } = render(<EstateRewindScreen initialAt={LONG_AGO} initialCompare={null} />);
    const counts = await screen.findByTestId("rewind-counts");

    expect(counts).toHaveTextContent("2 UNKNOWN");
    expect(within(counts).getByText("0 fine")).toBeInTheDocument();
    // Every unit tile reads UNKNOWN: no tile in the wall carries an OK label.
    const wall = screen.getByTestId("rewind-wall");
    expect(wall.querySelectorAll("[aria-label]").length).toBeGreaterThan(0);
    wall.querySelectorAll("[aria-label]").forEach((tile) => {
      expect(tile.getAttribute("aria-label") ?? "").toMatch(/UNKNOWN/);
    });
    expect(container.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(screen.queryByText(OK_WORD_RE)).toBeNull();
  });

  it("A: every count figure carries an evidence stamp", async () => {
    render(<EstateRewindScreen initialAt={LONG_AGO} initialCompare={null} />);
    const counts = await screen.findByTestId("rewind-counts");

    // Two stamps: the newest and the oldest evidence of the moment, each read as UNKNOWN before history.
    const stamps = counts.querySelectorAll("[data-evidence-stamp]");
    expect(stamps.length).toBeGreaterThanOrEqual(2);
    expect(counts.textContent ?? "").toMatch(/UNKNOWN/);
  });

  it("A: the moment and timeline figures each carry a stamp", async () => {
    render(<EstateRewindScreen initialAt={LONG_AGO} initialCompare={null} />);
    await screen.findByTestId("rewind-counts");

    const slider = screen.getByTestId("rewind-slider");
    const timeline = slider.parentElement as HTMLElement;
    expect(timeline.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });

  it("A: a failed read is shown as UNKNOWN with a stamp, not as OK", async () => {
    vi.mocked(adminApi.getEstateAsOf).mockRejectedValue(new Error("synthetic"));
    render(<EstateRewindScreen initialAt={LONG_AGO} initialCompare={null} />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("UNKNOWN");
    expect(alert.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(document.querySelector('[data-state-badge="ok"]')).toBeNull();
  });
});

// ---------------------------------------------------------------------------------------------------------------
// B. Ask
// ---------------------------------------------------------------------------------------------------------------

const RECENT = new Date(Date.now() - 2 * 3_600_000).toISOString();

/** A path verdict the stored evidence cannot decide: UNKNOWN, with one hop whose route is UNKNOWN. */
function unknownPathResult() {
  const hop = {
    hop_index: 0,
    node_id: "FW-TANGO-04",
    vendor: "check_point",
    context: null,
    verdict: "UNKNOWN",
    policy_collected_at: RECENT,
    policy_stale: false,
    reasons: [],
    route: null,
    nat: null,
    security: null,
    ingress: null,
    egress: null,
  };
  return {
    status: "COMPUTED",
    verdict: "UNKNOWN",
    verdict_reason: "Stored evidence does not decide this flow",
    computed_at: RECENT,
    asymmetric: false,
    limitations: null,
    executive_summary: { headline: "Not provable from stored evidence", unknown_hops: ["FW-TANGO-04"] },
    branches: [{ verdict: "UNKNOWN", hops: [hop] }],
  } as never;
}

describe("Estate Ask stamp guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminApi.queryPath).mockResolvedValue(unknownPathResult());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function askPath() {
    render(<EstateAskScreen initialMode="path" />);
    fireEvent.change(screen.getByTestId("ask-input"), { target: { value: "192.0.2.10 → 198.51.100.40 tcp/8443" } });
    fireEvent.click(screen.getByTestId("ask-submit"));
    return screen.findByTestId("ask-answer");
  }

  it("B: the verdict carries an evidence stamp", async () => {
    const answer = await askPath();
    const header = answer.querySelector("[data-evidence-stamp]");
    expect(header, "verdict header shows a verdict without an EvidenceStamp").not.toBeNull();
  });

  it("B: each hop card carries an evidence stamp", async () => {
    await askPath();
    const hops = document.querySelectorAll("[data-ask-hop]");
    expect(hops.length).toBeGreaterThan(0);
    hops.forEach((hop) => {
      expect(hop.querySelector("[data-evidence-stamp]"), `hop ${hop.getAttribute("data-ask-hop")} has no EvidenceStamp`).not.toBeNull();
    });
  });

  it("B: an UNKNOWN reachability verdict never renders the word OK", async () => {
    const answer = await askPath();
    expect(answer.getAttribute("data-ask-state")).toBe("unk");
    expect(answer.getAttribute("data-ask-unprovable")).toBe("true");
    expect(within(answer).getByTestId("ask-cannot-prove")).toBeInTheDocument();
    expect(within(answer).queryByText(OK_WORD_RE)).toBeNull();
    document.querySelectorAll("[data-ask-hop]").forEach((hop) => {
      expect(hop.getAttribute("data-ask-hop-state")).not.toBe("ok");
      expect(within(hop as HTMLElement).queryByText(OK_WORD_RE)).toBeNull();
    });
  });
});

// ---------------------------------------------------------------------------------------------------------------
// C. Failover
// ---------------------------------------------------------------------------------------------------------------

// Synthetic board: one pair with a readiness read, one pair never evaluated (every cell UNKNOWN).
const FAILOVER_ROWS: readonly CpFailoverSummary[] = [
  {
    unitId: "fw-tango-04",
    clusterId: "fw-tango-04",
    cluster_member_ref: "FW-TANGO-04",
    vendor: "check_point",
    unitKind: "VSX_CHASSIS",
    failoverRefusal: "VSX_CHASSIS_NOT_FAILOVER_UNIT",
    activeWindow: true,
    lastRunState: "DONE",
    lastRunOutcome: "SUCCEEDED",
    lastRunAt: RECENT,
    canRunReadiness: true,
    mode: "LOAD_SHARING",
    readiness: {
      status: "READY",
      observedAt: RECENT,
      failedCheck: "",
      checks: [
        { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
      ],
    },
  },
  {
    unitId: "fw-bravo-02",
    clusterId: "fw-bravo-02",
    cluster_member_ref: "FW-BRAVO-02",
    vendor: "palo_alto",
    activeWindow: true,
    lastRunState: null,
    lastRunOutcome: null,
    lastRunAt: null,
    canRunReadiness: true,
    readiness: null,
  },
] as never;

describe("Estate Failover stamp guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminApi.listFailoverIncidents).mockResolvedValue({ canRelease: false, roleNote: null, incidents: [] });
    vi.mocked(useEstateData).mockReturnValue({
      loading: false,
      fatalError: null,
      devices: { state: "ok", value: [] },
      failover: { state: "ok", value: FAILOVER_ROWS },
      model: null,
      refresh: () => undefined,
    } as never);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("C: each pair board card carries an evidence stamp", async () => {
    const { container } = render(<EstateFailoverScreen />);
    await waitFor(() => expect(screen.getByTestId("failover-board-columns")).toBeInTheDocument());

    const board = screen.getByTestId("failover-board-columns");
    expect(board.querySelectorAll("[data-evidence-stamp]").length).toBeGreaterThan(0);
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });

  it("C: a pair with no evidence renders UNKNOWN or UNSUPPORTED text, never a green OK state", async () => {
    render(<EstateFailoverScreen />);
    await waitFor(() => expect(screen.getByTestId("failover-board-columns")).toBeInTheDocument());

    const never = screen.getAllByRole("button", { name: /^FW-BRAVO-02: UNKNOWN$/ })[0];
    const cell = never.closest("td") as HTMLElement;
    expect(cell).toHaveAttribute("data-check-state", "unk");
    expect(cell.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(within(cell).queryByText(OK_WORD_RE)).toBeNull();
  });

  it("C: a pre-check matrix cell carries a stamp or a stamped evidence time", async () => {
    render(<EstateFailoverScreen />);
    await waitFor(() => expect(screen.getByTestId("failover-board-columns")).toBeInTheDocument());

    const cells = Array.from(document.querySelectorAll("td[data-check-state]"));
    expect(cells.length).toBeGreaterThan(0);
    const unknownCells = cells.filter((td) => td.getAttribute("data-check-state") === "unk");
    expect(unknownCells.length).toBeGreaterThan(0);
    // The cell shows the "?" glyph; its accessible name carries the word UNKNOWN (legend: "UNKNOWN (?)").
    for (const td of unknownCells) {
      const named = td.querySelector('[role="button"]')?.getAttribute("aria-label") ?? "";
      expect(named, "UNKNOWN pre-check cell without the word in its name").toMatch(/UNKNOWN/);
    }
  });
});
