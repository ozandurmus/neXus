export const fields = ["source", "destination", "port", "protocol", "time", "action", "sourceZone", "destinationZone", "rule", "count"] as const;
export type CsvField = typeof fields[number];
export const required: CsvField[] = ["source", "destination", "port", "protocol"];
const aliases: Record<CsvField, string[]> = {
  source: ["src", "srcip", "sourceip", "sourceaddress", "source"],
  destination: ["dst", "dstip", "destip", "destinationip", "destinationaddress", "destination", "dest"],
  port: ["dstport", "destport", "destinationport", "dport"], protocol: ["proto", "protocol", "ipproto", "transport"],
  time: ["time", "timestamp", "starttime", "eventtime", "devicetime"], action: ["action", "act", "deviceaction"],
  sourceZone: ["srczone", "sourcezone", "srcintf", "sourceinterface"], destinationZone: ["dstzone", "destinationzone", "dstintf", "destinationinterface"],
  rule: ["rule", "ruleid", "rulename", "policyid", "policyname", "policy", "ruleuid"], count: ["count", "eventcount", "flowcount", "logcount"],
};
export function detectMapping(headers: string[]): Partial<Record<CsvField, string>> {
  const mapping: Partial<Record<CsvField, string>> = {};
  for (const field of fields) {
    const matches = headers.filter(h => aliases[field].includes(h.toLowerCase().replace(/[^a-z0-9]/g, "")));
    if (matches.length === 1) mapping[field] = matches[0];
  }
  return mapping;
}
/** Read a bounded prefix only; raw preview remains in this component's memory. */
export function parsePreview(text: string, complete: boolean): { headers: string[]; rows: string[][] } {
  const records: string[][] = []; let row: string[] = [], field = "", quoted = false, closed = false, size = 0;
  const finish = () => { row.push(field); records.push(row); row = []; field = ""; closed = false; size = 0; };
  for (let i = 0; i < text.length && records.length < 21; i++) {
    const c = text[i]; if (++size > 65536 || c === "\0") throw new Error("Invalid CSV or record exceeds 65536 characters.");
    if (quoted) { if (c === '"') { quoted = false; closed = true; } else field += c; continue; }
    if (closed && c === '"') { field += c; quoted = true; closed = false; continue; }
    if (c === ",") { row.push(field); field = ""; closed = false; if (row.length >= 256) throw new Error("Maximum 256 columns."); continue; }
    if (c === "\r" || c === "\n") { finish(); if (c === "\r" && text[i + 1] === "\n") i++; continue; }
    if (closed || c === '"' && field) throw new Error("Invalid CSV quoting.");
    if (c === '"') quoted = true; else field += c;
  }
  if (records.length < 21 && complete) {
    if (quoted) throw new Error("Unterminated quoted field.");
    if (row.length || field || closed) finish();
  }
  const headers = records.shift() ?? []; if (headers[0]) headers[0] = headers[0].replace(/^\uFEFF/, "");
  if (!headers.length || headers.some(h => !h.trim()) || new Set(headers).size !== headers.length) throw new Error("Unique, nonempty CSV headers are required.");
  return { headers, rows: records.slice(0, 20) };
}
