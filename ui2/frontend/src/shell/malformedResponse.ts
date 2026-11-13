/**
 * A response whose body is not the shape the screen reads (`{}`, a string, a list where an object belongs) is an error
 * state, not a crash (robustness pass 2026-11-13). The read helpers of a panel check the body with a guard and throw
 * {@link malformedResponse} when it fails; the panel's normal error handling then shows its error state with Retry.
 *
 * Nothing of the body is logged or put in the message: a body can carry names, hostnames and account tokens.
 */
export const MALFORMED_RESPONSE_CODE = "MALFORMED_RESPONSE";

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** An `ApiError`-shaped rejection (`status`, `body.code`, `body.message`) so the existing error describers read it unchanged. */
export function malformedResponse(what: string): { readonly status: number; readonly body: { readonly code: string; readonly message: string } } {
  console.error(`${what}: the response was not in the expected shape`);
  return { status: 200, body: { code: MALFORMED_RESPONSE_CODE, message: `The ${what} came back in a shape this page cannot show.` } };
}
