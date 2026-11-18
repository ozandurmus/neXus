import { render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { SecurityScanCard, mailWarning } from "../src/screens/SecurityScanCard";
import * as api from "../src/auth/adminApi";
import type { SecurityScanSummaryView } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", () => ({ getSecurityScanSummary: vi.fn() }));
afterEach(() => vi.resetAllMocks());

const cell = (n = 0, e = 0) => ({ new: n, existing: e, accepted: 0, fixed: 0 });
const counts = Object.fromEntries(["semgrep", "gitleaks", "trivy", "zap"].map((t) => [t,
  Object.fromEntries(["UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"].map((s) => [s, t === "trivy" && s === "HIGH" ? cell(1, 2) : cell()]))]));
const delivering = { route_enabled: true, smtp_configured: true, recipients_configured: true, delivering: true, last_sent_at: null, last_error: null };

function view(extra: Partial<SecurityScanSummaryView> = {}): SecurityScanSummaryView {
  return {
    available: true, mail: delivering, stale_after_hours: 26, generated_at: "2026-11-18T02:40:00Z", age_hours: 6, stale: false,
    passed: false, scan_errors: 0, blocking: 1, counts, findings_listed_in_summary: true, findings_total: 2,
    findings: [
      { tool: "trivy", rule: "CVE-2026-0001", severity: "HIGH", location: "registry/app: libfoo", status: "new", fix_available: true },
      { tool: "semgrep", rule: "rule.x", severity: "MEDIUM", location: "src/A.java", status: "existing", fix_available: false },
    ],
    failed: [], ...extra,
  };
}

it("shows the date, counts by tool and severity and the high/medium table with a fix-available column", async () => {
  vi.mocked(api.getSecurityScanSummary).mockResolvedValue(view());
  render(<SecurityScanCard />);
  expect(await screen.findByText(/Last daily scan:/)).toBeInTheDocument();
  expect(within(screen.getByTestId("security-scan-counts")).getByText("3 (1 new)")).toBeInTheDocument();
  const table = screen.getByTestId("security-scan-findings");
  expect(within(table).getByText("FIX AVAILABLE")).toBeInTheDocument();
  expect(within(table).getByText("CVE-2026-0001")).toBeInTheDocument();
  expect(within(table).getByText("registry/app: libfoo")).toBeInTheDocument();
  expect(within(table).getByText("Yes")).toBeInTheDocument();
  expect(screen.queryByTestId("security-scan-mail-warning")).not.toBeInTheDocument();
  expect(screen.queryByTestId("security-scan-stale")).not.toBeInTheDocument();
});

it("warns when the last daily scan is stale and when the results are not mailed", async () => {
  vi.mocked(api.getSecurityScanSummary).mockResolvedValue(view({
    stale: true, age_hours: 30,
    mail: { ...delivering, route_enabled: false, smtp_configured: false, recipients_configured: false, delivering: false },
  }));
  render(<SecurityScanCard />);
  expect(await screen.findByTestId("security-scan-stale")).toHaveTextContent("30 h ago");
  expect(screen.getByTestId("security-scan-mail-warning")).toHaveTextContent("Daily scan results are not being mailed: configure Settings > Notifications");
  expect(screen.getByTestId("security-scan-mail-warning")).toHaveTextContent("SMTP is not configured");
});

it("names the missing piece, handles no summary and an older summary without a list, and stays quiet on a refused read", async () => {
  expect(mailWarning(view({ mail: { ...delivering, recipients_configured: false, delivering: false } }))).toContain("no recipient is set");
  expect(mailWarning(view())).toBeNull();
  vi.mocked(api.getSecurityScanSummary).mockResolvedValueOnce({ available: false, reason: "NO_DAILY_SUMMARY", mail: delivering, stale_after_hours: 26 });
  const first = render(<SecurityScanCard />);
  expect(await screen.findByText(/No daily scan summary exists yet/)).toBeInTheDocument();
  first.unmount();
  vi.mocked(api.getSecurityScanSummary).mockResolvedValueOnce(view({ findings_listed_in_summary: false, findings: [], findings_total: 0 }));
  const second = render(<SecurityScanCard />);
  expect(await screen.findByText(/written before the finding list existed/)).toBeInTheDocument();
  second.unmount();
  vi.mocked(api.getSecurityScanSummary).mockRejectedValueOnce({ status: 403 });
  render(<SecurityScanCard />);
  expect(await screen.findByText(/not available to your role/)).toBeInTheDocument();
  expect(screen.queryAllByRole("alert")).toHaveLength(0);
});
