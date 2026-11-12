import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { NexusMark } from "../src/brand/NexusMark";
import { NexusWordmark } from "../src/brand/NexusWordmark";
import { DARK, LIGHT, m3 } from "../src/theme/m3Theme";

/** Every colour an element of the logo paints with, read from the inline style (a token, never a literal). */
const paints = (root: Element) => [...root.querySelectorAll<SVGElement>("[style]")]
  .flatMap((el) => [el.style.getPropertyValue("stroke"), el.style.getPropertyValue("fill")]).filter((v) => v !== "");

describe("NexusWordmark", () => {
  it("draws the approved wordmark in place, named neXus, with no image file and no box behind it", () => {
    const { container } = render(<NexusWordmark />);
    const logo = screen.getByRole("img", { name: "neXus" });
    expect(logo.tagName.toLowerCase()).toBe("svg");
    expect(logo).toHaveAttribute("data-brand", "wordmark");
    expect(logo).toHaveAttribute("viewBox", "-2 -2 200 44");
    expect(container.querySelector("img")).toBeNull();
    expect(logo.style.backgroundColor).toBe("");
  });

  it("paints with the three brand tokens only, so it follows the theme", () => {
    render(<NexusWordmark />);
    expect(new Set(paints(screen.getByRole("img", { name: "neXus" })))).toEqual(new Set([m3.brandInk, m3.brandTeal, m3.brandBlue]));
  });

  it("keeps the height interface and the artwork's proportions", () => {
    for (const height of [22, 44, 200]) {
      const { unmount } = render(<NexusWordmark height={height} />);
      const logo = screen.getByRole("img", { name: "neXus" });
      expect(logo).toHaveAttribute("height", String(height));
      expect(Number(logo.getAttribute("width"))).toBeCloseTo(height * 200 / 44, 1);
      unmount();
    }
  });

  it("gives each instance its own mask, so two wordmarks on one page do not share one", () => {
    const { container } = render(<><NexusWordmark /><NexusWordmark /></>);
    const ids = [...container.querySelectorAll("mask")].map((m) => m.id);
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
    for (const id of ids) expect(container.querySelector(`[mask="url(#${id})"]`)).not.toBeNull();
  });
});

describe("NexusMark", () => {
  it("draws the X, its nodes and hub without the navy tile, named neXus", () => {
    const { container } = render(<NexusMark />);
    const logo = screen.getByRole("img", { name: "neXus" });
    expect(logo.tagName.toLowerCase()).toBe("svg");
    expect(logo).toHaveAttribute("data-brand", "mark");
    expect(container.querySelector("img")).toBeNull();
    // The mask's own rectangle is the only one: no tile is drawn.
    expect(logo.querySelectorAll(":scope > rect")).toHaveLength(0);
    expect(new Set(paints(logo))).toEqual(new Set([m3.brandInk, m3.brandTeal, m3.brandBlue]));
  });

  it("keeps the size interface", () => {
    for (const size of [20, 200]) {
      const { unmount } = render(<NexusMark size={size} />);
      const logo = screen.getByRole("img", { name: "neXus" });
      expect(logo).toHaveAttribute("width", String(size));
      expect(logo).toHaveAttribute("height", String(size));
      unmount();
    }
  });
});

describe("the brand tokens", () => {
  it("are the approved artwork's colours: the light files in light, the sign-in screen's in dark", () => {
    expect([LIGHT.brandInk, LIGHT.brandTeal, LIGHT.brandBlue]).toEqual(["#0c1a2b", "#0a7f96", "#1f5fe0"]);
    expect([DARK.brandInk, DARK.brandTeal, DARK.brandBlue]).toEqual(["#eaf1f8", "#56c7dc", "#6f9bff"]);
  });

  it("clear 3:1 against the sidebar and the drawer surface in both modes", () => {
    const lum = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const ratio = (a: string, b: string) => { const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); };
    for (const t of [LIGHT, DARK]) for (const colour of [t.brandInk, t.brandTeal, t.brandBlue]) for (const surface of [t.surface, t.scLow]) {
      expect(ratio(colour, surface)).toBeGreaterThanOrEqual(3);
    }
  });
});
