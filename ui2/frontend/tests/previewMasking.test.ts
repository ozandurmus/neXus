import { expect, it } from "vitest";
import {
  ALIGNMENT, INVENTORY, CONFIG_DEVICES, CONFIG_SETTINGS, COMPLIANCE_FINDINGS,
  OPERATIONS_JOBS, OPERATIONS_READINESS, ADMIN_DEVICES,
} from "../src/preview/previewData";

/**
 * Deterministic pseudonym dictionary from TopologyNamePseudonymizer (26 NATO + 6 constellations).
 * Every fixture device, cluster, virtual system, manager, or domain name in frontend tests and
 * previews must use this masked vocabulary and RFC 5737 documentation IPv4 ranges.
 */
const PSEUDONYM_WORDS = [
  "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL",
  "INDIA", "JULIET", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA",
  "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY",
  "YANKEE", "ZULU", "APOLLO", "ATLAS", "BEACON", "COMET", "ORION", "VEGA",
] as const;

const WORD_GROUP = PSEUDONYM_WORDS.join("|");

export const MASKED_ENTITY_NAME_PATTERN = new RegExp(
  `^(?:(?:FW|CLS|MGR|DEV|VS|DOM|DG|MD)-(?:${WORD_GROUP})(?:-\\d{1,3})?(?:\\.[12]|-M[12]|-CLS-[12]|-\\d{1,3}|-V\\d+)?|=.*)$`,
);

export const FORBIDDEN_DEVICE_LIKE_FIXTURE_PATTERN = new RegExp(
  "(?<![a-zA-Z0-9_-])(?:" +
    "(?:FW|CLS|VS|MGR|DOM|MD)-(?:SYNTH|CKP|PALT|RADWARE|EDGE|ASA|PA|FG|MDS|SEC|PROD|CORP|DMZ|HQ|DC|BRANCH|LAB|TEST)(?:-[A-Z0-9-]+)?" +
    "|PA-(?:HA-PAIR|PAIR|STANDALONE|FW-\\d+|SYNTHETIC)" +
    "|(?:fw|cp|pan|vsx|pano|fgt|asa)-(?:edge|gw|fw|core|dmz|dc|hq|branch|prod|corp|ha|pair|cluster|standalone)(?:-[a-z0-9-]+)?" +
    "|alpha-cp|bravo-pan|zeta-pan|mid-cp|member-[ab]|standalone-c|done-device|failed-device|full-device|sparse-device" +
    "|vs-finance|vs-dmz|VR-DMZ|CorpNet|Examplebank\\w*|PENDIK\\w*|EXTEST\\w*" +
    "|(?:ist|izm|ank|brs)-[a-z0-9-]+" +
  ")(?![a-zA-Z0-9_-])",
);

const HOSTNAME_OR_CLUSTER_ASSIGNMENT = /\b(hostname|cluster_member_ref|clusterRef)\s*[:=]\s*(["'])([^"']+)\2/g;
const IPV4_LITERAL = /\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b/g;
export const ALLOWED_SYNTHETIC_IPV4_PATTERN =
  /^(?:192\.0\.2\.\d{1,3}|198\.51\.100\.\d{1,3}|203\.0\.113\.\d{1,3}|0\.0\.0\.0|127\.0\.0\.1|255\.255\.255\.\d{1,3}|0000\.00\.00\.000|7\.4\.15\.1)$/;

const fixtureSources = import.meta.glob(
  ["./**/*.{ts,tsx}", "../e2e/**/*.{ts,tsx}", "../browser-tests/**/*.{ts,tsx}", "../src/preview/**/*.{ts,tsx}", "!./previewMasking.test.ts"],
  { eager: true, query: "?raw", import: "default" },
) as Record<string, string>;

it("uses AIView names throughout the shared preview data", () => {
  const names = [
    ...ALIGNMENT.map((row) => row.device),
    ...INVENTORY.map((row) => row.name),
    ...CONFIG_DEVICES.map((row) => row.name),
    ...CONFIG_SETTINGS.map((row) => row.device),
    ...COMPLIANCE_FINDINGS.map((row) => row.device),
    ...OPERATIONS_JOBS.map((row) => row.target),
    ...OPERATIONS_READINESS.map((row) => row.cluster),
    ...ADMIN_DEVICES.map((row) => row.name),
  ];
  for (const name of names) {
    expect(name).toMatch(/^(?:FW-[A-Z]+-\d{2}(?:-M[12])?|CLS-[A-Z]+-\d{2}|VS-[A-Z]+-\d{2}-\d{2})$/);
    expect(name).toMatch(MASKED_ENTITY_NAME_PATTERN);
  }
  expect(JSON.stringify({ ALIGNMENT, INVENTORY, CONFIG_DEVICES, CONFIG_SETTINGS, COMPLIANCE_FINDINGS, OPERATIONS_JOBS, OPERATIONS_READINESS, ADMIN_DEVICES }))
    .not.toMatch(/\b(?:fw|pan|cp|vsx|pano)-[a-z]|\b(?:ist|izm|ank|brs)\b/);
});

it("keeps the executive home preview to counts: no device or cluster name, no address (UI review 2026-11-05, C1)", async () => {
  const { EXEC_HOME } = await import("../src/preview/previewData");
  const text = JSON.stringify(EXEC_HOME);
  expect(text).not.toMatch(/\b(?:FW|CLS|VS)-[A-Z]+-\d{2}\b/);
  expect(text).not.toMatch(/\b\d{1,3}(?:\.\d{1,3}){3}\b/);
});

it("forbids device-like realistic names and non-RFC5737 IPv4 addresses across all frontend test and preview fixtures", () => {
  const entries = Object.entries(fixtureSources);
  expect(entries.length).toBeGreaterThan(100);

  const violations: string[] = [];
  for (const [rel, content] of entries) {
    for (const match of content.matchAll(new RegExp(FORBIDDEN_DEVICE_LIKE_FIXTURE_PATTERN.source, "g"))) {
      violations.push(`${rel}: forbidden device-like name "${match[0]}"`);
    }

    for (const match of content.matchAll(HOSTNAME_OR_CLUSTER_ASSIGNMENT)) {
      const [, prop, , value] = match;
      if (!MASKED_ENTITY_NAME_PATTERN.test(value)) {
        violations.push(`${rel}: ${prop}="${value}" does not match MASKED_ENTITY_NAME_PATTERN`);
      }
    }

    for (const match of content.matchAll(IPV4_LITERAL)) {
      const ip = match[1];
      if (!ALLOWED_SYNTHETIC_IPV4_PATTERN.test(ip)) {
        violations.push(`${rel}: non-RFC5737 IPv4 literal "${ip}"`);
      }
    }
  }

  expect(violations).toEqual([]);

  // Verify the guard patterns reject realistic / unmasked names and private/public IPs.
  for (const badName of ["fw-edge-1", "cp-gw-01", "FW-SYNTH-01", "FW-CKP-01", "PA-HA-PAIR", "CLS-ASA-01", "VS-SEC-62", "alpha-cp", "Examplebank_FW"]) {
    expect(
      FORBIDDEN_DEVICE_LIKE_FIXTURE_PATTERN.test(badName) || !MASKED_ENTITY_NAME_PATTERN.test(badName),
      `expected guard to reject ${badName}`,
    ).toBe(true);
  }
  for (const goodName of ["FW-TANGO-04", "FW-ROMEO-01-M1", "CLS-ROMEO-01", "VS-ROMEO-01-01", "MGR-BRAVO-01", "DOM-ALPHA-01", "MD-BRAVO-01"]) {
    expect(MASKED_ENTITY_NAME_PATTERN.test(goodName), `expected guard to accept ${goodName}`).toBe(true);
    expect(FORBIDDEN_DEVICE_LIKE_FIXTURE_PATTERN.test(goodName), `expected guard not to flag ${goodName}`).toBe(false);
  }
  for (const badIp of ["10.0.0.1", "172.16.0.1", "192.168.1.1", "240.12.0.8", "248.15.28.1"]) {
    expect(ALLOWED_SYNTHETIC_IPV4_PATTERN.test(badIp), `expected guard to reject ${badIp}`).toBe(false);
  }
  for (const goodIp of ["192.0.2.10", "198.51.100.25", "203.0.113.5", "0.0.0.0", "255.255.255.0"]) {
    expect(ALLOWED_SYNTHETIC_IPV4_PATTERN.test(goodIp), `expected guard to accept ${goodIp}`).toBe(true);
  }
});
