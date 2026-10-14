import { useRef, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { lookupPolicy, type PolicyMetadata, type PolicyLookupQuery, type PolicyLookupResult, type PolicyLookupDecision } from "../auth/adminApi";

export function PolicyLookupTab({ policies, policy, selectPolicy, openRule }: {
  policies: PolicyMetadata[]; policy: string; selectPolicy: (id: string) => void; openRule: (policy: string, rule: string) => void;
}) {
  const [query, setQuery] = useState<PolicyLookupQuery>({ source: "", destination: "", service: "tcp/443" });
  const [recent, setRecent] = useState<PolicyLookupResult[]>([]), [error, setError] = useState("");
  const [busy, setBusy] = useState(false); const inFlight = useRef(false);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError("");
    try {
      const result = await lookupPolicy(policy, query);
      if (!["ALLOWED", "DENIED", "PARTIAL", "UNKNOWN"].includes(result.verdict) || !Array.isArray(result.parts)
          || !Array.isArray(result.undecidableRules) || !result.query) throw new Error("Invalid lookup result");
      setRecent(previous => [result, ...previous].slice(0, 10));
    } catch (failure) {
      setError((failure as { status?: number }).status === 429 ? "Lookup limit reached. Retry after one minute."
        : "Lookup could not be completed. Check the inputs and try again.");
    } finally { inFlight.current = false; setBusy(false); }
  };
  return <Box role="tabpanel" aria-label="Lookup">
    <Typography variant="h6">Policy intent lookup</Typography>
    <Typography variant="body2">NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported. This is not observed traffic. Use addresses and zones as displayed in the rule viewer.</Typography>
    <Box component="form" onSubmit={event => void submit(event)} sx={{ my: 2 }}>
      <Stack direction="row" gap={2} flexWrap="wrap">
        <TextField select label="Lookup policy" value={policies.some(p => p.id === policy) ? policy : ""} required sx={{ minWidth: 220 }} onChange={e => selectPolicy(e.target.value)}>
          {policies.map(p => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
        </TextField>
        {([['source', 'Source IPv4 or CIDR'], ['destination', 'Destination IPv4 or CIDR'], ['service', 'Service'],
          ['sourceZone', 'Source zone/interface (optional)'], ['destinationZone', 'Destination zone/interface (optional)'],
          ['target', 'Install-on target reference (optional)'], ['context', 'Device context (optional)']] as const).map(([field, label]) =>
          <TextField key={field} label={label} value={query[field] ?? ""} required={["source", "destination", "service"].includes(field)}
            helperText={field === "service" ? "tcp/443, udp/53 or any" : undefined} inputProps={{ maxLength: 200 }}
            onChange={e => setQuery(previous => ({ ...previous, [field]: e.target.value }))} />)}
        <Button type="submit" disabled={busy || !policy || !query.source || !query.destination || !query.service}>{busy ? "Looking up…" : "Look up policy intent"}</Button>
      </Stack>
    </Box>
    {error && <Typography role="alert">{error}</Typography>}
    {recent.length > 0 && <Typography variant="h6">Recent lookups (this session)</Typography>}
    {recent.map((result, i) => <Box key={i} aria-label={`Lookup result ${i + 1}`} sx={{ border: 1, borderColor: "divider", borderRadius: 2, p: 2, my: 2 }}>
      <Typography variant="body2">{result.query.querySource} → {result.query.queryDestination} · {result.query.queryService}</Typography>
      <Typography variant="caption">Snapshot {result.policyId} · Collected {result.collectedAt}</Typography>
      <Decision decision={result} openRule={rule => openRule(result.policyId, rule)} />
      {result.parts.map((part, n) => <Box key={n} sx={{ mt: 1, pl: 2 }}>
        <Typography variant="body2">{part.querySource} → {part.queryDestination}</Typography>
        <Decision decision={part} openRule={rule => openRule(result.policyId, rule)} />
      </Box>)}
      <Typography variant="body2">{result.limitations}</Typography>
    </Box>)}
  </Box>;
}
function Decision({ decision, openRule }: { decision: PolicyLookupDecision; openRule: (id: string) => void }) {
  return <Box>
    <Chip label={decision.verdict} color={decision.verdict === "ALLOWED" ? "success" : decision.verdict === "DENIED" ? "error" : "warning"} />
    <Typography variant="body2">{decision.lookupReason}</Typography>
    {decision.decidingRule && <Box>
      <Typography variant="caption">{decision.verdict === "UNKNOWN" ? "First proven match after uncertainty (not a verdict)" : "Deciding rule"}</Typography>
      <Button onClick={() => openRule(decision.decidingRule!.ruleId)}>{decision.decidingRule.number} · {decision.decidingRule.name || "Unnamed rule"}</Button>
      <Typography variant="body2">{decision.decidingRule.sectionName} · {decision.decidingRule.action}</Typography>
    </Box>}
    {decision.undecidableRules.map(rule => <Typography key={rule.ruleId} variant="body2">
      UNKNOWN: rule {rule.number} could match ({rule.lookupReason}). <Button onClick={() => openRule(rule.ruleId)}>{rule.name || "View rule"}</Button>
    </Typography>)}
  </Box>;
}
