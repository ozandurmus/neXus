import type { Locator, Page } from "@playwright/test";
import type { DeviceSummary } from "../src/auth/adminApi";
import { TAB_COVERAGE, BACKUP_SECTIONS, SCREEN_HEADINGS, ADMIN_PAGES } from "../src/shell/tabCoverage";
import { SCREEN_IDS } from "../src/shell/types";
import { test, expect, visit, browserGet } from "./fixtures";

async function content(page: Page, region: Locator = page.getByRole("main")) {
  expect((await region.innerText()).trim().length > 0, "Main/panel content is empty (text withheld)").toBe(true);
  const cells = await page.getByRole("cell").allTextContents();
  expect(cells.some(text => /^\{\s*"/.test(text.trim())), "Raw JSON-looking table cell (content withheld)").toBe(false);
}

// Only role=tab navigation is traversed. Action buttons inside panels are never clicked.
async function nestedTabs(page: Page, root: Locator, checkpoint: () => Promise<void>, depth = 0) {
  expect(depth < 8, "Unexpected tab nesting").toBe(true);
  const rootPanelId = await root.getAttribute("role") === "tabpanel" ? await root.getAttribute("id") : null;
  const listNames = await root.getByRole("tablist").evaluateAll((lists, panelId) => lists
    .filter(list => (list.closest('[role="tabpanel"]')?.id || null) === panelId)
    .map(list => list.getAttribute("aria-label")), rootPanelId);
  for (const label of listNames) {
    const list = root.getByRole("tablist", { name: label ?? "", exact: true });
    if (!await list.isVisible()) continue;
    const definition = TAB_COVERAGE.find(row => row.tabList === label);
    // Context tabs contain masked estate-specific VS names, not static navigation labels.
    expect(Boolean(definition) || label === "Inventory context", "Unregistered tab list (label withheld)").toBe(true);
    const tabs = list.getByRole("tab");
    const tabCount = await tabs.count();
    for (let j = 0; j < tabCount; j++) {
      const tab = tabs.nth(j);
      if (definition) expect(definition.labels.includes((await tab.innerText()).trim()), "Unregistered tab (label withheld)").toBe(true);
      await tab.click();
      await checkpoint();
      const panelId = await tab.getAttribute("aria-controls");
      expect(Boolean(panelId), "Tab must name its panel").toBe(true);
      const panel = page.locator(`[id=${JSON.stringify(panelId)}]`);
      await content(page, panel);
      await nestedTabs(page, panel, checkpoint, depth + 1);
    }
  }
}

for (const screen of SCREEN_IDS) {
  test(`full: ${screen} main region`, async ({ page, safety }) => {
    await visit(page, `screen=${screen}`, SCREEN_HEADINGS[screen]);
    await safety.checkpoint();
    await content(page);
  });
}
// The Lifecycle and security view of the Devices screen, at its own address under its own heading (tabCoverage.ts).
const lifecycle = TAB_COVERAGE.find(row => row.tabList === "Lifecycle view")!;
for (const label of lifecycle.labels) {
  test(`full: inventory / Lifecycle / ${label}`, async ({ page, safety }) => {
    await visit(page, lifecycle.query!, lifecycle.heading!);
    await page.getByRole("tablist", { name: lifecycle.tabList, exact: true }).getByRole("tab", { name: label, exact: true }).click();
    await safety.checkpoint();
    await content(page, page.getByRole("tabpanel", { name: label, exact: true }));
    if (label === "Catalog") {
      await expect(page.getByLabel("Import lifecycle CSV")).toHaveCount(0);
      await expect(page.getByRole("button", { name: "Add row", exact: true })).toHaveCount(0);
    }
  });
}
// Inventory detail tabs need a stored entity and Packet Analysis case views an opened case (opened by a row click, an
// action this suite never takes): both are reached through nestedTabs from their own entry, not from the screen URL.
for (const { screen, tabList, labels, query, heading } of TAB_COVERAGE.filter(row => row.screen !== "inventory" && row.screen !== "packet_analysis")) {
  for (const label of labels) {
    test(`full: ${screen} / ${label}`, async ({ page, safety }) => {
      // A tab list that lives at its own address (an Administration page) is visited there, under that page's heading.
      await visit(page, query ?? `screen=${screen}`, heading ?? SCREEN_HEADINGS[screen]);
      await page.getByRole("tablist", { name: tabList, exact: true }).getByRole("tab", { name: label, exact: true }).click();
      await safety.checkpoint();
      const panel = page.getByRole("tabpanel", { name: label, exact: true });
      await content(page, panel);
      await nestedTabs(page, panel, safety.checkpoint);
      if (screen === "operations" && label === "HA and readiness") {
        await expect(panel.getByLabel("Readiness summary").getByRole("button")).toHaveCount(4);
        await expect(panel.getByRole("table", { name: "HA clusters", exact: true }).getByRole("row").nth(1)).toBeVisible();
      }
    });
  }
}
// Administration's seven pages (navigation redesign 2026-11-12, PO amendment 2026-11-13): each is its own screen under its own heading; a page the
// persona may not read shows the Restricted state, which is content.
for (const { query, heading } of ADMIN_PAGES) {
  test(`full: administration page / ${heading}`, async ({ page, safety }) => {
    await visit(page, query, heading);
    await safety.checkpoint();
    await content(page);
  });
}
for (const label of BACKUP_SECTIONS) {
  test(`full: backups / ${label}`, async ({ page, safety }) => {
    await visit(page, "screen=backups", "Backups");
    await page.getByRole("button", { name: new RegExp(`^${label} · \\d+$`) }).click();
    await safety.checkpoint();
    await content(page);
  });
}

test("full: every listed inventory entity and its nested tabs", async ({ page, safety }) => {
  test.setTimeout(30 * 60_000);
  await visit(page, "screen=inventory", "Devices");
  const response = await browserGet<{ devices: DeviceSummary[] }>(page, "/devices");
  expect(response.status).toBe(200);
  expect(response.body.devices.length > 0, "Inventory coverage requires stored devices").toBe(true);
  const seen = new Set<string>();
  for (const device of response.body.devices) {
    const key = device.cluster_member_ref ?? device.device_id;
    if (seen.has(key)) continue;
    seen.add(key);
    // Query identifiers stay inside the browser; test names and failures contain none.
    const query = device.cluster_member_ref ? `cluster_ref=${encodeURIComponent(key)}` : `device_id=${encodeURIComponent(key)}`;
    await visit(page, `screen=inventory&${query}`, "Devices");
    await safety.checkpoint();
    const name = device.cluster_member_ref ? "Cluster detail" : "Device detail";
    const list = page.getByRole("tablist", { name, exact: true });
    expect(await list.count() > 0, "Inventory entity detail did not open").toBe(true);
    await nestedTabs(page, page.getByRole("main"), safety.checkpoint);
    await content(page);
  }
});
