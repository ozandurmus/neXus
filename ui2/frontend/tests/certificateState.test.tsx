import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CertificateStatePill, certificateState } from "../src/screens/HttpsCertificatePanel";

const NOW = new Date("2026-10-18T09:00:00Z");
const view = (notAfter: string, status: "ACTIVE" | "PENDING" = "ACTIVE") => ({
  available: true,
  certificates: [{ trust_entry_id: "t-1", status, fingerprint_sha256: "0".repeat(64), subject_cn: "FW-TANGO-04",
    issuer_cn: "FW-TANGO-04", not_after: notAfter }],
});

/** P0-1B (UI review 2026-10-18): the pinned certificate's state, a word with every colour. */
describe("certificate state pill", () => {
  it("is critical once the pinned certificate has expired", () => {
    expect(certificateState(view("2026-10-11T00:00:00Z"), NOW)).toEqual({ tone: "bad", label: "Certificate expired 7 d ago" });
    expect(certificateState(view("2026-10-18T08:00:00Z"), NOW)).toEqual({ tone: "bad", label: "Certificate expired today" });
  });
  it("warns under 30 days and is neutral from 30 days on", () => {
    expect(certificateState(view("2026-11-01T09:00:00Z"), NOW)).toEqual({ tone: "warn", label: "Certificate expires in 14 d" });
    expect(certificateState(view("2026-11-16T12:00:00Z"), NOW)).toEqual({ tone: "warn", label: "Certificate expires in 29 d" });
    expect(certificateState(view("2026-11-17T09:00:00Z"), NOW)).toEqual({ tone: "neutral", label: "Certificate expires in 30 d" });
    expect(certificateState(view("2027-05-30T09:00:00Z"), NOW)).toEqual({ tone: "neutral", label: "Certificate expires in 224 d" });
    expect(certificateState(view("2026-10-18T20:00:00Z"), NOW)).toEqual({ tone: "warn", label: "Certificate expires today" });
  });
  it("says no certificate observed when an HTTPS device has no pinned certificate", () => {
    expect(certificateState({ available: true, certificates: [] }, NOW)).toEqual({ tone: "neutral", label: "No certificate observed" });
    expect(certificateState(view("2030-01-01T00:00:00Z", "PENDING"), NOW)).toEqual({ tone: "neutral", label: "No certificate observed" });
  });
  it("shows nothing for a device without an HTTPS endpoint, and UNKNOWN for an unreadable date", () => {
    expect(certificateState({ available: false }, NOW)).toBeNull();
    expect(certificateState(null, NOW)).toBeNull();
    expect(certificateState(view("not a date"), NOW)?.label).toBe("Certificate expiry UNKNOWN");
  });
  it("renders the label as text, never colour alone", () => {
    render(<CertificateStatePill view={view("2026-10-11T00:00:00Z")} now={NOW} />);
    expect(screen.getByText("Certificate expired 7 d ago")).toBeInTheDocument();
  });
});
