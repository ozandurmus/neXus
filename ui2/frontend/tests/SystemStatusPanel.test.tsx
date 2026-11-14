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
  expect(summary).toHaveTextContent("neXus OK");
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
