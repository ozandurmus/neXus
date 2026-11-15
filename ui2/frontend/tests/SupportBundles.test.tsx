import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SupportBundlesScreen, collisionHint } from "../src/screens/SupportBundlesScreen";
import {
  deleteSupportBundle, getSupportBundleCatalogue, listDevices, listSupportBundles, requestSupportBundle,
  requestSupportBundleDownloadTicket,
} from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  listSupportBundles: vi.fn(), getSupportBundleCatalogue: vi.fn(), listDevices: vi.fn(), requestSupportBundle: vi.fn(),
  cancelSupportBundle: vi.fn(), deleteSupportBundle: vi.fn(), requestSupportBundleDownloadTicket: vi.fn(),
}));

/** Support bundles screen (SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md 5.12). Masked names and documentation values only. */
const ID = "11111111-1111-4111-8111-111111111111";
const row = (over: Record<string, unknown> = {}) => ({
  id: ID, device_id: "d-cp", device_pseudonym: "FW-TANGO-04", vendor: "check_point", run_mode: "NOW",
  run_at: "2026-11-15T01:00:00Z", requested_at: "2026-11-15T01:00:00Z", finished_at: "2026-11-15T01:02:00Z",
  expires_at: "2026-11-29T01:02:00Z", expiry_warning: false, downloaded_at: null, download_count: 0, state: "COMPLETED",
  outcome: "COMPLETED", items: [{ kind: "cp_gaia_extras", ordinal: 1, state: "COMPLETED", outcome_code: "COMPLETED", size_bytes: 18432,
    digest_prefix: "e3b0c44298fc", detail: null }], ...over,
});
const kinds = [
  { kind: "cpdata_collector_present", vendor: "check_point", load: "low", default_ticked: true, enabled: true, heavy: false },
  { kind: "cp_gaia_extras", vendor: "check_point", load: "low", default_ticked: true, enabled: true, heavy: false },
  { kind: "cp_hcp_latest", vendor: "check_point", load: "none", default_ticked: true, enabled: true, heavy: false },
  { kind: "cp_hcp_run", vendor: "check_point", load: "medium", default_ticked: false, enabled: false, heavy: true },
  { kind: "cp_cpinfo", vendor: "check_point", load: "high", default_ticked: false, enabled: false, heavy: true },
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getSupportBundleCatalogue).mockResolvedValue({ kinds } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [{ device_id: "d-cp", hostname: "FW-TANGO-04", vendor_hint: "check_point" }] } as never);
  vi.mocked(listSupportBundles).mockResolvedValue({ support_bundles: [row()] } as never);
});

describe("support bundles screen", () => {
  it("lists a bundle with its items, size and digest prefix", async () => {
    render(<SupportBundlesScreen />);
    expect(await screen.findByText("FW-TANGO-04", { selector: "td" })).toBeTruthy();
    expect(screen.getByText(/18 KiB e3b0c44298fc/)).toBeTruthy();
  });

  it("ticks the light items by default and leaves heavy ones unavailable", async () => {
    render(<SupportBundlesScreen />);
    fireEvent.mouseDown(await screen.findByLabelText("Device"));
    fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-04" }));
    const extras = await screen.findByLabelText(/Gaia details/);
    expect((extras as HTMLInputElement).checked).toBe(true);
    const cpinfo = screen.getByLabelText(/cpinfo/) as HTMLInputElement;
    expect(cpinfo.checked).toBe(false);
    expect(cpinfo.disabled).toBe(true);
  });

  it("sends the ticked kinds with the reason", async () => {
    vi.mocked(requestSupportBundle).mockResolvedValue({ id: ID, job_id: "j", status: "ACCEPTED" });
    render(<SupportBundlesScreen />);
    fireEvent.mouseDown(await screen.findByLabelText("Device"));
    fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-04" }));
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "TAC case preparation" } });
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    await waitFor(() => expect(requestSupportBundle).toHaveBeenCalled());
    expect(vi.mocked(requestSupportBundle).mock.calls[0][1]).toMatchObject({
      kinds: ["cpdata_collector_present", "cp_gaia_extras", "cp_hcp_latest"], reason: "TAC case preparation" });
  });

  it("offers a delete confirmation after a download starts", async () => {
    vi.mocked(requestSupportBundleDownloadTicket).mockResolvedValue({ ticket: "t", href: "about:blank#t", expires_in_s: 60 });
    vi.mocked(deleteSupportBundle).mockResolvedValue({ id: ID, state: "DELETED" });
    render(<SupportBundlesScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Download" }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.change(dialog.getByLabelText(/Reason/), { target: { value: "Attach to the case" } });
    fireEvent.click(dialog.getByRole("button", { name: "Download" }));
    expect(await screen.findByText("Delete the bundle now?")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(deleteSupportBundle).toHaveBeenCalledWith(ID, expect.stringContaining("after download")));
  });

  it("shows the expiry warning of a bundle that was never downloaded", async () => {
    vi.mocked(listSupportBundles).mockResolvedValue({ support_bundles: [row({ expiry_warning: true })] } as never);
    render(<SupportBundlesScreen />);
    expect(await screen.findByText(/has not been downloaded/)).toBeTruthy();
  });

  it("hints at a time that collides with neXus' own night work, never refusing it", () => {
    expect(collisionHint("2026-11-16T02:30")).toMatch(/backups/);
    expect(collisionHint("2026-11-16T03:10")).toMatch(/HCP/);
    expect(collisionHint("2026-11-16T06:00")).toMatch(/system-log/);
    expect(collisionHint("2026-11-16T04:00")).toBeNull();
  });
});
