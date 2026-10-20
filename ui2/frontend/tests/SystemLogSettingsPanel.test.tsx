import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SystemLogSettingsPanel, SYSTEM_LOG_SETTING_ROWS } from "../src/components/settings/SystemLogSettingsPanel";
import type { SystemLogSettingsView } from "../src/auth/adminApi";

const api = vi.hoisted(() => ({ get: vi.fn(), save: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({
  getSystemLogSettings: api.get,
  saveSystemLogSettings: api.save,
}));

const GIB = 1024 * 1024 * 1024;
const MIB = 1024 * 1024;
const stored: SystemLogSettingsView = {
  raw_retention_days: 30, device_cap_bytes: 2 * GIB, hcp_retention_days: 7, spike_data_retention_days: 30,
  spike_data_cap_bytes: 512 * MIB, ledger_retention_days: 180, include_management_servers: false, spike_data_enabled: false,
  updated_at: "2026-10-20T09:00:00Z", updated_by: "system-log-migration", can_write: true,
  bounds: {
    raw_retention_days: { type: "integer", min: 1, max: 3650 }, device_cap_bytes: { type: "integer", min: 64 * MIB, max: 1024 * GIB },
    hcp_retention_days: { type: "integer", min: 1, max: 3650 }, spike_data_retention_days: { type: "integer", min: 1, max: 3650 },
    spike_data_cap_bytes: { type: "integer", min: MIB, max: 1024 * GIB }, ledger_retention_days: { type: "integer", min: 1, max: 3650 },
    include_management_servers: { type: "boolean" }, spike_data_enabled: { type: "boolean" },
  },
};

describe("SystemLogSettingsPanel", () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(stored);
    api.save.mockReset().mockImplementation(async (patch: Partial<SystemLogSettingsView>) =>
      ({ ...stored, ...patch, updated_at: "2026-10-20T10:00:00Z", updated_by: "actor-security-admin" }));
  });

  it("shows every documented setting with its explanation, the stored value and the last change", async () => {
    render(<SystemLogSettingsPanel />);
    for (const row of SYSTEM_LOG_SETTING_ROWS) {
      const section = within(await screen.findByTestId(`syslog-setting-${row.key}`));
      expect(section.getAllByText(row.label, { exact: false }).length).toBeGreaterThan(0);
      expect(section.getByText(row.explanation, { exact: false })).toBeInTheDocument();
    }
    expect(screen.getByLabelText("Raw log retention (days)")).toHaveValue(30);
    expect(screen.getByLabelText("Per-device storage cap (MiB)")).toHaveValue(2048);
    expect(screen.getByLabelText("Fetch Spike Detective data")).not.toBeChecked();
    expect(screen.getByTestId("syslog-settings-last-changed")).toHaveTextContent(/by system-log-migration/);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("asks a confirm step that says what starts happening before spike data is switched on, then saves only that key", async () => {
    render(<SystemLogSettingsPanel />);
    fireEvent.click(await screen.findByLabelText("Fetch Spike Detective data"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = screen.getByRole("dialog", { name: "Fetch Spike Detective data from the gateways?" });
    expect(dialog).toHaveTextContent(/next job window \(00:00, 06:00, 12:00 and 18:00 Europe\/Istanbul\)/);
    expect(dialog).toHaveTextContent(/command C4/);
    expect(dialog).toHaveTextContent(/lists it \(C5\)/);
    expect(dialog).toHaveTextContent(/\(C8\)/);
    expect(dialog).toHaveTextContent(/encrypted archive/);
    expect(api.save).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch on and save" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith({ spike_data_enabled: true }));
    const saved = await screen.findByText(/Fetch Spike Detective data starts at the next job window/);
    expect(saved).toHaveAttribute("role", "status");
    expect(screen.getByTestId("syslog-settings-last-changed")).toHaveTextContent(/by actor-security-admin/);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("cancel in the confirm step saves nothing, and the management-server switch has its own confirm", async () => {
    render(<SystemLogSettingsPanel />);
    fireEvent.click(await screen.findByLabelText("Collect from management servers"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = screen.getByRole("dialog", { name: "Include management servers in system-log collection?" });
    expect(dialog).toHaveTextContent(/GATE_NOT_KNOWN/);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(api.save).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Collect from management servers")).toBeChecked();
  });

  it("a retention or cap change saves directly, sends bytes for a MiB field, and shows the service's problems when refused", async () => {
    render(<SystemLogSettingsPanel />);
    fireEvent.change(await screen.findByLabelText("Raw log retention (days)"), { target: { value: "45" } });
    fireEvent.change(screen.getByLabelText("Spike Detective storage cap (MiB)"), { target: { value: "1024" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith({ raw_retention_days: 45, spike_data_cap_bytes: 1024 * MIB }));
    expect(screen.queryByRole("dialog")).toBeNull();

    api.save.mockRejectedValueOnce({ status: 400, body: { code: "SYSTEM_LOG_SETTINGS_INVALID", problems: ["raw_retention_days: must be between 1 and 3650"] } });
    fireEvent.change(screen.getByLabelText("Raw log retention (days)"), { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const refusal = await screen.findByText("Not saved", { exact: false });
    expect(refusal.closest("[data-state='error']")).toHaveTextContent(/raw_retention_days: must be between 1 and 3650/);
    expect(refusal.closest("[data-state='error']")).toHaveAttribute("role", "status");
  });

  it("without the write permission the values are read-only with the role note and no Save", async () => {
    api.get.mockResolvedValue({ ...stored, can_write: false });
    render(<SystemLogSettingsPanel />);
    const note = await screen.findByText(/needs the Security Admin role/);
    expect(note).toHaveAttribute("role", "status");
    expect(screen.getByTestId("syslog-value-device_cap_bytes")).toHaveTextContent("2048 MiB (2 GiB)");
    expect(screen.getByTestId("syslog-value-raw_retention_days")).toHaveTextContent("30 days");
    expect(screen.getByLabelText("Fetch Spike Detective data")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByLabelText("Raw log retention (days)")).toBeNull();
  });

  it("a refused read is the restricted state, never an error", async () => {
    api.get.mockRejectedValue({ status: 403, body: { error: "ACTION_REFUSED" } });
    render(<SystemLogSettingsPanel />);
    const panel = await screen.findByText("System logs", { exact: false });
    expect(panel.closest("[data-state='restricted']")).toHaveTextContent(/Security Admin/);
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });
});
