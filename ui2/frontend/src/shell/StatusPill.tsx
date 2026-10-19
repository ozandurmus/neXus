import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { m3 } from "../theme/m3Theme";
import { RADIUS, ROLE_COLORS, TYPE, UNKNOWN_HATCH, isUnknownWord, type StateRole } from "../theme/tokens";
import { relativeAge } from "./time";

/**
 * The one status pill (design language 2026-10-19, component 1): a word with a colour from the role table, an
 * optional count, an optional age and an optional suffix, written `Word · 3 · 4 h ago · Role: not permitted`.
 * A state is a word with a colour, never a colour alone; a count is neutral; an absence is hatched UNKNOWN. The
 * pill is a link when it carries `href`, a button when it carries `onClick`, otherwise plain text.
 */
export interface StatusPillProps {
  readonly tone: StateRole;
  /** The state word, sentence case (`Completed`, `Not ready`); roles stay upper case (`ACTIVE`). */
  readonly word: ReactNode;
  /** A number written after the word: `Failed · 27`. */
  readonly count?: number | string | null;
  /** An ISO instant written as a relative age after the count: `· 4 h ago`; a string is written as it is. */
  readonly age?: string | null;
  /** The control-suffix variant: `· Role: not permitted`, `· Next window: 12:00`. */
  readonly suffix?: string | null;
  /** The exact values behind the words, on hover. */
  readonly title?: string;
  readonly href?: string;
  readonly onClick?: () => void;
  /** 20 px instead of 22 px, for a table cell or a tree row. */
  readonly dense?: boolean;
  /** A key for tests and analytics (`data-fact`). */
  readonly fact?: string;
  readonly ariaLabel?: string;
  readonly now?: Date;
}

const SEP = " · ";

export function StatusPill({ tone, word, count, age, suffix, title, href, onClick, dense = false, fact, ariaLabel, now }: StatusPillProps) {
  const unknown = tone === "unknown" || ((tone === "neutral") && typeof word === "string" && isUnknownWord(word));
  const role = unknown ? "unknown" : tone;
  const colors = ROLE_COLORS[role];
  const ageText = age ? (/^\d{4}-\d{2}-\d{2}T/.test(age) ? relativeAge(age, now) : age) : null;
  const interactive = Boolean(href || onClick);
  const sx = {
    display: "inline-flex", alignItems: "center", gap: 0, height: dense ? 20 : 22, px: 1,
    borderRadius: `${RADIUS.pill}px`, whiteSpace: "nowrap", maxWidth: "100%",
    bgcolor: colors.bg, color: colors.fg, border: `1px solid ${colors.border}`,
    backgroundImage: colors.hatched ? UNKNOWN_HATCH : "none",
    fontSize: TYPE.label.fontSize, lineHeight: TYPE.label.lineHeight, fontWeight: role === "role" ? 600 : 500, fontFamily: "inherit",
    letterSpacing: unknown ? "0.02em" : 0, textDecoration: "none", cursor: interactive ? "pointer" : "default",
    "&:hover": { textDecoration: interactive ? "underline" : "none" },
    "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 },
  } as const;
  const content = (
    <>
      {word}
      {count !== undefined && count !== null && count !== "" && <Box component="span">{SEP}{count}</Box>}
      {ageText && <Box component="span" sx={{ opacity: 0.85 }}>{SEP}{ageText}</Box>}
      {suffix && <Box component="span" sx={{ opacity: 0.85 }}>{SEP}{suffix}</Box>}
    </>
  );
  const shared = { "data-pill": role, "data-fact": fact, "aria-label": ariaLabel } as const;
  const body = href
    ? <Box component="a" href={href} onClick={onClick} {...shared} sx={sx}>{content}</Box>
    : onClick
      ? <Box component="button" type="button" onClick={onClick} {...shared} sx={sx}>{content}</Box>
      : <Box component="span" {...shared} sx={sx}>{content}</Box>;
  return title ? <Tooltip title={title}><Box component="span" sx={{ display: "inline-flex", maxWidth: "100%" }}>{body}</Box></Tooltip> : body;
}

/** A row of pills that wraps, with the product's one gap. */
export function PillRow({ children, ariaLabel, testId }: { readonly children: ReactNode; readonly ariaLabel?: string; readonly testId?: string }) {
  return (
    <Box data-testid={testId} aria-label={ariaLabel} sx={{ display: "flex", alignItems: "center", columnGap: 0.75, rowGap: 0.5, flexWrap: "wrap", minWidth: 0 }}>
      {children}
    </Box>
  );
}
