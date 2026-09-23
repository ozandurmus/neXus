import { useEffect, useMemo, useRef, useState } from "react";
import Button from "@mui/material/Button";
import Box from "@mui/material/Box";
import InputBase from "@mui/material/InputBase";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { StatePanel, isRestricted, VendorBadge } from "./States";
import { globalSearch, listDevices, type DeviceSummary, type GlobalSearchResponse } from "../auth/adminApi";

/** The device and screen shortcuts stay immediate; stored settings and evidence arrive after the typing pause. */
interface Hit { readonly kind: "device" | "cluster" | "vs" | "screen"; readonly label: string; readonly sub: string; readonly href: string; readonly vendor?: string | null }
interface DisplayHit { readonly group: "Devices" | "Screens" | "Settings" | "Evidence"; readonly label: string; readonly sub: string; readonly href: string; readonly vendor?: string | null }

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

function emphasis(value: string, term: string) {
  const at = value.toLocaleLowerCase().indexOf(term.trim().toLocaleLowerCase());
  if (at < 0 || !term.trim()) return value;
  return <>{value.slice(0, at)}<Box component="mark" sx={{ bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer }}>{value.slice(at, at + term.trim().length)}</Box>{value.slice(at + term.trim().length)}</>;
}

function grouped(local: readonly Hit[], remote: GlobalSearchResponse | null): DisplayHit[] {
  const out: DisplayHit[] = local.map(h => ({ ...h, group: h.kind === "screen" ? "Screens" : "Devices" }));
  for (const d of remote?.devices ?? []) {
    const hit: DisplayHit = { group: "Devices", label: d.name ?? d.device_id.slice(0, 8),
      sub: [d.model, d.software_version, d.cluster, d.management_address, d.serial].filter(Boolean).join(" · "),
      href: d.href, vendor: d.vendor };
    const existing = out.findIndex(h => h.href === d.href);
    if (existing < 0) out.push(hit); else out[existing] = hit;
  }
  for (const s of remote?.settings ?? []) out.push({ group: "Settings", label: s.setting,
    sub: [s.device_count && s.device_count > 1 ? `${s.device_count} devices (e.g. ${s.device})` : s.device, s.section, s.value_excerpt].filter(Boolean).join(" · "),
    href: s.href });
  for (const e of remote?.evidence ?? []) out.push({ group: "Evidence", label: e.label, sub: e.detail, href: e.href });
  const order = ["Screens", "Devices", "Settings", "Evidence"];
  return out.sort((a, b) => order.indexOf(a.group) - order.indexOf(b.group));
}

export function GlobalSearch() {
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(false);
  const [devices, setDevices] = useState<readonly DeviceSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [remote, setRemote] = useState<GlobalSearchResponse | null>(null);
  const [remoteError, setRemoteError] = useState<unknown>(null);
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open || devices !== null || failed) return;
    listDevices().then((r) => setDevices(r.devices ?? [])).catch(() => setFailed(true));
  }, [open, devices, failed]);
  useEffect(() => {
    const q = term.trim();
    setRemote(null);
    setRemoteError(null);
    if (!open || q.length < 2 || q.length > 100) return;
    let current = true;
    const timer = window.setTimeout(() => {
      globalSearch(q).then(r => { if (current) setRemote(r); })
        .catch(error => { if (current) setRemoteError(error); });
    }, 250);
    return () => { current = false; window.clearTimeout(timer); };
  }, [open, term, retry]);
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);
  const results = useMemo(() => hits(devices ?? [], term), [devices, term]);
  const entries = useMemo(() => grouped(results, remote), [results, remote]);
  useEffect(() => { setSelected(0); }, [term, remote]);
  return (
    <Box ref={box} sx={{ position: "relative", width: { xs: 180, md: 320 } }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, height: 36, px: 1.5, borderRadius: "8px",
                 border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, color: m3.onSurfaceVar }}>
        <Icon name="search" size={18} />
        <InputBase value={term} placeholder="Search devices, settings, evidence…" inputProps={{ "aria-label": "Search devices, settings and evidence" }}
          onFocus={() => setOpen(true)} onChange={(e) => { setTerm(e.target.value); setOpen(true); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown" || e.key === "ArrowUp") {
              e.preventDefault();
              setSelected(i => (i + (e.key === "ArrowDown" ? 1 : -1) + entries.length) % (entries.length || 1));
            }
            if (e.key === "Enter" && entries[selected]) window.location.href = entries[selected].href;
            if (e.key === "Escape") setOpen(false);
          }}
          sx={{ flex: 1, fontSize: 13, color: m3.onSurface }} />
      </Box>
      {open && term.trim() && (
        <Paper role="listbox" sx={{ position: "absolute", top: 40, left: 0, width: { xs: "calc(100vw - 32px)", md: 560 }, zIndex: 1300, border: `1px solid ${m3.outlineVar}`,
                                    boxShadow: m3.e2, borderRadius: "10px", py: 0.5, maxHeight: 520, overflow: "auto", bgcolor: m3.scLowest }}>
          {failed && <StatePanel variant="error" title="The device list could not be read" action={<Button onClick={() => { setFailed(false); setDevices(null); }}>Retry</Button>} />}
          {!failed && devices === null && <Typography variant="body2" sx={{ px: 1.5, py: 1 }}>Reading the device list…</Typography>}
          {devices !== null && entries.length === 0 && !remoteError && <StatePanel variant="empty" title="No matches" />}
          {(["Screens", "Devices", "Settings", "Evidence"] as const).map(group => {
            const items = entries.map((h, index) => ({ h, index })).filter(({ h }) => h.group === group);
            return items.length ? <Box key={group}>
              <Typography sx={{ px: 1.5, pt: 1, fontSize: 11, fontWeight: 700, color: m3.onSurfaceVar }}>{group}</Typography>
              {items.map(({ h, index }) => <Link key={`${h.href}-${index}`} href={h.href} underline="none" role="option" aria-selected={selected === index}
                sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 1.5, py: 0.75, color: m3.onSurface,
                  bgcolor: selected === index ? m3.sc : undefined, "&:hover": { bgcolor: m3.sc } }}>
              {h.group === "Screens" ? <Box sx={{ width: 40, color: m3.onSurfaceVar, display: "flex" }}><Icon name="external" size={16} /></Box>
                : h.vendor ? <VendorBadge vendor={h.vendor} size={24} /> : null}
              <Box sx={{ minWidth: 0 }}>
                <Typography noWrap sx={{ fontSize: 13, fontWeight: 600, fontFamily: h.group === "Screens" ? undefined : MONO }}>{emphasis(h.label, term)}</Typography>
                <Typography noWrap variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>{emphasis(h.sub, term)}</Typography>
              </Box>
            </Link>)}</Box> : null;
          })}
          {remoteError !== null && (isRestricted(remoteError)
            ? <StatePanel variant="restricted" title="Search results are restricted" />
            : <StatePanel variant="error" title="Search failed" action={<Button onClick={() => setRetry(n => n + 1)}>Retry</Button>} />)}
        </Paper>
      )}
    </Box>
  );
}
