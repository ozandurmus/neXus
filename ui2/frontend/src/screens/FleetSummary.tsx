import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { getOverview, type DeviceSummary, type OverviewView } from "../auth/adminApi";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { vendorLabel } from "../shell/presenters";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { m3 } from "../theme/m3Theme";
import { EstateDetails } from "./OverviewScreen";

export interface QuickFilterChip { readonly label: string; readonly href: string; readonly title?: string }
export interface QuickFilterGroup {
  readonly label: string;
  readonly chips: readonly QuickFilterChip[];
  /** Written instead of chips when the figures behind the group could not be read: UNKNOWN, never 0. */
  readonly unknown?: string;
}

const q = (params: Record<string, string>) => `?${new URLSearchParams({ screen: "inventory", ...params }).toString()}`;

/**
 * The quick filters above the Fleet summary (Phase 2 follow-up 1.5): one chip row per dimension -- vendor, form,
 * inventory evidence age, configuration changed since the previous read -- each chip the filtered device list.
 * Vendor and form are counted from the device list the screen already holds; evidence age and changes come from the
 * Overview read the summary makes, so a failed read writes UNKNOWN for those two rather than a zero.
 */
export function fleetQuickFilters(fleet: readonly DeviceSummary[] | undefined, overview: OverviewView | null | undefined): QuickFilterGroup[] {
  const groups: QuickFilterGroup[] = [];
  const devices = fleet ?? [];
  const n = (count: number) => count.toLocaleString("en-US");
  if (devices.length > 0) {
    const byVendor = new Map<string, number>();
    for (const d of devices) byVendor.set(d.vendor_hint, (byVendor.get(d.vendor_hint) ?? 0) + 1);
    groups.push({ label: "Vendor", chips: [...byVendor.entries()]
      .sort(([a], [b]) => vendorLabel(a).localeCompare(vendorLabel(b)))
      .map(([vendor, count]) => ({ label: `${vendorLabel(vendor)} · ${n(count)}`, href: q({ vendor }) })) });
    const clusters = new Set(devices.map((d) => d.cluster_member_ref).filter((r): r is string => Boolean(r))).size;
    const managers = devices.filter((d) => d.role === "management_server").length;
    const appliances = devices.filter((d) => d.role === "appliance").length;
    const standalone = devices.filter((d) => !d.cluster_member_ref && d.role !== "management_server" && d.role !== "appliance").length;
    groups.push({ label: "Form", chips: [
      ...(clusters > 0 ? [{ label: `${n(clusters)} ${clusters === 1 ? "cluster" : "clusters"}`, href: q({ form: "cluster" }), title: "Cluster members, grouped under their cluster" }] : []),
      ...(standalone > 0 ? [{ label: `${n(standalone)} standalone ${standalone === 1 ? "gateway" : "gateways"}`, href: q({ form: "standalone" }) }] : []),
      ...(managers > 0 ? [{ label: `${n(managers)} management ${managers === 1 ? "server" : "servers"}`, href: q({ form: "manager" }) }] : []),
      ...(appliances > 0 ? [{ label: `${n(appliances)} ${appliances === 1 ? "appliance" : "appliances"}`, href: q({ form: "appliance" }) }] : []),
    ] });
  }
  const age = overview?.inventory_age;
  groups.push(age
    ? { label: "Evidence age", chips: [
        { label: `under 24 h · ${n(age.lt24h ?? 0)}`, href: q({ inventory_age: "lt24h" }), title: "Newest inventory read under 24 hours ago" },
        { label: `24–72 h · ${n(age.h24_72 ?? 0)}`, href: q({ inventory_age: "24h_72h" }) },
        { label: `over 72 h · ${n(age.gt72h ?? 0)}`, href: q({ inventory_age: "gt72h" }) },
        { label: `never read · ${n(age.never ?? 0)}`, href: q({ inventory_age: "never" }) },
      ] }
    : { label: "Evidence age", chips: [], unknown: "UNKNOWN · the estate summary did not return the evidence ages" });
  const changed = overview?.attention?.config_changed;
  groups.push(changed && changed.state === "OK"
    ? { label: "Changed since last read", chips: [{ label: `Configuration changed · ${n(changed.count)}${changed.of ? ` of ${n(changed.of)}` : ""}`, href: q({ change_state: "changed" }),
        title: "Devices whose newest configuration read differs from the previous one" }] }
    : { label: "Changed since last read", chips: [], unknown: "UNKNOWN · the configuration comparison was not read" });
  return groups;
}

function QuickFilters({ groups }: { readonly groups: readonly QuickFilterGroup[] }) {
  return (
    <Box data-testid="fleet-quick-filters" sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
      {groups.map((g) => (
        <Box key={g.label} role="group" aria-label={g.label} sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 0.75, rowGap: 0.5 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar, minWidth: 150, letterSpacing: "0.04em", textTransform: "uppercase", fontWeight: 600 }}>{g.label}</Typography>
          {g.unknown
            ? <Typography variant="caption" sx={{ color: m3.neutralInk, fontWeight: 600 }}>{g.unknown}</Typography>
            : g.chips.map((c) => (
              <Chip key={c.label} component="a" href={c.href} clickable size="small" label={c.label} title={c.title}
                sx={{ height: 24, fontSize: 12, borderRadius: "8px", bgcolor: m3.scLow, color: m3.onSurface, border: `1px solid ${m3.outlineVar}`,
                  "&:hover": { bgcolor: m3.secondaryContainer } }} />
            ))}
        </Box>
      ))}
    </Box>
  );
}

/**
 * The Devices screen's landing (UI review 2026-10-18, P1-1): where the empty "Select a device or cluster" pane was, the
 * estate inventory summary -- policy installed, hardware models and software versions per vendor, inventory evidence age.
 * It is the Overview's "Estate details" itself ({@link EstateDetails}, same stored-evidence read), so the two screens can not
 * disagree and every slice keeps its link to the filtered device list. {@code countLabel} is the screen's one count sentence.
 */
export function FleetSummary({ countLabel, fleet }: { readonly countLabel: string;
  /** The Devices screen's own device list: the other vendors' version slices are read from it, no second request (1.5). */
  readonly fleet?: readonly DeviceSummary[] }) {
  const overview = useFetchOnMount<OverviewView>(getOverview, (err) => {
    const e = err as { status?: number; message?: string };
    return e?.message ?? `status ${e?.status ?? "unknown"}`;
  });
  const settled = overview.data !== null || overview.error !== null;
  return (
    <Stack spacing={2} data-testid="fleet-summary">
      <Box>
        <Typography variant="h4">Fleet summary</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 760, mt: 0.5 }}>
          {countLabel}. Choose one on the left to see its interfaces, routing, cluster members, configuration, backup,
          logs and identity. Interface evidence is read over SSH or HTTPS; values are observed, never written.
        </Typography>
      </Box>
      {/* Quick filters (Phase 2 follow-up): each chip opens the device list narrowed to that slice; drawn once the overview settled. */}
      {fleet && settled && <QuickFilters groups={fleetQuickFilters(fleet, overview.data)} />}
      {overview.data && overview.data.platform && overview.data.nexus
        ? <EstateDetails data={overview.data} onDevicesScreen fleet={fleet} />
        : overview.error
          ? isRestricted(overview.rawError)
            ? <RestrictedPanel area="Estate summary" />
            // A finding about this one panel, not a failed screen: announced as a status, never as an alert.
            : <StatePanel variant="empty" title="Estate summary is not shown" body="The stored-evidence summary did not return data. Devices below are not affected; reload to try again." code={overview.error} />
          : overview.data
            ? <StatePanel variant="empty" title="Estate summary is not shown" body="The stored-evidence summary came back without its platform and neXus figures. Devices below are not affected." />
            : <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Reading the estate summary…</Typography>}
    </Stack>
  );
}
