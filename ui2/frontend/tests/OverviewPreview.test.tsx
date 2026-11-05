import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import { OverviewPreview } from "../src/preview/OverviewPreview";

it("previews the executive home with the live screen's verdict, banner and attention logic (UI review 2026-11-05, C1)", () => {
  render(<OverviewPreview />);
  expect(screen.getByText("AT RISK")).toBeInTheDocument();
  expect(screen.getByRole("status", { name: "Data freshness warning" }).textContent).toContain("131 of 132 active devices were last read more than 24 h ago");
  for (const pillar of ["Security posture", "Backup coverage", "HA readiness", "Data freshness"]) expect(screen.getByText(pillar)).toBeInTheDocument();
  expect(screen.getByText("2 of 45 HA clusters not ready for failover")).toBeInTheDocument();
  expect(screen.getAllByText(/of evidenced controls pass/)[0].textContent).toBe("37.5% of evidenced controls pass · 19.9% have no evidence");
});
