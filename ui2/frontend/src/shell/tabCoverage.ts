import type { ScreenId } from "./types";

// Read-only navigation contract, kept beside NavigationRail. The unit test compares
// this list with the actual tab definitions; the full E2E suite consumes it.
export interface TabCoverageRow {
  readonly screen: ScreenId;
  readonly tabList: string;
  readonly labels: readonly string[];
  /** Where the tab list is, when it is not on the screen's plain address (`screen=<id>`), and the heading shown there. */
  readonly query?: string;
  readonly heading?: string;
}
export const TAB_COVERAGE: readonly TabCoverageRow[] = [
  // Reports R1 (REPORTS_R1_CONTRACT.md "UI"): three tabs.
  { screen: "reports", tabList: "Reports sections", labels: ["Catalogue", "Run & preview", "History"] },
  // Lifecycle and security is a view of the Devices screen with its own address and heading (navigation entry of the same
  // name under Compliance and security, PO 2026-11-13); the full e2e visits it there.
  { screen: "inventory", tabList: "Lifecycle view", labels: ["Fleet", "Catalog", "Vulnerabilities"],
    query: "screen=inventory&view=lifecycle", heading: "Lifecycle and security" },
  // Navigation redesign 2026-11-12: "HA and readiness" (was "HA & readiness"), "Packet analysis", "Data sanitizer".
  // Diagnostics moved to Tools and the Change feed to the Changes screen (UI review 2026-11-05, C3/C4).
  { screen: "operations", tabList: "Operations sections", labels: ["HA and readiness", "Jobs", "Device health"] },
  // Administration's seven pages are children of the navigation group (navigation redesign 2026-11-12, PO amendment
  // 2026-11-13); a page's sections are its one tab strip, reached at the page's own address. Scheduled jobs, Platform
  // status, Audit log and Delivery plan have a single section and no strip.
  { screen: "administration", tabList: "Device registry sections", labels: ["Device management", "Inventory exclusions", "Credentials", "SSH trust"],
    query: "screen=administration&tab=registry", heading: "Device registry" },
  { screen: "administration", tabList: "Access and identity sections", labels: ["Local identities", "Sessions", "Roles and permissions", "LDAP settings"],
    query: "screen=administration&tab=access", heading: "Access and identity" },
  { screen: "administration", tabList: "Notifications and logging sections", labels: ["Notifications", "Log collection"],
    query: "screen=administration&tab=platform", heading: "Notifications and logging" },
  // Trace is two modes of one screen; Changes is two views of one question (C3).
  { screen: "trace", tabList: "Trace modes", labels: ["Topology path", "Policy rule lookup"] },
  { screen: "changes", tabList: "Changes views", labels: ["Timeline", "Policy rule revisions"] },
  // Cluster members is a cluster-page tab only (roadmap 2.2); a member page links to its cluster from the header. The
  // device's Identity tab is gone (PO 2026-11-06): the Platform identity grid is a collapsible panel in the device header.
  { screen: "inventory", tabList: "Device detail", labels: ["Interfaces", "Routing", "Grid members", "Managed estate", "Configuration", "Backup", "Logs"] },
  { screen: "inventory", tabList: "Cluster detail", labels: ["Interfaces", "Routing", "Members & Identity", "Configuration", "Logs", "Failover"] },
  // The Logs tab's own two views (PO 2026-10-20): the line viewer with the room, and the collection health panels beside it.
  { screen: "inventory", tabList: "Logs view", labels: ["Lines", "Collection health"] },
  { screen: "inventory", tabList: "Configuration sections", labels: ["Sections", "Configuration", "Details"] },
  // A Packet Analysis case's own views (shown once a case is opened); Device events is mockup 2026-10-17 screen 4.
  { screen: "packet_analysis", tabList: "Investigation views", labels: ["Overview & Verdict", "Topology", "Flows & Ladder", "Timeseries & Quality", "DNS", "UDP / Media", "Device events"] },
  { screen: "inventory", tabList: "Configuration detail", labels: ["Sections", "Changes", "Overrides", "XML Configuration", "Sanitized text", "Alignment"] },
];
export const BACKUP_SECTIONS = ["Backup-capable", "Targets", "Targets without archive", "Large backups"] as const;
export const SCREEN_HEADINGS: Record<ScreenId, string> = {
  policy: "Policy", overview: "Overview", reports: "Reports", inventory: "Devices", configuration: "Devices", compliance: "Compliance",
  backups: "Backups", operations: "Operations", administration: "Device registry", packet_analysis: "Packet analysis",
  sanitizer: "Data sanitizer", trace: "Trace", changes: "Changes", diagnostics: "Diagnostics", rule_usage: "Rule object usage",
};
/** Administration's seven pages: the address of each and the heading it shows (the same words as its navigation entry). */
export const ADMIN_PAGES: readonly { readonly query: string; readonly heading: string }[] = [
  { query: "screen=administration&tab=registry", heading: "Device registry" },
  { query: "screen=administration&tab=access", heading: "Access and identity" },
  { query: "screen=administration&tab=scheduled-jobs", heading: "Scheduled jobs" },
  { query: "screen=administration&tab=platform", heading: "Notifications and logging" },
  { query: "screen=administration&tab=platform-status", heading: "Platform status" },
  { query: "screen=administration&tab=audit", heading: "Audit log" },
  { query: "screen=administration&tab=product", heading: "Delivery plan" },
];
