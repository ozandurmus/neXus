import type { PolicyFirewallRef } from "../auth/adminApi";
import { statusWord } from "../shell/presenters";

export interface SyncWords { word: string; tone: "good" | "warn" | "neutral"; /** Whether the management server made a claim at all. */ reported: boolean; reason: string }
/**
 * A target's sync state in words. Panorama reports IN_SYNC / OUT_OF_SYNC and those are claims; Check Point does not
 * report a sync state the product can prove, so an absent or UNKNOWN value on a CP target is "not reported", neutral --
 * never a warning badge on every gateway. An UNKNOWN on any other vendor stays the word UNKNOWN (fail-closed law).
 */
export function syncStatusWords(vendor: string | null | undefined, status: string | null | undefined): SyncWords {
  const raw = (status ?? "").trim().toUpperCase();
  if (raw === "IN_SYNC") return { word: "In sync", tone: "good", reported: true, reason: "The management server reports this target in sync with the stored policy." };
  if (raw === "OUT_OF_SYNC") return { word: "Out of sync", tone: "warn", reported: true, reason: "The management server reports this target out of sync with the stored policy." };
  if (!raw || raw === "UNKNOWN") {
    if (vendor === "CP") return { word: "Sync not reported by Check Point", tone: "neutral", reported: false, reason: "Check Point does not report a sync state the product can prove; this is not a fault of the target. Installation evidence is on the Installation tab." };
    return { word: "Sync UNKNOWN", tone: "neutral", reported: false, reason: "The management server did not report a sync state for this target." };
  }
  return { word: statusWord(raw), tone: "neutral", reported: true, reason: `Reported by the management server as ${raw}.` };
}

/**
 * Panorama names a managed firewall by its serial. The server resolves each serial against the inventory
 * (exact match to an enrolled firewall's recorded serial) and sends the result beside the raw selector; this is the
 * one place the screens turn it into text. The serial stays available as the secondary text.
 */
export function firewallLabel(ref: PolicyFirewallRef): string {
  if (ref.enrolled) return ref.name || ref.serial;
  return `not enrolled (${ref.serial})`;
}

/** The serial as secondary text for an enrolled firewall shown by its hostname; empty when the label already holds it. */
export function firewallSecondary(ref: PolicyFirewallRef): string {
  return ref.enrolled && ref.name ? `Serial ${ref.serial}` : "";
}

export function firewallLookup(refs: PolicyFirewallRef[] | undefined): Map<string, PolicyFirewallRef> {
  return new Map((refs ?? []).map(ref => [ref.serial, ref]));
}

/** A serial list (a rule's target selectors) as labels; a value that is not a known serial passes through. */
export function firewallLabels(values: string[], refs: PolicyFirewallRef[] | undefined): string[] {
  const lookup = firewallLookup(refs);
  return values.map(value => { const ref = lookup.get(value); return ref ? firewallLabel(ref) : value; });
}

/** A recorded change value with every string that is a known serial replaced by its label. */
export function withFirewallNames(value: unknown, refs: PolicyFirewallRef[] | undefined): unknown {
  if (!refs?.length) return value;
  const lookup = firewallLookup(refs);
  const walk = (item: unknown): unknown => {
    if (typeof item === "string") { const ref = lookup.get(item); return ref ? firewallLabel(ref) : item; }
    if (Array.isArray(item)) return item.map(walk);
    if (item && typeof item === "object") return Object.fromEntries(Object.entries(item as Record<string, unknown>).map(([k, v]) => [k, walk(v)]));
    return item;
  };
  return walk(value);
}

/** Tooltip text naming each enrolled firewall's serial, for places that show the hostnames only. */
export function firewallSerialTitle(refs: PolicyFirewallRef[] | undefined): string | undefined {
  const parts = (refs ?? []).filter(ref => ref.enrolled && ref.name).map(ref => `${ref.name}: ${ref.serial}`);
  return parts.length ? parts.join("\n") : undefined;
}
