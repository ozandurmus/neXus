import type { ScreenId } from "./types";

// Read-only navigation contract, kept beside NavigationRail. The unit test compares
// this list with the actual tab definitions; the full E2E suite consumes it.
export const TAB_COVERAGE: readonly { screen: ScreenId; tabList: string; labels: readonly string[] }[] = [
  // Reports R1 (REPORTS_R1_CONTRACT.md "UI"): three tabs.
  { screen: "reports", tabList: "Reports sections", labels: ["Catalogue", "Run & preview", "History"] },
  { screen: "inventory", tabList: "Lifecycle view", labels: ["Fleet", "Catalog", "Vulnerabilities"] },
  // Diagnostics moved to Tools and the Change feed to the Changes screen (UI review 2026-11-05, C3/C4).
  { screen: "operations", tabList: "Operations sections", labels: ["HA & readiness", "Jobs", "System health"] },
  // Administration is four pages and a Product heading (C4); the former sub-pages are sections inside them.
  { screen: "administration", tabList: "Administration pages", labels: ["Device Registry", "Access & Identity", "Platform", "Audit"] },
  { screen: "administration", tabList: "Product", labels: ["Delivery plan"] },
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
  backups: "Backups", operations: "Operations", administration: "Administration", packet_analysis: "Packet Analysis",
  sanitizer: "Data Sanitizer", trace: "Trace", changes: "Changes", diagnostics: "Diagnostics", offline_analysis: "Offline analysis",
};
