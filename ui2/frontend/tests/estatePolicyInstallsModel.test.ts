import { describe, expect, it } from "vitest";
import type { PolicyInstallStatusRow } from "../src/auth/adminApi";
import { POLICY_INSTALL_STATES, stateTone } from "../src/screens/PolicyInstallMonitoringPanel";
import {
  POLICY_INSTALLS_SHOWN,
  buildPolicyInstallsModel,
  policyInstallTone,
  policyInstallWord,
  policyInstallsHref,
} from "../src/estate/estatePolicyInstallsModel";

const NOW = new Date("2026-11-22T12:00:00Z");

function row(id: string, overrides: Partial<PolicyInstallStatusRow> = {}): PolicyInstallStatusRow {
  return {
    row_id: id,
    device_id: null,
    hostname: `HOST-${id}`,
    context: "",
    vendor: "check_point",
    container_id: "c1",
    container_name: "Policy A",
    package_id: "p1",
    package_name: "Package 1",
    state: "INSTALLED",
    ha_parity: null,
    policy_name: null,
    installed_at: "2026-11-22T10:00:00Z",
    last_change_at: null,
    reason: "",
    ...overrides,
  };
}

describe("policy install word and tone", () => {
  it.each(POLICY_INSTALL_STATES.map((s) => [s]))("maps %s to its word and the standard tone", (state) => {
    expect(policyInstallWord(state)).toBe(state);
    expect(policyInstallTone(policyInstallWord(state))).toBe(stateTone(state));
  });

  it("uses the standard stateTone mapping for known states (not a copy)", () => {
    expect(policyInstallTone("INSTALLED")).toBe("ok");
    expect(policyInstallTone("OUT_OF_DATE")).toBe("warn");
    expect(policyInstallTone("NOT_INSTALLED")).toBe("bad");
  });

  it("PENDING is shown as PENDING and never as ok", () => {
    expect(policyInstallWord("PENDING")).toBe("PENDING");
    expect(policyInstallTone("PENDING")).toBe("unknown");
    expect(policyInstallTone("PENDING")).not.toBe("ok");
  });

  it("a state the client does not know becomes UNKNOWN, never ok or zero", () => {
    expect(policyInstallWord("SOMETHING_NEW")).toBe("UNKNOWN");
    expect(policyInstallWord(null)).toBe("UNKNOWN");
    expect(policyInstallWord(undefined)).toBe("UNKNOWN");
    expect(policyInstallTone("UNKNOWN")).toBe("unknown");
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
    expect(model.lines[1].word).toBe("UNKNOWN");
    expect(model.lines[2].tone).toBe("ok");
  });

  it("maps HA parity through the standard parityLabel and shows nothing when absent", () => {
    const model = buildPolicyInstallsModel(
      [row("1", { ha_parity: "DIFFER" }), row("2", { ha_parity: null })],
      2,
      NOW,
    );
    expect(model.lines[0].haWord).toBe("checksums differ");
    expect(model.lines[0].haTone).toBe("warn");
    expect(model.lines[1].haWord).toBeNull();
    expect(model.lines[1].haTone).toBeNull();
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

  it("links to the standard install panel with standard=1", () => {
    const href = policyInstallsHref();
    expect(href).toContain("screen=operations");
    expect(href).toContain("tab=policy-install");
    expect(href).toContain("standard=1");
  });
});
