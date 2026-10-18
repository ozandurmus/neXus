/** A byte count in the unit an operator reads (`162.1 MB`); the exact count belongs on hover (see {@link bytesTitle}). */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "unknown size";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** The exact byte count for a `title` attribute. */
export function bytesTitle(bytes: number): string {
  return Number.isFinite(bytes) && bytes >= 0 ? `${bytes.toLocaleString("en-US")} bytes` : "size unknown";
}
