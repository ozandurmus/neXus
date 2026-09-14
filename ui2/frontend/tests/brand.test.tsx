import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { NexusMark } from "../src/brand/NexusMark";
import { NexusWordmark } from "../src/brand/NexusWordmark";

describe("NexusWordmark", () => {
  it("renders an svg with an accessible neXus title", () => {
    const { container } = render(<NexusWordmark />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg?.querySelector("title")?.textContent).toBe("neXus");
  });

  it("scales from 20px to 200px tall with no raster asset", () => {
    for (const height of [20, 200]) {
      const { container, unmount } = render(<NexusWordmark height={height} />);
      const svg = container.querySelector("svg");
      expect(svg?.getAttribute("height")).toBe(String(height));
      expect(container.querySelector("img")).toBeNull();
      unmount();
    }
  });
});

describe("NexusMark", () => {
  it("renders an svg with an accessible neXus title", () => {
    const { container } = render(<NexusMark />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg?.querySelector("title")?.textContent).toBe("neXus");
  });

  it("scales from 20px to 200px tall with no raster asset", () => {
    for (const size of [20, 200]) {
      const { container, unmount } = render(<NexusMark size={size} />);
      const svg = container.querySelector("svg");
      expect(svg?.getAttribute("width")).toBe(String(size));
      expect(svg?.getAttribute("height")).toBe(String(size));
      expect(container.querySelector("img")).toBeNull();
      unmount();
    }
  });
});
