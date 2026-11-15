import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { BackupStoreQuotas } from "../src/auth/adminApi";
import { BackupStoreQuotaCard, gibToBytes, quotaSummary, quotaToGib } from "../src/screens/BackupStoreQuotaCard";

const GIB = 1024 ** 3;

const quotas: BackupStoreQuotas = {
  store_mounted: true,
  stores: [
    { store: "check_point", used_bytes: 9 * GIB, quota_bytes: 10 * GIB, remaining_bytes: GIB, percent: 90, near_limit: true, quota_exceeded: false,
      warning: "artefact_store_near_quota: store=check_point has used at least 80% of its quota" },
    { store: "palo_alto", used_bytes: 12 * GIB, quota_bytes: 10 * GIB, remaining_bytes: 0, percent: 120, near_limit: true, quota_exceeded: true,
      warning: "artefact_store_quota_exceeded: store=palo_alto is at or over its quota" },
    { store: "fortinet", used_bytes: 3 * GIB, quota_bytes: null, remaining_bytes: null, percent: null, near_limit: false, quota_exceeded: false },
  ],
};

describe("Storage quota per store (V225)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("counts stores with a quota and names what needs attention", () => {
    expect(quotaSummary(quotas.stores)).toEqual({ withQuota: 2, exceeded: 1, near: 1 });
    render(<BackupStoreQuotaCard quotas={quotas} onChanged={() => {}} />);
    expect(screen.getByText("Storage quota per store")).toBeInTheDocument();
    expect(screen.getByText(/1 store at or over quota: new backups to it are refused/)).toBeInTheDocument();
  });

  it("says UNKNOWN, not zero, when the read failed", () => {
    render(<BackupStoreQuotaCard quotas={null} onChanged={() => {}} />);
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.getByText("Store quotas unavailable")).toBeInTheDocument();
  });

  it("converts the typed GB amount and refuses anything that is not a positive number", () => {
    expect(gibToBytes("2")).toBe(2 * GIB);
    expect(gibToBytes("0.5")).toBe(GIB / 2);
    expect(gibToBytes("")).toBeNull();
    expect(gibToBytes("0")).toBeNull();
    expect(gibToBytes("abc")).toBeNull();
    expect(quotaToGib(null)).toBe("");
    expect(quotaToGib(10 * GIB)).toBe("10");
  });

  it("lists used / quota with the warning chips and saves and removes a quota through the audited route", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? "GET"} ${url} ${init?.body ?? ""}`);
      return Promise.resolve(new Response(JSON.stringify({ stores: [] }), { status: 200 }));
    }));
    const onChanged = vi.fn();
    render(<BackupStoreQuotaCard quotas={quotas} onChanged={onChanged} />);
    fireEvent.click(screen.getByText("Set quotas →"));

    const palo = await screen.findByRole("listitem", { name: "Store palo_alto" });
    expect(within(palo).getByText("Over quota")).toBeInTheDocument();
    expect(within(palo).getByText(/12.0 GB used \/ 10.0 GB \(120%\)/)).toBeInTheDocument();
    expect(within(screen.getByRole("listitem", { name: "Store check_point" })).getByText("Near limit")).toBeInTheDocument();
    const fortinet = screen.getByRole("listitem", { name: "Store fortinet" });
    expect(within(fortinet).getByText(/no quota/)).toBeInTheDocument();
    expect(within(fortinet).getByRole("button", { name: "Remove quota" })).toBeDisabled();

    fireEvent.change(within(fortinet).getByLabelText("Quota in GB for fortinet"), { target: { value: "5" } });
    fireEvent.click(within(fortinet).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(calls.some((c) => c === `PUT /api/v2/backups/store-quotas/fortinet {"quota_bytes":${5 * GIB}}`)).toBe(true);

    fireEvent.click(within(palo).getByRole("button", { name: "Remove quota" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(2));
    expect(calls.some((c) => c === 'PUT /api/v2/backups/store-quotas/palo_alto {"quota_bytes":null}')).toBe(true);
  });

  it("shows the refusal in words when the session may not change a quota", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(JSON.stringify({ error: "FORBIDDEN" }), { status: 403 }))));
    render(<BackupStoreQuotaCard quotas={quotas} onChanged={() => {}} />);
    fireEvent.click(screen.getByText("Set quotas →"));
    const fortinet = await screen.findByRole("listitem", { name: "Store fortinet" });
    fireEvent.change(within(fortinet).getByLabelText("Quota in GB for fortinet"), { target: { value: "5" } });
    fireEvent.click(within(fortinet).getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/backup_admin/);
  });
});
