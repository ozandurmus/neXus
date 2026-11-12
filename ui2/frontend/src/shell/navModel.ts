import type { IconName } from "./Icon";
import type { ScreenId } from "./types";
import { ADMIN_GROUP_SLUGS, LEGACY_ADMIN_TABS, rewriteLegacyRoute, type AdminGroupSlug } from "./legacyRoutes";

/**
 * The one navigation model (NAVIGATION_REDESIGN_2026_11_12.md, PO approved 2026-11-12). The expanded sidebar, the folded
 * icons and the narrow drawer are three renderings of this list; nothing else lists a destination.
 *
 * Nine top-level entries in the order of the product principle (SEE, VERIFY, TRACE, RECOVER, OPERATE; the words are no
 * longer printed). A domain with one screen is a link, a domain with several is a group that opens in place (D-NAV3).
 * One level of nesting. A label is the heading of the screen, or the tab on that screen, that the entry opens.
 *
 * Everything here is pure: what is listed for a plane list, and which single entry is lit for an address.
 */

/** How an Administration page reads for a session that may not use it: listed, with a lock (UI review 2026-11-05). */
export type NavLock = "adminRead" | "adminPlane";

export interface NavLeaf {
  readonly id: string;
  readonly label: string;
  readonly screen: ScreenId;
  readonly href: string;
  /** Listed only when the session's plane list holds this plane, or the leaf's screen is the open one. */
  readonly requiredPlane?: string;
  /** Listed for every session; drawn locked when the session lacks the grant. Never hides the entry. */
  readonly lock?: NavLock;
}

export interface NavLink extends NavLeaf {
  readonly kind: "link";
  readonly icon: IconName;
}

export interface NavGroup {
  readonly kind: "group";
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly children: readonly NavLeaf[];
  /** Closed on a viewer's first visit (Administration); every other group starts open. */
  readonly startsClosed?: boolean;
}

export type NavEntry = NavLink | NavGroup;

const adminPage = (slug: AdminGroupSlug, label: string, lock?: NavLock): NavLeaf =>
  ({ id: `admin-${slug}`, label, screen: "administration", href: `?screen=administration&tab=${slug}`, lock });

export const NAV_ENTRIES: readonly NavEntry[] = [
  { kind: "link", id: "overview", label: "Overview", icon: "grid", screen: "overview", href: "?screen=overview" },
  // Reports R1 (REPORTS_R1_CONTRACT.md "UI"): directly under Overview, because it is executive-facing.
  { kind: "link", id: "reports", label: "Reports", icon: "report", screen: "reports", href: "?screen=reports" },
  // Devices is a first-class top-level entry (D-NAV4).
  { kind: "link", id: "devices", label: "Devices", icon: "devices", screen: "inventory", href: "?screen=inventory", requiredPlane: "Devices" },
  { kind: "group", id: "compliance-security", label: "Compliance and security", icon: "compliance", children: [
    { id: "compliance", label: "Compliance", screen: "compliance", href: "?screen=compliance", requiredPlane: "Compliance" },
    // A view of the Devices screen (licences, end of support, vulnerabilities, Live Patch); it keeps the Devices plane.
    { id: "lifecycle", label: "Lifecycle and security", screen: "inventory", href: "?screen=inventory&view=lifecycle", requiredPlane: "Devices" },
  ] },
  { kind: "group", id: "policy-changes", label: "Policy and changes", icon: "policy", children: [
    { id: "policy", label: "Policy", screen: "policy", href: "?screen=policy", requiredPlane: "Policy" },
    // The path query follows the inventory read (open to every signed-in role, masked for aiview): no plane gate.
    { id: "trace", label: "Trace", screen: "trace", href: "?screen=trace" },
    { id: "changes", label: "Changes", screen: "changes", href: "?screen=changes" },
  ] },
  { kind: "link", id: "backups", label: "Backups", icon: "backup", screen: "backups", href: "?screen=backups" },
  // Jobs stays a child of Operations (PO-NAV-8); the three children are the three tabs of the one screen.
  { kind: "group", id: "operations", label: "Operations", icon: "operations", children: [
    { id: "operations-ha", label: "HA and readiness", screen: "operations", href: "?screen=operations" },
    { id: "operations-jobs", label: "Jobs", screen: "operations", href: "?screen=operations&tab=jobs" },
    { id: "operations-health", label: "System health", screen: "operations", href: "?screen=operations&tab=health" },
  ] },
  // The four ad hoc, file-in or single-device engineering tools (UI review 2026-11-05, C4), listed in place.
  { kind: "group", id: "tools", label: "Tools", icon: "tools", children: [
    { id: "packet-analysis", label: "Packet analysis", screen: "packet_analysis", href: "?screen=packet_analysis", requiredPlane: "PacketAnalysis" },
    { id: "sanitizer", label: "Data sanitizer", screen: "sanitizer", href: "?screen=sanitizer", requiredPlane: "Sanitizer" },
    { id: "diagnostics", label: "Diagnostics", screen: "diagnostics", href: "?screen=diagnostics" },
    { id: "offline-analysis", label: "Offline analysis", screen: "offline_analysis", href: "?screen=offline_analysis", requiredPlane: "Policy" },
  ] },
  // Listed for every session in every layout; a page the session may not use is locked, never hidden (amendment 6).
  { kind: "group", id: "administration", label: "Administration", icon: "admin", startsClosed: true, children: [
    adminPage("registry", "Device registry"),
    adminPage("access", "Access and identity", "adminRead"),
    adminPage("platform", "Platform"),
    adminPage("audit", "Audit log", "adminRead"),
    adminPage("product", "Delivery plan", "adminPlane"),
  ] },
];

export const leavesOf = (entry: NavEntry): readonly NavLeaf[] => (entry.kind === "group" ? entry.children : [entry]);

/** Every destination, top to bottom. */
export const NAV_LEAVES: readonly NavLeaf[] = NAV_ENTRIES.flatMap(leavesOf);

/** The group that holds a leaf, or `null` for a top-level link. */
export function groupOfLeaf(leafId: string): NavGroup | null {
  for (const entry of NAV_ENTRIES) {
    if (entry.kind === "group" && entry.children.some((c) => c.id === leafId)) return entry;
  }
  return null;
}

/**
 * The single lit entry for an open screen and its address. `screen` is what the shell resolved (it knows the path
 * forms `/devices/<id>`, `/compliance`, `/backups` and the preview addresses); `search` supplies the sub-view. An old
 * address is read through the same rewrite the shell applies, so a bookmark lights the entry of its new home.
 */
export function activeLeafId(screen: ScreenId, search: string): string {
  const params = new URLSearchParams(rewriteLegacyRoute(search) ?? search);
  switch (screen) {
    case "inventory":
    case "configuration":
      return params.get("view") === "lifecycle" ? "lifecycle" : "devices";
    case "operations": {
      const tab = params.get("tab");
      if (tab === "jobs" || tab === "queue" || tab === "history") return "operations-jobs";
      if (tab === "health") return "operations-health";
      if (tab === "ha") return "operations-ha";
      // A job link that names only `?job=<id>` lands on Jobs (roadmap 3.6).
      return params.get("job") ? "operations-jobs" : "operations-ha";
    }
    case "administration": {
      const tab = params.get("tab");
      if (tab && (ADMIN_GROUP_SLUGS as readonly string[]).includes(tab)) return `admin-${tab}`;
      const legacy = tab ? LEGACY_ADMIN_TABS[tab] : undefined;
      return `admin-${legacy ? legacy.group : "registry"}`;
    }
    case "packet_analysis": return "packet-analysis";
    case "offline_analysis": return "offline-analysis";
    default: return screen;
  }
}

/**
 * Whether a leaf is listed. Unchanged rule: a session with no plane list is unrestricted; otherwise a leaf with a plane
 * needs that plane, or its screen is the open one (the reader never loses "you are here"). Planes only, no role token.
 */
export function leafShown(leaf: NavLeaf, permissions: readonly string[] | undefined, activeScreen: ScreenId): boolean {
  const restricted = Boolean(permissions && permissions.length > 0);
  return !restricted || !leaf.requiredPlane || permissions!.includes(leaf.requiredPlane) || leaf.screen === activeScreen;
}

/** The list a session sees: leaves filtered by plane; a group is listed while at least one child is. */
export function visibleEntries(permissions: readonly string[] | undefined, activeScreen: ScreenId): readonly NavEntry[] {
  const out: NavEntry[] = [];
  for (const entry of NAV_ENTRIES) {
    if (entry.kind === "link") {
      if (leafShown(entry, permissions, activeScreen)) out.push(entry);
    } else {
      const children = entry.children.filter((c) => leafShown(c, permissions, activeScreen));
      if (children.length > 0) out.push({ ...entry, children });
    }
  }
  return out;
}

export interface NavSessionGrants {
  readonly permissions?: readonly string[];
  readonly canReadAdministration?: boolean;
}

/**
 * Presentation only, the same two checks the Administration screen makes for its pages: a page whose every section
 * needs the server's administration-read grant is locked without it; the Delivery plan follows the Admin plane. No
 * session at all (previews, tests) locks nothing.
 */
export function leafLocked(leaf: NavLeaf, session: NavSessionGrants | null | undefined): boolean {
  if (!leaf.lock || !session) return false;
  if (leaf.lock === "adminPlane") {
    const permissions = session.permissions;
    return Boolean(permissions && permissions.length > 0 && !permissions.includes("Admin"));
  }
  return !session.canReadAdministration;
}

/** Which groups are open: the viewer's stored choice, else the group's default; the active leaf's group is always open. */
export function groupOpen(group: NavGroup, stored: Readonly<Record<string, boolean>>, activeId: string): boolean {
  if (group.children.some((c) => c.id === activeId)) return true;
  return stored[group.id] ?? !group.startsClosed;
}

/** The stored open/closed choices (`id:1,id:0`), tolerant of anything else in the store. */
export function parseGroupChoices(raw: string | null): Readonly<Record<string, boolean>> {
  const out: Record<string, boolean> = {};
  for (const part of (raw ?? "").split(",")) {
    const [id, flag] = part.split(":");
    if (id && (flag === "1" || flag === "0") && NAV_ENTRIES.some((e) => e.kind === "group" && e.id === id)) out[id] = flag === "1";
  }
  return out;
}

export function formatGroupChoices(choices: Readonly<Record<string, boolean>>): string {
  return Object.entries(choices).map(([id, open]) => `${id}:${open ? "1" : "0"}`).join(",");
}
