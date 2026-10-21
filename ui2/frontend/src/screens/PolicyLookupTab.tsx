import { useRef, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { DISABLED_STYLE } from "../shell/disabledStyle";
import { statusWord } from "../shell/presenters";
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
  // What the button is waiting for, in the order the fields appear; the button says it instead of just going grey.
  const missing = [!policy && "a policy", !query.source && "a source", !query.destination && "a destination", !query.service && "a service"].filter(Boolean) as string[];
  const reason = missing.length ? `Needs ${missing.join(", ")}` : null;
  return <Box role="tabpanel" aria-label="Lookup">
    <Typography variant="h6">Policy intent lookup</Typography>
    <Typography variant="body2">NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported. This is not observed traffic. Use addresses and zones as displayed in the rule viewer.</Typography>
    <Box component="form" onSubmit={event => void submit(event)} sx={{ my: 2 }}>
      {/* Short labels above the fields (a label that sits in the border never truncates); what each field takes goes in the
          help line under it, so "(optional)" and the format are not squeezed into the label (UI review 2026-10-18). */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))", alignItems: "start" }}>
        <TextField select label="Lookup policy" value={policies.some(p => p.id === policy) ? policy : ""} required fullWidth helperText="The stored snapshot to look up in"
          onChange={e => selectPolicy(e.target.value)}>
          {policies.map(p => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
        </TextField>
        {([['source', 'Source', 'IPv4 or CIDR'], ['destination', 'Destination', 'IPv4 or CIDR'], ['service', 'Service', 'tcp/443, udp/53 or any'],
          ['sourceZone', 'Source zone', 'Optional · zone or interface'], ['destinationZone', 'Destination zone', 'Optional · zone or interface'],
          ['target', 'Install-on target', 'Optional · target reference'], ['context', 'Device context', 'Optional']] as const).map(([field, label, help]) =>
          <TextField key={field} label={label} value={query[field] ?? ""} required={["source", "destination", "service"].includes(field)} fullWidth
            helperText={help} inputProps={{ maxLength: 200 }}
            onChange={e => setQuery(previous => ({ ...previous, [field]: e.target.value }))} />)}
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
    <Chip label={statusWord(decision.verdict)} color={decision.verdict === "ALLOWED" ? "success" : decision.verdict === "DENIED" ? "error" : "warning"} />
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
