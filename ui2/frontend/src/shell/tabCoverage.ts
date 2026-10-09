import type { ScreenId } from "./types";

// Read-only navigation contract, kept beside NavigationRail. The unit test compares
// this list with the actual tab definitions; the full E2E suite consumes it.
export const TAB_COVERAGE: readonly { screen: ScreenId; tabList: string; labels: readonly string[] }[] = [
  { screen: "inventory", tabList: "Lifecycle view", labels: ["Fleet", "Catalog"] },
  { screen: "operations", tabList: "Operations sections", labels: ["HA & readiness", "Jobs", "Queue", "History", "Diagnostics"] },
  { screen: "administration", tabList: "Registry", labels: ["Device management", "Inventory exclusions", "Credentials"] },
  { screen: "administration", tabList: "Access", labels: ["Local identities", "Sessions", "Roles & Permissions", "LDAP Settings"] },
  { screen: "administration", tabList: "Platform", labels: ["System", "Notifications", "Delivery plan"] },
  { screen: "administration", tabList: "Records", labels: ["Audit Logs", "Job Logs"] },
  { screen: "inventory", tabList: "Device detail", labels: ["Interfaces", "Routing", "Grid members", "Managed devices", "Cluster members", "Configuration", "Backup", "Identity"] },
  { screen: "inventory", tabList: "Cluster detail", labels: ["Interfaces", "Routing", "Cluster members", "Configuration", "Identity", "Failover"] },
  { screen: "inventory", tabList: "Configuration sections", labels: ["Sections", "Configuration", "Details"] },
  { screen: "inventory", tabList: "Configuration detail", labels: ["Sections", "Changes", "Overrides", "XML Configuration", "Sanitized text", "Alignment"] },
];
export const BACKUP_SECTIONS = ["All enrolled", "Targets", "Targets without archive"] as const;
export const SCREEN_HEADINGS: Record<ScreenId, string> = {
  policy: "Policy", overview: "Overview", inventory: "Devices", configuration: "Devices", compliance: "Compliance",
  backups: "Backups", operations: "Operations", administration: "Administration",
};
