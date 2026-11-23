import { describe, expect, it } from "vitest";
import { screenFromSearch } from "../src/App";

describe("screenFromSearch devices alias", () => {
  it("opens the Devices (inventory) screen for ?screen=devices, filters kept, no tab forced", () => {
    window.history.replaceState(null, "", "/?screen=devices&q=edge");
    expect(screenFromSearch("screen=devices&q=edge")).toEqual({ screen: "inventory", preview: false });
    expect(window.location.search).toBe("?screen=inventory&q=edge");
  });

  it("still sends an unknown screen to Overview", () => {
    expect(screenFromSearch("screen=nope")).toEqual({ screen: "overview", preview: false });
  });
});
