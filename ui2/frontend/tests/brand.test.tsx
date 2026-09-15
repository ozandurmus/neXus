import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { NexusMark } from "../src/brand/NexusMark";
import { NexusWordmark } from "../src/brand/NexusWordmark";

describe("NexusWordmark", () => {
  it("renders the approved wordmark asset", () => {
    const { container } = render(<NexusWordmark />);
    const image = container.querySelector("img");
    expect(image).not.toBeNull();
    expect(image?.getAttribute("src")).toContain("wordmark.svg");
    expect(image?.getAttribute("alt")).toBe("neXus");
  });

  it("renders the approved tagline lockup when requested", () => {
    const { container } = render(<NexusWordmark tagline />);
    const image = container.querySelector("img");
    expect(image?.getAttribute("src")).toContain("wordmark-tagline.svg");
    expect(image?.getAttribute("alt")).toBe("neXus — A CLEARER TOMORROW");
  });

  it("keeps the height interface", () => {
    for (const height of [20, 200]) {
      const { container, unmount } = render(<NexusWordmark height={height} />);
      expect(container.querySelector("img")?.getAttribute("height")).toBe(String(height));
      unmount();
    }
  });
});

describe("NexusMark", () => {
  it("renders the approved app mark asset", () => {
    const { container } = render(<NexusMark />);
    const image = container.querySelector("img");
    expect(image).not.toBeNull();
    expect(image?.getAttribute("src")).toContain("app-mark.svg");
    expect(image?.getAttribute("alt")).toBe("neXus");
  });

  it("keeps the size interface", () => {
    for (const size of [20, 200]) {
      const { container, unmount } = render(<NexusMark size={size} />);
      const image = container.querySelector("img");
      expect(image?.getAttribute("width")).toBe(String(size));
      expect(image?.getAttribute("height")).toBe(String(size));
      unmount();
    }
  });
});
