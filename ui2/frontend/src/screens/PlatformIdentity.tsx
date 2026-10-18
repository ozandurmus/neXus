import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { RoleChip, Ts, Unknown } from "../shell/States";
import { vendorLabel } from "../shell/presenters";
import { MONO, m3 } from "../theme/m3Theme";
import type { DeviceSummary } from "../auth/adminApi";
import { ContentVersions, PLATFORM_IDENTITY_GRID_ID, ReferenceCell, vsListOf } from "./DeviceShared";
import { LifecycleCell } from "./LifecycleScreen";
import { useDetailTabs } from "./InventoryPanels";

/**
 * The Platform identity grid (UI product review 2026-10-18, roadmap 2.6): the audit grid of one device, now the first
 * card of the Identity tab, with the reference and the lifecycle as cells. The Configuration tab shows it as one
 * collapsed line ({@link PlatformIdentitySummary}) that leads here, so every field is reachable exactly once in full.
 */
const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" } as const;

export interface IdentityTileOptions {
  /** False for a vendor with no configuration read: the "Last configuration read" tile is then absent, not "never". */
  readonly configurationRead?: boolean;
}

export interface IdentityTile {
  readonly label: string;
  readonly value: ReactNode | null;
  /** Shown when the value is null: why it is not there. */
  readonly absent?: string;
  /**
   * Three words for three states (UI review 2026-10-18, P1-1B): UNKNOWN keeps its rule (a read was attempted and the value
   * is unknown, the default), "Not read yet" is a read that has not happened, "Not applicable" a value this device cannot have.
   */
  readonly absentWord?: "Not read yet" | "Not applicable";
  readonly mono?: boolean;
}

/**
 * Platform identity: what a security administrator checks first and what a
 * compliance review asks for -- vendor, model, software version, hotfix level,
 * serial number, HA role, virtual systems. A value the product has not read
 * says so (UNKNOWN, with the reason); nothing is inferred.
 */
export function identityTiles(device: DeviceSummary, collectedAt: string | null, options: IdentityTileOptions = {}): IdentityTile[] {
  const configurationRead = options.configurationRead ?? true;
  const vs = vsListOf(device);
  const isPaloAlto = device.vendor_hint === "palo_alto";
  const common: IdentityTile[] = [
    { label: "Vendor", value: vendorLabel(device.vendor_hint) },
    { label: "Model", value: device.model || device.platform_family || null, absent: "not read yet", absentWord: "Not read yet" },
  ];
  const tail: IdentityTile[] = [
    { label: "Management address", value: device.management_ip ?? null, absent: "not recorded", mono: true },
    { label: "Enrollment", value: device.enrollment_state || null, absent: "not recorded" },
    ...(configurationRead ? [{ label: "Last configuration read", value: collectedAt ? <Ts at={collectedAt} /> : null, absent: "never", absentWord: "Not read yet" } satisfies IdentityTile] : []),
  ];
  // The Lifecycle and Reference cells of the grid (UI review 2026-10-18, F-9): they used to be a loose line and the Identity tab.
  // The Identity tab is the one home of the audit grid (roadmap 2.6): the two columns only the old identity table had
  // (last inventory read, the read that produced the platform facts) are cells here now.
  const cells: IdentityTile[] = [
    { label: "Last inventory read", value: device.inventory_collected_at ? <Ts at={device.inventory_collected_at} /> : null, absent: "never", absentWord: "Not read yet" },
    { label: "Evidence source", value: device.platform_facts_source ?? null, absent: "no platform-facts read recorded", absentWord: "Not read yet", mono: true },
    { label: "Lifecycle", value: <LifecycleCell deviceId={device.device_id} /> },
    { label: "Reference", value: <ReferenceCell value={device.device_id} />, mono: true },
  ];
  const haRole: IdentityTile = { label: "HA role", value: device.ha_role ? <RoleChip role={device.ha_role} dense /> : device.cluster_member_ref ? null : "Standalone",
    absent: device.role === "management_server" ? "not read -- MDS high availability needs its own read" : device.cluster_member_ref ? "not reported" : undefined,
    absentWord: device.role === "management_server" ? "Not read yet" : undefined };
  if (device.vendor_hint === "fortinet") return [
    ...common,
    ...(device.serial_number ? [{ label: "Serial number", value: device.serial_number, mono: true } satisfies IdentityTile] : []),
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    ...(device.uptime_text ? [{ label: "Uptime", value: device.uptime_text } satisfies IdentityTile] : []),
    haRole,
    ...(vs.length ? [{ label: "VDOMs", value: `${vs.length} · ${vs.join(", ")}` } satisfies IdentityTile] : []),
    ...tail,
    ...cells,
  ];
  if (device.vendor_hint === "cisco_asa") return [
    ...common,
    ...(device.serial_number ? [{ label: "Serial number", value: device.serial_number, mono: true } satisfies IdentityTile] : []),
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    haRole,
    ...tail,
    ...cells,
  ];
  if (!isPaloAlto && device.vendor_hint !== "check_point") return [
    ...common,
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    ...tail,
    ...cells,
  ];
  const needsGate = "not collected -- needs its own gated read";
  return [
    ...common,
    { label: "Serial number", value: device.serial_number ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate, absentWord: "Not read yet", mono: true },
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    isPaloAlto
      ? { label: "Content versions", value: device.content_versions ? <ContentVersions versions={device.content_versions} /> : null, absent: "not read yet -- run Inventory collect", absentWord: "Not read yet" }
      : { label: "Hotfix / Jumbo take", value: device.hotfix_level ?? null, absent: needsGate, absentWord: "Not read yet" },
    { label: "Uptime", value: device.uptime_text ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate, absentWord: "Not read yet" },
    haRole,
    { label: isPaloAlto ? "Virtual systems (VSYS)" : "Virtual systems (VSX)", value: vs.length > 0 ? `${vs.length} · ${vs.join(", ")}` : "none" },
    ...tail.slice(0, 2),
    {
      label: "Policy installed",
      value: device.policy_installed_at
        ? <><Ts at={device.policy_installed_at} />{device.policy_name ? ` · ${device.policy_name}` : ""}</>
        : device.policy_installed_at_text ? `${device.policy_installed_at_text} (as reported)` : null,
      absent: device.role === "management_server"
        ? "not applicable -- a management server installs policy on gateways, it has none of its own"
        : "not read yet -- read every evening at 23:00",
      absentWord: device.role === "management_server" ? "Not applicable" : "Not read yet",
    },
    { label: "Platform facts read", value: device.platform_facts_observed_at ? <Ts at={device.platform_facts_observed_at} /> : null, absent: "never", absentWord: "Not read yet" },
    ...tail.slice(2),
    ...cells,
  ];
}

export function IdentityCard({ title, tiles }: { readonly title: string; readonly tiles: readonly IdentityTile[] }) {
  return (
    <Card id={PLATFORM_IDENTITY_GRID_ID} sx={{ p: 2, ...CARD, scrollMarginTop: 16 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Platform identity</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>{title}</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">What an audit asks for first</Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))" }}>
        {tiles.map((tile) => (
          <Box key={tile.label} sx={{ p: 1.5, borderRadius: "8px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", display: "block", mb: 0.5 }}>{tile.label.toUpperCase()}</Typography>
            {tile.value !== null && tile.value !== undefined ? (
              <Typography component="div" variant="body2" sx={{ fontWeight: 600, fontFamily: tile.mono ? MONO : undefined, wordBreak: "break-word" }}>{tile.value}</Typography>
            ) : (
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                {tile.absentWord
                  ? <Box component="span" sx={{ color: m3.neutralInk, fontWeight: 600, letterSpacing: "0.02em" }}>{tile.absentWord}</Box>
                  : <Unknown />}
                <Typography variant="caption" color="text.secondary">{tile.absent}</Typography>
              </Box>
            )}
          </Box>
        ))}
      </Box>
    </Card>
  );
}

/** The one-line fact strip the grid collapses to on the Configuration tab: the first facts, and the way to the full grid. */
export function identitySummaryLine(device: DeviceSummary): string {
  const parts = [vendorLabel(device.vendor_hint), device.model || device.platform_family || null, device.software_version,
    device.serial_number ? "serial read" : null, device.ha_role ? device.ha_role.trim().toUpperCase() : null].filter((p): p is string => Boolean(p));
  return parts.join(" · ");
}

/**
 * The Configuration tab's collapsed Platform identity line (roadmap 2.6): vendor · model · version · serial read, and a
 * link that opens the Identity tab on the grid. Outside the device tab strip (no tab context) the full grid is shown.
 */
export function PlatformIdentitySummary({ device }: { readonly device: DeviceSummary }) {
  const tabs = useDetailTabs();
  return (
    <Card data-testid="platform-identity-summary" sx={{ px: 2, py: 1, ...CARD, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
      <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em", lineHeight: 1.6 }}>Platform identity</Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>{identitySummaryLine(device)}</Typography>
      {tabs?.has("Identity") ? (
        <Link component="button" type="button" underline="hover" onClick={() => tabs.open("Identity", PLATFORM_IDENTITY_GRID_ID)}
          sx={{ ml: "auto", color: m3.primary, font: "inherit", fontSize: 13 }}>
          Full grid in Identity: serial, versions, uptime, policy installed, read times, lifecycle, reference →
        </Link>
      ) : (
        <Typography variant="caption" sx={{ ml: "auto", color: m3.onSurfaceVar }}>Full grid in the Identity tab</Typography>
      )}
    </Card>
  );
}
