import { describe, expect, it } from "vitest";
import {
  buildDossierClusterModel,
  DOSSIER_CLUSTER_ROW_CAP,
  type DossierClusterRead,
} from "../src/estate/estateDossierClusterModel";

// Synthetic names only (FW-TANGO-04 style); addresses from the documentation ranges.
const ok = (value: unknown): DossierClusterRead => ({ ok: true, value });

function iface(name: string) {
  return { name, kind: "ethernet", addresses: [], presence: "all", differences: [] };
}

function route(destination: string) {
  return { destination, next_hop: "192.0.2.1", interface: "eth1", protocol: "static", presence: "all", differences: [] };
}

const TWO_CONTEXTS = {
  cluster_member_ref: "CLS-ROMEO-01",
  members: [{ device_id: "FW-TANGO-04", hostname: "FW-TANGO-04" }],
  contexts: [
    { context: "physical", interfaces: [iface("eth1"), iface("eth2")], routes: [route("0.0.0.0/0")] },
    { context: "vs-1", vs_name: "VS-ROMEO-01", interfaces: [iface("eth3")], routes: [route("198.51.100.0/24"), route("203.0.113.0/24")] },
  ],
};

describe("buildDossierClusterModel", () => {
  it("yields per-context interface and route counts for a clustered unit with two contexts", () => {
    const model = buildDossierClusterModel(ok(TWO_CONTEXTS), true);
    expect(model.status).toBe("ok");
    if (model.status !== "ok") return;
    expect(model.contexts).toHaveLength(2);
    expect(model.contexts[0]).toMatchObject({ label: "physical", interfaceCount: 2, routeCount: 1, interfacesMore: 0, routesMore: 0 });
    expect(model.contexts[1]).toMatchObject({ label: "VS-ROMEO-01", interfaceCount: 1, routeCount: 2 });
    expect(model.contexts[1].interfaceNames).toEqual(["eth3"]);
    expect(model.contexts[1].routeDestinations).toEqual(["198.51.100.0/24", "203.0.113.0/24"]);
  });

  it("gives UNKNOWN for a failed read and never a count of 0", () => {
    const model = buildDossierClusterModel({ ok: false }, true);
    expect(model).toEqual({ status: "failed" });
    expect(JSON.stringify(model)).not.toContain("interfaceCount");
  });

  it("gives UNKNOWN for a malformed payload whose interfaces are not an array", () => {
    const payload = { contexts: [{ context: "physical", interfaces: "eth1", routes: [] }] };
    expect(buildDossierClusterModel(ok(payload), true)).toEqual({ status: "malformed" });
    expect(buildDossierClusterModel(ok({ contexts: "physical" }), true)).toEqual({ status: "malformed" });
    expect(buildDossierClusterModel(ok(null), true)).toEqual({ status: "malformed" });
  });

  it("caps each list and reports the exact remainder", () => {
    const names = Array.from({ length: DOSSIER_CLUSTER_ROW_CAP + 2 }, (_, i) => `eth${i + 1}`);
    const payload = { contexts: [{ context: "physical", interfaces: names.map(iface), routes: [] }] };
    const model = buildDossierClusterModel(ok(payload), true);
    expect(model.status).toBe("ok");
    if (model.status !== "ok") return;
    expect(model.contexts[0].interfaceNames).toHaveLength(DOSSIER_CLUSTER_ROW_CAP);
    expect(model.contexts[0].interfacesMore).toBe(2);
    expect(model.contexts[0].interfaceCount).toBe(DOSSIER_CLUSTER_ROW_CAP + 2);
  });

  it("says Not a cluster unit for a non-clustered unit", () => {
    expect(buildDossierClusterModel(null, false)).toEqual({ status: "not_cluster" });
    expect(buildDossierClusterModel(ok(TWO_CONTEXTS), false)).toEqual({ status: "not_cluster" });
  });

  it("is loading while a clustered unit's read is in flight", () => {
    expect(buildDossierClusterModel(null, true)).toEqual({ status: "loading" });
  });

  it("uses the payload's own collection timestamp only when it parses, otherwise null", () => {
    const stamped = buildDossierClusterModel(ok({ ...TWO_CONTEXTS, collected_at: "2026-11-22T10:00:00Z" }), true);
    expect(stamped.status === "ok" && stamped.collectedAt).toBe("2026-11-22T10:00:00Z");
    const bad = buildDossierClusterModel(ok({ ...TWO_CONTEXTS, collected_at: "not-a-time" }), true);
    expect(bad.status === "ok" && bad.collectedAt).toBeNull();
    const absent = buildDossierClusterModel(ok(TWO_CONTEXTS), true);
    expect(absent.status === "ok" && absent.collectedAt).toBeNull();
  });
});
