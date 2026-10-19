import { describe, expect, it } from "vitest";

import { DARK, LIGHT, m3ThemeFor } from "../src/theme/m3Theme";
import { DENSITY, KPI_FIGURE, LABEL_UPPER, RADIUS, ROLE_COLORS, SCREEN_DENSITY, SPACE, TYPE, isUnknownWord, roleValues } from "../src/theme/tokens";

/**
 * The design language (Phase 3.1, `docs/design/UI_DESIGN_LANGUAGE.md`): five type steps, two density modes chosen
 * per screen type, and a colour-role table whose every pairing reads in light and in dark.
 */

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

describe("type scale", () => {
  it("has exactly five steps with the review's sizes", () => {
    expect(Object.keys(TYPE)).toEqual(["pageTitle", "section", "body", "label", "mono"]);
    expect(TYPE.pageTitle).toMatchObject({ fontSize: 22, lineHeight: "28px", fontWeight: 600 });
    expect(TYPE.section).toMatchObject({ fontSize: 16, lineHeight: "22px", fontWeight: 600 });
    expect(TYPE.body).toMatchObject({ fontSize: 14, lineHeight: "20px" });
    expect(TYPE.label).toMatchObject({ fontSize: 12, lineHeight: "16px" });
    expect(TYPE.mono).toMatchObject({ fontSize: 13, lineHeight: "18px" });
    expect(TYPE.mono.fontFamily).toContain("Mono");
    expect(LABEL_UPPER.textTransform).toBe("uppercase");
    expect(KPI_FIGURE.fontVariantNumeric).toBe("tabular-nums");
  });

  it("is what the MUI theme hands to Typography", () => {
    const t = m3ThemeFor("light", "comfortable").typography;
    expect(t.h2).toMatchObject({ fontSize: 22, lineHeight: "28px", fontWeight: 600 });
    expect(t.h4).toMatchObject({ fontSize: 16, lineHeight: "22px", fontWeight: 600 });
    expect(t.body1).toMatchObject({ fontSize: 14, lineHeight: "20px" });
    expect(t.body2).toMatchObject({ fontSize: 12, lineHeight: "16px" });
    expect(t.caption).toMatchObject({ fontSize: 12, lineHeight: "16px" });
  });

  it("keeps the spacing scale on 4 px and the three radii", () => {
    for (const v of Object.values(SPACE)) expect(v % 4).toBe(0);
    expect(RADIUS).toEqual({ pill: 8, card: 10, button: 20 });
  });
});

describe("density", () => {
  it("is comfortable (44 px rows, 16 px gutters) or compact (32 px one-line rows, 8 px gutters)", () => {
    expect(DENSITY.comfortable).toEqual({ gutter: 16, row: 44, cellY: 8 });
    expect(DENSITY.compact).toEqual({ gutter: 8, row: 32, cellY: 4 });
  });

  it("is chosen per screen type: list screens compact, everything else comfortable", () => {
    expect(SCREEN_DENSITY).toEqual({ summary: "comfortable", object: "comfortable", list: "compact", tool: "comfortable" });
  });

  it("drives the MUI table row height", () => {
    const row = (density: "comfortable" | "compact") => (m3ThemeFor("light", density).components?.MuiTableRow?.styleOverrides?.root as { height: number }).height;
    expect(row("comfortable")).toBe(44);
    expect(row("compact")).toBe(32);
  });
});

describe("colour roles", () => {
  it("names the seven roles of the table plus the two alignment tones", () => {
    expect(Object.keys(ROLE_COLORS).sort()).toEqual(["attn", "bad", "brand", "mem", "neutral", "ok", "role", "unknown", "warn"]);
    expect(ROLE_COLORS.unknown.hatched).toBe(true);
    expect(ROLE_COLORS.neutral.bg).toMatch(/^var\(--nx-/);
  });

  it.each([["light", LIGHT], ["dark", DARK]] as const)("reads at 4.5:1 or better in %s", (mode, tokens) => {
    const values = roleValues(mode);
    for (const [role, { bg, fg }] of Object.entries(values)) {
      expect(contrast(fg, bg), `${role} in ${mode}`).toBeGreaterThanOrEqual(4.5);
    }
    // the hatch lines must not drown the unknown word
    expect(contrast(values.unknown.fg, tokens.outlineVar)).toBeGreaterThanOrEqual(3);
  });

  it("hatches UNKNOWN, Not assessed, Not evidenced and Not evaluated, nothing else", () => {
    for (const w of ["UNKNOWN", "Not assessed", "Not evidenced", "Not evaluated"]) expect(isUnknownWord(w)).toBe(true);
    for (const w of ["0", "Not ready", "Not read yet", "Not applicable", "Ready"]) expect(isUnknownWord(w)).toBe(false);
  });
});
