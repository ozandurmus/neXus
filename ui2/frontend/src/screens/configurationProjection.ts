/**
 * Configuration projection: the sanitized configuration a device reported,
 * turned into the operator's tables (section · setting · current value ·
 * origin · context) and the "operator snapshot" tiles -- the shape the
 * Product Owner asked for on 2026-09-22, after the old product's
 * Configuration screen.
 *
 * Pure functions over text the service already serves
 * (GET /devices/{id}/configuration/text: Check Point `set` lines with
 * secret-bearing lines withheld; Palo Alto sanitized XML with secret leaves
 * redacted). Nothing here reaches a device; nothing here invents a value --
 * a line that no rule understands lands in "Other", verbatim.
 */

export type Origin = "LOCAL" | "MEMBER" | "EFFECTIVE" | "OVERRIDE" | "PAN";

export interface SettingRow {
  readonly section: string;
  readonly setting: string;
  readonly value: string;
  readonly origin: Origin;
  readonly context: string | null;
  /** Stable key for joining two members' rows: section + setting (+ ordinal for repeated settings). */
  readonly key: string;
}

export interface Section {
  readonly label: string;
  readonly rows: readonly SettingRow[];
}

export interface SnapshotTile {
  readonly group: string;
  readonly label: string;
  readonly value: string;
  readonly origin: Origin;
}

export interface Projection {
  readonly vendor: "check_point" | "palo_alto";
  readonly sourcePlane: string;
  readonly settingCount: number;
  readonly withheldCount: number;
  readonly sections: readonly Section[];
  readonly snapshot: readonly SnapshotTile[];
}

const SECTION_ORDER = [
  "System", "DNS", "NTP", "Management", "Management Services", "Password Policy", "Login Banner", "Logging",
  "High Availability", "Interfaces", "Routing", "SNMP", "AAA", "Users", "Telemetry", "Network Configuration",
  "Settings", "Other Gaia Configuration", "Other",
];

const ACRONYMS: Record<string, string> = {
  dns: "DNS", ntp: "NTP", ssh: "SSH", ssl: "SSL", snmp: "SNMP", aaa: "AAA", arp: "ARP", ip: "IP", ipv4: "IPv4", ipv6: "IPv6",
  vs: "VS", vsx: "VSX", ha: "HA", lldp: "LLDP", rip: "RIP", ospf: "OSPF", bgp: "BGP", mtu: "MTU", lcd: "LCD", url: "URL",
  tls: "TLS", nat: "NAT", vlan: "VLAN", id: "ID", mgmt: "Mgmt",
};

function titleCase(token: string): string {
  return token
    .split(/[-_]/)
    .filter(Boolean)
    .map((t) => ACRONYMS[t.toLowerCase()] ?? t.charAt(0).toUpperCase() + t.slice(1))
    .join(" ");
}

function orderSections(bySection: Map<string, SettingRow[]>): Section[] {
  const labels = [...bySection.keys()].sort((a, b) => {
    const ia = SECTION_ORDER.indexOf(a);
    const ib = SECTION_ORDER.indexOf(b);
    return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib) || a.localeCompare(b);
  });
  return labels.map((label) => ({ label, rows: bySection.get(label) ?? [] }));
}

function push(bySection: Map<string, SettingRow[]>, counters: Map<string, number>, row: Omit<SettingRow, "key">): void {
  const base = `${row.section}|${row.setting}`;
  const n = (counters.get(base) ?? 0) + 1;
  counters.set(base, n);
  const key = n === 1 ? base : `${base}#${n}`;
  const rows = bySection.get(row.section) ?? [];
  rows.push({ ...row, key });
  bySection.set(row.section, rows);
}

// ---------------------------------------------------------------------------------------------
// Check Point Gaia -- `set ...` lines
// ---------------------------------------------------------------------------------------------

/** One rule: the tokens after `set` that select it, the section, and how many tokens after the prefix belong to the setting name. */
interface CpRule {
  readonly prefix: readonly string[];
  readonly section: string;
  /** Tokens (after the prefix) that are part of the setting name; the rest is the value. */
  readonly nameTokens: number;
  readonly label?: (tokens: readonly string[]) => string;
  readonly origin?: Origin;
}

const CP_RULES: readonly CpRule[] = [
  { prefix: ["hostname"], section: "System", nameTokens: 0, label: () => "Hostname", origin: "MEMBER" },
  { prefix: ["domainname"], section: "System", nameTokens: 0, label: () => "Domain" },
  { prefix: ["timezone"], section: "System", nameTokens: 0, label: () => "Timezone" },
  { prefix: ["format"], section: "System", nameTokens: 1 },
  { prefix: ["dns", "primary"], section: "DNS", nameTokens: 0, label: () => "Primary DNS" },
  { prefix: ["dns", "secondary"], section: "DNS", nameTokens: 0, label: () => "Secondary DNS" },
  { prefix: ["dns", "tertiary"], section: "DNS", nameTokens: 0, label: () => "Tertiary DNS" },
  { prefix: ["dns"], section: "DNS", nameTokens: 1 },
  { prefix: ["ntp", "server", "primary"], section: "NTP", nameTokens: 0, label: () => "Primary NTP Server" },
  { prefix: ["ntp", "server", "secondary"], section: "NTP", nameTokens: 0, label: () => "Secondary NTP Server" },
  { prefix: ["ntp"], section: "NTP", nameTokens: 1 },
  { prefix: ["inactivity-timeout"], section: "Management", nameTokens: 0, label: () => "Inactivity Timeout" },
  { prefix: ["management"], section: "Management", nameTokens: 1 },
  { prefix: ["web"], section: "Management", nameTokens: 1 },
  { prefix: ["ssh"], section: "Management", nameTokens: 1 },
  { prefix: ["net-access"], section: "Management", nameTokens: 1 },
  { prefix: ["proxy"], section: "Management", nameTokens: 0, label: () => "Proxy" },
  { prefix: ["ssl"], section: "Management", nameTokens: 1 },
  { prefix: ["password-controls"], section: "Password Policy", nameTokens: 1 },
  { prefix: ["message"], section: "Login Banner", nameTokens: 1 },
  { prefix: ["syslog"], section: "Logging", nameTokens: 1 },
  { prefix: ["cluster"], section: "High Availability", nameTokens: 1 },
  { prefix: ["interface"], section: "Interfaces", nameTokens: 2, label: (t) => `Interface ${t[0]} · ${titleCase(t[1] ?? "")}`, origin: "LOCAL" },
  { prefix: ["static-route"], section: "Routing", nameTokens: 1, label: (t) => `Static Route · ${t[0]}` },
  { prefix: ["ospf"], section: "Routing", nameTokens: 1 },
  { prefix: ["rip"], section: "Routing", nameTokens: 1 },
  { prefix: ["bgp"], section: "Routing", nameTokens: 1 },
  { prefix: ["inbound-route-filter"], section: "Routing", nameTokens: 1 },
  { prefix: ["router-options"], section: "Routing", nameTokens: 1 },
  { prefix: ["routedsyslog"], section: "Routing", nameTokens: 0, label: () => "Routed Syslog" },
  { prefix: ["max-path-splits"], section: "Routing", nameTokens: 0, label: () => "Max Path Splits" },
  { prefix: ["snmp"], section: "SNMP", nameTokens: 1 },
  { prefix: ["aaa"], section: "AAA", nameTokens: 1 },
  { prefix: ["user"], section: "Users", nameTokens: 1, label: (t) => `User · ${t[0]}` },
];

const CP_MEMBER_SPECIFIC = /^(Interface .* · (IPv4 Address|IPv6 Address)|Hostname)$/;

export function projectCheckPoint(text: string): Projection {
  const bySection = new Map<string, SettingRow[]>();
  const counters = new Map<string, number>();
  let withheldMarkers = 0;
  let withheldDeclared = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#")) {
      if (/WITHHELD\]?$/i.test(line)) withheldMarkers++;
      const m = /secret-bearing-lines-withheld=(\d+)/.exec(line);
      if (m) withheldDeclared = Number(m[1]);
      continue;
    }
    if (!line.startsWith("set ")) continue;
    const tokens = line.slice(4).split(/\s+/);
    const rule = CP_RULES.find((r) => r.prefix.every((p, i) => tokens[i] === p));
    if (!rule) {
      push(bySection, counters, {
        section: "Other Gaia Configuration",
        setting: titleCase(tokens[0] ?? "") + (tokens[1] ? ` · ${titleCase(tokens[1])}` : ""),
        value: tokens.slice(Math.min(2, tokens.length)).join(" ") || "on",
        origin: "LOCAL",
        context: null,
      });
      continue;
    }
    const after = tokens.slice(rule.prefix.length);
    const nameTokens = after.slice(0, rule.nameTokens);
    const value = after.slice(rule.nameTokens).join(" ");
    const setting = rule.label
      ? rule.label(nameTokens)
      : `${titleCase(rule.prefix[rule.prefix.length - 1])}${nameTokens.length ? ` · ${nameTokens.map(titleCase).join(" · ")}` : ""}`;
    const origin: Origin = rule.origin === "MEMBER" || CP_MEMBER_SPECIFIC.test(setting) ? "MEMBER" : "LOCAL";
    push(bySection, counters, { section: rule.section, setting, value: value || "on", origin, context: null });
  }
  const withheld = Math.max(withheldMarkers, withheldDeclared);
  const sections = orderSections(bySection);
  const all = sections.flatMap((s) => s.rows);
  const pick = (section: string, setting: string): SettingRow | undefined =>
    all.find((r) => r.section === section && r.setting === setting);
  const snapshot: SnapshotTile[] = [];
  for (const [group, section, setting, label] of [
    ["SYSTEM", "System", "Hostname", "Hostname"],
    ["SYSTEM", "System", "Domain", "Domain"],
    ["SYSTEM", "System", "Timezone", "Timezone"],
    ["DNS", "DNS", "Primary DNS", "Primary DNS"],
    ["DNS", "DNS", "Secondary DNS", "Secondary DNS"],
    ["NTP", "NTP", "Primary NTP Server", "Primary NTP Server"],
    ["NTP", "NTP", "Secondary NTP Server", "Secondary NTP Server"],
    ["MANAGEMENT", "Management", "Management · Interface", "Management Interface"],
    ["HIGH AVAILABILITY", "High Availability", "Cluster · Member", "Cluster Member"],
  ] as const) {
    const row = pick(section, setting);
    if (row) snapshot.push({ group, label, value: row.value, origin: row.origin });
  }
  return { vendor: "check_point", sourcePlane: "gaia-clish-show-configuration", settingCount: all.length, withheldCount: withheld, sections, snapshot };
}

// ---------------------------------------------------------------------------------------------
// Palo Alto -- sanitized XML
// ---------------------------------------------------------------------------------------------

interface PanRule {
  readonly test: RegExp;
  readonly section: string;
}

const PAN_RULES: readonly PanRule[] = [
  { test: /\/deviceconfig\/system\/dns-setting\//, section: "DNS" },
  { test: /\/deviceconfig\/system\/ntp-servers\//, section: "NTP" },
  { test: /\/deviceconfig\/system\/(permitted-ip|service)\//, section: "Management Services" },
  { test: /\/deviceconfig\/system\/(update-schedule|panorama|snmp-setting|login-banner|motd-and-banner)/, section: "Management" },
  { test: /\/deviceconfig\/system\//, section: "System" },
  { test: /\/deviceconfig\/high-availability\//, section: "High Availability" },
  { test: /\/deviceconfig\/setting\/management\//, section: "Management" },
  { test: /\/deviceconfig\/setting\//, section: "Settings" },
  { test: /\/mgt-config\/password-complexity\//, section: "Password Policy" },
  { test: /\/mgt-config\/users\//, section: "Users" },
  { test: /\/shared\/log-settings\//, section: "Logging" },
  { test: /\/network\/interface\//, section: "Interfaces" },
  { test: /\/network\/virtual-router\//, section: "Routing" },
  { test: /\/network\//, section: "Network Configuration" },
  { test: /\/telemetry|\/statistics-service/, section: "Telemetry" },
];

const PAN_MEMBER_SPECIFIC = /\/deviceconfig\/system\/(hostname|ip-address|ipv6-address)$|\/high-availability\/interface\/[^/]+\/(ip-address|ipv6-address)$|\/high-availability\/.*peer-ip[^/]*$/;

function panLabel(path: string, names: readonly string[]): string {
  // "/config/devices/entry[localhost.localdomain]/deviceconfig/system/dns-setting/servers/primary"
  const parts = path.split("/").filter((p) => p && p !== "config" && p !== "devices" && p !== "response" && p !== "result");
  const cleaned = parts.filter((p) => !/^(deviceconfig|entry|localhost\.localdomain|vsys1)$/.test(p) && !names.includes(p));
  const nameSuffix = names.filter((n) => n !== "localhost.localdomain").map((n) => ` · ${n}`).join("");
  const label = cleaned.slice(-3).map(titleCase).join(" · ");
  if (label === "Dns Setting · Servers · Primary" || label === "DNS Setting · Servers · Primary") return "Primary DNS";
  if (label.endsWith("Servers · Secondary") && path.includes("dns-setting")) return "Secondary DNS";
  if (path.includes("ntp-servers/primary-ntp-server")) return "Primary NTP Server";
  if (path.includes("ntp-servers/secondary-ntp-server")) return "Secondary NTP Server";
  return (label || titleCase(parts[parts.length - 1] ?? "")) + nameSuffix;
}

export function projectPaloAlto(xml: string, overridePaths: readonly string[] = []): Projection {
  const bySection = new Map<string, SettingRow[]>();
  const counters = new Map<string, number>();
  let withheld = 0;
  const parser = new DOMParser();
  const doc = parser.parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length > 0) {
    return { vendor: "palo_alto", sourcePlane: "active", settingCount: 0, withheldCount: 0, sections: [], snapshot: [] };
  }
  const counts = { interfaces: 0, vsys: 0, virtualRouters: 0, zones: 0 };
  const walk = (el: Element, path: string, names: string[]): void => {
    const name = el.getAttribute("name");
    const here = `${path}/${el.tagName}`;
    const nextNames = name ? [...names, name] : names;
    if (/\/network\/interface\/(ethernet|aggregate-ethernet|loopback|vlan|tunnel)(\/units)?$/.test(path) && el.tagName === "entry") counts.interfaces++;
    if (/\/vsys$/.test(path) && el.tagName === "entry") counts.vsys++;
    if (/\/network\/virtual-router$/.test(path) && el.tagName === "entry") counts.virtualRouters++;
    if (/\/zone$/.test(path) && el.tagName === "entry") counts.zones++;
    const children = [...el.children];
    if (children.length === 0) {
      const value = (el.textContent ?? "").trim();
      if (/\/network\//.test(here) || /\/vsys\//.test(here) || /\/shared\//.test(here)) return; // counted, not listed
      if (value === "[REDACTED]") {
        withheld++;
        return;
      }
      const rule = PAN_RULES.find((r) => r.test.test(here));
      const section = rule?.section ?? "Other";
      // A member's own name and addresses -- system hostname / ip-address, the HA link addresses -- differ by
      // nature between cluster members (MEMBER), like a Check Point hostname or interface address.
      const memberSpecific = PAN_MEMBER_SPECIFIC.test(here);
      const origin: Origin = memberSpecific ? "MEMBER" : overridePaths.some((p) => here.includes(p)) ? "OVERRIDE" : "EFFECTIVE";
      // An element with no text is a switch or an empty container: say so, rather than a value that reads like one.
      push(bySection, counters, { section, setting: panLabel(here, nextNames), value: value || "(set, no value)", origin, context: null });
      return;
    }
    for (const child of children) walk(child, here, nextNames);
  };
  if (doc.documentElement) walk(doc.documentElement, "", []);
  for (const [setting, value] of [
    ["VSYS", counts.vsys], ["Virtual Routers", counts.virtualRouters], ["Zones", counts.zones], ["Interfaces", counts.interfaces],
  ] as const) {
    push(bySection, counters, { section: "Network Configuration", setting, value: String(value), origin: "EFFECTIVE", context: null });
  }
  const sections = orderSections(bySection);
  const all = sections.flatMap((s) => s.rows);
  const find = (section: string, pred: (s: string) => boolean) => all.find((r) => r.section === section && pred(r.setting));
  const snapshot: SnapshotTile[] = [];
  const tiles: Array<[string, string, SettingRow | undefined]> = [
    ["SYSTEM", "Hostname", find("System", (s) => s === "System · Hostname" || s.endsWith("Hostname"))],
    ["SYSTEM", "Timezone", find("System", (s) => s.endsWith("Timezone"))],
    ["DNS", "Primary DNS", find("DNS", (s) => s === "Primary DNS")],
    ["DNS", "Secondary DNS", find("DNS", (s) => s === "Secondary DNS")],
    ["NTP", "Primary NTP Server", find("NTP", (s) => s === "Primary NTP Server")],
    ["NTP", "Secondary NTP Server", find("NTP", (s) => s === "Secondary NTP Server")],
    ["HIGH AVAILABILITY", "HA Enabled", find("High Availability", (s) => s.endsWith("Enabled") && !s.includes("Link"))],
    ["HIGH AVAILABILITY", "Group ID", find("High Availability", (s) => s.endsWith("Group ID"))],
  ];
  for (const [group, label, row] of tiles) {
    if (row) snapshot.push({ group, label, value: row.value, origin: row.origin });
  }
  return { vendor: "palo_alto", sourcePlane: "effective-running", settingCount: all.length, withheldCount: withheld, sections, snapshot };
}

// ---------------------------------------------------------------------------------------------
// Cluster view -- two (or more) members side by side
// ---------------------------------------------------------------------------------------------

export interface MemberRow {
  readonly section: string;
  readonly setting: string;
  readonly key: string;
  readonly origin: Origin;
  /** member id -> value ("—" when absent on that member). */
  readonly values: Readonly<Record<string, string>>;
  /** True when two collected members hold different values and the setting is not member-specific by nature. */
  readonly diff: boolean;
  /** True when the setting is expected to differ per member (hostname, member addresses). */
  readonly memberSpecific: boolean;
}

export interface ClusterProjection {
  readonly memberIds: readonly string[];
  readonly sections: readonly { readonly label: string; readonly rows: readonly MemberRow[] }[];
  readonly diffCount: number;
  readonly settingCount: number;
}

export function projectCluster(members: ReadonlyArray<{ id: string; projection: Projection }>): ClusterProjection {
  const memberIds = members.map((m) => m.id);
  const byKey = new Map<string, { section: string; setting: string; origin: Origin; values: Record<string, string> }>();
  const order: string[] = [];
  for (const member of members) {
    for (const section of member.projection.sections) {
      for (const row of section.rows) {
        let entry = byKey.get(row.key);
        if (!entry) {
          entry = { section: row.section, setting: row.setting, origin: row.origin, values: {} };
          byKey.set(row.key, entry);
          order.push(row.key);
        }
        entry.values[member.id] = row.value;
      }
    }
  }
  const bySection = new Map<string, MemberRow[]>();
  let diffCount = 0;
  for (const key of order) {
    const e = byKey.get(key)!;
    const memberSpecific = e.origin === "MEMBER";
    const present = memberIds.map((id) => e.values[id]).filter((v): v is string => v !== undefined);
    const diff = !memberSpecific && (present.length !== memberIds.length || new Set(present).size > 1);
    if (diff) diffCount++;
    const values: Record<string, string> = {};
    for (const id of memberIds) values[id] = e.values[id] ?? "—";
    const rows = bySection.get(e.section) ?? [];
    rows.push({ section: e.section, setting: e.setting, key, origin: e.origin, values, diff, memberSpecific });
    bySection.set(e.section, rows);
  }
  const sections = orderSections(bySection as unknown as Map<string, SettingRow[]>).map((s) => ({
    label: s.label,
    rows: bySection.get(s.label) ?? [],
  }));
  return { memberIds, sections, diffCount, settingCount: order.length };
}

export function filterProjection(sections: readonly Section[], query: string): Section[] {
  const q = query.trim().toLowerCase();
  if (!q) return [...sections];
  return sections
    .map((s) => ({ label: s.label, rows: s.rows.filter((r) => r.setting.toLowerCase().includes(q) || r.value.toLowerCase().includes(q) || s.label.toLowerCase().includes(q)) }))
    .filter((s) => s.rows.length > 0);
}
