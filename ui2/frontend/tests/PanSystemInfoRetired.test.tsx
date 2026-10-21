import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { IdentityCard, identityTiles, platformFactsNote } from "../src/screens/PlatformIdentity";
import { interfacesNotCollected } from "../src/screens/InventoryPanels";
import type { DeviceSummary } from "../src/auth/adminApi";

/**
 * PO 2026-10-21: show system info is retired for Palo Alto firewalls. Their platform facts are UNKNOWN, and the grid
 * says why once. Synthetic ids and documentation addresses only.
 */
const firewall = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "dev-pan-1", hostname: "FW-TANGO-04", vendor_hint: "palo_alto", role: "gateway", enrollment_state: "ENROLLED",
  model: null, software_version: null, ha_role: "active", cluster_member_ref: null, management_ip: "192.0.2.14",
  serial_number: null, content_versions: null, uptime_text: null,
  platform_facts_not_collected_reason: "Not collected (command retired by PO 2026-10-21)", ...over,
});

describe("Palo Alto firewall platform facts after show system info was retired", () => {
  it("says why once and marks the facts not collected, never 'Not read yet'", () => {
    const tiles = identityTiles(firewall(), null);
    for (const label of ["Model", "Serial number", "Software version", "Content versions", "Uptime"]) {
      const tile = tiles.find((t) => t.label === label)!;
      expect(tile.value, label).toBeNull();
      expect(tile.absent, label).toBe("not collected");
      expect(tile.absentWord, label).toBeUndefined();
    }
    const note = platformFactsNote(firewall());
    expect(note).toContain("not collected for Palo Alto firewalls (PO 2026-10-21)");
    render(<ThemeProvider theme={m3Theme}><IdentityCard title="FW-TANGO-04" tiles={tiles} note={note} /></ThemeProvider>);
    expect(screen.getAllByTestId("platform-facts-note")).toHaveLength(1);
    expect(screen.getAllByText("not collected")).toHaveLength(5);
  });

  it("leaves a Panorama and other vendors as they were", () => {
    expect(platformFactsNote(firewall({ role: "management_server", platform_facts_not_collected_reason: null }))).toBeNull();
    expect(platformFactsNote(firewall({ vendor_hint: "check_point", platform_facts_not_collected_reason: null }))).toBeNull();
    const tiles = identityTiles(firewall({ platform_facts_not_collected_reason: null }), null);
    expect(tiles.find((t) => t.label === "Serial number")!.absentWord).toBe("Not read yet");
  });
});

describe("Palo Alto firewall reads switched off (PO 2026-10-21 scope update)", () => {
  it("says the interfaces are UNKNOWN with the reason when show interface all was not sent", () => {
    const text = interfacesNotCollected({ not_collected: ["pan_show_interface_all"], not_collected_reason: "Not collected (command retired by PO 2026-10-21)" });
    expect(text).toContain("UNKNOWN");
    expect(text).toContain("retired by PO 2026-10-21");
    expect(interfacesNotCollected({ not_collected: [], not_collected_reason: null })).toBeNull();
    expect(interfacesNotCollected(null)).toBeNull();
  });
});
