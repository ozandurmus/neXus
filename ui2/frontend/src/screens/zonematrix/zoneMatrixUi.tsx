import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../../theme/m3Theme";
import { RADIUS, TYPE } from "../../theme/tokens";
import { MATRIX_SEVERITIES, MATRIX_STATEMENTS, shortHash, type MatrixStatement } from "./zoneMatrixApi";

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

/** The first twelve characters of a content hash, the whole value on hover; UNKNOWN when absent. */
export function Hash({ value }: { readonly value: string | null | undefined }) {
  const short = shortHash(value);
  return <Box component="span" title={value || undefined} sx={mono}>{short || "UNKNOWN"}</Box>;
}

/** One short muted line (the audit-log line, a hint). */
export function Hint({ children, id }: { readonly children: ReactNode; readonly id?: string }) {
  return <Typography id={id} variant="body2" sx={{ color: m3.onSurfaceVar }}>{children}</Typography>;
}

/** True only for the three contract 4.1 statements. */
export function isKnownStatement(value: string | null | undefined): value is MatrixStatement {
  return MATRIX_STATEMENTS.some((s) => s.value === value);
}

/** Contract 4.1 statement in words; unrecognised or missing values render UNKNOWN, never an allow label. */
export function knownStatementWord(value: string | null | undefined): string {
  return MATRIX_STATEMENTS.find((s) => s.value === value)?.label ?? "UNKNOWN";
}

/** Contract 4.1 severity in words; unrecognised or missing values render UNKNOWN. */
export function knownSeverityWord(value: string | null | undefined): string {
  return MATRIX_SEVERITIES.find((s) => s.value === value)?.label ?? "UNKNOWN";
}
