import { rewriteLegacyRoute } from "../shell/legacyRoutes";
import { isScreenId, type ScreenId } from "../shell/types";
import { parseAskMode, type AskMode } from "./estateAskModel";

export const ESTATE_LENSES = [
  "worst",
  "failover",
  "backups",
  "compliance",
  "exposure",
  "install",
  "health",
  "age",
] as const;

export type EstateLens = (typeof ESTATE_LENSES)[number];

export function isEstateLens(value: string | null | undefined): value is EstateLens {
  return Boolean(value && (ESTATE_LENSES as readonly string[]).includes(value));
}

export function parseEstateLens(value: string | null | undefined): EstateLens {
  return isEstateLens(value) ? value : "worst";
}

/** The Rewind moment(s) a dossier visit started from; the dossier itself always shows live data. */
export interface RewindOrigin {
  readonly at: string;
  readonly compare: string | null;
}

function rewindOriginFrom(params: URLSearchParams): { fromRewind: RewindOrigin } | Record<string, never> {
  const at = params.get("rewind_at")?.trim();
  if (!at) return {};
  return { fromRewind: { at, compare: params.get("rewind_compare")?.trim() || null } };
}

export type EstateRoute =
  | { readonly kind: "brief" }
  | { readonly kind: "wall"; readonly lens: EstateLens; readonly clusterRef: string | null }
  | { readonly kind: "dossier"; readonly deviceId: string; readonly fromRewind?: RewindOrigin }
  | { readonly kind: "failover"; readonly clusterRef: string | null }
  | { readonly kind: "changes"; readonly deviceId: string | null; readonly vendor: string | null }
  | { readonly kind: "compliance"; readonly deviceId: string | null; readonly family: string | null }
  | { readonly kind: "backups"; readonly query: string }
  | { readonly kind: "ask"; readonly mode: AskMode }
  | { readonly kind: "rewind"; readonly at: string | null; readonly compare: string | null }
  | { readonly kind: "cases" }
  | { readonly kind: "reports" };

/**
 * Resolves whether the current address renders inside the Estate view shell (Slice 1 + Slice 2: Brief, Estate wall,
 * Unit dossier, Failover board, Changes, Compliance lens, Backups lens, Ask workbench, Reports) or falls through to the standard screen
 * (Cases -> Packet analysis / Diagnostics / Support bundles, System -> Administration, Jobs,
 * Static routes, or any link carrying `standard=1` to open the standard view explicitly for device/admin actions).
 */
export function resolveEstateRoute(screen: ScreenId, pathname: string, search: string): EstateRoute | null {
  const params = new URLSearchParams(search);
  if (params.get("standard") === "1") {
    return null;
  }
  if (pathname.startsWith("/devices/")) {
    const id = decodeURIComponent(pathname.substring("/devices/".length)).trim();
    if (id) return { kind: "dossier", deviceId: id, ...rewindOriginFrom(params) };
  }
  if (screen === "overview") {
    return { kind: "brief" };
  }
  if (screen === "inventory" && params.get("view") !== "lifecycle") {
    if (params.get("view") === "rewind") {
      return { kind: "rewind", at: params.get("at")?.trim() || null, compare: params.get("compare")?.trim() || null };
    }
    const dossierId = params.get("device_id")?.trim();
    if (dossierId) {
      return { kind: "dossier", deviceId: dossierId, ...rewindOriginFrom(params) };
    }
    return {
      kind: "wall",
      lens: parseEstateLens(params.get("lens")),
      clusterRef: params.get("cluster_ref")?.trim() || null,
    };
  }
  if (screen === "operations") {
    const tab = params.get("tab");
    const job = params.get("job");
    if (!job && (!tab || tab === "ha")) {
      return {
        kind: "failover",
        clusterRef: params.get("cluster_ref")?.trim() || null,
      };
    }
    return null;
  }
  if (screen === "changes") {
    return {
      kind: "changes",
      deviceId: params.get("device_id")?.trim() || null,
      vendor: params.get("vendor")?.trim() || null,
    };
  }
  if (screen === "compliance" || pathname === "/compliance" || pathname === "/compliance/") {
    return {
      kind: "compliance",
      deviceId: params.get("device")?.trim() || null,
      family: params.get("family")?.trim() || null,
    };
  }
  if (screen === "backups" || pathname === "/backups" || pathname === "/backups/") {
    return {
      kind: "backups",
      query: params.get("q")?.trim() || "",
    };
  }
  if (screen === "trace") {
    return { kind: "ask", mode: parseAskMode(params.get("mode")) };
  }
  if (screen === "packet_analysis") {
    return { kind: "cases" };
  }
  if (screen === "reports") {
    return { kind: "reports" };
  }
  return null;
}

/**
 * When the viewer clicks "Estate" in the top bar's View switch while standing on a standard screen (either an
 * out-of-scope screen or one opened via `standard=1`), navigate to the matching Estate screen or lens so the switch
 * always lands in the Estate view immediately. Returns `null` when the current route already renders in Estate view.
 */
export function estateEntryHrefFor(screen: ScreenId, pathname: string, search: string): string | null {
  const params = new URLSearchParams(search);
  if (params.get("standard") === "1") {
    params.delete("standard");
    const nextSearch = params.toString();
    if (resolveEstateRoute(screen, pathname, nextSearch) !== null) {
      return nextSearch ? `?${nextSearch}` : pathname || "?screen=overview";
    }
  }
  if (resolveEstateRoute(screen, pathname, search) !== null) {
    return null;
  }
  if (screen === "inventory" && params.get("view") === "lifecycle") {
    return "?screen=inventory&lens=exposure";
  }
  if (screen === "operations" && params.get("tab") === "policy-install") {
    return "?screen=inventory&lens=install";
  }
  return "?screen=overview";
}

export function screenIdFromLocation(pathname: string, search: string): ScreenId {
  if (pathname.startsWith("/devices/")) return "inventory";
  if (pathname === "/compliance" || pathname === "/compliance/") return "compliance";
  if (pathname === "/backups" || pathname === "/backups/") return "backups";
  const rewritten = rewriteLegacyRoute(search);
  const params = new URLSearchParams(rewritten ?? search);
  const preview = params.get("preview");
  if (isScreenId(preview)) return preview;
  const screen = params.get("screen");
  if (screen === "configuration") return "inventory";
  return isScreenId(screen) ? screen : "overview";
}

export function briefHref(): string {
  return "?screen=overview";
}

export function wallHref(lens?: EstateLens): string {
  if (!lens || lens === "worst") return "?screen=inventory";
  return `?screen=inventory&lens=${encodeURIComponent(lens)}`;
}

export function dossierHref(deviceId: string, fromRewind?: RewindOrigin | null): string {
  const base = `?screen=inventory&device_id=${encodeURIComponent(deviceId)}`;
  if (!fromRewind?.at) return base;
  const extra = `&rewind_at=${encodeURIComponent(fromRewind.at)}`;
  return fromRewind.compare ? `${base}${extra}&rewind_compare=${encodeURIComponent(fromRewind.compare)}` : `${base}${extra}`;
}

/** True when the address renders outside the Estate view (a standard screen). */
export function leavesEstateView(href: string): boolean {
  const search = href.startsWith("?") ? href : `?${href}`;
  return resolveEstateRoute(screenIdFromLocation("/", search), "/", search) === null;
}

export function failoverHref(clusterRef?: string | null): string {
  if (clusterRef) {
    return `?screen=operations&cluster_ref=${encodeURIComponent(clusterRef)}`;
  }
  return "?screen=operations";
}

export function standardHaHref(clusterRef?: string | null): string {
  if (clusterRef) {
    return `?screen=operations&cluster_ref=${encodeURIComponent(clusterRef)}&standard=1`;
  }
  return "?screen=operations&standard=1";
}

export function changesHref(deviceId?: string | null): string {
  if (deviceId) {
    return `?screen=changes&device_id=${encodeURIComponent(deviceId)}`;
  }
  return "?screen=changes";
}

/** Standard Operations jobs list, failed jobs in the last 24 h (the attention rule's query, opened explicitly as standard). */
export function standardJobsHref(): string {
  return "?screen=operations&tab=jobs&state=FAILED&since_hours=24&standard=1";
}

export function standardChangesHref(view?: "feed" | "policy"): string {
  if (view) {
    return `?screen=changes&view=${encodeURIComponent(view)}&standard=1`;
  }
  return "?screen=changes&standard=1";
}

export function complianceHref(deviceId?: string | null): string {
  if (deviceId) {
    return `?screen=compliance&device=${encodeURIComponent(deviceId)}`;
  }
  return "?screen=compliance";
}

export function standardComplianceHref(options?: {
  readonly deviceId?: string | null;
  readonly controlId?: string | null;
}): string {
  const params = new URLSearchParams({ screen: "compliance" });
  if (options?.deviceId) params.set("device", options.deviceId);
  if (options?.controlId) params.set("control", options.controlId);
  params.set("standard", "1");
  return `?${params.toString()}`;
}

export function backupsHref(query?: string | null): string {
  if (query?.trim()) {
    return `?screen=backups&q=${encodeURIComponent(query.trim())}`;
  }
  return "?screen=backups";
}

export function standardBackupsHref(query?: string | null): string {
  const params = new URLSearchParams({ screen: "backups" });
  if (query?.trim()) params.set("q", query.trim());
  params.set("standard", "1");
  return `?${params.toString()}`;
}

export function askHref(mode?: AskMode): string {
  if (!mode || mode === "path") return "?screen=trace";
  return `?screen=trace&mode=${encodeURIComponent(mode)}`;
}

export function standardTraceHref(mode?: "path" | "lookup"): string {
  return `?screen=trace&mode=${mode ?? "path"}&standard=1`;
}

/** Rewind screen: `at` = moment A (ISO), `compare` = moment B (ISO) or absent for a single-moment read. */
export function rewindHref(at?: string | null, compare?: string | null): string {
  const params = new URLSearchParams({ screen: "inventory", view: "rewind" });
  if (at) params.set("at", at);
  if (compare) params.set("compare", compare);
  return `?${params.toString()}`;
}
