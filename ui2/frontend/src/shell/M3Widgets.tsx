import { useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import IconButton from "@mui/material/IconButton";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Divider from "@mui/material/Divider";
import Switch from "@mui/material/Switch";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { TONE_COLORS, type Tone } from "./tone";
import { Icon, type IconName } from "./Icon";

/**
 * The canvas's four button emphases (`M3Components`' "Actions" card): filled
 * for the one primary action per screen, tonal for a secondary action,
 * outlined for a neutral alternative, text for an action inside a card.
 */
export function M3Button({
  emphasis,
  icon,
  children,
  onClick,
  href,
}: {
  readonly emphasis: "filled" | "tonal" | "outlined" | "text";
  readonly icon?: IconName;
  readonly children: ReactNode;
  readonly onClick?: () => void;
  readonly href?: string;
}) {
  const byEmphasis = {
    filled: { bgcolor: m3.primary, color: m3.onPrimary, "&:hover": { bgcolor: m3.primary } },
    tonal: { bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, "&:hover": { bgcolor: m3.secondaryContainer } },
    outlined: { bgcolor: "transparent", color: m3.onSurface, border: `1px solid ${m3.outline}` },
    text: { bgcolor: "transparent", color: m3.primary, px: 1.5 },
  } as const;
  return (
    <Button
      href={href}
      onClick={onClick}
      startIcon={icon ? <Icon name={icon} size={20} /> : undefined}
      sx={{
        height: 40,
        px: 3,
        borderRadius: "20px",
        fontSize: 14,
        fontWeight: 500,
        letterSpacing: "0.1px",
        textTransform: "none",
        whiteSpace: "nowrap",
        ...byEmphasis[emphasis],
      }}
    >
      {children}
    </Button>
  );
}

/**
 * The component and drawer states the `M3Components` frame specifies,
 * shared here so the six screens draw from one definition apiece instead of
 * repeating the same markup. Every one of these is presentational: none of
 * them submits anywhere, because no frozen contract in this movement
 * authorizes a write path for what the canvas depicts (enrollment,
 * exclusion, collection toggles). Where the canvas implies an action this
 * build cannot perform, the control is shown disabled and says why, per the
 * canvas's own "a capability that exists but cannot run here is shown and
 * explained, never a bare greyed control" rule.
 */

export function StatusChip({
  tone,
  label,
  dense = false,
}: {
  readonly tone: Tone;
  readonly label: string;
  readonly dense?: boolean;
}) {
  const colors = TONE_COLORS[tone];
  return (
    <Chip
      size="small"
      label={label}
      sx={{
        bgcolor: colors.bg,
        color: colors.fg,
        borderRadius: "8px",
        height: dense ? 22 : 26,
        fontSize: dense ? 11 : 12,
        fontWeight: 500,
      }}
    />
  );
}

/** The canvas's `m3-tab` strip: an underline indicator in primary colour. */
export function M3Tabs({
  tabs,
  ariaLabel,
  initial = 0,
}: {
  readonly tabs: readonly string[];
  readonly ariaLabel: string;
  readonly initial?: number;
}) {
  const [value, setValue] = useState(initial);
  return (
    <Tabs
      value={value}
      onChange={(_event, next: number) => setValue(next)}
      aria-label={ariaLabel}
      variant="scrollable"
      scrollButtons={false}
      sx={{
        minHeight: 48,
        borderBottom: `1px solid ${m3.outlineVar}`,
        "& .MuiTabs-indicator": { backgroundColor: m3.primary, height: 3 },
      }}
    >
      {tabs.map((t) => (
        <Tab
          key={t}
          label={t}
          sx={{
            minHeight: 48,
            textTransform: "none",
            fontSize: 14,
            fontWeight: 500,
            letterSpacing: "0.1px",
            color: m3.onSurfaceVar,
            "&.Mui-selected": { color: m3.primary },
          }}
        />
      ))}
    </Tabs>
  );
}

/**
 * A `m3-switch` row. Every use in this build is `disabled`: none of the
 * scopes it depicts (inventory/configuration collection, class 1 backup)
 * has a write path in this movement, so the control shows the current
 * (always off, in an empty database) state rather than inviting a toggle
 * that would go nowhere.
 */
export function ToggleRow({
  label,
  checked,
  helperText,
}: {
  readonly label: string;
  readonly checked: boolean;
  readonly helperText?: string;
}) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <Typography variant="body2">{label}</Typography>
        <Switch
          size="small"
          checked={checked}
          disabled
          onChange={() => {}}
          sx={{
            "& .MuiSwitch-track": { bgcolor: m3.scHighest },
            "&.Mui-disabled .MuiSwitch-track": { opacity: 1, bgcolor: m3.scHighest },
          }}
        />
      </Box>
      {helperText ? <Typography variant="body2">{helperText}</Typography> : null}
    </Box>
  );
}

export interface CapabilityMenuItem {
  readonly label: string;
  /** Shown, disabled, with this trailing note -- the canvas's "console only" pattern. */
  readonly disabledReason?: string;
  readonly selected?: boolean;
  /** Renders a divider above this item, matching the canvas's grouping. */
  readonly dividerBefore?: boolean;
}

/**
 * The canvas's device overflow menu. Opening it never contacts a device;
 * every entry is either a read-evidence action or an explained no-op.
 */
export function CapabilityMenu({
  ariaLabel,
  items,
}: {
  readonly ariaLabel: string;
  readonly items: readonly CapabilityMenuItem[];
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  return (
    <>
      <IconButton aria-label={ariaLabel} onClick={(e) => setAnchor(e.currentTarget)} sx={{ color: m3.onSurfaceVar }}>
        <Icon name="more" size={20} />
      </IconButton>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        {items.flatMap((item) => {
          const entry = (
            <MenuItem
              key={item.label}
              disabled={Boolean(item.disabledReason)}
              selected={item.selected}
              onClick={() => setAnchor(null)}
              sx={{ fontSize: 14, letterSpacing: "0.1px", gap: 1.5, minWidth: 240 }}
            >
              <Box sx={{ flex: 1 }}>{item.label}</Box>
              {item.disabledReason ? (
                <Typography variant="body2" sx={{ fontSize: 11 }}>{item.disabledReason}</Typography>
              ) : null}
            </MenuItem>
          );
          return item.dividerBefore ? [<Divider key={`${item.label}-divider`} sx={{ my: 1 }} />, entry] : [entry];
        })}
      </Menu>
    </>
  );
}

export function HeaderActionsRow({ children }: { readonly children: ReactNode }) {
  return <Box sx={{ display: "flex", gap: 1.25, alignItems: "center" }}>{children}</Box>;
}
