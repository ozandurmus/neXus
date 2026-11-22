import { bundleDevice } from "../screens/SupportBundlesScreen";
import type { SourceRead } from "../screens/overview/attentionRules";

/**
 * Availability model for the gated actions on the unit dossier (concept rows 19, 23, 24). The Estate view never runs an
 * action: each one is a link to the standard screen for this unit (`standard=1`). Availability is derived only from data
 * the standard screens already read: the static-route target list (`listStaticRoutePreviews`), the diagnostic target list
 * and its `canExecute` flag (`listFmgDiagnosticTargets`), and the support-bundle device rule (`bundleDevice`).
 *
 * `checking` (read in flight) and `unknown` (read failed) are never shown as available. `unknown` keeps the link to the
 * standard view, because the standard view makes the decision and shows its own refusal.
 */

export type EstateActionKey = "static_routes" | "diagnostics" | "support_bundle";

export type EstateActionAvailability = "available" | "unavailable" | "unknown" | "checking";

export interface EstateActionUnit {
  readonly deviceId: string;
  readonly vendor: string;
  readonly role: string;
}

export interface EstateActionReads {
  /** `device_id` of every static-route target the standard Static routes panel lists; `null` while the read is in flight. */
  readonly staticRouteTargets: SourceRead<readonly { readonly device_id: string }[]> | null;
  /** The diagnostic targets and whether this session may execute diagnostics; `null` while the read is in flight. */
  readonly diagnosticTargets: SourceRead<{ readonly targets: readonly { readonly deviceId: string }[]; readonly canExecute: boolean }> | null;
}

export interface EstateActionSpec {
  readonly key: EstateActionKey;
  readonly label: string;
  readonly availability: EstateActionAvailability;
  readonly reason: string;
  /** Link text on the standard view, saying where the link goes. */
  readonly linkLabel: string;
  /** Link to the standard view pre-filtered to this unit; `null` when the action is unavailable or still being checked. */
  readonly href: string | null;
}

export const ESTATE_ACTION_LABELS: Readonly<Record<EstateActionKey, string>> = {
  static_routes: "Static routes",
  diagnostics: "Run diagnostics",
  support_bundle: "Support bundle",
};

const ESTATE_ACTION_LINK_LABELS: Readonly<Record<EstateActionKey, string>> = {
  static_routes: "Static routes (standard view)",
  diagnostics: "Run a diagnostic (standard view)",
  support_bundle: "Support bundle (standard view)",
};

const ROLE_REASON = "Your role does not include this action on the standard view.";

function standardHref(screen: string, deviceId: string, extra = ""): string {
  return `?screen=${screen}${extra}&device_id=${encodeURIComponent(deviceId)}&standard=1`;
}

function staticRouteSpec(unit: EstateActionUnit, reads: EstateActionReads): EstateActionSpec {
  const base = { key: "static_routes" as const, label: ESTATE_ACTION_LABELS.static_routes, linkLabel: ESTATE_ACTION_LINK_LABELS.static_routes };
  const href = standardHref("operations", unit.deviceId, "&tab=routes");
  const read = reads.staticRouteTargets;
  if (read === null) return { ...base, availability: "checking", reason: "Checking whether this unit is a static-route target…", href: null };
  if (read.state === "restricted") return { ...base, availability: "unavailable", reason: ROLE_REASON, href: null };
  if (read.state !== "ok") return { ...base, availability: "unknown", reason: "Static-route targets could not be read. The standard view decides.", href };
  if (read.value.some((t) => t.device_id === unit.deviceId)) {
    return { ...base, availability: "available", reason: "This unit is a static-route target on the standard view.", href };
  }
  return { ...base, availability: "unavailable", reason: "This unit is not a static-route target on the standard view (vendor or HA role).", href: null };
}

function diagnosticsSpec(unit: EstateActionUnit, reads: EstateActionReads): EstateActionSpec {
  const base = { key: "diagnostics" as const, label: ESTATE_ACTION_LABELS.diagnostics, linkLabel: ESTATE_ACTION_LINK_LABELS.diagnostics };
  const href = standardHref("diagnostics", unit.deviceId);
  const read = reads.diagnosticTargets;
  if (read === null) return { ...base, availability: "checking", reason: "Checking approved diagnostics for this unit…", href: null };
  if (read.state === "restricted") return { ...base, availability: "unavailable", reason: ROLE_REASON, href: null };
  if (read.state !== "ok") return { ...base, availability: "unknown", reason: "Diagnostic targets could not be read. The standard view decides.", href };
  if (!read.value.targets.some((t) => t.deviceId === unit.deviceId)) {
    return { ...base, availability: "unavailable", reason: "No approved diagnostic target for this unit's vendor.", href: null };
  }
  if (!read.value.canExecute) {
    return { ...base, availability: "unavailable", reason: "Your session can read diagnostics but not run them.", href: null };
  }
  return { ...base, availability: "available", reason: "An approved diagnostic exists for this unit's vendor.", href };
}

function supportBundleSpec(unit: EstateActionUnit): EstateActionSpec {
  const base = { key: "support_bundle" as const, label: ESTATE_ACTION_LABELS.support_bundle, linkLabel: ESTATE_ACTION_LINK_LABELS.support_bundle };
  const href = standardHref("support_bundles", unit.deviceId);
  if (!bundleDevice({ vendor_hint: unit.vendor, role: unit.role })) {
    return {
      ...base,
      availability: "unavailable",
      reason: "No support bundle is collected for this vendor or role on the standard view.",
      href: null,
    };
  }
  // The standard view does not expose a role read for bundles, so the role is decided by the server when the request is made.
  return { ...base, availability: "available", reason: "Collectable items exist for this vendor. Your role is checked when you request one.", href };
}

/** The three dossier actions for one unit, in display order. Pure: no request is made and nothing runs. */
export function unitEstateActions(unit: EstateActionUnit, reads: EstateActionReads): readonly EstateActionSpec[] {
  return [staticRouteSpec(unit, reads), diagnosticsSpec(unit, reads), supportBundleSpec(unit)];
}
