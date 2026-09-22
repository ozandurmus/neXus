import { afterEach, describe, expect, it, vi } from "vitest";

import { downloadBackupArtefact } from "../src/auth/adminApi";
import { formatBytes } from "../src/screens/BackupScreen";

/**
 * PO decision record 2026-09-22: the browser downloads the archive through
 * POST /backups/{id}/download -- CSRF token, reason in the body, the file name
 * taken from the server's Content-Disposition, never JSON-parsed.
 */
describe("downloadBackupArtefact", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function stubFetch(download: Response) {
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        calls.push({ url, init });
        if (url === "/session/status") {
          return new Response(JSON.stringify({ csrf_token: "csrf-1" }), { status: 200 });
        }
        return download;
      }),
    );
    return calls;
  }

  it("posts the reason with a CSRF token and returns the archive under the server's file name", async () => {
    const calls = stubFetch(
      new Response(new Uint8Array([0x1f, 0x8b, 0x08]), {
        status: 200,
        headers: {
          "Content-Type": "application/gzip",
          "Content-Disposition": 'attachment; filename="nexus-backup-check_point-3ed07327-20260922-1116.tgz"',
        },
      }),
    );

    const result = await downloadBackupArtefact("3ed07327-0000-0000-0000-000000000000", "DR drill SEC-4091");

    const download = calls.find((c) => c.url.endsWith("/download"));
    expect(download?.url).toBe("/backups/3ed07327-0000-0000-0000-000000000000/download");
    expect(download?.init?.method).toBe("POST");
    expect((download?.init?.headers as Record<string, string>)["X-CSRF-Token"]).toBe("csrf-1");
    expect(JSON.parse(String(download?.init?.body))).toEqual({ reason: "DR drill SEC-4091" });
    expect(result.fileName).toBe("nexus-backup-check_point-3ed07327-20260922-1116.tgz");
    expect(result.blob.size).toBe(3);
  });

  it("surfaces a refusal with its status and code instead of a blob", async () => {
    stubFetch(new Response(JSON.stringify({ error: "AUTHORIZATION_REFUSED", code: "ROLE_REQUIRED" }), { status: 403 }));

    await expect(downloadBackupArtefact("3ed07327-0000-0000-0000-000000000000", "DR drill SEC-4091")).rejects.toMatchObject({
      status: 403,
      body: { code: "ROLE_REQUIRED" },
    });
  });
});

describe("formatBytes", () => {
  it("reads a Gaia archive in MB and an empty store honestly", () => {
    expect(formatBytes(150366406)).toBe("143.4 MB");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(-1)).toBe("unknown size");
  });
});
