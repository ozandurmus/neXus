import { describe, expect, it } from "vitest";

import { interfacesFromConfiguration, interfacesNotCollected, interfacesSourceNote } from "../src/screens/InventoryPanels";

/**
 * PO 2026-10-24 (replace_12): on PAN-OS 12 or later a firewall's interfaces come from the stored configuration. The
 * Interfaces tab then shows the table with its source, never the "not collected" empty panel; with no stored
 * configuration it stays UNKNOWN with the reason.
 */
describe("PAN-OS 12 interfaces derived from configuration", () => {
  const derived = { not_collected: ["pan_show_interface_all", "pan_os_12_or_later", "pan_interfaces_from_configuration"],
    not_collected_reason: "Configured (from configuration)", interfaces_source: "configuration" as const };

  it("shows the table with the source and what stays UNKNOWN", () => {
    expect(interfacesFromConfiguration(derived)).toBe(true);
    expect(interfacesNotCollected(derived)).toBeNull();
    const note = interfacesSourceNote(derived)!;
    expect(note).toContain("configured (from configuration)");
    expect(note).toContain("Link state is UNKNOWN (not observed) except HA ports");
    // The token alone is enough (an API that did not send interfaces_source).
    expect(interfacesFromConfiguration({ not_collected: derived.not_collected })).toBe(true);
  });

  it("stays UNKNOWN with the reason when there is no stored configuration", () => {
    const missing = { not_collected: ["pan_show_interface_all", "pan_os_12_or_later", "pan_interfaces_configuration_missing"],
      not_collected_reason: "Not collected: PAN-OS 12 or later, and no stored configuration to derive the interfaces from (run a configuration collection)",
      interfaces_source: null };
    expect(interfacesSourceNote(missing)).toBeNull();
    expect(interfacesNotCollected(missing)).toContain("no stored configuration");
    expect(interfacesNotCollected(missing)).toContain("UNKNOWN");
  });

  it("says nothing for interfaces read from the device", () => {
    expect(interfacesSourceNote({ not_collected: [], interfaces_source: null })).toBeNull();
    expect(interfacesSourceNote(null)).toBeNull();
  });
});
