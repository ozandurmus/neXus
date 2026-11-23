import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CutoverNewAssessment, LOOKUP_VENDOR_HINTS, zoneNoun } from "../src/screens/cutover/CutoverNewAssessment";
import { listDevices, listPolicies } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  listDevices: vi.fn(), listPolicies: vi.fn(), runCutoverAssessment: vi.fn(),
}));

/** The vendor-specific wording and policy picker of the override panel (delivery step 9, ASA and FortiGate). Masked names only. */
const devices = [
  { device_id: "d-cp", hostname: "FW-TANGO-04", vendor_hint: "check_point" },
  { device_id: "d-pan", hostname: "FW-JULIET-06", vendor_hint: "palo_alto" },
  { device_id: "d-asa", hostname: "FW-BRAVO-02", vendor_hint: "cisco_asa" },
  { device_id: "d-fgt", hostname: "FW-ROMEO-01", vendor_hint: "fortinet" },
  { device_id: "d-rad", hostname: "FW-SIERRA-03", vendor_hint: "radware" },
];
const target = (deviceId: string, name: string) => [{ deviceId, name, context: "", syncStatus: "" }];
const policies = [
  { id: "p-cp", name: "POLICY-ALPHA", vendor: "CP", targets: target("d-cp", "FW-TANGO-04") },
  { id: "p-pan", name: "POLICY-BRAVO", vendor: "PAN", targets: target("d-pan", "FW-JULIET-06") },
  { id: "p-asa", name: "POLICY-CHARLIE", vendor: "ASA", targets: target("d-asa", "FW-BRAVO-02") },
  { id: "p-fgt", name: "POLICY-DELTA", vendor: "FGT", targets: target("d-fgt", "FW-ROMEO-01") },
];

beforeEach(() => {
  vi.mocked(listDevices).mockResolvedValue({ devices } as never);
  vi.mocked(listPolicies).mockResolvedValue({ policies, devices: [] } as never);
});
afterEach(() => { vi.clearAllMocks(); });

async function choose(hostname: RegExp) {
  render(<CutoverNewAssessment segmentId="seg-1" onDone={() => undefined} />);
  const panel = await screen.findByRole("region", { name: "New assessment" });
  fireEvent.click(within(panel).getByLabelText("Override target"));
  fireEvent.mouseDown(within(panel).getByLabelText(/Target firewall/));
  fireEvent.click(await screen.findByRole("option", { name: hostname }));
  return panel;
}

describe("override panel per vendor", () => {
  it("evaluates the four vendors with a policy model and no other", () => {
    expect([...LOOKUP_VENDOR_HINTS].sort()).toEqual(["check_point", "cisco_asa", "fortinet", "palo_alto"]);
    expect(zoneNoun("palo_alto")).toBe("zone");
    expect(zoneNoun("cisco_asa")).toBe("interface (nameif)");
    expect(zoneNoun("fortinet")).toBe("interface or zone");
    expect(zoneNoun("check_point")).toBeUndefined();
    expect(zoneNoun(undefined)).toBeUndefined();
  });

  it("labels the Cisco ASA inputs by interface nameif and enables its policy picker", async () => {
    const panel = await choose(/FW-BRAVO-02/);
    expect(await within(panel).findByLabelText(/Stored policy installed on it/)).toBeInTheDocument();
    expect(within(panel).queryByText(/every flow will be NOT_EVALUABLE/)).toBeNull();
    expect(within(panel).getByLabelText(/Segment-side interface \(nameif\)/)).toBeInTheDocument();
    expect(within(panel).getByLabelText(/Peer-side interface \(nameif\)/)).toBeInTheDocument();
    fireEvent.mouseDown(within(panel).getByLabelText(/Stored policy installed on it/));
    expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Select the policy", "POLICY-CHARLIE"]);
  });

  it("labels the FortiGate inputs by interface or zone and enables its policy picker", async () => {
    const panel = await choose(/FW-ROMEO-01/);
    expect(await within(panel).findByLabelText(/Stored policy installed on it/)).toBeInTheDocument();
    expect(within(panel).getByLabelText(/Segment-side interface or zone/)).toBeInTheDocument();
    expect(within(panel).getByLabelText(/Peer-side interface or zone/)).toBeInTheDocument();
    fireEvent.mouseDown(within(panel).getByLabelText(/Stored policy installed on it/));
    expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Select the policy", "POLICY-DELTA"]);
  });

  it("keeps the Palo Alto wording and shows no zone inputs for Check Point", async () => {
    const pan = await choose(/FW-JULIET-06/);
    expect(await within(pan).findByLabelText(/Segment-side zone/)).toBeInTheDocument();
    expect(within(pan).getByLabelText(/Peer-side zone/)).toBeInTheDocument();
    fireEvent.mouseDown(within(pan).getByLabelText(/Target firewall/));
    fireEvent.click(await screen.findByRole("option", { name: /FW-TANGO-04/ }));
    expect(await within(pan).findByLabelText(/Stored policy installed on it/)).toBeInTheDocument();
    expect(within(pan).queryByLabelText(/Segment-side/)).toBeNull();
  });

  it("still offers no picker for a vendor without a policy model", async () => {
    const panel = await choose(/FW-SIERRA-03/);
    expect(await within(panel).findByText(/every flow will be NOT_EVALUABLE/)).toBeInTheDocument();
    expect(within(panel).queryByLabelText(/Stored policy installed on it/)).toBeNull();
    expect(within(panel).queryByLabelText(/Segment-side/)).toBeNull();
  });
});
