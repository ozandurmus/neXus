import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getHttpsCertificate, type HttpsCertificateView } from "../src/auth/adminApi";
import {
  CERTIFICATE_NO_RECORD_TEXT,
  CERTIFICATE_RESTRICTED_TEXT,
  CERTIFICATE_UNKNOWN_TEXT,
  EstateDossierCertificateBlock,
} from "../src/estate/EstateDossierCertificateBlock";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getHttpsCertificate: vi.fn() };
});

const mockedRead = vi.mocked(getHttpsCertificate);

// Synthetic identifiers only (FW-TANGO-04 style names). The fingerprint is built at runtime, never a literal.
const DEVICE = "dev-tango-04";
const FINGERPRINT = Array.from({ length: 32 }, () => "AB").join("");

function activeView(overrides: Partial<HttpsCertificateView> = {}): HttpsCertificateView {
  const far = new Date(Date.now() + 400 * 86_400_000).toISOString();
  return {
    available: true,
    strict: false,
    certificate_changed: false,
    can_accept: false,
    can_set_strict: true,
    certificates: [
      {
        trust_entry_id: "trust-synthetic-1",
        status: "ACTIVE",
        fingerprint_sha256: FINGERPRINT,
        subject_cn: "FW-TANGO-04",
        issuer_cn: "Synthetic CA",
        not_after: far,
      },
    ],
    ...overrides,
  };
}

describe("EstateDossierCertificateBlock", () => {
  beforeEach(() => {
    mockedRead.mockReset();
  });

  it("renders the active state word and no action buttons", async () => {
    mockedRead.mockResolvedValue(activeView());

    render(<EstateDossierCertificateBlock deviceId={DEVICE} />);

    expect(await screen.findByTestId("dossier-certificate-state")).toBeTruthy();
    expect(mockedRead).toHaveBeenCalledWith(DEVICE);
    expect(screen.getByText(/Certificate expires in \d+ d/)).toBeTruthy();
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(screen.getByRole("link", { name: "Open in standard view" }).getAttribute("href")).toBe(
      `/devices/${DEVICE}?standard=1`,
    );
  });

  it("shows the UNKNOWN text and never an OK or Healthy word when the read rejects", async () => {
    mockedRead.mockRejectedValue(new Error("synthetic failure"));

    const { container } = render(<EstateDossierCertificateBlock deviceId={DEVICE} />);

    expect(await screen.findByText(CERTIFICATE_UNKNOWN_TEXT)).toBeTruthy();
    expect(screen.queryByTestId("dossier-certificate")).toBeNull();
    const text = container.textContent ?? "";
    expect(text).not.toMatch(/\bOK\b/);
    expect(text).not.toMatch(/Healthy/i);
  });

  it("renders the changed wording when certificate_changed is true", async () => {
    mockedRead.mockResolvedValue(activeView({ certificate_changed: true, strict: true }));

    render(<EstateDossierCertificateBlock deviceId={DEVICE} />);

    expect(await screen.findByTestId("dossier-certificate-changed")).toBeTruthy();
    expect(screen.getByText("Certificate changed since trust was recorded.")).toBeTruthy();
    expect(screen.getByText("Strict mode (refuse certificate changes): On")).toBeTruthy();
  });

  it("renders the restricted text, not the UNKNOWN text, for a 403", async () => {
    mockedRead.mockRejectedValue({ status: 403, message: "synthetic refusal" });

    render(<EstateDossierCertificateBlock deviceId={DEVICE} />);

    expect(await screen.findByText(CERTIFICATE_RESTRICTED_TEXT)).toBeTruthy();
    expect(screen.queryByText(CERTIFICATE_UNKNOWN_TEXT)).toBeNull();
  });

  it("renders nothing and makes no call without a device id", async () => {
    const { container } = render(<EstateDossierCertificateBlock deviceId={null} />);

    await waitFor(() => expect(container.textContent).toBe(""));
    expect(container.innerHTML).toBe("");
    expect(mockedRead).not.toHaveBeenCalled();
  });

  it("says no record in neutral wording for a device with available false", async () => {
    mockedRead.mockResolvedValue({ available: false });

    const { container } = render(<EstateDossierCertificateBlock deviceId={DEVICE} />);

    expect(await screen.findByText(CERTIFICATE_NO_RECORD_TEXT)).toBeTruthy();
    expect(container.textContent ?? "").not.toMatch(/\bOK\b|Healthy/i);
  });
});
