import { useEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import InputBase from "@mui/material/InputBase";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { VendorBadge } from "./States";
import { listDevices, type DeviceSummary } from "../auth/adminApi";

/**
 * Global search (review §7 P2, the M3 study's search field): devices, clusters and virtual systems by name,
 * serial, model or address, plus the screens themselves. It reads the same device list the Devices screen reads
 * -- masked server-side for aiview, so it searches and shows pseudonyms -- and only navigates; it never fetches
 * anything a screen would not. Configuration settings and evidence text are not searched here (backlog
 * `global_search_settings_and_evidence`: that needs a server-side index).
 */
interface Hit { readonly kind: "device" | "cluster" | "vs" | "screen"; readonly label: string; readonly sub: string; readonly href: string; readonly vendor?: string | null }

const SCREENS: Hit[] = [
  ["Overview", "overview"], ["Devices", "inventory"], ["Configuration", "configuration"], ["Compliance", "compliance"],
  ["Backups", "backups"], ["Operations", "operations"], ["Administration", "administration"],
].map(([label, id]) => ({ kind: "screen" as const, label, sub: "Screen", href: `?screen=${id}` }));

function hits(devices: readonly DeviceSummary[], term: string): Hit[] {
  const t = term.trim().toLowerCase();
  if (!t) return [];
  const out: Hit[] = SCREENS.filter((s) => s.label.toLowerCase().includes(t));
  const clusters = new Map<string, DeviceSummary>();
  for (const d of devices) {
    const fields = [d.hostname, d.serial_number, d.model, d.software_version, d.management_ip, d.cluster_member_ref, d.platform_family];
    if (fields.some((f) => f && String(f).toLowerCase().includes(t))) {
      out.push({ kind: "device", label: d.hostname ?? d.device_id.slice(0, 8), vendor: d.vendor_hint,
        sub: [d.model ?? d.platform_family, d.software_version, d.cluster_member_ref].filter(Boolean).join(" · ") || "Device",
        href: `?screen=inventory&device_id=${encodeURIComponent(d.device_id)}` });
    }
    if (d.cluster_member_ref && d.cluster_member_ref.toLowerCase().includes(t)) clusters.set(d.cluster_member_ref, d);
    for (const vs of (d.virtual_systems ?? "").split(/[,;\s]+/).filter(Boolean)) {
      if (vs.toLowerCase().includes(t)) {
        out.push({ kind: "vs", label: vs, sub: `Virtual system on ${d.hostname ?? "a device"}`, vendor: d.vendor_hint,
          href: `?screen=inventory&device_id=${encodeURIComponent(d.device_id)}` });
      }
    }
  }
  for (const [ref, d] of clusters) {
    out.push({ kind: "cluster", label: ref, sub: "Cluster", vendor: d.vendor_hint, href: `?screen=inventory&cluster_ref=${encodeURIComponent(ref)}` });
  }
  const seen = new Set<string>();
  return out.filter((h) => (seen.has(h.kind + h.label) ? false : (seen.add(h.kind + h.label), true))).slice(0, 10);
}

export function GlobalSearch() {
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(false);
  const [devices, setDevices] = useState<readonly DeviceSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open || devices !== null || failed) return;
    listDevices().then((r) => setDevices(r.devices ?? [])).catch(() => setFailed(true));
  }, [open, devices, failed]);
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);
  const results = useMemo(() => hits(devices ?? [], term), [devices, term]);
  return (
    <Box ref={box} sx={{ position: "relative", width: { xs: 180, md: 320 } }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, height: 36, px: 1.5, borderRadius: "8px",
                 border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, color: m3.onSurfaceVar }}>
        <Icon name="search" size={18} />
        <InputBase value={term} placeholder="Search devices, clusters, serials…" inputProps={{ "aria-label": "Search devices, clusters and virtual systems" }}
          onFocus={() => setOpen(true)} onChange={(e) => { setTerm(e.target.value); setOpen(true); }}
          onKeyDown={(e) => { if (e.key === "Enter" && results[0]) window.location.href = results[0].href; if (e.key === "Escape") setOpen(false); }}
          sx={{ flex: 1, fontSize: 13, color: m3.onSurface }} />
      </Box>
      {open && term.trim() && (
        <Paper role="listbox" sx={{ position: "absolute", top: 40, left: 0, right: 0, zIndex: 1300, border: `1px solid ${m3.outlineVar}`,
                                    boxShadow: m3.e2, borderRadius: "10px", py: 0.5, maxHeight: 420, overflow: "auto", bgcolor: m3.scLowest }}>
          {failed && <Typography variant="body2" sx={{ px: 1.5, py: 1 }}>The device list could not be read.</Typography>}
          {!failed && devices === null && <Typography variant="body2" sx={{ px: 1.5, py: 1 }}>Reading the device list…</Typography>}
          {devices !== null && results.length === 0 && <Typography variant="body2" sx={{ px: 1.5, py: 1 }}>No device, cluster or screen matches.</Typography>}
          {results.map((h) => (
            <Link key={h.kind + h.label} href={h.href} underline="none" role="option"
              sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 1.5, py: 0.75, color: m3.onSurface, "&:hover": { bgcolor: m3.sc } }}>
              {h.kind === "screen" ? <Box sx={{ width: 40, color: m3.onSurfaceVar, display: "flex" }}><Icon name="external" size={16} /></Box>
                : <VendorBadge vendor={h.vendor} size={24} />}
              <Box sx={{ minWidth: 0 }}>
                <Typography noWrap sx={{ fontSize: 13, fontWeight: 600, fontFamily: h.kind === "screen" ? undefined : MONO }}>{h.label}</Typography>
                <Typography noWrap variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>{h.sub}</Typography>
              </Box>
            </Link>
          ))}
        </Paper>
      )}
    </Box>
  );
}
