import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import type { OfflineAnalysisResult, OfflineAnalysisSummary } from "../src/auth/adminApi";

const listOfflineAnalyses = vi.fn();
const getOfflineAnalysis = vi.fn();
// Only the read clients are mocked; upload/delete are deliberately not exported to this component's test surface.
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    listOfflineAnalyses: (...args: unknown[]) => listOfflineAnalyses(...args),
    getOfflineAnalysis: (...args: unknown[]) => getOfflineAnalysis(...args),
  };
});

import { EstateOfflineAnalyses } from "../src/estate/EstateOfflineAnalyses";
import { OFFLINE_RULE_CAP } from "../src/estate/estateOfflineModel";

// Synthetic values only (FW-TANGO-04 style names, documentation addresses).
const NOW = new Date("2026-11-22T10:00:00Z");
const SUMMARY: OfflineAnalysisSummary = {
  id: "oa-1", policyId: "pol-tango-04", fileName: "siem-tango-04.csv", uploader: "operator-syn", createdAt: "2026-11-22T09:00:00Z",
  rowCount: 120, periodStart: "", periodEnd: "", mode: "WHOLE_POLICY",
};
const SUMMARY_TWO: OfflineAnalysisSummary = { ...SUMMARY, id: "oa-2", fileName: "siem-tango-04-week.csv", mode: "ONE_RULE", ruleId: "r-1" };

function result(overrides: Partial<OfflineAnalysisResult> = {}): OfflineAnalysisResult {
  return {
    ...SUMMARY, collectedAt: "2026-11-22T08:00:00Z", flowCount: 80, attributedFlows: 70, invalidRows: 2,
    agreementCount: 0, disagreementCount: 0, comparisonUnknownCount: 0, comparisonEnabled: false, comparisonNote: "",
    attributionNote: "", limitations: "", caution: "", parseStats: [], unknownStats: [], verdictStats: [],
    rules: [
      { ruleId: "a", name: "allow-web", number: 1, action: "allow", sectionName: "SEC-SYN", flowCount: 0, firstTime: "", lastTime: "",
        distinctSources: 0, distinctDestinations: 0, distinctServices: 0, topObserved: [], zeroTraffic: "", severity: "",
        memberStatus: "OK", unseenMembers: [], narrowing: {} },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  listOfflineAnalyses.mockReset();
  getOfflineAnalysis.mockReset();
});

describe("EstateOfflineAnalyses", () => {
  it("shows a loading state while the list is read", () => {
    listOfflineAnalyses.mockReturnValue(new Promise(() => {}));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading stored offline analyses");
  });

  it("shows UNKNOWN, not 'no analyses', when the list read fails", async () => {
    listOfflineAnalyses.mockRejectedValue(new Error("network"));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    expect(await screen.findByText(/Offline analyses UNKNOWN/)).toBeInTheDocument();
    expect(screen.queryByText(/No stored offline analysis/)).toBeNull();
  });

  it("shows the empty state only after a successful, empty read", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [], canManage: false });
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    expect(await screen.findByText("No stored offline analysis for this policy.")).toBeInTheDocument();
    expect(screen.queryByText(/UNKNOWN/)).toBeNull();
  });

  it("lists analyses with one evidence stamp per row and requests page 0 of the policy", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY, SUMMARY_TWO], canManage: true });
    const { container } = render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    expect(await screen.findByRole("button", { name: "Open siem-tango-04.csv" })).toBeInTheDocument();
    expect(listOfflineAnalyses).toHaveBeenCalledWith("pol-tango-04", 0);
    expect(container.querySelectorAll("[data-evidence-stamp]")).toHaveLength(2);
    expect(screen.getAllByText("Rules: UNKNOWN until opened")).toHaveLength(2);
  });

  it("renders no upload or delete control, and never calls a write client", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: true });
    getOfflineAnalysis.mockResolvedValue(result());
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    await screen.findByText(/Rules in this analysis: 1/);
    expect(screen.queryByRole("button", { name: /upload|delete|choose|remove/i })).toBeNull();
    expect(screen.queryByLabelText(/SIEM CSV file/i)).toBeNull();
  });

  it("shows summary counts for the selected analysis, each named for what it counts", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: false });
    getOfflineAnalysis.mockResolvedValue(result());
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    expect(getOfflineAnalysis).toHaveBeenCalledWith("oa-1");
    expect(await screen.findByText(/Zero-traffic rules \(zero attributed flows in this sample; not proof of an unused rule\): 1/)).toBeInTheDocument();
    expect(screen.getByText(/120 rows · 2 invalid rows · 80 valid sample flows · 70 attributed flows/)).toBeInTheDocument();
  });

  it("shows UNKNOWN for a selected analysis that cannot be read, never a zero summary", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: false });
    getOfflineAnalysis.mockRejectedValue(new Error("boom"));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    expect(await screen.findByText(/Analysis summary UNKNOWN/)).toBeInTheDocument();
    expect(screen.queryByText(/Rules in this analysis/)).toBeNull();
  });

  it("links out to the standard view with standard=1, not to a new route", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [], canManage: false });
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    await screen.findByText("No stored offline analysis for this policy.");
    const link = screen.getByRole("link", { name: "Open offline analyses in standard view" });
    expect(link.getAttribute("href")).toContain("standard=1");
  });

  it("renders period, SIEM comparison, parse/UNKNOWN stats and per-rule badges; UNKNOWN members are never OK", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: false });
    getOfflineAnalysis.mockResolvedValue(result({
      periodStart: "2026-11-01", periodEnd: "2026-11-07", comparisonEnabled: true, agreementCount: 3, disagreementCount: 1, comparisonUnknownCount: 2,
      parseStats: [{ statReason: "BAD_TIME", count: 1 }], unknownStats: [{ statReason: "NO_RULE", count: 4 }], verdictStats: [{ statReason: "ALLOW", count: 9 }],
      rules: [
        { ...result().rules[0], ruleId: "u", number: 2, name: "deny-tango", memberStatus: "UNKNOWN", lookupReason: "lookup timed out" },
        { ...result().rules[0], ruleId: "w", number: 3, name: "allow-sierra", unseenMembers: [{ memberId: "m1", name: "OBJ-SYN-1", dimension: "source" }] },
      ],
    }));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    expect(await screen.findByText("Period 2026-11-01 – 2026-11-07")).toBeInTheDocument();
    expect(screen.getByText("SIEM rule agreement: 3 · Disagreement: 1 · UNKNOWN: 2")).toBeInTheDocument();
    expect(screen.getByText("BAD_TIME: 1")).toBeInTheDocument();
    expect(screen.getByText("NO_RULE: 4")).toBeInTheDocument();
    expect(screen.getByText("ALLOW: 9")).toBeInTheDocument();
    expect(screen.getByText("#2 · deny-tango")).toBeInTheDocument();
    expect(screen.getByText("UNKNOWN: lookup timed out")).toBeInTheDocument();
    expect(screen.getByText("1 unseen member")).toBeInTheDocument();
    expect(screen.getByText("Members UNKNOWN")).toBeInTheDocument();
    expect(screen.queryByText("Members seen")).toBeNull();
  });

  it("omits the SIEM comparison line when comparison was not enabled", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: false });
    getOfflineAnalysis.mockResolvedValue(result({ comparisonEnabled: false }));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    await screen.findByText(/Rules in this analysis: 1/);
    expect(screen.queryByText(/SIEM rule agreement/)).toBeNull();
  });

  it("caps rendered rules at OFFLINE_RULE_CAP and prints the true remainder", async () => {
    listOfflineAnalyses.mockResolvedValue({ analyses: [SUMMARY], canManage: false });
    const many = Array.from({ length: OFFLINE_RULE_CAP + 5 }, (_, i) => ({ ...result().rules[0], ruleId: `r-${i}`, number: i + 1, name: `rule-syn-${i + 1}` }));
    getOfflineAnalysis.mockResolvedValue(result({ rules: many }));
    render(<EstateOfflineAnalyses policy="pol-tango-04" now={NOW} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open siem-tango-04.csv" }));
    expect(await screen.findByTestId("offline-rules-more")).toHaveTextContent("+5 more rules");
    expect(screen.getByText(`#${OFFLINE_RULE_CAP} · rule-syn-${OFFLINE_RULE_CAP}`)).toBeInTheDocument();
    expect(screen.queryByText(`#${OFFLINE_RULE_CAP + 1} · rule-syn-${OFFLINE_RULE_CAP + 1}`)).toBeNull();
  });
});
