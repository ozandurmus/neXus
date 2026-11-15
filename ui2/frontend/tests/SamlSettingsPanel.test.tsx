import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { SamlSettingsPanel } from "../src/components/settings/SamlSettingsPanel";

const view = (over: Record<string, unknown> = {}) => ({
  configured: true, enabled: false, spBaseUrl: "https://nexus.example.test", spEntityId: "https://nexus.example.test/saml2/service-provider-metadata/adfs",
  acsUrl: "https://nexus.example.test/login/saml2/sso/adfs", metadataUrl: "https://nexus.example.test/saml2/service-provider-metadata/adfs",
  loginUrl: "https://nexus.example.test/saml2/authenticate/adfs", idpEntityId: "https://adfs.example.test/adfs/services/trust",
  idpSsoUrl: "https://adfs.example.test/adfs/ls/", idpSsoBinding: "REDIRECT", idpSigningCertPem: "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
  groupClaimAttribute: "http://schemas.xmlsoap.org/claims/Group", adminGroupValue: "nexus-admins", usersGroupValue: "nexus-users",
  clockSkewSeconds: 60, signAuthnRequests: false, spKey: { source: "NONE", certPem: "", privateKeySet: false },
  updatedAt: "2026-11-16T09:00:00Z", adminGroupRoles: ["role:security_admin"], usersGroupRoles: ["role:viewer"], ...over,
});

interface Call { readonly url: string; readonly method: string; readonly body: any }
type Reply = { status?: number; body: unknown };

function mockApi(replies: { get?: Reply; put?: Reply }): Call[] {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const reply = (r: Reply) => new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: { "Content-Type": "application/json" } });
    calls.push({ url, method, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
    if (url.includes("/session/status")) return reply({ body: { csrf_token: "csrf-test-token" } });
    if (url.endsWith("/api/v2/config/saml") && method === "PUT") return reply(replies.put ?? { body: view({ enabled: true }) });
    if (url.endsWith("/api/v2/config/saml")) return reply(replies.get ?? { body: view() });
    return reply({ status: 404, body: {} });
  });
  return calls;
}

describe("SamlSettingsPanel", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows the saved settings and the addresses ADFS needs", async () => {
    mockApi({});
    render(<SamlSettingsPanel />);
    expect(await screen.findByLabelText("Administrators group value")).toHaveValue("nexus-admins");
    expect(screen.getByLabelText("Group claim name")).toHaveValue("http://schemas.xmlsoap.org/claims/Group");
    expect(screen.getByTestId("saml-acs-url")).toHaveTextContent("https://nexus.example.test/login/saml2/sso/adfs");
    expect(screen.getByTestId("saml-metadata-url")).toHaveTextContent("/saml2/service-provider-metadata/adfs");
    expect(screen.getByTestId("saml-key-source")).toHaveTextContent("No service provider key is configured");
    expect(screen.getByLabelText("Enable SAML sign-in")).not.toBeChecked();
  });

  it("saves the form with the CSRF-protected PUT and never sends the pasted key back after a save", async () => {
    const calls = mockApi({});
    render(<SamlSettingsPanel />);
    fireEvent.click(await screen.findByLabelText("Enable SAML sign-in"));
    fireEvent.change(screen.getByLabelText("Paste the ADFS federation metadata (XML)"), { target: { value: "<EntityDescriptor/>" } });
    fireEvent.click(screen.getByRole("button", { name: "Save SAML settings" }));
    expect(await screen.findByText("SAML settings saved.")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT");
    expect(put?.body.enabled).toBe(true);
    expect(put?.body.idpMetadataXml).toBe("<EntityDescriptor/>");
    expect(put?.body.adminGroupValue).toBe("nexus-admins");
    expect(screen.getByLabelText("Paste the ADFS federation metadata (XML)")).toHaveValue("");
  });

  it("puts the server's message under the field it names", async () => {
    mockApi({ put: { status: 400, body: { error: "INVALID_SETTINGS", problems: [
      { field: "adminGroupValue", message: "Enter the administrator group value (1 to 512 characters)." },
      { field: "", message: "A form level problem." }] } } });
    render(<SamlSettingsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Save SAML settings" }));
    expect(await screen.findByText("Enter the administrator group value (1 to 512 characters).")).toBeInTheDocument();
    expect(screen.getByTestId("saml-save-error")).toHaveTextContent("A form level problem.");
  });

  it("says where a file-supplied key comes from and hides the key fields", async () => {
    mockApi({ get: { body: view({ spKey: { source: "FILE", certPem: "x", privateKeySet: true } }) } });
    render(<SamlSettingsPanel />);
    expect(await screen.findByTestId("saml-key-source")).toHaveTextContent("UI2_SAML_SP_PRIVATE_KEY_PATH");
    expect(screen.queryByLabelText("Service provider private key (PKCS#8 PEM)")).toBeNull();
  });

  it("shows a restricted panel for a role without access", async () => {
    mockApi({ get: { status: 403, body: { error: "ACTION_REFUSED" } } });
    render(<SamlSettingsPanel />);
    expect(await screen.findByText(/Security Admin/)).toBeInTheDocument();
  });
});
