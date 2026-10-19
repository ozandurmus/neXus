import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ComplianceScreen, compareControls } from "../src/screens/ComplianceScreen";
import type { ComplianceControlItem } from "../src/auth/adminApi";

// The control drawer writes `?control=<id>`; jsdom keeps the URL between tests, so it is reset here.
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

const OVERVIEW = {
  total_firewalls: 10, evaluated_firewalls: 10, assured_compliance_pct: 40, evidence_coverage_pct: 90, observed_compliance_pct: 45,
  critical_deficiencies: 3, data_gaps: 0,
  frameworks: [{ framework: "CIS Benchmark", score_pct: 40, total_controls: 100, pass_count: 40, fail_count: 50, data_unavailable_count: 10 }],
};

const control = (id: string, severity: ComplianceControlItem["severity"], status: ComplianceControlItem["status"], vendors: string[], fail = 0): ComplianceControlItem => ({
  control_id: id, title: `Title ${id}`, description: null, family: "AC", severity, category: "Authentication", frameworks: [{ framework: "CIS", reference: "1.1" }],
  status, compliance_pct: 50, target_device_count: 10, pass_count: 10 - fail, fail_count: fail, data_unavailable_count: 0, affected_devices: [], vendors,
} as ComplianceControlItem);

// Deliberately in control-ID order, the order the review found: gaia_*, pan_*, fg_*, asa_*.
const CONTROLS = [
  control("asa_a", "LOW", "PASS", ["cisco_asa"]),
  control("fg_b", "CRITICAL", "PASS", ["fortinet"]),
  control("gaia_c", "HIGH", "FAIL", ["check_point"], 4),
  control("gaia_d", "CRITICAL", "FAIL", ["check_point"], 2),
  control("pan_e", "MEDIUM", "DATA_UNAVAILABLE", ["palo_alto"]),
  control("pan_f", "CRITICAL", "FAIL", ["palo_alto"], 6),
];

function stub() {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.includes("/api/v2/compliance/controls/") ? { control_id: "gaia_d", title: "Title gaia_d", severity: "CRITICAL", status: "FAIL", expected: [{ vendor: "check_point", text: "x" }], frameworks: [],
      devices: [{ device_id: "d1", hostname: "FW-TANGO-04", vendor: "check_point", status: "FAIL", observed_value: "3", message: "Too low" }] }
      : url.includes("/compliance/overview") ? OVERVIEW : { controls: CONTROLS }), { status: 200 }))));
}

const controlOrder = () => within(screen.getAllByRole("table").find((t) => within(t).queryByText("Control · code & title"))!)
  .getAllByRole("row").slice(1).map((row) => row.textContent?.match(/(gaia|pan|fg|asa)_[a-f]/)?.[0]);

describe("Compliance triage (UI review 2026-10-18, F-14)", () => {
  it("orders non-compliant controls first and the most severe first, with the most failing firewalls before fewer", () => {
    const sorted = [...CONTROLS].sort((a, b) => compareControls(a, b)).map((c) => c.control_id);
    expect(sorted).toEqual(["pan_f", "gaia_d", "gaia_c", "pan_e", "fg_b", "asa_a"]);
    expect(CONTROLS.map((c) => c.control_id).sort()).toEqual([...CONTROLS].sort((a, b) => compareControls(a, b, "ID")).map((c) => c.control_id));
  });

  it("opens with the non-compliant critical controls on top and lets the reader return to control-ID order", async () => {
    stub();
    render(<ComplianceScreen />);
    await screen.findByText("Title pan_f");
    expect(controlOrder()).toEqual(["pan_f", "gaia_d", "gaia_c", "pan_e", "fg_b", "asa_a"]);
    fireEvent.change(screen.getByLabelText("Sort controls"), { target: { value: "ID" } });
    expect(controlOrder()).toEqual(["asa_a", "fg_b", "gaia_c", "gaia_d", "pan_e", "pan_f"]);
  });

  it("puts vendor pills beside the outcome pills, with the display name and the count", async () => {
    stub();
    render(<ComplianceScreen />);
    await screen.findByText("Title pan_f");
    const vendors = screen.getByRole("group", { name: "Vendor filter" });
    expect(within(vendors).getByRole("button", { name: "All vendors" })).toHaveAttribute("aria-pressed", "true");
    expect(within(vendors).getByRole("button", { name: "Check Point · 2" })).toBeInTheDocument();
    expect(within(vendors).getByRole("button", { name: "Palo Alto · 2" })).toBeInTheDocument();
    expect(within(vendors).getByRole("button", { name: "Cisco ASA · 1" })).toBeInTheDocument();
    expect(within(vendors).getByRole("button", { name: "Fortinet · 1" })).toBeInTheDocument();
    expect(screen.getByText("Failing · 3")).toBeInTheDocument();
  });

  it("filters by vendor, together with the outcome pill", async () => {
    stub();
    render(<ComplianceScreen />);
    await screen.findByText("Title pan_f");
    fireEvent.click(screen.getByRole("button", { name: "Palo Alto · 2" }));
    expect(controlOrder()).toEqual(["pan_f", "pan_e"]);
    fireEvent.click(screen.getByText("Failing · 3"));
    expect(controlOrder()).toEqual(["pan_f"]);
    fireEvent.click(screen.getByRole("button", { name: "All vendors" }));
    fireEvent.click(screen.getByText("All · 6"));
    expect(controlOrder()).toHaveLength(6);
  });

  it("shows the vendor on each control row by its display name", async () => {
    stub();
    render(<ComplianceScreen />);
    await screen.findByText("Title asa_a");
    const row = screen.getByText("Title asa_a").closest("tr")!;
    expect(within(row).getByText("Cisco ASA")).toBeInTheDocument();
    expect(within(row).queryByText("cisco_asa")).toBeNull();
  });

  it("keeps the filter bar sticky", async () => {
    stub();
    render(<ComplianceScreen />);
    await screen.findByText("Title pan_f");
    expect(document.querySelector("[data-sticky-filter-bar]")).toHaveStyle({ position: "sticky", top: "0px" });
  });

  it("opens the control drawer 560 px wide, with the vendor by name and the outcome in words", async () => {
    stub();
    render(<ComplianceScreen />);
    fireEvent.click(await screen.findByText("Title gaia_d"));
    const drawer = await screen.findByText("Too low");
    expect(drawer).toBeInTheDocument();
    const paper = document.querySelector(".MuiDrawer-paper") as HTMLElement;
    // The width is responsive (full width on a phone, 560 px from the md breakpoint); jsdom applies no media query,
    // so the rule is read from the stylesheet.
    const css = [...document.querySelectorAll("style")].map((node) => [...((node as HTMLStyleElement).sheet?.cssRules ?? [])].map((r) => r.cssText).join(" ")).join(" ");
    expect(css).toMatch(/width:\s*560px/);
    expect(css).not.toMatch(/width:\s*540px/);
    expect(within(paper).getAllByText("Check Point").length).toBeGreaterThan(0);
    expect(within(paper).queryByText("check_point")).toBeNull();
    expect(within(paper).getByText("Non-Compliant")).toBeInTheDocument();
  });

  it("does not paint zero data gaps green: the badge is neutral and says so in words", async () => {
    stub();
    render(<ComplianceScreen />);
    const badge = await screen.findByText("No data gaps");
    const chip = badge.closest("[data-pill]") as HTMLElement;
    const css = [...document.querySelectorAll("style")].map((node) => [...((node as HTMLStyleElement).sheet?.cssRules ?? [])].map((r) => r.cssText).join(" ")).join(" ");
    const className = [...chip.classList].find((c) => c.startsWith("css-"))!;
    const rule = css.match(new RegExp(`\\.${className}\\s*\\{[^}]*\\}`))?.[0] ?? "";
    expect(rule).toContain("var(--nx-sc-high)");
    expect(rule).not.toContain("var(--nx-success-container)");
    expect(screen.queryByText("Full Coverage")).toBeNull();
  });
});
