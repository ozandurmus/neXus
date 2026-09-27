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
  "job_failure", "config_change", "compliance_regression", "device_health"];
const settings: NotificationSettingsView = {
  syslog_enabled: false, syslog_host: null, syslog_port: 514, syslog_protocol: "udp", syslog_facility: 16,
  smtp_enabled: true, smtp_host: "relay.example.test", smtp_port: 25, smtp_starttls: false,
  smtp_from: "nexus@example.test", smtp_to: "default@example.test", forward_audit_to_syslog: false,
  notify_job_failure: false,
  routes: types.map((type) => ({ type, enabled: false, recipients: null, last_sent_at: null, last_error: null })),
};

describe("NotificationSettingsPanel", () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(settings);
    api.save.mockReset().mockImplementation(async (value: NotificationSettingsView) => value);
    api.test.mockReset().mockResolvedValue({ sent: true, detail: "accepted" });
  });

  it("lists seven types with separate recipients and tests the current form values", async () => {
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
});
