import { describe, expect, it } from "vitest";
import type { DeviceInventory, GridMemberView, GridSummaryView } from "../src/auth/adminApi";
import {
  buildGridBlockModel,
  GRID_MEMBER_ROW_CAP,
  gridStandardHref,
  summaryLinesOf,
} from "../src/estate/estateDossierGridModel";

// Synthetic members only (masked pseudonym style); no addresses or serials are written into the fixtures.
function member(name: string, overrides: Partial<GridMemberView> = {}): GridMemberView {
  return {
    virtual_system: name,
    address: null,
    platform: null,
    hardware_type: null,
    hypervisor: null,
    grid_master: false,
    master_candidate: false,
    ha_enabled: false,
    ha_status: null,
    node_status: "WORKING",
    replication: null,
    disk_percent: 40,
    memory_percent: 50,
    cpu_percent: 10,
    db_percent: 5,
    services: [{ service: "DNS", status: "WORKING" }],
    ...overrides,
  };
}

const SUMMARY: GridSummaryView = {
  dns_views: 2,
  dns_views_at_least: false,
  auth_zones: 9,
  auth_zones_at_least: true,
  dhcp_networks: null,
  dhcp_networks_at_least: false,
  dhcp_ranges: 4,
  dhcp_ranges_at_least: false,
  top_networks: null,
  licenses: null,
};

function inv(overrides: Partial<DeviceInventory>): DeviceInventory {
  return {
    device_id: "dev-tango-04",
    collected_at: "2026-11-20T08:00:00Z",
    job: null,
    ...overrides,
  } as DeviceInventory;
}

const ok = (value: DeviceInventory) => ({ state: "ok" as const, value });

describe("estate dossier grid members block model", () => {
  it("caps the member list and prints the overflow as +N more", () => {
    const members = Array.from({ length: GRID_MEMBER_ROW_CAP + 3 }, (_, i) => member(`FW-TANGO-${String(i + 1).padStart(2, "0")}`));
    const m = buildGridBlockModel(ok(inv({ grid_members: members })));
    expect(m.drawn).toBe(true);
    if (!m.drawn) return;
    expect(m.memberCount).toBe(GRID_MEMBER_ROW_CAP + 3);
    expect(m.rows).toHaveLength(GRID_MEMBER_ROW_CAP);
    expect(m.hiddenCount).toBe(3);
    expect(m.headline).toContain(`${GRID_MEMBER_ROW_CAP + 3} grid members`);
  });

  it("shows an unknown member field as UNKNOWN, never OK", () => {
    const m = buildGridBlockModel(
      ok(
        inv({
          grid_members: [
            member("FW-TANGO-04", {
              ha_enabled: true,
              ha_status: null,
              node_status: null,
              disk_percent: null,
              memory_percent: null,
              cpu_percent: null,
              db_percent: null,
              services: [{ service: "NTP", status: "WORKING" }],
            }),
          ],
        }),
      ),
    );
    expect(m.drawn).toBe(true);
    if (!m.drawn) return;
    const row = m.rows[0];
    expect(row.ha).toBe("UNKNOWN");
    expect(row.node).toBe("UNKNOWN");
    expect(row.disk).toBe("UNKNOWN");
    expect(row.memory).toBe("UNKNOWN");
    expect(row.cpu).toBe("UNKNOWN");
    expect(row.db).toBe("UNKNOWN");
    const everything = JSON.stringify(row);
    expect(everything).not.toMatch(/"OK"/);
  });

  it("reports HA off as HA off when HA is disabled, not as a status word", () => {
    const m = buildGridBlockModel(ok(inv({ grid_members: [member("FW-TANGO-05", { ha_enabled: false, ha_status: null })] })));
    if (!m.drawn) throw new Error("expected drawn");
    expect(m.rows[0].ha).toBe("HA off");
  });

  it("no grid evidence at all: block is not drawn, so zero members is never shown as a pass", () => {
    expect(buildGridBlockModel(ok(inv({ grid_members: [], grid_summary: null })))).toEqual({ drawn: false });
    expect(buildGridBlockModel(ok(inv({})))).toEqual({ drawn: false });
  });

  it("summary present with no members and no counts renders the not-collected wording, not zero members", () => {
    const empty: GridSummaryView = {
      dns_views: null,
      dns_views_at_least: false,
      auth_zones: null,
      auth_zones_at_least: false,
      dhcp_networks: null,
      dhcp_networks_at_least: false,
      dhcp_ranges: null,
      dhcp_ranges_at_least: false,
      top_networks: null,
      licenses: null,
    };
    const m = buildGridBlockModel(ok(inv({ grid_members: [], grid_summary: empty })));
    expect(m.drawn).toBe(true);
    if (!m.drawn) return;
    expect(m.noEvidence).toBe(true);
    expect(m.headline).toBe("No grid member evidence");
    expect(m.badge).toBe("unk");
    expect(m.badgeWord).toBe("UNKNOWN");
    expect(m.rows).toHaveLength(0);
    expect(m.summaryLines.every((l) => l.value === "UNKNOWN")).toBe(true);
  });

  it("loading or failed inventory read draws nothing", () => {
    expect(buildGridBlockModel("loading")).toEqual({ drawn: false });
    expect(buildGridBlockModel({ state: "failed" })).toEqual({ drawn: false });
    expect(buildGridBlockModel(null)).toEqual({ drawn: false });
  });

  it("summary counts are each labelled with what they count, and a lower bound keeps its ≥ marker", () => {
    const lines = summaryLinesOf(SUMMARY);
    expect(lines.map((l) => l.label)).toEqual([
      "DNS views (count of views)",
      "Authoritative DNS zones (count of zones)",
      "DHCP networks (count of networks)",
      "DHCP ranges (count of ranges)",
    ]);
    for (const line of lines) expect(line.label).toMatch(/\(count of /);
    expect(lines.map((l) => l.value)).toEqual(["2 views", "≥9 zones", "UNKNOWN", "4 ranges"]);
  });

  it("uncollected member list with summary counts is UNKNOWN, never a zero member count", () => {
    const m = buildGridBlockModel(ok(inv({ grid_members: undefined, grid_summary: { ...SUMMARY, dns_views: 2 } })));
    expect(m.drawn).toBe(true);
    if (!m.drawn) return;
    expect(m.badge).toBe("unk");
    expect(m.membersKnown).toBe(false);
    expect(m.headline).not.toContain("0 grid members");
    expect(m.headline).toBe("Grid member list not collected (UNKNOWN)");
    expect(m.summaryLines.find((l) => l.label.startsWith("DNS views"))?.value).toBe("2 views");
    expect(m.evidenceAt).toBe("2026-11-20T08:00:00Z");
  });

  it("a collected member list is marked membersKnown and pluralises the headline", () => {
    const m = buildGridBlockModel(ok(inv({ grid_members: [member("FW-TANGO-04")], grid_summary: SUMMARY })));
    if (!m.drawn) throw new Error("expected drawn");
    expect(m.membersKnown).toBe(true);
    expect(m.headline).toBe("1 grid member listed in the stored inventory");
  });

  it("an HA field that is not reported is UNKNOWN, never HA off", () => {
    const m = buildGridBlockModel(ok(inv({ grid_members: [member("FW-TANGO-06", { ha_enabled: undefined as unknown as boolean })] })));
    if (!m.drawn) throw new Error("expected drawn");
    expect(m.rows[0].ha).toBe("UNKNOWN");
    expect(m.rows[0].ha).not.toBe("HA off");
  });

  it("evidence stamp is the inventory collected-at time and the source is inventory", () => {
    const m = buildGridBlockModel(ok(inv({ grid_members: [member("FW-TANGO-04")], grid_summary: SUMMARY })));
    if (!m.drawn) throw new Error("expected drawn");
    expect(m.evidenceAt).toBe("2026-11-20T08:00:00Z");
    expect(m.evidenceSource).toBe("inventory");
    expect(m.badgeWord).not.toBe("OK");
  });

  it("links to the standard device view with standard=1 and nothing else", () => {
    expect(gridStandardHref("dev-tango-04")).toBe("/devices/dev-tango-04?standard=1");
  });
});
