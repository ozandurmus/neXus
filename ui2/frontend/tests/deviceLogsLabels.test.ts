import { describe, expect, it } from "vitest";
import { gapKindLabel } from "../src/shell/presenters";
import * as DeviceLogsPanel from "../src/screens/DeviceLogsPanel";

describe("gapKindLabel (device-log gap kinds)", () => {
  it.each([
    ["SEQ_GAP_SUSPECTED", "Sequence gap suspected"],
    ["INITIAL_BASELINE", "First collection: older content not fetched"],
    ["ROTATED_AWAY", "Rotated away on the device"],
    ["TRUNCATED", "File truncated on the device"],
    ["ANCHOR_MISMATCH", "Saved position no longer matched"],
    ["BACKLOG", "Backlog on the device"],
    ["COLLECTION_FAILED", "Collection failed"],
    ["PAGE_STALL", "Paging stalled"],
    ["RETENTION_SHORTENED_BY_CAP", "Retention shortened by the storage cap"],
  ])("maps %s to its operator wording", (kind, label) => {
    expect(gapKindLabel(kind)).toBe(label);
  });

  it("humanises an unknown kind in the default branch", () => {
    expect(gapKindLabel("SOMETHING_NEW")).toBe("Something new");
    expect(gapKindLabel("FW_TANGO_EXTRA_KIND")).toBe("Fw tango extra kind");
  });

  it("is still re-exported from DeviceLogsPanel for existing importers", () => {
    expect(DeviceLogsPanel.gapKindLabel).toBe(gapKindLabel);
  });
});
