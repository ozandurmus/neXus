/**
 * One timestamp format for the whole product: `2026-09-22 22:57:54`, in Turkey time (Europe/Istanbul, GMT+3 --
 * Product Owner 2026-09-23), the zone declared once in the top bar; relative age in muted text where useful; the
 * full UTC ISO value on hover and in every export (the audit trail keeps one reference clock).
 */
export const DISPLAY_TZ = "Europe/Istanbul";
export const DISPLAY_TZ_LABEL = "GMT+3";

const FMT_S = new Intl.DateTimeFormat("sv-SE", { timeZone: DISPLAY_TZ, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
const FMT_M = new Intl.DateTimeFormat("sv-SE", { timeZone: DISPLAY_TZ, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });

/** Display time (GMT+3). Kept under its old name so every caller moves at once. */
export function formatUtc(iso: string | null | undefined, withSeconds = true): string {
  if (!iso) return "UNKNOWN";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return (withSeconds ? FMT_S : FMT_M).format(d).replace("T", " ");
}
export const formatTime = formatUtc;

/** "02:00 UTC" cron hour → "05:00 GMT+3", for labels next to a server-side (UTC) schedule. */
export function utcHourToLocal(hour: number, minute = 0): string {
  const d = new Date(Date.UTC(2026, 0, 15, hour, minute));
  return `${FMT_M.format(d).slice(11)} ${DISPLAY_TZ_LABEL}`;
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
