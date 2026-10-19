import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../theme/m3Theme";
import { RADIUS, ROLE_COLORS, TYPE, UNKNOWN_HATCH } from "../theme/tokens";
import { Icon } from "./Icon";
import { statusWord, vendorLabel } from "./presenters";
import { Timestamp } from "./Timestamp";

/**
 * The product's shared state vocabulary (UI_VISUAL_REVIEW_2026_09_23_FABLE.md §4; design language 2026-10-19,
 * component 5). One card for every empty, not-evaluated, unknown, refused and error state, one timestamp, one role
 * chip, one vendor badge -- so the same fact looks the same on every screen. Status colour is reserved for state
 * words; role and vendor are identity and render neutral or in their own identity token.
 */

export type StateVariant = "empty" | "not_evaluated" | "unknown" | "restricted" | "error";

const VARIANT: Record<StateVariant, { icon: "info" | "clock" | "lock" | "warning"; word: string; ink: string; bg: string }> = {
  empty: { icon: "info", word: "", ink: m3.onSurfaceVar, bg: m3.scLow },
  not_evaluated: { icon: "clock", word: statusWord("NOT_EVALUATED"), ink: m3.neutralInk, bg: m3.scLow },
  unknown: { icon: "info", word: "UNKNOWN", ink: m3.neutralInk, bg: m3.scLow },
  restricted: { icon: "lock", word: "Restricted", ink: m3.onPrimaryContainer, bg: m3.primaryContainer },
  error: { icon: "warning", word: "", ink: m3.onErrorContainer, bg: m3.errorContainer },
};

/**
 * The empty / refused / unknown card: icon, state word, one sentence of reason, one next step. Empty: nothing exists
 * yet, plus what would create it. Not evaluated: evidence not produced yet. Unknown: the read made no claim.
 * Restricted: an RBAC refusal -- intended behaviour, so it never looks like a fault and never offers Retry.
 * Error: a transient failure, a human sentence with the code in mono beneath, and Retry.
 */
export function StatePanel({ variant, title, body, code, action, announce, nextStep, titleAs = "p", children }: {
  readonly variant: StateVariant;
  readonly title: string;
  readonly body?: ReactNode;
  readonly code?: string | null;
  readonly action?: ReactNode;
  /** The live-region role; an error is announced as an alert unless the caller says the finding is a state, not a failed screen. */
  readonly announce?: "alert" | "status" | "none";
  /** The one next step: `Next window 12:00`, `Operators and Security Administrators can start a collection.` */
  readonly nextStep?: ReactNode;
  /** The element the title renders as; a screen-level empty state is a heading. */
  readonly titleAs?: "p" | "h3" | "h4";
  /** Anything that belongs inside the card below the sentence (a count list, a form). */
  readonly children?: ReactNode;
}) {
  const v = VARIANT[variant];
  const hatched = variant === "unknown" || variant === "not_evaluated";
  return (
    <Card role={announce === "none" ? undefined : (announce ?? (variant === "error" ? "alert" : "status"))} data-state={variant}
      sx={{ bgcolor: v.bg, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", borderRadius: `${RADIUS.card}px`, p: 2,
            display: "flex", gap: 1.5, alignItems: "flex-start" }}>
      <Box sx={{ color: v.ink, display: "flex", pt: 0.25 }}><Icon name={v.icon} size={20} /></Box>
      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Typography component={titleAs} sx={{ ...TYPE.section, color: m3.onSurface, m: 0 }}>
          {v.word && variant !== "empty"
            ? <Box component="span" data-state-word sx={{ color: v.ink, mr: 1, ...(hatched ? { px: 0.75, borderRadius: `${RADIUS.pill}px`, bgcolor: ROLE_COLORS.unknown.bg, backgroundImage: UNKNOWN_HATCH, fontSize: TYPE.label.fontSize, fontWeight: 600, letterSpacing: "0.02em", verticalAlign: "middle" } : {}) }}>{v.word}{hatched ? "" : " ·"}</Box>
            : null}
          {title}
        </Typography>
        {body ? <Typography variant="body1" sx={{ color: m3.onSurfaceVar, maxWidth: 760 }}>{body}</Typography> : null}
        {code ? <Typography sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight, color: m3.onSurfaceVar }}>{code}</Typography> : null}
        {nextStep ? <Typography variant="body2" data-next-step sx={{ color: m3.onSurface, fontWeight: 500 }}>{nextStep}</Typography> : null}
        {children}
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
export function RestrictedPanel({ area, role, nextStep }: { readonly area: string; readonly role?: string; readonly nextStep?: ReactNode }) {
  return (
    <StatePanel variant="restricted" title={area}
      body={`${role ? `This needs the ${role} role. ` : ""}This account can not view or change it.`}
      nextStep={nextStep} />
  );
}

/**
 * A timestamp under its older name: the one `<Timestamp>` (`2026-10-18 06:49 GMT+3 · 4 h ago`, exact on hover).
 * `seconds` keeps the seconds visible for an audit, session or log row.
 */
export function Ts({ at, relative = true, seconds = false, now }: { readonly at: string | null | undefined; readonly relative?: boolean; readonly seconds?: boolean; readonly now?: Date }) {
  return <Timestamp at={at} age={relative} seconds={seconds} now={now} />;
}

/**
 * An HA role. Role is not health (review §3): ACTIVE filled dark, STANDBY / PASSIVE outlined, both with the
 * word; colour stays for a member that is down or unreachable, which is a state and renders through a status
 * pill, not here.
 */
export function RoleChip({ role, dense = false }: { readonly role: string | null | undefined; readonly dense?: boolean }) {
  const word = (role ?? "").trim().toUpperCase();
  if (!word) return <Box component="span" sx={{ color: m3.neutralInk, fontSize: TYPE.label.fontSize }}>UNKNOWN</Box>;
  const active = word === "ACTIVE" || word === "MASTER" || word === "PRIMARY";
  return (
    <Box component="span" data-role={word} data-pill="role"
      sx={{ display: "inline-flex", alignItems: "center", height: dense ? 20 : 22, px: 1, borderRadius: `${RADIUS.pill}px`,
            fontSize: TYPE.label.fontSize, lineHeight: TYPE.label.lineHeight, fontWeight: 600, letterSpacing: "0.04em", whiteSpace: "nowrap",
            bgcolor: active ? m3.onSurface : "transparent", color: active ? m3.scLowest : m3.onSurface,
            border: `1px solid ${active ? m3.onSurface : m3.outline}` }}>
      {word}
    </Box>
  );
}

/** The vendor's display name; the raw hint when the product does not know it (never guessed as Check Point). */
export function vendorDisplayName(vendor: string | null | undefined): string {
  return vendor ? vendorLabel(vendor) : "Vendor UNKNOWN";
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
        sx={{ display: "inline-flex", alignItems: "center", gap: 0.6, height: size * 0.82, px: 0.9, borderRadius: `${RADIUS.pill}px`,
              border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, fontSize: TYPE.label.fontSize, fontWeight: 700,
              letterSpacing: "0.04em", color: m3.onSurface, flex: "none" }}>
        <Box component="span" sx={{ width: 7, height: 7, borderRadius: "2px", bgcolor: swatch }} />
        {label}
      </Box>
    </Tooltip>
  );
}

/** A value that is not evidenced: the word, never 0 or a dash, with the reason on hover. `NOT EVALUATED` reads `Not evaluated`. */
export function Unknown({ word = "UNKNOWN", reason }: { readonly word?: "UNKNOWN" | "NOT EVALUATED"; readonly reason?: string }) {
  return (
    <Tooltip title={reason ?? ""} disableHoverListener={!reason}>
      <Box component="span" data-unknown sx={{ color: m3.neutralInk, fontWeight: 600, letterSpacing: "0.02em" }}>{statusWord(word)}</Box>
    </Tooltip>
  );
}
