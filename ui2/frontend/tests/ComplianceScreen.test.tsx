import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ComplianceScreen } from "../src/screens/ComplianceScreen";

afterEach(() => vi.unstubAllGlobals());

const OVERVIEW = {
  total_firewalls: 105, evaluated_firewalls: 102, assured_compliance_pct: 28.9, evidence_coverage_pct: 82.5,
  observed_compliance_pct: 34.7, critical_deficiencies: 172, data_gaps: 428,
  frameworks: [{ framework: "CIS Benchmark", score_pct: 28.9, total_controls: 2448, pass_count: 708, fail_count: 1312, data_unavailable_count: 428 }],
};

const control = (id: string, category: string, status: "PASS" | "FAIL" | "DATA_UNAVAILABLE", counts: [number, number, number], affected: string[] = []) => ({
  control_id: id, title: `Control ${id}`, description: null, family: category === "Authentication" ? "AC" : "AU", severity: "HIGH", category, frameworks: [{ framework: "CIS", reference: "2.1.1" }],
  status, compliance_pct: 40, target_device_count: counts[0] + counts[1] + counts[2],
  pass_count: counts[0], fail_count: counts[1], data_unavailable_count: counts[2], affected_devices: affected,
  ...(status === "DATA_UNAVAILABLE" ? { missing_reason: "command not in the collection scope" } : {}),
});

function stub() {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.includes("/api/v2/compliance/controls/") ? {
      control_id: "c1", title: "Control c1", severity: "HIGH", status: "FAIL", rationale: null,
      frameworks: [], expected: [{ vendor: "check_point", text: "at least 5" }], devices: [
        { device_id: "d1", hostname: "FW-TANGO-04", vendor: "check_point", status: "FAIL", observed_value: "3", message: "Too low" },
        { device_id: "d2", hostname: "FW-JULIET-06", vendor: "check_point", status: "PASS", observed_value: "6", message: "OK" },
        { device_id: "d3", hostname: "FW-BRAVO-02", vendor: "check_point", status: "DATA_UNAVAILABLE", observed_value: null, message: null },
      ],
    } : url.includes("/compliance/overview") ? OVERVIEW : {
      controls: [
        control("c1", "Authentication", "FAIL", [40, 22, 0], ["FW-TANGO-04"]),
        control("c2", "Authentication", "PASS", [62, 0, 0]),
        control("c3", "Logging", "DATA_UNAVAILABLE", [0, 0, 62]),
      ],
    }), { status: 200 }))));
}

describe("Compliance -- UI review 2026-09-23", () => {
  it("labels framework checks as control checks with the shared stacked bar", async () => {
    stub();
    render(<ComplianceScreen />);
    expect(await screen.findByText("2448 control checks (control × firewall)")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Compliance" })).toBeInTheDocument();
  });

  it("uses plain words for re-checks, unchecked evidence, and passing checks", async () => {
    stub();
    render(<ComplianceScreen />);
    expect(await screen.findByRole("button", { name: "Re-check" })).toBeInTheDocument();
    expect(screen.getByText("Not yet checked · 1")).toBeInTheDocument();
    expect(screen.getByText("Checks passing")).toBeInTheDocument();
  });

  it("shows control families with checks, coverage and findings", async () => {
    stub();
    render(<ComplianceScreen />);
    const table = (await screen.findByText("Control families")).closest("div")!.parentElement!;
    const auth = within(table).getByText("AC · Access Control").closest("tr")!;
    expect(auth.textContent).toContain("124"); // checks 62 + 62
    expect(auth.textContent).toContain("22"); // findings
    const logging = within(table).getByText("AU · Audit and Accountability").closest("tr")!;
    expect(logging.textContent).toContain("0%"); // no evidence at all
  });

  it("names a data gap 'Evidence not collected', never an internal cause, and never invents a device", async () => {
    stub();
    render(<ComplianceScreen />);
    expect((await screen.findAllByText("Evidence not collected")).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByText("Control c1"));
    expect(await screen.findByText(/at least 5/)).toBeInTheDocument();
    expect(screen.getByText("FW-JULIET-06")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("null");
  });

  it("exports the audit report from what the screen read", async () => {
    stub();
    const created: Blob[] = [];
    vi.stubGlobal("URL", { ...URL, createObjectURL: (b: Blob) => { created.push(b); return "blob:x"; }, revokeObjectURL: () => {} });
    render(<ComplianceScreen />);
    await screen.findByText("Control c1");
    fireEvent.click(screen.getByRole("button", { name: /Export Audit Report/ }));
    expect(created).toHaveLength(1);
    const text = await new Promise<string>((resolve) => { const r = new FileReader(); r.onload = () => resolve(String(r.result)); r.readAsText(created[0]); });
    expect(text).toContain("# device scope");
    expect(text).toContain("102 of 105 firewalls evaluated");
    expect(text).toContain("FW-TANGO-04");
    expect(text).toContain("command not in the collection scope");
  });
});

describe("control families", () => {
  it("filters by family and keeps the URL", async () => {
    stub();
    render(<ComplianceScreen />);
    fireEvent.click((await screen.findByText("AC · Access Control")).closest("tr")!);
    expect(screen.getByText("Family: AC · Access Control")).toBeInTheDocument();
    expect(window.location.search).toContain("family=AC");
    expect(screen.queryByText("Control c3")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("CancelIcon"));
    expect(screen.getByText("Control c3")).toBeInTheDocument();
  });
  it("groups by the NIST SP 800-53 family when the catalog carries no category", async () => {
    const { familyOf } = await import("../src/screens/ComplianceScreen");
    const base = { control_id: "x", title: "t", description: "d", severity: "HIGH", status: "PASS", compliance_pct: 0, target_device_count: 0,
      pass_count: 0, fail_count: 0, data_unavailable_count: 0, affected_devices: [] } as const;
    expect(familyOf({ ...base, category: "null", frameworks: [{ framework: "CIS", reference: "2.1.1" }, { framework: "NIST_800_53", reference: "IA-5(1)" }] } as never))
      .toBe("IA · Identification and Authentication");
    expect(familyOf({ ...base, category: null, frameworks: [{ framework: "CIS", reference: "2.1.1" }] } as never)).toBe("Unmapped");
  });
});
