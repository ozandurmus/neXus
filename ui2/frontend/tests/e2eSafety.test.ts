// @vitest-environment node
import { describe, expect, it, afterEach, vi } from "vitest";
import {
  hasCanaryToken, isErrorAlert, isWarningAlert, failingAlerts, isAiview, readAllowed, installReadOnlyGuard,
  platformHealthIndicatorViolation, platformStatusViolations, type PlatformStatusSnapshot,
} from "../e2e/safety";
import { createHash } from "node:crypto";
import { readFileSync, statSync, unlinkSync } from "node:fs";
import { machineSetup } from "../e2e/global-setup";
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

  it("allows same-origin read-only app requests, never cross-origin requests (including fonts/styles) or writes", () => {
    for (const path of ["/session/status", "/jobs/synthetic-job/transcript", "/devices"]) {
      for (const method of ["GET", "HEAD"]) expect(readAllowed(method, origin + path, origin)).toBe(true);
      for (const method of ["POST", "PUT", "PATCH", "DELETE", "OPTIONS"]) expect(readAllowed(method, origin + path, origin)).toBe(false);
    }
    for (const path of ["/session/logout", "/session/refresh", "/session/status", "/login", "/login/resolve"]) {
      expect(readAllowed("POST", origin + path, origin)).toBe(false);
    }
    expect(readAllowed("GET", "https://elsewhere.invalid/session/status", origin)).toBe(false);
    for (const resource of ["font", "stylesheet"]) {
      expect(readAllowed("GET", origin + "/assets/synthetic-asset", origin, resource)).toBe(true);
      expect(readAllowed("GET", "https://elsewhere.invalid/synthetic-asset", origin, resource)).toBe(false);
      expect(readAllowed("POST", "https://elsewhere.invalid/synthetic-asset", origin, resource)).toBe(false);
    }
  });

  it("refuses missing, wrong, unauthenticated and password-change roles", () => {
    for (const value of [null, {}, { role_tokens: "role:replay_viewer" }, { roles: ["role:replay_viewer"] },
      { role_tokens: ["role:operator"] }, { role_tokens: ["role:replay_viewer"], authenticated: false },
      { role_tokens: ["role:replay_viewer"], must_change_password: true }]) expect(isAiview(value)).toBe(false);
    expect(isAiview({ role_tokens: ["role:replay_viewer"] })).toBe(true);
  });

  it("matches only hashed canary values without printing them", () => {
    const values = ["192.0.2.10", "SYNTHETIC-SERIAL-01"];
    const digests = new Set(values.map((value) => createHash("sha256").update(value).digest("hex")));
    for (const value of values) expect(hasCanaryToken(`Device ${value} visible`, digests)).toBe(true);
    expect(hasCanaryToken("Device 198.51.100.10 visible", digests)).toBe(false);
    expect(hasCanaryToken("Masked 203.0.113.11 visible", digests)).toBe(false);
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

  it("obtains machine state with a mocked request and saves a private browser cookie", async () => {
    vi.stubEnv("NEXUS_E2E_MACHINE_TOKEN", "synthetic-token");
    vi.stubEnv("NEXUS_E2E_BASE_URL", "http://example.invalid:8080");
    const post = vi.fn().mockResolvedValue({ status: () => 200, json: async () => ({ csrf_token: "synthetic-csrf" }),
      headers: () => ({ "set-cookie": "ui2_session=synthetic-cookie; Path=/; HttpOnly; Secure; SameSite=Strict" }) });
    const dispose = vi.fn();
    const api = { newContext: async () => ({ post, dispose }) };
    try {
      await machineSetup(api as never);
      const state = JSON.parse(readFileSync(statePath(), "utf8"));
      expect(post).toHaveBeenCalledWith(expect.stringContaining("/internal/machine-session"),
        expect.objectContaining({ headers: { "X-Nexus-Machine-Token": "synthetic-token" } }));
      expect(state.cookies[0]).toMatchObject({ domain: "example.invalid", secure: false });
      expect(statSync(statePath()).mode & 0o777).toBe(0o600);
      expect(dispose).toHaveBeenCalledOnce();
    } finally {
      unlinkSync(statePath());
    }
  });
});

it("accepts informational read-only notices but rejects warnings, errors and unknown alerts", () => {
  expect(isErrorAlert("MuiAlert-root MuiAlert-standardInfo")).toBe(false);
  expect(isErrorAlert("MuiAlert-filledSuccess")).toBe(false);
  for (const classes of ["", "MuiAlert-standardError", "MuiAlert-outlinedWarning"]) expect(isErrorAlert(classes)).toBe(true);
});

it("lets stated operational warnings through but fails operational errors, reasonless warnings, unknown styles and unmarked alerts", () => {
  const warn = "MuiAlert-root MuiAlert-standardWarning";
  const outlinedWarn = "MuiAlert-root MuiAlert-outlinedWarning";
  const error = "MuiAlert-root MuiAlert-standardError";
  expect(isWarningAlert(warn)).toBe(true);
  expect(isWarningAlert(outlinedWarn)).toBe(true);
  expect(isWarningAlert(error)).toBe(false);
  expect(isWarningAlert("")).toBe(false);

  // Legitimate operational warnings with a stated reason pass.
  expect(failingAlerts([
    { className: warn, statusAlert: "operational", text: "Disk warning: artefact_store 84% used (warning)." },
    { className: outlinedWarn, statusAlert: "operational", text: "policy: 2 quarantined permits or task leases." },
  ])).toEqual([]);
  // Informational and success alerts never fail.
  expect(failingAlerts([{ className: "MuiAlert-standardInfo", statusAlert: null, text: "" }])).toEqual([]);
  // Error-severity alerts fail even when marked operational (e.g. disk critical or quota exceeded).
  expect(failingAlerts([{ className: error, statusAlert: "operational", text: "Disk critical: artefact_store 94% used (critical)." }])).toHaveLength(1);
  // Reasonless operational warnings fail.
  expect(failingAlerts([{ className: warn, statusAlert: "operational", text: "" }])).toHaveLength(1);
  expect(failingAlerts([{ className: warn, statusAlert: "operational", text: "   " }])).toHaveLength(1);
  expect(failingAlerts([{ className: warn, statusAlert: "operational" }])).toHaveLength(1);
  // Unknown alert styles fail closed even when marked operational.
  expect(failingAlerts([{ className: "", statusAlert: "operational", text: "Disk warning: synthetic" }])).toHaveLength(1);
  // Unmarked warnings/errors and other markers fail.
  expect(failingAlerts([{ className: warn, statusAlert: null, text: "Unmarked warning" }])).toHaveLength(1);
  expect(failingAlerts([{ className: error, statusAlert: null, text: "Load failed" }])).toHaveLength(1);
  expect(failingAlerts([{ className: error, statusAlert: "other", text: "Other error" }])).toHaveLength(1);
  expect(failingAlerts([
    { className: warn, statusAlert: "operational", text: "Disk warning: artefact_store 84% used (warning)." },
    { className: error, statusAlert: null, text: "System status could not be read (status 503)" },
  ])).toHaveLength(1);
});

describe("Platform status and health indicator rules", () => {
  const healthySnapshot = (over: Partial<PlatformStatusSnapshot> = {}): PlatformStatusSnapshot => ({
    hasPanel: true,
    hasStorageSection: true,
    servicePodsHeading: "Service pods (3)",
    observedUnknown: false,
    indicator: { state: "ok", ariaLabel: "neXus status: OK. All 3 service pods running and ready." },
    summary: { label: "neXus OK", reason: "All 3 service pods running and ready.", text: "neXus OK All 3 service pods running and ready." },
    alerts: [],
    ...over,
  });

  it("passes healthy and WARN/ATTENTION (degraded) states when a reason is stated", () => {
    expect(platformStatusViolations(healthySnapshot())).toEqual([]);
    // Disk usage warning keeps the top-bar light OK and shows a stated operational warning alert.
    expect(platformStatusViolations(healthySnapshot({
      alerts: [{ className: "MuiAlert-root MuiAlert-standardWarning", statusAlert: "operational", text: "Disk warning: root 84% used (warning)." }],
    }))).toEqual([]);
    // Operations warning (quarantined permits) degrades the light with a stated reason.
    expect(platformStatusViolations(healthySnapshot({
      indicator: { state: "degraded", ariaLabel: "neXus status: degraded. 2 permits or task leases quarantined; collection may be blocked." },
      summary: { label: "neXus degraded", reason: "2 permits or task leases quarantined; collection may be blocked.", text: "neXus degraded 2 permits or task leases quarantined; collection may be blocked." },
      alerts: [{ className: "MuiAlert-root MuiAlert-standardWarning", statusAlert: "operational", text: "policy: 2 quarantined permits or task leases." }],
    }))).toEqual([]);
    // Unavailable pod view shows UNKNOWN pod count, degraded light with stated reason, and operational warning alert.
    expect(platformStatusViolations(healthySnapshot({
      servicePodsHeading: "Service pods (UNKNOWN)",
      indicator: { state: "degraded", ariaLabel: "neXus status: degraded. Service status cannot be read: no Kubernetes service account token." },
      summary: null,
      alerts: [{ className: "MuiAlert-root MuiAlert-standardWarning", statusAlert: "operational", text: "no Kubernetes service account token" }],
    }))).toEqual([]);
  });

  it("fails on error states, missing panel/sections, reasonless warnings, and UNKNOWN shown as healthy", () => {
    // Error state on the indicator (`down`) or operational error alert.
    expect(platformHealthIndicatorViolation({ state: "down", ariaLabel: "neXus status: down. neXus could not read its own status (status 503)." }))
      .toContain("error state (down)");
    expect(platformStatusViolations(healthySnapshot({
      alerts: [{ className: "MuiAlert-root MuiAlert-standardError", statusAlert: "operational", text: "Disk critical: artefact_store 95% used (critical)." }],
    }))).toEqual(expect.arrayContaining([expect.stringContaining("error, unmarked, or reasonless alert")]));

    // Missing panel, Storage section, Service pods section, summary or indicator.
    expect(platformStatusViolations(healthySnapshot({ hasPanel: false }))).toContain("Platform status panel is missing");
    expect(platformStatusViolations(healthySnapshot({ hasStorageSection: false }))).toContain("Platform status Storage section is missing");
    expect(platformStatusViolations(healthySnapshot({ servicePodsHeading: null }))).toContain("Platform status Service pods section is missing");
    expect(platformStatusViolations(healthySnapshot({ summary: null }))).toContain("Platform status health summary is missing");
    expect(platformStatusViolations(healthySnapshot({ indicator: null }))).toContain("Platform health indicator is missing");

    // Reasonless degraded state or placeholder "no reason given".
    expect(platformHealthIndicatorViolation({ state: "degraded", ariaLabel: "neXus status: degraded. " })).toContain("no stated reason");
    expect(platformHealthIndicatorViolation({ state: "degraded", ariaLabel: "neXus status: degraded. Service status cannot be read: no reason given." })).toContain("no stated reason");
    expect(platformStatusViolations(healthySnapshot({
      indicator: { state: "degraded", ariaLabel: "neXus status: degraded. 1 service pod not ready: svc-a (Pending)." },
      summary: { label: "neXus degraded", reason: "", text: "neXus degraded" },
    }))).toEqual(expect.arrayContaining([expect.stringContaining("no stated reason")]));

    // UNKNOWN shown as healthy fails in every form.
    expect(platformHealthIndicatorViolation({ state: "unknown", ariaLabel: "neXus status: unknown. Synthetic." })).toContain("invalid or unknown state");
    expect(platformHealthIndicatorViolation({ state: "ok", ariaLabel: "neXus status: OK. Status is UNKNOWN." })).toContain("UNKNOWN");
    expect(platformStatusViolations(healthySnapshot({
      servicePodsHeading: "Service pods (UNKNOWN)",
      summary: null,
      alerts: [{ className: "MuiAlert-root MuiAlert-standardWarning", statusAlert: "operational", text: "pod status unavailable" }],
    }))).toEqual(expect.arrayContaining([expect.stringContaining("service pods are UNKNOWN")]));
    expect(platformStatusViolations(healthySnapshot({ observedUnknown: true })))
      .toEqual(expect.arrayContaining([expect.stringContaining("observation time is UNKNOWN")]));
    expect(platformStatusViolations(healthySnapshot({
      summary: { label: "neXus OK", reason: "UNKNOWN pods", text: "neXus OK UNKNOWN pods" },
    }))).toEqual(expect.arrayContaining([expect.stringContaining("alongside UNKNOWN")]));
    // Summary / indicator contradiction fails.
    expect(platformStatusViolations(healthySnapshot({
      indicator: { state: "degraded", ariaLabel: "neXus status: degraded. 1 permit quarantined." },
    }))).toEqual(expect.arrayContaining([expect.stringContaining("disagrees")]));
  });
});
