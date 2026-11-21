/**
 * Single source of truth for the product's user-visible display name.
 * Change `PRODUCT_NAME` and `PRODUCT_SHORT` here to rename the product across the UI.
 */
export const PRODUCT_NAME = "Overwatch";
export const PRODUCT_SHORT = "Overwatch";

/**
 * True when `name` matches the five-letter "neXus" spelling that the bespoke
 * option-D vector wordmark geometry was drawn for. Any other `PRODUCT_NAME`
 * renders the wordmark text directly from `name`.
 */
export function hasBespokeWordmark(name: string = PRODUCT_NAME): boolean {
  return name === "neXus";
}
