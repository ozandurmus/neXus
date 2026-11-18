import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  PolicyInstallMonitoringPanel,
  POLICY_INSTALL_CONTRACT_PATH,
  parityLabel,
  stateTone,
} from "../src/screens/PolicyInstallMonitoringPanel";
import { tabFromUrl } from "../src/screens/OperationsScreen";
import type { PolicyInstallStatusResponse } from "../src/auth/adminApi";

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

const SAMPLE_RESPONSE: PolicyInstallStatusResponse = {
  counts: {
    INSTALLED: 1,
    OUT_OF_DATE: 1,
    NOT_INSTALLED: 0,
    SAVED: 1,
    UNSAVED: 0,
    NOT_APPLICABLE: 1,
    UNKNOWN: 1,
  },
  total: 5,
  page: 0,
  page_size: 200,
  masked: true,
  contract_ref: POLICY_INSTALL_CONTRACT_PATH,
  rows: [
    {
      row_id: "cp:pkg-1:dev-cp-1",
      device_id: "dev-cp-1",
      hostname: "FW-TANGO-04",
      context: "",
      vendor: "check_point",
      container_id: "dom-1",
      container_name: "DOM-ALPHA-01",
      package_id: "pkg-1",
      package_name: "POL-ALPHA-01",
      state: "INSTALLED",
      ha_parity: null,
      policy_name: "POL-ALPHA-01",
      installed_at: "2026-11-17T18:00:00Z",
      last_change_at: "2026-11-17T12:00:00Z",
      reason: "Direct gateway cpstat corroborates package name and install timestamp",
    },
    {
      row_id: "pan:dg-1:dev-pan-1",
      device_id: "dev-pan-1",
      hostname: "FW-JULIET-06",
      context: "",
      vendor: "palo_alto",
      container_id: "dg-1",
      container_name: "DOM-BRAVO-02",
      package_id: "dg-1",
      package_name: "POL-BRAVO-02",
      state: "OUT_OF_DATE",
      ha_parity: null,
      policy_name: "POL-BRAVO-02",
      installed_at: "2026-11-17T10:00:00Z",
      last_change_at: "2026-11-17T16:00:00Z",
      reason: "Panorama device-group shared policy status is OUT_OF_SYNC",
    },
    {
      row_id: "asa:dev-asa-1",
      device_id: "dev-asa-1",
      hostname: "FW-BRAVO-02",
      context: "",
      vendor: "cisco_asa",
      container_id: "dev-asa-1",
      container_name: "running-config vs startup-config",
      package_id: "asa-config:dev-asa-1",
      package_name: "running-config / startup-config",
      state: "SAVED",
      ha_parity: null,
      policy_name: null,
      installed_at: "2026-11-17T06:00:00Z",
      last_change_at: "2026-11-17T06:00:00Z",
      reason: "running-config matches startup-config",
    },
    {
      row_id: "fgt:dev-fgt-1",
      device_id: "dev-fgt-1",
      hostname: "FW-ROMEO-01",
      context: "",
      vendor: "fortinet",
      container_id: "dev-fgt-1",
      container_name: "Direct FortiOS (cfg-save automatic)",
      package_id: "fgt-policy:dev-fgt-1",
      package_name: "Local firewall policy",
      state: "NOT_APPLICABLE",
      ha_parity: "EQUAL",
      policy_name: null,
      installed_at: "2026-11-17T14:00:00Z",
      last_change_at: null,
      reason: "FortiOS applies changes immediately; HA cluster checksums equal",
    },
    {
      row_id: "cp:pkg-2:dev-cp-2:3",
      device_id: "dev-cp-2",
      hostname: "FW-SIERRA-03",
      context: "3",
      vendor: "check_point",
      container_id: "dom-1",
      container_name: "DOM-ALPHA-01",
      package_id: "pkg-2",
      package_name: "POL-CHARLIE-03",
      state: "UNKNOWN",
      ha_parity: null,
      policy_name: null,
      installed_at: null,
      last_change_at: "2026-11-17T11:00:00Z",
      reason: "needs a read not approved yet (GI-1: per-VS cpstat policy read)",
    },
  ],
};

function stubPolicyInstallFetch(response: PolicyInstallStatusResponse = SAMPLE_RESPONSE) {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      if (url.startsWith("/api/v2/operations/policy-install/status")) {
        return Promise.resolve(new Response(JSON.stringify(response), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 404 }));
    })
  );
  return calls;
}

describe("Operations > Policy installation tab (Phase 1 read-only monitoring)", () => {
  it("maps ?tab=policy-install to tab index 3 on OperationsScreen", () => {
    expect(tabFromUrl("policy-install")).toEqual({ tab: 3, pill: null });
    expect(stateTone("INSTALLED")).toBe("ok");
    expect(stateTone("SAVED")).toBe("ok");
    expect(stateTone("OUT_OF_DATE")).toBe("warn");
    expect(stateTone("UNSAVED")).toBe("warn");
    expect(stateTone("NOT_INSTALLED")).toBe("bad");
    expect(stateTone("NOT_APPLICABLE")).toBe("neutral");
    expect(stateTone("UNKNOWN")).toBe("unknown");
    expect(parityLabel("EQUAL")).toBe("checksums equal");
    expect(parityLabel("DIFFER")).toBe("checksums differ");
    expect(parityLabel("UNKNOWN")).toBe("checksums unknown");
  });

  it("renders unified vendor table, state counts, HA parity chip, reason hover, Not built yet contract link, and zero action buttons", async () => {
    const calls = stubPolicyInstallFetch();
    render(<PolicyInstallMonitoringPanel />);

    const table = await screen.findByRole("table", { name: "Policy installation status table" });
    expect(calls).toContain("/api/v2/operations/policy-install/status");

    // Counts per state at the top
    const countsBar = screen.getByTestId("policy-install-counts");
    expect(within(countsBar).getByText("INSTALLED")).toBeInTheDocument();
    expect(within(countsBar).getByText("SAVED")).toBeInTheDocument();
    expect(within(countsBar).getByText("NOT_APPLICABLE")).toBeInTheDocument();

    // "Not built yet" note and contract link
    const note = screen.getByTestId("policy-install-not-built-note");
    expect(note).toHaveTextContent(/Not built yet:/);
    const link = within(note).getByRole("link", { name: POLICY_INSTALL_CONTRACT_PATH });
    expect(link).toHaveAttribute("href", `/${POLICY_INSTALL_CONTRACT_PATH}`);

    // Unified table rows across all vendors
    expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(table).getByText("FW-JULIET-06")).toBeInTheDocument();
    expect(within(table).getByText("FW-BRAVO-02")).toBeInTheDocument();
    expect(within(table).getByText("FW-ROMEO-01")).toBeInTheDocument();
    expect(within(table).getByText("FW-SIERRA-03 (VS 3)")).toBeInTheDocument();

    // FortiGate NOT_APPLICABLE + HA parity indicator
    expect(within(table).getByText("checksums equal")).toBeInTheDocument();

    // Reason on hover
    const gi1Reason = within(table).getByText("needs a read not approved yet (GI-1: per-VS cpstat policy read)");
    expect(gi1Reason).toHaveAttribute("title", "needs a read not approved yet (GI-1: per-VS cpstat policy read)");

    // No buttons that would start verify, install, commit, or schedule
    expect(screen.queryByRole("button", { name: /verify|install|commit|approve|schedule/i })).toBeNull();
  });

  it("queries the GET endpoint when search text is entered", async () => {
    const calls = stubPolicyInstallFetch();
    render(<PolicyInstallMonitoringPanel />);
    await screen.findByRole("table", { name: "Policy installation status table" });

    const searchInput = screen.getByLabelText("Search policy installations");
    fireEvent.change(searchInput, { target: { value: "FW-TANGO-04" } });

    await waitFor(() =>
      expect(calls.some((u) => u.includes("/api/v2/operations/policy-install/status?q=FW-TANGO-04"))).toBe(true)
    );
  });
});
