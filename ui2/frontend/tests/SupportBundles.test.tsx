import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SupportBundlesScreen, bundleDevice, collisionHint, nextQuietHourLocal, notStartedSentence, waitWarning } from "../src/screens/SupportBundlesScreen";
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

  it("picks quiet hours avoiding 00/06/12/18 collection windows and 02:00 backups", () => {
    // 23:46 GMT+3 (20:46Z) -> 04:00 next morning
    expect(nextQuietHourLocal(new Date("2026-11-25T20:46:00Z"))).toBe("2026-11-26T04:00");
    // 02:46 GMT+3 (23:46Z) -> 04:00 same morning
    expect(nextQuietHourLocal(new Date("2026-11-25T23:46:00Z"))).toBe("2026-11-26T04:00");
    // 11:00 GMT+3 (08:00Z) -> 20:00 same evening
    expect(nextQuietHourLocal(new Date("2026-11-26T08:00:00Z"))).toBe("2026-11-26T20:00");
    // 20:10 GMT+3 (17:10Z) -> 21:00 same evening
    expect(nextQuietHourLocal(new Date("2026-11-26T17:10:00Z"))).toBe("2026-11-26T21:00");
    // 23:10 GMT+3 (20:10Z) -> 23:30 same evening
    expect(nextQuietHourLocal(new Date("2026-11-26T20:10:00Z"))).toBe("2026-11-26T23:30");
  });

  it("shows Request again only for NOT_STARTED_DEVICE_BUSY or CANCELLED before start, pre-fills the form, and sends previous_request_id", async () => {
    const busyId = "22222222-2222-4222-8222-222222222222";
    const cancelledId = "33333333-3333-4333-8333-333333333333";
    const startedCancelId = "44444444-4444-4444-8444-444444444444";
    vi.mocked(listSupportBundles).mockResolvedValue({
      support_bundles: [
        row(),
        row({
          id: busyId, state: "FAILED", outcome: "NOT_STARTED_DEVICE_BUSY", started_at: null,
          finished_at: "2026-11-25T23:46:00Z", case_ref: "SR-7700",
          hold_reasons: ["MODULE_DRAIN", "COLLECTION_WINDOW", "DEVICE_JOB_RUNNING"],
          items: [
            { kind: "cp_gaia_extras", ordinal: 1, state: "CANCELLED", outcome_code: "NOT_STARTED_DEVICE_BUSY", size_bytes: null, digest_prefix: null, detail: null },
            { kind: "cp_cpinfo", ordinal: 2, vs_id: 2, domain_ref: "dom-retail", state: "CANCELLED", outcome_code: "NOT_STARTED_DEVICE_BUSY", size_bytes: null, digest_prefix: null, detail: null },
          ],
        }),
        row({ id: cancelledId, state: "CANCELLED", outcome: "CANCELLED", started_at: null }),
        row({ id: startedCancelId, state: "CANCELLED", outcome: "CANCELLED", started_at: "2026-11-25T01:00:10Z" }),
      ],
    } as never);
    vi.mocked(requestSupportBundle).mockResolvedValue({ id: ID, job_id: "j-retry", status: "ACCEPTED" });

    render(<SupportBundlesScreen />);
    await screen.findAllByText("FW-TANGO-04", { selector: "td" });

    // COMPLETED and started-then-CANCELLED rows have no Request again button; busy-expired and pre-start cancelled rows do
    const completedRow = document.querySelector(`[data-bundle="${ID}"]`) as HTMLElement;
    const busyRow = document.querySelector(`[data-bundle="${busyId}"]`) as HTMLElement;
    const preCancelRow = document.querySelector(`[data-bundle="${cancelledId}"]`) as HTMLElement;
    const postStartCancelRow = document.querySelector(`[data-bundle="${startedCancelId}"]`) as HTMLElement;
    expect(within(completedRow).queryByRole("button", { name: "Request again" })).toBeNull();
    expect(within(postStartCancelRow).queryByRole("button", { name: "Request again" })).toBeNull();
    expect(within(preCancelRow).getByRole("button", { name: "Request again" })).toBeTruthy();

    // Row displays the plain NOT_STARTED_DEVICE_BUSY sentence built from closed hold reasons and expiry time
    expect(within(busyRow).getByText(/Did not start: held by a module drain, a collection window, and another job on the device; expired 2026-11-26 02:46 GMT\+3\./)).toBeTruthy();

    // Click Request again -> form pre-fills device, exact kinds (including unticked cp_cpinfo), case_ref, and suggested quiet hour
    fireEvent.click(within(busyRow).getByRole("button", { name: "Request again" }));
    expect(await screen.findByText(/Pre-filled from request 22222222 for the next quiet hour/)).toBeTruthy();
    expect((screen.getByLabelText(/Gaia details/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/cpinfo/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/Latest HCP health report/) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText(/Vendor case reference/) as HTMLInputElement).value).toBe("SR-7700");
    const runAtInput = screen.getByLabelText(/Run at/) as HTMLInputElement;
    expect(runAtInput.value).toMatch(/T(04:00|20:00|21:00|22:00|23:00|23:30)$/);

    // Operator types a fresh reason and clicks Collect
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Re-run after night window cleared" } });
    fireEvent.click(screen.getByRole("button", { name: "Collect" }));
    await waitFor(() => expect(requestSupportBundle).toHaveBeenCalled());
    expect(vi.mocked(requestSupportBundle).mock.calls[0][0]).toBe("d-cp");
    expect(vi.mocked(requestSupportBundle).mock.calls[0][1]).toMatchObject({
      kinds: ["cp_gaia_extras", "cp_cpinfo"],
      reason: "Re-run after night window cleared",
      case_ref: "SR-7700",
      previous_request_id: busyId,
      vs_ids: [2],
      domain_ref: "dom-retail",
    });
    expect(vi.mocked(requestSupportBundle).mock.calls[0][1].run_at).toBeTruthy();
  });

  it("shows the bidirectional link between an expired-busy request and its automatic retry, plus detail sentence", async () => {
    const origId = "55555555-5555-4555-8555-555555555555";
    const retryId = "66666666-6666-4666-8666-666666666666";
    vi.mocked(listSupportBundles).mockResolvedValue({
      support_bundles: [
        row({
          id: retryId, state: "QUEUED", outcome: null, run_mode: "AT", run_at: "2026-11-26T01:00:00Z",
          previous_request_id: origId, auto_retry: true,
        }),
        row({
          id: origId, state: "FAILED", outcome: "NOT_STARTED_DEVICE_BUSY", started_at: null,
          finished_at: "2026-11-25T23:46:00Z", hold_reasons: ["MODULE_DRAIN"], retry_request_id: retryId,
          retry_outcome: "RETRY_SCHEDULED",
        }),
      ],
    } as never);
    render(<SupportBundlesScreen />);
    await screen.findAllByText("FW-TANGO-04", { selector: "td" });

    const retryRow = document.querySelector(`[data-bundle="${retryId}"]`) as HTMLElement;
    const origRow = document.querySelector(`[data-bundle="${origId}"]`) as HTMLElement;
    expect(within(retryRow).getByText(/Automatic retry of/)).toBeTruthy();
    expect(within(retryRow).getByRole("button", { name: "55555555" })).toBeTruthy();
    expect(within(retryRow).getByRole("button", { name: "Cancel" })).toBeTruthy();
    expect(within(origRow).getByText(/Retried as/)).toBeTruthy();
    expect(within(origRow).getByRole("button", { name: "66666666" })).toBeTruthy();

    // Open Details on the expired-busy row: shows the same plain sentence and link
    fireEvent.click(within(origRow).getByRole("button", { name: "Details" }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.getByText(/Did not start: held by a module drain; expired 2026-11-26 02:46 GMT\+3\./)).toBeTruthy();
    expect(dialog.getByRole("button", { name: retryId })).toBeTruthy();
  });

  it("warns before submit when a module drain is active or inside a collection window", async () => {
    expect(waitWarning("", true, false, null)).toMatch(/module drain is active; this will wait until/);
    expect(waitWarning("", false, true, "2026-11-25T22:00:00Z")).toMatch(/collection window is active; this will wait until/);
    expect(waitWarning("2026-11-26T00:15", false, false, null)).toMatch(/00:00-00:59 is a scheduled collection window; this will wait until 01:00/);
    expect(waitWarning("2026-11-26T04:00", false, false, null)).toBeNull();
    expect(notStartedSentence({ outcome: "NOT_STARTED_DEVICE_BUSY", hold_reasons: [], finished_at: "2026-11-25T23:46:00Z", expires_at: "2026-11-29T00:00:00Z" }))
      .toMatch(/device remained busy for 3 hours; expired 2026-11-26 02:46 GMT\+3/);

    vi.mocked(getSupportBundleCatalogue).mockResolvedValue({
      kinds, module_draining: true, collection_window_open: true, collection_window_ends_at: "2026-11-25T22:00:00Z",
    } as never);
    render(<SupportBundlesScreen />);
    const alert = (await screen.findByText(/A module drain and a collection window are active; this will wait until/)).closest('[role="alert"]');
    expect(alert).toBeTruthy();
    expect(alert?.getAttribute("data-status-alert")).toBe("operational");
    expect(alert?.getAttribute("data-fact")).toBe("wait-warning");
  });
});
