import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ComplianceScreen, complianceHeadline } from "../src/screens/ComplianceScreen";
import { backupsHeadline } from "../src/screens/BackupScreen";
import { policyHeadline, POLICY_TAB_GROUPS } from "../src/screens/PolicyScreen";
import type { PolicyMetadata } from "../src/auth/adminApi";

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

describe("headline lines (review 4.3): number · noun · denominator · age, UNKNOWN when absent", () => {
  it("Compliance", () => {
    // B4 (UI review 2026-11-05): the pass share of evidenced checks and the no-evidence share; unevidenced never counts as failed.
    expect(complianceHeadline({ evaluated_firewalls: 118, total_firewalls: 123, critical_deficiencies: 164, data_gaps: 496, control_checks: 2491,
      observed_compliance_pct: 37.5, evidence_coverage_pct: 80.1 }, 0, "loaded"))
      .toBe("37.5% of evidenced controls pass, 19.9% have no evidence · 2,491 checks · 118 of 123 firewalls · 164 critical failing · re-check time not recorded");
    // Without the service's own total the checks are summed from the controls the screen read; absent shares say UNKNOWN.
    expect(complianceHeadline({ evaluated_firewalls: 2, total_firewalls: 3, critical_deficiencies: 0, data_gaps: 0 }, 186, "loaded"))
      .toBe("UNKNOWN of evidenced controls pass, UNKNOWN have no evidence · 186 checks · 2 of 3 firewalls · 0 critical failing · re-check time not recorded");
    expect(complianceHeadline({ evaluated_firewalls: 0, total_firewalls: 3, critical_deficiencies: 0, data_gaps: 0 }, 0, "loaded")).toBe("No framework assigned · nothing assessed yet");
    expect(complianceHeadline(null, 0, "failed")).toBe("UNKNOWN of evidenced controls pass, UNKNOWN have no evidence · checks UNKNOWN · firewalls UNKNOWN · critical failing UNKNOWN");
    expect(complianceHeadline(null, 0, "loading")).toBe("Reading the compliance evaluation…");
  });

  it("Backups", () => {
    expect(backupsHeadline({ targets: 130, targetsWithArchive: 123, targetsWithoutArchive: 7 }, { schedule_enabled: true, backup_retention_days: 7 }))
      .toBe("123 of 130 targets with a stored backup · 02:00 daily · 7 without archive · retention 7 d");
    expect(backupsHeadline({ targets: 130, targetsWithArchive: 123, targetsWithoutArchive: 7 }, { schedule_enabled: false, backup_retention_days: 14 }))
      .toContain("· not scheduled · 7 without archive · retention 14 d");
    expect(backupsHeadline(null, null)).toBe("targets with a stored backup UNKNOWN · schedule UNKNOWN · without archive UNKNOWN · retention UNKNOWN");
  });

  it("Policy", () => {
    const now = new Date("2026-10-18T10:00:00Z");
    const policy = (id: string, containerId: string, ruleCount: number | undefined, collectedAt: string): PolicyMetadata => ({
      id, sourceId: "src-1", sourceName: "FW-JULIET-01", vendor: "PAN", containerId, containerName: `DOM-${containerId}`, name: `POL-${id}`,
      collectedAt, artefactRef: "", targets: [], ruleCount,
    } as unknown as PolicyMetadata);
    const catalog = [policy("a", "c1", 869, "2026-10-18T06:00:00Z"), policy("b", "c2", 91, "2026-10-18T05:00:00Z"), policy("c", "c2", 10, "2026-10-18T04:00:00Z")];
    expect(policyHeadline({ catalog, sources: 2, loading: false, now })).toBe("2 containers · 2 sources · 970 rules · newest snapshot 4 h ago");
    expect(policyHeadline({ catalog: [catalog[0], policy("d", "c3", undefined, "")], sources: 1, loading: false, now })).toBe("2 containers · 1 source · UNKNOWN rules · newest snapshot 4 h ago");
    // The newest snapshot alone would hide the stale ones: the oldest is named once it is more than a day behind.
    expect(policyHeadline({ catalog: [...catalog, policy("e", "c4", 5, "2026-09-19T06:00:00Z")], sources: 2, loading: false, now })).toBe("3 containers · 2 sources · 975 rules · newest snapshot 4 h ago · oldest 29 d ago");
    expect(policyHeadline({ catalog: [], sources: 0, loading: true, now })).toBe("Reading stored policy snapshots…");
    expect(policyHeadline({ catalog: [], sources: 0, loading: false, now })).toBe("0 containers · 0 sources · 0 rules · collected UNKNOWN");
  });

  it("has four Policy tabs, grouped Browse · Judge · Trace (UI review 2026-11-05, C2)", () => {
    expect(POLICY_TAB_GROUPS.map((g) => g.group)).toEqual(["Browse", "Judge", "Trace"]);
    expect(POLICY_TAB_GROUPS.flatMap((g) => g.tabs)).toEqual(["Rules & Containers", "Objects", "Hygiene", "Changes"]);
  });
});

const GUIDANCE = { summary: "Review password policy", steps: ["Open vendor settings"], cli: null, references: [], caution: "Review account access" };
const OVERVIEW = { total_firewalls: 105, evaluated_firewalls: 102, assured_compliance_pct: 28.9, evidence_coverage_pct: 82.5, observed_compliance_pct: 34.7,
  critical_deficiencies: 172, data_gaps: 428, control_checks: 2448,
  frameworks: [{ framework: "CIS Benchmark", score_pct: 28.9, total_controls: 2448, pass_count: 708, fail_count: 1312, data_unavailable_count: 428 }] };
const control = (id: string, status: "PASS" | "FAIL") => ({
  control_id: id, title: `Control ${id}`, description: null, family: "AC", severity: "HIGH", category: "Authentication", frameworks: [{ framework: "CIS", reference: "2.1.1" }],
  status, compliance_pct: 40, target_device_count: 62, pass_count: status === "PASS" ? 62 : 40, fail_count: status === "PASS" ? 0 : 22, data_unavailable_count: 0, affected_devices: [],
});
function stubCompliance() {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.includes("/api/v2/compliance/controls/") ? {
      control_id: url.split("/").pop(), title: `Control ${url.split("/").pop()}`, severity: "HIGH", status: "FAIL", rationale: "Reduces credential exposure", frameworks: [],
      expected: [{ vendor: "check_point", text: "at least 5" }],
      devices: [{ device_id: "d1", hostname: "FW-TANGO-04", vendor: "check_point", status: "FAIL", observed_value: "3", message: "Too low", guidance: GUIDANCE }],
    } : url.includes("/compliance/overview") ? OVERVIEW : { controls: [control("c1", "FAIL"), control("c2", "PASS")] }), { status: 200 }))));
}

describe("Compliance control drawer is addressable (roadmap 3.6)", () => {
  it("opens the control named by ?control= once the controls are read, and shows the headline from the overview", async () => {
    window.history.replaceState(null, "", "/?screen=compliance&control=c2");
    stubCompliance();
    render(<ComplianceScreen />);
    expect(await screen.findByRole("button", { name: "Close control detail" })).toBeInTheDocument();
    expect(await screen.findByText("Too low")).toBeInTheDocument();
    expect(document.querySelector(".MuiDrawer-paper")?.textContent).toContain("Control c2");
    expect(screen.getByText("34.7% of evidenced controls pass, 17.5% have no evidence · 2,448 checks · 102 of 105 firewalls · 172 critical failing · re-check time not recorded")).toBeInTheDocument();
  });

  it("writes ?control= when a row opens, removes it on close, and closes on the browser's Back", async () => {
    stubCompliance();
    render(<ComplianceScreen />);
    fireEvent.click(await screen.findByText("Control c1"));
    await screen.findByText("Too low");
    expect(new URLSearchParams(window.location.search).get("control")).toBe("c1");
    expect(screen.getByRole("button", { name: "Copy link" })).toBeInTheDocument();
    act(() => { window.history.back(); });
    await act(async () => { window.dispatchEvent(new PopStateEvent("popstate")); });
    await waitFor(() => expect(screen.queryByRole("button", { name: "Close control detail" })).toBeNull());
    fireEvent.click(await screen.findByText("Control c1"));
    fireEvent.click(await screen.findByRole("button", { name: "Close control detail" }));
    expect(new URLSearchParams(window.location.search).get("control")).toBeNull();
  });

  it("ignores a control id the list does not carry", async () => {
    window.history.replaceState(null, "", "/?screen=compliance&control=nope");
    stubCompliance();
    render(<ComplianceScreen />);
    await screen.findByText("Control c1");
    expect(screen.queryByRole("button", { name: "Close control detail" })).toBeNull();
  });
});
