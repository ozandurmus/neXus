import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import type { PolicyHygieneSummary, PolicyMetadata, PolicyPage, PolicyRule } from "../src/auth/adminApi";
import {
  POLICY_BROWSER_RULE_CAP,
  capList,
  flattenRules,
  hygieneSummaryView,
  policyUnitOptions,
  ruleMatches,
} from "../src/estate/estatePolicyBrowserModel";

const listPolicies = vi.fn();
const getPolicy = vi.fn();
const getPolicyHygieneSummary = vi.fn();
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    listPolicies: (...args: unknown[]) => listPolicies(...args),
    getPolicy: (...args: unknown[]) => getPolicy(...args),
    getPolicyHygieneSummary: (...args: unknown[]) => getPolicyHygieneSummary(...args),
  };
});

// Synthetic values only (FW-TANGO-04 style names, documentation addresses).
const META: PolicyMetadata = {
  id: "pol-tango-04", sourceId: "src-1", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerId: "c-1",
  containerName: "DG-SYNTH-A", name: "FW-TANGO-04", collectedAt: new Date().toISOString(), artefactRef: "", targets: [], ruleCount: 2,
};

function rule(n: number, name: string, action = "allow", comment = ""): PolicyRule {
  return {
    id: `r${n}`, uuid: `u${n}`, number: n, name, enabled: true,
    source: { refs: [], negated: false }, destination: { refs: [], negated: false },
    service: { refs: [], negated: false }, application: { refs: [], negated: false },
    action, log: "", comment, extras: {},
  };
}

describe("capList", () => {
  it("keeps the first N and labels the rest as +N more", () => {
    const items = Array.from({ length: 53 }, (_, i) => i);
    const capped = capList(items, POLICY_BROWSER_RULE_CAP);
    expect(capped.shown).toHaveLength(50);
    expect(capped.hidden).toBe(3);
    expect(capped.moreLabel).toBe("+3 more");
  });

  it("has no label when nothing is cut", () => {
    const capped = capList([1, 2], 5);
    expect(capped.shown).toEqual([1, 2]);
    expect(capped.moreLabel).toBeNull();
  });

  it("treats a bad cap as zero rather than showing everything", () => {
    expect(capList([1, 2], Number.NaN).shown).toEqual([]);
  });
});

describe("policy browser model", () => {
  it("flattens rules across sections and matches locally on number, name, action and comment", () => {
    const rules = flattenRules([{ rules: [rule(1, "ALLOW-WEB")] }, { rules: [rule(2, "DENY-OLD", "drop", "stale rule")] }]);
    expect(rules).toHaveLength(2);
    expect(rules.filter((r) => ruleMatches(r, "web"))).toHaveLength(1);
    expect(rules.filter((r) => ruleMatches(r, "STALE"))).toHaveLength(1);
    expect(rules.filter((r) => ruleMatches(r, "2"))).toHaveLength(1);
    expect(ruleMatches(rules[0], "   ")).toBe(true);
  });

  it("builds unit options sorted by label, with an UNKNOWN-safe rule count", () => {
    const options = policyUnitOptions([META, { ...META, id: "pol-a", name: "ALPHA", ruleCount: undefined }]);
    expect(options[0].label.startsWith("ALPHA")).toBe(true);
    expect(options[0].ruleCount).toBeNull();
    expect(options[1].ruleCount).toBe(2);
  });

  it("shows PENDING containers without zeros and sums only READY containers", () => {
    const summary: PolicyHygieneSummary = {
      containers: [
        { sourceId: "s", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerId: "c1", containerName: "DG-A", snapshotAt: new Date().toISOString(), state: "READY", rules: 10, counts: { shadowed: 2, unused: 1 } },
        { sourceId: "s", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerId: "c2", containerName: "DG-B", snapshotAt: new Date().toISOString(), state: "PENDING" },
      ],
      totals: { shadowed: 2 }, rules: 10, pending: 1, days: 90,
    };
    const view = hygieneSummaryView(summary);
    expect(view.readyContainers).toBe(1);
    expect(view.pendingContainers).toBe(1);
    expect(view.totals).toEqual([{ finding: "shadowed", rules: 2 }, { finding: "unused", rules: 1 }]);
    expect(view.rows.find((r) => r.kind === "pending")).toBeDefined();
    expect(view.pendingLine).toContain("not counted as zero");
  });

  it("reports an unreadable summary as UNKNOWN, never as OK", () => {
    const view = hygieneSummaryView(undefined);
    expect(view.state).toBe("unknown");
    expect(view.rows).toHaveLength(0);
  });

  it("caps long container lists with +N more", () => {
    const containers = Array.from({ length: 30 }, (_, i) => ({
      sourceId: "s", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerId: `c${i}`, containerName: `DG-${i}`,
      snapshotAt: new Date().toISOString(), state: "PENDING" as const,
    }));
    const view = hygieneSummaryView({ containers, totals: {}, rules: 0, pending: 30, days: 90 });
    expect(view.rows).toHaveLength(25);
    expect(view.moreLabel).toBe("+5 more");
  });
});

describe("EstatePolicyBrowser", () => {
  beforeEach(() => {
    listPolicies.mockReset();
    getPolicy.mockReset();
    getPolicyHygieneSummary.mockReset();
  });

  it("lists the unit's rules capped at 50 and shows +N more and PENDING hygiene", async () => {
    const { EstatePolicyBrowser } = await import("../src/estate/EstatePolicyBrowser");
    listPolicies.mockResolvedValue({ policies: [META], devices: [] });
    const rules = Array.from({ length: 53 }, (_, i) => rule(i + 1, `RULE-SYN-${i + 1}`));
    const page = { metadata: META, sections: [{ id: "s", name: "pre", source: "", parentRuleId: null, rules, total: 53 }], objects: [], page: 0, pageSize: 50, total: 53 } as unknown as PolicyPage;
    getPolicy.mockResolvedValue(page);
    getPolicyHygieneSummary.mockResolvedValue({
      containers: [{ sourceId: "s", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerId: "c1", containerName: "DG-SYNTH-A", snapshotAt: new Date().toISOString(), state: "PENDING" }],
      totals: {}, rules: 0, pending: 1, days: 90,
    });
    render(<EstatePolicyBrowser />);
    await waitFor(() => expect(screen.getByText("+3 more")).toBeTruthy());
    expect(screen.getByText(/PENDING, no counts/)).toBeTruthy();
    expect(screen.getByText("Open in standard view").getAttribute("href")).toContain("standard=1");
    expect(getPolicy).toHaveBeenCalledWith("pol-tango-04", 0, "");
  });

  it("says UNKNOWN when the hygiene summary cannot be read", async () => {
    const { EstatePolicyBrowser } = await import("../src/estate/EstatePolicyBrowser");
    listPolicies.mockResolvedValue({ policies: [], devices: [] });
    getPolicyHygieneSummary.mockRejectedValue(new Error("boom"));
    render(<EstatePolicyBrowser />);
    await waitFor(() => expect(screen.getByText(/Hygiene summary UNKNOWN/)).toBeTruthy());
  });
});
