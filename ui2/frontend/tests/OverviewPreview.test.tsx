import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import { OverviewPreview } from "../src/preview/OverviewPreview";

it("previews the Overview attention list with the live screen's own rules (OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md)", () => {
  render(<OverviewPreview />);
  const list = screen.getByTestId("attention-list");
  const order = [...list.querySelectorAll("[data-attention]")].map((e) => e.getAttribute("data-attention"));
  expect(order.slice(0, 4)).toEqual(["ha-not-ready", "backup-missing", "advisory-exploited", "compliance-critical"]);
  expect(screen.getByText("1 HA cluster not ready for failover")).toBeInTheDocument();
  expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument();
  // eight rows, then "Show all": the Watch rows (ageing read, Live Patch, UNKNOWN readiness, unknown actor) wait behind it
  expect(order).toHaveLength(8);
  expect(screen.getByText("Show all 12")).toBeInTheDocument();
  expect(screen.getAllByText(/of evidenced controls pass/)[0].textContent).toBe("37.5% of evidenced controls pass · 19.9% have no evidence");
  expect(document.body.textContent).not.toMatch(/AT RISK|Security posture|Change pulse/);
});
