import { useRef, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { DISABLED_STYLE } from "../shell/disabledStyle";
import { statusWord } from "../shell/presenters";
import { lookupPolicy, type PolicyMetadata, type PolicyLookupQuery, type PolicyLookupResult, type PolicyLookupDecision } from "../auth/adminApi";
import { SnapshotFreshnessChip, isPolicyBusy, POLICY_BUSY_MESSAGE } from "./PolicyRuleViewer";

const LOOKUP_REASON_LABELS: Readonly<Record<string, string>> = {
  FGT_VIP_PRIORITY_UNPROVEN:
    "VIP priority unproven (FGT_VIP_PRIORITY_UNPROVEN): a later VIP policy may take precedence over an earlier ordinary rule",
  FGT_MATCH_VIP_UNPROVEN:
    "VIP destination match unproven (FGT_MATCH_VIP_UNPROVEN): a deny rule with match-vip could match the mapped destination",
};

export function formatLookupReason(reason: string | undefined): string {
  if (!reason) return "";
  return LOOKUP_REASON_LABELS[reason] ?? reason;
}

export function policyOptionLabel(p: PolicyMetadata, policies: readonly PolicyMetadata[]): string {
  const duplicateName = policies.filter(other => other.name === p.name).length > 1;
  if (p.vendor !== "ASA" && p.vendor !== "FGT" && !duplicateName) return p.name;
  const parts: string[] = [p.name];
  const addPart = (value: string | undefined) => {
    const trimmed = value?.trim();
    if (trimmed && !parts.includes(trimmed)) parts.push(trimmed);
  };
  if (p.vendor === "ASA") {
    addPart(p.sourceName);
    if (p.containerName?.trim() !== "single" || parts.length === 1) addPart(p.containerName);
    return parts.join(" · ");
  }
  if (p.vendor === "FGT") {
    const duplicateContainer = policies.filter(other => other.name === p.name && other.containerName === p.containerName).length > 1;
    if (duplicateContainer || !p.containerName?.trim() || p.containerName.trim() === p.name) addPart(p.sourceName);
    else if (p.sourceName?.trim() && policies.some(other => other.vendor === "FGT" && other.sourceName !== p.sourceName)) addPart(p.sourceName);
    addPart(p.containerName);
    if (parts.length === 1) addPart(p.sourceName);
    return parts.join(" · ");
  }
  const duplicateContainer = policies.filter(other => other.name === p.name && other.containerName === p.containerName).length > 1;
  if (duplicateContainer) addPart(p.sourceName);
  addPart(p.containerName);
  if (parts.length === 1) addPart(p.sourceName);
  return parts.join(" · ");
}

export function snapshotVendorContext(p: PolicyMetadata | undefined): string | null {
  if (!p) return null;
  if (p.vendor === "ASA") {
    const ctx = p.containerName?.trim();
    return ctx && ctx !== "single" && ctx !== p.name ? `Cisco ASA · ${ctx}` : "Cisco ASA";
  }
  if (p.vendor === "FGT") {
    const vdom = p.containerName?.trim();
    return vdom && vdom !== p.name ? `FortiGate · ${vdom}` : "FortiGate";
  }
  return null;
}

export function PolicyLookupTab({ policies, policy, selectPolicy, openRule }: {
  policies: PolicyMetadata[]; policy: string; selectPolicy: (id: string) => void; openRule: (policy: string, rule: string) => void;
}) {
  const [query, setQuery] = useState<PolicyLookupQuery>({ source: "", destination: "", service: "tcp/443" });
  const [recent, setRecent] = useState<PolicyLookupResult[]>([]), [error, setError] = useState("");
  const [busy, setBusy] = useState(false); const inFlight = useRef(false);
  const selectedPolicy = policies.find(p => p.id === policy);
  const isAsa = selectedPolicy?.vendor === "ASA";
  const isFgt = selectedPolicy?.vendor === "FGT";
  // What the button is waiting for, in the order the fields appear; the button says it instead of just going grey.
  const missing = [
    !policy && "a policy",
    !query.source && "a source",
    !query.destination && "a destination",
    !query.service && "a service",
    isAsa && !query.sourceZone?.trim() && "an ingress interface",
    isAsa && !query.destinationZone?.trim() && "an egress interface",
  ].filter(Boolean) as string[];
  const reason = missing.length ? `Needs ${missing.join(", ")}` : null;
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); if (inFlight.current || reason !== null) return;
    inFlight.current = true; setBusy(true); setError("");
    try {
      const sourceZone = query.sourceZone?.trim();
      const destinationZone = query.destinationZone?.trim();
      const payload: PolicyLookupQuery = {
        ...query,
        sourceZone: sourceZone || undefined,
        destinationZone: destinationZone || undefined,
      };
      const result = await lookupPolicy(policy, payload);
      if (!["ALLOWED", "DENIED", "PARTIAL", "UNKNOWN"].includes(result.verdict) || !Array.isArray(result.parts)
          || !Array.isArray(result.undecidableRules) || !result.query) throw new Error("Invalid lookup result");
      setRecent(previous => [result, ...previous].slice(0, 10));
    } catch (failure) {
      // The bulkhead's wait is never the inputs' fault: it says retry, not "check the inputs".
      setError(isPolicyBusy(failure) ? POLICY_BUSY_MESSAGE
        : (failure as { status?: number }).status === 429 ? "Lookup limit reached. Retry after one minute."
        : "Lookup could not be completed. Check the inputs and try again.");
    } finally { inFlight.current = false; setBusy(false); }
  };
  const scopeGuidance = isAsa
    ? "Use real (untranslated) addresses and Cisco ASA nameif interfaces as displayed in the rule viewer."
    : isFgt
    ? "Use addresses and FortiGate VDOM interfaces (srcintf / dstintf) as displayed in the rule viewer."
    : "Use addresses and zones as displayed in the rule viewer.";
  const advancedFields = [
    ...(!isAsa ? [
      ["sourceZone", isFgt ? "Ingress interface" : "Source zone", isFgt ? "Optional · FortiGate srcintf (unless rules use any)" : "Optional · zone or interface"],
      ["destinationZone", isFgt ? "Egress interface" : "Destination zone", isFgt ? "Optional · FortiGate dstintf (unless rules use any)" : "Optional · zone or interface"],
    ] as const : []),
    ["target", "Install-on target", "Optional · target reference"],
    ["context", "Device context", "Optional"],
  ] as const;
  const advancedOpen = isFgt
    || Boolean((!isAsa && (query.sourceZone || query.destinationZone)) || query.target || query.context)
    || undefined;
  return <Box role="tabpanel" aria-label="Lookup">
    <Typography variant="h6">Policy intent lookup</Typography>
    <Typography variant="body2">NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported. This is not observed traffic. {scopeGuidance}</Typography>
    <Box component="form" onSubmit={event => void submit(event)} sx={{ my: 2 }}>
      {/* Short labels above the fields (a label that sits in the border never truncates); what each field takes goes in the
          help line under it, so "(optional)" and the format are not squeezed into the label (UI review 2026-10-18). */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))", alignItems: "start" }}>
        <TextField select label="Lookup policy" value={selectedPolicy ? policy : ""} required fullWidth helperText="The stored snapshot to look up in"
          onChange={e => selectPolicy(e.target.value)}>
          {policies.map(p => <MenuItem key={p.id} value={p.id}>{policyOptionLabel(p, policies)}</MenuItem>)}
        </TextField>
        {([['source', 'Source', 'IPv4 or CIDR'], ['destination', 'Destination', 'IPv4 or CIDR'], ['service', 'Service', 'tcp/443, udp/53 or any']] as const).map(([field, label, help]) =>
          <TextField key={field} label={label} value={query[field] ?? ""} required fullWidth
            helperText={help} inputProps={{ maxLength: 200 }}
            onChange={e => setQuery(previous => ({ ...previous, [field]: e.target.value }))} />)}
        {isAsa && ([['sourceZone', 'Ingress interface', 'Required · Cisco ASA nameif'], ['destinationZone', 'Egress interface', 'Required · Cisco ASA nameif']] as const).map(([field, label, help]) =>
          <TextField key={field} label={label} value={query[field] ?? ""} required fullWidth
            helperText={help} inputProps={{ maxLength: 200 }}
            onChange={e => setQuery(previous => ({ ...previous, [field]: e.target.value }))} />)}
      </Box>
      {/* Advanced fields (UI review 2026-11-05, C3): the four optional narrowing fields sit behind one disclosure, so the
          form a reader meets first is policy, source, destination and service. A filled one keeps the disclosure open. */}
      <Box component="details" data-testid="lookup-advanced" sx={{ mt: 2 }}
        open={advancedOpen}>
        <Box component="summary" sx={{ cursor: "pointer", color: "primary.main", fontSize: 13, fontWeight: 500 }}>Advanced fields</Box>
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))", alignItems: "start", mt: 1.5 }}>
          {advancedFields.map(([field, label, help]) =>
            <TextField key={field} label={label} value={query[field] ?? ""} fullWidth
              helperText={help} inputProps={{ maxLength: 200 }}
              onChange={e => setQuery(previous => ({ ...previous, [field]: e.target.value }))} />)}
        </Box>
      </Box>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap" sx={{ mt: 2 }}>
        {/* A real button even when it cannot run, with its reason beside the label (the suffix rule used on every gated control). */}
        <Button type="submit" variant="contained" disableElevation disabled={busy || reason !== null} sx={[{ textTransform: "none", borderRadius: "20px", px: 3 }, DISABLED_STYLE]}>
          {busy ? "Looking up…" : reason ? `Look up policy intent · ${reason}` : "Look up policy intent"}
        </Button>
      </Stack>
    </Box>
    {error && <Typography role="alert">{error}</Typography>}
    {recent.length > 0 && <Typography variant="h6">Recent lookups (this session)</Typography>}
    {recent.map((result, i) => {
      const resultPolicy = policies.find(p => p.id === result.policyId);
      const vendorContext = snapshotVendorContext(resultPolicy);
      return <Box key={i} aria-label={`Lookup result ${i + 1}`} sx={{ border: 1, borderColor: "divider", borderRadius: 2, p: 2, my: 2 }}>
        <Typography variant="body2">{result.query.querySource} → {result.query.queryDestination} · {result.query.queryService}</Typography>
        {/* The answer is only as current as the snapshot it was read from: the policy by name and the collection age with its freshness state. */}
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap" sx={{ my: 0.5 }}>
          <Typography variant="caption">Snapshot: {resultPolicy?.name ?? result.policyId}</Typography>
          {vendorContext && <Typography variant="caption">{vendorContext}</Typography>}
          <SnapshotFreshnessChip at={result.collectedAt} now={new Date()} />
        </Stack>
        <Decision decision={result} openRule={rule => openRule(result.policyId, rule)} />
        {result.parts.map((part, n) => <Box key={n} sx={{ mt: 1, pl: 2 }}>
          <Typography variant="body2">{part.querySource} → {part.queryDestination}</Typography>
          <Decision decision={part} openRule={rule => openRule(result.policyId, rule)} />
        </Box>)}
        <Typography variant="body2">{result.limitations}</Typography>
      </Box>;
    })}
  </Box>;
}
function Decision({ decision, openRule }: { decision: PolicyLookupDecision; openRule: (id: string) => void }) {
  return <Box>
    <Chip label={statusWord(decision.verdict)} color={decision.verdict === "ALLOWED" ? "success" : decision.verdict === "DENIED" ? "error" : "warning"} />
    <Typography variant="body2">{formatLookupReason(decision.lookupReason)}</Typography>
    {decision.decidingRule && <Box>
      <Typography variant="caption">{decision.verdict === "UNKNOWN" ? "First proven match after uncertainty (not a verdict)" : "Deciding rule"}</Typography>
      <Button onClick={() => openRule(decision.decidingRule!.ruleId)}>{decision.decidingRule.number} · {decision.decidingRule.name || "Unnamed rule"}</Button>
      <Typography variant="body2">{decision.decidingRule.sectionName} · {decision.decidingRule.action}</Typography>
    </Box>}
    {decision.undecidableRules.map(rule => <Typography key={rule.ruleId} variant="body2">
      UNKNOWN: rule {rule.number} could match ({formatLookupReason(rule.lookupReason)}). <Button onClick={() => openRule(rule.ruleId)}>{rule.name || "View rule"}</Button>
    </Typography>)}
  </Box>;
}
