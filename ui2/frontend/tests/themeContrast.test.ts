import { describe, expect, it } from "vitest";
import { LIGHT, DARK } from "../src/theme/m3Theme";
import { STATUS } from "../src/shell/Charts";

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((index) => {
    const value = parseInt(hex.slice(index, index + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrast(first: string, second: string): number {
  const [lighter, darker] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (lighter + 0.05) / (darker + 0.05);
}

describe.each([["light", LIGHT], ["dark", DARK]] as const)("%s token contrast", (_mode, tokens) => {
  it("keeps body and state ink readable on both page and card surfaces", () => {
    for (const ink of ["onSurface", "onSurfaceVar", "goodInk", "warningInk", "seriousInk", "criticalInk", "neutralInk"] as const) {
      for (const surface of ["surface", "scLowest"] as const) {
        expect(contrast(tokens[ink], tokens[surface]), `${ink} on ${surface}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it("keeps container labels and primary button text readable", () => {
    for (const [ink, container] of [
      ["onPrimaryContainer", "primaryContainer"],
      ["onErrorContainer", "errorContainer"],
      ["onWarningContainer", "warningContainer"],
      ["onSuccessContainer", "successContainer"],
      ["onAttentionContainer", "attentionContainer"],
      ["onMemberContainer", "memberContainer"],
    ] as const) {
      expect(contrast(tokens[ink], tokens[container]), `${ink} on ${container}`).toBeGreaterThanOrEqual(4.5);
    }
    expect(contrast(tokens.primary, tokens.onPrimary)).toBeGreaterThanOrEqual(4.5);
  });
});

it("keeps status fills and vendor swatches distinct on the dark card surface", () => {
  for (const [name, fill] of Object.entries(STATUS)) {
    expect(contrast(fill, DARK.scLowest), `status ${name}`).toBeGreaterThanOrEqual(3);
  }
  for (const vendor of ["cp", "vsx", "pan"] as const) {
    expect(contrast(DARK[vendor], DARK.scLowest), `vendor ${vendor}`).toBeGreaterThanOrEqual(3);
  }
});
