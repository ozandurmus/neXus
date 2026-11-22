import { describe, expect, it } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { managedThroughOf } from "../src/estate/estateManagedThrough";

const node = (over: Partial<ManagementTreeNode>): ManagementTreeNode => ({
  kind: "gateway",
  device_id: null,
  imported: false,
  ha_role: null,
  enrollment_state: null,
  last_collection_state: null,
  last_collection_at: null,
  category: "device",
  model: null,
  candidate_id: null,
  ack_token: null,
  acknowledged: false,
  acknowledged_reason: null,
  children: [],
  ...over,
});

const TREE_UNDER_NODE: ManagementTree = {
  device_id: "mgmt-tango",
  vendor: "palo_alto",
  run_id: "run-1",
  discovered_at: "2026-11-21T10:00:00Z",
  domains: [
    {
      domain: null,
      nodes: [
        node({
          kind: "management",
          device_id: "mgmt-tango",
          category: "management_appliance",
          hostname: "PANORAMA-TANGO-01",
          children: [node({ device_id: "unit-fw-07", hostname: "FW-ROMEO-07" })],
        }),
      ],
    },
  ],
  counts: {},
};

const TREE_WITHOUT_UNIT: ManagementTree = {
  ...TREE_UNDER_NODE,
  domains: [{ domain: null, nodes: [node({ device_id: "other", hostname: "FW-OTHER" })] }],
};

describe("managedThroughOf", () => {
  it("returns MANAGED with the masked node label when the unit sits under a node", () => {
    const r = managedThroughOf("unit-fw-07", { state: "ok", value: TREE_UNDER_NODE });
    expect(r.state).toBe("MANAGED");
    expect(r.nodeLabel).toBe("PANORAMA-TANGO-01");
    expect(r.headline).toContain("PANORAMA-TANGO-01");
    expect(r.badgeState).toBe("ok");
    expect(r.evidenceAt).toBe("2026-11-21T10:00:00Z");
    expect(r.evidenceSource).toBe("management tree");
    expect(r.detail).toContain("not device runtime truth");
  });

  it("returns UNKNOWN for a rejected read and never OK", () => {
    for (const bad of [{ state: "failed" as const }, { state: "restricted" as const }, { state: "skipped" as const }]) {
      const r = managedThroughOf("unit-fw-07", bad);
      expect(r.state).toBe("UNKNOWN");
      expect(r.badgeState).toBe("unk");
      expect(r.badgeWord).toBe("UNKNOWN");
      expect(r.nodeLabel).toBeNull();
      expect(r.badgeState).not.toBe("ok");
      expect(r.evidenceAt).toBeNull();
    }
  });

  it("returns UNKNOWN while the tree is still loading or missing, never NOT_UNDER_ANY_NODE", () => {
    expect(managedThroughOf("unit-fw-07", "loading").state).toBe("READING");
    expect(managedThroughOf("unit-fw-07", undefined).state).toBe("READING");
    expect(managedThroughOf("unit-fw-07", "loading").badgeState).not.toBe("ok");
  });

  it("returns UNKNOWN, never NOT UNDER A NODE, when a readable tree does not list the unit", () => {
    const r = managedThroughOf("unit-fw-07", { state: "ok", value: TREE_WITHOUT_UNIT });
    expect(r.state).toBe("UNKNOWN");
    expect(r.badgeWord).toBe("UNKNOWN");
    expect(r.badgeWord).not.toBe("NOT UNDER A NODE");
    expect(r.headline).toBe("Unit not listed in the management tree");
    expect(r.nodeLabel).toBeNull();
    expect(r.badgeState).toBe("unk");
  });

  it("returns UNKNOWN when domains is malformed (not an array), never NOT UNDER A NODE", () => {
    const malformed = { ...TREE_UNDER_NODE, domains: "not-a-list" } as unknown as ManagementTree;
    const r = managedThroughOf("unit-fw-07", { state: "ok", value: malformed });
    expect(r.state).toBe("UNKNOWN");
    expect(r.badgeWord).toBe("UNKNOWN");
    expect(r.badgeWord).not.toBe("NOT UNDER A NODE");
    expect(r.headline).toBe("Management tree could not be read");
    expect(r.badgeState).toBe("unk");
  });

  it("keeps NOT_UNDER_ANY_NODE for a unit positively listed at the top level", () => {
    const topLevel: ManagementTree = {
      ...TREE_UNDER_NODE,
      domains: [{ domain: null, nodes: [node({ device_id: "unit-fw-07", hostname: "FW-ROMEO-07" })] }],
    };
    const r = managedThroughOf("unit-fw-07", { state: "ok", value: topLevel });
    expect(r.state).toBe("NOT_UNDER_ANY_NODE");
    expect(r.badgeWord).toBe("NOT UNDER A NODE");
    expect(r.headline).toBe("Not under any management node");
    expect(r.nodeLabel).toBeNull();
  });

  it("never shows a raw address or serial as the node label", () => {
    const withRaw: ManagementTree = {
      ...TREE_UNDER_NODE,
      domains: [{ domain: null, nodes: [node({ device_id: "m", hostname: "PANORAMA-TANGO-01", cluster_member_ref: undefined, children: [node({ device_id: "unit-fw-07" })] })] }],
    };
    const r = managedThroughOf("unit-fw-07", { state: "ok", value: withRaw });
    expect(r.nodeLabel).toBe("PANORAMA-TANGO-01");
  });
});
