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
    if (vendor === "CP") return { word: "Sync not reported by Check Point", tone: "neutral", reported: false, reason: "Check Point does not report a sync state the product can prove; this is not a fault of the target. Installation evidence is under Rules & Containers › Installation." };
    return { word: "Sync UNKNOWN", tone: "neutral", reported: false, reason: "The management server did not report a sync state for this target." };
  }
  return { word: statusWord(raw), tone: "neutral", reported: true, reason: `Reported by the management server as ${raw}.` };
}

/**
 * The Installation status of a container in one line (UI review 2026-11-05, C2): the management server's reported sync
 * state over every (package, target) pair, counted by word. It states what the server reported and nothing more -- a target
 * the server made no claim about is "not reported" (neutral, with the vendor's reason on hover), never a fault of the target,
 * and the line is management-plane evidence, not proof of what a device enforces.
 */
export function installationSummary(vendor: string | null | undefined, states: readonly string[]): { text: string; tone: "good" | "warn" | "neutral"; reason: string } {
  if (states.length === 0) return { text: "No target", tone: "neutral", reason: "No installation target is recorded for the packages of this container." };
  let inSync = 0, outOfSync = 0, other = 0, unreported = 0;
  for (const state of states) {
    const words = syncStatusWords(vendor, state);
    if (!words.reported) unreported++;
    else if (words.word === "In sync") inSync++;
    else if (words.word === "Out of sync") outOfSync++;
    else other++;
  }
  const parts = [inSync && `${inSync} in sync`, outOfSync && `${outOfSync} out of sync`, other && `${other} other`, unreported && `${unreported} not reported`].filter(Boolean) as string[];
  const reason = unreported ? syncStatusWords(vendor, "UNKNOWN").reason : "As reported by the management server; installation is intent, not runtime enforcement.";
  return { text: parts.join(" · "), tone: outOfSync ? "warn" : unreported || other || !inSync ? "neutral" : "good", reason };
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
