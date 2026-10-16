import type { ApiError } from "../../auth/adminApi";

/** Byte counts as an operator reads them (captures run to 2 GB). */
export function packetBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "UNKNOWN";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes, unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return unit === 0 ? `${bytes} B` : `${value.toFixed(1)} ${units[unit]}`;
}

/** The server's refusal, in its own words (closed codes and reasons; never a file name). */
export function packetError(err: unknown, fallback: string): string {
  const failure = err as ApiError | undefined;
  if (failure?.status === 403) return "Your role does not allow this action.";
  if (failure?.status === 0) return "The request did not complete. Check the connection and retry.";
  const reason = failure?.body?.reason ?? failure?.body?.error;
  return typeof reason === "string" && reason ? reason : fallback;
}
