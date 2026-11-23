import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type {
  ConfigurationDeviationEntry,
  ConfigurationIndexEntry,
  ConfigurationOverride,
} from "../src/auth/adminApi";
import { EstateDossierConfigurationBlock } from "../src/estate/EstateDossierConfigurationBlock";
import {
  CONFIG_CHANGES_CAP,
  CONFIG_OVERRIDES_CAP,
  CONFIG_SECTIONS_CAP,
  projectDossierConfiguration,
  type DeviceConfigurationDetail,
} from "../src/estate/estateDossierConfigurationModel";

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses).
const DEVICE_ID = "fw-tango-04";
const NOW = new Date("2026-11-22T12:00:00.000Z");
const TWO_HOURS_AGO = new Date(NOW.getTime() - 2 * 3_600_000).toISOString();
const HASH = ["a1b2c3d4e5f6", "00112233445566778899"].join("");

function makeSnapshot(overrides: Partial<DeviceConfigurationDetail> = {}): DeviceConfigurationDetail {
  return {
    device_id: DEVICE_ID,
    collected_at: TWO_HOURS_AGO,
    vendor: "check_point",
    read_kind: "gaia-clish-show-configuration",
    canonical_hash: HASH,
    change_state: "unchanged",
    change_deviation_summary: null,
    withheld_line_count: 3,
    sanitized_text_available: true,
    text_readable: true,
    index: [
      { context: "physical", section: "System", source: "local", entry_count: 4, has_override: false },
      { context: "physical", section: "Interfaces", source: "tpl", entry_count: 12, has_override: true },
    ],
    overrides: [],
    supplementary_runs: [],
    ...overrides,
  };
}

describe("EstateDossierConfigurationBlock", () => {
  it("renders UNKNOWN badge and 'not collected' evidence stamp when configuration read failed or has collected_at: null on a non-appliance firewall", () => {
    const { unmount } = render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{ state: "failed" }}
        now={NOW}
      />,
    );

    let block = screen.getByTestId("dossier-configuration-block");
    expect(block.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(within(block).getByText("UNKNOWN")).toBeTruthy();
    expect(within(block).getByText(/Configuration read failed — UNKNOWN/)).toBeTruthy();
    expect(block.querySelector('[data-evidence-stamp="a0"]')?.textContent).toContain("not collected");
    expect(block.textContent).not.toMatch(/\bOK\b|\bAligned\b/);
    unmount();

    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="palo_alto"
        role="gateway"
        configuration={{ state: "ok", value: makeSnapshot({ collected_at: null, change_state: null, index: [] }) }}
        now={NOW}
      />,
    );

    block = screen.getByTestId("dossier-configuration-block");
    expect(block.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(within(block).getByText("UNKNOWN")).toBeTruthy();
    expect(within(block).getByText(/No configuration snapshot collected yet — UNKNOWN/)).toBeTruthy();
    expect(block.querySelector('[data-evidence-stamp="a0"]')?.textContent).toContain("not collected");
    expect(block.textContent).not.toMatch(/\bOK\b|\bAligned\b/);
  });

  it("renders 'Not applicable' badge when role === 'appliance' and collected_at === null", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId="dev-bravo-02"
        vendor="infoblox"
        role="appliance"
        configuration={{ state: "ok", value: makeSnapshot({ collected_at: null, change_state: null, index: [] }) }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.querySelector('[data-state-badge="na"]')).not.toBeNull();
    expect(within(block).getByText("Not applicable")).toBeTruthy();
    expect(within(block).getByText(/Appliance — configuration collection does not target management appliances/)).toBeTruthy();
    expect(block.querySelector('[data-evidence-stamp="a0"]')?.textContent).toContain("not collected");
  });

  it("renders loading and restricted states with honest badges", () => {
    const loadingModel = projectDossierConfiguration({ state: "loading" }, { vendor: "check_point", role: "gateway" });
    expect(loadingModel.badge).toEqual({ state: "unk", word: "Loading" });
    expect(loadingModel.summaryText).toBe("Reading configuration snapshot…");

    const restrictedModel = projectDossierConfiguration(
      { state: "restricted" },
      { vendor: "check_point", role: "gateway" },
    );
    expect(restrictedModel.badge).toEqual({ state: "na", word: "Restricted" });
    expect(restrictedModel.summaryText).toBe("Restricted for this role.");
  });

  it("renders withheld count as UNKNOWN (never 0) when withheld_line_count is null", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{ state: "ok", value: makeSnapshot({ withheld_line_count: null }) }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.textContent).toContain("secret-bearing line count UNKNOWN");
    expect(block.textContent).not.toContain("0 secret-bearing lines withheld");
    expect(block.textContent).not.toContain("secret-bearing lines withheld");
  });

  it("renders a real withheld_line_count of 0 as '0 secret-bearing lines withheld'", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{ state: "ok", value: makeSnapshot({ withheld_line_count: 0 }) }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.textContent).toContain("0 secret-bearing lines withheld");
    expect(block.textContent).not.toContain("UNKNOWN (");
  });

  it("renders 'entries UNKNOWN' instead of a partial total when an indexed section has no entry_count", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{
          state: "ok",
          value: makeSnapshot({
            index: [
              { context: "physical", section: "System", source: "local", entry_count: 4, has_override: false },
              { context: "physical", section: "Interfaces", source: "tpl", has_override: false } as ConfigurationIndexEntry,
            ],
          }),
        }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.textContent).toContain("2 sections indexed · entries UNKNOWN ·");
    expect(block.textContent).not.toMatch(/\d+ entr(y|ies) ·/);
    expect(block.textContent).not.toContain("4 entries ·");
    const list = screen.getByTestId("dossier-config-sections-list");
    expect(list.textContent).toContain("entries UNKNOWN");
    expect(list.textContent).not.toMatch(/undefined entries|null entries/);
  });

  it("renders 'entries UNKNOWN' for a section whose entry_count is null, never 'null entries'", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{
          state: "ok",
          value: makeSnapshot({
            index: [
              { context: "physical", section: "System", source: "local", entry_count: null, has_override: false } as unknown as ConfigurationIndexEntry,
            ],
          }),
        }}
        now={NOW}
      />,
    );

    const list = screen.getByTestId("dossier-config-sections-list");
    expect(list.textContent).toContain("entries UNKNOWN");
    expect(list.textContent).not.toMatch(/undefined entries|null entries/);
    expect(screen.getByTestId("dossier-configuration-block").textContent).not.toMatch(/\bnull\b|\bundefined\b/);
  });

  it("renders sections, entries and local overrides as UNKNOWN (never 0) and never 'Aligned' when index and overrides are not reported", () => {
    for (const absent of [
      { index: null, overrides: null },
      { index: undefined, overrides: undefined },
    ]) {
      const { unmount } = render(
        <EstateDossierConfigurationBlock
          deviceId={DEVICE_ID}
          vendor="check_point"
          role="gateway"
          configuration={{
            state: "ok",
            value: makeSnapshot({ change_state: "unchanged", ...absent }),
          }}
          now={NOW}
        />,
      );

      const block = screen.getByTestId("dossier-configuration-block");
      expect(block.textContent).toContain("sections UNKNOWN");
      expect(block.textContent).toContain("entries UNKNOWN");
      expect(block.textContent).toContain("local overrides UNKNOWN");
      expect(block.textContent).not.toMatch(/0 sections indexed|0 entries|0 local overrides/);
      expect(block.textContent).not.toContain("No configuration sections indexed");
      expect(block.textContent).toContain("Configuration sections UNKNOWN (not reported in this snapshot).");
      expect(block.querySelector('[data-state-badge="unk"]')).not.toBeNull();
      expect(block.querySelector('[data-state-badge="ok"]')).toBeNull();
      expect(block.textContent).not.toContain("Aligned");
      unmount();
    }
  });

  it("renders real empty index and overrides arrays as reported zeros", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{
          state: "ok",
          value: makeSnapshot({ change_state: "unchanged", index: [], overrides: [] }),
        }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.textContent).toContain("0 sections indexed · 0 entries · 0 changed sections · 0 local overrides");
    expect(block.textContent).toContain("No configuration sections indexed in this snapshot.");
    expect(block.querySelector('[data-state-badge="ok"]')?.textContent).toContain("Aligned");
  });

  it("renders indexed sections with unit words, hash prefix, EvidenceStamp, and standard=1 configuration link", () => {
    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="check_point"
        role="gateway"
        configuration={{ state: "ok", value: makeSnapshot() }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.querySelector('[data-state-badge="ok"]')?.textContent).toContain("Aligned");
    expect(block.textContent).toContain("2 sections indexed · 16 entries · 0 changed sections · 0 local overrides");
    expect(block.textContent).toContain("Read kind: gaia-clish-show-configuration");
    expect(block.textContent).toContain("Hash: a1b2c3d4e5f6");
    expect(block.textContent).toContain("3 secret-bearing lines withheld");

    const stamp = block.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    expect(stamp?.getAttribute("data-evidence-stamp")).toBe("a1");
    expect(stamp?.textContent).toContain("configuration");

    const sectionsList = within(block).getByTestId("dossier-config-sections-list");
    expect(sectionsList.textContent).toContain("System");
    expect(sectionsList.textContent).toContain("4 entries");
    expect(sectionsList.textContent).toContain("Interfaces");
    expect(sectionsList.textContent).toContain("12 entries");
    expect(within(sectionsList).getByText("Override")).toBeTruthy();

    const link = within(block).getByRole("link", { name: "Open configuration (standard view) →" });
    expect(link.getAttribute("href")).toBe("?screen=inventory&device_id=fw-tango-04&tab=configuration&standard=1");
  });

  it("filters FortiGate indexed sections through FORTIGATE_SECTIONS while keeping all sections for Check Point, Palo Alto, and Cisco ASA", () => {
    const mixedIndex: readonly ConfigurationIndexEntry[] = [
      { context: "root", section: "system global", source: "local", entry_count: 5, has_override: false },
      { context: "root", section: "system interface", source: "local", entry_count: 10, has_override: false },
      { context: "root", section: "firewall policy", source: "local", entry_count: 99, has_override: false },
      { context: "root", section: "vpn ipsec phase1-interface", source: "local", entry_count: 7, has_override: false },
    ];

    const fgtModel = projectDossierConfiguration(
      { state: "ok", value: makeSnapshot({ vendor: "fortinet", index: mixedIndex }) },
      { vendor: "fortinet", role: "gateway" },
    );
    expect(fgtModel.totalSectionCount).toBe(2);
    expect(fgtModel.totalEntryCount).toBe(15);
    expect(fgtModel.sectionsShown.map((s) => s.section)).toEqual(["system global", "system interface"]);

    for (const vendor of ["check_point", "palo_alto", "cisco_asa"] as const) {
      const otherModel = projectDossierConfiguration(
        { state: "ok", value: makeSnapshot({ vendor, index: mixedIndex }) },
        { vendor, role: "gateway" },
      );
      expect(otherModel.totalSectionCount).toBe(4);
      expect(otherModel.totalEntryCount).toBe(121);
    }
  });

  it("renders run-to-run changed sections with major category labels and old/new entry counts, local overrides, and MoreFooter when counts exceed caps", () => {
    const sections: ConfigurationIndexEntry[] = Array.from({ length: CONFIG_SECTIONS_CAP + 2 }, (_, i) => ({
      context: "vsys1",
      section: `Section-${i + 1}`,
      source: i === 0 ? "local" : "tpl",
      entry_count: i + 1,
      has_override: i === 0,
    }));

    const changed: ConfigurationDeviationEntry[] = Array.from({ length: CONFIG_CHANGES_CAP + 3 }, (_, i) => ({
      context: "vsys1",
      section: `ChangedSection-${i + 1}`,
      kind: i === 0 ? "MODIFIED" : i === 1 ? "ADDED" : "REMOVED",
      old_count: i === 1 ? null : 4,
      new_count: i === 2 ? null : 1,
      category: i === 0 ? "interfaces" : i === 1 ? "routing" : null,
    }));

    const overrides: ConfigurationOverride[] = Array.from({ length: CONFIG_OVERRIDES_CAP + 1 }, (_, i) => ({
      context: "vsys1",
      category: "network",
      element_path: `/network/interface/ethernet/entry[@name='ethernet1/${i + 1}']`,
      panorama_source: i === 0 ? "TPL-CORE-01" : null,
    }));

    render(
      <EstateDossierConfigurationBlock
        deviceId={DEVICE_ID}
        vendor="palo_alto"
        role="gateway"
        configuration={{
          state: "ok",
          value: makeSnapshot({
            vendor: "palo_alto",
            change_state: "changed",
            index: sections,
            change_deviation_summary: { status: "OK", entries: changed },
            overrides,
          }),
        }}
        now={NOW}
      />,
    );

    const block = screen.getByTestId("dossier-configuration-block");
    expect(block.querySelector('[data-state-badge="watch"]')?.textContent).toContain("Changed");

    const changesList = within(block).getByTestId("dossier-config-changes-list");
    expect(changesList.textContent).toContain("vsys1 · ChangedSection-1");
    expect(changesList.textContent).toContain("Major · Interfaces");
    expect(changesList.textContent).toContain("Modified");
    expect(changesList.textContent).toContain("4 entries → 1 entry");
    expect(changesList.textContent).toContain("Major · Routing");
    expect(changesList.textContent).toContain("none → 1 entry");
    expect(changesList.textContent).toContain("Minor");
    expect(changesList.textContent).toContain("4 entries → none");

    const overridesList = within(block).getByTestId("dossier-config-overrides-list");
    expect(overridesList.textContent).toContain("Panorama: TPL-CORE-01");
    expect(overridesList.textContent).toContain("Local");

    expect(within(block).getByTestId("dossier-config-sections-more").textContent).toBe("+2 more sections");
    expect(within(block).getByTestId("dossier-config-changes-more").textContent).toBe("+3 more changed sections");
    expect(within(block).getByTestId("dossier-config-overrides-more").textContent).toBe("+1 more overrides");
  });

  it("supports changed_sections as well as entries in change_deviation_summary and first_run badge state", () => {
    const firstRun = projectDossierConfiguration(
      {
        state: "ok",
        value: makeSnapshot({
          change_state: "first_run",
          change_deviation_summary: null,
          overrides: [],
        }),
      },
      { vendor: "check_point", role: "gateway" },
    );
    expect(firstRun.badge).toEqual({ state: "ok", word: "First run" });

    const withChangedSections = projectDossierConfiguration(
      {
        state: "ok",
        value: makeSnapshot({
          change_state: "changed",
          change_deviation_summary: {
            changed_sections: [
              { context: "physical", section: "DNS", kind: "MODIFIED", old_count: 1, new_count: 2, category: null },
            ],
          },
        }),
      },
      { vendor: "check_point", role: "gateway" },
    );
    expect(withChangedSections.badge).toEqual({ state: "watch", word: "Changed" });
    expect(withChangedSections.totalChangedCount).toBe(1);
    expect(withChangedSections.changedShown[0]).toMatchObject({
      context: "physical",
      section: "DNS",
      kindWord: "Modified",
      severityWord: "Minor",
      badgeState: "na",
      oldCountText: "1 entry",
      newCountText: "2 entries",
    });
  });
});
