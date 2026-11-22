import { describe, expect, it } from "vitest";
import type { SshHostKeyObservation, SshHostKeyView } from "../src/auth/adminApi";
import { buildSshTrustModel, SSH_TRUST_ROW_CAP } from "../src/estate/estateSshTrustModel";

// Synthetic observations only; key-shaped values are built at runtime, never written as literals.
const FP = ["SHA256", "x".repeat(43)].join(":");

function obs(id: string, status: SshHostKeyObservation["status"], lastSeen: string, seen = 1): SshHostKeyObservation {
  return {
    observation_id: id,
    device_id: "dev-tango-04",
    hostname: "FW-TANGO-04",
    key_algorithm: "ssh-ed25519",
    presented_fingerprint: FP,
    expected_fingerprint_prefix: "SHA256:xx",
    first_seen: "2026-11-01T00:00:00Z",
    last_seen: lastSeen,
    seen_count: seen,
    status,
    strict_refusal: false,
    ssh_identity_unverified: false,
    model: null,
    software_version: null,
    serial_number: null,
    management_source: "direct",
  };
}

const VIEW = (observations: SshHostKeyObservation[], mode: "WARN" | "STRICT" = "WARN"): SshHostKeyView => ({ mode, observations });

describe("buildSshTrustModel", () => {
  it("maps a failed read to UNKNOWN, never to a healthy state", () => {
    const m = buildSshTrustModel({ ok: false });
    expect(m.badge).toBe("unk");
    expect(m.badgeWord).toBe("UNKNOWN");
    expect(m.headline).toBe("SSH host-key status unavailable");
    expect(m.mode).toBeNull();
    expect(m.rows).toEqual([]);
    expect(m.badgeWord).not.toBe("Fine");
  });

  it("maps zero observations to UNKNOWN, not Fine", () => {
    const m = buildSshTrustModel({ ok: true, view: VIEW([]) });
    expect(m.badge).toBe("unk");
    expect(m.headline).toBe("No SSH host-key observation for this unit");
    expect(m.counts.observations).toBe(0);
    expect(m.mode).toBe("WARN");
  });

  it("sorts non-accepted rows first: OPEN, then REJECTED, then ACCEPTED", () => {
    const m = buildSshTrustModel({
      ok: true,
      view: VIEW([
        obs("a", "ACCEPTED", "2026-11-20T00:00:00Z"),
        obs("b", "REJECTED", "2026-11-10T00:00:00Z"),
        obs("c", "OPEN", "2026-11-05T00:00:00Z"),
        obs("d", "OPEN", "2026-11-21T00:00:00Z"),
      ]),
    });
    expect(m.rows.map((r) => r.observationId)).toEqual(["d", "c", "b", "a"]);
    expect(m.rows.slice(0, 3).every((r) => r.word !== "ACCEPTED")).toBe(true);
  });

  it("counts match the observations and the headline follows the worst state", () => {
    const m = buildSshTrustModel({
      ok: true,
      view: VIEW([
        obs("a", "ACCEPTED", "2026-11-20T00:00:00Z"),
        obs("b", "ACCEPTED", "2026-11-20T00:00:00Z"),
        obs("c", "OPEN", "2026-11-05T00:00:00Z"),
      ], "STRICT"),
    });
    expect(m.mode).toBe("STRICT");
    expect(m.counts).toEqual({ observations: 3, open: 1, rejected: 0, accepted: 2, unrecognised: 0 });
    expect(m.badge).toBe("watch");
    expect(m.headline).toBe("1 open observation, not yet accepted");

    const rejected = buildSshTrustModel({ ok: true, view: VIEW([obs("r", "REJECTED", "2026-11-01T00:00:00Z")]) });
    expect(rejected.badge).toBe("act");
    expect(rejected.counts.rejected).toBe(1);
  });

  it("an all-accepted unit reads ACCEPTED, and an unrecognised status reads UNKNOWN", () => {
    const ok = buildSshTrustModel({ ok: true, view: VIEW([obs("a", "ACCEPTED", "2026-11-20T00:00:00Z")]) });
    expect(ok.badge).toBe("ok");
    expect(ok.headline).toBe("All observations accepted");

    const odd = buildSshTrustModel({
      ok: true,
      view: VIEW([obs("a", "ACCEPTED", "2026-11-20T00:00:00Z"), { ...obs("z", "OPEN", "2026-11-20T00:00:00Z"), status: "PENDING" as never }]),
    });
    expect(odd.badge).toBe("unk");
    expect(odd.counts.unrecognised).toBe(1);
  });

  it("caps the rows and reports the remainder", () => {
    const many = Array.from({ length: SSH_TRUST_ROW_CAP + 3 }, (_, i) =>
      obs(`o${i}`, "ACCEPTED", `2026-11-${String(10 + i).padStart(2, "0")}T00:00:00Z`),
    );
    const m = buildSshTrustModel({ ok: true, view: VIEW(many) });
    expect(m.rows).toHaveLength(SSH_TRUST_ROW_CAP);
    expect(m.hiddenCount).toBe(3);
    expect(m.counts.observations).toBe(SSH_TRUST_ROW_CAP + 3);
  });

  it("a missing seen_count is null, never 0", () => {
    const { seen_count: _omitted, ...rest } = obs("a", "OPEN", "2026-11-20T00:00:00Z");
    const m = buildSshTrustModel({ ok: true, view: VIEW([rest as SshHostKeyObservation]) });
    expect(m.rows[0].seenCount).toBeNull();
    expect(m.rows[0].seenCount).not.toBe(0);
  });

  it("a view without an observations array is a failed read: UNKNOWN, no throw", () => {
    let m: ReturnType<typeof buildSshTrustModel> | undefined;
    expect(() => {
      m = buildSshTrustModel({ ok: true, view: { mode: "WARN" } as unknown as SshHostKeyView });
    }).not.toThrow();
    expect(m?.badge).toBe("unk");
    expect(m?.badgeWord).toBe("UNKNOWN");
    expect(m?.mode).toBeNull();
    expect(m?.rows).toEqual([]);
  });

  it("never carries the fingerprint or addresses into the model", () => {
    const m = buildSshTrustModel({ ok: true, view: VIEW([obs("a", "OPEN", "2026-11-20T00:00:00Z")]) });
    expect(JSON.stringify(m)).not.toContain(FP);
    expect(JSON.stringify(m)).not.toContain("FW-TANGO-04");
  });
});
