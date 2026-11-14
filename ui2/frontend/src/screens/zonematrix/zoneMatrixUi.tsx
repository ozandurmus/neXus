import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../../theme/m3Theme";
import { RADIUS, TYPE } from "../../theme/tokens";
import { shortHash } from "./zoneMatrixApi";

/** What the zone matrix tabs share: the mono step, the section title, the bordered panel and the short hash. */
export const mono = { fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight } as const;

export const panelSx = {
  border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, bgcolor: m3.scLowest, p: 2,
  display: "flex", flexDirection: "column", gap: 1.25,
} as const;

export function SectionTitle({ children, note }: { readonly children: ReactNode; readonly note?: ReactNode }) {
  return (
    <Box>
      <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>{children}</Typography>
      {note ? <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{note}</Typography> : null}
    </Box>
  );
}

/** The first twelve characters of a content hash, the whole value on hover. */
export function Hash({ value }: { readonly value: string | null | undefined }) {
  return <Box component="span" title={value ?? undefined} sx={mono}>{shortHash(value)}</Box>;
}

/** One short muted line (the audit-log line, a hint). */
export function Hint({ children, id }: { readonly children: ReactNode; readonly id?: string }) {
  return <Typography id={id} variant="body2" sx={{ color: m3.onSurfaceVar }}>{children}</Typography>;
}
