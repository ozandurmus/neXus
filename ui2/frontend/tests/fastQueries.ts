import { fireEvent, screen, waitFor } from "@testing-library/react";

/**
 * Lookups that stay cheap on a large page. jsdom has no layout, but Testing Library's role-with-name and label queries
 * still compute an accessible name per element and, for each, walk every <label> in the document (dom-accessibility-api
 * `labels` lookups), which is quadratic in page size. On the policy screen that was most of the run time of a test and
 * made it miss the default timeout when the machine was busy. These match the same attributes with one selector.
 */
export function getByAria(root: ParentNode, label: string): HTMLElement {
  const found = root.querySelectorAll<HTMLElement>(`[aria-label="${label.replace(/["\\]/g, "\\$&")}"]`);
  if (found.length !== 1) throw new Error(`Expected exactly one element with aria-label "${label}", found ${found.length}`);
  return found[0];
}

export function findByAria(label: string, root: ParentNode = document.body): Promise<HTMLElement> {
  return waitFor(() => getByAria(root, label));
}

/** A button identified by its own text (not its aria-label). */
export function findButtonByText(text: string): Promise<HTMLElement> {
  return screen.findByText(text, { selector: "button" });
}

/** Ensure a tree node button (source or container) is expanded: opens it only when it is collapsed, so auto-expanded nodes are not toggled shut. */
export async function expandByText(text: string): Promise<HTMLElement> {
  const button = await findButtonByText(text);
  if (button.getAttribute("aria-expanded") === "false") fireEvent.click(button);
  return button;
}

/** A form field named by a visible <label> (e.g. an MUI TextField label), resolved through the label's `for` id. */
export function getFieldByLabel(text: string): HTMLInputElement {
  const labels = [...document.querySelectorAll("label")].filter(label => label.textContent?.replace(/\s*\*$/, "") === text);
  if (labels.length !== 1 || !labels[0].htmlFor) throw new Error(`Expected exactly one <label for> reading "${text}", found ${labels.length}`);
  const field = document.getElementById(labels[0].htmlFor);
  if (!(field instanceof HTMLInputElement)) throw new Error(`Label "${text}" does not point at an input`);
  return field;
}

/**
 * The body rows of a table, without its header row: the elements `getAllByRole("row").slice(1)` returns for a table with
 * one header row. The role query decides, for every row of a 50-120 row page, whether it is hidden from the accessibility
 * tree (a computed style per element and per ancestor); the structural selector reads the same rows at no cost.
 */
export function bodyRowsOf(root: ParentNode = document): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>("tbody tr"));
}

/** A row's data cells, as `within(row).getAllByRole("cell")` returns them. */
export function cellsOf(row: ParentNode): HTMLElement[] {
  return Array.from(row.querySelectorAll<HTMLElement>("td"));
}

/** The one sort-header button whose own text is `name`: what `getByRole("button", { name })` finds for a `SortHeaderCell`. */
export function getSortButton(root: ParentNode, name: string): HTMLElement {
  const found = Array.from(root.querySelectorAll<HTMLElement>('th [role="button"]')).filter((b) => b.textContent?.trim() === name);
  if (found.length !== 1) throw new Error(`Expected exactly one sort header button "${name}", found ${found.length}`);
  return found[0];
}

/** Buttons inside `root` whose text matches `pattern`: what `getAllByRole("button", { name: pattern })` finds when a button is named by its content. */
export function getButtonsMatching(root: ParentNode, pattern: RegExp): HTMLElement[] {
  const found = Array.from(root.querySelectorAll<HTMLElement>("button, [role=\"button\"]")).filter((b) => pattern.test((b.textContent ?? "").trim()));
  if (found.length === 0) throw new Error(`Expected a button whose text matches ${pattern}, found none`);
  return found;
}

/** The one column header named `name` (its `aria-label`, else its own text): what `getByRole("columnheader", { name })` finds. */
export function getColumnHeader(root: ParentNode, name: string): HTMLElement {
  const found = Array.from(root.querySelectorAll<HTMLElement>("th")).filter((h) => (h.getAttribute("aria-label") ?? h.textContent?.trim()) === name);
  if (found.length !== 1) throw new Error(`Expected exactly one column header "${name}", found ${found.length}`);
  return found[0];
}
