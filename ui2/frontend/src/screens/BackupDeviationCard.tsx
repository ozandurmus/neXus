import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import type { BackupDeviations, BackupMajorDeviation } from "../auth/adminApi";
import { MetricCard } from "../shell/ScreenLayout";
import { Stamp } from "../shell/PresentedText";
import { StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

/**
 * Backups "Active major deviations" (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md): the number of devices whose
 * configuration changed in a major section (interfaces, routing, rules, NAT, admin access, HA) in the last 7 days,
 * read from the configuration runs. UNKNOWN only when no device could be compared; otherwise "N of M devices
 * compared", so a partial comparison is never read as "no deviation".
 */

/** The device's configuration Details > Changes tab (Inventory reads `config_tab`). */
export const configChangesHref = (deviceId: string) =>
  `?screen=inventory&device_id=${encodeURIComponent(deviceId)}&tab=configuration&config_tab=changes`;

const CATEGORY_WORDS: Record<string, string> = {
  interfaces: "Interfaces",
  routing: "Routing",
  access_rules: "Access rules",
  nat: "NAT",
  admin_access: "Admin access",
  high_availability: "Cluster / HA",
};

export function categoryWord(category: string): string {
  return CATEGORY_WORDS[category] ?? category;
}

/** Devices with at least one active major deviation (rows are per device and category). */
export function majorDeviceIds(deviations: BackupDeviations | null): ReadonlySet<string> {
  return new Set((deviations?.active_major_deviations ?? []).map((row) => row.device_id));
}

/** One line per device: its categories, newest change first. */
export function majorDeviationsByDevice(rows: readonly BackupMajorDeviation[]): { deviceId: string; name: string; vendor: string; changedAt: string; categories: string[]; sections: string[] }[] {
  const byDevice = new Map<string, { deviceId: string; name: string; vendor: string; changedAt: string; categories: string[]; sections: string[] }>();
  for (const row of rows) {
    const entry = byDevice.get(row.device_id) ?? { deviceId: row.device_id, name: row.hostname ?? row.device_id, vendor: row.vendor, changedAt: row.changed_at, categories: [], sections: [] };
    if (row.changed_at > entry.changedAt) entry.changedAt = row.changed_at;
    entry.categories.push(categoryWord(row.category));
    for (const s of row.sections) if (!entry.sections.includes(s)) entry.sections.push(s);
    byDevice.set(row.device_id, entry);
  }
  return Array.from(byDevice.values()).sort((a, b) => (a.changedAt < b.changedAt ? 1 : a.changedAt > b.changedAt ? -1 : a.name.localeCompare(b.name)));
}

function comparedSentence(deviations: BackupDeviations): string {
  const days = deviations.window_days ?? 7;
  const known = deviations.devices_known;
  const n = deviations.total_deviations_checked;
  return known !== undefined && known > 0
    ? `${n} of ${known} devices compared · last ${days} days`
    : `${n} device${n === 1 ? "" : "s"} compared · last ${days} days`;
}

export function BackupDeviationCard({ deviations }: { readonly deviations: BackupDeviations | null }) {
  const compared = deviations !== null && deviations.total_deviations_checked > 0;
  const count = majorDeviceIds(deviations).size;
  return (
    <MetricCard
      title="Active major deviations"
      value={compared ? count : null}
      unit={compared ? (count === 1 ? "device" : "devices") : undefined}
      state={compared ? "ok" : "unknown"}
      reason={deviations
        ? "No device has two configuration reads to compare in the last 7 days, so no deviation can be claimed or ruled out"
        : "The deviation read failed"}
      note={deviations ? comparedSentence(deviations) : "Deviation state unavailable"}
      href={compared && count > 0 ? "#major-deviations" : undefined}
      linkLabel="Which devices →"
    />
  );
}

/** The devices behind the card, each linking to its configuration Changes tab; nothing when there are none. */
export function MajorDeviationList({ deviations }: { readonly deviations: BackupDeviations | null }) {
  const rows = majorDeviationsByDevice(deviations?.active_major_deviations ?? []);
  if (rows.length === 0) return null;
  return (
    <Card id="major-deviations" aria-label="Active major deviations" sx={{ p: 2, borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", maxWidth: 1180 }}>
      <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar, mb: 1 }}>
        Major configuration changes · last {deviations?.window_days ?? 7} days
      </Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: 0.75 }}>
        {rows.map((row) => (
          <Box component="li" key={row.deviceId} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Link href={configChangesHref(row.deviceId)} underline="hover" sx={{ fontWeight: 600, color: m3.primary }}
              aria-label={`${row.name}: open the configuration changes`}>
              {row.name}
            </Link>
            {row.categories.map((c) => <StatusChip key={c} tone="bad" label={c} dense />)}
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {row.sections.join(", ")} · changed <Stamp at={row.changedAt} />
            </Typography>
          </Box>
        ))}
      </Box>
    </Card>
  );
}
