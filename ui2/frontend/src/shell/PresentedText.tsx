import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { MONO, m3 } from "../theme/m3Theme";
import { formatStamp, relativeAge } from "./time";

/**
 * A presented identifier: the plain label first, the raw value on hover (default) or as a muted suffix
 * (`suffix`). When the map did not know the identifier the label already is the raw value, so nothing is repeated.
 */
export function Presented({ label, raw, suffix = false }: {
  readonly label: string;
  readonly raw?: string | null;
  /** Show the raw value after the label in muted monospace instead of only on hover. */
  readonly suffix?: boolean;
}) {
  const hasRaw = Boolean(raw) && raw !== label;
  if (!hasRaw) return <Box component="span">{label}</Box>;
  if (suffix) {
    return (
      <Box component="span" title={raw ?? undefined}>
        {label}
        <Box component="span" sx={{ ml: 0.75, fontFamily: MONO, fontSize: "0.85em", color: m3.onSurfaceVar }}>{raw}</Box>
      </Box>
    );
  }
  return (
    <Tooltip title={<Box component="span" sx={{ fontFamily: MONO }}>{raw}</Box>}>
      <Box component="span" tabIndex={0} data-raw={raw}>{label}</Box>
    </Tooltip>
  );
}

/**
 * The product's display stamp, `2026-10-18 06:49 GMT+3`, with the relative age as a muted suffix; the UTC reference
 * value is on hover. Absent value: the word UNKNOWN.
 */
export function Stamp({ at, age = true, now, stacked = false, compact = false }: {
  readonly at: string | null | undefined; readonly age?: boolean; readonly now?: Date;
  /** Put the relative age on a second line, for a narrow table column. */
  readonly stacked?: boolean;
  /** Only the relative age (`14 h ago`), the exact GMT+3 stamp and the UTC value on hover: for a fixed-width fleet column. */
  readonly compact?: boolean;
}) {
  if (!at) return <Box component="span" sx={{ color: m3.neutralInk }}>UNKNOWN</Box>;
  const stamp = formatStamp(at);
  if (compact) {
    return (
      <Box component="time" dateTime={at} title={`${stamp} · ${at} (UTC reference)`} data-stamp={stamp} sx={{ whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
        {stamp === at ? stamp : relativeAge(at, now)}
      </Box>
    );
  }
  return (
    <Tooltip title={`${at} (UTC reference)`}>
      <Box component="time" dateTime={at} sx={{ whiteSpace: "nowrap" }}>
        <Box component="span" sx={{ fontVariantNumeric: "tabular-nums" }}>{stamp}</Box>
        {age && stamp !== at ? <Box component="span" sx={stacked ? { display: "block", color: m3.onSurfaceVar, fontSize: "0.85em" } : { ml: 0.75, color: m3.onSurfaceVar, fontSize: "0.92em" }}>{relativeAge(at, now)}</Box> : null}
      </Box>
    </Tooltip>
  );
}
