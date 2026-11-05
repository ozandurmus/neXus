import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import {
  BUSINESS_HOURS_ACK_HEADER,
  BUSINESS_HOURS_CONFIRM_REQUIRED,
  requestSystemLogCollect,
  setBusinessHoursConfirm,
  type DeviceSummary,
  type SystemHealth,
  type SystemLogSources,
} from "../src/auth/adminApi";
import { CollectNowButton, DeviceInventoryPanels, buildDeviceCollectMenuItems } from "../src/screens/InventoryPanels";
import { DeviceLogsPanel, describeSystemLogCollectError, describeSystemLogCollectOutcome } from "../src/screens/DeviceLogsPanel";
import { SystemHealthPanel } from "../src/screens/SystemHealthPanel";

const VIEWER_PERMS = ["Devices", "Config", "Compliance"] as const;
const ONBOARDING_ADMIN_PERMS = ["Devices", "Config", "Admin"] as const;
const OPERATION_ADMIN_PERMS = ["Devices", "Config", "Compliance", "Operations"] as const;
const SECURITY_ADMIN_PERMS = ["Devices", "Config", "Compliance", "Operations", "Admin"] as const;

function sessionWith(permissions: readonly string[]): SessionInfo {
  return {
    displayName: "op-1",
    roleTokens: [],
    permissions: [...permissions],
    onSignOut: () => {},
  };
}

function withProviders(node: React.ReactElement, permissions: readonly string[] = OPERATION_ADMIN_PERMS) {
  return (
    <ThemeProvider theme={m3Theme}>
      <SessionContext.Provider value={sessionWith(permissions)}>
        {node}
      </SessionContext.Provider>
    </ThemeProvider>
  );
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "X-Masking-Active": "true" },
  });
}

const CP_GATEWAY: DeviceSummary = {
  device_id: "dev-cp-gw",
  hostname: "FW-TANGO-04",
  vendor_hint: "check_point",
  role: "gateway",
  enrollment_state: "ENROLLED",
  model: "Quantum 6600",
  software_version: "R81.20",
  ha_role: "ACTIVE",
  cluster_member_ref: null,
  management_ip: "192.0.2.14",
  backup_target: true,
};

afterEach(() => {
  setBusinessHoursConfirm(null);
  vi.unstubAllGlobals();
});

describe("Device Collect menu items per role and vendor", () => {
  it("hides all items when the viewer role has neither Collect nor Operations plane", () => {
    const items = buildDeviceCollectMenuItems({
      vendorHint: "check_point",
      role: "gateway",
      enrollmentState: "ENROLLED",
      backupTarget: true,
      permissions: VIEWER_PERMS,
    });
    expect(items).toEqual([]);
  });

  it("shows only Inventory and Configuration for onboarding_admin and hides Backup and System logs", () => {
    const items = buildDeviceCollectMenuItems({
      vendorHint: "check_point",
      role: "gateway",
      enrollmentState: "ENROLLED",
      backupTarget: true,
      permissions: ONBOARDING_ADMIN_PERMS,
    });
    expect(items.map((i) => i.key)).toEqual(["inventory", "configuration"]);
    expect(items.every((i) => !i.disabled)).toBe(true);
  });

  it("shows all four items enabled for operation_admin and security_admin on a Check Point or Palo Alto gateway", () => {
    for (const perms of [OPERATION_ADMIN_PERMS, SECURITY_ADMIN_PERMS]) {
      for (const vendorHint of ["check_point", "palo_alto"]) {
        const items = buildDeviceCollectMenuItems({
          vendorHint,
          role: "gateway",
          enrollmentState: "ENROLLED",
          backupTarget: true,
          permissions: perms,
        });
        expect(items.map((i) => `${i.label}: ${i.description}`)).toEqual([
          "Inventory: reads the device's interfaces, routes and platform facts",
          "Configuration: reads the device's running configuration and settings",
          "Backup: reads and stores a backup archive of the device",
          "System logs: reads the device's recent system log lines",
        ]);
        expect(items.map((i) => i.disabled)).toEqual([false, false, false, false]);
      }
    }
  });

  it("disables unsupported items with plain-language reasons per vendor and device role", () => {
    // 1. Check Point management server: System logs disabled on management servers; Configuration and Backup supported
    const cpMgr = buildDeviceCollectMenuItems({
      vendorHint: "check_point",
      role: "management_server",
      enrollmentState: "ENROLLED",
      backupTarget: true,
      permissions: SECURITY_ADMIN_PERMS,
    });
    expect(cpMgr.find((i) => i.key === "configuration")?.disabled).toBe(false);
    expect(cpMgr.find((i) => i.key === "backup")?.disabled).toBe(false);
    expect(cpMgr.find((i) => i.key === "system_logs")?.disabled).toBe(true);
    expect(cpMgr.find((i) => i.key === "system_logs")?.disabledReason).toContain("disabled on management servers");

    // 2. Palo Alto management server (Panorama): Configuration and System logs disabled on management servers; Backup supported
    const panMgr = buildDeviceCollectMenuItems({
      vendorHint: "palo_alto",
      role: "management_server",
      enrollmentState: "ENROLLED",
      backupTarget: true,
      permissions: OPERATION_ADMIN_PERMS,
    });
    expect(panMgr.find((i) => i.key === "configuration")?.disabled).toBe(true);
    expect(panMgr.find((i) => i.key === "configuration")?.disabledReason).toContain("Palo Alto management server");
    expect(panMgr.find((i) => i.key === "backup")?.disabled).toBe(false);
    expect(panMgr.find((i) => i.key === "system_logs")?.disabled).toBe(true);
    expect(panMgr.find((i) => i.key === "system_logs")?.disabledReason).toContain("disabled on management servers");

    // 3. Fortinet management server (FortiManager): Configuration, Backup, and System logs all disabled with reasons
    const fmg = buildDeviceCollectMenuItems({
      vendorHint: "fortinet",
      role: "management_server",
      enrollmentState: "ENROLLED",
      backupTarget: true,
      permissions: OPERATION_ADMIN_PERMS,
    });
    expect(fmg.find((i) => i.key === "inventory")?.disabled).toBe(false);
    expect(fmg.find((i) => i.key === "configuration")?.disabled).toBe(true);
    expect(fmg.find((i) => i.key === "configuration")?.disabledReason).toContain("Fortinet management server");
    expect(fmg.find((i) => i.key === "backup")?.disabled).toBe(true);
    expect(fmg.find((i) => i.key === "backup")?.disabledReason).toContain("Fortinet management servers");
    expect(fmg.find((i) => i.key === "system_logs")?.disabled).toBe(true);
    expect(fmg.find((i) => i.key === "system_logs")?.disabledReason).toContain("disabled on management servers");

    // 4. Infoblox appliance: Configuration and System logs disabled for unsupported vendor; Backup enabled unless backupTarget is false
    const infoblox = buildDeviceCollectMenuItems({
      vendorHint: "infoblox",
      role: "appliance",
      enrollmentState: "ENROLLED",
      backupTarget: false,
      permissions: OPERATION_ADMIN_PERMS,
    });
    expect(infoblox.find((i) => i.key === "configuration")?.disabled).toBe(true);
    expect(infoblox.find((i) => i.key === "configuration")?.disabledReason).toContain("Unsupported vendor (Infoblox");
    expect(infoblox.find((i) => i.key === "backup")?.disabled).toBe(true);
    expect(infoblox.find((i) => i.key === "backup")?.disabledReason).toContain("Not switched on as a backup target");
    expect(infoblox.find((i) => i.key === "system_logs")?.disabled).toBe(true);
    expect(infoblox.find((i) => i.key === "system_logs")?.disabledReason).toContain("Unsupported vendor (Infoblox");
  });

  it("renders the Collect menu on the device header and triggers each manual start endpoint", async () => {
    const calls: Array<{ url: string; method: string }> = [];
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      calls.push({ url, method });
      if (url === "/session/status") return Promise.resolve(json({ csrf_token: "tok-1" }));
      if (url === "/devices/dev-cp-gw") {
        return Promise.resolve(json({
          device_id: "dev-cp-gw",
          vendor_hint: "check_point",
          enrollment_state: "ENROLLED",
          disabled: false,
          facts: null,
          peer_follow_outcome: null,
          peer_follow_reason: null,
          identity_mismatch_state: "NONE",
          cluster_member_ref: null,
          job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
        }));
      }
      if (url === "/devices/dev-cp-gw/inventory") {
        return Promise.resolve(json({ device_id: "dev-cp-gw", collected_at: "2026-10-20T06:00:00Z", job: null, contexts: [] }));
      }
      if (url === "/devices/dev-cp-gw/configuration/collect" && method === "POST") {
        return Promise.resolve(json({ job_id: "cfg-job-1" }, 202));
      }
      if (url === "/devices/dev-cp-gw/backup/collect" && method === "POST") {
        return Promise.resolve(json({ job_id: "bk-job-1" }, 202));
      }
      if (url === "/api/v2/system-logs/collect" && method === "POST") {
        return Promise.resolve(json({
          targets: 1,
          admitted: 1,
          deduplicated: 0,
          refused: 0,
          refusal_codes: [],
          job_ids: ["syslog-job-1"],
        }, 202));
      }
      return Promise.resolve(json({ backups: [] }));
    }));

    const onCollected = vi.fn();
    render(withProviders(
      <CollectNowButton
        deviceId="dev-cp-gw"
        onCollected={onCollected}
        enrollmentState="ENROLLED"
        vendorHint="fortinet"
        role="gateway"
        backupTarget={true}
      />,
      OPERATION_ADMIN_PERMS,
    ));

    // Open menu on a Fortinet gateway: System logs is disabled with vendor reason; Configuration and Backup are enabled
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    const syslogItem = await screen.findByRole("menuitem", { name: /System logs: reads the device's recent system log lines/ });
    expect(syslogItem).toHaveAttribute("aria-disabled", "true");
    expect(within(syslogItem).getByTestId("collect-disabled-system_logs")).toHaveTextContent(
      "Unsupported vendor (Fortinet has no system-log collection)",
    );

    const configItem = screen.getByRole("menuitem", { name: /Configuration: reads the device's running configuration and settings/ });
    fireEvent.click(configItem);
    await waitFor(() => expect(calls.some((c) => c.url === "/devices/dev-cp-gw/configuration/collect" && c.method === "POST")).toBe(true));

    // Open menu and click Backup
    await waitFor(() => expect(screen.getByRole("button", { name: "Collect" })).not.toBeDisabled());
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    fireEvent.click(await screen.findByRole("menuitem", { name: /Backup: reads and stores a backup archive of the device/ }));
    await waitFor(() => expect(calls.some((c) => c.url === "/devices/dev-cp-gw/backup/collect" && c.method === "POST")).toBe(true));
  });

  it("renders disabled menu items inside DeviceInventoryPanels for an unsupported role and hides Operations items for onboarding_admin", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/inventory")) return Promise.resolve(json({ device_id: "dev-cp-gw", collected_at: null, job: null, contexts: [] }));
      if (url.includes("/backups")) return Promise.resolve(json({ backups: [] }));
      return Promise.resolve(json({}));
    }));

    const { unmount } = render(withProviders(<DeviceInventoryPanels device={CP_GATEWAY} />, ONBOARDING_ADMIN_PERMS));
    fireEvent.click(await screen.findByRole("button", { name: "Collect" }));
    expect(await screen.findByRole("menuitem", { name: /Inventory:/ })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: /Configuration:/ })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: /Backup:/ })).toBeNull();
    expect(screen.queryByRole("menuitem", { name: /System logs:/ })).toBeNull();
    unmount();
  });
});

describe("DeviceLogsPanel Collect logs now action", () => {
  function baseSources(over: Partial<SystemLogSources> = {}): SystemLogSources {
    return {
      device_id: "dev-cp-gw",
      collection_state: "NO_COLLECTION_YET",
      can_view_unmasked: false,
      default_page_bytes: 65536,
      max_page_bytes: 262144,
      last_job: null,
      sources: [],
      ...over,
    };
  }

  it.each([
    [
      "202 admitted",
      202,
      { targets: 1, admitted: 1, deduplicated: 0, refused: 0, refusal_codes: [], job_ids: ["job-syslog-1"] },
      "System-log collection admitted.",
    ],
    [
      "202 deduplicated (already running)",
      202,
      { targets: 1, admitted: 0, deduplicated: 1, refused: 0, refusal_codes: [], job_ids: ["job-syslog-1"] },
      "System-log collection is already running for this device (deduplicated).",
    ],
    [
      "409 OUTSIDE_JOB_WINDOW with next window time",
      409,
      { error: "ACTION_REFUSED", code: "OUTSIDE_JOB_WINDOW", next_window_start: "2026-10-18T15:00:00Z" },
      "Refused: outside the job window (next window at 2026-10-18 18:00 GMT+3)",
    ],
    [
      "409 SYSTEM_LOG_VENDOR_UNSUPPORTED",
      409,
      { targets: 1, admitted: 0, deduplicated: 0, refused: 1, refusal_codes: ["SYSTEM_LOG_VENDOR_UNSUPPORTED"], job_ids: [] },
      "Refused: unsupported vendor (system logs are collected from Check Point and Palo Alto gateways only)",
    ],
    [
      "409 SYSTEM_LOG_DISABLED_ON_MANAGEMENT",
      409,
      { targets: 1, admitted: 0, deduplicated: 0, refused: 1, refusal_codes: ["SYSTEM_LOG_DISABLED_ON_MANAGEMENT"], job_ids: [] },
      "Refused: system-log collection is disabled on management servers",
    ],
  ])("shows %s in words and refreshes the panel's job state", async (_label, collectStatus, collectBody, expectedText) => {
    let jobsCalls = 0;
    let sourcesCalls = 0;
    const postBodies: string[] = [];

    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      if (url === "/session/status") return Promise.resolve(json({ csrf_token: "tok-1" }));
      if (url === "/api/v2/system-logs/dev-cp-gw/sources") {
        sourcesCalls += 1;
        return Promise.resolve(json(baseSources()));
      }
      if (url.startsWith("/api/v2/jobs?")) {
        jobsCalls += 1;
        const items = jobsCalls >= 2 && collectStatus === 202
          ? [{
              job_id: "job-syslog-1",
              device_id: "dev-cp-gw",
              job_type: "cp_system_log_collect",
              state: "COMPLETED",
              submitted_at: "2026-10-20T06:10:00Z",
              finished_at: "2026-10-20T06:11:00Z",
              terminal_reason: null,
            }]
          : [];
        return Promise.resolve(json({ items, page: 1, page_size: 1, total: items.length }));
      }
      if (url === "/api/v2/system-logs/collect" && method === "POST") {
        postBodies.push(String(init?.body ?? ""));
        return Promise.resolve(json(collectBody, collectStatus));
      }
      return Promise.resolve(json({}, 404));
    }));

    render(withProviders(<DeviceLogsPanel deviceId="dev-cp-gw" vendorHint="check_point" />, OPERATION_ADMIN_PERMS));

    const btn = await screen.findByRole("button", { name: "Collect logs now" });
    const initialSourcesCalls = sourcesCalls;
    const initialJobsCalls = jobsCalls;

    fireEvent.click(btn);

    const outcome = await screen.findByTestId("system-log-collect-outcome");
    expect(outcome).toHaveTextContent(expectedText);
    expect(postBodies).toEqual([JSON.stringify({ device_id: "dev-cp-gw" })]);
    await waitFor(() => {
      expect(sourcesCalls).toBeGreaterThan(initialSourcesCalls);
      expect(jobsCalls).toBeGreaterThan(initialJobsCalls);
    });
  });

  it("explains multiple refusal codes together when returned in one response", () => {
    expect(
      describeSystemLogCollectOutcome({
        targets: 2,
        admitted: 0,
        deduplicated: 0,
        refused: 2,
        refusal_codes: ["SYSTEM_LOG_VENDOR_UNSUPPORTED", "SYSTEM_LOG_DISABLED_ON_MANAGEMENT"],
      }).text,
    ).toBe(
      "Refused: unsupported vendor (system logs are collected from Check Point and Palo Alto gateways only) · Refused: system-log collection is disabled on management servers",
    );
    expect(
      describeSystemLogCollectError({
        status: 409,
        body: { refusal_codes: ["OUTSIDE_JOB_WINDOW"], next_window_start: "2026-10-18T15:00:00Z" },
      }).text,
    ).toContain("Refused: outside the job window (next window at 2026-10-18 18:00 GMT+3)");
  });
});

describe("Fleet Collect logs (all supported devices) action and confirmation dialog", () => {
  const HEALTH_BODY: SystemHealth = {
    computation: { state: "READY", computed_at: "2026-10-20T06:20:00Z", duration_ms: 800, snapshot_ttl_seconds: 600, store_available: true },
    window_days: 7,
    cpu_p95_threshold_percent: 85,
    time_basis: "Europe/Istanbul",
    rules: {
      critical: ["a new crash or dump file alert"],
      attention: ["CPU p95 above 85 % on 1 or 2 days"],
      normal: "every applicable fact evidenced",
      unknown: "an unevidenced fact outranks Normal",
      state_order: ["CRITICAL", "ATTENTION", "UNKNOWN", "NORMAL"],
      p95: "nearest rank",
      restart_rule: { id: "cpwd-restart-v1", status: "PROVISIONAL", text: "restart line" },
    },
    kpis: {
      monitored: { devices: 4, by_vendor: { check_point: 3, palo_alto: 1 }, not_collected_listed: 0 },
      crash_alerts: { devices: 0, alerts: 0, truncated: false },
      cpu_over_threshold: { devices: 0, evidenced: 4, unknown: 0, not_applicable: 0, basis: "x" },
      collection_not_ok: { devices: 0, by_state: {} },
    },
    devices: [],
  };

  it("hides the fleet action for viewer and onboarding_admin, and confirms with the device count from the response for operation_admin/security_admin", async () => {
    const postBodies: string[] = [];
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      if (url === "/session/status") return Promise.resolve(json({ csrf_token: "tok-1" }));
      if (url === "/api/v2/system-logs/health") return Promise.resolve(json(HEALTH_BODY));
      if (url === "/api/v2/system-logs/collect" && method === "POST") {
        postBodies.push(String(init?.body ?? ""));
        return Promise.resolve(json({
          targets: 4,
          admitted: 3,
          deduplicated: 1,
          refused: 0,
          refusal_codes: [],
          job_ids: ["j-1", "j-2", "j-3"],
        }, 202));
      }
      return Promise.resolve(json({}, 404));
    }));

    // Hidden for viewer
    const { unmount: unmountViewer } = render(withProviders(<SystemHealthPanel />, VIEWER_PERMS));
    await screen.findByLabelText("System health summary");
    expect(screen.queryByRole("button", { name: "Collect logs (all supported devices)" })).toBeNull();
    unmountViewer();

    // Hidden for onboarding_admin
    const { unmount: unmountOnboarding } = render(withProviders(<SystemHealthPanel />, ONBOARDING_ADMIN_PERMS));
    await screen.findByLabelText("System health summary");
    expect(screen.queryByRole("button", { name: "Collect logs (all supported devices)" })).toBeNull();
    unmountOnboarding();

    // Visible and functional for operation_admin / security_admin
    render(withProviders(<SystemHealthPanel />, SECURITY_ADMIN_PERMS));
    const trigger = await screen.findByRole("button", { name: "Collect logs (all supported devices)" });
    fireEvent.click(trigger);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Start system-log collection across all 4 supported devices\?/)).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(postBodies).toEqual(["{}"]));
    expect(await within(dialog).findByTestId("fleet-system-log-collect-dialog-outcome")).toHaveTextContent(
      "4 supported devices: 3 admitted, 1 already running (deduplicated), 0 refused.",
    );
    expect(screen.getByTestId("fleet-system-log-collect-outcome")).toHaveTextContent(
      "4 supported devices: 3 admitted, 1 already running (deduplicated), 0 refused.",
    );
  });
});

describe("Business-hours confirmation flow on requestSystemLogCollect", () => {
  it("retries POST /api/v2/system-logs/collect with X-Nexus-Business-Hours-Ack when confirmed", async () => {
    const headersSeen: Array<string | null> = [];
    let attempt = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/session/status") return Promise.resolve(json({ csrf_token: "tok-1" }));
      if (url === "/api/v2/system-logs/collect") {
        attempt += 1;
        const h = new Headers(init?.headers);
        headersSeen.push(h.get(BUSINESS_HOURS_ACK_HEADER));
        if (attempt === 1) {
          return Promise.resolve(json({
            code: BUSINESS_HOURS_CONFIRM_REQUIRED,
            reason: "Inside business hours (08:00–18:00 Europe/Istanbul).",
            business_hours: { start: "08:00", end: "18:00" },
          }, 409));
        }
        return Promise.resolve(json({
          targets: 1,
          admitted: 1,
          deduplicated: 0,
          refused: 0,
          refusal_codes: [],
          job_ids: ["job-bh-1"],
        }, 202));
      }
      return Promise.resolve(json({}, 404));
    }));

    setBusinessHoursConfirm(async () => true);
    const outcome = await requestSystemLogCollect("dev-cp-gw");
    expect(outcome.admitted).toBe(1);
    expect(headersSeen).toEqual([null, "true"]);
  });
});
