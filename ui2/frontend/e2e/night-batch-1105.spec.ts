import { test, expect, visit } from "./fixtures";

// Night batch 2026-11-05, read-only checks only (the suite never clicks an action). Behaviour that needs a writing
// role (Collect menu items, Collect logs now, fleet log collection) is proven by the vitest integration tests; here
// the aiview persona must NOT be offered those actions.

test("aiview: Operations > Device health offers no fleet log collection action", async ({ page, safety }) => {
  await visit(page, "screen=operations", "Operations");
  await page.getByRole("tablist", { name: "Operations sections" }).getByRole("tab", { name: "Device health", exact: true }).click();
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

test("Operations Device health and the Changes timeline reach a settled state (no endless loader)", async ({ page, safety }) => {
  await visit(page, "screen=operations", "Operations");
  await page.getByRole("tablist", { name: "Operations sections" }).getByRole("tab", { name: "Device health", exact: true }).click();
  await safety.checkpoint(); // waits for all reads to finish
  await expect(page.getByRole("tabpanel", { name: "Device health", exact: true }).getByRole("progressbar")).toHaveCount(0);
  // The change feed moved from Operations to the Changes screen (UI review 2026-11-05, C3).
  await visit(page, "screen=changes", "Changes");
  await safety.checkpoint();
  await expect(page.getByRole("tabpanel", { name: "Timeline", exact: true }).getByRole("progressbar")).toHaveCount(0);
});

// Scheduled jobs (2026-11-10): every signed-in role reads the family list; only Security Admin and Operation Admin are
// offered a switch. The aiview persona must see the six families, no control, and no change reason.
test("aiview: Administration > Scheduled jobs is read-only and withholds the change reason", async ({ page, safety }) => {
  // PO 2026-11-13 amendment of F2: its own Administration page, unlocked; the old Operations and Platform addresses are
  // rewritten to this one (shell/legacyRoutes.ts).
  await visit(page, "screen=administration&tab=scheduled-jobs", "Scheduled jobs");
  const section = page.getByRole("region", { name: "Scheduled jobs", exact: true });
  const families = section.getByRole("list", { name: "Scheduled job families" });
  await expect(families).toBeVisible();
  await safety.checkpoint();
  for (const label of ["Discovery refresh", "Inventory collection", "Configuration refresh", "HA readiness", "Scheduled backup", "System log collection"]) {
    await expect(families.getByRole("heading", { name: label, exact: true })).toBeVisible();
  }
  await expect(section.getByRole("switch")).toHaveCount(0);
  await expect(section.getByRole("checkbox")).toHaveCount(0);
  await expect(section.getByText(/Read-only: turning a job family on or off needs the Security Admin or Operation Admin role/)).toBeVisible();
  // A reason is free text an administrator typed: under the masked view it is never rendered, only the withheld note.
  await expect(section.getByText(/^Reason: /)).toHaveCount(0);
  const reasons = section.getByTestId("scheduled-family-reason");
  for (const text of await reasons.allInnerTexts()) expect(text.trim()).toBe("Reason withheld (masked view)");
});
