import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { HttpsCertificatePanel } from "../src/screens/HttpsCertificatePanel";
import * as api from "../src/auth/adminApi";
vi.mock("../src/auth/adminApi", () => ({ getHttpsCertificate: vi.fn(), acceptHttpsCertificate: vi.fn(), setHttpsCertificateStrict: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });
const certificate = { trust_entry_id: "pending-1", status: "PENDING" as const, fingerprint_sha256: "a".repeat(64),
  subject_cn: "FW-TANGO-04", issuer_cn: "FW-JULIET-06", not_after: "2030-01-01T00:00:00Z" };
it("shows the warning and masked certificate and accepts the exact observed entry", async () => {
  vi.mocked(api.getHttpsCertificate).mockResolvedValue({ available: true, certificate_changed: true, strict: false,
    can_accept: true, can_set_strict: true, certificates: [certificate] });
  vi.mocked(api.acceptHttpsCertificate).mockResolvedValue({});
  vi.mocked(api.setHttpsCertificateStrict).mockResolvedValue({});
  render(<HttpsCertificatePanel deviceId="device-1" />);
  expect(await screen.findByText(/Certificate changed/)).toBeInTheDocument();
  expect(screen.getByText("Subject CN: FW-TANGO-04")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Accept new certificate" }));
  await waitFor(() => expect(api.acceptHttpsCertificate).toHaveBeenCalledWith("device-1", "pending-1"));
  await waitFor(() => expect(api.getHttpsCertificate).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByRole("checkbox", { name: "Refuse certificate changes (strict mode)" }));
  await waitFor(() => expect(api.setHttpsCertificateStrict).toHaveBeenCalledWith("device-1", true));
});
it("uses server affordances to hide certificate changes from a read-only viewer", async () => {
  vi.mocked(api.getHttpsCertificate).mockResolvedValue({ available: true, certificate_changed: true, can_accept: false,
    can_set_strict: false, certificates: [certificate] });
  render(<HttpsCertificatePanel deviceId="device-1" />);
  await screen.findByText(/Certificate changed/);
  expect(screen.queryByRole("button", { name: "Accept new certificate" })).not.toBeInTheDocument();
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
});
it("hides the panel when the server excludes the endpoint, including Palo Alto", async () => {
  vi.mocked(api.getHttpsCertificate).mockResolvedValue({ available: false });
  const view = render(<HttpsCertificatePanel deviceId="device-pan" />);
  await waitFor(() => expect(api.getHttpsCertificate).toHaveBeenCalled());
  expect(view.container).toBeEmptyDOMElement();
});
