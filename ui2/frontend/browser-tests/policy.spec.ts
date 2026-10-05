import { test, expect } from "@playwright/test";
import { loadErrorText } from "../e2e/safety";

// Offline browser proof only: synthetic AIView API projections; no deployment or device contact.
// Run: node_modules/.bin/playwright test --config playwright.policy-local.config.ts
test("policy local: 54 containers, three tabs, rule drawer, recursive drawer and masked CSV", async ({ page }) => {
  const targets = [{ deviceId: "device-1", name: "FW-TANGO-04", context: "VS-ROMEO-01", syncStatus: "UNKNOWN" }];
  const policies = Array.from({ length: 54 }, (_, index) => ({ id: `policy-${index}`, name: `POL-ALPHA-${index}`,
    sourceId: "source-1", sourceName: "MGR-BRAVO-01", containerId: `container-${index}`, containerName: `DOM-TANGO-${index}`,
    vendor: "PAN", ruleCount: 1, collectedAt: "2026-10-02T06:00:00Z", artefactRef: `artifact-${index}`, targets }));
  const any = { refs: ["any-1"], negated: false };
  const rule = { id: "rule-1", uuid: "uuid-1", name: "RULE-BRAVO-12", number: 1, enabled: true,
    source: { refs: ["group-1"], negated: false }, destination: any, service: any, application: any,
    action: "allow", log: "UNKNOWN", comment: "Withheld in AIView", extras: { withheld: ["Withheld in AIView"] } };
  const group = { id: "group-1", name: "GRP-TANGO-01", type: "address-group", status: "RESOLVED" };
  const failures: string[] = [];
  page.on("pageerror", () => failures.push("page error"));
  page.on("response", response => { if (response.status() >= 400) failures.push("HTTP error"); });
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    // External fonts/styles are not required for this offline check.
    if (url.origin !== new URL(test.info().project.use.baseURL as string).origin) {
      await route.fulfill({ status: 200, body: "" }); return;
    }
    const path = url.pathname;
    let body: unknown;
    if (path === "/session/status") body = { authenticated: true, role_tokens: ["role:replay_viewer"], permissions: [] };
    else if (path === "/api/v2/policy/sources") body = { canCollect: false, sources: [{ sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "PAN",
      collection: { jobId: "job-latest", state: "COMPLETED", outcome: "COMPLETED", reason: "COLLECTION_FAILED", step: 221, total: 60, collectedAt: new Date(Date.now() - 7200000).toISOString() } }] };
    else if (path === "/api/v2/policy/devices") body = { policies, devices: targets };
    else if (path.includes("/domains/") && path.endsWith("/objects")) body = { objects: [{ id: "inventory-1", uid: "uid-1", name: "ADDR-ROMEO-01", type: "host", values: ["ipv4-address: 240.12.0.8"],
      members: [], unused: true, emptyGroup: false, singleMember: false, duplicateId: null, ruleCount: 1, groupCount: 0 }], types: [], total: 1, page: 0, pageSize: 200 };
    else if (path.includes("/domains/") && path.endsWith("/installation")) body = { installations: [{ id: "gateway-1", policyId: "policy-53", policyName: "POL-ALPHA-53",
      name: "FW-TANGO-04", deviceId: "device-1", allTargets: true, targeted: true, installed: null }], types: [], total: 1, page: 0, pageSize: 200 };
    else if (path.startsWith("/api/v2/policy/policies/")) body = { metadata: policies.find(p => path.endsWith(p.id)),
      sections: [{ id: "section-1", name: "Pre rules", source: "Shared", total: 1, rules: [rule] }],
      objects: [group, { id: "any-1", name: "ANY", type: "any", status: "RESOLVED" }], total: 1, page: 0, pageSize: 200 };
    else if (path.startsWith("/api/v2/policy/objects/")) body = { object: { ...group, children: [
      { id: "leaf-1", name: "ADDR-ROMEO-01", type: "address", status: "RESOLVED", values: ["192.0.2.8"] },
    ] } };
    else if (path === "/notifications") body = { notifications: [] };
    else if (path === "/project-plan") body = {};
    else { await route.continue(); return; }
    await route.fulfill({ status: 200, contentType: "application/json", headers: { "X-Nexus-Masked": "true" }, body: JSON.stringify(body) });
  });
  await page.goto("/?screen=policy");
  await expect(page.getByText("Panorama · 54 containers")).toBeVisible();
  await expect(page.getByText("Collected · 2 h ago")).toBeVisible();
  await page.getByRole("button", { name: "MGR-BRAVO-01" }).click();
  await expect(page.getByRole("group", { name: "Policy container", exact: true })).toHaveCount(54);
  await page.getByRole("button", { name: "DOM-TANGO-53", exact: true }).click();
  await page.getByRole("button", { name: "POL-ALPHA-53", exact: true }).click();
  await expect(page.getByRole("region", { name: "Policy content", exact: true })
    .getByRole("region", { name: "Policy rulebase", exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "Search sources, containers and policies" }).fill("POL-ALPHA-53");
  await expect(page.getByRole("group", { name: "Policy container", exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "POL-ALPHA-53", exact: true }).click();
  await expect(page.getByRole("region", { name: "Policy rulebase" })).toBeVisible();
  for (const width of [1550, 1390]) {
    await page.setViewportSize({ width, height: 784 });
    await expect(page.locator(".policy-rule-card")).toHaveClass(width < 1400 ? /stacked/ : /wide/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const region = page.getByRole("region", { name: "Policy rulebase" });
    expect(await region.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeInViewport();
    expect(await page.locator("body").innerText()).not.toMatch(/[a-f0-9]{32}/i);
  }
  await page.getByRole("button", { name: "RULE-BRAVO-12", exact: true }).click();
  const drawer = page.getByRole("dialog", { name: "Rule details" });
  await expect(drawer.getByText("UUID: uuid-1")).toBeVisible();
  await drawer.getByRole("button", { name: "Objects", exact: true }).click();
  await drawer.getByRole("button", { name: "GRP-TANGO-01" }).click();
  const object = page.getByRole("dialog", { name: "Object details" });
  await object.getByText("ADDR-ROMEO-01", { exact: false }).first().click();
  await expect(object.getByText("192.0.2.8")).toBeVisible();
  await object.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("tab", { name: "Objects", exact: true }).click();
  const objectTable = page.getByRole("table", { name: "Policy objects" });
  await expect(objectTable.getByText("ADDR-ROMEO-01")).toBeVisible();
  await expect(objectTable.getByText("ipv4-address: 240.12.0.8")).toBeVisible();
  await page.getByRole("tab", { name: "Installation", exact: true }).click();
  const installation = page.getByRole("table", { name: "Policy installation" });
  await expect(installation.getByRole("link", { name: "FW-TANGO-04" })).toHaveAttribute("href", "/?screen=inventory&device_id=device-1");
  await expect(installation.getByText("ALL", { exact: true })).toBeVisible();
  await expect(installation.getByText("UNKNOWN", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Rules", exact: true }).click();
  await expect(page.getByRole("region", { name: "Policy rulebase" })).toBeVisible();
  await page.getByRole("checkbox", { name: "Select DOM-TANGO-53" }).check();
  await expect(page.getByRole("button", { name: "Collect selected" })).toHaveCount(0);
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export selected rules (CSV)" }).click();
  expect((await download).suggestedFilename()).toBe("selected-policy-rules.csv");
  await expect(page.getByText("Exported 1 rules.")).toBeVisible();
  expect(loadErrorText.test(await page.locator("body").innerText())).toBe(false);
  expect(failures).toEqual([]);
});

test("HA local: both masked members, wrapped messages and no horizontal overflow", async ({ page }) => {
  const members = ["ACTIVE", "STANDBY"].map((ha_role, index) => ({ device_id: `member-${index}`, hostname: `FW-ROMEO-01-M${index + 1}`,
    ha_role, cluster_member_ref: "CLS-ROMEO-01", vendor_hint: "check_point" }));
  const checks = members.map((member, index) => ({ checkNo: 2, title: "Cluster IP table", device_id: member.device_id, member: `Member ${index + 1}`,
    result: "FAIL", status: "FAIL", blocking: true, derived: {}, summary: "Tables differ; " + "missing entry on observer; ".repeat(30) }));
  const failures: string[] = [];
  page.on("pageerror", () => failures.push("page error"));
  page.on("response", response => { if (response.status() >= 400) failures.push("HTTP error"); });
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    if (url.origin !== new URL(test.info().project.use.baseURL as string).origin) { await route.fulfill({ status: 200, body: "" }); return; }
    let body: unknown;
    if (url.pathname === "/session/status") body = { authenticated: true, role_tokens: ["role:replay_viewer"], permissions: [] };
    else if (url.pathname === "/devices") body = { devices: members };
    else if (url.pathname === "/api/v2/cp-failover/summary") body = [{ clusterId: "cluster-1", unitId: "cluster-1", cluster_member_ref: "CLS-ROMEO-01",
      masked: true, canRunReadiness: false, members: [...members].reverse(), readiness: { status: "NOT_READY", observedAt: "2026-10-02T06:06:00Z", failedCheck: "Cluster IP table", checks } }];
    else if (url.pathname === "/api/v2/jobs/stats") body = { total_24h: 1, completed_24h: 1, failed_24h: 0, running: 0 };
    else if (url.pathname === "/notifications") body = { notifications: [] };
    else if (url.pathname === "/project-plan") body = {};
    else { await route.continue(); return; }
    await route.fulfill({ status: 200, contentType: "application/json", headers: { "X-Nexus-Masked": "true" }, body: JSON.stringify(body) });
  });
  await page.goto("/?screen=operations");
  const table = page.getByRole("table", { name: "HA clusters" });
  await expect(table.getByText("Not ready", { exact: true })).toBeVisible();
  await expect(table.getByRole("columnheader", { name: "Actions" })).toHaveCount(0);
  await table.getByRole("row", { name: "CLS-ROMEO-01" }).click();
  const detail = page.getByRole("region", { name: "Checks for CLS-ROMEO-01" });
  await expect(detail.getByRole("columnheader", { name: "FW-ROMEO-01-M1 · active" })).toBeVisible();
  await expect(detail.getByRole("columnheader", { name: "FW-ROMEO-01-M2 · standby" })).toBeVisible();
  for (const width of [1550, 1390]) {
    await page.setViewportSize({ width, height: 784 });
    expect(await table.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  const message = detail.locator(".readiness-message").first();
  await expect(message).toHaveCSS("white-space", "normal");
  await expect(message).toHaveCSS("-webkit-line-clamp", "2");
  await message.locator("..").getByRole("button", { name: "More" }).click();
  await expect(message).toHaveCSS("display", "block");
  expect(failures).toEqual([]);
});
