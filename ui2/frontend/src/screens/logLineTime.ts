/**
 * The device time of one raw log line, for the Logs tab's fixed time column (UI review 2026-10-18, F-8). Nothing is
 * converted or guessed: the text is the one the line itself carries, in the device's own clock and format.
 *
 *  - a Palo Alto line carries a `receive_time` field (JSON `"receive_time": "2026/10/18 06:11:02"` or `receive_time=...`);
 *  - a Check Point / Gaia line starts with the syslog stamp (`Oct 17 08:00:01`) or an ISO stamp; a Check Point daemon log
 *    (`cpd.elg`, `fwd.elg`) carries it in brackets after the process tag, `[cpd 1234 5678]@FW-X[18 Oct 11:13:23]`;
 *  - a line with neither (a continuation line, a stack trace) has no time: `null`, shown as an empty cell, never invented.
 */
const RECEIVE_TIME = /"?receive_time"?\s*[:=]\s*"?(\d{4}[/-]\d{2}[/-]\d{2}[T ]\d{2}:\d{2}:\d{2})/;
const SYSLOG_STAMP = /^\s*([A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})\b/;
const ISO_STAMP = /^\s*(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})/;
const ELG_STAMP = /^[^\n]{0,80}?\[(\d{1,2} [A-Z][a-z]{2}(?: \d{4})? \d{2}:\d{2}:\d{2})\]/;

export function lineDeviceTime(line: string): string | null {
  const field = RECEIVE_TIME.exec(line);
  if (field) return field[1].replace("T", " ");
  const syslog = SYSLOG_STAMP.exec(line);
  if (syslog) return syslog[1].replace(/\s+/g, " ");
  const iso = ISO_STAMP.exec(line);
  if (iso) return iso[1].replace("T", " ");
  const elg = ELG_STAMP.exec(line);
  return elg ? elg[1] : null;
}

export interface LineSeverity {
  /** The token exactly as the line writes it (`high`, `ERROR`). */
  readonly token: string;
  /** Where the token starts in the line, so the viewer colours that word and nothing else. */
  readonly index: number;
  /** `bad` for a critical/high/error token, `warn` for a medium/warning one; a low or informational token is left uncoloured. */
  readonly tone: "bad" | "warn" | null;
}

const PAN_SEVERITY = /"?severity"?\s*[:=]\s*"?(critical|high|medium|low|informational)\b/i;
/** An explicit level the line itself writes: `[ERROR]`, `ERROR:`, `<WARNING>` -- upper case, delimited, never a word inside a sentence. */
const LEVEL_TOKEN = /(?:^|[\s[<(])(EMERG|EMERGENCY|ALERT|CRIT|CRITICAL|ERR|ERROR|WARN|WARNING)(?=[:\]>)]|\s*:)/;
const BAD_LEVELS: ReadonlySet<string> = new Set(["EMERG", "EMERGENCY", "ALERT", "CRIT", "CRITICAL", "ERR", "ERROR"]);

/**
 * The severity a line carries in its own text, and only that (design language: a state is a word with a colour, and
 * no severity is ever invented). A Palo Alto record names it in a `severity` field; a daemon line may write a delimited
 * level token. A line with neither has no severity: `null`, and the viewer shows nothing for it.
 */
export function lineSeverity(line: string): LineSeverity | null {
  const pan = PAN_SEVERITY.exec(line);
  if (pan) {
    const token = pan[1];
    const low = token.toLowerCase();
    return { token, index: pan.index + pan[0].length - token.length, tone: low === "critical" || low === "high" ? "bad" : low === "medium" ? "warn" : null };
  }
  const level = LEVEL_TOKEN.exec(line);
  if (!level) return null;
  const token = level[1];
  return { token, index: level.index + level[0].indexOf(token), tone: BAD_LEVELS.has(token) ? "bad" : "warn" };
}

/** `00:15–06:11` for a window inside one day, the two full stamps otherwise; the device's own clock, never converted. */
export function windowWords(from: string | null | undefined, to: string | null | undefined): string | null {
  if (!from || !to) return null;
  const f = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(from);
  const t = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(to);
  if (!f || !t) return `${from} to ${to}`;
  return f[1] === t[1] ? `${f[2]}–${t[2]}` : `${f[1]} ${f[2]} – ${t[1]} ${t[2]}`;
}
