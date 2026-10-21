import type { PolicyFirewallRef } from "../auth/adminApi";

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
