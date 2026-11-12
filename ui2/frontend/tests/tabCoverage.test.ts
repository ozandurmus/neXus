import { expect, it } from "vitest";
import ts from "typescript";
import { TAB_COVERAGE, BACKUP_SECTIONS, SCREEN_HEADINGS, ADMIN_PAGES } from "../src/shell/tabCoverage";
import { NAV_ENTRIES } from "../src/shell/navModel";
import { SCREEN_IDS } from "../src/shell/types";

const sources = import.meta.glob("../src/screens/**/*.tsx", { eager: true, query: "?raw", import: "default" }) as Record<string, string>;
function labels(expression: ts.Node): string[] {
  if (ts.isStringLiteral(expression)) return [expression.text];
  if (ts.isConditionalExpression(expression)) return [...labels(expression.whenTrue), ...labels(expression.whenFalse)];
  throw new Error("A dynamic navigation label needs explicit coverage");
}
function panelLabels(node: ts.Node): string[] {
  if (ts.isObjectLiteralExpression(node)) {
    const properties = node.properties.filter(ts.isPropertyAssignment);
    if (properties.some(p => p.name.getText() === "panel")) {
      return labels(properties.find(p => p.name.getText() === "label")!.initializer);
    }
  }
  const found: string[] = [];
  ts.forEachChild(node, child => { found.push(...panelLabels(child)); });
  return found;
}
it("covers each screen/tab-list/label pair in the actual navigation definitions", () => {
  expect(Object.keys(SCREEN_HEADINGS).sort()).toEqual([...SCREEN_IDS].sort());
  const actual = new Map<string, Set<string>>();
  const adminPages: string[] = [];
  const add = (group: string, values: string[]) => actual.set(group, new Set([...(actual.get(group) ?? []), ...values]));
  for (const [path, source] of Object.entries(sources)) {
    const file = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const walk = (node: ts.Node) => {
      if (ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) {
        const attrs = node.attributes.properties.filter(ts.isJsxAttribute);
        const name = attrs.find(a => a.name.getText(file) === "ariaLabel")?.initializer;
        const tabs = attrs.find(a => a.name.getText(file) === "tabs")?.initializer;
        if (name && ts.isStringLiteral(name) && tabs) {
          if (name.text === "Inventory context") {
            expect(tabs.getText(file)).toContain("contexts.map"); // Masked VS labels: full E2E traverses these dynamically.
          } else add(name.text, panelLabels(tabs));
        }
      }
      if (path.endsWith("/AdministrationScreen.tsx") && ts.isObjectLiteralExpression(node)) {
        // Administration's pages (navigation redesign 2026-11-12): a page object names its one tab strip (`tabList`) and
        // lists its sections, which are that strip's tabs. A page with a single section has no strip and no row here.
        const props = node.properties.filter(ts.isPropertyAssignment);
        const tabList = props.find(p => p.name.getText(file) === "tabList");
        const sections = props.find(p => p.name.getText(file) === "sections");
        const name = props.find(p => p.name.getText(file) === "label");
        if (sections && name) adminPages.push(labels(name.initializer)[0]);
        if (tabList && sections) add(labels(tabList.initializer)[0], panelLabels(sections.initializer));
      }
      ts.forEachChild(node, walk);
    };
    walk(file);
  }
  expect([...actual.keys()].sort()).toEqual(TAB_COVERAGE.map(row => row.tabList).sort());
  for (const row of TAB_COVERAGE) expect([...(actual.get(row.tabList) ?? [])].sort()).toEqual([...row.labels].sort());
  // The five pages, their headings and the navigation's own labels are the same words.
  expect(adminPages).toEqual(ADMIN_PAGES.map(p => p.heading));
  expect(NAV_ENTRIES.flatMap(e => e.kind === "group" && e.id === "administration" ? e.children.map(c => [c.label, c.href]) : []))
    .toEqual(ADMIN_PAGES.map(p => [p.heading, `?${p.query}`]));
  expect(SCREEN_HEADINGS.administration).toBe(ADMIN_PAGES[0].heading);
  for (const row of TAB_COVERAGE.filter(r => r.screen === "administration")) expect(ADMIN_PAGES).toContainEqual({ query: row.query, heading: row.heading });
  const backups = sources["../src/screens/BackupScreen.tsx"];
  expect([...backups.matchAll(/\{chip\("[^"]+", "([^"]+)"/g)].map(m => m[1])).toEqual(BACKUP_SECTIONS);
});
