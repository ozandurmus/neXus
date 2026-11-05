/**
 * The role words under the signed-in name (UI review 2026-11-05, B7; Argon R-10, Fable F-6). The top bar used to show the
 * highest-ranked role token it knew, and `replay_viewer` (the aiview inspection persona) ends in `viewer` while the persona
 * can also carry an administrator token, so aiview read "Compliance Admin" -- a claim the persona does not have. The
 * persona's own word now comes first and wins. This only words a token for display; it grants and hides nothing
 * (AG-J3: the server decides every request, the page branches on no role).
 */
const LABELS: ReadonlyArray<readonly [suffix: string, label: string]> = [
  // The inspection persona first: `replay_viewer` also ends in `viewer`.
  ["replay_viewer", "aiview · read-only"],
  ["security_admin", "Security Admin"],
  ["compliance_admin", "Compliance Admin"],
  ["backup_admin", "Backup Admin"],
  ["onboarding_admin", "Onboarding Admin"],
  ["operation_admin", "Operation Admin"],
  ["operator", "Operator"],
  ["viewer", "Viewer"],
];

const words = (token: string) => token.replace(/^role:/, "").replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());

/** One token as words: its known label, else its name with the prefix and underscores removed. */
export function roleTokenLabel(token: string): string {
  return LABELS.find(([suffix]) => token.endsWith(suffix))?.[1] ?? words(token);
}

/** The one label for the bar: the inspection persona if the session carries it, else the first token in the order above; `No Role` when there is none. */
export function primaryRoleLabel(tokens: readonly string[]): string {
  if (tokens.length === 0) return "No Role";
  for (const [suffix, label] of LABELS) {
    if (tokens.some((t) => t.endsWith(suffix))) return label;
  }
  return words(tokens[0]);
}
