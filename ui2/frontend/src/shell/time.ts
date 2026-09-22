/**
 * One timestamp format for the whole product (review §4): `2026-09-22 22:57:54`, UTC, the zone declared once in
 * the top bar; relative age in muted text where useful; full precision on hover and in exports.
 */

export function formatUtc(iso: string | null | undefined, withSeconds = true): string {
  if (!iso) return "UNKNOWN";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, "0");
  const date = `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`;
  const time = `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}${withSeconds ? `:${p(d.getUTCSeconds())}` : ""}`;
  return `${date} ${time}`;
}

export function relativeAge(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "UNKNOWN";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "UNKNOWN";
  const s = Math.max(0, (now.getTime() - t) / 1000);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 172800) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

/** Duration in the product's one style: `52.1 s`, `3 min 12 s`. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return "—";
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms % 60_000) / 1000);
  return `${m} min ${s} s`;
}
