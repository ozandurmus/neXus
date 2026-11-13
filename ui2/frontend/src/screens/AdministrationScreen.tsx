import { SshHostKeyPanel } from "./SshHostKeyPanel";
import { useSession } from "../auth/SessionContext";
import { useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";

import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { M3Tabs } from "../shell/M3Widgets";
import { AddDeviceDialogTrigger, ImportFromManagerTrigger } from "../shell/AddDeviceDialog";
import { Icon } from "../shell/Icon";
import { RestrictedPanel } from "../shell/States";
import { PanelErrorBoundary } from "../shell/PanelErrorBoundary";
import { PLANE_ADMIN, planeGranted } from "../shell/actionAccess";
import { LEGACY_ADMIN_TABS, type AdminGroupSlug } from "../shell/legacyRoutes";
import { useLocationSearch, writeUrlParams } from "../shell/urlParams";
import { CredentialsPanel } from "./CredentialsPanel";
import { LocalIdentitiesPanel } from "./LocalIdentitiesPanel";
import { SessionsPanel } from "./SessionsPanel";
import { DeviceManagementPane, InventoryExclusionsPanel } from "./DeviceRegistryPanel";
import { ProjectPlanPanel } from "./ProjectPlanPanel";
import { ScheduledJobsPanel } from "./ScheduledJobsPanel";
import { SystemStatusPanel } from "./SystemStatusPanel";

import { CustomRolesPanel } from "./CustomRolesPanel";
import { AuditLogsPanel } from "./AuditLogsPanel";
import { DirectorySettingsPanel } from "../components/settings/DirectorySettingsPanel";
import { NotificationSettingsPanel } from "../components/settings/NotificationSettingsPanel";
import { SystemLogSettingsPanel } from "../components/settings/SystemLogSettingsPanel";

interface AdminSectionDef {
  readonly slug: string;
  readonly label: string;
  readonly panel: ReactNode;
  /** Needs the server's administration-read grant (`canReadAdministration`); without it the section reads as locked. */
  readonly administratorOnly?: boolean;
}

interface AdminPageDef {
  readonly slug: AdminGroupSlug;
  /** The page's heading; the same words as its entry in the navigation (`shell/navModel.ts`). */
  readonly label: string;
  /** The name of the page's one tab strip. A page with a single section has none and shows that section directly. */
  readonly tabList?: string;
  readonly blurb: string;
  readonly sections: readonly AdminSectionDef[];
}

/**
 * Administration is seven pages, and the pages are the children of the Administration group in the left navigation
 * (navigation redesign 2026-11-12, PO approved, amended 2026-11-13; it amends UI review 2026-11-05, C4): Device registry,
 * Access and identity, Scheduled jobs, Notifications and logging, Platform status, Audit log and Delivery plan. The screen no longer carries a second side menu or a "Product" heading. Each page
 * is one screen: its heading is its menu label, and its sections are one tab strip, the one Operations uses. Only the open
 * tab is mounted, so a page that groups four heavy panels still reads one thing at a time. A page with a single section
 * (Scheduled jobs, Platform status, Audit log, Delivery plan) has no tab strip.
 *
 * The address is unchanged: `?screen=administration&tab=<page>&section=<slug>`; `section` now selects a tab, and a tab
 * switch writes it back. The screen reads the address on every change, so Back and Forward move between pages and tabs. Every older slug (`?tab=ssh-trust`) lands on its page with that tab open (`LEGACY_ADMIN_TABS`).
 *
 * Nothing is hidden from a reader who may not use it: a section the session may not read keeps its tab, with a lock, and
 * opens to the shared Restricted state (never a blank, never an error, never Retry), because the aiview persona is the
 * inspection persona and an inspector who cannot see that a page exists cannot verify the structure. The server still
 * decides every request; this only states the result. The header actions ("Import from manager", "Add device") show on
 * the Device management tab of Device registry only.
 */
const PAGES: readonly AdminPageDef[] = [
  {
    slug: "registry",
    label: "Device registry",
    tabList: "Device registry sections",
    blurb: "Enrollment is the one place a device enters the product: the registry, what is excluded from collection, the credentials collection uses, and per-device SSH trust.",
    sections: [
      { slug: "device-management", label: "Device management", panel: <DeviceManagementPane /> },
      { slug: "inventory-exclusions", label: "Inventory exclusions", panel: <InventoryExclusionsPanel /> },
      { slug: "credentials", administratorOnly: true, label: "Credentials", panel: <CredentialsPanel /> },
      // A per-device trust setting belongs with the registry, not with access control (UI review 2026-10-18, 2.7).
      { slug: "ssh-trust", label: "SSH trust", panel: <SshHostKeyPanel settings /> },
    ],
  },
  {
    slug: "access",
    label: "Access and identity",
    tabList: "Access and identity sections",
    blurb: "Who can sign in and what they may do: local identities, live sessions, roles and permissions, and the directory.",
    sections: [
      { slug: "local-identities", administratorOnly: true, label: "Local identities", panel: <LocalIdentitiesPanel /> },
      { slug: "sessions", administratorOnly: true, label: "Sessions", panel: <SessionsPanel /> },
      { slug: "roles-permissions", administratorOnly: true, label: "Roles and permissions", panel: <CustomRolesPanel /> },
      { slug: "ldap-settings", administratorOnly: true, label: "LDAP settings", panel: <DirectorySettingsPanel /> },
    ],
  },
  {
    slug: "scheduled-jobs",
    label: "Scheduled jobs",
    // Back from Operations (PO 2026-11-13 amendment of F2): which job families run is product configuration. Every
    // signed-in role reads the list; the server decides who may switch a family, and the panel offers no switch otherwise.
    blurb: "Which scheduled job families run, and who stopped one and why. Device jobs still run only inside the job window.",
    sections: [
      { slug: "scheduled-jobs", label: "Scheduled jobs", panel: <ScheduledJobsPanel /> },
    ],
  },
  {
    slug: "platform",
    // "Notifications and logging" since 2026-11-13 (was "Platform"); the address slug stays `platform`, so no address changes.
    label: "Notifications and logging",
    tabList: "Notifications and logging sections",
    // System and Scheduled jobs are pages of their own (PO 2026-11-13 amendment); old addresses are rewritten in `legacyRoutes`.
    blurb: "The product's own settings: where notifications go, and which system logs are collected from the devices.",
    sections: [
      { slug: "notifications", administratorOnly: true, label: "Notifications", panel: <NotificationSettingsPanel /> },
      { slug: "system-logs", administratorOnly: true, label: "Log collection", panel: <SystemLogSettingsPanel /> },
    ],
  },
  {
    slug: "platform-status",
    label: "Platform status",
    // neXus' own health (PO 2026-11-13 amendment of F1): readable by every signed-in role, as it was under Operations;
    // the indicator in the top bar summarises it on every screen and links here.
    blurb: "neXus itself: what the evidence takes on disk and the state of the product's own service pods.",
    sections: [
      { slug: "platform-status", label: "Platform status", panel: <SystemStatusPanel /> },
    ],
  },
  {
    slug: "audit",
    label: "Audit log",
    blurb: "The record of who did what in the product.",
    sections: [
      { slug: "audit-logs", administratorOnly: true, label: "Audit log", panel: <AuditLogsPanel /> },
    ],
  },
  {
    slug: "product",
    label: "Delivery plan",
    blurb: "Where the product build stands against its declared roadmap. For the Product Owner and engineers.",
    sections: [
      { slug: "delivery-plan", label: "Delivery plan", panel: <ProjectPlanPanel /> },
    ],
  },
];

function findPage(slug: string | null): AdminPageDef | null {
  return slug ? PAGES.find((p) => p.slug === slug) ?? null : null;
}

/** An address names a page (`tab=registry`) or, from before the regrouping, a former page (`tab=ssh-trust`); either lands on its page. */
export function resolveAdminRoute(tab: string | null, section: string | null): { readonly page: AdminPageDef; readonly section: string | null } {
  const direct = findPage(tab);
  if (direct) {
    return { page: direct, section: section && direct.sections.some((s) => s.slug === section) ? section : null };
  }
  const legacy = tab ? LEGACY_ADMIN_TABS[tab] : undefined;
  if (legacy) return { page: findPage(legacy.group)!, section: legacy.section };
  return { page: PAGES[0], section: null };
}

/**
 * Presentation only. The Delivery plan is for the people who run the build: it follows the Admin plane, a stand-in for
 * "PO and engineer". Any other section follows the server's administration-read grant where it is marked.
 */
function useSectionLocked(page: AdminPageDef): (section: AdminSectionDef) => boolean {
  const session = useSession();
  const planeLocked = page.slug === "product" && !planeGranted(session?.permissions, PLANE_ADMIN);
  return (section) => planeLocked || Boolean(section.administratorOnly && session && !session.canReadAdministration);
}

const LOCK_TITLE = "Restricted for this account";

/** One section's content: its panel, or the shared Restricted state where the session may not read it. */
function SectionBody({ section, locked }: { readonly section: AdminSectionDef; readonly locked: boolean }) {
  return (
    <Box component="section" id={`admin-section-${section.slug}`} aria-label={section.label} data-locked={locked ? "true" : "false"}
      sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, minWidth: 0 }}>
      {locked
        ? <RestrictedPanel area={section.label} nextStep="The page is shown so its place in the product can be verified; its content is not available to this account." />
        : <PanelErrorBoundary label={`Administration > ${section.label}`} resetKey={section.slug}>{section.panel}</PanelErrorBoundary>}
    </Box>
  );
}

/** One Administration page. `?screen=administration` alone opens Device registry; enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  // The address is the state: the page and the open tab are read from it on every change (a tab switch here, the
  // browser's Back and Forward, any in-app link), so the screen, its tab and the lit navigation entry stay in step.
  const search = useLocationSearch();
  const params = new URLSearchParams(search);
  const route = resolveAdminRoute(params.get("tab"), params.get("section"));
  const page = route.page;
  const addressTab = Math.max(0, page.sections.findIndex((s) => s.slug === route.section));
  // Where the address cannot be written (not a browser, or the history API refuses), the click still selects in memory.
  const [picked, setPicked] = useState<{ readonly search: string; readonly page: AdminGroupSlug; readonly tab: number } | null>(null);
  const tab = picked && picked.search === search && picked.page === page.slug ? picked.tab : addressTab;
  const locked = useSectionLocked(page);

  const select = (next: number) => {
    // The address names the open tab, so it is a shareable link and the navigation follows.
    writeUrlParams({ screen: "administration", tab: page.slug, section: page.sections[next].slug });
    setPicked({ search: typeof window === "undefined" ? search : window.location.search, page: page.slug, tab: next });
  };
  // Enrollment belongs to the registry list itself: its buttons show on Device management only, not beside Credentials.
  const enrollment = page.slug === "registry" && page.sections[tab]?.slug === "device-management";

  return (
    <ScreenRoot screenType="list">
      <ScreenHeader
        overline="Administration"
        title={page.label}
        subtitle={page.blurb}
        actions={
          enrollment ? (
            <>
              <ImportFromManagerTrigger />
              <AddDeviceDialogTrigger />
            </>
          ) : undefined
        }
      />
      {page.tabList ? (
        <M3Tabs
          key={page.slug}
          ariaLabel={page.tabList}
          value={tab}
          onChange={select}
          tabs={page.sections.map((section) => ({
            label: section.label,
            adornment: locked(section)
              ? <Box component="span" data-lock-badge title={LOCK_TITLE} sx={{ display: "inline-flex" }}><Icon name="lock" size={14} /></Box>
              : undefined,
            panel: <SectionBody section={section} locked={locked(section)} />,
          }))}
        />
      ) : (
        <SectionBody key={page.slug} section={page.sections[0]} locked={locked(page.sections[0])} />
      )}
    </ScreenRoot>
  );
}
