/**
 * Old addresses keep working (UI review 2026-11-05, C2/C3/C4/B6). The information architecture moved screens and tabs
 * (Path and Policy > Lookup became Trace; the Change feed and Policy > Changes met on one Changes screen; Diagnostics and
 * Offline analysis moved under Tools; Administration's fourteen pages became four groups; Job Logs became Operations >
 * Jobs), and a bookmark, an audit link or a deep link in an e-mail written before that must land on the new home with its
 * own parameters kept. This is the one place that knows the old shapes: a pure function from a query string to the query
 * string of the new home, or `null` when the address is already current.
 */

/** Administration's four groups plus the Product heading (C4); the slug is the `tab` value. */
export const ADMIN_GROUP_SLUGS = ["registry", "access", "platform", "audit", "product"] as const;
export type AdminGroupSlug = (typeof ADMIN_GROUP_SLUGS)[number];

/** Every Administration tab slug that existed before the regrouping, with the group page and the section that now holds it. */
export const LEGACY_ADMIN_TABS: Readonly<Record<string, { readonly group: AdminGroupSlug; readonly section: string }>> = {
  "device-management": { group: "registry", section: "device-management" },
  "inventory-exclusions": { group: "registry", section: "inventory-exclusions" },
  credentials: { group: "registry", section: "credentials" },
  "ssh-trust": { group: "registry", section: "ssh-trust" },
  "local-identities": { group: "access", section: "local-identities" },
  sessions: { group: "access", section: "sessions" },
  "roles-permissions": { group: "access", section: "roles-permissions" },
  "ldap-settings": { group: "access", section: "ldap-settings" },
  system: { group: "platform", section: "system" },
  notifications: { group: "platform", section: "notifications" },
  "system-logs": { group: "platform", section: "system-logs" },
  "delivery-plan": { group: "product", section: "delivery-plan" },
  "audit-logs": { group: "audit", section: "audit-logs" },
};

/** The Policy tab slugs of the four-tab layout (C2). */
export const POLICY_TAB_SLUGS = ["rules", "objects", "hygiene", "changes"] as const;

/** The new query string (no leading `?`) for an old address, or `null` when `search` needs no rewrite. */
export function rewriteLegacyRoute(search: string): string | null {
  const params = new URLSearchParams(search);
  const before = params.toString();
  // A preview address names its screen in `preview`; the same renames apply.
  const key = params.get("preview") ? "preview" : "screen";
  const screen = params.get(key);
  const tab = params.get("tab");

  if (screen === "path_analysis") {
    params.set(key, "trace");
    if (!params.get("mode")) params.set("mode", "path");
  } else if (screen === "policy" && key === "screen") {
    if (tab === "lookup") {
      params.set("screen", "trace");
      params.set("mode", "lookup");
      params.delete("tab");
    } else if (tab === "offline" || tab === "offline-analysis") {
      params.set("screen", "offline_analysis");
      params.delete("tab");
    } else if (tab === "installation") {
      // Installation is a column and a section of Rules & Containers now.
      params.set("tab", "rules");
    }
  } else if (screen === "operations" && key === "screen") {
    if (tab === "changes") {
      params.set("screen", "changes");
      params.set("view", "feed");
      params.delete("tab");
    } else if (tab === "diagnostics") {
      params.set("screen", "diagnostics");
      params.delete("tab");
    }
  } else if (screen === "administration" && key === "screen") {
    if (tab === "job-logs" || (!tab && params.get("job"))) {
      // Job Logs was the same table as Operations > Jobs (B6); a `?job=<id>` link opens that job there.
      params.set("screen", "operations");
      params.set("tab", "jobs");
    } else if (tab && LEGACY_ADMIN_TABS[tab]) {
      const home = LEGACY_ADMIN_TABS[tab];
      params.set("tab", home.group);
      params.set("section", home.section);
    }
  }
  const after = params.toString();
  return after === before ? null : after;
}

/** The Policy screen's "Search in this policy" deep link: Trace in lookup mode, pre-scoped to one stored policy (C2/C3). */
export const lookupInPolicyHref = (policyId: string) => `?screen=trace&mode=lookup&policy_id=${encodeURIComponent(policyId)}`;

/** The unified Changes screen, on its timeline or on the policy rule revisions. */
export type ChangesView = "feed" | "policy";
export const changesHref = (view: ChangesView) => `?screen=changes&view=${view}`;
