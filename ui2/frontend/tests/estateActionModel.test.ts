import { describe, expect, it } from "vitest";
import { unitEstateActions, type EstateActionReads, type EstateActionSpec } from "../src/estate/estateActionModel";

const GATEWAY = { deviceId: "dev-tango-04", vendor: "check_point", role: "gateway" };
const ok = <T,>(value: T) => ({ state: "ok" as const, value });

function byKey(actions: readonly EstateActionSpec[], key: EstateActionSpec["key"]): EstateActionSpec {
  const found = actions.find((a) => a.key === key);
  if (!found) throw new Error(`missing ${key}`);
  return found;
}

const ALL_OK: EstateActionReads = {
  staticRouteTargets: ok([{ device_id: "dev-tango-04" }]),
  diagnosticTargets: ok({ targets: [{ deviceId: "dev-tango-04" }], canExecute: true }),
};

describe("unitEstateActions", () => {
  it("lists the three gated actions in display order", () => {
    const actions = unitEstateActions(GATEWAY, ALL_OK);
    expect(actions.map((a) => a.key)).toEqual(["static_routes", "diagnostics", "support_bundle"]);
  });

  it("marks every action checking while its read is in flight, and never available", () => {
    const actions = unitEstateActions(GATEWAY, { staticRouteTargets: null, diagnosticTargets: null });
    expect(byKey(actions, "static_routes").availability).toBe("checking");
    expect(byKey(actions, "static_routes").href).toBeNull();
    expect(byKey(actions, "diagnostics").availability).toBe("checking");
    expect(byKey(actions, "diagnostics").href).toBeNull();
  });

  it("available actions link to the standard view for this unit with standard=1", () => {
    const actions = unitEstateActions(GATEWAY, ALL_OK);
    for (const a of actions) {
      expect(a.availability).toBe("available");
      expect(a.href).toContain("standard=1");
      expect(a.href).toContain("device_id=dev-tango-04");
    }
    expect(byKey(actions, "static_routes").href).toContain("screen=operations");
    expect(byKey(actions, "diagnostics").href).toContain("screen=diagnostics");
    expect(byKey(actions, "support_bundle").href).toContain("screen=support_bundles");
  });

  it("static routes: a unit missing from the target list is unavailable, with the reason", () => {
    const spec = byKey(
      unitEstateActions(GATEWAY, { ...ALL_OK, staticRouteTargets: ok([{ device_id: "dev-other-01" }]) }),
      "static_routes",
    );
    expect(spec.availability).toBe("unavailable");
    expect(spec.reason).toMatch(/not a static-route target/);
    expect(spec.href).toBeNull();
  });

  it("a restricted read means the role does not allow the action", () => {
    const actions = unitEstateActions(GATEWAY, {
      staticRouteTargets: { state: "restricted" },
      diagnosticTargets: { state: "restricted" },
    });
    expect(byKey(actions, "static_routes").availability).toBe("unavailable");
    expect(byKey(actions, "static_routes").reason).toMatch(/role/);
    expect(byKey(actions, "diagnostics").availability).toBe("unavailable");
    expect(byKey(actions, "diagnostics").href).toBeNull();
  });

  it("an unreadable source is UNKNOWN, never available, and keeps the standard-view link", () => {
    const actions = unitEstateActions(GATEWAY, {
      staticRouteTargets: { state: "failed" },
      diagnosticTargets: { state: "failed" },
    });
    const staticSpec = byKey(actions, "static_routes");
    const diagSpec = byKey(actions, "diagnostics");
    expect(staticSpec.availability).toBe("unknown");
    expect(staticSpec.reason).toMatch(/could not be read/);
    expect(staticSpec.href).not.toBeNull();
    expect(diagSpec.availability).toBe("unknown");
    expect(diagSpec.href).not.toBeNull();
  });

  it("diagnostics: a session that can read but not execute is unavailable", () => {
    const spec = byKey(
      unitEstateActions(GATEWAY, {
        ...ALL_OK,
        diagnosticTargets: ok({ targets: [{ deviceId: "dev-tango-04" }], canExecute: false }),
      }),
      "diagnostics",
    );
    expect(spec.availability).toBe("unavailable");
    expect(spec.reason).toMatch(/not run them/);
    expect(spec.href).toBeNull();
  });

  it("diagnostics: a unit with no approved target is unavailable for its vendor", () => {
    const spec = byKey(
      unitEstateActions(GATEWAY, { ...ALL_OK, diagnosticTargets: ok({ targets: [], canExecute: true }) }),
      "diagnostics",
    );
    expect(spec.availability).toBe("unavailable");
    expect(spec.reason).toMatch(/vendor/);
  });

  it("support bundle follows the bundle device rule, not the read reads", () => {
    const panorama = unitEstateActions({ deviceId: "dev-tango-05", vendor: "palo_alto", role: "management_server" }, ALL_OK);
    expect(byKey(panorama, "support_bundle").availability).toBe("available");

    const asaManager = unitEstateActions({ deviceId: "dev-tango-06", vendor: "cisco_asa", role: "management_server" }, ALL_OK);
    expect(byKey(asaManager, "support_bundle").availability).toBe("unavailable");
    expect(byKey(asaManager, "support_bundle").href).toBeNull();

    const unknownVendor = unitEstateActions({ deviceId: "dev-tango-07", vendor: "acme_unknown", role: "appliance" }, ALL_OK);
    expect(byKey(unknownVendor, "support_bundle").availability).toBe("unavailable");
  });
});
