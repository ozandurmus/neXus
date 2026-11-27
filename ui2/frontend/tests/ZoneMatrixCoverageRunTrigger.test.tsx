import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CoverageTab } from "../src/screens/zonematrix/CoverageTab";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { startZoneMatrixRun, type SummaryHeadline, type SummaryPolicy, type ZoneMatrixCan, type ZoneMatrixSummaryResponse } from "../src/screens/zonematrix/zoneMatrixApi";
import type { ZoneSummaryHandle } from "../src/screens/zonematrix/zoneMatrixSummaryUi";

/** Zone matrix step 4b-2: Coverage tab on-demand durable run trigger (`POST /api/v2/zone-matrix/runs`). Synthetic values only. */
const HASH = "a".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN_EVALUATE: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false, evaluate: true };
const CAN_READ_ONLY: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false, evaluate: false };

const activeSummary = {
  version_id: "v-active",
  number: 3,
  state: "ACTIVE" as const,
  revision: 4,
  content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z",
  updated_at: "2026-11-12T08:00:00Z",
  published_by: "USER-2",
  published_at: "2026-11-12T08:00:00Z",
};
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const makeRun = (over: Record<string, unknown> = {}) => ({
  run_id: "run-1",
  state: "COMPLETE",
  failure_reason: null,
  version_id: "v-active",
  version_number: 3,
  content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z",
  finished_at: "2026-11-25T07:05:00Z",
  snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE",
  counts: null,
  ...over,
});

const makePolicy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-cp-1",
  policy_name: "POLICY-ALFA",
  vendor: "check_point",
  source_id: "src-1",
  source_name: "FW-TANGO-04",
  container_id: null,
  container_name: null,
  collected_at: "2026-11-25T06:00:00Z",
  rule_count: 12,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true },
  coverage: "COMPLETE",
  coverage_items: [],
  collection_failures: 0,
  has_complete_run: true,
  latest_complete_run: makeRun(),
  latest_run: makeRun(),
  ...over,
});

const makeHeadline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active",
  version_number: 3,
  distinct_violating_rules: 1,
  violated_zone_pairs: 1,
  critical_rules: 0,
  covered_by_earlier_rule: 0,
  conditional_rules: 0,
  outside_headline_ordered_layer: 0,
  not_evaluable_findings: 0,
  unknown_findings: 0,
  became_undecided_findings: 0,
  stale_policies: 0,
  incomplete_policies: 0,
  older_version_policies: 0,
  not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0,
  exact: true,
  count_text: "1 violating rule in 1 policy",
  text: "1 rule violates the zone matrix",
  outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" },
  ...over,
});

const makeSummary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  can: CAN_EVALUATE,
  evaluated_at: "2026-11-25T08:00:00Z",
  state: "READY",
  state_text: "findings of the latest complete run of each policy",
  masked: false,
  policy_names_masked: false,
  witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: makeHeadline(),
  headline_absent_reason: null,
  policies: [makePolicy()],
  not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 },
  unfiltered_findings: 0,
  total_findings: 0,
  returned: 0,
  more: 0,
  capped: false,
  findings: [],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  ...over,
});

function makeHandle(data: ZoneMatrixSummaryResponse): ZoneSummaryHandle & { readonly reloadMock: ReturnType<typeof vi.fn> } {
  const reloadMock = vi.fn();
  return {
    data,
    error: null,
    rawError: null,
    loading: false,
    params: {},
    setParams: vi.fn(),
    ensure: vi.fn(),
    reload: reloadMock,
    reloadMock,
  };
}

const jsonReply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix Coverage tab: on-demand durable run trigger", () => {
  it("starts a durable run via POST /api/v2/zone-matrix/runs, disables while in flight, shows a neutral info notice naming run_id, and reloads summary", async () => {
    const sentBodies: unknown[] = [];
    let finishRun!: (response: Response) => void;
    const runPromise = new Promise<Response>((resolve) => { finishRun = resolve; });

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/session/status") return jsonReply(200, { csrf_token: "synthetic-token" });
      if (url === "/api/v2/zone-matrix/runs" && init?.method === "POST") {
        sentBodies.push(JSON.parse(String(init.body)));
        return runPromise;
      }
      return jsonReply(404, { error: "NOT_FOUND", reason: "unexpected route" });
    }));

    const handle = makeHandle(makeSummary({
      policies: [
        makePolicy({ policy_id: "pol-cp-1", policy_name: "POLICY-ALFA", source_name: "FW-TANGO-04" }),
        makePolicy({ policy_id: "pol-pan-2", policy_name: "POLICY-BRAVO", vendor: "palo_alto", source_name: "FW-JULIET-06" }),
      ],
    }));

    render(<CoverageTab summary={handle} />);

    const btnCp = screen.getByTestId("run-policy-pol-cp-1");
    const btnPan = screen.getByTestId("run-policy-pol-pan-2");
    expect(btnCp).toBeEnabled();
    expect(btnCp).toHaveTextContent("Run evaluation");
    expect(btnPan).toBeEnabled();
    expect(btnPan).toHaveTextContent("Run evaluation");

    fireEvent.click(btnCp);

    await waitFor(() => expect(sentBodies).toEqual([{ policy_id: "pol-cp-1" }]));
    expect(screen.getByTestId("run-policy-pol-cp-1")).toBeDisabled();
    expect(screen.getByTestId("run-policy-pol-cp-1")).toHaveTextContent("Running…");
    expect(screen.getByTestId("run-policy-pol-pan-2")).toBeEnabled();

    finishRun(jsonReply(202, { run_id: "run-42", already_running: false, state: "RUNNING" }));

    const notice = await screen.findByTestId("zone-run-submission-notice");
    expect(notice).toHaveTextContent(
      "Run run-42 queued (RUNNING). Stored findings update only after the run finishes COMPLETE.",
    );
    expect(notice.className).toMatch(/Info/);
    expect(notice.className).not.toMatch(/Success/);
    expect(notice.textContent ?? "").not.toMatch(/complian|\bpass\b|bddk|score|\b0\b/i);
    expect(handle.reloadMock).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByTestId("run-policy-pol-cp-1")).toBeEnabled());
  });

  it("renders the 'already running' info notice when already_running is true and reloads summary", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/session/status") return jsonReply(200, { csrf_token: "synthetic-token" });
      if (url === "/api/v2/zone-matrix/runs" && init?.method === "POST") {
        return jsonReply(202, { run_id: "run-99", already_running: true, state: "RUNNING" });
      }
      return jsonReply(404, { error: "NOT_FOUND", reason: "unexpected route" });
    }));

    const handle = makeHandle(makeSummary());
    render(<CoverageTab summary={handle} />);

    fireEvent.click(screen.getByTestId("run-policy-pol-cp-1"));

    const notice = await screen.findByTestId("zone-run-submission-notice");
    expect(notice).toHaveTextContent(
      "Run run-99 is already running (RUNNING). Stored findings update only after the run finishes COMPLETE.",
    );
    expect(notice.className).toMatch(/Info/);
    expect(notice.className).not.toMatch(/Success/);
    expect(handle.reloadMock).toHaveBeenCalledTimes(1);
  });

  it("renders the button disabled with label 'Running…' when latest_run.state === 'RUNNING'", () => {
    const handle = makeHandle(makeSummary({
      policies: [
        makePolicy({
          policy_id: "pol-cp-1",
          latest_run: makeRun({ run_id: "run-77", state: "RUNNING", finished_at: null }),
        }),
      ],
    }));

    render(<CoverageTab summary={handle} />);

    const table = screen.getByRole("table", { name: "Policy coverage" });
    const btn = within(table).getByTestId("run-policy-pol-cp-1");
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent("Running…");
  });

  it("renders zone-run-submission-error with zoneErrorLines text on server refusal (503 POOL_SATURATED and 403 READ_ONLY_PERSONA)", async () => {
    let refusal = { status: 503, body: { error: "POOL_SATURATED", reason: "zone matrix evaluation pool is full; try again shortly" } };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/session/status") return jsonReply(200, { csrf_token: "synthetic-token" });
      if (url === "/api/v2/zone-matrix/runs" && init?.method === "POST") {
        return jsonReply(refusal.status, refusal.body);
      }
      return jsonReply(404, { error: "NOT_FOUND", reason: "unexpected route" });
    }));

    const handle = makeHandle(makeSummary());
    render(<CoverageTab summary={handle} />);

    fireEvent.click(screen.getByTestId("run-policy-pol-cp-1"));

    const errAlert = await screen.findByTestId("zone-run-submission-error");
    expect(errAlert).toHaveTextContent("Zone matrix evaluation pool is full; try again shortly.");
    expect(screen.queryByTestId("zone-run-submission-notice")).not.toBeInTheDocument();
    expect(handle.reloadMock).not.toHaveBeenCalled();

    refusal = { status: 403, body: { error: "READ_ONLY_PERSONA", reason: "the masked persona cannot start a zone matrix run" } };
    fireEvent.click(screen.getByTestId("run-policy-pol-cp-1"));

    await waitFor(() =>
      expect(screen.getByTestId("zone-run-submission-error")).toHaveTextContent(
        "The masked persona cannot start a zone matrix run.",
      ));
  });

  it.each([
    { label: "masked: true", override: { can: CAN_EVALUATE, masked: true } },
    { label: "can.evaluate: false", override: { can: CAN_READ_ONLY, masked: false } },
    { label: "can omitted", override: { can: undefined, masked: false } },
    { label: "active_version: null", override: { can: CAN_EVALUATE, masked: false, active_version: null, state: "NO_ACTIVE_VERSION", headline: null } },
  ])("renders zero run-policy-* buttons when $label", ({ override }) => {
    const handle = makeHandle(makeSummary(override));
    render(<CoverageTab summary={handle} />);

    expect(screen.queryAllByTestId(/^run-policy-/)).toHaveLength(0);
    expect(screen.queryByRole("button", { name: /Run evaluation|Running…/ })).not.toBeInTheDocument();
  });

  it("re-reads GET /api/v2/zone-matrix/summary when triggered inside ZoneMatrixScreen and forwards optional version_id in startZoneMatrixRun", async () => {
    let summaryReads = 0;
    const runBodies: unknown[] = [];

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const path = url.split("?")[0];
      if (url === "/session/status") return jsonReply(200, { csrf_token: "synthetic-token" });
      if (path === "/api/v2/zone-matrix/summary") {
        summaryReads += 1;
        return jsonReply(200, makeSummary());
      }
      if (path === "/api/v2/zone-matrix/runs" && init?.method === "POST") {
        runBodies.push(JSON.parse(String(init.body)));
        return jsonReply(202, { run_id: "run-55", already_running: false, state: "RUNNING" });
      }
      if (path === "/api/v2/zone-matrix/draft") {
        return jsonReply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: CAN_EVALUATE });
      }
      if (path === "/api/v2/zone-matrix/versions/v-active") return jsonReply(200, { ...activeDetail, can: CAN_EVALUATE });
      if (path === "/api/v2/zone-matrix/versions") return jsonReply(200, { versions: [activeSummary], can: CAN_EVALUATE });
      return jsonReply(404, { error: "NOT_FOUND", reason: "unexpected route" });
    }));

    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Coverage" }));
    const btn = await screen.findByTestId("run-policy-pol-cp-1");
    expect(summaryReads).toBe(1);

    fireEvent.click(btn);
    await screen.findByTestId("zone-run-submission-notice");
    await waitFor(() => expect(summaryReads).toBe(2));

    await startZoneMatrixRun("pol-cp-1", "v-active");
    expect(runBodies).toEqual([
      { policy_id: "pol-cp-1" },
      { policy_id: "pol-cp-1", version_id: "v-active" },
    ]);
  });
});
