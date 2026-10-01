import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import { CredentialsPanel, CreateCredentialDialog } from "../src/screens/CredentialsPanel";
import * as api from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", () => ({ createCredential: vi.fn(), replaceCredentialSecret: vi.fn(),
  listCredentials: vi.fn(), deleteCredential: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

async function select(label: string, value: string) {
  fireEvent.mouseDown(screen.getByRole("combobox", { name: new RegExp(`^${label}`) }));
  fireEvent.click(within(await screen.findByRole("listbox")).getByText(value));
}

it("creates a community credential without username and warns about clear text", async () => {
  vi.mocked(api.createCredential).mockResolvedValue({} as api.CredentialView);
  render(<CreateCredentialDialog onClose={() => {}} onCreated={() => {}} />);
  await select("Kind", "SNMP v1/v2c");
  expect(screen.getByText("Community sent in clear text.")).toBeInTheDocument();
  expect(screen.queryByLabelText("Username")).toBeNull();
  fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Synthetic SNMP" } });
  fireEvent.change(screen.getByLabelText("Community"), { target: { value: "synthetic-community" } });
  fireEvent.click(screen.getByRole("button", { name: "Add" }));
  await waitFor(() => expect(api.createCredential).toHaveBeenCalledWith("Synthetic SNMP", "snmp_v1_v2c", "",
    false, false, "synthetic-community", "", undefined));
});

it("warns for weak algorithms and both no-privacy levels, discarding hidden secrets", async () => {
  vi.mocked(api.createCredential).mockResolvedValue({} as api.CredentialView);
  render(<CreateCredentialDialog onClose={() => {}} onCreated={() => {}} />);
  await select("Kind", "SNMP v3");
  await select("Authentication protocol", "MD5");
  expect(screen.getByText("MD5/DES are weak.")).toBeInTheDocument();
  await select("Authentication protocol", "SHA-512");
  await select("Privacy protocol", "DES");
  expect(screen.getByText("MD5/DES are weak.")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Authentication secret"), { target: { value: "synthetic-auth" } });
  fireEvent.change(screen.getByLabelText("Privacy secret"), { target: { value: "synthetic-privacy" } });
  await select("Security level", "authNoPriv");
  expect(screen.getByText("No privacy, data exposed.")).toBeInTheDocument();
  expect(screen.queryByLabelText("Privacy secret")).toBeNull();
  await select("Security level", "noAuthNoPriv");
  expect(screen.getByText("No privacy, data exposed.")).toBeInTheDocument();
  expect(screen.queryByLabelText("Authentication secret")).toBeNull();
  fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Synthetic SNMP" } });
  fireEvent.change(screen.getByLabelText("Username"), { target: { value: "synthetic-user" } });
  fireEvent.click(screen.getByRole("button", { name: "Add" }));
  await waitFor(() => expect(api.createCredential).toHaveBeenCalledWith("Synthetic SNMP", "snmp_v3", "synthetic-user",
    false, false, "", "", { securityLevel: "noAuthNoPriv", authProtocol: null, privProtocol: null }));
});

it("edits SNMP metadata and secrets through the existing update route and deletes by opaque ID", async () => {
  const credential = { credential_id: "synthetic-id", display_name: "Synthetic SNMP", kind: "snmp_v3",
    username: "synthetic-user", snmp: { securityLevel: "authPriv", authProtocol: "SHA-256", privProtocol: "AES-128" },
    auth_secret: "set", priv_secret: "set" } as api.CredentialView;
  vi.mocked(api.listCredentials).mockResolvedValue({ credentials: [credential] });
  vi.mocked(api.replaceCredentialSecret).mockResolvedValue(credential);
  vi.mocked(api.deleteCredential).mockResolvedValue({ credential_id: "synthetic-id", deleted: true });
  render(<CredentialsPanel />);
  fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
  expect(screen.getByLabelText("Authentication secret")).toHaveValue("");
  expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  await select("Security level", "noAuthNoPriv");
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(api.replaceCredentialSecret).toHaveBeenCalledWith("synthetic-id", "", "",
    { securityLevel: "noAuthNoPriv", authProtocol: null, privProtocol: null }, "synthetic-user"));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  fireEvent.click(screen.getByRole("button", { name: "Delete" }));
  await waitFor(() => expect(api.deleteCredential).toHaveBeenCalledWith("synthetic-id"));
});

it("shows the existing restricted state when the server refuses access", async () => {
  vi.mocked(api.listCredentials).mockRejectedValue({ status: 403, body: { error: "ACTION_REFUSED" } });
  render(<CredentialsPanel />);
  expect(await screen.findByText(/Security Admin/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Add credential" })).toBeNull();
});
