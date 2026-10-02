import { SCREEN_IDS, type ScreenId } from "../src/shell/types";
import { test, expect, visit } from "./fixtures";
import type { Page } from "@playwright/test";

import { SCREEN_HEADINGS as headings } from "../src/shell/tabCoverage";

async function screenHasData(page: Page, screen: ScreenId, settleReads: () => Promise<void>) {
  switch (screen) {
    case "overview":
      await expect(page.getByText(/Estate assurance · [1-9]\d* devices/)).toBeVisible();
      break;
    case "inventory":
    case "configuration":
      await expect(page.getByRole("button").filter({ has: page.getByText(/^(?:FW|CLS|MGR|DEV|GRID)-[A-Z]+-\d+(?:-M\d+)?$/) }).first()).toBeVisible();
      break;
    case "policy": {
      // Settle each lazy read before counting children; collection actions are never clicked.
      await settleReads();
      const tree = page.getByRole("navigation", { name: "Management policies" });
      if (await tree.count() === 0) {
        await expect(page.getByRole("status", { name: "No policy snapshot" })).toBeVisible();
        break;
      }
      await expect(tree).toBeVisible();
      const sources = tree.getByRole("group", { name: "Policy source", exact: true });
      let selected = false;
      for (let s = 0; s < await sources.count() && !selected; s++) {
        const source = sources.nth(s);
        await source.getByRole("button", { expanded: false }).first().click();
        await settleReads();
        const containers = source.getByRole("group", { name: "Policy container", exact: true });
        for (let c = 0; c < await containers.count() && !selected; c++) {
          const container = containers.nth(c);
          await container.getByRole("button", { expanded: false }).click();
          await settleReads();
          const policy = container.getByRole("button", { pressed: false }).first();
          if (await policy.count() > 0) {
            await policy.click();
            await settleReads();
            await expect(page.getByRole("table", { name: "Policy rulebase" })
              .or(page.getByText("No matching rules.", { exact: true }))
              .or(page.getByText("Partial snapshot · incomplete", { exact: true })).first()).toBeVisible();
            selected = true;
          }
        }
      }
      if (!selected) await expect(page.getByRole("status", { name: "No collected policy" })).toBeVisible();
      break;
    }
    case "compliance": {
      const table = page.getByRole("table").filter({ has: page.getByRole("columnheader", { name: "Control · code & title" }) });
      // Six cells distinguish a real control from the one-cell loading/empty placeholder.
      await expect.poll(() => table.getByRole("row").nth(1).getByRole("cell").count()).toBe(6);
      break;
    }
    case "backups":
      await expect(page.getByRole("checkbox", { name: /^Backup target / }).first()).toBeVisible();
      break;
    case "operations":
      await expect(page.getByLabel("Readiness summary").getByRole("button")).toHaveCount(4);
      await expect(page.getByRole("list", { name: "HA clusters", exact: true }).getByRole("listitem").first()).toBeVisible();
      break;
    case "administration":
      await expect(page.getByRole("tabpanel", { name: "Device management" }).getByRole("checkbox", { name: /^Select / }).nth(1)).toBeVisible();
      break;
  }
}

for (const screen of SCREEN_IDS) {
  test(`${screen}: renders its screen and populated estate data without loading errors`, async ({ page, safety }) => {
    await visit(page, `screen=${screen}`, headings[screen]);
    await screenHasData(page, screen, safety.checkpoint);
    if (screen === "configuration") {
      expect(new URL(page.url()).searchParams.get("screen"), "Legacy Configuration routes to Devices").toBe("inventory");
    }
    await safety.checkpoint();
  });

  test(`${screen}: aiview visible text passes canary and Transcript checks`, async ({ page, safety }) => {
    await visit(page, `screen=${screen}`, headings[screen]);
    await screenHasData(page, screen, safety.checkpoint);
    await safety.checkpoint();
  });
}
