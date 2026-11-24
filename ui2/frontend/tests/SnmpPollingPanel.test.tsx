import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SNMP_SWITCH_ON_STARTS, SnmpPollingPanel, snmpPatch } from "../src/components/settings/SnmpPollingPanel";
import type { SnmpPollingView } from "../src/auth/adminApi";

// Synthetic data only: pseudonym device names, opaque ids and references.
const api = vi.hoisted(() => ({ get: vi.fn(), save: vi.fn(), devices: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({
  getSnmpPolling: api.get,
  saveSnmpPolling: api.save,
  listDevices: api.devices,
}));

const stored: SnmpPollingView = {
  enabled: false, interval_s: 300, scope_mode: "VENDORS", default_credential_reference_id: null,
  raw_retention_days: 35, hourly_retention_days: 400,
  vendors: [{ vendor: "check_point", in_scope: true, credential_reference_id: null },
    { vendor: "palo_alto", in_scope: true, credential_reference_id: null }],
  devices: [],
  credentials: [
    { credential_reference_id: "cref-1", display_name: "SNMP pilot user", security_level: "authPriv", auth_protocol: "SHA-256", priv_protocol: "AES-128", suitable: true },
    { credential_reference_id: "cref-2", display_name: "Old SNMP user", security_level: "authPriv", auth_protocol: "MD5", priv_protocol: "DES", suitable: false },
  ],
  eligible_devices: [{ device_id: "dev-1", vendor: "check_point" }, { device_id: "dev-2", vendor: "palo_alto" }],
  last_outcome_counts: {}, pilot_vendors: ["check_point", "palo_alto"], interval_min_s: 60, interval_max_s: 3600,
  warnings: [], updated_at: "2026-11-24T09:00:00Z", updated_by: "snmp-polling-migration", can_write: true,
};

function pick(testId: string, optionName: RegExp | string) {
  fireEvent.mouseDown(within(screen.getByTestId(testId).parentElement as HTMLElement).getByRole("combobox"));
  fireEvent.click(within(screen.getByRole("listbox")).getByRole("option", { name: optionName }));
}

describe("SnmpPollingPanel", () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(stored);
    api.devices.mockReset().mockResolvedValue({ devices: [
      { device_id: "dev-1", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
      { device_id: "dev-2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-BRAVO-02", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
    ] });
    api.save.mockReset().mockImplementation(async (patch: Partial<SnmpPollingView>) =>
      ({ ...stored, ...patch, updated_at: "2026-11-24T10:00:00Z", updated_by: "actor-security-admin" }));
  });

  it("shows the switch off, the interval, both pilot vendors, retention and the last change; nothing to save yet", async () => {
    render(<SnmpPollingPanel />);
    expect(await screen.findByLabelText("Poll devices over SNMP")).not.toBeChecked();
    expect(screen.getByTestId("snmp-fleet-switch")).toHaveTextContent("Off.");
    expect(screen.getByLabelText("Interval (seconds)")).toHaveValue(300);
    expect(screen.getByTestId("snmp-vendor-check_point")).toHaveTextContent("Check Point");
    expect(screen.getByTestId("snmp-vendor-palo_alto")).toHaveTextContent("Palo Alto");
    expect(screen.getByText(/samples 35 days, hourly p95 and maximum 400 days/)).toBeInTheDocument();
    expect(screen.getByTestId("snmp-last-changed")).toHaveTextContent(/by snmp-polling-migration/);
    expect(screen.getByTestId("snmp-last-outcomes")).toHaveTextContent("No poll yet.");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("turning the switch on asks first, says what starts, and saves only the changed keys", async () => {
    render(<SnmpPollingPanel />);
    fireEvent.click(await screen.findByLabelText("Poll devices over SNMP"));
    pick("snmp-default-credential", /SNMP pilot user/);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = screen.getByRole("dialog", { name: "Turn SNMP polling on?" });
    for (const line of SNMP_SWITCH_ON_STARTS) expect(dialog).toHaveTextContent(line);
    expect(dialog).toHaveTextContent(/Nothing is ever set on a device/);
    expect(api.save).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Turn on and save" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith({ enabled: true, default_credential_reference_id: "cref-1" }));
    expect(await screen.findByText("Saved. Polling starts within 15 seconds.")).toBeInTheDocument();
  });

  it("flags a credential the poller will not use and shows the server's warnings", async () => {
    api.get.mockResolvedValue({ ...stored, warnings: ['Credential "Old SNMP user" is not authPriv with SHA-2 authentication and AES privacy; the poller will not use it (SNMP_CREDENTIAL_UNSUITABLE).'] });
    render(<SnmpPollingPanel />);
    expect(await screen.findByTestId("snmp-warnings")).toHaveTextContent("SNMP_CREDENTIAL_UNSUITABLE");
    fireEvent.mouseDown(within(screen.getByTestId("snmp-default-credential").parentElement as HTMLElement).getByRole("combobox"));
    expect(within(screen.getByRole("listbox")).getByRole("option", { name: /Old SNMP user .*not used: needs authPriv, SHA-2 and AES/ })).toBeInTheDocument();
  });

  it("adds a device row by name, excludes it, and sends the whole device list", async () => {
    render(<SnmpPollingPanel />);
    await screen.findByLabelText("Poll devices over SNMP");
    await waitFor(() => expect(api.devices).toHaveBeenCalled());
    pick("snmp-add-device", /FW-BRAVO-02 \(Palo Alto\)/);
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(screen.getByTestId("snmp-device-rows")).toHaveTextContent("FW-BRAVO-02");
    fireEvent.mouseDown(within(screen.getByTestId("snmp-device-rows")).getByRole("combobox", { name: "Polling" }));
    fireEvent.click(within(screen.getByRole("listbox")).getByRole("option", { name: "Excluded" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith({
      devices: [{ device_id: "dev-2", in_scope: false, credential_reference_id: null }] }));
  });

  it("a refused save shows the server's problems and keeps the draft", async () => {
    api.save.mockRejectedValue({ status: 400, body: { code: "SNMP_POLLING_SETTINGS_INVALID", problems: ["interval_s: must be a whole number of seconds between 60 and 3600"] } });
    render(<SnmpPollingPanel />);
    fireEvent.change(await screen.findByLabelText("Interval (seconds)"), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/interval_s: must be a whole number/)).toBeInTheDocument();
    expect(screen.getByLabelText("Interval (seconds)")).toHaveValue(30);
  });

  it("is read-only without the write permission and for the masked aiview projection", async () => {
    api.get.mockResolvedValue({ ...stored, can_write: false, enabled: true, default_credential_reference_id: "CRED-1",
      credentials: [{ ...stored.credentials[0], credential_reference_id: "CRED-1", display_name: "CRED-1" }],
      last_outcome_counts: { OK: 3, SNMP_NO_ANSWER: 1 } });
    render(<SnmpPollingPanel />);
    expect(await screen.findByText(/Read-only: changing SNMP polling needs the Security Admin role/)).toBeInTheDocument();
    expect(screen.getByLabelText("Poll devices over SNMP")).toBeDisabled();
    expect(screen.getByTestId("snmp-default-credential")).toHaveTextContent("Fleet default credential: CRED-1");
    expect(screen.getByTestId("snmp-interval")).toHaveTextContent("Every 300 seconds");
    expect(screen.getByTestId("snmp-last-outcomes")).toHaveTextContent("OK: 3 devices");
    expect(screen.getByTestId("snmp-last-outcomes")).toHaveTextContent("SNMP_NO_ANSWER: 1 device");
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByTestId("snmp-add-device")).toBeNull();
  });

  it("a restricted read shows the shared Restricted state", async () => {
    api.get.mockRejectedValue({ status: 403, body: { error: "ACTION_REFUSED" } });
    render(<SnmpPollingPanel />);
    expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
  });

  it("the patch carries only what changed", () => {
    const base = { enabled: false, interval_s: 300, scope_mode: "VENDORS" as const, default_credential_reference_id: null,
      vendors: stored.vendors, devices: [] };
    expect(snmpPatch(stored, base)).toEqual({});
    expect(snmpPatch(stored, { ...base, interval_s: 600, scope_mode: "DEVICES" })).toEqual({ interval_s: 600, scope_mode: "DEVICES" });
    const vendors = [{ ...stored.vendors[0], credential_reference_id: "cref-1" }, stored.vendors[1]];
    expect(snmpPatch(stored, { ...base, vendors })).toEqual({ vendors });
  });
});
