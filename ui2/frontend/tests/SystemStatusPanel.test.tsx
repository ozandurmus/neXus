import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { SystemStatusPanel } from "../src/screens/SystemStatusPanel";
import * as api from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", () => ({ getSystemPods: vi.fn(), getSystemStorage: vi.fn() }));
afterEach(() => vi.resetAllMocks());

it("warns once per owner role and includes quarantined task leases", async () => {
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-10-06T00:00:00Z", pods: [], modules: [
      { module: "policy", owner: "policy", quarantined_permits: 2 },
      { module: "inventory", owner: "general", quarantined_permits: 1 },
      { module: "backup", owner: "general", quarantined_permits: 1 },
      { module: "scheduler", owner: "scheduler", quarantined_permits: 0 },
    ], quarantined_by_role: [
      { owner: "policy", quarantined_permits: 2 },
      { owner: "general", quarantined_permits: 1 },
      { owner: "service", quarantined_permits: 1 },
    ],
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue({
    read_at: "2026-10-06T00:00:00Z", backups: [], top_devices: [], database_bytes: 0,
    configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 },
    artefact_volume: {},
  });
  render(<SystemStatusPanel />);
  expect(await screen.findByText(/policy: 2 quarantined/)).toBeInTheDocument();
  expect(screen.getAllByText(/general: 1 quarantined/)).toHaveLength(1);
  expect(screen.queryByText(/scheduler: 0 quarantined/)).not.toBeInTheDocument();
  expect(screen.getByText(/service: 1 quarantined/)).toBeInTheDocument();
  expect(screen.getAllByRole("alert")).toHaveLength(3);
});

it("explains the top-bar light and lists one-off jobs and older scheduled runs without counting them (2026-11-14)", async () => {
  const base = { ready: "0/1", restarts: 0, started_at: null, image_digest: null, cpu_millicores: null, cpu_limit_millicores: null, memory_bytes: null, memory_limit_bytes: null };
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-11-14T09:00:00Z", quarantined_by_role: [], pods: [
      { ...base, name: "svc-a-x", component: "svc", phase: "Running", state: "Running", ready: "1/1", owner_kind: "ReplicaSet", owner_name: "svc-a", created_at: "2026-11-13T18:00:00Z" },
      { ...base, name: "nightly-check-29908740-x", component: "e2e", phase: "Failed", state: "Failed", owner_kind: "Job", owner_name: "nightly-check-29908740", created_at: "2026-11-12T23:00:00Z" },
      { ...base, name: "nightly-check-29910180-x", component: "e2e", phase: "Succeeded", state: "Succeeded", owner_kind: "Job", owner_name: "nightly-check-29910180", created_at: "2026-11-13T23:00:00Z" },
      { ...base, name: "nightly-check-clean-1110-x", component: "e2e", phase: "Failed", state: "Failed", owner_kind: "Job", owner_name: "nightly-check-clean-1110", created_at: "2026-11-09T23:01:51Z" },
    ],
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue({
    read_at: "2026-11-14T09:00:00Z", backups: [], top_devices: [], database_bytes: 0,
    configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 }, artefact_volume: {},
  });
  render(<SystemStatusPanel />);
  const summary = await screen.findByTestId("platform-health-summary");
  expect(summary).toHaveTextContent("Overwatch OK");
  expect(summary).toHaveTextContent("All 1 service pod running and ready.");
  expect(summary).toHaveTextContent("1 one-off job pod is listed below; one-off jobs and older scheduled runs do not change the status.");
  // No error alert for a status that is fine (the e2e safety check fails closed on warning and error alerts).
  expect(screen.queryAllByRole("alert")).toHaveLength(0);
  screen.getByRole("button", { name: "Show service pods (4)" }).click();
  expect(await screen.findByText("nightly-check, older run")).toBeInTheDocument();
  expect(screen.getByText("nightly-check, latest run")).toBeInTheDocument();
  expect(screen.getByText("One-off job")).toBeInTheDocument();
  expect(screen.getByText("Service")).toBeInTheDocument();
});

const emptyStorage = {
  read_at: "2026-10-06T00:00:00Z", backups: [], top_devices: [], database_bytes: 0,
  configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 },
  artefact_volume: { total_bytes: 1000, usable_bytes: 70, used_percent: 93, level: "critical" as const, note: "synthetic volume" },
};
const diskView = (level: "ok" | "warning" | "critical", volumes: Array<{ name: string; used_percent: number; level: "ok" | "warning" | "critical" }>) => ({
  level, thresholds: { warning_percent: 80, critical_percent: 90 },
  volumes: volumes.map((v) => ({ ...v, total_bytes: 1000, usable_bytes: 1000 - v.used_percent * 10 })),
});

it("shows the disk headroom alert with the volume name and percentage, and the artefact volume percentage", async () => {
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-10-06T00:00:00Z", pods: [],
    disk: diskView("critical", [{ name: "root", used_percent: 72, level: "ok" }, { name: "artefact_store", used_percent: 93, level: "critical" }]),
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue(emptyStorage);
  render(<SystemStatusPanel />);
  const alert = await screen.findByText(/Disk critical: artefact_store 93% used \(critical\)/);
  expect(alert).toBeInTheDocument();
  expect(alert).not.toHaveTextContent("root 72%");
  expect(alert).toHaveTextContent("Thresholds: warning 80%, critical 90%.");
  expect(screen.getByText(/93% used · synthetic volume/)).toBeInTheDocument();
});

it("shows no disk alert while every volume is under its threshold", async () => {
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-10-06T00:00:00Z", pods: [], disk: diskView("ok", [{ name: "root", used_percent: 72, level: "ok" }]),
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue({ ...emptyStorage, artefact_volume: {} });
  render(<SystemStatusPanel />);
  await screen.findByText(/What the evidence takes/);
  expect(screen.queryByText(/Disk (warning|critical)/)).not.toBeInTheDocument();
});

it("renders artefact volume near-limit warning when storage approaches quota", async () => {
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-10-06T00:00:00Z", pods: [], modules: [], quarantined_by_role: [],
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue({
    read_at: "2026-10-06T00:00:00Z", backups: [], top_devices: [], database_bytes: 0,
    configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 },
    artefact_volume: {
      quota_bytes: 1000,
      used_bytes: 850,
      remaining_bytes: 150,
      usage_percent: 85,
      near_limit: true,
      quota_exceeded: false,
      warning: "artefact_store_near_quota: artefact store usage (850 of 1000 bytes, 85%) has reached the 80% warning threshold",
    },
  });
  render(<SystemStatusPanel />);
  expect(await screen.findByText(/artefact store is at 85% of its quota/)).toBeInTheDocument();
  expect(screen.getByText(/nothing is deleted automatically/)).toBeInTheDocument();
  expect(screen.getAllByRole("alert")).toHaveLength(1);
  expect(screen.getByText("ARTEFACT STORE QUOTA")).toBeInTheDocument();
});

it("renders the store-full error when the quota is reached", async () => {
  vi.mocked(api.getSystemPods).mockResolvedValue({
    available: true, read_at: "2026-10-06T00:00:00Z", pods: [], modules: [], quarantined_by_role: [],
  });
  vi.mocked(api.getSystemStorage).mockResolvedValue({
    read_at: "2026-10-06T00:00:00Z", backups: [], top_devices: [], database_bytes: 0,
    configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 },
    artefact_volume: {
      quota_bytes: 1000, used_bytes: 1000, remaining_bytes: 0, usage_percent: 100,
      near_limit: true, quota_exceeded: true, warning: "artefact_store_quota_exceeded: synthetic",
    },
  });
  render(<SystemStatusPanel />);
  expect(await screen.findByText(/artefact store is full/)).toBeInTheDocument();
  expect(screen.getByText(/New backups and collections are refused/)).toBeInTheDocument();
});
