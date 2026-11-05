/** The product screens this build routes, named for their M3 frame. */
export type ScreenId =
  | "overview"
  | "reports"
  | "inventory"
  | "configuration"
  | "policy"
  | "trace"
  | "changes"
  | "compliance"
  | "backups"
  | "operations"
  | "administration"
  | "diagnostics"
  | "offline_analysis"
  | "packet_analysis"
  | "sanitizer";

export const SCREEN_IDS: readonly ScreenId[] = [
  "overview",
  "reports",
  "inventory",
  "configuration",
  "policy",
  "trace",
  "changes",
  "compliance",
  "backups",
  "operations",
  "administration",
  "diagnostics",
  "offline_analysis",
  "packet_analysis",
  "sanitizer",
];

export function isScreenId(value: string | null): value is ScreenId {
  return value !== null && (SCREEN_IDS as readonly string[]).includes(value);
}
