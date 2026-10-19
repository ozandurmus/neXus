import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { MONO, m3 } from "../theme/m3Theme";
import { Timestamp } from "./Timestamp";

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

/** The product's display stamp: the one `<Timestamp>` under its older name. */
export const Stamp = Timestamp;
