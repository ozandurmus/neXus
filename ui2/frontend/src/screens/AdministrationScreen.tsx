import { useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { AddDeviceDialogTrigger, ImportFromManagerTrigger } from "../shell/AddDeviceDialog";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { urlParam } from "../shell/urlParams";
import { CredentialsPanel } from "./CredentialsPanel";
import { LocalIdentitiesPanel } from "./LocalIdentitiesPanel";
import { SessionsPanel } from "./SessionsPanel";
import { DeviceManagementPane, InventoryExclusionsPanel } from "./DeviceRegistryPanel";
import { ProjectPlanPanel } from "./ProjectPlanPanel";

import { CustomRolesPanel } from "./CustomRolesPanel";
import { AuditLogsPanel } from "./AuditLogsPanel";
import { JobLogsPanel } from "./JobLogsPanel";
import { DirectorySettingsPanel } from "../components/settings/DirectorySettingsPanel";
import { NotificationSettingsPanel } from "../components/settings/NotificationSettingsPanel";
import { SystemStatusPanel } from "./SystemStatusPanel";

interface AdminTabDef {
  readonly slug: string;
  readonly label: string;
  readonly panel: ReactNode;
}

interface AdminGroupDef {
  readonly id: string;
  readonly label: string;
  readonly tabs: readonly AdminTabDef[];
}

/**
 * The review's four Administration groups (§3: "Twelve tabs in one row ... Group into four ... Use a left
 * sub-navigation inside Admin, as in the M3 drawer. Every tab stays reachable."). Header actions
 * ("Import from manager", "Add device") show only while the Registry group is active (§3: they "appear on all
 * twelve tabs" today; the review confines them to the group they belong to).
 */
const GROUPS: readonly AdminGroupDef[] = [
  {
    id: "registry",
    label: "Registry",
    tabs: [
      { slug: "device-management", label: "Device management", panel: <DeviceManagementPane /> },
      { slug: "inventory-exclusions", label: "Inventory exclusions", panel: <InventoryExclusionsPanel /> },
      { slug: "credentials", label: "Credentials", panel: <CredentialsPanel /> },
    ],
  },
  {
    id: "access",
    label: "Access",
    tabs: [
      { slug: "local-identities", label: "Local identities", panel: <LocalIdentitiesPanel /> },
      { slug: "sessions", label: "Sessions", panel: <SessionsPanel /> },
      { slug: "roles-permissions", label: "Roles & Permissions", panel: <CustomRolesPanel /> },
      { slug: "ldap-settings", label: "LDAP Settings", panel: <DirectorySettingsPanel /> },
    ],
  },
  {
    id: "platform",
    label: "Platform",
    tabs: [
      { slug: "system", label: "System", panel: <SystemStatusPanel /> },
      { slug: "notifications", label: "Notifications", panel: <NotificationSettingsPanel /> },
      { slug: "delivery-plan", label: "Delivery plan", panel: <ProjectPlanPanel /> },
    ],
  },
  {
    id: "records",
    label: "Records",
    tabs: [
      { slug: "audit-logs", label: "Audit Logs", panel: <AuditLogsPanel /> },
      { slug: "job-logs", label: "Job Logs", panel: <JobLogsPanel /> },
    ],
  },
];

function findBySlug(slug: string | null): { readonly group: AdminGroupDef; readonly tab: AdminTabDef } | null {
  if (!slug) return null;
  for (const group of GROUPS) {
    const tab = group.tabs.find((t) => t.slug === slug);
    if (tab) return { group, tab };
  }
  return null;
}

/** Pushes the current selection into `?screen=administration&tab=<slug>` so it is a real, shareable deep link. */
function writeTabToUrl(slug: string) {
  try {
    const url = new URL(window.location.href);
    url.searchParams.set("screen", "administration");
    url.searchParams.set("tab", slug);
    window.history.replaceState(null, "", url.toString());
  } catch {
    // A non-browser test environment, or a URL API that refuses this origin -- the in-memory selection still works.
  }
}

/** M3Administration with an empty registry. Enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  const initial = findBySlug(urlParam("tab")) ?? { group: GROUPS[0], tab: GROUPS[0].tabs[0] };
  const [activeGroupId, setActiveGroupId] = useState(initial.group.id);
  const [activeSlug, setActiveSlug] = useState(initial.tab.slug);

  const activeGroup = GROUPS.find((g) => g.id === activeGroupId) ?? GROUPS[0];
  const activeTab = activeGroup.tabs.find((t) => t.slug === activeSlug) ?? activeGroup.tabs[0];

  const select = (group: AdminGroupDef, tab: AdminTabDef) => {
    setActiveGroupId(group.id);
    setActiveSlug(tab.slug);
    writeTabToUrl(tab.slug);
  };

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Administration"
        subtitle="Device registry, collection scope and delivery plan. Enrollment is the one place a device enters the product."
        actions={
          activeGroup.id === "registry" ? (
            <>
              <ImportFromManagerTrigger />
              <AddDeviceDialogTrigger />
            </>
          ) : undefined
        }
      />
      <Box sx={{ flex: 1, minHeight: 0, display: "flex", gap: 3 }}>
        <Box component="nav" aria-label="Administration navigation" sx={{ width: 224, flex: "none", display: "flex", flexDirection: "column", gap: 2 }}>
          {GROUPS.map((group) => (
            <Box key={group.id}>
              <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.06em", textTransform: "uppercase", color: m3.onSurfaceVar, px: 1.25, pb: 0.5 }}>
                {group.label}
              </Typography>
              <Box role="tablist" aria-label={group.label} sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
                {group.tabs.map((tab) => {
                  const selected = activeSlug === tab.slug;
                  return (
                    <Box
                      key={tab.slug}
                      component="button"
                      type="button"
                      role="tab"
                      aria-selected={selected}
                      onClick={() => select(group, tab)}
                      sx={{
                        textAlign: "left",
                        border: "none",
                        cursor: "pointer",
                        font: "inherit",
                        borderRadius: "8px",
                        px: 1.25,
                        py: 1,
                        fontSize: 14,
                        fontWeight: 500,
                        bgcolor: selected ? m3.secondaryContainer : "transparent",
                        color: selected ? m3.onSecondaryContainer : m3.onSurface,
                        "&:hover": { bgcolor: selected ? m3.secondaryContainer : m3.scLow },
                      }}
                    >
                      {tab.label}
                    </Box>
                  );
                })}
              </Box>
            </Box>
          ))}
        </Box>
        <Box role="tabpanel" aria-label={activeTab.label} sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
          {activeTab.panel}
        </Box>
      </Box>
    </ScreenRoot>
  );
}
