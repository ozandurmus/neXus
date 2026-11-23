import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CutoverAssessmentPage } from "../src/screens/cutover/CutoverAssessmentPage";
import { FORBIDDEN_PATTERN } from "../src/screens/cutover/cutoverWording";
import { getCutoverAssessment, getCutoverFlows, getCutoverSegment, listDevices, type CutoverAssessmentView } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  getCutoverAssessment: vi.fn(), getCutoverFlows: vi.fn(), getCutoverSegment: vi.fn(), deleteCutoverAssessment: vi.fn(), listDevices: vi.fn(),
}));

/** Q7 retention (PO decision record 2026-11-14): an assessment whose flow rows expired says so, never "no flows". Synthetic data only. */
const SEGMENT_ID = "11111111-1111-4111-8111-111111111111";
const ASSESSMENT_ID = "33333333-3333-4333-8333-333333333333";
const view: CutoverAssessmentView = {
  assessmentId: ASSESSMENT_ID, segmentId: SEGMENT_ID, segmentRevision: 1, createdAt: "2026-08-01T10:00:00Z", actor: "ACTOR-ALPHA",
  targetSource: "PATH", topologySnapshotId: "topo-1", status: "COMPLETE", readiness: "BLOCKERS_FOUND", engineVersion: "cutover-1",
  headline: "2 of 5 evaluated flows would be dropped by the target policy.",
  counts: { tuples: 5, flows: 90, WOULD_DROP: 2, NOT_DECIDED: 1, NOT_EVALUABLE: 0, WOULD_ALLOW: 2, alreadyDroppedTuples: 0, missingRuleGroups: 1 },
  coverage: [{ code: "LOG_PERIOD", periodStart: "2026-07-01T00:00:00Z", periodEnd: "2026-07-08T00:00:00Z" }], uploads: [], stale: false, staleReasons: [],
};
const EXPIRED = "Flow rows were removed after 90 days (retention); the verdict and counts above are kept.";

beforeEach(() => {
  vi.mocked(getCutoverAssessment).mockResolvedValue(view);
  vi.mocked(getCutoverSegment).mockResolvedValue({ segmentId: SEGMENT_ID, name: "SEG-ALPHA", revision: 1, blocks: ["192.0.2.0/24"], peerBlocks: ["198.51.100.0/24"], assessments: [], canManage: false } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [{ device_id: "d-cp", hostname: "FW-TANGO-04", vendor_hint: "check_point" }] } as never);
});
afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("assessment page: flow retention (Q7)", () => {
  it("says the flow rows expired instead of showing an empty table, and keeps the verdict and counts", async () => {
    vi.mocked(getCutoverFlows).mockResolvedValue({ total: 0, page: 0, items: [], flowsExpired: true, retentionDays: 90 } as never);
    const { container } = render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    const notice = await screen.findByText(/Flow rows were removed after/);
    expect(notice.closest("[data-fact='flows-expired']")!.textContent).toBe(EXPIRED);
    expect(container.querySelector("[data-fact='no-rows']")).toBeNull();
    expect(container.querySelector("table[aria-label='Flows']")).toBeNull();
    expect(screen.getByText("2 of 5 evaluated flows would be dropped by the target policy.")).toBeInTheDocument();
    expect(container.querySelector("[data-fact='headline']")!.textContent).not.toBe("UNKNOWN");
    expect(document.body.textContent ?? "").not.toMatch(FORBIDDEN_PATTERN);
  });

  it("reads the retention period from the server and never invents one", async () => {
    vi.mocked(getCutoverFlows).mockResolvedValue({ total: 0, page: 0, items: [], flowsExpired: true } as never);
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    expect((await screen.findByText(/Flow rows were removed after/)).textContent).toContain("after UNKNOWN days");
  });

  it("a young assessment with no row in a tab keeps the plain empty answer", async () => {
    vi.mocked(getCutoverFlows).mockResolvedValue({ total: 0, page: 0, items: [] } as never);
    const { container } = render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    await waitFor(() => expect(container.querySelector("[data-fact='no-rows']")).not.toBeNull());
    expect(container.querySelector("[data-fact='flows-expired']")).toBeNull();
  });
});
