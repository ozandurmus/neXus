import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { DirectorySettingsPanel } from "../src/components/settings/DirectorySettingsPanel";

/** Synthetic only: example.local names and 192.0.2.x addresses. */
const PEM = "-----BEGIN CERTIFICATE-----\nc3ludGhldGlj\n-----END CERTIFICATE-----";
const savedView = {
  configured: true, enabled: true, serverType: "ACTIVE_DIRECTORY", servers: ["dc1.example.local", "192.0.2.10"], port: 636,
  domainDn: "DC=example,DC=local", adminGroupDn: "CN=nexus-admins,OU=Groups,DC=example,DC=local",
  usersGroupDn: "CN=nexus-users,OU=Groups,DC=example,DC=local", bindDn: "svc-nexus@example.local", bindPasswordSet: true,
  connectTimeoutSeconds: 10, ldaps: true, trustMode: "TRUST_PEM", trustPem: PEM, updatedAt: "2026-11-11T08:00:00Z",
  adminGroupRoles: ["role:security_admin", "role:operation_admin"], usersGroupRoles: ["role:viewer"],
};
const defaultsView = {
  ...savedView, configured: false, enabled: false, servers: [], domainDn: "", adminGroupDn: "", usersGroupDn: "", bindDn: "",
  bindPasswordSet: false, trustPem: "", updatedAt: null,
};

interface Call { readonly url: string; readonly method: string; readonly headers: Headers; readonly body: any }
type Reply = { status?: number; body: unknown };

function json({ status = 200, body }: Reply): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Mocks fetch underneath the shared adminApi call() helper, so the CSRF header and the real request bodies are seen. */
function mockApi(replies: { get?: Reply; put?: Reply; test?: Reply } = {}): Call[] {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({ url, method, headers: new Headers(init?.headers), body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
    if (url.includes("/session/status")) return json({ body: { csrf_token: "csrf-test-token" } });
    if (url.endsWith("/api/v2/config/ldap/test")) return json(replies.test ?? { body: {} });
    if (url.endsWith("/api/v2/config/ldap") && method === "PUT") return json(replies.put ?? { body: savedView });
    if (url.endsWith("/api/v2/config/ldap")) return json(replies.get ?? { body: savedView });
    return json({ status: 404, body: {} });
  });
  return calls;
}

describe("DirectorySettingsPanel", () => {
  afterEach(() => vi.restoreAllMocks());

  it("renders the saved view with the stored-password helper and never a password value", async () => {
    mockApi();
    render(<DirectorySettingsPanel />);
    expect(await screen.findByLabelText("Server 1")).toHaveValue("dc1.example.local");
    expect(screen.getByLabelText("Server 2")).toHaveValue("192.0.2.10");
    expect(screen.getByLabelText("Enable LDAP authentication")).toBeChecked();
    expect(screen.getByLabelText("Domain DN (search base)")).toHaveValue("DC=example,DC=local");
    expect(screen.getByLabelText("Port")).toHaveValue(636);
    expect(screen.getByLabelText("Connection timeout (seconds)")).toHaveValue(10);
    expect(screen.getByLabelText("LDAP bind DN")).toHaveValue("svc-nexus@example.local");
    const password = screen.getByLabelText("LDAP bind password");
    expect(password).toHaveValue("");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByText("A password is stored. Leave blank to keep it.")).toBeInTheDocument();
    expect(screen.getByText("Members receive: Security Admin, Operation Admin.")).toBeInTheDocument();
    expect(screen.getByText("Members receive: Viewer.")).toBeInTheDocument();
    expect(screen.getByText("Active Directory")).toBeInTheDocument();
    expect(screen.getByText(/Users sign in with their sAMAccountName \(for example jdoe\)/)).toBeInTheDocument();
    expect(screen.getByLabelText("Trust only the certificate below")).toBeChecked();
    expect(screen.getByLabelText("Certificate (PEM)")).toHaveValue(PEM);
  });

  it("starts from the defaults when nothing is saved, with one empty server row", async () => {
    mockApi({ get: { body: defaultsView } });
    render(<DirectorySettingsPanel />);
    expect(await screen.findByLabelText("Server 1")).toHaveValue("");
    expect(screen.getByTestId("ldap-settings-last-saved")).toHaveTextContent("Not configured yet");
    expect(screen.queryByText("A password is stored. Leave blank to keep it.")).toBeNull();
    expect(screen.getByLabelText("Port")).toHaveValue(636);
  });

  it("saves through the shared helper with the CSRF token and bindPassword \"\" when the field is left blank", async () => {
    const calls = mockApi();
    render(<DirectorySettingsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Save LDAP settings" }));
    expect(await screen.findByText("LDAP settings saved.")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.headers.get("X-CSRF-Token")).toBe("csrf-test-token");
    expect(put.body).toEqual({
      enabled: true, serverType: "ACTIVE_DIRECTORY", servers: ["dc1.example.local", "192.0.2.10"], port: 636,
      domainDn: savedView.domainDn, adminGroupDn: savedView.adminGroupDn, usersGroupDn: savedView.usersGroupDn,
      bindDn: savedView.bindDn, bindPassword: "", connectTimeoutSeconds: 10, ldaps: true, trustMode: "TRUST_PEM", trustPem: PEM,
    });
  });

  it("sends a typed password once and clears the field after the save", async () => {
    const calls = mockApi();
    render(<DirectorySettingsPanel />);
    const typed = ["synthetic", "bind", "value"].join("-");
    fireEvent.change(await screen.findByLabelText("LDAP bind password"), { target: { value: typed } });
    fireEvent.click(screen.getByRole("button", { name: "Save LDAP settings" }));
    await screen.findByText("LDAP settings saved.");
    expect(calls.find((c) => c.method === "PUT")!.body.bindPassword).toBe(typed);
    expect(screen.getByLabelText("LDAP bind password")).toHaveValue("");
  });

  it("puts each 400 problem under its field and form-level ones in the error alert", async () => {
    mockApi({ put: { status: 400, body: { error: "INVALID_SETTINGS", problems: [
      { field: "domainDn", message: "Domain DN is not a valid DN." },
      { field: "servers", message: "A server name must not contain a scheme." },
      { field: "bindPassword", message: "Enter the bind password again: the server changed." },
      { field: "", message: "The settings could not be stored." },
    ] } } });
    render(<DirectorySettingsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Save LDAP settings" }));
    const domain = await screen.findByLabelText("Domain DN (search base)");
    await waitFor(() => expect(domain).toHaveAttribute("aria-invalid", "true"));
    expect(document.getElementById(domain.getAttribute("aria-describedby")!)).toHaveTextContent("Domain DN is not a valid DN.");
    const password = screen.getByLabelText("LDAP bind password");
    expect(document.getElementById(password.getAttribute("aria-describedby")!)).toHaveTextContent("Enter the bind password again: the server changed.");
    expect(screen.getByTestId("ldap-servers-helper")).toHaveTextContent("A server name must not contain a scheme.");
    expect(screen.getByTestId("ldap-save-error")).toHaveTextContent("The settings could not be stored.");
    expect(screen.getByLabelText("Port")).toHaveAttribute("aria-invalid", "false");
    expect(screen.queryByText("LDAP settings saved.")).toBeNull();
    // Editing the field takes its message away.
    fireEvent.change(domain, { target: { value: "DC=corp,DC=example,DC=local" } });
    expect(screen.queryByText("Domain DN is not a valid DN.")).toBeNull();
  });

  it("shows the impersonation warning for trust-any and hides the PEM field", async () => {
    mockApi();
    render(<DirectorySettingsPanel />);
    fireEvent.click(await screen.findByLabelText("Trust any certificate"));
    expect(screen.getByText(/neXus will accept any certificate, including one from a machine impersonating the directory/)).toBeInTheDocument();
    expect(screen.getByText(/this risk was accepted by the Product Owner/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Certificate (PEM)")).toBeNull();
  });

  it("shows the clear-text warning when LDAPS is off and hides the trust choice", async () => {
    mockApi();
    render(<DirectorySettingsPanel />);
    fireEvent.click(await screen.findByLabelText("LDAPS"));
    expect(screen.getByText("Plain LDAP sends the service account password and every user's password over the network in clear text.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Trust any certificate")).toBeNull();
    // The bind target changed, so the stored password will be refused: the helper says so before the save.
    expect(screen.getByText(/enter the password again/)).toBeInTheDocument();
  });

  it("edits the server list: add up to five, remove, and send only filled rows", async () => {
    const calls = mockApi();
    render(<DirectorySettingsPanel />);
    const add = await screen.findByRole("button", { name: "Add server" });
    fireEvent.click(add);
    fireEvent.click(add);
    fireEvent.click(add);
    expect(add).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Server 3"), { target: { value: " 192.0.2.11 " } });
    fireEvent.click(screen.getByRole("button", { name: "Remove server 1" }));
    expect(screen.getByLabelText("Server 1")).toHaveValue("192.0.2.10");
    fireEvent.click(screen.getByRole("button", { name: "Save LDAP settings" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")!.body.servers).toEqual(["192.0.2.10", "192.0.2.11"]);
  });

  it("tests the unsaved form and renders the per-server outcome sentences and the checks", async () => {
    const calls = mockApi({ test: { body: {
      servers: [{ server: "dc1.example.local", outcome: "strong_auth_required" }, { server: "192.0.2.10", outcome: "ok" }],
      serviceBindOk: true, domainDnExists: true, adminGroupExists: true, usersGroupExists: false,
      userMatch: "found", inAdminGroup: false, inUsersGroup: null,
    } } });
    render(<DirectorySettingsPanel />);
    fireEvent.change(await screen.findByLabelText("Domain DN (search base)"), { target: { value: "DC=lab,DC=example,DC=local" } });
    fireEvent.change(screen.getByLabelText("Test user name (sAMAccountName)"), { target: { value: "jdoe" } });
    fireEvent.click(screen.getByRole("button", { name: "Test connection" }));
    const result = within(await screen.findByTestId("ldap-test-result"));
    const servers = result.getAllByTestId("ldap-test-server");
    expect(servers).toHaveLength(2);
    expect(servers[0]).toHaveTextContent("Server dc1.example.local");
    expect(servers[0]).toHaveTextContent("The server requires LDAP signing or TLS: turn LDAPS on.");
    expect(servers[1]).toHaveTextContent("Reached; the service account signed in.");
    const rows = result.getAllByRole("listitem").map((li) => li.textContent);
    expect(rows.slice(2)).toEqual([
      "Service bindOK", "Domain DNFound", "Administrators groupFound", "Users groupNot found", "Test userFound",
      "Member of administrators groupNo", "Member of users groupNot checked",
    ]);
    expect(screen.getByText(/The test never signs the user in and never shows directory attributes\./)).toBeInTheDocument();
    const post = calls.find((c) => c.url.endsWith("/ldap/test"))!;
    expect(post.method).toBe("POST");
    expect(post.headers.get("X-CSRF-Token")).toBe("csrf-test-token");
    expect(post.body.testUser).toBe("jdoe");
    expect(post.body.settings.domainDn).toBe("DC=lab,DC=example,DC=local");
    expect(post.body.settings.bindPassword).toBe("");
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
  });

  it("shows a test's 400 problems on the form instead of a result", async () => {
    mockApi({ test: { status: 400, body: { error: "INVALID_SETTINGS", problems: [{ field: "port", message: "Port must be 1 to 65535." }] } } });
    render(<DirectorySettingsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Test connection" }));
    expect(await screen.findByText("Port must be 1 to 65535.")).toBeInTheDocument();
    expect(screen.getByTestId("ldap-test-error")).toHaveTextContent("correct the marked fields");
    expect(screen.queryByTestId("ldap-test-result")).toBeNull();
  });

  it("shows the restricted panel on 403", async () => {
    mockApi({ get: { status: 403, body: { error: "ACTION_REFUSED" } } });
    render(<DirectorySettingsPanel />);
    expect(await screen.findByText("LDAP Settings")).toBeInTheDocument();
    expect(screen.getByText(/This needs the Security Admin role/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save LDAP settings" })).toBeNull();
  });
});
