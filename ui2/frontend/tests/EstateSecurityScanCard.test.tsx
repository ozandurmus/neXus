import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getSecurityScanSummary, type SecurityScanSummaryView } from "../src/auth/adminApi";
import { EstateSecurityScanCard } from "../src/estate/EstateSecurityScanCard";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getSecurityScanSummary: vi.fn() };
});

const NOW = new Date("2026-11-22T10:00:00Z");
const RULE_TEXT = "tango-rule-unique-7731";
const LOCATION_TEXT = "src/tango/module_42.py";

function populated(overrides: Partial<SecurityScanSummaryView> = {}): SecurityScanSummaryView {
  return {
    available: true,
    mail: { route_enabled: true, smtp_configured: true, recipients_configured: true, delivering: true, last_sent_at: null, last_error: null },
    stale_after_hours: 24,
    generated_at: "2026-11-22T02:30:00Z",
    age_hours: 8,
    stale: false,
    passed: true,
    counts: {
      semgrep: { HIGH: { new: 1, existing: 1, accepted: 0, fixed: 0 }, MEDIUM: { new: 2, existing: 3, accepted: 0, fixed: 0 } },
    },
    findings_listed_in_summary: true,
    findings_total: 1,
    findings: [{ tool: "semgrep", rule: RULE_TEXT, severity: "HIGH", location: LOCATION_TEXT, status: "new", fix_available: false }],
    ...overrides,
  } as unknown as SecurityScanSummaryView;
}

beforeEach(() => {
  vi.mocked(getSecurityScanSummary).mockReset();
});

describe("EstateSecurityScanCard", () => {
  it("shows the loading state while the summary is being read", () => {
    vi.mocked(getSecurityScanSummary).mockReturnValue(new Promise(() => {}));
    render(<EstateSecurityScanCard now={NOW} />);
    expect(screen.getByTestId("estate-scan-loading")).toHaveTextContent("Reading the latest scan summary");
    expect(screen.queryByText("Passed")).toBeNull();
  });

  it("shows a failed read as UNKNOWN, never as clean", async () => {
    vi.mocked(getSecurityScanSummary).mockRejectedValue(new Error("network down"));
    render(<EstateSecurityScanCard now={NOW} />);
    await waitFor(() => expect(screen.getByTestId("estate-scan-headline")).toBeInTheDocument());
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.getByTestId("estate-scan-headline")).toHaveTextContent("could not be read");
    expect(screen.queryByText("Passed")).toBeNull();
    expect(screen.queryByTestId("estate-scan-counts")).toBeNull();
  });

  it("says no scan is recorded, and does not show OK", async () => {
    vi.mocked(getSecurityScanSummary).mockResolvedValue(
      populated({ available: false, reason: "NO_DAILY_SUMMARY", generated_at: undefined }),
    );
    render(<EstateSecurityScanCard now={NOW} />);
    expect(await screen.findByText("No scan")).toBeInTheDocument();
    expect(screen.getByTestId("estate-scan-headline")).toHaveTextContent("No daily scan has been recorded yet");
    expect(screen.queryByText("Passed")).toBeNull();
    expect(screen.queryByTestId("estate-scan-counts")).toBeNull();
  });

  it("renders the populated card: evidence stamp, named severity counts, no finding text", async () => {
    vi.mocked(getSecurityScanSummary).mockResolvedValue(populated());
    const { container } = render(<EstateSecurityScanCard now={NOW} />);
    const counts = await screen.findByTestId("estate-scan-counts");
    expect(within(counts).getByText("HIGH 2 · MEDIUM 5")).toBeInTheDocument();
    expect(counts).toHaveTextContent("accepted and fixed not counted");
    expect(screen.getByText("Passed")).toBeInTheDocument();
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(screen.getByTestId("estate-scan-date")).toHaveTextContent("Scan finished");
    expect(container.textContent ?? "").not.toContain(RULE_TEXT);
    expect(container.textContent ?? "").not.toContain(LOCATION_TEXT);
  });

  it("links to the standard view with standard=1 and renders no upload or delete control", async () => {
    vi.mocked(getSecurityScanSummary).mockResolvedValue(populated());
    render(<EstateSecurityScanCard now={NOW} />);
    await screen.findByTestId("estate-scan-counts");
    const link = screen.getByRole("link", { name: "Open platform status (standard view)" });
    expect(link.getAttribute("href")).toContain("standard=1");
    expect(screen.queryByRole("button", { name: /upload|delete/i })).toBeNull();
  });
});
