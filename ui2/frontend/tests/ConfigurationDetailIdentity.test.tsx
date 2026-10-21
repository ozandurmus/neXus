import { expect, it } from "vitest";
import type { DeviceSummary } from "../src/auth/adminApi";
import { identityTiles } from "../src/screens/ConfigurationDetail";

const device = (extra: Partial<DeviceSummary> = {}) => ({
  device_id: "device-1", hostname: "FW-TANGO-04", vendor_hint: "check_point", role: "gateway",
  cluster_member_ref: null, ha_role: null, model: null, platform_family: null, management_ip: null,
  enrollment_state: "ENROLLED", virtual_systems: null, ...extra,
} as DeviceSummary);

it("reports Standalone for a device without an HA role or cluster", () => {
  const tile = identityTiles(device(), null).find(({ label }) => label === "HA role");
  expect(tile?.value).toBe("Standalone");
  expect(tile?.absent).toBeUndefined();
});

it("keeps not reported for a cluster member without an HA role", () => {
  const tile = identityTiles(device({ cluster_member_ref: "CLS-ROMEO-01" }), null).find(({ label }) => label === "HA role");
  expect(tile?.value).toBeNull();
  expect(tile?.absent).toBe("not reported");
});

// Post-release review 2026-10-20 (L4): the Enrollment cell says the Registry's word, not the raw token.
it("words the enrollment state as the Registry does", () => {
  const word = (state: string) => identityTiles(device({ enrollment_state: state }), null).find(({ label }) => label === "Enrollment")?.value;
  expect(word("ENROLLED")).toBe("Enrolled");
  expect(word("DRAFT")).toBe("Registered, not confirmed");
  expect(word("SUSPENDED")).toBe("Suspended");
  expect(identityTiles(device({ enrollment_state: "" }), null).find(({ label }) => label === "Enrollment")?.value).toBeNull();
});
