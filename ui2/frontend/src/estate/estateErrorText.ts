/** The display text of a thrown or rejected value: its `message` when present, otherwise its string form. */
export function describeError(err: unknown): string {
  return String((err as { message?: string } | null)?.message ?? err);
}
