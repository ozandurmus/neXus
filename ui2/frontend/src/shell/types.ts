/** The seven product screens this build routes, named for their M3 frame. */
export type ScreenId =
  | "overview"
  | "inventory"
  | "configuration"
  | "compliance"
  | "operations"
  | "administration"
  | "audit";

export const SCREEN_IDS: readonly ScreenId[] = [
  "overview",
  "inventory",
  "configuration",
  "compliance",
  "operations",
  "administration",
  "audit",
];

export function isScreenId(value: string | null): value is ScreenId {
  return value !== null && (SCREEN_IDS as readonly string[]).includes(value);
}
