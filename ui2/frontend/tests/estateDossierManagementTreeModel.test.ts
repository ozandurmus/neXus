import { describe, expect, it } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { domainCounts, notImported } from "../src/screens/ManagementTreePanel";
import {
  buildManagementTreeBlockModel,
  MANAGEMENT_TREE_ROW_CAP,
} from "../src/estate/estateDossierManagementTreeModel";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).

const node = (over: Partial<ManagementTreeNode>): ManagementTreeNode => ({
  kind: "PALO_ALTO_DEVICE",
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
  hostname: "FW-TANGO-04",
  ...over,
});

const TWO_DOMAINS: ManagementTree = {
  device_id: "dev-mgmt-tango",
  vendor: "check_point",
  run_id: "run-1",
  discovered_at: "2026-11-21T10:00:00Z",
  domains: [
    {
      domain: "DOMAIN-ALPHA",
      nodes: [
        node({
          kind: "PLAIN_HIGH_AVAILABILITY_CLUSTER",
          category: "cluster",
          cluster_member_ref: "CLS-ROMEO-01",
          hostname: undefined,
          children: [
            node({ cluster_member_ref: "FW-TANGO-04", kind: "PLAIN_CLUSTER_MEMBER" }),
            node({ cluster_member_ref: "FW-JULIET-06", kind: "PLAIN_CLUSTER_MEMBER", ack_token: "tok-a", acknowledged: false }),
          ],
        }),
        node({ hostname: "FW-BRAVO-02", ack_token: "tok-b", acknowledged: true }),
      ],
    },
    {
      domain: "DOMAIN-BRAVO",
      nodes: [node({ virtual_system: "VS-ECHO-03", category: "virtual_system", ack_token: "tok-c", acknowledged: false })],
    },
  ],
  counts: {},
};

describe("buildManagementTreeBlockModel", () => {
  it("loading tree is UNKNOWN with its own text", () => {
    const m = buildManagementTreeBlockModel("loading");
    expect(m.state).toBe("UNKNOWN");
    expect(m.headline).toBe("Management tree not read yet");
    expect(m.evidenceAt).toBeNull();
    expect(m.domains).toEqual([]);
  });

  it("null or undefined tree is UNKNOWN, never a negative claim", () => {
    for (const tree of [null, undefined]) {
      const m = buildManagementTreeBlockModel(tree);
      expect(m.state).toBe("UNKNOWN");
      expect(m.headline).toBe("Management tree not read yet");
    }
  });

  it("failed read is UNKNOWN and never reads as not managed", () => {
    for (const state of ["failed", "restricted", "skipped"] as const) {
      const m = buildManagementTreeBlockModel({ state });
      expect(m.state).toBe("UNKNOWN");
      expect(m.headline).toBe("Management tree could not be read");
      expect(m.headline.toLowerCase()).not.toContain("not managed");
      expect(m.notImportedTotal).toBeNull();
    }
  });

  it("malformed domains list is UNKNOWN", () => {
    const m = buildManagementTreeBlockModel({ state: "ok", value: { ...TWO_DOMAINS, domains: null as never } });
    expect(m.state).toBe("UNKNOWN");
  });

  it("two domains yield the counts from domainCounts and the not-imported total", () => {
    const m = buildManagementTreeBlockModel({ state: "ok", value: TWO_DOMAINS });
    expect(m.state).toBe("OK");
    expect(m.evidenceAt).toBe("2026-11-21T10:00:00Z");
    expect(m.domains).toHaveLength(2);
    const [alpha, bravo] = m.domains;
    const expectedAlpha = domainCounts(TWO_DOMAINS.domains[0].nodes);
    expect(alpha.counts).toEqual({ clusters: expectedAlpha.clusters, gateways: expectedAlpha.gateways, vs: expectedAlpha.vs });
    expect(alpha.notImported).toBe(expectedAlpha.missing);
    expect(alpha.nodeTotal).toBe(4);
    expect(bravo.counts.vs).toBe(1);
    expect(bravo.notImported).toBe(1);
    expect(m.notImportedTotal).toBe(notImported(TWO_DOMAINS.domains));
    expect(m.notImportedTotal).toBe(2);
  });

  it("a domain with no HA collection says HA is not read", () => {
    const m = buildManagementTreeBlockModel({ state: "ok", value: TWO_DOMAINS });
    expect(m.domains[0].haLine).toBe("Domain HA not read");
  });

  it("caps listed rows per domain at 12 and keeps the full node total", () => {
    const fifteen = Array.from({ length: 15 }, (_, i) => node({ hostname: `FW-SYNTH-${String(i).padStart(2, "0")}` }));
    const m = buildManagementTreeBlockModel({ state: "ok", value: { ...TWO_DOMAINS, domains: [{ domain: "DOMAIN-CHARLIE", nodes: fifteen }] } });
    expect(m.domains[0].rows).toHaveLength(MANAGEMENT_TREE_ROW_CAP);
    expect(m.domains[0].nodeTotal).toBe(15);
  });
});
