import { m3 } from "../theme/m3Theme";

/**
 * The single disabled look for every action button. Spread into a Button's `sx` (the `&.Mui-disabled` selector
 * out-ranks any fill the caller sets for the enabled state): a pale surface, muted text that reads on it in both
 * themes, and a hairline. Before this, a filled button kept its primary fill while MUI greyed the text, which
 * rendered dark text on a dark blue fill (review 2026-10-17, "Collect now" and "Run Fleet Backup").
 */
export const DISABLED_STYLE = {
  "&.Mui-disabled": {
    bgcolor: m3.sc,
    color: m3.onSurfaceVar,
    border: `1px solid ${m3.outlineVar}`,
    boxShadow: "none",
  },
} as const;
