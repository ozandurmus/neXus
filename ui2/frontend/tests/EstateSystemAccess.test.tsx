import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getScheduledJobs,
  getSystemHealth,
  listAuditEvents,
  listCredentials,
  listDevices,
  listLocalIdentities,
  listSessions,
} from "../src/auth/adminApi";
import { EstateSystemScreen } from "../src/estate/EstateSystemScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getScheduledJobs: vi.fn(),
    getSystemHealth: vi.fn(),
    listAuditEvents: vi.fn(),
    listCredentials: vi.fn(),
    listDevices: vi.fn(),
    listLocalIdentities: vi.fn(),
    listSessions: vi.fn(),
  };
});

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses). The names must never reach the DOM.
const IDENTITY_NAME = "FW-TANGO-04-admin";
const SESSION_ID = "SES-SYNTH-0001";
const AT = "2026-11-22T08:00:00.000Z";

const identity = (name: string, enabled: boolean, mustChange: boolean) => ({
  local_identity_id: `id-${name}`,
  local_identity_name: name,
  enabled,
  must_change_password: mustChange,
  created_at: AT,
  password_set_at: AT,
});

const session = (id: string) => ({
  session_id: id,
  actor_fingerprint: "fp-synthetic",
  created_at: AT,
  last_seen_at: AT,
  idle_deadline_at: AT,
  absolute_expires_at: AT,
  state: "ACTIVE",
  end_reason: null,
  ended_by_actor_fingerprint: null,
  superseded_by_session_id: null,
});

/** A 403 as the API client raises it (the shape isRestricted reads). */
const forbidden = () => Object.assign(new Error("forbidden"), { status: 403, body: {} });

function mockOtherReads() {
  vi.mocked(getScheduledJobs).mockResolvedValue({
    master_enabled: true, master_env: "SYNTHETIC_MASTER", can_write: false, masked: true, business_hours: null, families: [],
  } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [] } as never);
  vi.mocked(getSystemHealth).mockRejectedValue(new Error("synthetic"));
  vi.mocked(listAuditEvents).mockResolvedValue({ events: [] } as never);
  vi.mocked(listCredentials).mockResolvedValue({ credentials: [] } as never);
}

describe("Estate System screen: Access section", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockOtherReads();
  });

  it("renders the identity and session counts, and no identity name or session id", async () => {
    vi.mocked(listLocalIdentities).mockResolvedValue({
      identities: [identity(IDENTITY_NAME, true, false), identity("FW-TANGO-04-ops", false, true)],
    });
    vi.mocked(listSessions).mockResolvedValue({
      sessions: [session(SESSION_ID)], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
    });

    const { container } = render(<EstateSystemScreen />);

    expect(await screen.findByText("2 local identities")).toBeTruthy();
    expect(screen.getByText("Local identities disabled")).toBeTruthy();
    expect(screen.getByText("1 session")).toBeTruthy();
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
    const text = container.textContent ?? "";
    expect(text).not.toContain(IDENTITY_NAME);
    expect(text).not.toContain("FW-TANGO-04-ops");
    expect(text).not.toContain(SESSION_ID);
    const link = screen.getByRole("link", { name: /Open access and identity \(standard\)/ });
    expect(link.getAttribute("href")).toBe("?screen=administration&tab=access&standard=1");
  });

  it("renders credential counts by kind and no username or display name", async () => {
    const CRED_USER = "FW-TANGO-04-svc-user";
    const CRED_NAME = "FW-TANGO-04 display label";
    vi.mocked(listLocalIdentities).mockResolvedValue({ identities: [] });
    vi.mocked(listSessions).mockResolvedValue({
      sessions: [], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
    });
    vi.mocked(listCredentials).mockResolvedValue({
      credentials: [
        { credential_id: "c1", credential_reference_id: "r1", display_name: CRED_NAME, kind: "ssh_password", username: CRED_USER, auth_secret: "set", allows_check_point: true, allows_palo_alto: false, created_at: AT } as never,
        { credential_id: "c2", credential_reference_id: "r2", display_name: CRED_NAME, kind: "snmp_v3", username: CRED_USER, auth_secret: "not set", priv_secret: "set", allows_check_point: false, allows_palo_alto: true, created_at: AT } as never,
      ],
    });

    const { container } = render(<EstateSystemScreen />);

    expect(await screen.findByText("Credentials (total as read)")).toBeTruthy();
    // The total and the with-secret count are both 2 in this fixture.
    expect(screen.getAllByText("2 credentials")).toHaveLength(2);
    expect(screen.getByText("Credentials with a secret set")).toBeTruthy();
    expect(screen.getByText("Credentials of kind ssh_password")).toBeTruthy();
    expect(screen.getByText("Credential list read at")).toBeTruthy();
    const text = container.textContent ?? "";
    expect(text).not.toContain(CRED_USER);
    expect(text).not.toContain(CRED_NAME);
    const link = screen.getByRole("link", { name: /Open credentials \(standard\)/ });
    expect(link.getAttribute("href")).toBe("?screen=administration&tab=registry&section=credentials&standard=1");
  });

  it("renders the credential refusal line, not 0, when the credential list is refused with a 403", async () => {
    vi.mocked(listLocalIdentities).mockResolvedValue({ identities: [] });
    vi.mocked(listSessions).mockResolvedValue({
      sessions: [], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
    });
    vi.mocked(listCredentials).mockRejectedValue(forbidden());

    render(<EstateSystemScreen />);

    expect(await screen.findByText("Your role cannot read credentials.")).toBeTruthy();
    expect(screen.queryByText("0 credentials")).toBeNull();
  });

  it("renders UNKNOWN for credentials when the credential read fails, never OK", async () => {
    vi.mocked(listLocalIdentities).mockResolvedValue({ identities: [] });
    vi.mocked(listSessions).mockResolvedValue({
      sessions: [], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
    });
    vi.mocked(listCredentials).mockRejectedValue(new Error("synthetic"));

    render(<EstateSystemScreen />);

    expect(await screen.findByText("UNKNOWN: could not be read.")).toBeTruthy();
    expect(screen.queryByText("0 credentials")).toBeNull();
  });

  it("renders the restriction text, not 0, when identities are refused with a 403", async () => {
    vi.mocked(listLocalIdentities).mockRejectedValue(forbidden());
    vi.mocked(listSessions).mockResolvedValue({
      sessions: [], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
    });

    render(<EstateSystemScreen />);

    expect(await screen.findByText("Your role cannot read local identities.")).toBeTruthy();
    expect(screen.queryByText("0 local identities")).toBeNull();
    expect(screen.getByText("0 sessions")).toBeTruthy();
    await waitFor(() => expect(listSessions).toHaveBeenCalled());
  });
});
