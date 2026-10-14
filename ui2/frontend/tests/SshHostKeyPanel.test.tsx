import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { SshHostKeyPanel } from "../src/screens/SshHostKeyPanel";
import { SessionContext } from "../src/auth/SessionContext";
const api = vi.hoisted(() => ({ get: vi.fn(), mode: vi.fn(), accept: vi.fn(), reject: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ getSshHostKeyView: api.get, setSshTrustMode: api.mode,
  acceptSshHostKey: api.accept, rejectSshHostKey: api.reject }));
const observation = { observation_id: "observation-fixture", device_id: "device-fixture", hostname: "FW-TANGO-04",
  key_algorithm: "ssh-ed25519", presented_fingerprint: "a".repeat(64), expected_fingerprint_prefix: "b".repeat(16),
  first_seen: "2026-10-14T00:00:00Z", last_seen: "2026-10-14T01:00:00Z", seen_count: 3, status: "OPEN",
  strict_refusal: false, ssh_identity_unverified: true, model: "Synthetic model", software_version: "1.0",
  serial_number: "SERIAL-MASKED-01", management_source: "MANAGEMENT_DISCOVERY" };
beforeEach(() => {
  vi.resetAllMocks(); api.get.mockResolvedValue({ mode: "WARN", observations: [observation] });
  api.accept.mockResolvedValue({}); api.reject.mockResolvedValue({}); api.mode.mockResolvedValue({});
});
function panel(manage = true, settings = false) {
  return render(<SessionContext.Provider value={{ displayName: "Synthetic operator", roleTokens: [],
    canReadAdministration: manage, permissions: [], onSignOut: () => {} }}>
    <SshHostKeyPanel deviceId="device-fixture" settings={settings} />
  </SessionContext.Provider>);
}
it("shows the warning, masked identity, fingerprints, provenance and manager view to readers", async () => {
  panel(false);
  expect(await screen.findByText("SSH host key changed for FW-TANGO-04")).toBeInTheDocument();
  expect(screen.getByText(/Evidence collected under an unverified identity/)).toBeInTheDocument();
  expect(screen.getByText(/Presented/)).toHaveTextContent("a".repeat(64));
  expect(screen.getByText(/Management-plane view/)).toHaveTextContent("Synthetic model");
  expect(screen.queryByRole("button", { name: "Accept new key" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
});
it("requires the exact attestation text before accepting and refreshes the observation", async () => {
  panel(); const accept = await screen.findByRole("button", { name: "Accept new key" });
  expect(accept).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Confirmation"), { target: { value: "yes" } });
  expect(accept).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Confirmation"), { target: { value: "ACCEPT NEW SSH KEY" } });
  fireEvent.click(accept);
  await waitFor(() => expect(api.accept).toHaveBeenCalledWith("observation-fixture", "ACCEPT NEW SSH KEY"));
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
});
it("rejects without changing the pin and displays forced refusal", async () => {
  panel(); await screen.findByRole("button", { name: "Reject" });
  api.get.mockResolvedValue({ mode: "WARN", observations: [{ ...observation, status: "REJECTED", strict_refusal: true }] });
  fireEvent.click(screen.getByRole("button", { name: "Reject" }));
  await waitFor(() => expect(api.reject).toHaveBeenCalledWith("observation-fixture"));
  expect(await screen.findByText(/Strict refusal for this device/)).toBeInTheDocument();
});
it("saves strict mode and reports update failures", async () => {
  panel(true, true); const mode = await screen.findByRole("combobox", { name: "Trust mode" });
  fireEvent.mouseDown(mode); fireEvent.click(await screen.findByRole("option", { name: "STRICT" }));
  api.mode.mockRejectedValue(new Error());
  fireEvent.click(screen.getByRole("button", { name: "Save trust mode" }));
  await waitFor(() => expect(api.mode).toHaveBeenCalledWith("STRICT"));
  expect(await screen.findByText(/Unable to update SSH trust/)).toBeInTheDocument();
});
it("retains historical unverified-evidence warning after acceptance", async () => {
  api.get.mockResolvedValue({ mode: "WARN", observations: [{ ...observation, status: "ACCEPTED" }] });
  panel(); expect(await screen.findByText(/Evidence collected under an unverified identity/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Accept new key" })).not.toBeInTheDocument();
});
