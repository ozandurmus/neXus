import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { DENSITY, RADIUS, TYPE } from "../theme/tokens";

/**
 * The one object header (design language 2026-10-19, component 2; product review §4.2): used by a device, a cluster,
 * a virtual system, a manager, a policy container, a compliance control and a packet case. Three parts, always in
 * the same place: the title row (avatar, name, the health badges beside the name, the one action on the right), the
 * identity row (vendor · form · model · version · parent), and the state row (read ages, then the plane pills in the
 * fixed order SEE → VERIFY → TRACE → RECOVER → OPERATE, with an optional link on the right). No composite score: a
 * pill is tinted only when its own word is a problem word, and the eye finds red by position. Comfortable density.
 */
export interface ObjectHeaderProps {
  /** The vendor avatar or badge in front of the name. */
  readonly avatar?: ReactNode;
  readonly title: ReactNode;
  /** Replaces the title (a selected virtual system: parent link, `›`, scope name). */
  readonly breadcrumb?: ReactNode;
  /** The health badges beside the name: `Live`, `Identity verified`, the HA role. */
  readonly badges?: ReactNode;
  /** Row 1: identity pills (vendor · form · model · version · parent). */
  readonly identity?: ReactNode;
  /** Row 2, left: the read ages (`Platform read 4 h ago · Config read 16 h ago`). */
  readonly readAges?: ReactNode;
  /** Row 2: the plane pills in their fixed order. */
  readonly state?: ReactNode;
  /** The one action, right of the title. */
  readonly action?: ReactNode;
  /** A pill-shaped link that belongs to the object (`Open assigned policy →`), right of the state row. */
  readonly link?: ReactNode;
  /** Below the rows (member cards, a summary line). */
  readonly children?: ReactNode;
  readonly testId?: string;
}

export function ObjectHeader({ avatar, title, breadcrumb, badges, identity, readAges, state, action, link, children, testId = "entity-header" }: ObjectHeaderProps) {
  const gutter = `${DENSITY.comfortable.gutter}px`;
  return (
    <Box data-testid={testId} data-density="comfortable"
      sx={{ display: "flex", flexDirection: "column", gap: 1.5, p: gutter, bgcolor: m3.scLowest, borderRadius: `${RADIUS.card}px`,
            border: `1px solid ${m3.outlineVar}`, boxShadow: "none" }}>
      {/* Title row: the title block takes the width and wraps its badges inside; the action keeps the right edge of the
          title row instead of dropping to a row of its own (R13: tab content closer to the top). */}
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, minWidth: 0, flex: "1 1 420px" }}>
          {avatar}
          <Box sx={{ minWidth: 0, flex: "1 1 auto" }}>
            <Box data-testid="object-title-row" sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
              <Typography component="h2" sx={{ ...TYPE.pageTitle, color: m3.onSurface, m: 0, minWidth: 0, overflowWrap: "anywhere" }}>
                {breadcrumb ?? title}
              </Typography>
              {badges}
            </Box>
            {identity ? <Box data-testid="object-identity-row" sx={{ mt: 0.5, display: "flex", alignItems: "center", columnGap: 0.75, rowGap: 0.5, flexWrap: "wrap" }}>{identity}</Box> : null}
          </Box>
        </Box>
        {action ? <Box data-testid="object-action" sx={{ flex: "none" }}>{action}</Box> : null}
      </Box>
      {(readAges || state || link) ? (
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
          <Box data-testid="object-state-row" sx={{ minWidth: 0, display: "flex", alignItems: "center", columnGap: 1.5, rowGap: 0.75, flexWrap: "wrap" }}>
            {readAges}
            {state}
          </Box>
          {link}
        </Box>
      ) : null}
      {children}
    </Box>
  );
}
