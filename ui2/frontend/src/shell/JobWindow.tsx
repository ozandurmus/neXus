import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import Button, { type ButtonProps } from "@mui/material/Button";
import { getJobWindow } from "../auth/adminApi";

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
export function JobButton({ disabled, children, ...props }: ButtonProps) {
  const window = useJobWindow();
  return <Button {...props} disabled={disabled || !window.open}>{children}{window.label && ` · ${window.label}`}</Button>;
}
