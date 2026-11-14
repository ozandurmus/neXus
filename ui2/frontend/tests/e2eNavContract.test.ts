import { expect, it } from "vitest";
import ts from "typescript";
import { ADMIN_PAGES, SCREEN_HEADINGS, TAB_COVERAGE } from "../src/shell/tabCoverage";
import { NAV_LEAVES } from "../src/shell/navModel";

// The ship's preview runs only the quick e2e suite; the full suite (every-tab.spec.ts) runs at night. On 2026-11-14 the
// nightly run failed because the full suite still expected the "Devices" heading on the Lifecycle and security view after
// the navigation moved it. This test is the preview-time guard for that class: every navigation entry must be visited by
// the full e2e at the entry's own address, under the heading the entry names, and that heading must be a title the
// screens actually render. It runs in vitest (milliseconds, no HOST-A), so every ship's pretest covers it.

/** The addresses the full e2e suite visits and the heading it waits for at each (e2e/every-tab.spec.ts). */
function e2eVisits(): Map<string, string> {
  const visits = new Map<string, string>();
  for (const [screen, heading] of Object.entries(SCREEN_HEADINGS)) visits.set(`screen=${screen}`, heading);
  for (const page of ADMIN_PAGES) visits.set(page.query, page.heading);
  for (const row of TAB_COVERAGE) if (row.query && row.heading) visits.set(row.query, row.heading);
  return visits;
}

it("visits every navigation entry at its address under the heading the entry names", () => {
  const visits = e2eVisits();
  for (const leaf of NAV_LEAVES) {
    const query = leaf.href.replace(/^\?/, "");
    const heading = visits.get(query);
    if (heading === leaf.label) continue;
    // An entry that opens a tab of a screen (Operations' children) is named as that tab; the e2e reaches it through the
    // screen's tab list, which must list the entry's label.
    const tabRow = TAB_COVERAGE.find((row) => row.screen === leaf.screen && !row.query && row.labels.includes(leaf.label));
    expect(tabRow, `navigation entry "${leaf.label}" (${leaf.href}): the full e2e expects heading "${heading ?? "none"}"`).toBeDefined();
  }
});

const sources = import.meta.glob("../src/screens/**/*.tsx", { eager: true, query: "?raw", import: "default" }) as Record<string, string>;

/** Literal `title` props of every ScreenHeader / CompactScreenHeader in the screens. */
function renderedTitles(): Set<string> {
  const titles = new Set<string>();
  for (const [path, source] of Object.entries(sources)) {
    const file = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const walk = (node: ts.Node) => {
      if ((ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) && /^(Compact)?ScreenHeader$/.test(node.tagName.getText(file))) {
        const title = node.attributes.properties.filter(ts.isJsxAttribute).find((a) => a.name.getText(file) === "title")?.initializer;
        if (title && ts.isStringLiteral(title)) titles.add(title.text);
        if (title && ts.isJsxExpression(title) && title.expression && ts.isStringLiteral(title.expression)) titles.add(title.expression.text);
      }
      ts.forEachChild(node, walk);
    };
    walk(file);
  }
  return titles;
}

it("waits only for headings a screen renders as its title", () => {
  const titles = renderedTitles();
  // Administration's page titles come from its page table; tabCoverage.test.ts binds those to ADMIN_PAGES.
  const admin = new Set(ADMIN_PAGES.map((p) => p.heading));
  for (const [query, heading] of e2eVisits()) {
    if (admin.has(heading)) continue;
    expect(titles.has(heading), `the full e2e waits for "${heading}" at ${query}, but no screen header has that title`).toBe(true);
  }
});
