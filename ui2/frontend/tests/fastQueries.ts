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
