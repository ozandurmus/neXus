/**
 * The device time of one raw log line, for the Logs tab's fixed time column (UI review 2026-10-18, F-8). Nothing is
 * converted or guessed: the text is the one the line itself carries, in the device's own clock and format.
 *
 *  - a Palo Alto line carries a `receive_time` field (JSON `"receive_time": "2026/10/18 06:11:02"` or `receive_time=...`);
 *  - a Check Point / Gaia line starts with the syslog stamp (`Oct 17 08:00:01`) or an ISO stamp;
 *  - a line with neither (a continuation line, a stack trace) has no time: `null`, shown as an empty cell, never invented.
 */
const RECEIVE_TIME = /"?receive_time"?\s*[:=]\s*"?(\d{4}[/-]\d{2}[/-]\d{2}[T ]\d{2}:\d{2}:\d{2})/;
const SYSLOG_STAMP = /^\s*([A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})\b/;
const ISO_STAMP = /^\s*(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})/;

export function lineDeviceTime(line: string): string | null {
  const field = RECEIVE_TIME.exec(line);
  if (field) return field[1].replace("T", " ");
  const syslog = SYSLOG_STAMP.exec(line);
  if (syslog) return syslog[1].replace(/\s+/g, " ");
  const iso = ISO_STAMP.exec(line);
  return iso ? iso[1].replace("T", " ") : null;
}

/** `00:15–06:11` for a window inside one day, the two full stamps otherwise; the device's own clock, never converted. */
export function windowWords(from: string | null | undefined, to: string | null | undefined): string | null {
  if (!from || !to) return null;
  const f = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(from);
  const t = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(to);
  if (!f || !t) return `${from} to ${to}`;
  return f[1] === t[1] ? `${f[2]}–${t[2]}` : `${f[1]} ${f[2]} – ${t[1]} ${t[2]}`;
}
