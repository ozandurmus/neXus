import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { m3 } from "../theme/m3Theme";
import { TYPE } from "../theme/tokens";
import { formatStamp, formatUtc, relativeAge } from "./time";

/**
 * The one timestamp (design language 2026-10-19, component 7): `2026-10-18 06:49 GMT+3 · 4 h ago` in the mono
 * step, the exact value (seconds, and the UTC ISO instant the audit trail keeps) on hover. An absent value is the
 * word UNKNOWN, never 0 or a dash. `compact` writes only the age for a fixed-width fleet column, with the stamp on
 * hover; `stacked` puts the age on a second line; `seconds` keeps the seconds visible for an audit or log row.
 */
export interface TimestampProps {
  readonly at: string | null | undefined;
  /** Write the relative age after the stamp (default on). */
  readonly age?: boolean;
  /** Put the relative age on a second line, for a narrow table column. */
  readonly stacked?: boolean;
  /** Only the relative age; the exact stamp and the UTC value on hover. */
  readonly compact?: boolean;
  /** Keep seconds in the visible stamp (audit, session and log rows). */
  readonly seconds?: boolean;
  readonly now?: Date;
}

export function Timestamp({ at, age = true, stacked = false, compact = false, seconds = false, now }: TimestampProps) {
  if (!at) return <Box component="span" data-stamp="UNKNOWN" sx={{ color: m3.neutralInk, fontWeight: 600, letterSpacing: "0.02em" }}>UNKNOWN</Box>;
  const stamp = seconds ? `${formatUtc(at, true)} GMT+3` : formatStamp(at);
  const parseable = stamp !== at;
  const relative = parseable ? relativeAge(at, now) : null;
  const exact = `${at} (UTC reference)`;
  const mono = { fontFamily: TYPE.mono.fontFamily, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight, fontVariantNumeric: "tabular-nums" } as const;
  if (compact) {
    return (
      <Box component="time" dateTime={at} title={`${stamp} · ${exact}`} data-stamp={stamp}
        sx={{ ...mono, whiteSpace: "nowrap" }}>
        {relative ?? stamp}
      </Box>
    );
  }
  return (
    <Tooltip title={exact}>
      <Box component="time" dateTime={at} data-stamp={stamp} sx={{ ...mono, whiteSpace: "nowrap", display: stacked ? "inline-block" : "inline" }}>
        <Box component="span">{stamp}</Box>
        {age && relative
          ? <Box component="span" sx={stacked
              ? { display: "block", color: m3.onSurfaceVar, fontFamily: "Roboto, sans-serif", fontSize: TYPE.label.fontSize, lineHeight: TYPE.label.lineHeight }
              : { ml: 0.75, color: m3.onSurfaceVar, fontFamily: "Roboto, sans-serif", fontSize: TYPE.label.fontSize }}>
              {stacked ? relative : `· ${relative}`}
            </Box>
          : null}
      </Box>
    </Tooltip>
  );
}
