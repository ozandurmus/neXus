import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ComplianceGuidance, GUIDANCE_WARNING, observedValue } from "../src/screens/ComplianceGuidance";
import { remediationReport } from "../src/screens/complianceReport";
import type { DeviceComplianceResult } from "../src/auth/adminApi";

const guidance = { summary: "Review the setting", steps: ["Open vendor settings", "Review the approved value"],
  cli: "synthetic documentation snippet", references: ["Synthetic vendor reference"], caution: "Requires change review" };
const report: DeviceComplianceResult = { device_id: "opaque-device", hostname: "FW-TANGO-04", vendor: "check_point",
  totalAssigned: 3, passCount: 1, failCount: 1, dataUnavailableCount: 1, observedCompliance: 50, evidenceCoverage: 66.7, assuredCompliance: 33.3,
  items: [{ controlId: "failed", title: "Failed control", severity: "HIGH", frameworks: [], verdict: "FAIL", reasonCode: "ASSERTION_FAILED",
    displayStatus: "FAIL", observedValue: "8", rationale: "Reduces exposure", guidance },
  { controlId: "passed", title: "Passing control", severity: "LOW", frameworks: [], verdict: "PASS", reasonCode: "ASSERTION_SATISFIED", displayStatus: "PASS" },
  { controlId: "unknown", title: "Unknown control", severity: "LOW", frameworks: [], verdict: "UNKNOWN", reasonCode: "EVIDENCE_MISSING", displayStatus: "DATA_UNAVAILABLE" }] };

afterEach(() => vi.unstubAllGlobals());

describe("operator guidance", () => {
  it("opens failing guidance and copies only the snippet", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    render(<ComplianceGuidance guidance={guidance} status="FAIL" />);
    expect(screen.getByText("How to fix").parentElement).toHaveAttribute("open");
    expect(screen.getByText(GUIDANCE_WARNING)).toBeInTheDocument();
    expect(screen.getByText("Synthetic vendor reference")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy CLI" }));
    expect(await screen.findByText("Copied")).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(guidance.cli);
  });

  it("keeps passing guidance collapsed and has no copy action for prose-only guidance", () => {
    render(<ComplianceGuidance guidance={{ ...guidance, cli: null }} status="PASS" />);
    expect(screen.getByText("How to fix").parentElement).not.toHaveAttribute("open");
    expect(screen.queryByRole("button", { name: "Copy CLI" })).not.toBeInTheDocument();
    expect(observedValue(null)).toBe("observed value not recorded");
    expect(observedValue(" ")).toBe("observed value not recorded");
    expect(observedValue("0")).toBe("0");
  });

  it("reports clipboard failure without claiming success", async () => {
    vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) } });
    render(<ComplianceGuidance guidance={guidance} status="FAIL" />);
    fireEvent.click(screen.getByRole("button", { name: "Copy CLI" }));
    expect(await screen.findByText(/Could not copy/)).toBeInTheDocument();
    expect(screen.queryByText("Copied")).not.toBeInTheDocument();
  });
});

describe("per-device remediation reports", () => {
  it.each(["csv", "html"] as const)("exports only failing findings and their guidance as %s", format => {
    const text = remediationReport(report, format, true);
    for (const value of ["FW-TANGO-04", "masked (aiview pseudonyms)", "Failed control", "Reduces exposure", guidance.summary,
      ...guidance.steps, guidance.cli, ...guidance.references, guidance.caution, GUIDANCE_WARNING]) expect(text).toContain(value);
    expect(text).not.toContain("Passing control");
    expect(text).not.toContain("Unknown control");
  });

  it("escapes HTML and neutralizes spreadsheet formulas", () => {
    const unsafe = { ...report, hostname: "=1+1", items: [{ ...report.items[0], title: '<script>alert("x")</script>',
      observedValue: "+1+1", guidance: { ...guidance, cli: "<img src=x onerror=alert(1)>" } }] };
    const html = remediationReport(unsafe, "html", true);
    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<img");
    expect(html).toContain("&lt;script&gt;");
    expect(html).toContain("Content-Security-Policy");
    expect(html).toContain("@media print");
    const csv = remediationReport(unsafe, "csv", true);
    expect(csv).toContain('"\'=1+1"');
    expect(csv).toContain('"\'+1+1"');
  });

  it("handles legacy evaluations and no failing findings", () => {
    const legacy = { ...report, items: [{ ...report.items[0], guidance: null, observedValue: null, rationale: null }] };
    expect(remediationReport(legacy, "html", true)).toContain("observed value not recorded");
    expect(remediationReport(legacy, "csv", true)).toContain("Guidance not recorded for this evaluation.");
    expect(remediationReport({ ...report, items: [] }, "html", true)).toContain("0 failing findings");
  });
});
