import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import Button, { type ButtonProps } from "@mui/material/Button";
import { getJobWindow } from "../auth/adminApi";
import { ROLE_LOCK_LABEL, useRoleAllows } from "./actionAccess";
import { DISABLED_STYLE } from "./disabledStyle";

export interface JobWindowStatus {
  open: boolean;
  server_time: string;
  window_end: string;
  next_window_start: string;
}
export interface WindowView { open: boolean; label: string | null }
// Standalone previews have no live provider. The authenticated app always mounts the provider.
export const JobWindowContext = createContext<WindowView>({ open: true, label: null });
const unavailable: WindowView = { open: false, label: "Window status unavailable" };
const timeLabel = (iso: string) => new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Istanbul", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(iso.replace(/\[.*\]$/, "")));

export function windowView(status: JobWindowStatus, elapsedMs: number): WindowView {
  const open = status.open && Date.parse(status.server_time) + elapsedMs < Date.parse(status.window_end);
  return { open, label: open ? null : `Next window: ${timeLabel(status.next_window_start)}` };
}

export function JobWindowProvider({ children }: { children: ReactNode }) {
  const [view, setView] = useState<WindowView>(unavailable);
  useEffect(() => {
    let stopped = false;
    let sample: { status: JobWindowStatus; received: number } | null = null;
    const refresh = async () => {
      try {
        const sent = performance.now();
        const status = await getJobWindow();
        if (!stopped) { sample = { status, received: sent }; setView(windowView(status, performance.now() - sent)); }
      } catch { if (!stopped) { sample = null; setView(unavailable); } }
    };
    void refresh();
    const poll = window.setInterval(() => void refresh(), 30_000);
    const tick = window.setInterval(() => {
      if (sample) setView(windowView(sample.status, performance.now() - sample.received));
    }, 1000);
    return () => { stopped = true; window.clearInterval(poll); window.clearInterval(tick); };
  }, []);
  return <JobWindowContext.Provider value={view}>{children}</JobWindowContext.Provider>;
}

export function useJobWindow() { return useContext(JobWindowContext); }

/**
 * The one suffix rule for every gated control (UI review 2026-10-18, F-7): a control a role may not use ends with
 * "Role: not permitted"; a device-job control outside its window ends with "Next window: HH:MM". Buttons render it as
 * " · <suffix>" after the label, menu items the same way, so the same fact reads the same everywhere. The role reason
 * wins over the window (the window would wrongly promise the action opens up at that time).
 */
export interface Gate {
  /** True when the control must not be used right now (role refused, a fixed reason, or the device-job window is closed). */
  readonly locked: boolean;
  /** The words after the label ("Role: not permitted", "Next window: 12:00", a fixed reason), or null when usable. */
  readonly suffix: string | null;
}

export function useGate(plane?: string | readonly string[], options: { readonly deviceJob?: boolean; readonly lockedReason?: string } = {}): Gate {
  const jobWindow = useJobWindow();
  const roleAllowed = useRoleAllows(plane);
  if (options.lockedReason) return { locked: true, suffix: options.lockedReason };
  if (!roleAllowed) return { locked: true, suffix: ROLE_LOCK_LABEL };
  if (options.deviceJob && !jobWindow.open) return { locked: true, suffix: jobWindow.label };
  return { locked: false, suffix: null };
}

/** `label · suffix`, or the bare label when the control is usable. */
export function withGateSuffix(label: string, gate: Gate): string {
  return gate.suffix ? `${label} · ${gate.suffix}` : label;
}

/**
 * A window-gated device-job button. With `plane`, a session that does not hold that plane sees it locked with the
 * role reason instead of "Next window" (which would wrongly promise the action opens up at that time).
 */
export function JobButton({ disabled, children, plane, sx, ...props }: ButtonProps & { readonly plane?: string | readonly string[] }) {
  const gate = useGate(plane, { deviceJob: true });
  return (
    <Button {...props} sx={[DISABLED_STYLE, ...(Array.isArray(sx) ? sx : [sx])]} disabled={disabled || gate.locked}>
      {children}{gate.suffix && ` · ${gate.suffix}`}
    </Button>
  );
}
