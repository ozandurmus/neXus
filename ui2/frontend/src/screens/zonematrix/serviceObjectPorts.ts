import {
  getPolicyObject,
  getPolicyObjects,
  getPolicyUsage,
  responsesAreMasked,
  listPolicies,
  type PolicyInventoryObject,
  type PolicyMetadata,
  type PolicyObject,
} from "../../auth/adminApi";

/** Matches ServiceEntry.java and PolicyNormalization.java port bounds (0-65535). */
export const MIN_SERVICE_PORT = 0;
export const MAX_SERVICE_PORT = 65535;
/** Maximum transitive nesting depth for service groups (inclusive, matching backend guard). */
export const MAX_SERVICE_GROUP_DEPTH = 32;

const PORT_TOKEN_RE = /^[0-9]+(?:-[0-9]+)?$/;

const MAX_OBJECT_VISITS = 500; // Same bound as the server expansion.
const LEAF_SERVICE_TYPES = new Set(["service", "service-tcp", "service-udp"]);
const SOURCE_PORT_KEYS = new Set(["source-port", "protocol/tcp/source-port", "protocol/udp/source-port"]);
const FLAT_ALLOWED_KEYS = new Set(["protocol", "port"]);
const PREFIXED_ALLOWED_KEYS = new Set(["protocol/tcp/port", "protocol/udp/port"]);

interface PortRange {
  readonly low: number;
  readonly high: number;
}

interface CollectedPorts {
  readonly tcp: PortRange[];
  readonly udp: PortRange[];
}

export type ServicePortExtractionResult =
  | { readonly ok: true; readonly entries: readonly string[] }
  | { readonly ok: false; readonly reason: string };

interface ServiceObject extends PolicyObject {
  readonly resolution?: string;
  readonly totalMembers?: number;
  readonly children?: ServiceObject[];
}

export type PolicyObjectFetcher = (objectId: string) => Promise<ServiceObject | null>;

function labelOf(obj: Pick<PolicyObject, "id" | "name">): string {
  const name = obj.name?.trim();
  return name ? name : obj.id;
}

function parsePortList(
  rawList: string,
  objectLabel: string,
): { readonly ok: true; readonly ranges: readonly PortRange[] } | { readonly ok: false; readonly reason: string } {
  const parts = rawList.split(",");
  const ranges: PortRange[] = [];
  for (const rawPart of parts) {
    const token = rawPart.trim();
    if (!PORT_TOKEN_RE.test(token)) {
      return {
        ok: false,
        reason: `Unsupported port expression "${token || rawPart}" in "${objectLabel}".`,
      };
    }
    const ends = token.split("-");
    const low = Number(ends[0]);
    const high = ends.length === 1 ? low : Number(ends[1]);
    if (
      !Number.isInteger(low)
      || !Number.isInteger(high)
      || low < MIN_SERVICE_PORT
      || high > MAX_SERVICE_PORT
      || low > high
    ) {
      return {
        ok: false,
        reason: `Invalid port range "${token}" in "${objectLabel}" (ports must be ${MIN_SERVICE_PORT}-${MAX_SERVICE_PORT} with low <= high).`,
      };
    }
    ranges.push({ low, high });
  }
  if (ranges.length === 0) {
    return { ok: false, reason: `Service object "${objectLabel}" has no port values.` };
  }
  return { ok: true, ranges };
}

function parseLeafValues(
  obj: PolicyObject,
  acc: CollectedPorts,
): { readonly ok: true } | { readonly ok: false; readonly reason: string } {
  const name = labelOf(obj);
  if ((obj.members?.length ?? 0) > 0 || (obj.children?.length ?? 0) > 0) {
    return { ok: false, reason: `Service object "${name}" has unsupported nested members.` };
  }
  const values = obj.values ?? [];
  if (values.length === 0) {
    return { ok: false, reason: `Service object "${name}" has no port values.` };
  }

  const fields = new Map<string, string>();
  for (const rawEntry of values) {
    const split = rawEntry.indexOf(": ");
    const key = split < 0 ? "literal" : rawEntry.substring(0, split);
    const val = split < 0 ? rawEntry : rawEntry.substring(split + 2);
    if (fields.has(key)) {
      return { ok: false, reason: `Ambiguous duplicate field "${key}" in "${name}".` };
    }
    fields.set(key, val);
  }

  for (const key of fields.keys()) {
    if (SOURCE_PORT_KEYS.has(key)) {
      return {
        ok: false,
        reason: `Service "${name}" has a source-port constraint ("${key}"); source-port-narrowed services cannot be copied.`,
      };
    }
  }

  const hasPrefixedKey = Array.from(fields.keys()).some((k) => k.startsWith("protocol/"));
  if (hasPrefixedKey && obj.type === "service") {
    for (const key of fields.keys()) {
      if (!PREFIXED_ALLOWED_KEYS.has(key)) {
        return { ok: false, reason: `Unsupported service constraint "${key}" in "${name}".` };
      }
    }
    const tcpRaw = fields.get("protocol/tcp/port");
    const udpRaw = fields.get("protocol/udp/port");
    if (tcpRaw === undefined && udpRaw === undefined) {
      return { ok: false, reason: `Service object "${name}" has no TCP/UDP port values.` };
    }
    if (tcpRaw !== undefined) {
      const parsed = parsePortList(tcpRaw, name);
      if (!parsed.ok) return parsed;
      acc.tcp.push(...parsed.ranges);
    }
    if (udpRaw !== undefined) {
      const parsed = parsePortList(udpRaw, name);
      if (!parsed.ok) return parsed;
      acc.udp.push(...parsed.ranges);
    }
    return { ok: true };
  }

  for (const key of fields.keys()) {
    if (!FLAT_ALLOWED_KEYS.has(key)) {
      return { ok: false, reason: `Unsupported service constraint "${key}" in "${name}".` };
    }
  }
  // Native Check Point types identify transport; their protocol field is an application handler.
  const rawProtocol = obj.type === "service-tcp" ? "tcp" : obj.type === "service-udp" ? "udp" : fields.get("protocol");
  if (rawProtocol === undefined) {
    return { ok: false, reason: `Service object "${name}" is missing a protocol field.` };
  }
  const proto = rawProtocol.trim().toLowerCase();
  if (proto !== "tcp" && proto !== "udp") {
    return {
      ok: false,
      reason: `Unsupported protocol "${rawProtocol.trim()}" in "${name}"; only TCP and UDP ports can be copied.`,
    };
  }
  const portRaw = fields.get("port");
  if (portRaw === undefined) {
    return { ok: false, reason: `Service object "${name}" has no port values.` };
  }
  const parsed = parsePortList(portRaw, name);
  if (!parsed.ok) return parsed;
  if (proto === "tcp") acc.tcp.push(...parsed.ranges);
  else acc.udp.push(...parsed.ranges);
  return { ok: true };
}

function mergeRanges(ranges: readonly PortRange[]): PortRange[] {
  if (ranges.length === 0) return [];
  const sorted = [...ranges].sort((a, b) => (a.low !== b.low ? a.low - b.low : a.high - b.high));
  const merged: { low: number; high: number }[] = [];
  for (const r of sorted) {
    const last = merged[merged.length - 1];
    if (last && r.low <= last.high + 1) {
      last.high = Math.max(last.high, r.high);
    } else {
      merged.push({ low: r.low, high: r.high });
    }
  }
  return merged;
}

function formatCollectedPorts(acc: CollectedPorts, rootName: string): ServicePortExtractionResult {
  const entries: string[] = [];
  for (const r of mergeRanges(acc.tcp)) {
    entries.push(r.low === r.high ? `tcp/${r.low}` : `tcp/${r.low}-${r.high}`);
  }
  for (const r of mergeRanges(acc.udp)) {
    entries.push(r.low === r.high ? `udp/${r.low}` : `udp/${r.low}-${r.high}`);
  }
  if (entries.length === 0) {
    return { ok: false, reason: `Service object "${rootName}" produced no TCP/UDP port entries.` };
  }
  return { ok: true, entries };
}

function validateNodeHeader(
  obj: ServiceObject,
  depth: number,
  visiting: ReadonlySet<string>,
): { readonly ok: true; readonly kind: "group" | "leaf" } | { readonly ok: false; readonly reason: string } {
  const name = labelOf(obj);
  if (depth > MAX_SERVICE_GROUP_DEPTH) {
    return { ok: false, reason: `Service group nesting exceeds maximum depth (${MAX_SERVICE_GROUP_DEPTH}).` };
  }
  if (obj.id && visiting.has(obj.id)) {
    return { ok: false, reason: `Service group "${name}" contains a cyclic reference.` };
  }
  if (obj.status !== "RESOLVED") {
    return { ok: false, reason: `Object "${name}" is not resolved (status: ${obj.status || "UNKNOWN"}).` };
  }
  if (obj.type === "service-group") {
    if ((obj.values?.length ?? 0) > 0) {
      return { ok: false, reason: `Service group "${name}" has unsupported dynamic or exclusion values.` };
    }
    if (!obj.members?.length) return { ok: false, reason: `Service group "${name}" is empty.` };
    if (obj.totalMembers !== undefined && obj.totalMembers !== obj.members.length) {
      return { ok: false, reason: `Service group "${name}" has a truncated member list.` };
    }
    return { ok: true, kind: "group" };
  }
  if (LEAF_SERVICE_TYPES.has(obj.type)) return { ok: true, kind: "leaf" };
  return {
    ok: false,
    reason: `Unsupported object type "${obj.type}" for "${name}"; only TCP/UDP services and service groups can be copied.`,
  };
}

/** The server expands members in order and inserts a placeholder if a member is absent. */
function loadedMembers(obj: ServiceObject, members: readonly string[]): readonly (ServiceObject | undefined)[] {
  const children = obj.children ?? [];
  const byId = new Map(children.map((child) => [child.id, child]));
  return members.map((member, index) => {
    const child = children[index];
    // expandInventory omits the UID from its output; this order is the server's explicit association.
    if (children.length === members.length && child?.resolution === "DOMAIN_INVENTORY") return child;
    return byId.get(member);
  });
}

type TraversalResult = { readonly ok: true } | { readonly ok: false; readonly reason: string };

interface Traversal {
  readonly visiting: Set<string>;
  readonly ports: CollectedPorts;
  remaining: number;
}

function visitObject(obj: ServiceObject, depth: number, traversal: Traversal) {
  if (--traversal.remaining < 0) return { ok: false, reason: "Service group expansion exceeds the object limit." } as const;
  return validateNodeHeader(obj, depth, traversal.visiting);
}

function extractSync(obj: ServiceObject, depth: number, traversal: Traversal): TraversalResult {
  const header = visitObject(obj, depth, traversal);
  if (!header.ok) return header;
  if (header.kind === "leaf") return parseLeafValues(obj, traversal.ports);

  const members = obj.members ?? [];
  traversal.visiting.add(obj.id);
  try {
    for (const [index, child] of loadedMembers(obj, members).entries()) {
      if (traversal.visiting.has(members[index])) {
        return { ok: false, reason: `Service group "${labelOf(obj)}" contains a cyclic reference.` };
      }
      if (!child) return { ok: false, reason: `Member "${members[index]}" of service group "${labelOf(obj)}" is not loaded.` };
      const res = extractSync(child, depth + 1, traversal);
      if (!res.ok) return res;
    }
    return { ok: true };
  } finally {
    traversal.visiting.delete(obj.id);
  }
}

function newTraversal(): Traversal {
  return { visiting: new Set(), ports: { tcp: [], udp: [] }, remaining: MAX_OBJECT_VISITS };
}

/** Extracts a fully loaded service tree; any unresolved member refuses the whole object. */
export function extractServiceObjectPorts(object: ServiceObject): ServicePortExtractionResult {
  const traversal = newTraversal();
  const res = extractSync(object, 0, traversal);
  return res.ok ? formatCollectedPorts(traversal.ports, labelOf(object)) : res;
}

async function extractAsync(
  obj: ServiceObject,
  depth: number,
  traversal: Traversal,
  fetchMember: PolicyObjectFetcher,
): Promise<TraversalResult> {
  const header = visitObject(obj, depth, traversal);
  if (!header.ok) return header;
  if (header.kind === "leaf") return parseLeafValues(obj, traversal.ports);

  const members = obj.members ?? [];
  traversal.visiting.add(obj.id);
  try {
    for (const [index, loaded] of loadedMembers(obj, members).entries()) {
      const member = members[index];
      if (traversal.visiting.has(member)) {
        return { ok: false, reason: `Service group "${labelOf(obj)}" contains a cyclic reference.` };
      }
      let child = loaded ?? null;
      if (!child && traversal.remaining > 0 && depth < MAX_SERVICE_GROUP_DEPTH) {
        try {
          child = await fetchMember(member);
        } catch {
          return { ok: false, reason: `Could not load member "${member}" of service group "${labelOf(obj)}".` };
        }
      }
      if (!child) {
        if (depth >= MAX_SERVICE_GROUP_DEPTH) return { ok: false, reason: `Service group nesting exceeds maximum depth (${MAX_SERVICE_GROUP_DEPTH}).` };
        if (traversal.remaining <= 0) return { ok: false, reason: "Service group expansion exceeds the object limit." };
        return { ok: false, reason: `Member "${member}" of service group "${labelOf(obj)}" could not be resolved.` };
      }
      const res = await extractAsync(child, depth + 1, traversal, fetchMember);
      if (!res.ok) return res;
    }
    return { ok: true };
  } finally {
    traversal.visiting.delete(obj.id);
  }
}

/** Resolves missing members with a per-inspection cache and the same fail-closed traversal guards. */
export async function resolveServiceObjectPorts(
  object: ServiceObject,
  fetchMember?: PolicyObjectFetcher,
): Promise<ServicePortExtractionResult> {
  if (!fetchMember) return extractServiceObjectPorts(object);
  const traversal = newTraversal();
  const cache = new Map<string, ServiceObject | null>();
  const res = await extractAsync(object, 0, traversal, async (member) => {
    if (!cache.has(member)) cache.set(member, await fetchMember(member));
    return cache.get(member) ?? null;
  });
  return res.ok ? formatCollectedPorts(traversal.ports, labelOf(object)) : res;
}

/**
 * Appends canonical copied service entries to an existing newline-separated textarea without duplicating
 * lines already present and without inserting any vendor object name or ID.
 */
export function mergeCopiedServiceEntries(
  existingText: string,
  copiedEntries: readonly string[],
): { readonly text: string; readonly added: number } {
  const existingLines = existingText
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "");
  const seen = new Set<string>(existingLines);
  const toAdd: string[] = [];
  for (const raw of copiedEntries) {
    const entry = raw.trim();
    if (entry !== "" && !seen.has(entry)) {
      seen.add(entry);
      toAdd.push(entry);
    }
  }
  if (toAdd.length === 0) {
    return { text: existingText, added: 0 };
  }
  if (existingLines.length === 0) {
    return { text: toAdd.join("\n"), added: toAdd.length };
  }
  return {
    text: `${existingText.trimEnd()}\n${toAdd.join("\n")}`,
    added: toAdd.length,
  };
}

/** True for object types that represent services or service groups in policy object listings. */
export function isSelectableServiceObjectType(type: string): boolean {
  return type === "service" || type === "service-group" || type.startsWith("service-");
}

export function formatPolicyOptionLabel(policy: PolicyMetadata): string {
  const parts = [policy.name, policy.containerName, policy.sourceName].filter((p) => Boolean(p && p.trim()));
  return parts.join(" · ") || policy.id;
}

export async function loadAvailablePolicies(): Promise<PolicyMetadata[]> {
  const response = await listPolicies();
  if (!Array.isArray(response?.policies)) throw new Error("Could not read the collected policy list.");
  return response.policies;
}

export interface ServiceObjectSearchResult {
  readonly objects: readonly PolicyInventoryObject[];
  readonly more: number;
  readonly incompleteTypes: readonly string[];
}

export async function searchPolicyServiceObjects(
  policy: PolicyMetadata,
  query: string,
): Promise<ServiceObjectSearchResult> {
  const pages = await Promise.all([
    getPolicyObjects(policy.sourceId, policy.containerId, 0, query, "service"),
    getPolicyObjects(policy.sourceId, policy.containerId, 0, query, "service-group"),
  ]);
  const byId = new Map<string, PolicyInventoryObject>();
  const incompleteTypes = new Set<string>();
  let more = 0;
  for (const page of pages) {
    if (!Array.isArray(page?.objects) || !Array.isArray(page.types)
        || !Number.isSafeInteger(page.total) || page.total < page.objects.length) {
      throw new Error("Could not read the service-object search results.");
    }
    more += page.total - page.objects.length;
    for (const { type, status } of page.types) {
      if ((isSelectableServiceObjectType(type) || (type === "services" || type.startsWith("services-"))) && status !== "RESOLVED") incompleteTypes.add(type);
    }
    for (const item of page.objects) {
      if (isSelectableServiceObjectType(item.type)) byId.set(item.id, item);
    }
  }
  return { objects: [...byId.values()].sort((a, b) => a.name.localeCompare(b.name)), more, incompleteTypes: [...incompleteTypes].sort() };
}

export async function fetchAndResolveServiceObjectPorts(
  row: PolicyInventoryObject,
  policy: PolicyMetadata,
  isCurrent = () => true,
): Promise<{ readonly object: PolicyObject; readonly result: ServicePortExtractionResult }> {
  const unreadable = { ok: false, reason: "These ports cannot be read for this object here." } as const;
  const canRead = () => isCurrent() && !responsesAreMasked();
  const readInDomain = async (uid: string, expectedId?: string): Promise<ServiceObject | null> => {
    if (!uid || !canRead()) return null;
    // Same UID and source/domain read as the Objects tab's “Where used” drawer. It returns the object row too.
    const { object } = await getPolicyUsage(policy.sourceId, policy.containerId, uid);
    if (!canRead() || object?.uid !== uid || !object.id || (expectedId !== undefined && object.id !== expectedId)) return null;
    return { ...object, status: "status" in object && typeof object.status === "string" ? object.status : "UNKNOWN" };
  };

  let object: ServiceObject | null;
  try {
    object = await readInDomain(row.uid, row.id);
  } catch {
    object = null;
  }
  if (!object) return { object: { ...row, status: "UNKNOWN" }, result: unreadable };

  const header = validateNodeHeader(object, 0, new Set());
  if (!header.ok) return { object, result: header };

  // An inventory UID differs from its domain reference. Only a snapshot group can use the package expansion endpoint.
  if (header.kind === "group" && row.id === row.uid) {
    try {
      const snapshot = (await getPolicyObject(row.id, policy.id)).object as ServiceObject;
      if (snapshot?.id !== row.id || !canRead()) return { object, result: unreadable };
      return { object, result: extractServiceObjectPorts(snapshot) };
    } catch (error) {
      // A domain search may return an exact snapshot ID from another package. Don't switch packages.
      if (!error || typeof error !== "object" || !("status" in error) || error.status !== 404) return { object, result: unreadable };
    }
  }

  const result = await resolveServiceObjectPorts(object, readInDomain);
  return { object, result: canRead() ? result : unreadable };
}
