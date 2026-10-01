import { afterEach, expect, it, vi } from "vitest";
import { fullMode, latencyViolation, pathTemplate, validateAllowlist } from "../e2e/latency";
import { requestViolation, sanitizedPath } from "../e2e/requestPath";
afterEach(() => vi.unstubAllEnvs());

it("reports methods and static path words while masking every unknown segment", () => {
  for (const identity of ["synthetic-id", "192.0.2.10", "FW-TANGO-04", "SYNTHETIC-SERIAL-01", "%64evices", "a%2Fb"]) {
    const url = `https://example.invalid/api/v2/devices/${identity}/backups?name=FW-TANGO-04#192.0.2.10`;
    expect(sanitizedPath(url)).toBe("/api/v2/devices/{id}/backups");
    expect(requestViolation("GET", url, 404)).toBe("API GET /api/v2/devices/{id}/backups returned HTTP 404");
    expect(requestViolation("HEAD", url)).toBe("API HEAD /api/v2/devices/{id}/backups request failed");
  }
  expect(sanitizedPath("https://example.invalid/clusters/null/inventory")).toBe("/clusters/{id}/inventory");
  expect(sanitizedPath("https://example.invalid/unknown/192.0.2.10/other")).toBe("/{id}/{id}/{id}");
  expect(sanitizedPath("https://example.invalid/api/v2/jobs/export.csv?token=synthetic#secret")).toBe("/api/v2/jobs/export.csv");
  expect(sanitizedPath("https://example.invalid/?device=synthetic")).toBe("/");
  expect(requestViolation("synthetic-secret", "invalid", 500)).toBe("API {method} /{id} returned HTTP 500");
});

it("enforces a strict 3 second boundary and withholds identities and queries", () => {
  const url = "https://example.invalid/api/v2/jobs/synthetic-id?address=192.0.2.10";
  expect(pathTemplate(url)).toBe("/api/v2/jobs/{id}");
  expect(latencyViolation(url, 3000)).toBeNull();
  expect(latencyViolation(url, 3001)).toBe("API latency budget exceeded: /api/v2/jobs/{id} (3001 ms > 3000 ms)");
  expect(pathTemplate("https://example.invalid/api/private-value/192.0.2.10")).toBe("/api/{unmapped}");
});
it("requires reason, valid expiry, bounded budget and a known unique template", () => {
  const entry = { path: "/api/v2/jobs", reason: "Synthetic load investigation", expires: "2099-01-01", budgetMs: 5000 };
  expect(() => validateAllowlist([entry], "2026-09-30")).not.toThrow();
  for (const change of [{ reason: "" }, { expires: "2026-09-29" }, { expires: "2099-02-31" }, { budgetMs: 3000 }, { path: "/api/{unmapped}" }]) {
    expect(() => validateAllowlist([{ ...entry, ...change }], "2026-09-30")).toThrow();
  }
  expect(() => validateAllowlist([entry, entry])).toThrow();
  expect(latencyViolation("https://example.invalid/api/v2/jobs", 4999, [entry])).toBeNull();
  expect(latencyViolation("https://example.invalid/api/v2/jobs", 5001, [entry])).not.toBeNull();
});
it("defaults to quick, opts into full and rejects misspelled modes", () => {
  vi.stubEnv("NEXUS_E2E_MODE", undefined);
  expect(fullMode()).toBe(false);
  vi.stubEnv("NEXUS_E2E_MODE", "full");
  expect(fullMode()).toBe(true);
  vi.stubEnv("NEXUS_E2E_MODE", "ful");
  expect(() => fullMode()).toThrow();
});
