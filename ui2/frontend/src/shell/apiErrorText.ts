import type { ApiError } from "../auth/adminApi";

/**
 * Shared text for a failed API call: the server's `error` string when present,
 * otherwise a generic request-failed line that carries the HTTP status if known.
 */
export function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}
