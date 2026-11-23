import { createElement } from "react";
import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import type { PolicyInstallState, PolicyInstallStatusResponse, PolicyInstallStatusRow } from "../src/auth/adminApi";
import { POLICY_INSTALL_STATES, stateTone } from "../src/screens/PolicyInstallMonitoringPanel";
import { EstatePolicyInstalls } from "../src/estate/EstatePolicyInstalls";
import {
  POLICY_INSTALLS_SHOWN,
  buildPolicyInstallsModel,
  policyInstallBadgeState,
  policyInstallTone,
  policyInstallWord,
  policyInstallsHref,
  stateRoleToBadgeState,
} from "../src/estate/estatePolicyInstallsModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getPolicyInstallStatus: vi.fn(),
}));

const getPolicyInstallStatusMock = vi.mocked(adminApi.getPolicyInstallStatus);

const NOW = new Date("2026-11-22T12:00:00Z");

function row(id: string, overrides: Partial<PolicyInstallStatusRow> = {}): PolicyInstallStatusRow {
  return {
    row_id: id,
    device_id: null,
    hostname: `FW-TANGO-0${id}`,
    context: "",
    vendor: "check_point",
    container_id: "c1",
    container_name: "DOM-ALPHA-01",
    package_id: "p1",
    package_name: "PKG-ALPHA-01",
    state: "INSTALLED",
    ha_parity: null,
    policy_name: null,
    installed_at: "2026-11-22T10:00:00Z",
    last_change_at: null,
    reason: "",
    ...overrides,
  };
}

describe("policy install word, tone and BadgeState", () => {
  it.each(POLICY_INSTALL_STATES.map((s) => [s]))("maps %s to its word and the standard tone", (state) => {
    expect(policyInstallWord(state)).toBe(state);
    expect(policyInstallTone(policyInstallWord(state))).toBe(stateTone(state));
  });

  it("uses the standard stateTone mapping for known states (not a copy)", () => {
    expect(policyInstallTone("INSTALLED")).toBe("ok");
    expect(policyInstallTone("OUT_OF_DATE")).toBe("warn");
    expect(policyInstallTone("NOT_INSTALLED")).toBe("bad");
  });

  it("maps StateRole and policy install states to Estate BadgeState", () => {
    expect(stateRoleToBadgeState("bad")).toBe("act");
    expect(stateRoleToBadgeState("warn")).toBe("watch");
    expect(stateRoleToBadgeState("ok")).toBe("ok");
    expect(stateRoleToBadgeState("neutral")).toBe("na");
    expect(stateRoleToBadgeState("unknown")).toBe("unk");

    expect(policyInstallBadgeState("NOT_INSTALLED")).toBe("act");
    expect(policyInstallBadgeState("OUT_OF_DATE")).toBe("watch");
    expect(policyInstallBadgeState("UNSAVED")).toBe("watch");
    expect(policyInstallBadgeState("INSTALLED")).toBe("ok");
    expect(policyInstallBadgeState("SAVED")).toBe("ok");
    expect(policyInstallBadgeState("NOT_APPLICABLE")).toBe("na");
    expect(policyInstallBadgeState("UNKNOWN")).toBe("unk");
    expect(policyInstallBadgeState("PENDING")).toBe("unk");
  });

  it("PENDING is shown as PENDING and never as ok", () => {
    expect(policyInstallWord("PENDING")).toBe("PENDING");
    expect(policyInstallTone("PENDING")).toBe("unknown");
    expect(policyInstallTone("PENDING")).not.toBe("ok");
    expect(policyInstallBadgeState("PENDING")).toBe("unk");
    expect(policyInstallBadgeState("PENDING")).not.toBe("ok");
  });

  it("a state the client does not know becomes UNKNOWN, never ok or zero", () => {
    expect(policyInstallWord("SOMETHING_NEW")).toBe("UNKNOWN");
    expect(policyInstallWord(null)).toBe("UNKNOWN");
    expect(policyInstallWord(undefined)).toBe("UNKNOWN");
    expect(policyInstallTone("UNKNOWN")).toBe("unknown");
    expect(policyInstallBadgeState("UNKNOWN")).toBe("unk");
  });
});

describe("buildPolicyInstallsModel", () => {
  it("PENDING and UNKNOWN rows are counted as not evaluated, never as OK", () => {
    const model = buildPolicyInstallsModel(
      [row("1", { state: "PENDING" as never }), row("2", { state: "UNKNOWN" }), row("3", { state: "INSTALLED" })],
      3,
      NOW,
    );
    expect(model.notEvaluatedCount).toBe(2);
    expect(model.lines[0].word).toBe("PENDING");
    expect(model.lines[0].tone).toBe("unknown");
    expect(model.lines[0].badgeState).toBe("unk");
    expect(model.lines[1].word).toBe("UNKNOWN");
    expect(model.lines[1].badgeState).toBe("unk");
    expect(model.lines[2].tone).toBe("ok");
    expect(model.lines[2].badgeState).toBe("ok");
  });

  it("maps PolicyInstallStatusResponse.counts into ordered labelled state counts with BadgeState", () => {
    const counts: Record<PolicyInstallState, number> = {
      INSTALLED: 4,
      OUT_OF_DATE: 1,
      NOT_INSTALLED: 2,
      SAVED: 3,
      UNSAVED: 1,
      NOT_APPLICABLE: 2,
      UNKNOWN: 1,
    };
    const model = buildPolicyInstallsModel([row("1")], 14, NOW, counts);
    expect(model.stateCountsKnown).toBe(true);
    expect(model.stateCounts).toEqual([
      { state: "INSTALLED", badgeState: "ok", count: 4, label: "4 INSTALLED" },
      { state: "OUT_OF_DATE", badgeState: "watch", count: 1, label: "1 OUT_OF_DATE" },
      { state: "NOT_INSTALLED", badgeState: "act", count: 2, label: "2 NOT_INSTALLED" },
      { state: "SAVED", badgeState: "ok", count: 3, label: "3 SAVED" },
      { state: "UNSAVED", badgeState: "watch", count: 1, label: "1 UNSAVED" },
      { state: "NOT_APPLICABLE", badgeState: "na", count: 2, label: "2 NOT_APPLICABLE" },
      { state: "UNKNOWN", badgeState: "unk", count: 1, label: "1 UNKNOWN" },
    ]);
  });

  it("reports stateCountsKnown false when counts is missing or malformed (never inventing zero)", () => {
    for (const malformed of [
      undefined,
      null,
      {},
      [] as unknown as Partial<Record<PolicyInstallState, number>>,
      { INSTALLED: Number.NaN },
      { INSTALLED: -1 },
      { INSTALLED: "4" as unknown as number },
    ]) {
      const model = buildPolicyInstallsModel([row("1")], 1, NOW, malformed);
      expect(model.stateCountsKnown).toBe(false);
      expect(model.stateCounts).toEqual([]);
    }
  });

  it("maps HA parity through the standard parityLabel and BadgeState and shows nothing when absent", () => {
    const model = buildPolicyInstallsModel(
      [
        row("1", { ha_parity: "DIFFER" }),
        row("2", { ha_parity: "EQUAL" }),
        row("3", { ha_parity: "UNKNOWN" }),
        row("4", { ha_parity: null }),
      ],
      4,
      NOW,
    );
    expect(model.lines[0].haWord).toBe("checksums differ");
    expect(model.lines[0].haTone).toBe("warn");
    expect(model.lines[0].haBadgeState).toBe("watch");
    expect(model.lines[1].haWord).toBe("checksums equal");
    expect(model.lines[1].haBadgeState).toBe("ok");
    expect(model.lines[2].haWord).toBe("checksums unknown");
    expect(model.lines[2].haBadgeState).toBe("unk");
    expect(model.lines[3].haWord).toBeNull();
    expect(model.lines[3].haTone).toBeNull();
    expect(model.lines[3].haBadgeState).toBeNull();
  });

  it("carries vendorLabel, contextLabel, policyName, lastChangeAt, reason and computes newestEvidenceAt", () => {
    const model = buildPolicyInstallsModel(
      [
        row("1", {
          vendor: "check_point",
          context: " 3 ",
          policy_name: " POL-ALPHA-01 ",
          installed_at: "2026-11-22T08:00:00Z",
          last_change_at: "2026-11-22T11:30:00Z",
          reason: " Direct gateway cpstat corroborates package ",
        }),
        row("2", {
          vendor: "palo_alto",
          context: "   ",
          policy_name: "   ",
          installed_at: "2026-11-22T10:00:00Z",
          last_change_at: null,
          reason: "",
        }),
      ],
      2,
      NOW,
    );

    expect(model.lines[0].vendorLabel).toBe("Check Point");
    expect(model.lines[0].contextLabel).toBe("VS 3");
    expect(model.lines[0].policyName).toBe("POL-ALPHA-01");
    expect(model.lines[0].lastChangeAt).toBe("2026-11-22T11:30:00Z");
    expect(model.lines[0].reason).toBe("Direct gateway cpstat corroborates package");

    expect(model.lines[1].vendorLabel).toBe("Palo Alto");
    expect(model.lines[1].contextLabel).toBeNull();
    expect(model.lines[1].policyName).toBeNull();
    expect(model.lines[1].lastChangeAt).toBeNull();
    expect(model.lines[1].reason).toBeNull();

    expect(model.newestEvidenceAt).toBe("2026-11-22T11:30:00Z");
  });

  it("gives the install timestamp an age and marks a missing install time as UNKNOWN", () => {
    const model = buildPolicyInstallsModel(
      [row("1"), row("2", { installed_at: null })],
      2,
      NOW,
    );
    expect(model.lines[0].installedAge).toBe("2 h ago");
    expect(model.lines[1].installedAt).toBeNull();
    expect(model.lines[1].installedAge).toBeNull();
  });

  it("caps the shown rows at 12 and prints the correct remainder", () => {
    const rows = Array.from({ length: 30 }, (_, i) => row(String(i)));
    const model = buildPolicyInstallsModel(rows, 30, NOW);
    expect(POLICY_INSTALLS_SHOWN).toBe(12);
    expect(model.lines).toHaveLength(12);
    expect(model.moreCount).toBe(18);
  });

  it("uses the server total for the remainder when more rows exist than were returned", () => {
    const rows = Array.from({ length: 12 }, (_, i) => row(String(i)));
    const model = buildPolicyInstallsModel(rows, 45, NOW);
    expect(model.lines).toHaveLength(12);
    expect(model.moreCount).toBe(33);
  });

  it("prints no remainder at exactly 12 rows and one at 13", () => {
    expect(buildPolicyInstallsModel(Array.from({ length: 12 }, (_, i) => row(String(i))), 12, NOW).moreCount).toBe(0);
    expect(buildPolicyInstallsModel(Array.from({ length: 13 }, (_, i) => row(String(i))), 13, NOW).moreCount).toBe(1);
  });

  it("an unknown total (undefined or null) is never the row count: totalKnown false, no remainder", () => {
    for (const total of [undefined, null] as const) {
      const model = buildPolicyInstallsModel([row("1"), row("2")], total, NOW);
      expect(model.totalKnown).toBe(false);
      expect(model.totalCount).toBeNull();
      expect(model.moreCount).toBe(0);
      expect(model.lines).toHaveLength(2);
    }
  });

  it("a known total keeps totalKnown true and the count it always had", () => {
    const model = buildPolicyInstallsModel([row("1"), row("2")], 2, NOW);
    expect(model.totalKnown).toBe(true);
    expect(model.totalCount).toBe(2);
    expect(model.moreCount).toBe(0);
  });

  it("links to the standard install panel with standard=1", () => {
    const href = policyInstallsHref();
    expect(href).toContain("screen=operations");
    expect(href).toContain("tab=policy-install");
    expect(href).toContain("standard=1");
  });
});

describe("EstatePolicyInstalls component", () => {
  beforeEach(() => {
    getPolicyInstallStatusMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders StateBadge with unk on a failed read alongside the UNKNOWN message and never OK", async () => {
    getPolicyInstallStatusMock.mockRejectedValueOnce(new Error("synthetic read failure"));
    const { container } = render(createElement(EstatePolicyInstalls));

    expect(await screen.findByText("Policy install status could not be read: UNKNOWN.")).toBeInTheDocument();
    const unkBadge = container.querySelector('[data-state-badge="unk"]');
    expect(unkBadge).not.toBeNull();
    expect(unkBadge).toHaveTextContent("UNKNOWN");
    expect(container.querySelector('[data-state-badge="ok"]')).toBeNull();
  });

  it("renders state summary counts, summary and per-row EvidenceStamps, StateBadges, vendor/context/policy/reason, MoreFooter and standard=1 link", async () => {
    const twoHoursAgo = new Date(Date.now() - 2 * 3_600_000).toISOString();
    const oneHourAgo = new Date(Date.now() - 3_600_000).toISOString();
    const response: PolicyInstallStatusResponse = {
      counts: {
        INSTALLED: 4,
        OUT_OF_DATE: 1,
        NOT_INSTALLED: 1,
        SAVED: 0,
        UNSAVED: 0,
        NOT_APPLICABLE: 1,
        UNKNOWN: 1,
      },
      total: 15,
      page: 0,
      page_size: 200,
      masked: true,
      contract_ref: "synthetic",
      rows: [
        row("4", {
          hostname: "FW-TANGO-04",
          vendor: "check_point",
          context: "3",
          state: "INSTALLED",
          ha_parity: "EQUAL",
          policy_name: "POL-ALPHA-01",
          installed_at: twoHoursAgo,
          last_change_at: oneHourAgo,
          reason: "Direct gateway cpstat corroborates package",
        }),
        row("6", {
          hostname: "FW-JULIET-06",
          vendor: "palo_alto",
          context: "",
          state: "OUT_OF_DATE",
          ha_parity: "DIFFER",
          policy_name: "POL-BRAVO-02",
          installed_at: twoHoursAgo,
          last_change_at: null,
          reason: "Panorama shared policy status is OUT_OF_SYNC",
        }),
        row("2", {
          hostname: "FW-BRAVO-02",
          vendor: "cisco_asa",
          context: "",
          state: "UNKNOWN",
          ha_parity: null,
          policy_name: null,
          installed_at: null,
          last_change_at: null,
          reason: "needs a read not approved yet",
        }),
        ...Array.from({ length: 10 }, (_, i) =>
          row(`extra-${i}`, {
            hostname: `FW-ROMEO-0${i + 1}`,
            vendor: "fortinet",
            state: "NOT_APPLICABLE",
            installed_at: twoHoursAgo,
          }),
        ),
      ],
    };
    getPolicyInstallStatusMock.mockResolvedValueOnce(response);

    const { container } = render(createElement(EstatePolicyInstalls));
    const countsStrip = await screen.findByTestId("policy-installs-state-counts");

    // Summary counts strip shows non-zero states with StateBadge and carries an EvidenceStamp
    expect(within(countsStrip).getByText("4 INSTALLED")).toBeInTheDocument();
    expect(within(countsStrip).getByText("1 OUT_OF_DATE")).toBeInTheDocument();
    expect(within(countsStrip).getByText("1 NOT_INSTALLED")).toBeInTheDocument();
    expect(within(countsStrip).getByText("1 NOT_APPLICABLE")).toBeInTheDocument();
    expect(within(countsStrip).getByText("1 UNKNOWN")).toBeInTheDocument();
    expect(within(countsStrip).queryByText(/SAVED/)).toBeNull();
    expect(countsStrip.querySelector('[data-state-count="UNKNOWN"] [data-state-badge="unk"]')).not.toBeNull();
    expect(countsStrip.querySelector("[data-evidence-stamp]")).not.toBeNull();

    // Rows capped at 12, each carrying a StateBadge and EvidenceStamp
    const items = container.querySelectorAll("li");
    expect(items).toHaveLength(POLICY_INSTALLS_SHOWN);
    for (const item of Array.from(items)) {
      expect(item.querySelector("[data-state-badge]")).not.toBeNull();
      expect(item.querySelector("[data-evidence-stamp]")).not.toBeNull();
    }

    // Row 1: Check Point, VS 3, POL-ALPHA-01, reason, HA parity EQUAL -> ok
    expect(items[0]).toHaveTextContent("FW-TANGO-04");
    expect(items[0]).toHaveTextContent("VS 3");
    expect(items[0]).toHaveTextContent("Check Point");
    expect(items[0]).toHaveTextContent("POL-ALPHA-01");
    expect(items[0]).toHaveTextContent("Direct gateway cpstat corroborates package");
    expect(items[0]).toHaveTextContent("checksums equal");
    expect(items[0].querySelector('[data-state-badge="ok"]')).not.toBeNull();

    // Row 2: Palo Alto, OUT_OF_DATE -> watch, HA parity DIFFER -> watch
    expect(items[1]).toHaveTextContent("FW-JULIET-06");
    expect(items[1]).toHaveTextContent("Palo Alto");
    expect(items[1]).toHaveTextContent("POL-BRAVO-02");
    expect(items[1]).toHaveTextContent("Panorama shared policy status is OUT_OF_SYNC");
    expect(items[1]).toHaveTextContent("checksums differ");
    expect(items[1].querySelectorAll('[data-state-badge="watch"]').length).toBeGreaterThanOrEqual(2);

    // Row 3: UNKNOWN state -> data-state-badge="unk" (never ok), null installed_at -> "install time UNKNOWN"
    expect(items[2]).toHaveTextContent("FW-BRAVO-02");
    expect(items[2]).toHaveTextContent("Cisco ASA");
    expect(items[2]).toHaveTextContent("needs a read not approved yet");
    expect(items[2]).toHaveTextContent("install time UNKNOWN");
    expect(items[2].querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(items[2].querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(items[2].querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();

    // MoreFooter (+3 more policy installs) and standard=1 links
    expect(screen.getByText("+3 more policy installs")).toBeInTheDocument();
    const links = screen.getAllByRole("link", { name: "Open in standard view" });
    expect(links.length).toBe(POLICY_INSTALLS_SHOWN);
    expect(links[0].getAttribute("href")).toContain("standard=1");
  });

  it("prints state counts not reported when counts is missing from the response", async () => {
    getPolicyInstallStatusMock.mockResolvedValueOnce({
      counts: undefined as never,
      total: 1,
      page: 0,
      page_size: 200,
      masked: true,
      contract_ref: "synthetic",
      rows: [row("1")],
    });

    render(createElement(EstatePolicyInstalls));
    const countsStrip = await screen.findByTestId("policy-installs-state-counts");
    expect(countsStrip).toHaveTextContent("state counts not reported");
    expect(countsStrip.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });
});
