import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ComplianceGuidance } from "../src/auth/adminApi";
import { EstateComplianceGuidance } from "../src/estate/EstateComplianceGuidance";
import { GUIDANCE_WARNING } from "../src/screens/ComplianceGuidance";

// Synthetic fixture rows only (FW-TANGO-04 style). Guidance text is generic, not device output.
const FAIL_GUIDANCE: ComplianceGuidance = {
  summary: "Set the example logging level to the required value.",
  steps: ["Open the example policy view", "Set the logging level to the expected value"],
  cli: "set example-logging level high",
  references: ["Vendor guide section 4.2"],
  caution: "Confirm the change window first.",
};

const FIXTURE_ROWS = [
  { device_id: "dev-tango-04", hostname: "FW-TANGO-04", status: "FAIL", guidance: FAIL_GUIDANCE },
  { device_id: "dev-juliet-06", hostname: "FW-JULIET-06", status: "PASS", guidance: FAIL_GUIDANCE },
  { device_id: "dev-bravo-02", hostname: "FW-BRAVO-02", status: "UNKNOWN", guidance: null },
  { device_id: "dev-romeo-01", hostname: "CLS-ROMEO-01", status: "UNKNOWN", guidance: FAIL_GUIDANCE },
] as const;

describe("EstateComplianceGuidance", () => {
  it("shows the guidance text and GUIDANCE_WARNING for a fail row", () => {
    render(<EstateComplianceGuidance guidance={FIXTURE_ROWS[0].guidance} status={FIXTURE_ROWS[0].status} />);
    expect(screen.getByText(FAIL_GUIDANCE.summary)).toBeTruthy();
    expect(screen.getByText("Open the example policy view")).toBeTruthy();
    expect(screen.getByText(FAIL_GUIDANCE.cli as string)).toBeTruthy();
    expect(screen.getByText(GUIDANCE_WARNING)).toBeTruthy();
    expect(screen.getByText("Vendor guide section 4.2")).toBeTruthy();
    expect(screen.getByText("Confirm the change window first.")).toBeTruthy();
  });

  it("renders nothing for a pass row, even when guidance is present", () => {
    const { container } = render(
      <EstateComplianceGuidance guidance={FIXTURE_ROWS[1].guidance} status={FIXTURE_ROWS[1].status} />,
    );
    expect(container.innerHTML).toBe("");
    expect(screen.queryByTestId("estate-compliance-guidance")).toBeNull();
    expect(screen.queryByText(GUIDANCE_WARNING)).toBeNull();
  });

  it("renders nothing for an unknown row without guidance", () => {
    const { container } = render(
      <EstateComplianceGuidance guidance={FIXTURE_ROWS[2].guidance} status={FIXTURE_ROWS[2].status} />,
    );
    expect(container.innerHTML).toBe("");
  });

  it("shows guidance only for an unknown row that carries it", () => {
    render(<EstateComplianceGuidance guidance={FIXTURE_ROWS[3].guidance} status={FIXTURE_ROWS[3].status} />);
    expect(FIXTURE_ROWS[3].status).toBe("UNKNOWN");
    expect(screen.getByText(FAIL_GUIDANCE.summary)).toBeTruthy();
    expect(screen.getByText(GUIDANCE_WARNING)).toBeTruthy();
  });

  it("is read-only: the block contains no buttons", () => {
    render(<EstateComplianceGuidance guidance={FAIL_GUIDANCE} status="FAIL" />);
    expect(screen.getByTestId("estate-compliance-guidance")).toBeTruthy();
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(screen.queryByText("Copy CLI")).toBeNull();
  });
});
