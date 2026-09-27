// @vitest-environment node
import { describe, expect, it, afterEach, vi } from "vitest";
import { hasPrivateAddress, isAiview, readAllowed, installReadOnlyGuard } from "../e2e/safety";
import type { BrowserContext, Route } from "@playwright/test";
import { baseURL, statePath } from "../e2e/settings.mjs";

afterEach(() => vi.unstubAllEnvs());

describe("E2E safety boundaries", () => {
  const origin = "https://example.invalid";
  it("installs a catch-all route that aborts writes and records a test failure even if the app catches it", async () => {
    const context = { route: vi.fn(), routeWebSocket: vi.fn() };
    const report = vi.fn();
    await installReadOnlyGuard(context as unknown as BrowserContext, origin, report);
    expect(context.route.mock.calls[0][0]).toBe("**/*");
    const handler = context.route.mock.calls[0][1] as (route: Route) => Promise<void>;
    const route = {
      request: () => ({ method: () => "POST", url: () => origin + "/devices/synthetic-device/backup/collect", resourceType: () => "fetch" }),
      abort: vi.fn(), continue: vi.fn(), fetch: vi.fn(),
    };
    await handler(route as unknown as Route);
    expect(route.abort).toHaveBeenCalledWith("blockedbyclient");
    expect(route.continue).not.toHaveBeenCalled();
    expect(route.fetch).not.toHaveBeenCalled();
    expect(report).toHaveBeenCalledOnce();
    expect(report.mock.calls[0][0]).toContain("Read-only guard aborted");
  });

  it("aborts a changed or expired session instead of fulfilling it into the product", async () => {
    const context = { route: vi.fn(), routeWebSocket: vi.fn() };
    const report = vi.fn();
    await installReadOnlyGuard(context as unknown as BrowserContext, origin, report);
    const handler = context.route.mock.calls[0][1] as (route: Route) => Promise<void>;
    const route = {
      request: () => ({ method: () => "GET", url: () => origin + "/session/status", resourceType: () => "fetch" }),
      abort: vi.fn(), fulfill: vi.fn(),
      fetch: vi.fn().mockResolvedValue({ status: () => 200, json: async () => ({ role_tokens: ["role:operator"] }) }),
    };
    await handler(route as unknown as Route);
    expect(route.fetch).toHaveBeenCalledWith({ maxRedirects: 0 });
    expect(route.abort).toHaveBeenCalledWith("blockedbyclient");
    expect(route.fulfill).not.toHaveBeenCalled();
    expect(report).toHaveBeenCalledWith("Session is no longer an active aiview session");
  });

  it("allows read-only app requests and passive external fonts/styles, never external API calls or writes", () => {
    for (const path of ["/session/status", "/jobs/synthetic-job/transcript", "/devices"]) {
      for (const method of ["GET", "HEAD"]) expect(readAllowed(method, origin + path, origin)).toBe(true);
      for (const method of ["POST", "PUT", "PATCH", "DELETE", "OPTIONS"]) expect(readAllowed(method, origin + path, origin)).toBe(false);
    }
    for (const path of ["/session/logout", "/session/refresh", "/session/status", "/login", "/login/resolve"]) {
      expect(readAllowed("POST", origin + path, origin)).toBe(false);
    }
    expect(readAllowed("GET", "https://elsewhere.invalid/session/status", origin)).toBe(false);
    for (const resource of ["font", "stylesheet"]) {
      expect(readAllowed("GET", "https://elsewhere.invalid/synthetic-asset", origin, resource)).toBe(true);
      expect(readAllowed("POST", "https://elsewhere.invalid/synthetic-asset", origin, resource)).toBe(false);
    }
  });

  it("refuses missing, wrong, unauthenticated and password-change roles", () => {
    for (const value of [null, {}, { role_tokens: "role:replay_viewer" }, { roles: ["role:replay_viewer"] },
      { role_tokens: ["role:operator"] }, { role_tokens: ["role:replay_viewer"], authenticated: false },
      { role_tokens: ["role:replay_viewer"], must_change_password: true }]) expect(isAiview(value)).toBe(false);
    expect(isAiview({ role_tokens: ["role:replay_viewer"] })).toBe(true);
  });

  it("detects every RFC 1918 boundary without treating documentation ranges as private", () => {
    // Construct classification inputs from octets; never embed environment addresses.
    for (const octets of [[10, 0, 0, 0], [10, 255, 255, 255], [172, 16, 0, 0], [172, 31, 255, 255], [192, 168, 0, 1]]) {
      expect(hasPrivateAddress(`Address ${octets.join(".")}/24`)).toBe(true);
    }
    for (const value of ["192.0.2.10", "198.51.100.10", "203.0.113.10", [172, 15, 0, 1].join("."),
      [172, 32, 0, 1].join("."), [10, 999, 0, 1].join("."), "FW-TANGO-04"]) expect(hasPrivateAddress(value)).toBe(false);
  });

  it("requires an explicit credential-free deployment origin", () => {
    for (const value of ["", "invalid", "file:///tmp/synthetic", "https://example.invalid/path", "https://example.invalid?x=1"]) {
      vi.stubEnv("NEXUS_E2E_BASE_URL", value);
      expect(() => baseURL()).toThrow();
    }
    vi.stubEnv("NEXUS_E2E_BASE_URL", origin);
    expect(baseURL()).toBe(origin);
  });

  it("refuses session state inside the checkout", () => {
    vi.stubEnv("NEXUS_E2E_STATE", "e2e-results/session.json");
    expect(() => statePath()).toThrow("outside the repository");
  });
});
