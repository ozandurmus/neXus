import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SupportBundlesScreen, bundleDevice, collisionHint } from "../src/screens/SupportBundlesScreen";
import {
  cleanupSupportBundleWorkDir, deleteSupportBundle, getSupportBundleCatalogue, getSupportBundleUploadDestinations, listDevices,
  listSupportBundleUploads, listSupportBundles, requestSupportBundle, requestSupportBundleDownloadTicket, startSupportBundleUpload,
} from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  listSupportBundles: vi.fn(), getSupportBundleCatalogue: vi.fn(), listDevices: vi.fn(), requestSupportBundle: vi.fn(),
  cancelSupportBundle: vi.fn(), deleteSupportBundle: vi.fn(), requestSupportBundleDownloadTicket: vi.fn(),
  getSupportBundleUploadDestinations: vi.fn(), listSupportBundleUploads: vi.fn(), startSupportBundleUpload: vi.fn(),
  cleanupSupportBundleWorkDir: vi.fn(),
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
  { kind: "cpdata_collector_present", vendor: "check_point", load: "low", default_ticked: true, enabled: true, heavy: false, check_point_management: true },
  { kind: "cp_gaia_extras", vendor: "check_point", load: "low", default_ticked: true, enabled: true, heavy: false },
  { kind: "cp_hcp_latest", vendor: "check_point", load: "none", default_ticked: true, enabled: true, heavy: false },
  { kind: "cp_hcp_run", vendor: "check_point", load: "medium", default_ticked: false, enabled: true, heavy: true, check_point_management: true },
  { kind: "cp_cpinfo", vendor: "check_point", load: "high", default_ticked: false, enabled: true, heavy: true, check_point_management: true },
  { kind: "cpdata_collector_run", vendor: "check_point", load: "unknown", default_ticked: false, enabled: true, heavy: true, check_point_management: true },
  { kind: "pan_tsf", vendor: "palo_alto", load: "unknown", default_ticked: true, enabled: true, heavy: false },
  { kind: "asa_crashinfo", vendor: "cisco_asa", load: "low", default_ticked: true, enabled: true, heavy: false },
  { kind: "asa_show_tech", vendor: "cisco_asa", load: "unknown", default_ticked: true, enabled: true, heavy: false },
  { kind: "fgt_crashlog", vendor: "fortinet", load: "low", default_ticked: true, enabled: true, heavy: false },
  { kind: "fgt_tac_report", vendor: "fortinet", load: "unknown", default_ticked: true, enabled: true, heavy: false },
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getSupportBundleCatalogue).mockResolvedValue({ kinds } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [{ device_id: "d-cp", hostname: "FW-TANGO-04", vendor_hint: "check_point" }] } as never);
  vi.mocked(listSupportBundles).mockResolvedValue({ support_bundles: [row()] } as never);
  vi.mocked(getSupportBundleUploadDestinations).mockResolvedValue({ manual_only_vendors: ["check_point"], destinations: [
    { id: "cisco_cxd_https", label: "Cisco CXD (HTTPS)", method: "HTTPS_PUT", vendor: "cisco_asa", host: "cxd.cisco.com", port: 443, generic: false },
    { id: "fortinet_sftp", label: "Fortinet support (SFTP)", method: "SFTP", vendor: "fortinet", host: "support.fortinet.com", port: 2222, generic: false },
    { id: "generic_sftp", label: "Generic SFTP server", method: "SFTP", vendor: null, host: null, port: 22, generic: true },
  ] } as never);
  vi.mocked(listSupportBundleUploads).mockResolvedValue({ uploads: [] } as never);
});

describe("support bundles screen", () => {
  it("lists a bundle with its items, size and digest prefix", async () => {
    render(<SupportBundlesScreen />);
    expect(await screen.findByText("FW-TANGO-04", { selector: "td" })).toBeTruthy();
    expect(screen.getByText(/18 KiB e3b0c44298fc/)).toBeTruthy();
  });

  it("ticks the light items by default and leaves the heavy ones available but unticked", async () => {
    render(<SupportBundlesScreen />);
    fireEvent.mouseDown(await screen.findByLabelText("Device"));
    fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-04" }));
    const extras = await screen.findByLabelText(/Gaia details/);
    expect((extras as HTMLInputElement).checked).toBe(true);
    const cpinfo = screen.getByLabelText(/cpinfo/) as HTMLInputElement;
    expect(cpinfo.checked).toBe(false);
    expect(cpinfo.disabled).toBe(false);
    expect((screen.getByLabelText(/Run HCP now/) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText(/cpdata_collector run/) as HTMLInputElement).checked).toBe(false);
    expect(screen.queryByText(/Multi-Domain Server has crashed/)).toBeNull();
  });

  it("offers a Check Point management server the heavy items only, with a clear warning and no block", async () => {
    vi.mocked(listDevices).mockResolvedValue({ devices: [{ device_id: "d-mds", hostname: "FW-TANGO-06", vendor_hint: "check_point", role: "management_server" }] } as never);
    vi.mocked(requestSupportBundle).mockResolvedValue({ id: ID, job_id: "j", status: "ACCEPTED", mds_warning: true, warning: "Synthetic MDS warning from the server." });
    render(<SupportBundlesScreen />);
    fireEvent.mouseDown(await screen.findByLabelText("Device"));
    fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-06" }));
    expect(await screen.findByText(/Multi-Domain Server has crashed/)).toBeTruthy();
    expect(screen.queryByLabelText(/Gaia details/)).toBeNull();
    expect(screen.queryByLabelText(/Latest HCP health report/)).toBeNull();
    const hcp = screen.getByLabelText(/Run HCP now/) as HTMLInputElement;
    expect(hcp.disabled).toBe(false);
    fireEvent.click(hcp);
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "TAC case preparation" } });
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    await waitFor(() => expect(requestSupportBundle).toHaveBeenCalled());
    expect(vi.mocked(requestSupportBundle).mock.calls[0][1]).toMatchObject({ kinds: ["cpdata_collector_present", "cp_hcp_run"] });
    expect(await screen.findByText(/Synthetic MDS warning from the server/)).toBeTruthy();
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

  it("offers the four vendors' gateways, Panorama and a Check Point management server, not a FortiManager", () => {
    expect(bundleDevice({ vendor_hint: "check_point", role: "gateway" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "palo_alto", role: "gateway" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "palo_alto", role: "management_server" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "cisco_asa", role: "gateway" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "fortinet", role: "gateway" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "check_point", role: "management_server" })).toBe(true);
    expect(bundleDevice({ vendor_hint: "fortinet", role: "management_server" })).toBe(false);
    expect(bundleDevice({ vendor_hint: "infoblox", role: "appliance" })).toBe(false);
  });

  it.each([
    ["palo_alto", "d-pan", ["pan_tsf"]],
    ["cisco_asa", "d-asa", ["asa_crashinfo", "asa_show_tech"]],
    ["fortinet", "d-fgt", ["fgt_crashlog", "fgt_tac_report"]],
  ])("ticks the %s items by default and sends them", async (vendor, id, expected) => {
    vi.mocked(listDevices).mockResolvedValue({ devices: [{ device_id: id, hostname: "FW-TANGO-05", vendor_hint: vendor, role: "gateway" }] } as never);
    vi.mocked(requestSupportBundle).mockResolvedValue({ id: ID, job_id: "j", status: "ACCEPTED" });
    render(<SupportBundlesScreen />);
    fireEvent.mouseDown(await screen.findByLabelText("Device"));
    fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-05" }));
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "TAC case preparation" } });
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    await waitFor(() => expect(requestSupportBundle).toHaveBeenCalled());
    expect(vi.mocked(requestSupportBundle).mock.calls[0][0]).toBe(id);
    expect(vi.mocked(requestSupportBundle).mock.calls[0][1]).toMatchObject({ kinds: expected });
  });

  it("hints at a time that collides with neXus' own night work, never refusing it", () => {
    expect(collisionHint("2026-11-16T02:30")).toMatch(/backups/);
    expect(collisionHint("2026-11-16T03:10")).toMatch(/HCP/);
    expect(collisionHint("2026-11-16T06:00")).toMatch(/system-log/);
    expect(collisionHint("2026-11-16T04:00")).toBeNull();
  });

  it("shows Check Point as a manual upload and offers only the generic SFTP server", async () => {
    render(<SupportBundlesScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Upload" }));
    expect(await screen.findByText(/Manual upload: Check Point has no scriptable upload route yet/)).toBeTruthy();
    fireEvent.mouseDown(within(screen.getByRole("dialog")).getByLabelText("Destination"));
    expect(screen.getByRole("option", { name: /Generic SFTP server/ })).toBeTruthy();
    expect(screen.queryByRole("option", { name: /Cisco CXD/ })).toBeNull();
    expect(screen.queryByRole("option", { name: /Fortinet/ })).toBeNull();
  });

  it("sends a Cisco upload only after the confirmation, with the credential from the field, and clears it afterwards", async () => {
    vi.mocked(listSupportBundles).mockResolvedValue({ support_bundles: [row({ vendor: "cisco_asa", case_ref: "CASE-1" })] } as never);
    vi.mocked(startSupportBundleUpload).mockResolvedValue({ upload_id: "u1", status: "RUNNING" });
    render(<SupportBundlesScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Upload" }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.mouseDown(dialog.getByLabelText("Destination"));
    fireEvent.click(await screen.findByRole("option", { name: /Cisco CXD/ }));
    expect(screen.queryByRole("option", { name: /Fortinet/ })).toBeNull();
    fireEvent.change(dialog.getByLabelText("Case (SR) number"), { target: { value: "CASE-1" } });
    const secret = dialog.getByLabelText("Upload token") as HTMLInputElement;
    expect(secret.type).toBe("password");
    fireEvent.change(secret, { target: { value: "synthetic-token-1" } });
    fireEvent.change(dialog.getByLabelText(/Reason/), { target: { value: "Attach to the TAC case" } });
    const send = dialog.getByRole("button", { name: "Send" }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);   // not confirmed yet
    expect(dialog.getByText(/bank firewall may need an opening/)).toBeTruthy();
    fireEvent.click(dialog.getByLabelText(/I confirm that this bundle may be sent to cxd.cisco.com:443/));
    expect(send.disabled).toBe(false);
    fireEvent.click(send);
    await waitFor(() => expect(startSupportBundleUpload).toHaveBeenCalled());
    expect(vi.mocked(startSupportBundleUpload).mock.calls[0][1]).toMatchObject({
      destination: "cisco_cxd_https", user: "CASE-1", password: "synthetic-token-1", reason: "Attach to the TAC case",
      item_kinds: ["cp_gaia_extras"], case_ref: "CASE-1" });
    await waitFor(() => expect((dialog.getByLabelText("Upload token") as HTMLInputElement).value).toBe(""));
  });

  it("takes the host and port of a generic SFTP upload and refuses a bad port before sending", async () => {
    vi.mocked(startSupportBundleUpload).mockResolvedValue({ upload_id: "u1", status: "RUNNING" });
    render(<SupportBundlesScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Upload" }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.mouseDown(dialog.getByLabelText("Destination"));
    fireEvent.click(await screen.findByRole("option", { name: /Generic SFTP server/ }));
    fireEvent.change(dialog.getByLabelText("Host"), { target: { value: "sftp.example.com" } });
    fireEvent.change(dialog.getByLabelText("Port"), { target: { value: "70000" } });
    fireEvent.change(dialog.getByLabelText("User"), { target: { value: "svc" } });
    fireEvent.change(dialog.getByLabelText("Password"), { target: { value: "synthetic-pw" } });
    fireEvent.change(dialog.getByLabelText(/Reason/), { target: { value: "Send to the lab server" } });
    fireEvent.click(dialog.getByLabelText(/I confirm that this bundle may be sent to sftp.example.com/));
    expect((dialog.getByRole("button", { name: "Send" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(dialog.getByLabelText("Port"), { target: { value: "2022" } });
    fireEvent.click(dialog.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(startSupportBundleUpload).toHaveBeenCalled());
    expect(vi.mocked(startSupportBundleUpload).mock.calls[0][1]).toMatchObject({ destination: "generic_sftp", host: "sftp.example.com", port: 2022, user: "svc" });
  });

  it("shows an upload's progress, result, a changed host key warning and a clear connection failure", async () => {
    vi.mocked(listSupportBundleUploads).mockResolvedValue({ uploads: [
      { id: "u1", request_id: ID, destination: "cisco_cxd_sftp", destination_host: "cxd.cisco.com", destination_port: 22, remote_name: "b.tgz",
        state: "COMPLETED", started_at: null, finished_at: null, attempts: 1, bytes_total: 2097152, bytes_sent: 2097152,
        sha256: "ab".repeat(32), remote_status: "SFTP_OK size 2097152 confirmed", error_code: null, error_detail: null,
        host_key_changed: true, item_count: 1 },
      { id: "u2", request_id: ID, destination: "cisco_cxd_sftp", destination_host: "cxd.cisco.com", destination_port: 22, remote_name: "b.tgz",
        state: "FAILED", started_at: null, finished_at: null, attempts: 2, bytes_total: 2097152, bytes_sent: 0, sha256: null, remote_status: null,
        error_code: "CONNECTION_FAILED", error_detail: "Could not reach cxd.cisco.com:22. The bank firewall may need an opening.",
        host_key_changed: false, item_count: 1 },
    ] } as never);
    render(<SupportBundlesScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Upload" }));
    expect(await screen.findByText(/Remote answer: SFTP_OK size 2097152 confirmed/)).toBeTruthy();
    expect(screen.getByText(/host key differs from one seen before. The upload was not blocked/)).toBeTruthy();
    expect(screen.getByText(/CONNECTION_FAILED: Could not reach cxd.cisco.com:22/)).toBeTruthy();
  });

  it("offers the admin-confirmed removal of a leftover work directory only where one is recorded", async () => {
    vi.mocked(listSupportBundles).mockResolvedValue({ support_bundles: [row({ state: "FAILED", work_dir_pending: true, cleanup_state: null })] } as never);
    vi.mocked(cleanupSupportBundleWorkDir).mockResolvedValue({ id: ID, job_id: "j", status: "ACCEPTED" });
    render(<SupportBundlesScreen />);
    expect(await screen.findByText(/work directory from this run is still on the device/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Remove leftover work directory" }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.change(dialog.getByLabelText(/Reason/), { target: { value: "Remove the leftover directory" } });
    const remove = dialog.getByRole("button", { name: "Remove" }) as HTMLButtonElement;
    expect(remove.disabled).toBe(true);
    fireEvent.click(dialog.getByLabelText(/I confirm that the leftover work directory/));
    fireEvent.click(remove);
    await waitFor(() => expect(cleanupSupportBundleWorkDir).toHaveBeenCalledWith(ID, "Remove the leftover directory"));
  });

  it("offers no removal when no directory is left", async () => {
    render(<SupportBundlesScreen />);
    await screen.findByText("FW-TANGO-04", { selector: "td" });
    expect(screen.queryByRole("button", { name: "Remove leftover work directory" })).toBeNull();
  });
});
