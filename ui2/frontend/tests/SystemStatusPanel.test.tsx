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
