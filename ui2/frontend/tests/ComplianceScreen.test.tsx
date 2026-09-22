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
  control_id: id, title: `Control ${id}`, description: "d", severity: "HIGH", category, frameworks: [{ framework: "CIS", reference: "2.1.1" }],
  status, compliance_pct: 40, target_device_count: counts[0] + counts[1] + counts[2],
  pass_count: counts[0], fail_count: counts[1], data_unavailable_count: counts[2], affected_devices: affected,
  ...(status === "DATA_UNAVAILABLE" ? { missing_reason: "command not in the collection scope" } : {}),
});

function stub() {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.includes("/compliance/overview") ? OVERVIEW : {
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

  it("shows control families with checks, coverage and findings", async () => {
    stub();
    render(<ComplianceScreen />);
    const table = (await screen.findByText("Control families")).closest("div")!.parentElement!;
    const auth = within(table).getByText("Authentication").closest("tr")!;
    expect(auth.textContent).toContain("124"); // checks 62 + 62
    expect(auth.textContent).toContain("22"); // findings
    const logging = within(table).getByText("Logging").closest("tr")!;
    expect(logging.textContent).toContain("0%"); // no evidence at all
  });

  it("names a data gap 'Evidence not collected', never an internal cause, and never invents a device", async () => {
    stub();
    render(<ComplianceScreen />);
    expect((await screen.findAllByText("Evidence not collected")).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByText("Control c2"));
    expect(await screen.findByText("No firewall listed for this control")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("FW-JULIET-06");
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
  it("groups by the NIST SP 800-53 family when the catalog carries no category", async () => {
    const { familyOf } = await import("../src/screens/ComplianceScreen");
    const base = { control_id: "x", title: "t", description: "d", severity: "HIGH", status: "PASS", compliance_pct: 0, target_device_count: 0,
      pass_count: 0, fail_count: 0, data_unavailable_count: 0, affected_devices: [] } as const;
    expect(familyOf({ ...base, category: "null", frameworks: [{ framework: "CIS", reference: "2.1.1" }, { framework: "NIST_800_53", reference: "IA-5(1)" }] } as never))
      .toBe("IA · Identification and Authentication");
    expect(familyOf({ ...base, category: null, frameworks: [{ framework: "CIS", reference: "2.1.1" }] } as never)).toBe("Unmapped");
  });
});
