import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationSettingsPanel } from "../src/components/settings/NotificationSettingsPanel";
import type { NotificationRouteView, NotificationSettingsView } from "../src/auth/adminApi";

const api = vi.hoisted(() => ({
  get: vi.fn(), save: vi.fn(), test: vi.fn(),
}));
vi.mock("../src/auth/adminApi", () => ({
  getNotificationSettings: api.get,
  saveNotificationSettings: api.save,
  testNotification: api.test,
}));

const types: NotificationRouteView["type"][] = ["admin_event", "login_security", "backup_failure",
  "job_failure", "config_change", "compliance_regression", "device_health", "security_scan",
  "device_crash_file", "backup_size_anomaly"];
const settings: NotificationSettingsView = {
  syslog_enabled: false, syslog_host: null, syslog_port: 514, syslog_protocol: "udp", syslog_facility: 16,
  smtp_enabled: true, smtp_host: "relay.example.test", smtp_port: 25, smtp_starttls: false,
  smtp_from: "nexus@example.test", smtp_to: "default@example.test", forward_audit_to_syslog: false,
  notify_job_failure: false, notification_identity: "masked",
  routes: types.map((type) => ({ type, enabled: false, recipients: null, last_sent_at: null, last_error: null })),
};

describe("NotificationSettingsPanel", () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(settings);
    api.save.mockReset().mockImplementation(async (value: NotificationSettingsView) => value);
    api.test.mockReset().mockResolvedValue({ sent: true, detail: "accepted" });
  });

  it("lists ten types with separate recipients and tests the current form values", async () => {
    render(<NotificationSettingsPanel />);
    for (const type of types) expect(await screen.findByTestId(`route-${type}`)).toBeInTheDocument();
    const row = within(screen.getByTestId("route-admin_event"));
    fireEvent.change(row.getByLabelText("Administration changes recipients"), { target: { value: "admin@example.test" } });
    fireEvent.change(screen.getByLabelText("Relay host"), { target: { value: "unsaved.example.test" } });
    fireEvent.click(row.getByRole("button", { name: "Send test" }));
    await waitFor(() => expect(api.test).toHaveBeenCalled());
    expect(api.test).toHaveBeenCalledWith("mail", "admin_event", expect.objectContaining({
      smtp_host: "unsaved.example.test",
      routes: expect.arrayContaining([expect.objectContaining({ type: "admin_event", recipients: "admin@example.test" })]),
    }));
    expect(within(screen.getByTestId("route-login_security")).getByLabelText("Sign-in and access recipients"))
      .toHaveValue("");
  });

  it("defaults to masked, saves raw only after an explicit choice, and explains a disabled control", async () => {
    const { unmount } = render(<NotificationSettingsPanel />);
    const masked = await screen.findByRole("radio", { name: "Masked (recommended)" });
    expect(masked).toBeChecked();
    expect(screen.getByRole("radio", { name: "Raw" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "Raw" }));
    expect(screen.getByText(/real hostnames leave this appliance/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ notification_identity: "raw" })));
    unmount();

    render(<NotificationSettingsPanel canChangeIdentity={false} />);
    expect(await screen.findByRole("radio", { name: "Masked (recommended)" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "Raw" })).toBeDisabled();
    expect(screen.getByText("Only a Security Admin can change this setting.")).toBeInTheDocument();
  });
});
