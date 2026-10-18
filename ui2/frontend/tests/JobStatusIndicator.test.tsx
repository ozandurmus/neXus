import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { JobStatusIndicator, latestJobSentence } from "../src/shell/JobStatusIndicator";
import { liveBasis } from "../src/screens/DeviceShared";

/**
 * The green tick beside every device name is gone (post-release review 2026-10-18, §3): with the identity badge bound
 * to a real comparison it read as a verification it never was. The fact it carried -- the latest job completed -- is a
 * sentence on the Live chip's hover; running, queued and failed jobs keep their glyphs.
 */
describe("the job indicator", () => {
  it("renders nothing for a completed job: no tick, no glyph", () => {
    const { container } = render(<JobStatusIndicator state="COMPLETED" type="asa_inventory_collect" />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByText("✓")).toBeNull();
  });

  it("keeps the failed glyph with its reason on hover", async () => {
    render(<JobStatusIndicator state="FAILED" type="asa_inventory_collect" terminalReason="TIMEOUT" />);
    fireEvent.mouseOver(screen.getByText("⚠"));
    expect(await screen.findByText("Failed: TIMEOUT")).toBeInTheDocument();
  });

  it("words the latest job as one sentence with the friendly job type", () => {
    expect(latestJobSentence("COMPLETED", "asa_inventory_collect")).toBe("Latest job: Cisco ASA inventory read completed.");
    expect(latestJobSentence("EXECUTING", "asa_inventory_collect")).toBe("Latest job: Cisco ASA inventory read is running.");
    expect(latestJobSentence("REQUESTED", null)).toBe("Latest job: Job is queued.");
    expect(latestJobSentence("FAILED", "asa_inventory_collect")).toBe("Latest job: Cisco ASA inventory read failed.");
    expect(latestJobSentence(null, "asa_inventory_collect")).toBeNull();
  });

  it("states what Live rests on, never identity, and appends the latest job", () => {
    const basis = liveBasis(latestJobSentence("COMPLETED", "asa_inventory_collect"));
    expect(basis).toMatch(/^Live means neXus confirmed this device and has read evidence from it/);
    expect(basis).toContain("It is not an identity check; the identity badge says that.");
    expect(basis).toMatch(/Latest job: Cisco ASA inventory read completed\.$/);
    expect(basis).not.toMatch(/verified/i);
    expect(liveBasis(null)).toMatch(/identity badge says that\.$/);
  });
});
