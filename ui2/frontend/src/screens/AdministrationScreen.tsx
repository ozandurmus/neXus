import { SshHostKeyPanel } from "./SshHostKeyPanel";
import { useSession } from "../auth/SessionContext";
import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { AddDeviceDialogTrigger, ImportFromManagerTrigger } from "../shell/AddDeviceDialog";
import { Icon } from "../shell/Icon";
import { RestrictedPanel } from "../shell/States";
import { PLANE_ADMIN, planeGranted } from "../shell/actionAccess";
import { LEGACY_ADMIN_TABS, type AdminGroupSlug } from "../shell/legacyRoutes";
import { m3 } from "../theme/m3Theme";
import { urlParam, writeUrlParams } from "../shell/urlParams";
import { CredentialsPanel } from "./CredentialsPanel";
import { LocalIdentitiesPanel } from "./LocalIdentitiesPanel";
import { SessionsPanel } from "./SessionsPanel";
import { DeviceManagementPane, InventoryExclusionsPanel } from "./DeviceRegistryPanel";
import { ProjectPlanPanel } from "./ProjectPlanPanel";

import { CustomRolesPanel } from "./CustomRolesPanel";
import { AuditLogsPanel } from "./AuditLogsPanel";
import { DirectorySettingsPanel } from "../components/settings/DirectorySettingsPanel";
import { NotificationSettingsPanel } from "../components/settings/NotificationSettingsPanel";
import { SystemLogSettingsPanel } from "../components/settings/SystemLogSettingsPanel";
import { SystemStatusPanel } from "./SystemStatusPanel";

interface AdminSectionDef {
  readonly slug: string;
  readonly label: string;
  readonly panel: ReactNode;
  /** Needs the server's administration-read grant (`canReadAdministration`); without it the section reads as locked. */
  readonly administratorOnly?: boolean;
}

interface AdminPageDef {
  readonly slug: AdminGroupSlug;
  readonly label: string;
  /** Which navigation list the page sits in: the four Administration groups, or the Product heading (C4). */
  readonly nav: "Administration pages" | "Product";
  readonly blurb: string;
  readonly sections: readonly AdminSectionDef[];
}

/**
 * Administration is four pages and a Product heading (UI review 2026-11-05, C4; Argon R-22, Fable on R-21/R-22): Device
 * Registry, Access & Identity, Platform, Audit, and, under its own **Product** heading, the Delivery plan. They replace
 * the fourteen sub-pages the screen carried (and the Job Logs page, which was the same table as Operations > Jobs and now
 * redirects there, B6). Each former page is a **section** of its group: a disclosure whose panel mounts only while it is
 * open, so a page that groups four heavy panels still reads four things at most when asked. Every old slug
 * (`?tab=ssh-trust`) lands on its group with that section open (`LEGACY_ADMIN_TABS`).
 *
 * Nothing is hidden from a reader who may not use it: a section the session may not read keeps its place and opens to
 * the shared Restricted state (never a blank, never an error, never Retry), because the aiview persona is the inspection
 * persona and an inspector who cannot see that a page exists cannot verify the structure. The server still decides every
 * request; this only states the result. The header actions ("Import from manager", "Add device") show on the registry
 * page only.
 */
const PAGES: readonly AdminPageDef[] = [
  {
    slug: "registry",
    label: "Device Registry",
    nav: "Administration pages",
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
    label: "Access & Identity",
    nav: "Administration pages",
    blurb: "Who can sign in and what they may do: local identities, live sessions, roles and permissions, and the directory.",
    sections: [
      { slug: "local-identities", administratorOnly: true, label: "Local identities", panel: <LocalIdentitiesPanel /> },
      { slug: "sessions", administratorOnly: true, label: "Sessions", panel: <SessionsPanel /> },
      { slug: "roles-permissions", administratorOnly: true, label: "Roles & Permissions", panel: <CustomRolesPanel /> },
      { slug: "ldap-settings", administratorOnly: true, label: "LDAP Settings", panel: <DirectorySettingsPanel /> },
    ],
  },
  {
    slug: "platform",
    label: "Platform",
    nav: "Administration pages",
    blurb: "The product's own health and settings: system status, notifications and log collection.",
    sections: [
      { slug: "system", label: "System", panel: <SystemStatusPanel /> },
      { slug: "notifications", administratorOnly: true, label: "Notifications", panel: <NotificationSettingsPanel /> },
      { slug: "system-logs", administratorOnly: true, label: "Log collection settings", panel: <SystemLogSettingsPanel /> },
    ],
  },
  {
    slug: "audit",
    label: "Audit",
    nav: "Administration pages",
    blurb: "The record of who did what in the product.",
    sections: [
      { slug: "audit-logs", administratorOnly: true, label: "Audit Logs", panel: <AuditLogsPanel /> },
    ],
  },
  {
    slug: "product",
    label: "Delivery plan",
    nav: "Product",
    blurb: "Where the product build stands against its declared roadmap. For the Product Owner and engineers.",
    sections: [
      { slug: "delivery-plan", label: "Delivery plan", panel: <ProjectPlanPanel /> },
    ],
  },
];

function findPage(slug: string | null): AdminPageDef | null {
  return slug ? PAGES.find((p) => p.slug === slug) ?? null : null;
}

/** An address names a page (`tab=registry`) or, from before the regrouping, a former page (`tab=ssh-trust`); either lands on its group. */
export function resolveAdminRoute(tab: string | null, section: string | null): { readonly page: AdminPageDef; readonly section: string | null } {
  const direct = findPage(tab);
  if (direct) {
    return { page: direct, section: section && direct.sections.some((s) => s.slug === section) ? section : null };
  }
  const legacy = tab ? LEGACY_ADMIN_TABS[tab] : undefined;
  if (legacy) return { page: findPage(legacy.group)!, section: legacy.section };
  return { page: PAGES[0], section: null };
}

/** The Product heading's page is for the people who run the build: it follows the Admin plane, a presentation-only stand-in for "PO and engineer". */
function usePageLocked(page: AdminPageDef): boolean {
  const session = useSession();
  if (page.slug === "product") return !planeGranted(session?.permissions, PLANE_ADMIN);
  return page.sections.every((s) => s.administratorOnly) && Boolean(session) && !session!.canReadAdministration;
}

function SectionDisclosure({ section, page, open, onToggle }: {
  readonly section: AdminSectionDef; readonly page: AdminPageDef; readonly open: boolean; readonly onToggle: () => void;
}) {
  const session = useSession();
  const planeLocked = page.slug === "product" && !planeGranted(session?.permissions, PLANE_ADMIN);
  const locked = planeLocked || Boolean(section.administratorOnly && session && !session.canReadAdministration);
  const id = `admin-section-${section.slug}`;
  return (
    <Box component="section" id={id} aria-label={section.label} data-locked={locked ? "true" : "false"}
      sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: "12px", bgcolor: m3.scLowest, overflow: "hidden" }}>
      <Box component="button" type="button" aria-expanded={open} aria-controls={`${id}-body`} onClick={onToggle}
        sx={{ all: "unset", boxSizing: "border-box", width: "100%", display: "flex", alignItems: "center", gap: 1.25, px: 2, py: 1.5, cursor: "pointer",
              fontSize: 15, fontWeight: 600, color: m3.onSurface, "&:hover": { bgcolor: m3.scLow }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: -2 } }}>
        <Icon name={open ? "collapse" : "expand"} size={18} />
        <Box component="span" sx={{ flex: 1 }}>{section.label}</Box>
        {locked && <Box component="span" data-lock-badge sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, fontSize: 12, fontWeight: 500, color: m3.onSurfaceVar }}>
          <Icon name="lock" size={14} />Restricted</Box>}
      </Box>
      {open && (
        <Box id={`${id}-body`} sx={{ px: 2, pb: 2, display: "flex", flexDirection: "column", gap: 2 }}>
          {locked
            ? <RestrictedPanel area={section.label} nextStep="The page is shown so its place in the product can be verified; its content is not available to this account." />
            : section.panel}
        </Box>
      )}
    </Box>
  );
}

function PageBody({ page, requestedSection }: { readonly page: AdminPageDef; readonly requestedSection: string | null }) {
  // The requested section (a deep link) opens alone; otherwise the page opens its first section, so an unrestricted page
  // is never a wall of closed headers and a deep link never mounts a panel nobody asked for.
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set([requestedSection ?? page.sections[0].slug]));
  useEffect(() => {
    if (!requestedSection) return;
    const el = typeof document === "undefined" ? null : document.getElementById(`admin-section-${requestedSection}`);
    (el as { scrollIntoView?: () => void } | null)?.scrollIntoView?.();
  }, [requestedSection]);
  const toggle = (slug: string) => {
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(slug)) next.delete(slug); else next.add(slug);
      return next;
    });
    writeUrlParams({ section: open.has(slug) ? null : slug });
  };
  return (
    <>
      <Typography variant="body1" sx={{ color: m3.onSurfaceVar, maxWidth: 820 }}>{page.blurb}</Typography>
      {page.sections.map((section) => (
        <SectionDisclosure key={section.slug} section={section} page={page} open={open.has(section.slug)} onToggle={() => toggle(section.slug)} />
      ))}
    </>
  );
}

function NavTab({ page, selected, onSelect }: { readonly page: AdminPageDef; readonly selected: boolean; readonly onSelect: () => void }) {
  const locked = usePageLocked(page);
  return (
    <Box component="button" type="button" role="tab" aria-selected={selected} onClick={onSelect}
      sx={{ textAlign: "left", border: "none", cursor: "pointer", font: "inherit", borderRadius: "8px", px: 1.25, py: 1, fontSize: 14, fontWeight: 500,
            display: "flex", alignItems: "center", gap: 0.75,
            bgcolor: selected ? m3.secondaryContainer : "transparent", color: selected ? m3.onSecondaryContainer : m3.onSurface,
            "&:hover": { bgcolor: selected ? m3.secondaryContainer : m3.scLow } }}>
      {page.label}
      {locked && <Box component="span" title="Restricted for this account" sx={{ display: "inline-flex", color: m3.onSurfaceVar }}><Icon name="lock" size={14} /></Box>}
    </Box>
  );
}

const NAV_LISTS = ["Administration pages", "Product"] as const;

/** M3Administration with an empty registry. Enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  const initial = resolveAdminRoute(urlParam("tab"), urlParam("section"));
  const [activeSlug, setActiveSlug] = useState<AdminGroupSlug>(initial.page.slug);
  const [requestedSection, setRequestedSection] = useState<string | null>(initial.section);
  const activePage = findPage(activeSlug) ?? PAGES[0];

  const select = (page: AdminPageDef) => {
    setActiveSlug(page.slug);
    setRequestedSection(null);
    writeUrlParams({ screen: "administration", tab: page.slug, section: null });
  };

  return (
    <ScreenRoot screenType="list">
      <ScreenHeader
        title="Administration"
        subtitle="Device registry, access and identity, the platform's own settings and the audit record. Enrollment is the one place a device enters the product."
        actions={
          activePage.slug === "registry" ? (
            <>
              <ImportFromManagerTrigger />
              <AddDeviceDialogTrigger />
            </>
          ) : undefined
        }
      />
      <Box sx={{ flex: 1, minHeight: 0, display: "flex", gap: 3 }}>
        <Box component="nav" aria-label="Administration navigation" sx={{ width: 224, flex: "none", display: "flex", flexDirection: "column", gap: 2 }}>
          {NAV_LISTS.map((nav) => (
            <Box key={nav}>
              {nav === "Product" && (
                <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.06em", textTransform: "uppercase", color: m3.onSurfaceVar, px: 1.25, pb: 0.5 }}>
                  Product
                </Typography>
              )}
              <Box role="tablist" aria-label={nav} sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
                {PAGES.filter((p) => p.nav === nav).map((page) => (
                  <NavTab key={page.slug} page={page} selected={activeSlug === page.slug} onSelect={() => select(page)} />
                ))}
              </Box>
            </Box>
          ))}
        </Box>
        <Box role="tabpanel" aria-label={activePage.label} sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
          <PageBody key={activePage.slug} page={activePage} requestedSection={requestedSection} />
        </Box>
      </Box>
    </ScreenRoot>
  );
}

