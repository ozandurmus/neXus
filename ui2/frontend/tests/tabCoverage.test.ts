import { expect, it } from "vitest";
import ts from "typescript";
import { TAB_COVERAGE, BACKUP_SECTIONS, SCREEN_HEADINGS } from "../src/shell/tabCoverage";
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
        const props = node.properties.filter(ts.isPropertyAssignment);
        const tabs = props.find(p => p.name.getText(file) === "tabs");
        const name = props.find(p => p.name.getText(file) === "label");
        if (tabs && name) add(labels(name.initializer)[0], panelLabels(tabs.initializer));
      }
      ts.forEachChild(node, walk);
    };
    walk(file);
  }
  expect([...actual.keys()].sort()).toEqual(TAB_COVERAGE.map(row => row.tabList).sort());
  for (const row of TAB_COVERAGE) expect([...(actual.get(row.tabList) ?? [])].sort()).toEqual([...row.labels].sort());
  const backups = sources["../src/screens/BackupScreen.tsx"];
  expect([...backups.matchAll(/\{chip\("[^"]+", "([^"]+)"/g)].map(m => m[1])).toEqual(BACKUP_SECTIONS);
});
