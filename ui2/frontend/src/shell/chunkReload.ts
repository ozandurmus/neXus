/**
 * A deploy replaces the hashed screen chunks. A tab opened before it still runs the old index and asks for chunks that no
 * longer exist; the import fails and the screen showed "This section could not be shown" until a manual reload (PO
 * 2026-11-24, seen right after a ship). A failed chunk import now reloads the page once, so the tab picks up the new
 * build. A second failure within a minute is not reloaded again (no loop); it reaches the error boundary as before.
 */
const KEY = "overwatch.chunkReloadAt";
const STALE_CHUNK = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Loading chunk .* failed/i;

export function isStaleChunkError(error: unknown): boolean {
  return error instanceof Error && STALE_CHUNK.test(error.message ?? "");
}

export function reloadOnceForStaleChunk(error: unknown, now: number = Date.now(),
  storage: Pick<Storage, "getItem" | "setItem"> | null = safeSession(), reload: () => void = () => window.location.reload()): boolean {
  if (!isStaleChunkError(error) || !storage) return false;
  const last = Number(storage.getItem(KEY) ?? "0");
  if (Number.isFinite(last) && now - last < 60_000) return false;
  storage.setItem(KEY, String(now));
  reload();
  return true;
}

function safeSession(): Storage | null {
  try { return window.sessionStorage; } catch { return null; }
}
