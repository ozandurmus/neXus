import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { JobStatusIndicator } from "../src/shell/JobStatusIndicator";

it("uses the friendly job type in the completed tooltip", async () => {
  render(<JobStatusIndicator state="COMPLETED" type="asa_inventory_collect" />);
  fireEvent.mouseOver(screen.getByText("✓"));
  expect(await screen.findByText("Completed: Cisco ASA · read inventory finished successfully")).toBeInTheDocument();
});
