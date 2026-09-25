import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { formatUtc, relativeAge } from "./time";

/**
 * The product's shared state vocabulary (UI_VISUAL_REVIEW_2026_09_23_FABLE.md §4). One component for every
 * empty, not-evaluated, restricted and error state, one timestamp, one role chip, one vendor badge -- so the
 * same fact looks the same on every screen. Status colour is reserved for state words; role and vendor are
 * identity and render neutral or in their own identity token.
 */

export type StateVariant = "empty" | "not_evaluated" | "restricted" | "error";

const VARIANT: Record<StateVariant, { icon: "info" | "clock" | "lock" | "warning"; word: string; ink: string; bg: string }> = {
  empty: { icon: "info", word: "", ink: m3.onSurfaceVar, bg: m3.scLow },
  not_evaluated: { icon: "clock", word: "NOT EVALUATED", ink: m3.neutralInk, bg: m3.scLow },
  restricted: { icon: "lock", word: "Restricted", ink: m3.onPrimaryContainer, bg: m3.primaryContainer },
  error: { icon: "warning", word: "", ink: m3.onErrorContainer, bg: m3.errorContainer },
};

/**
 * Empty: nothing exists yet, plus what would create it. Not evaluated: evidence not produced yet.
 * Restricted: an RBAC refusal -- intended behaviour, so it never looks like a fault and never offers Retry.
 * Error: a transient failure, a human sentence with the code in mono beneath, and Retry.
 */
export function StatePanel({ variant, title, body, code, action }: {
  readonly variant: StateVariant;
  readonly title: string;
  readonly body?: ReactNode;
  readonly code?: string | null;
  readonly action?: ReactNode;
}) {
  const v = VARIANT[variant];
  return (
    <Card role={variant === "error" ? "alert" : "status"} data-state={variant}
      sx={{ bgcolor: v.bg, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", borderRadius: "10px", p: 2,
            display: "flex", gap: 1.5, alignItems: "flex-start" }}>
      <Box sx={{ color: v.ink, display: "flex", pt: 0.25 }}><Icon name={v.icon} size={20} /></Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600, color: m3.onSurface }}>
          {v.word && variant !== "empty" ? <Box component="span" sx={{ color: v.ink, mr: 1 }}>{v.word} ·</Box> : null}
          {title}
        </Typography>
        {body ? <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5, maxWidth: 760 }}>{body}</Typography> : null}
        {code ? <Typography sx={{ fontFamily: MONO, fontSize: 11.5, color: m3.onSurfaceVar, mt: 0.5 }}>{code}</Typography> : null}
      </Box>
      {variant !== "restricted" && action ? <Box sx={{ flex: "none" }}>{action}</Box> : null}
    </Card>
  );
}

/** What an API failure means for the viewer: a 403 or ACTION_REFUSED is a restriction, anything else an error. */
export function isRestricted(error: unknown): boolean {
  if (!error) return false;
  const e = error as { status?: number; message?: string; code?: string };
  const text = `${e.code ?? ""} ${e.message ?? ""} ${typeof error === "string" ? error : ""}`;
  return e.status === 403 || /ACTION_REFUSED|forbidden|do not have permission|not permitted/i.test(text);
}

/** The refusal copy for one area, in the LDAP tab's pattern (the one the review called right). */
export function RestrictedPanel({ area, role }: { readonly area: string; readonly role?: string }) {
  return (
    <StatePanel variant="restricted" title={area}
      body={`${role ? `This needs the ${role} role. ` : ""}This account can not view or change it.`} />
  );
}

/** A timestamp: `2026-09-22 22:57:54` in GMT+3, optional relative age, full UTC ISO on hover. */
export function Ts({ at, relative = false, seconds = true }: { readonly at: string | null | undefined; readonly relative?: boolean; readonly seconds?: boolean }) {
  if (!at) return <Box component="span" sx={{ color: m3.neutralInk }}>UNKNOWN</Box>;
  return (
    <Tooltip title={`${at} (UTC reference)`}>
      <Box component="span" sx={{ fontFamily: MONO, fontSize: "0.95em", whiteSpace: "nowrap" }}>
        {formatUtc(at, seconds)}
        {relative ? <Box component="span" sx={{ fontFamily: "inherit", color: m3.onSurfaceVar, ml: 0.75, fontSize: "0.92em" }}>{relativeAge(at)}</Box> : null}
      </Box>
    </Tooltip>
  );
}

/**
 * An HA role. Role is not health (review §3): ACTIVE filled dark, STANDBY / PASSIVE outlined, both with the
 * word; colour stays for a member that is down or unreachable, which is a state and renders through a status
 * chip, not here.
 */
export function RoleChip({ role, dense = false }: { readonly role: string | null | undefined; readonly dense?: boolean }) {
  const word = (role ?? "").trim().toUpperCase();
  if (!word) return <Box component="span" sx={{ color: m3.neutralInk, fontSize: 11 }}>UNKNOWN</Box>;
  const active = word === "ACTIVE" || word === "MASTER" || word === "PRIMARY";
  return (
    <Box component="span" data-role={word}
      sx={{ display: "inline-flex", alignItems: "center", height: dense ? 20 : 22, px: 1, borderRadius: "6px",
            fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", whiteSpace: "nowrap",
            bgcolor: active ? m3.onSurface : "transparent", color: active ? m3.scLowest : m3.onSurface,
            border: `1px solid ${active ? m3.onSurface : m3.outline}` }}>
      {word}
    </Box>
  );
}

/** The vendor's display name; the raw hint when the product does not know it (never guessed as Check Point). */
export function vendorDisplayName(vendor: string | null | undefined): string {
  switch (vendor) {
    case "check_point": return "Check Point";
    case "palo_alto": return "Palo Alto Networks";
    case "radware": return "Radware";
    case "infoblox": return "Infoblox";
    case "bluecoat": return "Blue Coat";
    case "cisco_asa": return "Cisco ASA";
    case "fortinet": return "Fortinet";
    case "pulse_secure": return "Pulse Secure";
    default: return vendor ? vendor : "Vendor UNKNOWN";
  }
}

export type VendorKind = "check_point" | "palo_alto" | "vsx" | string | null | undefined;

/** The vendor monogram used on every screen: an outlined chip with an identity swatch; never a status colour. */
export function VendorBadge({ vendor, vsx = false, size = 28 }: { readonly vendor: VendorKind; readonly vsx?: boolean; readonly size?: number }) {
  const kind = vsx ? "vsx" : vendor === "check_point" ? "cp" : vendor === "palo_alto" ? "pan"
    : vendor === "radware" ? "rdw" : vendor === "infoblox" ? "ibx" : vendor === "bluecoat" ? "bc" : vendor === "cisco_asa" ? "asa" : vendor === "fortinet" ? "ftnt" : vendor === "pulse_secure" ? "pls" : "unknown";
  const label = kind === "cp" ? "CP" : kind === "pan" ? "PAN" : kind === "vsx" ? "VSX" : kind === "rdw" ? "RDW" : kind === "ibx" ? "IBX" : kind === "bc" ? "BC" : kind === "asa" ? "ASA" : kind === "ftnt" ? "FTNT" : kind === "pls" ? "PLS" : "?";
  const swatch = kind === "cp" ? m3.cp : kind === "pan" ? m3.pan : kind === "vsx" ? m3.vsx : m3.outline;
  const name = kind === "vsx" ? "Check Point VSX" : kind === "unknown" ? "Vendor UNKNOWN" : vendorDisplayName(vendor);
  return (
    <Tooltip title={name}>
      <Box component="span" aria-label={name}
        sx={{ display: "inline-flex", alignItems: "center", gap: 0.6, height: size * 0.82, px: 0.9, borderRadius: "6px",
              border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, fontSize: 11, fontWeight: 700,
              letterSpacing: "0.04em", color: m3.onSurface, flex: "none" }}>
        <Box component="span" sx={{ width: 7, height: 7, borderRadius: "2px", bgcolor: swatch }} />
        {label}
      </Box>
    </Tooltip>
  );
}

/** A value that is not evidenced: the word, never 0 or a dash, with the reason on hover. */
export function Unknown({ word = "UNKNOWN", reason }: { readonly word?: "UNKNOWN" | "NOT EVALUATED"; readonly reason?: string }) {
  return (
    <Tooltip title={reason ?? ""} disableHoverListener={!reason}>
      <Box component="span" sx={{ color: m3.neutralInk, fontWeight: 600, letterSpacing: "0.02em" }}>{word}</Box>
    </Tooltip>
  );
}
