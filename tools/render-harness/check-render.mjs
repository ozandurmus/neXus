/**
 * Headless load + navigation smoke test for a generated SecurityExpert report.
 *
 *   bun tools/render-harness/check-render.mjs <path-to-index.html>
 *
 * Checks, in order:
 *   1. the single inline <script> parses (new Function) -- catches the 0.7.4a
 *      class of bug (a payload literal corrupted so the whole script is dead);
 *   2. it executes in a DOM without throwing and without console.error;
 *   3. every .module-nav-item switches its [data-module-panel] to .active;
 *   4. every .tab / .config-tab click runs without a new console error.
 *
 * Exit 0 = pass. Non-zero = fail, with a report on stderr.
 */
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";

const file = process.argv[2];
if (!file) {
  console.error("usage: bun check-render.mjs <index.html>");
  process.exit(2);
}

const html = readFileSync(file, "utf8");
const problems = [];

// --- 1. parse the inline script on its own -------------------------------
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>\s*<\/body>/);
if (!scriptMatch) {
  console.error("FAIL: no inline <script> before </body>");
  process.exit(1);
}
try {
  // eslint-disable-next-line no-new-func
  new Function(scriptMatch[1]);
} catch (err) {
  console.error(`FAIL: inline <script> does not parse: ${err.message}`);
  process.exit(1);
}

// --- 2. execute in a DOM ------------------------------------------------
// Build the DOM from the markup, then run the inline script explicitly in the
// window scope. happy-dom does not reliably auto-execute a script inserted via
// document.write(); window.eval() is deterministic and still exercises the real
// script against a real DOM + localStorage + history.
const window = new Window({ url: "http://localhost/", settings: { disableCSSFileLoading: true } });
const consoleErrors = [];
window.console.error = (...args) => consoleErrors.push(args.map(String).join(" "));
window.addEventListener("error", (e) => consoleErrors.push(`window.onerror: ${e.message || e.error}`));
window.addEventListener("unhandledrejection", (e) => consoleErrors.push(`unhandledrejection: ${e.reason}`));

window.document.write(html.replace(/<script>[\s\S]*?<\/script>/, "<script></script>"));
await window.happyDOM.waitUntilComplete();
try {
  window.eval(scriptMatch[1]);
} catch (err) {
  console.error(`FAIL: inline <script> threw during execution: ${err.message}\n${err.stack || ""}`);
  process.exit(1);
}
await window.happyDOM.waitUntilComplete();

const { document } = window;
if (typeof window.switchModule !== "function") {
  problems.push("script executed but window.switchModule is not defined (bootstrap did not run)");
}

if (consoleErrors.length) {
  problems.push(`console errors during load:\n    ${consoleErrors.join("\n    ")}`);
}

// --- 3. module navigation --------------------------------------------
const navButtons = [...document.querySelectorAll(".module-nav-item")];
if (navButtons.length < 4) {
  problems.push(`only ${navButtons.length} .module-nav-item buttons found (expected >= 6)`);
}
for (const button of navButtons) {
  const target = button.getAttribute("data-module");
  const before = consoleErrors.length;
  button.click();
  await window.happyDOM.waitUntilComplete();
  const panel = document.querySelector(`[data-module-panel="${target}"]`);
  if (!panel) {
    problems.push(`nav "${target}": no [data-module-panel="${target}"] in the DOM`);
    continue;
  }
  if (!panel.classList.contains("active")) {
    problems.push(`nav "${target}": panel did not become .active on click`);
  }
  if (!button.classList.contains("active")) {
    problems.push(`nav "${target}": button did not become .active on click`);
  }
  if (consoleErrors.length > before) {
    problems.push(`nav "${target}": ${consoleErrors.length - before} console error(s) on click:\n    ${consoleErrors.slice(before).join("\n    ")}`);
  }
}

// --- 4. inner tabs (best-effort: must not throw) ---------------------
for (const selector of [".tab[data-tab]", ".config-tab[data-config-tab]"]) {
  for (const tab of document.querySelectorAll(selector)) {
    const before = consoleErrors.length;
    tab.click();
    await window.happyDOM.waitUntilComplete();
    if (consoleErrors.length > before) {
      const label = tab.getAttribute("data-tab") || tab.getAttribute("data-config-tab");
      problems.push(`tab "${label}": ${consoleErrors.length - before} console error(s) on click:\n    ${consoleErrors.slice(before).join("\n    ")}`);
    }
  }
}

await window.happyDOM.close();

if (problems.length) {
  console.error(`FAIL (${problems.length}):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(`PASS: script parses, executes clean, ${navButtons.length} nav modules + all inner tabs switch with no console errors`);
process.exit(0);
