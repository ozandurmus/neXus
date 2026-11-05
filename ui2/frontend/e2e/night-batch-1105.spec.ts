import { test, expect, visit } from "./fixtures";

// Night batch 2026-11-05, read-only checks only (the suite never clicks an action). Behaviour that needs a writing
// role (Collect menu items, Collect logs now, fleet log collection) is proven by the vitest integration tests; here
// the aiview persona must NOT be offered those actions.

test("aiview: Operations > System health offers no fleet log collection action", async ({ page, safety }) => {
  await visit(page, "screen=operations", "Operations");
  await page.getByRole("tablist", { name: "Operations sections" }).getByRole("tab", { name: "System health", exact: true }).click();
  await safety.checkpoint();
  await expect(page.getByRole("button", { name: /Collect logs \(all supported devices\)/ })).toHaveCount(0);
});

test("aiview: Jobs table sorts on the server from a column header and returns to the default order", async ({ page, safety }) => {
  await visit(page, "screen=operations&tab=jobs", "Operations");
  const table = page.getByRole("table", { name: "Job logs" });
  await expect(table.getByRole("row").nth(1)).toBeVisible();
  const header = table.getByRole("columnheader", { name: /Type/ });
  const sorted = page.waitForResponse((r) => new URL(r.url()).pathname === "/api/v2/jobs" && new URL(r.url()).searchParams.has("sort"));
  await header.getByRole("button").click();
  expect((await sorted).status()).toBe(200);
  await expect(header).toHaveAttribute("aria-sort", "ascending");
  await header.getByRole("button").click();
  await expect(header).toHaveAttribute("aria-sort", "descending");
  await header.getByRole("button").click();
  await expect(header).not.toHaveAttribute("aria-sort");
  await safety.checkpoint();
});

test("Jobs: every listed row names its target (a masked name or an explicit 'No target recorded')", async ({ page, safety }) => {
  await visit(page, "screen=operations&tab=jobs", "Operations");
  const rows = page.getByRole("table", { name: "Job logs" }).getByRole("row");
  await expect(rows.nth(1)).toBeVisible();
  const texts = await rows.allInnerTexts();
  expect(texts.slice(1).every((t) => t.trim().length > 0), "A job row has no visible content").toBe(true);
  expect(texts.some((t) => /unknown device/i.test(t)), "A job row shows 'Unknown device' instead of its target").toBe(false);
  await safety.checkpoint();
});

test("Operations System health and the Changes timeline reach a settled state (no endless loader)", async ({ page, safety }) => {
  await visit(page, "screen=operations", "Operations");
  await page.getByRole("tablist", { name: "Operations sections" }).getByRole("tab", { name: "System health", exact: true }).click();
  await safety.checkpoint(); // waits for all reads to finish
  await expect(page.getByRole("tabpanel", { name: "System health", exact: true }).getByRole("progressbar")).toHaveCount(0);
  // The change feed moved from Operations to the Changes screen (UI review 2026-11-05, C3).
  await visit(page, "screen=changes", "Changes");
  await safety.checkpoint();
  await expect(page.getByRole("tabpanel", { name: "Timeline", exact: true }).getByRole("progressbar")).toHaveCount(0);
});
