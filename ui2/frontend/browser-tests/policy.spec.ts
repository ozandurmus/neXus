import { test, expect } from "@playwright/test";
import { loadErrorText } from "../e2e/safety";

// Offline browser proof only: synthetic AIView API projections; no deployment or device contact.
// Run: node_modules/.bin/playwright test --config playwright.policy-local.config.ts
test("policy local: 54 containers, rule drawer, recursive drawer and masked CSV", async ({ page }) => {
  const targets = [{ deviceId: "device-1", name: "FW-TANGO-04", context: "VS-ROMEO-01", syncStatus: "UNKNOWN" }];
  const policies = Array.from({ length: 54 }, (_, index) => ({ id: `policy-${index}`, name: `OBJ-POLICY-${index}`,
    sourceId: "source-1", sourceName: "MGR-BRAVO-01", containerId: `container-${index}`, containerName: `DOM-TANGO-${index}`,
    vendor: "PAN", ruleCount: 1, collectedAt: "2026-10-02T06:00:00Z", artefactRef: `artifact-${index}`, targets }));
  const any = { refs: ["any-1"], negated: false };
  const rule = { id: "rule-1", uuid: "uuid-1", name: "OBJ-RULE-01", number: 1, enabled: true,
    source: { refs: ["group-1"], negated: false }, destination: any, service: any, application: any,
    action: "allow", log: "UNKNOWN", comment: "Withheld in AIView", extras: { withheld: ["Withheld in AIView"] } };
  const group = { id: "group-1", name: "OBJ-GROUP-01", type: "address-group", status: "RESOLVED" };
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
      collection: { jobId: "job-latest", state: "COMPLETED", outcome: "COMPLETED", reason: "COLLECTION_FAILED", step: 221, total: 60 } }] };
    else if (path === "/api/v2/policy/devices") body = { policies, devices: targets };
    else if (path.startsWith("/api/v2/policy/policies/")) body = { metadata: policies.find(p => path.endsWith(p.id)),
      sections: [{ id: "section-1", name: "Pre rules", source: "Shared", total: 1, rules: [rule] }],
      objects: [group, { id: "any-1", name: "ANY", type: "any", status: "RESOLVED" }], total: 1, page: 0, pageSize: 200 };
    else if (path.startsWith("/api/v2/policy/objects/")) body = { object: { ...group, children: [
      { id: "leaf-1", name: "OBJ-ADDRESS-01", type: "address", status: "RESOLVED", values: ["192.0.2.8"] },
    ] } };
    else if (path === "/notifications") body = { notifications: [] };
    else if (path === "/project-plan") body = {};
    else { await route.continue(); return; }
    await route.fulfill({ status: 200, contentType: "application/json", headers: { "X-Nexus-Masked": "true" }, body: JSON.stringify(body) });
  });
  await page.goto("/?screen=policy");
  await expect(page.getByText("Panorama · 54 containers")).toBeVisible();
  await expect(page.getByText("Collected · 60/60 collection steps")).toBeVisible();
  await page.getByRole("button", { name: "MGR-BRAVO-01" }).click();
  await expect(page.getByRole("group", { name: "Policy container", exact: true })).toHaveCount(54);
  await page.getByRole("textbox", { name: "Search sources, containers and policies" }).fill("OBJ-POLICY-53");
  await expect(page.getByRole("group", { name: "Policy container", exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "OBJ-POLICY-53", exact: true }).click();
  await expect(page.getByRole("region", { name: "Policy rulebase" })).toBeVisible();
  await page.getByRole("button", { name: "OBJ-RULE-01", exact: true }).click();
  const drawer = page.getByRole("dialog", { name: "Rule details" });
  await expect(drawer.getByText("UUID: uuid-1")).toBeVisible();
  await drawer.getByRole("button", { name: "Objects", exact: true }).click();
  await drawer.getByRole("button", { name: "OBJ-GROUP-01" }).click();
  const object = page.getByRole("dialog", { name: "Object details" });
  await object.getByText("OBJ-ADDRESS-01", { exact: false }).first().click();
  await expect(object.getByText("192.0.2.8")).toBeVisible();
  await object.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("checkbox", { name: "Select DOM-TANGO-53" }).check();
  await expect(page.getByRole("button", { name: "Collect selected" })).toHaveCount(0);
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export selected rules (CSV)" }).click();
  expect((await download).suggestedFilename()).toBe("selected-policy-rules.csv");
  await expect(page.getByText("Exported 1 rules.")).toBeVisible();
  expect(loadErrorText.test(await page.locator("body").innerText())).toBe(false);
  expect(failures).toEqual([]);
});
