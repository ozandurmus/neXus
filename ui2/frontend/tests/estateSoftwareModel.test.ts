import { describe, expect, it } from "vitest";
import type { CpInstallEventWithDevice, SoftwareChange } from "../src/auth/adminApi";
import {
  buildEstateSoftwareModel,
  SOFTWARE_CHANGE_ROW_CAP,
  SOFTWARE_INSTALL_EVENT_CAP,
  SOFTWARE_UNIT_CAP,
  softwareChangeSummary,
} from "../src/estate/estateSoftwareModel";

const NOW = new Date("2026-11-22T12:00:00Z");

function change(deviceId: string, daysAgo: number, to = "R81.20", from: string | null = "R81.10"): SoftwareChange {
  const at = new Date(NOW.getTime() - daysAgo * 86_400_000).toISOString();
  return {
    device_id: deviceId,
    vendor: "check_point",
    software_version: to,
    hotfix_level: null,
    first_seen_at: at,
    last_seen_at: at,
    previous_software_version: from,
    previous_hotfix_level: null,
    window_start: null,
    window_end: at,
    source: "inventory",
    hostname: "FW-ZULU-99",
    from_version: from,
    from_hotfix_level: null,
    to_version: to,
    to_hotfix_level: null,
  };
}

const UNITS = new Map([
  ["dev-tango-04", { name: "FW-TANGO-04" }],
  ["dev-bravo-02", { name: "FW-BRAVO-02" }],
]);

const ok = (changes: SoftwareChange[], installEvents: CpInstallEventWithDevice[] = []) =>
  ({ status: "ok", days: 90, changes, installEvents }) as const;

describe("buildEstateSoftwareModel", () => {
  it("counts total and per unit, and says what it counts", () => {
    const m = buildEstateSoftwareModel(
      ok([change("dev-tango-04", 2), change("dev-tango-04", 40), change("dev-bravo-02", 10)]),
      UNITS,
    );
    expect(m.status).toBe("ok");
    expect(m.totalChanges).toBe(3);
    expect(m.headline).toBe("3 version changes in 90 days");
    expect(m.perUnit.map((u) => [u.unitName, u.count])).toEqual([
      ["FW-TANGO-04", 2],
      ["FW-BRAVO-02", 1],
    ]);
    expect(m.perUnit[0].newestAt).toBe(change("x", 2).window_end);
  });

  it("uses the singular for one change", () => {
    const m = buildEstateSoftwareModel(ok([change("dev-bravo-02", 5)]), UNITS);
    expect(m.headline).toBe("1 version change in 90 days");
  });

  it("pluralises the noun for any count other than exactly one (shared plural helper)", () => {
    const one = buildEstateSoftwareModel(ok([change("dev-bravo-02", 5)]), UNITS);
    const many = buildEstateSoftwareModel(ok([change("dev-bravo-02", 5), change("dev-bravo-02", 6)]), UNITS);
    expect(one.headline).toBe("1 version change in 90 days");
    expect(many.headline).toBe("2 version changes in 90 days");
  });

  it("caps rows at 10 and reports the exact number hidden", () => {
    const changes = Array.from({ length: 14 }, (_, i) => change("dev-tango-04", i + 1));
    const m = buildEstateSoftwareModel(ok(changes), UNITS);
    expect(SOFTWARE_CHANGE_ROW_CAP).toBe(10);
    expect(m.rows).toHaveLength(10);
    expect(m.hiddenRows).toBe(4);
    expect(m.totalChanges).toBe(14);
    expect(m.perUnit[0].count).toBe(14);
  });

  it("orders rows newest first by the change's own timestamp", () => {
    const m = buildEstateSoftwareModel(ok([change("dev-bravo-02", 30), change("dev-tango-04", 1)]), UNITS);
    expect(m.rows.map((r) => r.unitName)).toEqual(["FW-TANGO-04", "FW-BRAVO-02"]);
  });

  it("says no changes only when the read succeeded and is empty", () => {
    const m = buildEstateSoftwareModel(ok([]), UNITS);
    expect(m.status).toBe("ok");
    expect(m.headline).toBe("No software changes in 90 days");
    expect(m.totalChanges).toBe(0);
    expect(m.rows).toEqual([]);
  });

  it("never reports a failed or unread list as no changes", () => {
    for (const read of [{ status: "failed" as const }, { status: "not_read" as const }, { status: "loading" as const }]) {
      const m = buildEstateSoftwareModel(read, UNITS);
      expect(m.headline).not.toMatch(/No software changes/);
      expect(m.totalChanges).toBe(0);
      expect(m.rows).toEqual([]);
    }
    expect(buildEstateSoftwareModel({ status: "failed" }, UNITS).headline).toMatch(/could not be read/);
  });

  it("shows the Estate label, never the raw hostname, and a fallback for units not on the wall", () => {
    const m = buildEstateSoftwareModel(ok([change("dev-unknown", 3)]), UNITS);
    expect(m.rows[0].unitName).toBe("Unit not on this Estate wall");
    expect(JSON.stringify(m)).not.toContain("FW-ZULU-99");
  });

  it("formats the change summary with UNKNOWN for a missing previous version", () => {
    expect(softwareChangeSummary({ from_version: null, from_hotfix_level: null, to_version: "R81.20", to_hotfix_level: "Take 119" }))
      .toBe("UNKNOWN to R81.20 (Take 119)");
    expect(softwareChangeSummary({ from_version: "R81.10", from_hotfix_level: null, to_version: "R81.20", to_hotfix_level: null }))
      .toBe("R81.10 to R81.20");
  });

  it("counts install log entries separately from version changes", () => {
    const install: CpInstallEventWithDevice = {
      device_id: "dev-tango-04", hostname: null, kind: "BASE_INSTALL", product_version: "R81.20", jumbo_take: null,
      installed_at_local: "2026-11-01 10:00:00", file_name: "x", first_observed_at: NOW.toISOString(),
    };
    const m = buildEstateSoftwareModel(ok([change("dev-tango-04", 2)], [install]), UNITS);
    expect(m.totalChanges).toBe(1);
    expect(m.installEventLine).toMatch(/^1 Check Point install log entry in 90 days/);
    expect(buildEstateSoftwareModel(ok([]), UNITS).installEventLine).toBeNull();
  });

  it("includes the change window on each row; UNKNOWN when a bound is missing", () => {
    const dated = { ...change("dev-tango-04", 2), window_start: "2026-11-01T08:00:00Z", window_end: "2026-11-02T09:30:00Z" };
    const m = buildEstateSoftwareModel(ok([change("dev-bravo-02", 3), dated]), UNITS);
    const byUnit = Object.fromEntries(m.rows.map((r) => [r.unitName, r.windowText]));
    expect(byUnit["FW-BRAVO-02"]).toBe("UNKNOWN (first version seen)");
    expect(byUnit["FW-TANGO-04"]).toContain(" to ");
    expect(byUnit["FW-TANGO-04"]).not.toContain("UNKNOWN");
  });

  it("caps perUnit at SOFTWARE_UNIT_CAP and keeps the total unit count", () => {
    const units = new Map(Array.from({ length: 13 }, (_, i) => [`dev-u${i}`, { name: `FW-UNIT-${i}` }] as const));
    const changes = Array.from({ length: 13 }, (_, i) => change(`dev-u${i}`, i + 1));
    const m = buildEstateSoftwareModel(ok(changes), units);
    expect(SOFTWARE_UNIT_CAP).toBe(10);
    expect(m.perUnit).toHaveLength(10);
    expect(m.totalUnits).toBe(13);
  });

  it("maps install events into capped, newest-first rows with Estate labels only", () => {
    const installs: CpInstallEventWithDevice[] = Array.from({ length: 12 }, (_, i) => ({
      device_id: i % 2 ? "dev-bravo-02" : "dev-tango-04",
      hostname: "FW-ZULU-99",
      kind: i === 0 ? "BASE_INSTALL" : "JUMBO_INSTALL",
      product_version: "R81.20",
      jumbo_take: i === 0 ? null : 100 + i,
      installed_at_local: `2026-11-${String(i + 1).padStart(2, "0")} 10:00:00`,
      file_name: `install-${i}`,
      first_observed_at: new Date(NOW.getTime() - i * 3_600_000).toISOString(),
    }));
    const m = buildEstateSoftwareModel(ok([], installs), UNITS);
    expect(SOFTWARE_INSTALL_EVENT_CAP).toBe(10);
    expect(m.installRows).toHaveLength(10);
    expect(m.totalInstallEvents).toBe(12);
    expect(m.newestInstallObservedAt).toBe(installs[0].first_observed_at);
    expect(m.installRows[0]).toEqual({
      key: "dev-tango-04-install-0-2026-11-01 10:00:00-0",
      deviceId: "dev-tango-04",
      unitName: "FW-TANGO-04",
      what: "Base install R81.20",
      installedAtLocal: "2026-11-01 10:00:00",
      firstObservedAt: installs[0].first_observed_at,
    });
    expect(m.installRows[1].what).toBe("Jumbo R81.20 Take 101");
    expect(m.installRows[1].unitName).toBe("FW-BRAVO-02");
    expect(JSON.stringify(m)).not.toContain("FW-ZULU-99");
  });

  it("has no install rows and a null newest stamp when the install log is empty", () => {
    const m = buildEstateSoftwareModel(ok([change("dev-tango-04", 2)]), UNITS);
    expect(m.installRows).toEqual([]);
    expect(m.totalInstallEvents).toBe(0);
    expect(m.newestInstallObservedAt).toBeNull();
  });
});
