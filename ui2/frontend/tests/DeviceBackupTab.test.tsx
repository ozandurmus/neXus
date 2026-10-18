import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext } from "../src/auth/SessionContext";
import { ASA_BACKUP_LABEL, BACKUP_ATTEMPTS_SHOWN, BackupPanel } from "../src/screens/InventoryPanels";
import { attemptWords } from "../src/screens/backupWords";
import { bytesTitle, formatBytes } from "../src/shell/bytes";
import type { JobEventView } from "../src/auth/adminApi";

/** Device Backup tab (UI review 2026-10-18, F-16 / P1-2B): archives first, five attempts then "Show all", MB, one status word. */
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const themed = (node: React.ReactElement, permissions = ["Operations", "Admin"]) => (
  <ThemeProvider theme={m3Theme}>
    <SessionContext.Provider value={{ displayName: "x", roleTokens: [], permissions, onSignOut: () => {} }}>{node}</SessionContext.Provider>
  </ThemeProvider>
);
afterEach(() => vi.unstubAllGlobals());

const archive = (i: number, over: Record<string, unknown> = {}) => ({
  artefact_id: `a${i}`, device_id: "dev-1", collected_at: `2026-10-1${i}T02:30:00Z`, size_bytes: 169993690 + i, digest_prefix: `digest${i}`,
  validation_level: "V1", deviation_state: "changed", ...over,
});
const attempt = (i: number, state = "COMPLETED", reason?: string): JobEventView => ({
  job_id: `job-${i}`, job_type: "backup", target_device_id: "dev-1", submitted_at: `2026-10-18T02:${String(i).padStart(2, "0")}:00Z`, state, has_transcript: false, terminal_reason: reason,
});

function stub(attempts: JobEventView[], archives: object[] = [archive(1), archive(2)]) {
  const jobCalls: string[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url.startsWith("/api/v2/jobs")) {
      jobCalls.push(url);
      const size = Number(new URL(url, "http://x").searchParams.get("page_size"));
      return json({ items: attempts.slice(0, size), total: attempts.length, page: 1, page_size: size });
    }
    if (url === "/devices/dev-1/backups") return json({ backups: archives });
    if (url.startsWith("/api/v2/backups/policies")) return json({ schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 7, snapshot_retention_depth: 1 });
    return json({}, 404);
  }));
  return jobCalls;
}

describe("stored archives come first, under their own title", () => {
  it("titles the archives with their count and the retention, and puts them above the attempts", async () => {
    stub([attempt(1)]);
    render(themed(<BackupPanel deviceId="dev-1" />));
    const archives = await screen.findByText("Stored archives (2) · retention 7 d");
    const attempts = screen.getByText("Backup attempts");
    // eslint-disable-next-line no-bitwise
    expect(archives.compareDocumentPosition(attempts) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole("table", { name: "Stored archives" })).toBeInTheDocument();
  });

  it("omits the retention rather than guess it when the policy cannot be read", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/dev-1/backups" ? json({ backups: [archive(1)] }) : json({}, 500)));
    render(themed(<BackupPanel deviceId="dev-1" />));
    expect(await screen.findByText("Stored archives (1)")).toBeInTheDocument();
  });

  it("shows sizes in MB with the exact bytes on hover, and Checked (V1)", async () => {
    stub([attempt(1)]);
    render(themed(<BackupPanel deviceId="dev-1" />));
    const sizes = await screen.findAllByText("162.1 MB");
    expect(sizes).toHaveLength(2);
    expect(sizes.map((size) => size.closest("td")!.getAttribute("title"))).toEqual(["169,993,691 bytes", "169,993,692 bytes"]);
    expect(screen.getAllByText("Checked (V1)")).toHaveLength(2);
    expect(screen.queryByText("V1")).toBeNull();
    expect(screen.queryByText("169993691")).toBeNull();
    expect(formatBytes(169993690)).toBe("162.1 MB");
    expect(bytesTitle(169993690)).toBe("169,993,690 bytes");
  });

  it("labels the Cisco ASA backup as configuration text and no other vendor", async () => {
    stub([attempt(1)]);
    const { unmount } = render(themed(<BackupPanel deviceId="dev-1" vendorHint="cisco_asa" />));
    expect(await screen.findByText(ASA_BACKUP_LABEL)).toBeInTheDocument();
    expect(ASA_BACKUP_LABEL).toBe("Configuration text (running + startup) — certificates, VPN images and profiles not included · secret-bearing");
    unmount();
    render(themed(<BackupPanel deviceId="dev-1" vendorHint="check_point" />));
    await screen.findByText("Stored archives (2) · retention 7 d");
    expect(screen.queryByText(ASA_BACKUP_LABEL)).toBeNull();
  });
});

describe("attempts are collapsed to the newest five", () => {
  const many = Array.from({ length: 25 }, (_, i) => attempt(i + 1));

  it("shows five, then every attempt (paged at 25) after Show all, and can collapse again", async () => {
    const calls = stub(many);
    render(themed(<BackupPanel deviceId="dev-1" />));
    expect(BACKUP_ATTEMPTS_SHOWN).toBe(5);
    await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(5));
    expect(calls.some((url) => url.includes("page_size=5"))).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Show all 25 attempts" }));
    await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(25));
    fireEvent.click(screen.getByRole("button", { name: "Show only the latest 5" }));
    await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(5));
  });

  it("offers no Show all when everything already fits", async () => {
    stub([attempt(1), attempt(2)]);
    render(themed(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(2));
    expect(screen.queryByRole("button", { name: /Show all/ })).toBeNull();
  });
});

describe("one status word per attempt", () => {
  it("never doubles the state (COMPLETED: COMPLETED) or leaves a dangling colon (CANCELLED: cancelled:)", () => {
    expect(attemptWords("COMPLETED", "COMPLETED")).toMatchObject({ word: "Completed", inline: null, detail: null });
    expect(attemptWords("CANCELLED", "cancelled:")).toMatchObject({ word: "Cancelled", inline: null, detail: null });
    expect(attemptWords("COMPLETED", null)).toMatchObject({ word: "Completed", detail: null });
    // a success note stays behind the disclosure
    expect(attemptWords("COMPLETED", "completed in 8038ms")).toMatchObject({ word: "Completed", inline: null, detail: "in 8038ms" });
  });

  it("shortens host_key_mismatch and partial lines to a word with the detail kept", () => {
    const hostKey = attemptWords("FAILED", "connect_failed: host_key_mismatch expected fingerprint-detail-synthetic");
    expect(hostKey).toMatchObject({ word: "Host key mismatch", tone: "bad", inline: null });
    expect(hostKey.detail).toContain("host_key_mismatch");
    const partial = attemptWords("FAILED", "partial: stored without running-config.set (show config running: TimedOut)");
    expect(partial).toMatchObject({ word: "Partial", tone: "warn", inline: null });
    expect(partial.detail).toBe("stored without running-config.set (show config running: TimedOut)");
  });

  it("keeps a short generic failure beside the word and moves a long one behind the disclosure", () => {
    expect(attemptWords("FAILED", "Synthetic collection failure")).toMatchObject({ word: "Failed", inline: "Synthetic collection failure", detail: null });
    const long = "x".repeat(90);
    expect(attemptWords("FAILED", long)).toMatchObject({ word: "Failed", inline: null, detail: long });
  });

  it("renders the word, hides the machine line in a disclosure and keeps every attempt", async () => {
    stub([attempt(1, "FAILED", "connect_failed: host_key_mismatch fingerprint-detail"), attempt(2, "COMPLETED", "COMPLETED"), attempt(3, "CANCELLED", "cancelled:")]);
    render(themed(<BackupPanel deviceId="dev-1" />));
    expect(await screen.findByText("Host key mismatch")).toBeInTheDocument();
    expect(screen.getByText("Completed")).toBeInTheDocument();
    expect(screen.getByText("Cancelled")).toBeInTheDocument();
    expect(screen.queryByText(/COMPLETED: COMPLETED|CANCELLED: cancelled:/)).toBeNull();
    const detail = screen.getByText("connect_failed: host_key_mismatch fingerprint-detail");
    expect(detail.closest("details")).not.toHaveAttribute("open");
    expect(within(detail.closest("details")!).getByText("Details")).toBeInTheDocument();
  });
});
