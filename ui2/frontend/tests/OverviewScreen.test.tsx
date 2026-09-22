import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OverviewScreen, computeFigures } from "../src/screens/OverviewScreen";
import type { DeviceSummary } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

function device(id: string, extra: Partial<DeviceSummary> = {}): DeviceSummary {
  return {
    device_id: id, vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: `FW-TANGO-${id}`,
    model: null, software_version: null, ha_role: null, cluster_member_ref: null, ...extra,
  };
}

describe("the Overview counts what the store returned (PO, 2026-09-22: it read '0 devices enrolled' over 103)", () => {
  it("computes enrolled, vendors, clusters, inventory, configuration and backup coverage", () => {
    const f = computeFigures({
      devices: [
        device("a", { ip_addresses: "192.0.2.1", cluster_member_ref: "CLS-ROMEO-01", backup_target: true }),
        device("b", { ip_addresses: "192.0.2.2", cluster_member_ref: "CLS-ROMEO-01" }),
        device("c", { vendor_hint: "palo_alto", enrollment_state: "DRAFT" }),
      ],
      configurationDeviceIds: ["a", "zzz"],
      backups: [
        { artefact_id: "1", device_id: "a", collected_at: "2026-09-20T02:00:00Z", size_bytes: 1, digest_prefix: "", validation_level: "V1", deviation_state: null },
        { artefact_id: "2", device_id: "a", collected_at: "2026-09-22T02:00:00Z", size_bytes: 1, digest_prefix: "", validation_level: "V1", deviation_state: null },
      ],
      jobsTotal: 40, jobsRunning: 2, jobsFailed24h: 3, compliance: null,
      now: new Date("2026-09-23T02:00:00Z"),
    });

    expect(f.devicesTotal).toBe(3);
    expect(f.devicesEnrolled).toBe(2);
    expect(f.devicesDraft).toBe(1);
    expect(f.byVendor).toEqual({ check_point: 2, palo_alto: 1 });
    expect(f.clusters).toBe(1);
    expect(f.withInventory).toBe(2);
    expect(f.withConfiguration).toBe(1);
    expect(f.withBackup).toBe(1);
    expect(f.backupTargets).toBe(1);
    expect(f.oldestBackupAgeDays).toBe(1);
  });

  it("a failed read is reported as failed, never as zero", () => {
    const f = computeFigures({ devices: [device("a")], configurationDeviceIds: null, backups: null, jobsTotal: null, jobsRunning: null, jobsFailed24h: null, compliance: null });
    expect(f.withConfiguration).toBeNull();
    expect(f.withBackup).toBeNull();
    expect(f.jobsTotal).toBeNull();
  });

  it("renders the enrolled count from /devices, not a constant", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") return Promise.resolve(new Response(JSON.stringify({ devices: [device("a", { ip_addresses: "192.0.2.1" }), device("b")] })));
      if (url.startsWith("/api/v2/jobs")) return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, page_size: 1, total: 7 })));
      if (url === "/backups") return Promise.resolve(new Response(JSON.stringify({ backups: [] })));
      if (url === "/configuration") return Promise.resolve(new Response(JSON.stringify({ devices: [] })));
      return Promise.resolve(new Response("{}", { status: 404 }));
    }));

    render(<OverviewScreen />);

    await waitFor(() => expect(screen.getByText(/2 of 2 devices enrolled · 2 Check Point/)).toBeInTheDocument());
    expect(screen.getByText(/1 of 2 with collected interfaces/)).toBeInTheDocument();
    expect(screen.getByText(/7 on record/)).toBeInTheDocument();
  });
});
