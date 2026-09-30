import { test, expect, visit, browserGet } from "./fixtures";
import type { DeviceSummary, DeviceComplianceResult, JobPageView } from "../src/auth/adminApi";

test("Inventory: opens a device and every available detail tab including Interfaces and Configuration", async ({ page, safety }) => {
  await visit(page, "screen=inventory", "Devices");
  const devices = await browserGet<{ devices: DeviceSummary[] }>(page, "/devices");
  expect(devices.status).toBe(200);
  const device = devices.body.devices.find((d) => !d.cluster_member_ref && d.role === "gateway"
    && ["check_point", "palo_alto", "fortinet", "cisco_asa"].includes(d.vendor_hint) && d.enrollment_state === "ENROLLED");
  expect(Boolean(device), "An enrolled standalone gateway with detail tabs is required").toBe(true);
  expect(/^FW-[A-Z]+-\d+(?:-M\d+)?$/.test(device!.hostname ?? ""), "Selected device name must be an aiview pseudonym").toBe(true);
  const row = page.getByRole("button").filter({ has: page.getByText(device!.hostname!, { exact: true }) }).last();
  await row.click();
  const tabs = page.getByRole("tablist", { name: "Device detail", exact: true });
  await expect(tabs).toBeVisible();
  await expect(tabs.getByRole("tab", { name: "Interfaces", exact: true })).toBeVisible();
  await expect(tabs.getByRole("tab", { name: "Configuration", exact: true })).toBeVisible();
  await safety.checkpoint();
  const names = await tabs.getByRole("tab").allTextContents();
  for (const name of names) {
    // These are navigation tabs only; no action inside a panel is clicked.
    expect(["Interfaces", "Routing", "Cluster members", "Configuration", "Backup", "Identity"].includes(name)).toBe(true);
    await test.step(`Read ${name} tab`, async () => {
      await tabs.getByRole("tab", { name, exact: true }).click();
      await expect(page.getByRole("tabpanel", { name, exact: true })).toBeVisible();
      await safety.checkpoint();
    });
  }
});

test("Inventory: Check Point failover units use the device list's VS names", async ({ page, safety }) => {
  await visit(page, "screen=inventory", "Devices");
  const devices = await browserGet<{ devices: DeviceSummary[] }>(page, "/devices");
  expect(devices.status).toBe(200);
  const cluster = devices.body.devices.find(d => d.vendor_hint === "check_point" && d.cluster_member_ref
    && d.enrollment_state === "ENROLLED" && d.virtual_systems);
  expect(Boolean(cluster), "An enrolled Check Point VSX cluster is required").toBe(true);
  const names = cluster!.virtual_systems!.split(/,\s*/).filter(Boolean);
  expect(names.length).toBeGreaterThan(0);
  await page.locator('[data-row="cluster"]').filter({ hasText: cluster!.cluster_member_ref! }).first().click();
  // A cluster opens the cluster detail (its own tablist name), not the device detail.
  const tabs = page.getByRole("tablist", { name: "Cluster detail", exact: true });
  await tabs.getByRole("tab", { name: "Failover", exact: true }).click();
  const failover = page.getByLabel("Check Point failover");
  for (const name of names) {
    await expect(failover.getByRole("button", { name: `Virtual System · ${name}`, exact: true })).toBeVisible();
  }
  await failover.getByRole("button", { name: `Virtual System · ${names[0]}`, exact: true }).click();
  await safety.checkpoint();
  await expect(failover.getByText(/CLUSTER_NOT_FOUND|UNIT_NOT_FOUND|Request failed/)).toHaveCount(0);
});

for (const [vendor, label] of [["check_point", "Check Point"], ["palo_alto", "Palo Alto"], ["fortinet", "FortiGate"], ["cisco_asa", "Cisco ASA"]]) {
  test(`Compliance: shows a control assigned to at least one ${label} device`, async ({ page, safety }) => {
    await visit(page, "screen=compliance", "Compliance");
    const devices = await browserGet<{ devices: DeviceSummary[] }>(page, "/devices");
    expect(devices.status).toBe(200);
    const candidates = devices.body.devices.filter((d) => d.vendor_hint === vendor && d.role === "gateway");
    expect(candidates.length, `${label} gateway must exist; missing coverage is a failure`).toBeGreaterThan(0);
    let controlId: string | undefined;
    for (const device of candidates) {
      const result = await browserGet<DeviceComplianceResult>(page, `/devices/${encodeURIComponent(device.device_id)}/compliance`);
      expect(result.status, "Device compliance read must succeed").toBe(200);
      if (result.body.items?.length) {
        controlId = result.body.items[0].controlId;
        break;
      }
    }
    expect(Boolean(controlId), `${label} must have at least one assigned compliance control`).toBe(true);
    const table = page.getByRole("table").filter({ has: page.getByRole("columnheader", { name: "Control · code & title" }) });
    await expect(table.getByRole("row").filter({ has: page.getByText(controlId!, { exact: true }) })).toBeVisible();
    await safety.checkpoint();
  });
}

test("Operations Jobs: lists at least one job", async ({ page, safety }) => {
  await visit(page, "screen=operations", "Operations");
  await page.getByRole("tablist", { name: "Operations sections" }).getByRole("tab", { name: "Jobs", exact: true }).click();
  await expect(page.getByRole("table", { name: "Job logs" }).getByRole("row").nth(1)).toBeVisible();
  await safety.checkpoint();
});

test("Backups: lists at least one enabled backup target", async ({ page, safety }) => {
  await visit(page, "screen=backups", "Backups");
  await page.getByRole("button", { name: /^Targets · \d+$/ }).click();
  await expect(page.getByRole("checkbox", { name: /^Backup target / }).first()).toBeChecked();
  await safety.checkpoint();
});

test("aiview: a listed backup job transcript GET is forbidden with HTTP 403", async ({ page, safety }) => {
  const jobsResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v2/jobs"
    && new URL(response.url()).searchParams.get("job_type") === "backup");
  await visit(page, "screen=operations&tab=jobs&job_type=backup", "Operations");
  const response = await jobsResponse;
  expect(response.status()).toBe(200);
  const jobs = await response.json() as JobPageView;
  expect(jobs.items.length, "At least one listed backup job is required to prove transcript denial").toBeGreaterThan(0);
  expect(/backup/i.test(jobs.items[0].job_type), "Listed job must be a backup job").toBe(true);
  await expect(page.getByRole("table", { name: "Job logs" }).getByRole("row").nth(1)).toBeVisible();
  safety.expectForbiddenTranscript(`/jobs/${encodeURIComponent(jobs.items[0].job_id)}/transcript`);
  const result = await browserGet<null>(page, `/jobs/${encodeURIComponent(jobs.items[0].job_id)}/transcript`);
  expect(result.status, "aiview must receive 403 even for a real listed backup job").toBe(403);
  await safety.checkpoint();
});
