import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import {
  DEVICES_STALE_MS,
  addDeviceSingle,
  deleteDevice,
  getJobWindow,
  getSessionStatus,
  getSystemPods,
  invalidateDevicesCache,
  listDevices,
  listNotifications,
  resetSharedApiCache,
  setBackupTarget,
} from "../src/auth/adminApi";
import { handleInAppLinkClick, navigateTo } from "../src/shell/urlParams";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  resetSharedApiCache();
  window.history.replaceState(null, "", "/?screen=overview");
});

afterEach(() => {
  resetSharedApiCache();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("client-side pushState navigation", () => {
  it("intercepts left-menu and in-app link clicks with history.pushState without reloading the app", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return jsonResponse(200, {
            devices: [
              {
                device_id: "dev-synthetic-1",
                vendor_hint: "palo_alto",
                enrollment_state: "ENROLLED",
                hostname: "FW-TANGO-04",
                model: "PA-5410",
                software_version: "11.0.2",
                ha_role: null,
                cluster_member_ref: null,
              },
            ],
          });
        }
        if (url === "/backups") return jsonResponse(200, { backups: [] });
        if (url === "/api/v2/backups/policies") {
          return jsonResponse(200, {
            policy_id: "default",
            schedule_enabled: true,
            daily_backup_cron: "0 1 * * *",
            backup_retention_days: 30,
            snapshot_retention_depth: 5,
          });
        }
        return jsonResponse(200, {});
      }),
    );

    const pushSpy = vi.spyOn(window.history, "pushState");
    render(<App />);

    const rail = screen.getByRole("navigation", { name: "Primary" });
    const devicesLink = within(rail).getByRole("link", { name: "Devices" });

    let prevented = false;
    act(() => {
      const clickEvent = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 });
      devicesLink.dispatchEvent(clickEvent);
      prevented = clickEvent.defaultPrevented;
    });

    expect(prevented).toBe(true);
    expect(pushSpy).toHaveBeenCalled();
    expect(window.location.search).toBe("?screen=inventory");
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();

    // Clicking Backups in the left menu switches screens client-side
    const backupsLink = within(rail).getByRole("link", { name: "Backups" });
    fireEvent.click(backupsLink);
    expect(window.location.search).toBe("?screen=backups");
    expect(await screen.findByRole("heading", { name: "Backups" })).toBeInTheDocument();
  });

  it("handles Back and Forward (popstate) across screens and sub-tabs", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: [] });
        if (url === "/api/v2/cp-failover/summary") return jsonResponse(200, []);
        if (url === "/api/v2/system-logs/health") {
          return jsonResponse(200, {
            computation: { state: "READY", computed_at: null, snapshot_ttl_seconds: 300, store_available: true },
            window_days: 7,
            cpu_p95_threshold_percent: 80,
            time_basis: "UTC",
            rules: {
              critical: ["crash"],
              attention: ["cpu"],
              normal: "ok",
              unknown: "unknown",
              state_order: ["CRITICAL", "ATTENTION", "NORMAL", "UNKNOWN"],
              p95: "p95",
              restart_rule: { id: "R1", status: "FROZEN", text: "restart" },
            },
            kpis: { monitored: null, crash_alerts: null, cpu_over_threshold: null, collection_not_ok: null },
            devices: [],
          });
        }
        return jsonResponse(200, {});
      }),
    );

    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });

    fireEvent.click(within(rail).getByRole("link", { name: "Devices" }));
    expect(window.location.search).toBe("?screen=inventory");
    expect(await screen.findByText("No device to show")).toBeInTheDocument();

    if (!within(rail).queryByRole("link", { name: "HA and failover" })) fireEvent.click(within(rail).getByRole("button", { name: "Operations" }));
    fireEvent.click(within(rail).getByRole("link", { name: "HA and failover" }));
    expect(window.location.search).toBe("?screen=operations");
    expect(await screen.findByText("No HA pair or cluster enrolled")).toBeInTheDocument();

    fireEvent.click(within(rail).getByRole("link", { name: "Device health" }));
    expect(window.location.search).toBe("?screen=operations&tab=health");
    expect(await screen.findByTestId("health-rules")).toBeInTheDocument();

    // Simulate browser Back to ?screen=operations
    act(() => {
      window.history.replaceState(null, "", "/?screen=operations");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(await screen.findByText("No HA pair or cluster enrolled")).toBeInTheDocument();

    // Simulate browser Back to ?screen=inventory
    act(() => {
      window.history.replaceState(null, "", "/?screen=inventory");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(await screen.findByText("No device to show")).toBeInTheDocument();
  });

  it("lets modifier-clicks, middle-clicks, target=_blank and download links fall through to the browser", () => {
    const pushSpy = vi.spyOn(window.history, "pushState");
    const container = document.createElement("div");
    container.innerHTML = `
      <a id="in-app" href="?screen=inventory">Devices</a>
      <a id="new-tab" href="?screen=inventory" target="_blank">New tab</a>
      <a id="download" href="?screen=inventory" download="report.csv">Download</a>
      <a id="external" href="https://example.invalid/docs">External</a>
    `;
    document.body.appendChild(container);

    try {
      const inApp = container.querySelector("#in-app") as HTMLAnchorElement;
      const newTab = container.querySelector("#new-tab") as HTMLAnchorElement;
      const download = container.querySelector("#download") as HTMLAnchorElement;
      const external = container.querySelector("#external") as HTMLAnchorElement;

      for (const init of [
        { button: 0, ctrlKey: true },
        { button: 0, metaKey: true },
        { button: 0, shiftKey: true },
        { button: 0, altKey: true },
        { button: 1 },
      ]) {
        const event = new MouseEvent("click", { bubbles: true, cancelable: true, ...init });
        Object.defineProperty(event, "target", { value: inApp });
        expect(handleInAppLinkClick(event)).toBe(false);
        expect(event.defaultPrevented).toBe(false);
      }

      for (const anchor of [newTab, download, external]) {
        const event = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 });
        Object.defineProperty(event, "target", { value: anchor });
        expect(handleInAppLinkClick(event)).toBe(false);
        expect(event.defaultPrevented).toBe(false);
      }

      expect(pushSpy).not.toHaveBeenCalled();

      // Plain primary click on the in-app link is intercepted
      const plainClick = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 });
      Object.defineProperty(plainClick, "target", { value: inApp });
      expect(handleInAppLinkClick(plainClick)).toBe(true);
      expect(plainClick.defaultPrevented).toBe(true);
      expect(pushSpy).toHaveBeenCalledTimes(1);
      expect(window.location.search).toBe("?screen=inventory");
    } finally {
      document.body.removeChild(container);
    }
  });

  it("applies legacy-route and ?screen=configuration redirects during client-side navigation", async () => {
    render(<App />);

    act(() => {
      navigateTo("?screen=path_analysis");
    });
    expect(window.location.search).toBe("?screen=trace&mode=path");
    expect(await screen.findByRole("heading", { name: "Trace" })).toBeInTheDocument();

    act(() => {
      navigateTo("?screen=operations&tab=changes");
    });
    expect(window.location.search).toBe("?screen=changes&view=feed");
    expect(await screen.findByRole("heading", { name: "Changes" })).toBeInTheDocument();

    act(() => {
      navigateTo("?screen=configuration&device_id=dev-synthetic-1");
    });
    expect(window.location.search).toBe("?screen=inventory&device_id=dev-synthetic-1&tab=configuration");
  });

  it("renders a screen's frame immediately on navigation while its own data is still loading", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>(() => {})),
    );

    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });

    if (!within(rail).queryByRole("link", { name: "Compliance" })) fireEvent.click(within(rail).getByRole("button", { name: "Compliance and security" }));
    fireEvent.click(within(rail).getByRole("link", { name: "Compliance" }));
    // Screens are lazy chunks: the frame header appears once the chunk loads, before any fetch resolves
    expect(await screen.findByRole("heading", { name: "Compliance" })).toBeInTheDocument();

    fireEvent.click(within(rail).getByRole("link", { name: "Backups" }));
    expect(await screen.findByRole("heading", { name: "Backups" })).toBeInTheDocument();
  });
});

describe("shared shell-level cache deduplication, staleness and invalidation", () => {
  it("deduplicates /devices across two screens within the 30 s staleness window and refreshes after expiry", async () => {
    let now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);

    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return jsonResponse(200, {
          devices: [
            {
              device_id: "dev-synthetic-1",
              vendor_hint: "check_point",
              enrollment_state: "ENROLLED",
              hostname: "FW-TANGO-04",
              model: "Quantum",
              software_version: "R81.20",
              ha_role: null,
              cluster_member_ref: null,
            },
            {
              device_id: "dev-synthetic-2",
              vendor_hint: "check_point",
              enrollment_state: "ENROLLED",
              hostname: "FW-JULIET-06",
              model: "Quantum",
              software_version: "R81.20",
              ha_role: "active",
              cluster_member_ref: "CLS-ROMEO-01",
            },
          ],
        });
      }
      if (url === "/backups") return jsonResponse(200, { backups: [] });
      if (url === "/api/v2/backups/policies") {
        return jsonResponse(200, {
          policy_id: "default",
          schedule_enabled: false,
          daily_backup_cron: "0 1 * * *",
          backup_retention_days: 30,
          snapshot_retention_depth: 5,
        });
      }
      if (url === "/api/v2/cp-failover/summary") return jsonResponse(200, []);
      return jsonResponse(200, {});
    });
    vi.stubGlobal("fetch", fetchMock);

    window.history.replaceState(null, "", "/?screen=inventory");
    render(<App />);
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();

    const devicesCallsAfterInventory = fetchMock.mock.calls.filter(([u]) => String(u) === "/devices").length;
    expect(devicesCallsAfterInventory).toBe(1);

    // Navigate to Backups (which calls listDevices() twice in BackupScreen + BackupDeviationsSection) within 30 s
    const rail = screen.getByRole("navigation", { name: "Primary" });
    now += 10_000;
    fireEvent.click(within(rail).getByRole("link", { name: "Backups" }));
    expect(await screen.findByRole("heading", { name: "Backups" })).toBeInTheDocument();

    // Navigate to Operations (which also calls listDevices() on mount) within 30 s
    now += 5_000;
    if (!within(rail).queryByRole("link", { name: "HA and failover" })) fireEvent.click(within(rail).getByRole("button", { name: "Operations" }));
    fireEvent.click(within(rail).getByRole("link", { name: "HA and failover" }));
    expect(await screen.findByText("CLS-ROMEO-01")).toBeInTheDocument();

    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(1);

    // Advance past the 30 s staleness window; the next screen read re-fetches /devices
    now += DEVICES_STALE_MS + 1;
    fireEvent.click(within(rail).getByRole("link", { name: "Devices" }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(2),
    );
  });

  it("invalidates the /devices cache after device-changing mutations", async () => {
    let deviceCount = 1;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") return jsonResponse(200, { authenticated: true, csrf_token: "csrf-synthetic" });
      if (url === "/devices") {
        return jsonResponse(200, {
          devices: Array.from({ length: deviceCount }, (_, i) => ({
            device_id: `dev-synthetic-${i + 1}`,
            vendor_hint: "palo_alto",
            enrollment_state: "ENROLLED",
            hostname: `FW-TANGO-0${i + 1}`,
            model: "PA-5410",
            software_version: "11.0.2",
            ha_role: null,
            cluster_member_ref: null,
          })),
        });
      }
      if (url === "/devices/add-single") {
        deviceCount = 2;
        return jsonResponse(200, { device_id: "dev-synthetic-2", job_id: "job-1", enrollment_state: "DRAFT" });
      }
      if (url === "/devices/dev-synthetic-2/backup-target") {
        return jsonResponse(200, { device_id: "dev-synthetic-2", backup_target: true, changed: true });
      }
      if (url === "/devices/dev-synthetic-2/delete") {
        deviceCount = 1;
        return jsonResponse(200, { deleted: true, device_id: "dev-synthetic-2", backup_artefact_count: 0 });
      }
      return jsonResponse(200, {});
    });
    vi.stubGlobal("fetch", fetchMock);

    const first = await listDevices();
    expect(first.devices).toHaveLength(1);
    await listDevices();
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(1);

    // Adding a device invalidates the cache
    await addDeviceSingle("192.0.2.10", "gateway", "palo_alto", "cred-ref-1");
    const afterAdd = await listDevices();
    expect(afterAdd.devices).toHaveLength(2);
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(2);

    // Updating backup target invalidates the cache
    await setBackupTarget("dev-synthetic-2", true);
    await listDevices();
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(3);

    // Deleting a device invalidates the cache
    await deleteDevice("dev-synthetic-2");
    const afterDelete = await listDevices();
    expect(afterDelete.devices).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(4);

    // Explicit invalidation also forces a fresh read
    invalidateDevicesCache();
    await listDevices();
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/devices")).toHaveLength(5);
  });

  it("caches shell-level reads (session status, notifications, job window, pods health) within their staleness windows", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") return jsonResponse(200, { authenticated: true, display_name: "operator" });
      if (url === "/notifications") return jsonResponse(200, { notifications: [] });
      if (url === "/api/v2/job-window") {
        return jsonResponse(200, {
          deviceJobsEnabled: true,
          overrideActive: false,
          overrideExpiresAt: null,
          windowOpen: true,
          currentWindowEndsAt: null,
          nextWindowStartsAt: null,
          schedule: "20:00-08:00",
        });
      }
      if (url === "/api/v2/system/pods") {
        return jsonResponse(200, { available: true, read_at: "2026-11-14T09:00:00Z", pods: [] });
      }
      return jsonResponse(200, {});
    });
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([getSessionStatus(), getSessionStatus()]);
    await Promise.all([listNotifications(), listNotifications()]);
    await Promise.all([getJobWindow(), getJobWindow()]);
    await Promise.all([getSystemPods(), getSystemPods()]);

    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/session/status")).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/notifications")).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/api/v2/job-window")).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([u]) => String(u) === "/api/v2/system/pods")).toHaveLength(1);
  });
});
