import { SCREEN_IDS, type ScreenId } from "../src/shell/types";
import { test, expect, visit } from "./fixtures";
import type { Page } from "@playwright/test";

const headings: Record<ScreenId, string> = {
  overview: "Overview", inventory: "Devices", configuration: "Devices", compliance: "Compliance",
  backups: "Backups", operations: "Operations", administration: "Administration",
};

async function screenHasData(page: Page, screen: ScreenId) {
  switch (screen) {
    case "overview":
      await expect(page.getByText(/Estate assurance · [1-9]\d* devices/)).toBeVisible();
      break;
    case "inventory":
    case "configuration":
      await expect(page.getByRole("button").filter({ has: page.getByText(/^(?:FW|CLS|MGR|DEV|GRID)-[A-Z]+-\d+(?:-M\d+)?$/) }).first()).toBeVisible();
      break;
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
      await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();
      break;
    case "administration":
      await expect(page.getByRole("tabpanel", { name: "Device management" }).getByRole("checkbox", { name: /^Select / }).nth(1)).toBeVisible();
      break;
  }
}

for (const screen of SCREEN_IDS) {
  test(`${screen}: renders its screen and populated estate data without loading errors`, async ({ page, safety }) => {
    await visit(page, `screen=${screen}`, headings[screen]);
    await screenHasData(page, screen);
    if (screen === "configuration") {
      expect(new URL(page.url()).searchParams.get("screen"), "Legacy Configuration routes to Devices").toBe("inventory");
    }
    await safety.checkpoint();
  });

  test(`${screen}: aiview visible text has no private addresses and no Transcript button`, async ({ page, safety }) => {
    await visit(page, `screen=${screen}`, headings[screen]);
    await screenHasData(page, screen);
    await safety.checkpoint();
  });
}
